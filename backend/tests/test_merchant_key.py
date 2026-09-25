"""
Pure (no database) tests for the shared Layer 1 key and the re-key migration planner.
"""

import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from categorizer.transaction_categorizer import merchant_key, normalize_name  # noqa: E402
from scripts.rekey_merchant_mappings import Row, plan_rekey, new_key_for  # noqa: E402


class TestMerchantKey(unittest.TestCase):
    def test_order_numbers_do_not_change_the_key(self):
        self.assertEqual(merchant_key("SWIGGY*ORDER8827"), merchant_key("SWIGGY*ORDER9931"))
        self.assertEqual(merchant_key("SWIGGY*ORDER8827"), "SWIGGY")

    def test_separator_and_case_variants(self):
        for raw in ["Swiggy ORDER 8827", "SWIGGY-ORDER#8827", "swiggy*order8827", "SWIGGY ORD 12345"]:
            self.assertEqual(merchant_key(raw), "SWIGGY", raw)

    def test_other_reference_shapes(self):
        self.assertEqual(merchant_key("AMAZON*2B7Y91H"), "AMAZON")
        self.assertEqual(merchant_key("BIGBASKET 4829173"), "BIGBASKET")
        self.assertEqual(merchant_key("SHOP-INV#0042"), "SHOP")
        self.assertEqual(merchant_key("MCDONALDS 123456 789012"), "MCDONALDS")

    def test_distinct_merchants_are_not_merged(self):
        self.assertEqual(merchant_key("PRATIK JAYDEEP"), "PRATIK JAYDEEP")
        self.assertNotEqual(merchant_key("7ELEVEN"), merchant_key("ELEVEN"))
        self.assertEqual(merchant_key("7 ELEVEN"), "7 ELEVEN")
        self.assertEqual(merchant_key("SECTOR 21 STORE"), "SECTOR 21 STORE")
        self.assertEqual(merchant_key("UBER TRIP"), "UBER TRIP")      # marker without an ID
        self.assertEqual(merchant_key("ORDER 12345"), "ORDER 12345")  # never strips everything

    def test_upi_handles_keep_their_digits(self):
        self.assertNotEqual(merchant_key("9876543210@ybl"), merchant_key("9123456780@ybl"))
        self.assertEqual(merchant_key("9876543210@ybl"), normalize_name("9876543210@ybl"))

    def test_empty_and_idempotent(self):
        self.assertEqual(merchant_key(None), "")
        self.assertEqual(merchant_key("  "), "")
        for raw in ["SWIGGY*ORDER8827", "AMAZON PAY INDIA", "PRATIK JAYDEEP"]:
            self.assertEqual(merchant_key(merchant_key(raw)), merchant_key(raw))


class TestRekeyPlan(unittest.TestCase):
    T0 = datetime(2026, 9, 1, tzinfo=timezone.utc)

    def row(self, id, user, key, display, category="Food & Dining", count=1, days=0):
        return Row(id, user, key, display, category, count, self.T0 + timedelta(days=days))

    def test_old_key_is_rebuilt_from_display_name(self):
        r = self.row(1, 7, "SWIGGYORDER8827", "SWIGGY*ORDER8827")
        self.assertEqual(new_key_for(r), "SWIGGY")

    def test_display_name_that_is_not_the_source_is_ignored(self):
        r = self.row(1, 7, "BUNDL", "Swiggy")   # display name is a different merchant
        self.assertEqual(new_key_for(r), "BUNDL")

    def test_rows_already_in_new_format_are_untouched(self):
        plan = plan_rekey([self.row(1, 7, "PRATIK JAYDEEP", "PRATIK JAYDEEP"), self.row(2, 7, "ZOMATO", "Zomato")])
        self.assertEqual(plan.unchanged, 2)
        self.assertEqual(plan.rekeyed, {})

    def test_same_user_duplicates_merge_newest_wins(self):
        plan = plan_rekey([
            self.row(1, 7, "SWIGGYORDER8827", "SWIGGY*ORDER8827", "Food & Dining", 2, days=0),
            self.row(2, 7, "SWIGGYORDER9931", "SWIGGY*ORDER9931", "Groceries", 1, days=5),
        ])
        self.assertEqual(plan.merged_away, [1])
        self.assertEqual(plan.rekeyed[2], "SWIGGY")
        self.assertEqual(plan.survivor_count, {2: 3})
        self.assertEqual(plan.conflicts, 1)

    def test_different_users_never_merge(self):
        plan = plan_rekey([
            self.row(1, 7, "SWIGGYORDER8827", "SWIGGY*ORDER8827"),
            self.row(2, 8, "SWIGGYORDER9931", "SWIGGY*ORDER9931"),
        ])
        self.assertEqual(plan.merged_away, [])
        self.assertEqual(plan.rekeyed, {1: "SWIGGY", 2: "SWIGGY"})

    def test_second_run_changes_nothing(self):
        first = plan_rekey([self.row(1, 7, "SWIGGYORDER8827", "SWIGGY*ORDER8827")])
        again = plan_rekey([self.row(1, 7, first.rekeyed[1], "SWIGGY*ORDER8827")])
        self.assertEqual(again.rekeyed, {})


if __name__ == "__main__":
    unittest.main()
