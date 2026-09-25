"""
UPI SMS Transaction Parser + Merchant Category Matcher.

Flow:
  SMS -> Extract transaction info -> Match merchant -> Get category
  -> If no match -> "Miscellaneous" (user can reclassify)

Data sources used:
  1. merchants.json      - Top 300 Indian merchants (local DB)
  2. mcc_codes.json      - greggles/mcc-codes (ISO MCC standard)
User-specific corrections (Layer 1) are stored in the merchant_mappings table and are
looked up by the API layer (routers/transactions.py: find_user_correction) *before* this
module runs. They must not be kept in this process-wide data directory. The key used to
store and look them up is merchant_key() below — the only implementation of it.
"""

import json
import os
import re
from datetime import datetime
from functools import lru_cache
from typing import Any, Dict, List, Optional

# Load data files path relative to this file
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_DIR = os.path.join(BASE_DIR, "data")
MERCHANTS_PATH = os.path.join(DATA_DIR, "merchants.json")
MCC_PATH = os.path.join(DATA_DIR, "mcc_codes.json")
DEBIT_KEYWORDS_PATH = os.path.join(DATA_DIR, "debit_keywords.json")
LEGACY_CORRECTIONS_PATH = os.path.join(DATA_DIR, "user_corrections.json")

MERCHANTS: List[Dict[str, Any]] = []
MCC_CODES: List[Dict[str, Any]] = []
# (compiled whole-word pattern, keyword, category, rule order) - built from debit_keywords.json
DEBIT_KEYWORD_RULES: List[tuple] = []
DEBIT_KEYWORD_CONFIDENCE = "medium"


def compile_keyword_rules(rules: List[Dict[str, Any]]) -> List[tuple]:
    """
    Turn the JSON rule list into whole-word patterns. A keyword matches only as complete
    words ("SALOON" in "MC SALOON", not "PROVISION" for "VI"); multi-word keywords match
    across any spacing or punctuation ("general store" also matches "GENERAL-STORE").
    """
    compiled: List[tuple] = []
    for order, rule in enumerate(rules):
        for keyword in rule.get("keywords", []):
            words = [re.escape(w) for w in keyword.upper().split()]
            if not words:
                continue
            pattern = re.compile(r"(?<![A-Z0-9])" + r"\s+".join(words) + r"(?![A-Z0-9])")
            compiled.append((pattern, keyword, rule["category"], order))
    return compiled


def load_data() -> None:
    """
    Load the static merchant and MCC data files.
    """
    global MERCHANTS, MCC_CODES, DEBIT_KEYWORD_RULES, DEBIT_KEYWORD_CONFIDENCE
    try:
        # Remove the old shared corrections file if an older deployment left it
        # on disk. It was never user-scoped and is no longer a data source.
        if os.path.exists(LEGACY_CORRECTIONS_PATH):
            os.remove(LEGACY_CORRECTIONS_PATH)

        if os.path.exists(MERCHANTS_PATH):
            with open(MERCHANTS_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
                MERCHANTS = data.get("merchants", [])
                print(f"[OK] Loaded {len(MERCHANTS)} merchants")

        if os.path.exists(MCC_PATH):
            with open(MCC_PATH, "r", encoding="utf-8") as f:
                MCC_CODES = json.load(f)
                print(f"[OK] Loaded {len(MCC_CODES)} MCC codes")

        if os.path.exists(DEBIT_KEYWORDS_PATH):
            with open(DEBIT_KEYWORDS_PATH, "r", encoding="utf-8") as f:
                data = json.load(f)
            DEBIT_KEYWORD_CONFIDENCE = data.get("confidence", "medium")
            DEBIT_KEYWORD_RULES = compile_keyword_rules(data.get("rules", []))
            print(f"[OK] Loaded {len(DEBIT_KEYWORD_RULES)} debit keywords")

    except Exception as err:
        print(f"Error loading data files: {err}")


# Initialize data on import
load_data()


# ─────────────────────────────────────────────
# 2. SMS PARSER
# Extracts: amount, merchant, type (debit/credit), UPI ref, bank
# ─────────────────────────────────────────────

def is_generic_bank_text(text: str) -> bool:
    """
    Filter out non-merchant text like "YOUR A/C", "SBI BANK", etc.
    """
    generic_terms = [
        'YOUR', 'A/C', 'ACCOUNT', 'BANK', 'BALANCE', 'AVAILABLE',
        'HDFC', 'SBI', 'ICICI', 'AXIS', 'KOTAK', 'YES BANK',
        'PAYMENT', 'TRANSFER', 'TRANSACTION', 'UPI', 'IMPS', 'NEFT'
    ]
    return any(term in text for term in generic_terms)


def parse_sms(sms: str) -> Optional[Dict[str, Any]]:
    """
    Parse a raw bank SMS and return structured transaction data.
    """
    if not sms or not isinstance(sms, str):
        return None

    sms_lower = sms.lower()
    spam_patterns = [
        r"save\s+(?:rs\.?|inr|₹)",
        r"earn\s+up\s+to",
        r"cashback\s+every",
        r"apply\s+now",
        r"pre-approved",
        r"pre\s+approved",
        r"loan\s+offer",
        r"get\s+(?:flat|up\s+to)\s+(?:rs\.?|inr|₹|\d+%)",
        r"win\s+up\s+to",
        r"lifetime\s+free",
        r"at\s+no\s+extra\s+charge",
        r"play\s+\d+\+\s+games",
        r"pro\s+pass",
        r"voucher",
        r"coupon\s+code",
        r"promo\s+code",
        r"discount\s+on",
        r"mandate\s+collect\s+request",
        r"request\s+for\s+blocking\s+of\s+funds",
        r"otp\s+is",
        r"verification\s+code",
        r"do\s+not\s+share\s+(?:this\s+)?otp",
        r"claim\s+now",
        r"offer\s+ends",
        r"congratulations",
        r"credit\s+card\s+limit",
        r"personal\s+loan"
    ]
    for pat in spam_patterns:
        if re.search(pat, sms_lower):
            return None

    normalized = sms.upper().strip()

    parsed = {
        "raw": sms,
        "amount": None,
        "merchant_raw": None,
        "type": None,       # 'debit' | 'credit'
        "upi_ref": None,
        "bank": None,
        "date": None,
    }

    # Extract amount (standalone pass)
    amount_match = re.search(r"(?:RS\.?|INR|₹)\s*([\d,]+(?:\.\d{1,2})?)", normalized, re.IGNORECASE)
    if amount_match:
        try:
            parsed["amount"] = float(amount_match.group(1).replace(",", ""))
        except ValueError:
            pass

    # Extract UPI reference number
    upi_ref_match = re.search(
        r"(?:UPI\s*(?:REF|TXNID|ID|NO)?|REF\.?\s*NO\.?|TXN\s*(?:ID|NO)?)[:\s\-]*([\d]{6,20})",
        normalized,
        re.IGNORECASE
    )
    if upi_ref_match:
        parsed["upi_ref"] = upi_ref_match.group(1)

    # Extract date
    date_match = re.search(
        r"(\d{1,2}[-\/]\d{1,2}[-\/]\d{2,4}|\d{1,2}\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\s*\d{2,4})",
        normalized,
        re.IGNORECASE
    )
    if date_match:
        parsed["date"] = date_match.group(1)

    # Detect credit vs debit
    if re.search(r"CREDITED|RECEIVED|CREDIT", normalized, re.IGNORECASE):
        parsed["type"] = "credit"
    elif re.search(r"DEBITED|DEDUCTED|SENT|PAID|PURCHASE|SPENT", normalized, re.IGNORECASE):
        parsed["type"] = "debit"

    # Extract merchant name from common UPI SMS info fields
    info_patterns = [
        r"(?:info|remarks?|particulars?|narration)[:\s\-]+(?:UPI[\-\/])?([A-Z][A-Z0-9\s\.\-@&]{2,40}?)(?:[\-\/][\w]+)?(?:\s+ref|\s+upi|\.|,|$)",
        r"(?:to|towards|for|merchant)[:\s]+([A-Z][A-Z0-9\s\.\-@&]{2,40}?)(?:\s+via|\s+on|\s+ref|\.|,|$)",
        r"UPI[\-\/]([\w\s\.\-@&]+?)[\-\/]",
        r"(?:paid to|sent to|transferred to)[:\s]+([A-Z][A-Z0-9\s\.\-@&]{2,40}?)(?:\s+via|\s+on|\s+ref|\.|,|$)"
    ]

    for pattern in info_patterns:
        match = re.search(pattern, normalized, re.IGNORECASE)
        if match and match.group(1):
            candidate = match.group(1).strip()
            if not is_generic_bank_text(candidate):
                parsed["merchant_raw"] = candidate
                break

    return parsed


# ─────────────────────────────────────────────
# 3. MERCHANT MATCHING ENGINE
# Priority: User DB mapping (looked up by the API layer, see merchant_key) -> Merchant DB -> MCC Codes -> Miscellaneous
# ─────────────────────────────────────────────

@lru_cache(maxsize=8192)
def normalize_name(name: str) -> str:
    """
    Normalize merchant name for comparison.
    Removes special chars, extra spaces, common suffixes.
    """
    name_upper = name.upper()
    name_upper = re.sub(r"\bPVT\.?\s*LTD\.?\b", "", name_upper)
    name_upper = re.sub(r"\bLIMITED\b", "", name_upper)
    name_upper = re.sub(r"\bPRIVATE\b", "", name_upper)
    name_upper = re.sub(r"\bINDIA\b", "", name_upper)
    name_upper = re.sub(r"\bTECHNOLOGIES\b", "", name_upper)
    name_upper = re.sub(r"[^A-Z0-9\s]", "", name_upper)
    name_upper = re.sub(r"\s+", " ", name_upper)
    return name_upper.strip()


# Order / reference suffixes that make otherwise-identical merchants look different:
# "SWIGGY*ORDER8827" and "SWIGGY*ORDER9931" are the same merchant. Each pattern only
# strips a *trailing* piece that follows a separator, and never the whole string.
_REF_SEP = r"[\s*#/_:\-]+"
_REF_STRIP_PATTERNS = (
    # separator + a marker word + an ID containing a digit:  "*ORDER8827", " TXN 12AB34", "-INV#0042"
    re.compile(
        _REF_SEP
        + r"(?:ORDER\s*ID|ORDERID|ORDER|ORD|TXN\s*ID|TXNID|TXN|TRANSACTION|TRN|REFERENCE|REF\s*NO|REFNO|REF|INVOICE|INV|BILL|BOOKING)"
        + r"[\s#:._\-]*[A-Z0-9]*\d[A-Z0-9]*\s*$"
    ),
    # separator + a bare number of 6+ digits:  " 4829173"
    re.compile(_REF_SEP + r"\d{6,}\s*$"),
    # descriptor-style "*" + an alphanumeric reference of 5+ characters with a digit:  "AMAZON*2B7Y91H"
    re.compile(r"\*\s*(?=[A-Z0-9]*\d)[A-Z0-9]{5,}\s*$"),
)


def merchant_key(name: Optional[str]) -> str:
    """
    The one key that identifies a merchant for user corrections (Layer 1).

    Both writing a correction (recategorize) and looking one up at ingest must call this,
    so a merchant always maps to the same key.

    Rule, applied to the upper-cased text:
      1. UPI handles (anything containing "@") are left alone — the digits in
         "9876543210@ybl" identify the payee; they are not an order number.
      2. Repeatedly (max 3 times) remove ONE trailing order/reference suffix:
           a. separator + marker word (ORDER, ORD, ORDERID, TXN, TXNID, TRANSACTION, TRN,
              REF, REFNO, REFERENCE, INV, INVOICE, BILL, BOOKING) + an ID containing a digit
              -> "SWIGGY*ORDER8827", "SWIGGY ORDER 8827", "SHOP-INV#0042"
           b. separator + a bare number of 6 or more digits
              -> "BIGBASKET 4829173"
           c. "*" + an alphanumeric reference of 5+ characters that contains a digit
              -> "AMAZON*2B7Y91H"
         A separator is any of whitespace * # / _ : -.
      3. If stripping would leave nothing, the original text is kept.
      4. The result goes through normalize_name (punctuation and company suffixes removed).

    Digits glued to a name ("7ELEVEN", "3M") and short numbers ("SECTOR 21") are never
    stripped, so different merchants are not merged.
    """
    text = (name or "").strip().upper()
    if not text:
        return ""
    if "@" not in text:
        for _ in range(3):
            stripped = text
            for pattern in _REF_STRIP_PATTERNS:
                candidate = pattern.sub("", stripped, count=1).strip()
                if candidate and candidate != stripped:
                    stripped = candidate
                    break
            if stripped == text:
                break
            text = stripped
    return normalize_name(text)


# Containment ("SWIGGY" inside "SWIGGY ORDER 9931") only counts when the contained text is
# at least this many characters long (spaces ignored) AND lines up with whole words.
# 3 keeps real short brands (OLA, KFC, SBI, LIC); it drops 2-letter tokens such as "VI",
# which used to match inside "PROVISION" and return Telecom at high confidence. A 2-letter
# name still matches when it is the entire merchant text (equality is not containment).
MIN_CONTAINMENT_CHARS = 3


@lru_cache(maxsize=8192)
def _word_form(name: str) -> str:
    """Like normalize_name, but separators become spaces so word boundaries survive."""
    n = name.upper()
    n = re.sub(r"[\u2019']", "", n)  # DOMINO'S -> DOMINOS
    n = re.sub(r"\bPVT\.?\s*LTD\.?\b", "", n)
    n = re.sub(r"\bLIMITED\b", "", n)
    n = re.sub(r"\bPRIVATE\b", "", n)
    n = re.sub(r"\bINDIA\b", "", n)
    n = re.sub(r"\bTECHNOLOGIES\b", "", n)
    n = re.sub(r"[^A-Z0-9]+", " ", n)
    return n.strip()


def _has_whole_words(short: str, long: str) -> bool:
    return re.search(r"(?<![A-Z0-9])" + re.escape(short) + r"(?![A-Z0-9])", long) is not None


def match_strength(query: str, target: str) -> tuple:
    """
    Score 0-100 and how it was reached:
      "exact"          - same text once normalised
      "target_in_text" - the target (e.g. a brand) appears as whole words inside the query
      "text_in_target" - the query is only a fragment of the target ("CAFE" in "CAFE COFFEE DAY")
      "overlap"        - shared words
    """
    q = normalize_name(query)
    t = normalize_name(target)

    if q == t:
        return 100, "exact"

    qw, tw = _word_form(query), _word_form(target)
    if qw and tw:
        if len(qw) <= len(tw):
            short, long_, kind = qw, tw, "text_in_target"
        else:
            short, long_, kind = tw, qw, "target_in_text"
        if len(short.replace(" ", "")) >= MIN_CONTAINMENT_CHARS and short in long_ and _has_whole_words(short, long_):
            return 90, kind

    # Check word overlap
    q_words = [w for w in q.split(" ") if len(w) > 2]
    t_words = [w for w in t.split(" ") if len(w) > 2]

    if not q_words or not t_words:
        return 0, "none"

    overlap = len([w for w in q_words if w in t_words])
    if overlap > 0:
        return round((overlap / max(len(q_words), len(t_words))) * 80), "overlap"

    return 0, "none"


def fuzzy_score(query: str, target: str) -> int:
    """Match score 0-100 (see match_strength)."""
    return match_strength(query, target)[0]


def match_merchant_db(merchant_raw: str) -> Optional[Dict[str, Any]]:
    """
    Match against Top 300 Indian Merchants DB.

    "merchant" stays the text the bank sent. The brand's clean name goes in
    "merchant_display", and only when the brand itself is in the text (an exact or
    whole-word match) - a text that is merely a fragment of a brand name is not renamed,
    and is capped at medium confidence.
    """
    best_match = None
    best_rank = (0, False, 0)
    best_kind = "none"
    THRESHOLD = 60

    for merchant in MERCHANTS:
        for candidate in [merchant.get("name", "")] + list(merchant.get("aliases", [])):
            score, kind = match_strength(merchant_raw, candidate)
            # Best score first, then a brand found in the text over a mere fragment, then the
            # longest (most specific) brand string: "Sulekha Parking" beats "Sulekha".
            rank = (score, kind in ("exact", "target_in_text"), len(_word_form(candidate)))
            if rank > best_rank:
                best_rank, best_match, best_kind = rank, merchant, kind

        if best_rank[0] == 100:
            break

    best_score, strong, _specificity = best_rank
    if best_match and best_score >= THRESHOLD:
        return {
            "category": best_match["category"],
            "subcategory": best_match.get("subcategory"),
            "merchant": merchant_raw,
            "merchant_display": best_match["name"] if strong and best_score >= 90 else None,
            "source": "merchant_db",
            "confidence": "high" if best_score >= 90 and strong else "medium",
            "score": best_score,
        }

    return None


def mcc_description_to_category(description: str) -> str:
    """
    Map MCC description to our app's category names.
    """
    desc = description.upper()
    if re.search(r"GROCERY|SUPERMARKET|FOOD STORE", desc):
        return "Groceries"
    if re.search(r"RESTAURANT|EATING|FAST FOOD|PIZZA|BURGER|CAFE", desc):
        return "Food & Dining"
    if re.search(r"AIRLINE|AVIATION|AIRPORT|HOTEL|MOTEL|LODGING", desc):
        return "Travel & Hotels"
    if re.search(r"FUEL|PETROL|GAS STATION|SERVICE STATION", desc):
        return "Fuel"
    if re.search(r"PHARMACY|DRUG STORE|MEDICAL|HEALTH|HOSPITAL|CLINIC|DOCTOR", desc):
        return "Healthcare"
    if re.search(r"ELECTRIC|UTILITY|WATER|GAS", desc):
        return "Utilities & Bills"
    if re.search(r"TELECOM|PHONE|WIRELESS|MOBILE", desc):
        return "Telecom & Recharge"
    if re.search(r"ENTERTAINMENT|MOVIE|CINEMA|AMUSEMENT", desc):
        return "Entertainment"
    if re.search(r"EDUCATION|SCHOOL|COLLEGE|UNIVERSITY", desc):
        return "Education"
    if re.search(r"INSURANCE|LIFE INSURANCE", desc):
        return "Finance & Insurance"
    if re.search(r"TRANSPORT|TAXI|VEHICLE|PARKING|BUS|TRAIN", desc):
        return "Transportation"
    if re.search(r"CLOTHING|APPAREL|SHOE|FASHION", desc):
        return "Shopping"
    if re.search(r"ELECTRONIC|COMPUTER|SOFTWARE", desc):
        return "Electronics"
    return "Miscellaneous"


def match_mcc_codes(merchant_raw: str) -> Optional[Dict[str, Any]]:
    """
    Match against MCC codes (greggles/mcc-codes).
    """
    if not MCC_CODES:
        return None

    normalized = normalize_name(merchant_raw)
    best_match = None
    best_score = 0

    for mcc in MCC_CODES:
        description = mcc.get("edited_description") or mcc.get("combined_description") or ""
        score = fuzzy_score(normalized, description)
        if score > best_score:
            best_score = score
            best_match = mcc

    if best_match and best_score >= 50:
        desc = best_match.get("edited_description") or best_match.get("combined_description") or ""
        return {
            "category": mcc_description_to_category(desc),
            "subcategory": desc,
            "merchant": merchant_raw,
            "source": "mcc_codes",
            "mcc": best_match.get("mcc"),
            "confidence": "low",
            "score": best_score,
        }

    return None


def match_debit_keywords(merchant_raw: Optional[str]) -> Optional[Dict[str, Any]]:
    """
    Keyword rules for debit merchants (categorizer/data/debit_keywords.json).

    Whole-word, case-insensitive. If several keywords match, the longest wins ("general
    store" beats "store"); on a tie the category listed first in the file wins.
    """
    if not merchant_raw or not DEBIT_KEYWORD_RULES:
        return None

    text = _word_form(merchant_raw)
    best = None
    for pattern, keyword, category, order in DEBIT_KEYWORD_RULES:
        if pattern.search(text):
            rank = (len(keyword), -order)
            if best is None or rank > best[0]:
                best = (rank, keyword, category)
    if best is None:
        return None

    return {
        "category": best[2],
        "subcategory": None,
        "merchant": merchant_raw,
        "merchant_display": None,
        "source": "keyword_rules",
        "confidence": DEBIT_KEYWORD_CONFIDENCE,
        "keyword": best[1],
    }


def fallback_category(merchant_raw: Optional[str]) -> Dict[str, Any]:
    """
    Fallback to Miscellaneous when no matches are found.
    """
    return {
        "category": "Miscellaneous",
        "subcategory": None,
        "merchant": merchant_raw or "Unknown",
        "source": "fallback",
        "confidence": "none",
    }


# ─────────────────────────────────────────────
# 4. MAIN CATEGORIZER FUNCTION
# ─────────────────────────────────────────────

CANONICAL_CATEGORY_MAP = {
    # Debit / Expense categories
    "food": "Food & Dining",
    "food & dining": "Food & Dining",
    "food and dining": "Food & Dining",
    "dining": "Food & Dining",
    "travel": "Transportation",
    "travel & hotels": "Travel & Hotels",
    "travel and hotels": "Travel & Hotels",
    "hotels": "Travel & Hotels",
    "hotel": "Travel & Hotels",
    "transportation": "Transportation",
    "cab": "Transportation",
    "fuel": "Fuel",
    "bills": "Utilities & Bills",
    "utilities": "Utilities & Bills",
    "utilities & bills": "Utilities & Bills",
    "groceries": "Groceries",
    "shopping": "Shopping",
    "healthcare": "Healthcare",
    "entertainment": "Entertainment",
    "education": "Education",
    "subscriptions": "Subscriptions",
    "telecom & recharge": "Telecom & Recharge",
    "recharge": "Telecom & Recharge",
    "finance": "Finance & Insurance",
    "finance & insurance": "Finance & Insurance",
    "personal care": "Personal Care",
    "rent": "Rent",
    "transfer": "Transfer",
    "miscellaneous": "Other",
    "other": "Other",
    "needs review": "Needs Review",
    "needs_review": "Needs Review",

    # 8 Canonical Credit categories
    "salary": "Salary",
    "refund": "Refund",
    "reversed": "Refund",
    "reversal": "Refund",
    "interest": "Interest",
    "interest credited": "Interest",
    "bank deposit": "Bank Deposit",
    "bank_deposit": "Bank Deposit",
    "deposit": "Bank Deposit",
    "investment return": "Investment Return",
    "investment_return": "Investment Return",
    "investment": "Investment Return",
    "dividend": "Investment Return",
    "reimbursement": "Reimbursement",
    "cashback": "Cashback",
    "reward": "Cashback",
    "rewards": "Cashback",
    "other credit": "Other Credit",
    "other_credit": "Other Credit",
}


CREDIT_KEYWORD_RULES = [
    (r"\b(salary|payroll|stipend|wages)\b", "Salary"),
    (r"\b(refund|refunded|reversed|reversal)\b", "Refund"),
    (r"\b(interest\s+credited|interest|int\.pd|int\s+credit)\b", "Interest"),
    (r"\b(bank\s+deposit|cash\s+deposit|cdm\s+deposit|cheque\s+deposit|deposit)\b", "Bank Deposit"),
    (r"\b(investment\s+return|investment\s+returns|dividend|dividends|redemption|mf\s+return)\b", "Investment Return"),
    (r"\b(reimbursement|reimbursed|claim\s+approved|expense\s+claim)\b", "Reimbursement"),
    (r"\b(cashback|cash\s+back|reward|rewards)\b", "Cashback"),
    (r"\b(other\s+credit|credit\s+adjustment)\b", "Other Credit"),
]


def match_credit_keywords(merchant_raw: Optional[str]) -> Optional[Dict[str, Any]]:
    """
    Layer 4: Match credit-side keyword patterns against raw merchant / description text.

    Args:
        merchant_raw (Optional[str]): Raw merchant or transaction description string.

    Returns:
        Optional[Dict[str, Any]]: Categorization result dict if matched, None otherwise.
    """
    if not merchant_raw:
        return None

    raw_lower = merchant_raw.lower().strip()

    for pattern, cat in CREDIT_KEYWORD_RULES:
        if re.search(pattern, raw_lower, re.IGNORECASE):
            return {
                "category": cat,
                "subcategory": None,
                "merchant": merchant_raw,
                "source": "keyword_rules",
                "confidence": "high",
            }

    return None


def normalize_category_name(cat: Optional[str]) -> str:
    """
    Normalizes a category string to its canonical title-cased name.

    Args:
        cat (Optional[str]): Input category string.

    Returns:
        str: Canonical category name, or 'Other' if empty/unrecognized.
    """
    if not cat:
        return "Other"
    key = cat.strip().lower()
    return CANONICAL_CATEGORY_MAP.get(key, cat.strip())


def categorize_transaction(
    merchant_raw: Optional[str | Dict[str, Any]], transaction_type: Optional[str] = None
) -> Dict[str, Any]:
    """
    Run the categorization cascade using the parsed merchant value.

    This function runs layers 2-5 only. Layer 1 (the user's own corrections) needs the
    database, so the caller checks it first: routers/transactions.py ingest_sms calls
    find_user_correction() and only reaches this function when that finds nothing.

    Priority layers, in the order they are tried overall:
    1. User corrections from merchant_mappings (NOT here — see above)
    2. Credit keyword matching (Salary, Refund, Interest, Cashback, etc.)
    3. Top 300 Indian Merchants DB matching
    3b. Debit keyword rules from debit_keywords.json (skipped for credits)
    4. MCC Code matching
    5. Fallback to Miscellaneous

    "merchant" in the result is always the text that was passed in. A matched brand's clean
    name is returned separately as "merchant_display" (None when there was no brand match).

    Args:
        merchant_raw (Optional[str | Dict[str, Any]]): Parsed merchant text/description,
            or a parsed transaction dict from legacy scratch tests.

    Returns:
        Dict[str, Any]: Enriched categorization metadata.
    """
    if isinstance(merchant_raw, dict):
        merchant_raw = (
            merchant_raw.get("merchant_raw")
            or merchant_raw.get("merchant")
            or merchant_raw.get("raw")
        )
    is_credit = (transaction_type or "").strip().lower() == "credit"

    category_result = None

    if merchant_raw:
        # Layer 2: Credit keyword matching
        category_result = match_credit_keywords(merchant_raw)

        # Layer 3: Match against Top 300 Indian Merchants DB
        if not category_result:
            category_result = match_merchant_db(merchant_raw)

        # Layer 3b: debit keyword rules (a debit category on a credit would be rejected later)
        if not category_result and not is_credit:
            category_result = match_debit_keywords(merchant_raw)

        # Layer 4: Match against MCC codes
        if not category_result:
            category_result = match_mcc_codes(merchant_raw)

    if not category_result:
        category_result = fallback_category(merchant_raw)

    if category_result and "category" in category_result:
        category_result["category"] = normalize_category_name(category_result["category"])

    category_result["merchant"] = merchant_raw or "Unknown"
    category_result.setdefault("merchant_display", None)
    category_result["categorized_at"] = datetime.utcnow().isoformat() + "Z"
    return category_result


def process_upi_sms(sms: str) -> Optional[Dict[str, Any]]:
    """
    Parse SMS and categorize in a single call.
    """
    parsed = parse_sms(sms)
    if not parsed:
        return None
    return {**parsed, **categorize_transaction(parsed.get("merchant_raw"))}


# ─────────────────────────────────────────────
# 5. BATCH PROCESSING
# ─────────────────────────────────────────────

# ─────────────────────────────────────────────
# 6. BATCH PROCESSING
# ─────────────────────────────────────────────

def process_batch(sms_list: List[str]) -> List[Dict[str, Any]]:
    """
    Process a batch of SMS messages.
    """
    results = [process_upi_sms(sms) for sms in sms_list]
    return [r for r in results if r is not None]


# ─────────────────────────────────────────────
# 7. DEMO / TEST
# ─────────────────────────────────────────────

def run_demo() -> None:
    """
    Run a diagnostic demo to test parsing and categorization.
    """
    print("\n" + "=" * 50)
    print("       UPI SMS Transaction Categorizer - Demo")
    print("=" * 50 + "\n")

    test_sms_list = [
        "Rs.349 debited from SBI A/c XX1234 on 09-07-26. Info: UPI-SWIGGY-Swiggy Order. Avail Bal: Rs.12,456.78",
        "Your A/c XXXX5678 debited by Rs.1,299 on 09Jul26. UPI Ref 987654321. Info: UPI-NETFLIX-Netflix Subscription",
        "Dear Customer, Rs.500.00 has been debited from your account. Merchant: BPCL PETROL PUMP. Ref: 112233445",
        "Sent Rs 150 to ZOMATO INDIA PVT LTD via UPI on 09/07/2026. UPI Ref: 445566778",
        "INR 2500 paid to IRCTC via UPI. Txn ID: 998877665544. Your train ticket is confirmed.",
        "Rs.89 debited. Info: UPI-SPOTIFY-Monthly Plan. Ref No: 554433221",
        "Payment of Rs.45 to UNKNOWN KIRANA SHOP via UPI successful. Ref: 667788990",
        "Rs.12,000 credited to your account from HDFC SALARY. Ref: 223344556",
    ]

    print("Processing SMS messages...\n")

    for i, sms in enumerate(test_sms_list):
        result = process_upi_sms(sms)
        if result:
            print(f"[{i + 1}] SMS: \"{sms[:60]}...\"")
            print(f"     Amount   : Rs. {result.get('amount') or 'N/A'}")
            print(f"     Merchant : {result.get('merchant') or 'Unknown'}")
            print(f"     Category : {result.get('category')}" + (f" -> {result['subcategory']}" if result.get('subcategory') else ""))
            print(f"     Source   : {result.get('source')} ({result.get('confidence')} confidence)")
            print(f"     Type     : {result.get('type') or 'unknown'}")
            print()

if __name__ == "__main__":
    run_demo()
