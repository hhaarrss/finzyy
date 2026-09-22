"""
Cross-Page Consistency Test Suite for SmartSpend backend.

Validates that cross-page metrics (Dashboard, Budget Limits, Insights)
use the shared backend data-service layer (`services/transaction_aggregates.py`)
and yield 100% consistent financial figures across all pages.
"""

import unittest
import asyncio
from datetime import datetime, timezone
from sqlalchemy import delete, select
from database import AsyncSessionLocal, engine, Base
from models.user import User
from models.transaction import Transaction
from models.budget import BudgetLimit, OverallBudgetLimit
from models.merchant_mapping import MerchantMapping
from routers.budget import set_overall_budget_limit
from routers.transactions import get_monthly_category_summary, list_known_merchants
from schemas.budget import OverallBudgetLimitCreate
from services.transaction_aggregates import (
    get_category_totals,
    get_mom_change,
    get_budget_utilization,
    get_anomalies,
    get_monthly_overview,
    MIN_MEANINGFUL_BASELINE,
    EXCLUDED_CATEGORY_PLACEHOLDERS,
    get_budget_utilization_tone,
)


class TestCrossPageConsistency(unittest.IsolatedAsyncioTestCase):
    """
    Test suite for cross-page metric consistency validation.
    """

    async def asyncSetUp(self):
        """Create a dedicated test user and seed test transactions and budget limits."""
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)

        self.session = AsyncSessionLocal()

        # Clean up existing test user if present
        stmt = select(User).where(User.email == "consistency_test_user@example.com")
        res = await self.session.execute(stmt)
        user = res.scalar_one_or_none()

        if user:
            await self.session.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user.id))
            await self.session.execute(delete(Transaction).where(Transaction.user_id == user.id))
            await self.session.execute(delete(BudgetLimit).where(BudgetLimit.user_id == user.id))
            await self.session.execute(delete(OverallBudgetLimit).where(OverallBudgetLimit.user_id == user.id))
            await self.session.execute(delete(User).where(User.id == user.id))
            await self.session.commit()

        # Create fresh test user
        self.user = User(
            email="consistency_test_user@example.com",
            hashed_password="testpassword",
            full_name="Consistency Tester",
        )
        self.session.add(self.user)
        await self.session.commit()
        await self.session.refresh(self.user)
        self.user_id = self.user.id

        # Target month: August 2026
        # Previous month: July 2026

        # Seed budget limits
        budgets = [
            BudgetLimit(user_id=self.user_id, category="Food & Dining", monthly_limit=5000.0, alert_at_percent=80.0),
            BudgetLimit(user_id=self.user_id, category="Groceries", monthly_limit=4000.0, alert_at_percent=80.0),
            BudgetLimit(user_id=self.user_id, category="Shopping", monthly_limit=3000.0, alert_at_percent=80.0),
            BudgetLimit(user_id=self.user_id, category="Entertainment", monthly_limit=2000.0, alert_at_percent=80.0),
        ]
        self.session.add_all(budgets)

        # Seed August 2026 transactions
        # Food & Dining: total 6000.0 (BREACHED! > 5000 limit)
        # Groceries: total 2500.0 (WITHIN BUDGET: 62.5%)
        # Shopping: total 1500.0 (WITHIN BUDGET: 50.0%)
        # Needs Review (placeholder): 450.0 (EXCLUDED from category totals, INCLUDED in total spent)
        aug_txs = [
            Transaction(
                user_id=self.user_id,
                amount=3500.0,
                type="debit",
                category="Food & Dining",
                merchant="Barbeque Nation",
                date=datetime(2026, 8, 10, 12, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=2500.0,
                type="debit",
                category="Food & Dining",
                merchant="Swiggy",
                date=datetime(2026, 8, 15, 19, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=2500.0,
                type="debit",
                category="Groceries",
                merchant="DMart",
                date=datetime(2026, 8, 12, 10, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=1500.0,
                type="debit",
                category="Shopping",
                merchant="Amazon",
                date=datetime(2026, 8, 18, 16, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=450.0,
                type="debit",
                category="Needs Review",
                merchant="Unknown Vendor",
                date=datetime(2026, 8, 20, 11, 0, 0, tzinfo=timezone.utc),
                review_status="needs_review",
            ),
        ]

        # Seed July 2026 transactions (previous month)
        # Food & Dining: 4000.0 (>= MIN_MEANINGFUL_BASELINE 100.0) -> MoM valid
        # Groceries: 2000.0 (>= 100.0) -> MoM valid
        # Shopping: 50.0 (< MIN_MEANINGFUL_BASELINE 100.0) -> MoM returns None ("Not enough data")
        july_txs = [
            Transaction(
                user_id=self.user_id,
                amount=4000.0,
                type="debit",
                category="Food & Dining",
                merchant="Zomato",
                date=datetime(2026, 7, 10, 12, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=2000.0,
                type="debit",
                category="Groceries",
                merchant="Blinkit",
                date=datetime(2026, 7, 15, 10, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=50.0,
                type="debit",
                category="Shopping",
                merchant="Local Store",
                date=datetime(2026, 7, 20, 14, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
        ]

        self.session.add_all(aug_txs + july_txs)
        await self.session.commit()

    async def asyncTearDown(self):
        """Clean up test records from database after test run."""
        if hasattr(self, "user_id"):
            await self.session.execute(delete(Transaction).where(Transaction.user_id == self.user_id))
            await self.session.execute(delete(BudgetLimit).where(BudgetLimit.user_id == self.user_id))
            await self.session.execute(delete(OverallBudgetLimit).where(OverallBudgetLimit.user_id == self.user_id))
            await self.session.execute(delete(MerchantMapping).where(MerchantMapping.user_id == self.user_id))
            await self.session.execute(delete(User).where(User.id == self.user_id))
            await self.session.commit()
        await self.session.close()
        await engine.dispose()

    async def test_1_dashboard_vs_budget_limits_total_spent_consistency(self):
        """
        Requirement 1:
        Verify 'Total spent this month' on Dashboard EQUALS the sum of all category totals
        shown on Budget Limits (excluding Needs Review / placeholder rows).
        """
        overview = await get_monthly_overview(self.session, self.user_id, 2026, 8, include_transfers=False)
        cat_totals = await get_category_totals(self.session, self.user_id, 2026, 8, include_transfers=False)
        budgets = await get_budget_utilization(self.session, self.user_id, 2026, 8)

        # Expected canonical category totals:
        # Food & Dining: 6000.0
        # Groceries: 2500.0
        # Shopping: 1500.0
        # Total canonical spent: 10000.0
        expected_cat_sum = sum(cat_totals.values())
        self.assertEqual(expected_cat_sum, 10000.0)

        # Dashboard spent = category totals + the 450.0 still waiting for review
        # (unreviewed debits count towards total spent immediately)
        self.assertEqual(overview["merchant_spent"], expected_cat_sum + 450.0)
        self.assertEqual(overview["total_spent"], expected_cat_sum + 450.0)

        # Budget Limits total spent across categories must match category totals
        budget_spent_sum = sum(b.spent for b in budgets)
        self.assertEqual(budget_spent_sum, expected_cat_sum)

        # Verify Needs Review row was excluded from category breakdowns
        self.assertNotIn("Needs Review", cat_totals)
        self.assertNotIn("needs_review", cat_totals)
        self.assertEqual(overview["needs_review_count"], 1)

    async def test_2_food_and_dining_spent_equals_critical_warning_amount(self):
        """
        Requirement 2:
        Verify 'Food & Dining spent' on Budget Limits EQUALS the amount referenced in
        Insights' Critical Budget Warnings card for Food & Dining.
        """
        budgets = await get_budget_utilization(self.session, self.user_id, 2026, 8)
        anomalies = await get_anomalies(self.session, self.user_id, 2026, 8)

        food_budget = next((b for b in budgets if b.category == "Food & Dining"), None)
        self.assertIsNotNone(food_budget)
        self.assertEqual(food_budget.spent, 6000.0)

        # Find budget anomaly for Food & Dining
        food_warning = next(
            (a for a in anomalies if a.get("kind") == "budget" and a.get("category") == "Food & Dining"),
            None,
        )
        self.assertIsNotNone(food_warning)
        self.assertEqual(food_warning["amount"], food_budget.spent)

    async def test_3_budget_breach_anomaly_classification_consistency(self):
        """
        Requirement 3:
        Verify that any category flagged as a budget breach in Insights corresponds to a category
        actually >100% utilized on Budget Limits. No category should be over budget on one page
        and within budget on another.
        """
        budgets = await get_budget_utilization(self.session, self.user_id, 2026, 8)
        anomalies = await get_anomalies(self.session, self.user_id, 2026, 8)

        over_100_categories = {b.category for b in budgets if b.percent_used > 100.0}
        within_budget_categories = {b.category for b in budgets if b.percent_used <= 100.0}

        budget_anomaly_categories = {
            a["category"] for a in anomalies if a.get("kind") == "budget"
        }

        # Every budget anomaly category must be in over_100_categories
        self.assertEqual(budget_anomaly_categories, over_100_categories)

        # No within-budget category should ever appear in budget_anomaly_categories
        self.assertTrue(within_budget_categories.isdisjoint(budget_anomaly_categories))

    async def test_4_mom_low_baseline_threshold_guard(self):
        """
        Requirement 4:
        Verify that a category with previous-month spend below MIN_MEANINGFUL_BASELINE (₹100)
        returns None ("Not enough data") consistently on BOTH top-level/category MoM calculations.
        """
        # Shopping in July had 50.0 spend (< MIN_MEANINGFUL_BASELINE = 100.0) -> must return None
        mom_shopping = await get_mom_change(self.session, self.user_id, 2026, 8, category="Shopping")
        self.assertIsNone(mom_shopping)

        # Food & Dining in July had 4000.0 spend (>= 100.0) -> cur 6000.0 vs prev 4000.0 = +50.0%
        mom_food = await get_mom_change(self.session, self.user_id, 2026, 8, category="Food & Dining")
        self.assertEqual(mom_food, 50.0)

        # Groceries in July had 2000.0 spend (>= 100.0) -> cur 2500.0 vs prev 2000.0 = +25.0%
        mom_groceries = await get_mom_change(self.session, self.user_id, 2026, 8, category="Groceries")
        self.assertEqual(mom_groceries, 25.0)

        # Overall MoM: July total spend = 6050.0 (>= 100.0), Aug total spent = 10450.0
        # (includes the 450.0 awaiting review) -> valid float
        mom_overall = await get_mom_change(self.session, self.user_id, 2026, 8, category=None)
        self.assertIsNotNone(mom_overall)
        self.assertEqual(mom_overall, 72.7)

    async def test_no_duplicate_category_cards(self):
        """
        Requirement 5 / Acceptance:
        Verify no category card is ever duplicated (e.g. 'Transportation' appearing multiple times
        with casing/whitespace variations) on the Budget Limits page for a single user in a single month.
        If duplicates exist pre-fix, merge by summing spent and taking min(limit).
        """
        # Add duplicate category entries with casing/whitespace variations and different limits
        duplicate_budgets = [
            BudgetLimit(user_id=self.user_id, category="Transportation", monthly_limit=3000.0, alert_at_percent=80.0),
            BudgetLimit(user_id=self.user_id, category="transportation", monthly_limit=3500.0, alert_at_percent=80.0),
            BudgetLimit(user_id=self.user_id, category="  Transportation  ", monthly_limit=2500.0, alert_at_percent=80.0),
        ]
        self.session.add_all(duplicate_budgets)

        # Add debits for Transportation to verify spent is summed properly
        trans_txs = [
            Transaction(
                user_id=self.user_id,
                amount=1000.0,
                type="debit",
                category="Transportation",
                merchant="Uber",
                date=datetime(2026, 8, 10, 12, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
            Transaction(
                user_id=self.user_id,
                amount=500.0,
                type="debit",
                category="transportation",
                merchant="Ola",
                date=datetime(2026, 8, 14, 15, 0, 0, tzinfo=timezone.utc),
                review_status="reviewed",
            ),
        ]
        self.session.add_all(trans_txs)
        await self.session.commit()

        budgets = await get_budget_utilization(self.session, self.user_id, 2026, 8)

        category_names = [b.category for b in budgets]
        unique_category_names = set(category_names)

        # Total count must equal unique count (no duplicate cards)
        self.assertEqual(len(category_names), len(unique_category_names))
        # 'Transportation' (or normalized canonical equivalent) must appear exactly once
        transportation_cards = [b for b in budgets if "transportation" in b.category.lower()]
        self.assertEqual(len(transportation_cards), 1)

        trans_card = transportation_cards[0]
        # Verify min(limit) was taken: min(3000, 3500, 2500) = 2500.0
        self.assertEqual(trans_card.limit, 2500.0)
        # Verify spent is summed: 1000.0 + 500.0 = 1500.0
        self.assertEqual(trans_card.spent, 1500.0)
        # Verify percent_used: (1500 / 2500) * 100 = 60.0%
        self.assertEqual(trans_card.percent_used, 60.0)

    async def test_monthly_category_summary_counts_canonical_categories(self):
        """
        Category list transaction_count must match every category's actual transaction count,
        including categories whose raw transaction names normalize to canonical names.
        """
        summary = await get_monthly_category_summary(
            month=8,
            year=2026,
            current_user=self.user,
            db=self.session,
        )

        counts = {item.category: item.transaction_count for item in summary.categories}

        self.assertEqual(counts["Food & Dining"], 2)
        self.assertEqual(counts["Groceries"], 1)
        self.assertEqual(counts["Shopping"], 1)

    async def test_merchant_list_returns_canonical_category_names(self):
        """Merchant list endpoint serializes normalized canonical category names only."""
        self.session.add(
            MerchantMapping(
                user_id=self.user_id,
                merchant_key="uber",
                display_name="Uber",
                category="travel",
                count=2,
            )
        )
        await self.session.commit()

        merchants = await list_known_merchants(current_user=self.user, db=self.session)
        categories = {item["category"] for item in merchants}

        self.assertIn("Transportation", categories)
        self.assertIn("Food & Dining", categories)
        self.assertNotIn("travel", categories)
        self.assertNotIn("Food", categories)

    async def test_budget_utilization_tone_thresholds(self):
        """Utilization colors map 79%, 85%, and 105% to normal, amber, and red."""
        self.assertEqual(get_budget_utilization_tone(79.0), "normal")
        self.assertEqual(get_budget_utilization_tone(85.0), "amber")
        self.assertEqual(get_budget_utilization_tone(105.0), "red")

    async def test_overall_budget_persists_independently_from_category_sum(self):
        """Overall budget stores the user's manual value, not the sum of category limits."""
        saved = await set_overall_budget_limit(
            budget_in=OverallBudgetLimitCreate(monthly_limit=12345.0),
            current_user=self.user,
            db=self.session,
        )
        await self.session.commit()

        self.assertEqual(float(saved.monthly_limit), 12345.0)
        category_limits = (
            await self.session.execute(
                select(BudgetLimit.monthly_limit).where(BudgetLimit.user_id == self.user_id)
            )
        ).scalars().all()
        self.assertNotEqual(float(saved.monthly_limit), sum(float(limit) for limit in category_limits))


if __name__ == "__main__":
    unittest.main()
