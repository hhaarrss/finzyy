"""
A user's own spending categories: the naming rules and the three routes.

Runs without a database. The session is a stand-in that holds the user's categories in a list,
so what is tested here is the routes' own logic (validation, duplicates, the limit, ownership),
not the SQL - the SQL is exercised against a real database separately.

    cd backend && python -m pytest tests/test_custom_categories.py
"""

import os
import unittest
from types import SimpleNamespace

os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://nobody@127.0.0.1:1/none")
os.environ.setdefault("JWT_SECRET_KEY", "test-only")

from fastapi.testclient import TestClient  # noqa: E402

import main  # noqa: E402
from constants.categories import CREDIT_CATEGORIES, DEBIT_CATEGORIES  # noqa: E402
from database import get_db  # noqa: E402
from models.user_category import UserCategory  # noqa: E402
from services.custom_categories import (  # noqa: E402
    MAX_CUSTOM_CATEGORIES,
    CategoryNameError,
    clean_name,
    spending_list,
)
from routers.categories import get_optional_user  # noqa: E402
from utils.dependencies import get_current_user  # noqa: E402

ME = 1


class NameRulesTest(unittest.TestCase):
    def test_ordinary_names_are_kept_as_typed_and_tidied(self):
        self.assertEqual(clean_name("Pets"), "Pets")
        self.assertEqual(clean_name("  Gym   & Fitness "), "Gym & Fitness")
        self.assertEqual(clean_name("Kids' school"), "Kids' school")
        self.assertEqual(clean_name("घर का खर्च"), "घर का खर्च")
        self.assertEqual(clean_name("x" * 30), "x" * 30)
        self.assertEqual(clean_name("Car\n wash"), "Car wash")   # any run of whitespace is one space

    def test_names_that_are_too_short_long_or_odd_are_refused(self):
        for bad in ("", " ", "a", "x" * 31, "🙂🙂", "DROP TABLE;", "under_score", "&&&", "<b>hi</b>"):
            with self.assertRaises(CategoryNameError, msg=repr(bad)):
                clean_name(bad)

    def test_built_in_names_and_their_other_spellings_are_refused(self):
        for taken in ("Food & Dining", "food & dining", "FOOD", "Salary", "Other", "Transfer",
                      "Needs Review", "Miscellaneous", "utilities", "Groceries "):
            with self.assertRaises(CategoryNameError, msg=taken):
                clean_name(taken)

    def test_custom_names_sit_before_transfer_and_other(self):
        result = spending_list(["Pets", "Gym"])
        self.assertEqual(result[-2:], ["Transfer", "Other"])
        self.assertEqual(result[-4:-2], ["Pets", "Gym"])
        self.assertEqual(sorted(spending_list([])), sorted(DEBIT_CATEGORIES))


class _Rows:
    def __init__(self, rows):
        self._rows = rows

    def scalars(self):
        return self

    def all(self):
        return list(self._rows)

    def first(self):
        return self._rows[0] if self._rows else None


class _FakeSession:
    """Holds one user's categories. Deletes and updates of other tables are counted, not run."""

    def __init__(self, rows=None):
        self.rows = list(rows or [])
        self.statements = []
        self.next_id = 100

    async def execute(self, statement, *_a, **_k):
        self.statements.append(str(statement))
        text = str(statement)
        if text.lstrip().upper().startswith("SELECT"):
            params = statement.compile().params
            wanted_id = next((v for k, v in params.items() if k.startswith("id_")), None)
            owner = next((v for k, v in params.items() if k.startswith("user_id")), None)
            rows = [r for r in self.rows if owner is None or r.user_id == owner]
            if wanted_id is not None:
                rows = [r for r in rows if r.id == wanted_id]
            return _Rows(rows)
        return SimpleNamespace(rowcount=3)

    def add(self, row):
        row.id = self.next_id
        self.next_id += 1
        self.rows.append(row)

    async def delete(self, row):
        self.rows.remove(row)

    async def commit(self):
        pass

    async def rollback(self):
        pass

    async def refresh(self, _row):
        pass


def _category(cid, name, user_id=ME):
    return UserCategory(id=cid, user_id=user_id, name=name, name_key=name.lower())


class RoutesTest(unittest.TestCase):
    def use(self, rows=None, signed_in=True):
        self.session = _FakeSession(rows)

        async def fake_db():
            yield self.session

        main.app.dependency_overrides[get_db] = fake_db
        if signed_in:
            me = SimpleNamespace(id=ME)
            main.app.dependency_overrides[get_current_user] = lambda: me
            # The list route resolves the user itself (it must also work signed out).
            main.app.dependency_overrides[get_optional_user] = lambda: me
        return TestClient(main.app)

    def tearDown(self):
        main.app.dependency_overrides.clear()

    def test_signed_out_gets_the_built_in_lists_only(self):
        body = self.use(signed_in=False).get("/categories").json()
        self.assertEqual(sorted(body["debit"]), sorted(DEBIT_CATEGORIES))
        self.assertEqual(body["credit"], CREDIT_CATEGORIES)
        self.assertEqual(body["custom"], [])

    def test_creating_requires_signing_in(self):
        client = self.use(signed_in=False)
        self.assertEqual(client.post("/categories/custom", json={"name": "Pets"}).status_code, 401)
        self.assertEqual(client.delete("/categories/custom/1").status_code, 401)

    def test_create_then_it_is_in_the_spending_list(self):
        client = self.use()
        created = client.post("/categories/custom", json={"name": "  Pets "})
        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.json()["name"], "Pets")
        body = client.get("/categories").json()
        self.assertIn("Pets", body["debit"])
        self.assertNotIn("Pets", body["credit"])
        self.assertEqual(body["debit"][-2:], ["Transfer", "Other"])
        self.assertEqual(body["custom"], [{"id": created.json()["id"], "name": "Pets"}])

    def test_bad_and_duplicate_names_are_refused(self):
        client = self.use([_category(1, "Pets")])
        self.assertEqual(client.post("/categories/custom", json={"name": "Groceries"}).status_code, 400)
        self.assertEqual(client.post("/categories/custom", json={"name": "x"}).status_code, 400)
        self.assertEqual(client.post("/categories/custom", json={"name": "pets"}).status_code, 409)
        self.assertEqual(client.post("/categories/custom", json={"name": "Pets", "extra": 1}).status_code, 422)
        self.assertEqual(len(self.session.rows), 1)

    def test_the_limit_is_enforced(self):
        client = self.use([_category(i, f"Cat {i}") for i in range(MAX_CUSTOM_CATEGORIES)])
        refused = client.post("/categories/custom", json={"name": "One more"})
        self.assertEqual(refused.status_code, 400)
        self.assertIn(str(MAX_CUSTOM_CATEGORIES), refused.json()["detail"])

    def test_delete_moves_payments_and_removes_what_pointed_at_it(self):
        client = self.use([_category(7, "Pets")])
        gone = client.delete("/categories/custom/7")
        self.assertEqual(gone.status_code, 200)
        self.assertEqual(gone.json(), {"deleted": "Pets", "moved_transactions": 3})
        self.assertEqual(self.session.rows, [])
        sql = " ".join(self.session.statements)
        self.assertIn("UPDATE transactions", sql)
        for table in ("budget_limits", "budget_alerts_log", "merchant_mappings"):
            self.assertIn(f"DELETE FROM {table}", sql)

    def test_someone_elses_category_cannot_be_deleted(self):
        client = self.use([_category(9, "Theirs", user_id=2)])
        self.assertEqual(client.delete("/categories/custom/9").status_code, 404)
        self.assertEqual(len(self.session.rows), 1)


if __name__ == "__main__":
    unittest.main()
