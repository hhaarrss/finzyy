"""
Regression tests for problems found in the pre-release review (Sept 2026). They run without a
database: the session dependency is replaced by a stand-in that returns one transaction held
in memory, and the signed-in user is fixed.

    DATABASE_URL=postgresql+asyncpg://nobody@127.0.0.1:1/none JWT_SECRET_KEY=x \
        python -m pytest tests/test_route_security.py
"""

import os
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace

os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://nobody@127.0.0.1:1/none")
os.environ.setdefault("JWT_SECRET_KEY", "test-only")

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from database import get_db  # noqa: E402
from models.transaction import Transaction  # noqa: E402
from utils.dependencies import get_current_user  # noqa: E402

OWNER_ID = 1


def _transaction(**overrides) -> Transaction:
    now = datetime.now(timezone.utc)
    fields = dict(
        id=5, user_id=OWNER_ID, amount=100.0, type="debit", category="Food & Dining",
        merchant="SWIGGY", date=now, created_at=now, source="sms", confidence="high",
        review_status="auto_categorized", is_transfer=False,
    )
    fields.update(overrides)
    return Transaction(**fields)


class _Result:
    def __init__(self, row):
        self._row = row

    def scalars(self):
        return self

    def first(self):
        return self._row


class _FakeSession:
    """Answers every query with the one transaction; records whether anything was saved."""

    def __init__(self, row):
        self.row = row
        self.committed = False

    async def execute(self, *_args, **_kwargs):
        return _Result(self.row)

    async def commit(self):
        self.committed = True

    async def refresh(self, _obj):
        pass

    def add(self, _obj):
        pass


class RouteSecurityTests(unittest.TestCase):
    def setUp(self):
        self.tx = _transaction()
        self.session = _FakeSession(self.tx)

        async def fake_db():
            yield self.session

        main.app.dependency_overrides[get_db] = fake_db
        main.app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=OWNER_ID, family_id=7)
        self.client = TestClient(main.app)

    def tearDown(self):
        main.app.dependency_overrides.clear()

    def test_amount_edit_is_saved(self):
        # PATCH /transactions/{id} used to be answered by a handler without an amount field,
        # so the app's "edit amount" was silently dropped.
        r = self.client.patch("/transactions/5", json={"amount": 999.5, "merchant": "Swiggy"})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(float(r.json()["amount"]), 999.5)
        self.assertEqual(self.tx.amount, 999.5)
        self.assertEqual(self.tx.merchant, "Swiggy")
        self.assertTrue(self.session.committed)

    def test_editing_someone_elses_transaction_is_refused(self):
        self.session.row = _transaction(user_id=2)
        r = self.client.patch("/transactions/5", json={"amount": 1})
        self.assertEqual(r.status_code, 403, r.text)
        self.assertFalse(self.session.committed)

    def test_reading_another_users_transactions_is_refused(self):
        # Used to be allowed for "family members"; joining a family needed only its number.
        r = self.client.get("/transactions/", params={"user_id": 2})
        self.assertEqual(r.status_code, 403, r.text)


if __name__ == "__main__":
    unittest.main()
