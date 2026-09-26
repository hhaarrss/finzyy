"""
Layer 3/4 matching fixes.

A - containment needs whole words and a minimum length ("provision" is not "VI").
B - the merchant text is never replaced by a brand name; the brand goes in merchant_display.
C - debit keyword rules from categorizer/data/debit_keywords.json.

Sections A (matching) and C (keywords) need no database. Section B ingests through the real
endpoint and uses database.engine like the other suites.
"""

import json
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from sqlalchemy import delete, select

from categorizer import transaction_categorizer as cat
from categorizer.transaction_categorizer import categorize_transaction, match_merchant_db, merchant_key
from database import AsyncSessionLocal, Base, engine
from models.merchant_mapping import MerchantMapping
from models.transaction import Transaction
from models.user import User
from routers.transactions import edit_transaction, ingest_sms, recategorize_transaction
from schemas.sms import SMSIngestionRequest
from schemas.transaction import CorrectionRequest, TransactionUpdate

# Brands whose only short alias (under 3 characters) can no longer be found *inside* a longer
# text. They still match when the merchant text is exactly that alias, and every full brand
# name still matches. Listed so any change to this set is a conscious one.
KNOWN_SHORT_ALIAS_LOSSES = {
    ("Fi Money", "FI"), ("Flipkart", "FK"), ("H&M", "H&M"), ("IndiGo", "6E"), ("Vi (Vodafone Idea)", "VI"),
}


def debit(text):
    return categorize_transaction(text, "debit")


class TestFixAWholeWordContainment(unittest.TestCase):
    def test_provision_is_not_telecom(self):
        for text in ["provision", "PROVISION STORE", "Sri Ram Provisions"]:
            r = debit(text)
            self.assertNotEqual(r["category"], "Telecom & Recharge", text)
            self.assertEqual(r["category"], "Groceries", text)

    def test_letters_inside_a_word_never_match_a_brand(self):
        self.assertIsNone(match_merchant_db("PROVISION"))
        self.assertIsNone(match_merchant_db("TRADERS"))  # used to match TRADE INDIA

    def test_brand_as_whole_words_inside_a_longer_text_still_matches(self):
        for text in ["SWIGGY*ORDER9931", "PAYMENT TO ZOMATO", "UBER INDIA 12345 MUMBAI", "DOMINO'S PIZZA"]:
            self.assertIsNotNone(match_merchant_db(text), text)

    def test_short_brand_still_matches_when_it_is_the_whole_text(self):
        self.assertEqual(match_merchant_db("VI")["category"], "Telecom & Recharge")
        self.assertEqual(match_merchant_db("OLA")["category"], "Transportation")  # 3 letters: allowed in longer text
        self.assertIsNotNone(match_merchant_db("OLA CABS BANGALORE"))

    def test_fragment_of_a_brand_is_medium_confidence_and_not_renamed(self):
        r = match_merchant_db("CAFE")  # "CAFE" is only part of "Cafe Coffee Day"
        self.assertEqual(r["confidence"], "medium")
        self.assertIsNone(r["merchant_display"])

    def test_every_merchant_and_alias_still_matches_its_category(self):
        """The full 256-merchant list, three ways each. Anything lost must be in the known list."""
        # A few strings are listed by two brands with different categories in merchants.json
        # ("AMAZON PRIME", "NYKAA", ...). Either brand's category is acceptable for those.
        allowed = {}
        for m in cat.MERCHANTS:
            for cand in [m["name"]] + list(m.get("aliases", [])):
                allowed.setdefault(cat.normalize_name(cand), set()).add(m["category"])
        lost, total = set(), 0
        for m in cat.MERCHANTS:
            for cand in [m["name"]] + list(m.get("aliases", [])):
                for text in (cand, "PAYMENT TO " + cand, cand + "*ORDER1234"):
                    total += 1
                    r = match_merchant_db(text)
                    if not (r and r["category"] in allowed[cat.normalize_name(cand)]):
                        lost.add((m["name"], cand))
        self.assertGreater(total, 2000)
        self.assertEqual(lost, KNOWN_SHORT_ALIAS_LOSSES)

    def test_lost_short_aliases_all_work_as_the_whole_text(self):
        by_name = {m["name"]: m for m in cat.MERCHANTS}
        for name, alias in KNOWN_SHORT_ALIAS_LOSSES:
            r = match_merchant_db(alias)
            self.assertIsNotNone(r, alias)
            self.assertEqual(r["category"], by_name[name]["category"], alias)


AUDIT_TERMS = {
    "cafe": "Food & Dining", "general store": "Groceries", "kirana": "Groceries", "medical": "Healthcare",
    "saloon": "Personal Care", "salon": "Personal Care", "dhaba": "Food & Dining", "restaurant": "Food & Dining",
    "sweets": "Food & Dining", "provision": "Groceries", "traders": "Shopping", "electronics": "Shopping",
    "stationery": "Shopping", "hospital": "Healthcare", "clinic": "Healthcare", "petrol pump": "Fuel",
}
REAL_EXAMPLES = {
    "MC SALOON": "Personal Care", "Sharma General Store": "Groceries", "RAJU KIRANA STORE": "Groceries",
    "HOTEL SAI DHABA": "Food & Dining", "SRI SWEETS AND BAKERS": "Food & Dining",
}


class TestFixCDebitKeywords(unittest.TestCase):
    def test_all_sixteen_audit_terms(self):
        for term, expected in AUDIT_TERMS.items():
            r = debit(term)
            self.assertEqual(r["category"], expected, term)
            self.assertNotEqual(r["confidence"], "high", term)  # keyword guesses are never "high"

    def test_the_five_real_examples(self):
        for text, expected in REAL_EXAMPLES.items():
            r = debit(text)
            self.assertEqual(r["category"], expected, text)
            self.assertEqual(r["source"], "keyword_rules", text)
            self.assertEqual(r["confidence"], "medium", text)

    def test_case_insensitive_and_whole_word(self):
        self.assertEqual(debit("mc SALOON")["category"], "Personal Care")
        self.assertEqual(debit("General-Store")["category"], "Groceries")
        self.assertNotEqual(debit("SMARTPHONE HUB")["source"], "keyword_rules")  # MART inside SMART
        self.assertNotEqual(debit("AUTOMOTIVE PARTS")["source"], "keyword_rules")  # AUTO inside AUTOMOTIVE

    def test_longest_keyword_wins(self):
        self.assertEqual(debit("PETROL PUMP AND GENERAL STORE")["category"], "Groceries")  # 13 chars beats 6

    def test_keywords_live_in_the_json_file_with_spelling_variants(self):
        data = json.loads((Path(cat.DATA_DIR) / "debit_keywords.json").read_text(encoding="utf-8"))
        words = {k for rule in data["rules"] for k in rule["keywords"]}
        for pair in [("salon", "saloon"), ("parlour", "parlor")]:
            self.assertTrue(set(pair) <= words, pair)
        self.assertEqual(data["confidence"], "medium")
        self.assertEqual(len(cat.DEBIT_KEYWORD_RULES), sum(len(r["keywords"]) for r in data["rules"]))

    def test_starting_set_is_present(self):
        data = json.loads((Path(cat.DATA_DIR) / "debit_keywords.json").read_text(encoding="utf-8"))
        by_cat = {r["category"]: set(r["keywords"]) for r in data["rules"]}
        wanted = {
            "Food & Dining": "cafe restaurant dhaba hotel tiffin sweets bakery mess canteen chai juice bhojan",
            "Groceries": "kirana provision supermarket mart grocery sabzi dairy milk",
            "Healthcare": "medical pharmacy chemist clinic hospital diagnostic dental",
            "Personal Care": "salon saloon parlour parlor spa barber",
            "Fuel": "petrol diesel fuel",
            "Transportation": "travels transport tours cab taxi auto",
            "Shopping": "traders electronics stationery garments textiles footwear",
        }
        for category, words in wanted.items():
            for w in words.split():
                self.assertIn(w, by_cat[category], f"{category}: {w}")
        self.assertIn("general store", by_cat["Groceries"])
        self.assertIn("filling station", by_cat["Fuel"])

    def test_not_applied_to_credits(self):
        r = categorize_transaction("SHARMA GENERAL STORE", "credit")
        self.assertNotEqual(r["source"], "keyword_rules")
        self.assertNotEqual(r["category"], "Groceries")

    def test_brand_match_still_beats_a_keyword(self):
        r = debit("SWIGGY KITCHEN")  # the brand is in the text; "kitchen" is only a keyword
        self.assertEqual(r["source"], "merchant_db")


EMAIL = "layer4_b@example.com"


class TestFixBMerchantIsNeverOverwritten(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.session = AsyncSessionLocal()
        await self._wipe()
        self.user = User(email=EMAIL, hashed_password="x", full_name="Layer4 Tester")
        self.session.add(self.user)
        await self.session.commit()
        await self.session.refresh(self.user)
        self._n = 0

    async def asyncTearDown(self):
        await self._wipe()
        await self.session.close()

    async def _wipe(self):
        res = await self.session.execute(select(User).where(User.email == EMAIL))
        for user in res.scalars().all():
            await self.session.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user.id))
            await self.session.execute(delete(Transaction).where(Transaction.user_id == user.id))
            await self.session.execute(delete(User).where(User.id == user.id))
        await self.session.commit()

    async def _ingest(self, merchant, tx_type="debit"):
        self._n += 1
        resp = await ingest_sms(
            SMSIngestionRequest(
                amount=200.0 + self._n, transaction_type=tx_type, merchant_raw=merchant, bank_sender_id="HDFCBK",
                account_last4="1234", date=datetime.now(timezone.utc) - timedelta(minutes=self._n),
                upi_ref=f"L4REF{self._n:06d}",
            ),
            current_user=self.user, db=self.session,
        )
        await self.session.commit()
        self.assertTrue(resp.success, resp.message)
        return resp.transaction

    async def _row(self, tx_id):
        return (await self.session.execute(select(Transaction).where(Transaction.id == tx_id))).scalar_one()

    async def test_mc_cafe_is_stored_as_mc_cafe(self):
        tx = await self._ingest("MC CAFE")
        row = await self._row(tx.id)
        self.assertEqual(row.merchant, "MC CAFE")           # the stored text
        self.assertIsNone(row.merchant_display)             # no exact brand match
        self.assertEqual(tx.merchant, "MC CAFE")            # what the app / notification shows
        self.assertEqual(tx.category, "Food & Dining")
        self.assertEqual(tx.source, "keyword_rules")
        self.assertEqual(tx.review_status, "auto_categorized")

    async def test_brand_match_keeps_the_raw_text_and_adds_the_brand(self):
        tx = await self._ingest("SWIGGY*ORDER9931")
        row = await self._row(tx.id)
        self.assertEqual(row.merchant, "SWIGGY*ORDER9931")
        self.assertEqual(row.merchant_display, "Swiggy")
        self.assertEqual(tx.merchant, "Swiggy")             # shown
        self.assertEqual(tx.merchant_raw, "SWIGGY*ORDER9931")  # the correction key source

    async def test_correction_key_comes_from_the_raw_text_not_the_brand(self):
        first = await self._ingest("BUNDL TECHNOLOGIES")   # an alias of Swiggy
        self.assertEqual(first.merchant, "Swiggy")
        # The app sends merchant_raw, not the displayed brand name.
        await recategorize_transaction(
            transaction_id=first.id,
            body=CorrectionRequest(transaction_id=first.id, merchant_raw=first.merchant_raw, new_category="Groceries"),
            db=self.session, current_user=self.user,
        )
        keys = [r.merchant_key for r in (await self.session.execute(select(MerchantMapping))).scalars().all()]
        self.assertEqual(keys, [merchant_key("BUNDL TECHNOLOGIES")])
        self.assertNotIn(merchant_key("Swiggy"), keys)

        second = await self._ingest("BUNDL TECHNOLOGIES")
        self.assertEqual((second.category, second.source), ("Groceries", "user_correction"))

    async def test_editing_the_payee_by_hand_replaces_the_brand_name(self):
        tx = await self._ingest("SWIGGY*ORDER9931")
        await edit_transaction(tx.id, TransactionUpdate(merchant="Office lunch"), db=self.session, current_user=self.user)
        row = await self._row(tx.id)
        self.assertEqual(row.merchant, "Office lunch")
        self.assertIsNone(row.merchant_display)

    async def test_debit_keyword_is_not_applied_to_a_credit_and_credit_is_not_dropped(self):
        tx = await self._ingest("SHARMA GENERAL STORE", tx_type="credit")  # must not raise a 400
        self.assertNotEqual(tx.source, "keyword_rules")

    async def test_credit_keyword_rules_can_be_saved(self):
        tx = await self._ingest("SALARY ACME CORP", tx_type="credit")  # source "keyword_rules" used to fail validation
        self.assertEqual((tx.category, tx.source), ("Salary", "keyword_rules"))

    async def test_ingest_saves_keyword_categories_end_to_end(self):
        for text, expected in REAL_EXAMPLES.items():
            tx = await self._ingest(text)
            self.assertEqual(tx.category, expected, text)
            self.assertEqual(tx.merchant, text, text)


if __name__ == "__main__":
    unittest.main()
