"""
The one-off script that re-runs categorisation on transactions stuck in needs-review.
Uses database.engine like the other suites (point DATABASE_URL at a scratch database).
"""

import contextlib
import io
import unittest
from datetime import datetime, timezone

from sqlalchemy import delete, select, text

from database import AsyncSessionLocal, Base, engine
from models.merchant_mapping import MerchantMapping
from models.transaction import Transaction
from models.user import User
from scripts.recategorize_needs_review import Mapping, Tx, decide, run

EMAILS = ("backfill_a@example.com", "backfill_b@example.com")


class TestDecide(unittest.TestCase):
    MAPS = {(1, "PRATIK JAYDEEP"): Mapping("Personal Care", None), (1, "RAHUL"): Mapping("Transfer", None)}

    def test_users_correction_applies_to_a_matching_debit(self):
        d = decide(Tx(10, 1, "debit", "PRATIK JAYDEEP"), self.MAPS)
        self.assertEqual((d.outcome, d.category, d.source, d.review_status), ("user_correction", "Personal Care", "user_correction", "reviewed"))

    def test_debit_correction_is_not_applied_to_a_credit(self):
        d = decide(Tx(11, 1, "credit", "PRATIK JAYDEEP"), self.MAPS)
        self.assertEqual(d.outcome, "unchanged")

    def test_another_users_correction_never_applies(self):
        self.assertEqual(decide(Tx(12, 2, "debit", "PRATIK JAYDEEP"), self.MAPS).outcome, "unchanged")

    def test_transfer_correction_sets_the_transfer_flag(self):
        d = decide(Tx(13, 1, "credit", "RAHUL"), self.MAPS)
        self.assertEqual((d.outcome, d.category, d.is_transfer), ("user_correction", "Transfer", True))

    def test_confident_automatic_rules_are_applied(self):
        d = decide(Tx(14, 1, "debit", "SHARMA GENERAL STORE"), {})
        self.assertEqual((d.outcome, d.category, d.review_status), ("automatic", "Groceries", "auto_categorized"))

    def test_a_plain_name_stays_in_review(self):
        self.assertEqual(decide(Tx(15, 1, "debit", "SOME PERSON NAME"), {}).outcome, "unchanged")
        self.assertEqual(decide(Tx(16, 1, "debit", None), {}).outcome, "unchanged")


class TestBackfillRun(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.session = AsyncSessionLocal()
        await self._wipe()
        self.a = (await self._user(EMAILS[0])).id
        self.b = (await self._user(EMAILS[1])).id

    async def asyncTearDown(self):
        await self._wipe()
        await self.session.close()

    async def _wipe(self):
        for user in (await self.session.execute(select(User).where(User.email.in_(EMAILS)))).scalars().all():
            await self.session.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user.id))
            await self.session.execute(delete(Transaction).where(Transaction.user_id == user.id))
            await self.session.execute(delete(User).where(User.id == user.id))
        await self.session.commit()

    async def _user(self, email):
        user = User(email=email, hashed_password="x", full_name="Backfill Tester")
        self.session.add(user)
        await self.session.commit()
        await self.session.refresh(user)
        return user

    async def _tx(self, user, merchant, tx_type="debit", review="needs_review", category="Needs Review", n=[0]):
        n[0] += 1
        tx = Transaction(user_id=user, amount=100 + n[0], type=tx_type, category=category, merchant=merchant,
                         review_status=review, source="fallback", confidence="none", date=datetime.now(timezone.utc))
        self.session.add(tx)
        await self.session.commit()
        await self.session.refresh(tx)
        return tx.id

    async def _row(self, tx_id):
        await self.session.rollback()  # see committed data from the script's own session
        return (await self.session.execute(select(Transaction).where(Transaction.id == tx_id))).scalar_one()

    async def test_dry_run_writes_nothing_and_apply_only_touches_needs_review(self):
        self.session.add(MerchantMapping(user_id=self.a, merchant_key="PRATIK JAYDEEP", category="Personal Care"))
        await self.session.commit()
        stuck = await self._tx(self.a, "PRATIK JAYDEEP")
        credit = await self._tx(self.a, "PRATIK JAYDEEP", tx_type="credit")
        store = await self._tx(self.a, "SHARMA GENERAL STORE")
        other_user = await self._tx(self.b, "PRATIK JAYDEEP")
        reviewed = await self._tx(self.a, "PRATIK JAYDEEP", review="reviewed", category="Groceries")

        with contextlib.redirect_stdout(io.StringIO()):
            await run(apply=False, verbose=False, user_id=self.a)
        self.assertEqual((await self._row(stuck)).category, "Needs Review")  # dry run: unchanged

        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            await run(apply=True, verbose=False, user_id=self.a)

        row = await self._row(stuck)
        self.assertEqual((row.category, row.source, row.review_status), ("Personal Care", "user_correction", "reviewed"))
        self.assertEqual((await self._row(store)).category, "Groceries")
        self.assertEqual((await self._row(credit)).review_status, "needs_review")       # type-incompatible correction
        self.assertEqual((await self._row(other_user)).review_status, "needs_review")   # other user, not in scope
        self.assertEqual((await self._row(reviewed)).category, "Groceries")              # already reviewed: untouched

        backup = [w for w in out.getvalue().split() if w.startswith("transactions_bak_")][0]
        count = (await self.session.execute(text(f"SELECT count(*) FROM {backup}"))).scalar()
        self.assertEqual(count, 2)

        again = io.StringIO()
        with contextlib.redirect_stdout(again):
            await run(apply=True, verbose=False, user_id=self.a)
        self.assertIn("Nothing to change", again.getvalue())


if __name__ == "__main__":
    unittest.main()
