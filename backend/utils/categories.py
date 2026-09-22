"""
Canonical list of categories for SmartSpend.
Single source of truth for the backend and the Android app.
"""

from typing import List, Optional

# 8 Canonical Credit Categories
CREDIT_CATEGORIES: List[str] = [
    "Salary",
    "Refund",
    "Interest",
    "Bank Deposit",
    "Investment Return",
    "Reimbursement",
    "Cashback",
    "Other Credit",
]

# Canonical Debit / Expense Categories
DEBIT_CATEGORIES: List[str] = [
    "Food & Dining",
    "Groceries",
    "Transportation",
    "Shopping",
    "Entertainment",
    "Utilities & Bills",
    "Healthcare",
    "Education",
    "Travel & Hotels",
    "Fuel",
    "Subscriptions",
    "Telecom & Recharge",
    "Finance & Insurance",
    "Personal Care",
    "Rent",
    "Transfer",
    "Other",
]

CATEGORIES: List[str] = DEBIT_CATEGORIES + CREDIT_CATEGORIES

LEGACY_CATEGORY_MAPPING = {
    "food & dining": "Food & Dining",
    "groceries": "Groceries",
    "transportation": "Transportation",
    "shopping": "Shopping",
    "entertainment": "Entertainment",
    "utilities": "Utilities & Bills",
    "utilities & bills": "Utilities & Bills",
    "telecom & recharge": "Telecom & Recharge",
    "healthcare": "Healthcare",
    "education": "Education",
    "travel": "Travel & Hotels",
    "rent": "Rent",
    "transfer": "Transfer",
    "investment": "Investment Return",
    "salary": "Salary",
    "refund": "Refund",
    "miscellaneous": "Other",
    "other": "Other",
}


def validate_category_matches_type(category: Optional[str], transaction_type: Optional[str]) -> bool:
    """
    Validates whether a transaction category is compatible with the transaction type ('debit' or 'credit').

    Rules:
    - Credit categories (Salary, Refund, Interest, Bank Deposit, Investment Return, Reimbursement, Cashback, Other Credit)
      cannot be assigned to debit transactions.
    - Debit categories (Food & Dining, Groceries, Shopping, Transportation, Entertainment, Utilities & Bills, Healthcare,
      Education, Travel & Hotels, Fuel, Subscriptions, Telecom & Recharge, Finance & Insurance, Personal Care, Rent, etc.)
      cannot be assigned to credit transactions.
    - Neutral / placeholder categories (Transfer, Needs Review, Miscellaneous, Other) are permitted for either type.

    Args:
        category (Optional[str]): Category name to validate (raw or normalized).
        transaction_type (Optional[str]): Transaction type ('debit' or 'credit').

    Returns:
        bool: True if category matches transaction type or is neutral; False on mismatch.
    """
    if not category or not transaction_type:
        return True

    from categorizer.transaction_categorizer import normalize_category_name

    norm_cat = normalize_category_name(category).strip().lower()
    raw_cat = category.strip().lower()
    tx_type = transaction_type.strip().lower()

    # Neutral categories allowed for both debit and credit
    neutral_cats = {
        "transfer",
        "needs review",
        "needs_review",
        "miscellaneous",
        "other",
        "unassigned",
    }
    if norm_cat in neutral_cats or raw_cat in neutral_cats:
        return True

    credit_set = {c.lower() for c in CREDIT_CATEGORIES}
    debit_set = {c.lower() for c in DEBIT_CATEGORIES if c.lower() not in neutral_cats}
    debit_set.update({
        "food", "transport", "travel", "utilities", "bills", "dining", "cab",
        "food and dining", "utilities and bills", "telecom", "recharge",
        "finance", "electronics", "personal care",
    })

    if tx_type == "credit":
        # Disallow debit categories on credit transactions
        if norm_cat in debit_set or raw_cat in debit_set:
            return False
    elif tx_type == "debit":
        # Disallow credit categories on debit transactions
        if norm_cat in credit_set or raw_cat in credit_set:
            return False

    return True
