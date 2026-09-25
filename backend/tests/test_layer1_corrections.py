"""
Layer 1 (user corrections): a correction made through recategorize must be applied to the
next transaction from the same merchant at ingest, and only for the user who made it.

Uses the same database setup as the other suites (database.engine).
"""

import unittest
from datetime import datetime, timezone, timedelta

from sqlalchemy import select, delete

from database import AsyncSessionLocal, engine, Base
from models.user import User
from models.transaction import Transaction
from models.merchant_mapping import MerchantMapping
from schemas.sms import SMSIngestionRequest
from schemas.transaction import CorrectionRequest
from routers.transactions import ingest_sms, recategorize_transaction
from categorizer.transaction_categorizer import merchant_key


EMAILS = ("layer1_a@example.com", "layer1_b@example.com")


class TestLayer1Corrections(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.create_all)
        self.session = AsyncSessionLocal()
        await self._wipe()
        self.user_a = await self._make_user(EMAILS[0])
        self.user_b = await self._make_user(EMAILS[1])
        self._n = 0

    async def asyncTearDown(self):
        await self._wipe()
        await self.session.close()

    async def _wipe(self):
        res = await self.session.execute(select(User).where(User.email.in_(EMAILS)))
        for user in res.scalars().all():
            await self.session.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user.id))
            await self.session.execute(delete(Transaction).where(Transaction.user_id == user.id))
            await self.session.execute(delete(User).where(User.id == user.id))
        await self.session.commit()

    async def _make_user(self, email):
        user = User(email=email, hashed_password="x", full_name="Layer1 Tester")
        self.session.add(user)
        await self.session.commit()
        await self.session.refresh(user)
        return user

    async def _ingest(self, user, merchant, tx_type="debit", amount=100.0):
        """One SMS through the real ingest endpoint; every call is a distinct payment."""
        self._n += 1
        resp = await ingest_sms(
            SMSIngestionRequest(
                amount=amount + self._n,
                transaction_type=tx_type,
                merchant_raw=merchant,
                bank_sender_id="HDFCBK",
                account_last4="1234",
                date=datetime.now(timezone.utc) - timedelta(minutes=self._n),
                upi_ref=f"L1REF{user.id}{self._n:06d}",
            ),
            current_user=user,
            db=self.session,
        )
        await self.session.commit()
        self.assertTrue(resp.success, resp.message)
        return resp.transaction

    async def _correct(self, user, tx, merchant_raw, category):
        await recategorize_transaction(
            transaction_id=tx.id,
            body=CorrectionRequest(transaction_id=tx.id, merchant_raw=merchant_raw, new_category=category),
            db=self.session,
            current_user=user,
        )

    # -- acceptance 1 ------------------------------------------------------------------

    async def test_correction_applies_to_next_order_number(self):
        first = await self._ingest(self.user_a, "SWIGGY*ORDER8827")
        await self._correct(self.user_a, first, "SWIGGY*ORDER8827", "Food & Dining")

        second = await self._ingest(self.user_a, "SWIGGY*ORDER9931")
        self.assertEqual(second.category, "Food & Dining")
        self.assertEqual(second.source, "user_correction")
        self.assertEqual(second.confidence, "high")
        self.assertEqual(second.review_status, "reviewed")  # no manual review needed
        self.assertEqual(second.merchant_raw, "SWIGGY*ORDER9931")  # the new message's own text, stored as-is

    async def test_correction_overrides_a_different_automatic_category(self):
        first = await self._ingest(self.user_a, "SWIGGY*ORDER8827")
        await self._correct(self.user_a, first, "SWIGGY*ORDER8827", "Groceries")
        second = await self._ingest(self.user_a, "SWIGGY*ORDER9931")
        self.assertEqual(second.category, "Groceries")
        self.assertEqual(second.source, "user_correction")

    # -- acceptance 2 ------------------------------------------------------------------

    async def test_one_users_correction_never_reaches_another_user(self):
        a_tx = await self._ingest(self.user_a, "SWIGGY*ORDER8827")
        await self._correct(self.user_a, a_tx, "SWIGGY*ORDER8827", "Groceries")

        b_tx = await self._ingest(self.user_b, "SWIGGY*ORDER9931")
        self.assertNotEqual(b_tx.source, "user_correction")
        self.assertNotEqual(b_tx.category, "Groceries")

        rows = (await self.session.execute(select(MerchantMapping))).scalars().all()
        mine = [r for r in rows if r.user_id in (self.user_a.id, self.user_b.id)]
        self.assertEqual({r.user_id for r in mine}, {self.user_a.id})

    # -- acceptance 3: the PRATIK JAYDEEP pattern (same plain name, debits and credits) -----

    async def test_pratik_jaydeep_pattern_applies_to_a_new_payment(self):
        first = await self._ingest(self.user_a, "PRATIK JAYDEEP")
        self.assertEqual(first.review_status, "needs_review")
        await self._ingest(self.user_a, "PRATIK JAYDEEP")  # still uncorrected, as in production
        await self._correct(self.user_a, first, "PRATIK JAYDEEP", "Personal Care")

        new = await self._ingest(self.user_a, "PRATIK JAYDEEP")
        self.assertEqual(new.category, "Personal Care")
        self.assertEqual(new.source, "user_correction")
        self.assertEqual(new.review_status, "reviewed")

    async def test_debit_correction_is_not_applied_to_a_credit_and_credit_is_not_dropped(self):
        debit = await self._ingest(self.user_a, "PRATIK JAYDEEP")
        await self._correct(self.user_a, debit, "PRATIK JAYDEEP", "Personal Care")

        credit = await self._ingest(self.user_a, "PRATIK JAYDEEP", tx_type="credit")  # must not raise
        self.assertNotEqual(credit.source, "user_correction")
        self.assertNotEqual(credit.category, "Personal Care")

    # -- write side --------------------------------------------------------------------

    async def test_recategorize_writes_the_shared_key_and_reuses_the_row(self):
        first = await self._ingest(self.user_a, "SWIGGY*ORDER8827")
        await self._correct(self.user_a, first, "SWIGGY*ORDER8827", "Food & Dining")
        third = await self._ingest(self.user_a, "SWIGGY*ORDER9931")
        await self._correct(self.user_a, third, "SWIGGY*ORDER9931", "Groceries")

        rows = (await self.session.execute(
            select(MerchantMapping).where(MerchantMapping.user_id == self.user_a.id)
        )).scalars().all()
        self.assertEqual(len(rows), 1)  # one merchant, one mapping
        self.assertEqual(rows[0].merchant_key, merchant_key("SWIGGY*ORDER8827"))
        self.assertEqual(rows[0].category, "Groceries")
        self.assertEqual(rows[0].count, 2)

    async def test_correction_beats_p2p_transfer_detection(self):
        first = await self._ingest(self.user_a, "rahul.sharma@okaxis")
        await self._correct(self.user_a, first, "rahul.sharma@okaxis", "Food & Dining")
        second = await self._ingest(self.user_a, "rahul.sharma@okaxis")
        self.assertEqual(second.category, "Food & Dining")
        self.assertFalse(second.is_transfer)


if __name__ == "__main__":
    unittest.main()
