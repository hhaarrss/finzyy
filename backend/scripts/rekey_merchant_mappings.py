"""
Re-key merchant_mappings rows with categorizer.transaction_categorizer.merchant_key().

Rows written before the shared key helper used the old format (normalize_name of the raw
merchant, or a plain lower() from the /categorize route), so an order-numbered merchant such
as "SWIGGY*ORDER8827" was stored as "SWIGGYORDER8827" and can never equal the key computed
for the next SMS. This rewrites each row's key with merchant_key(), and merges rows of the
same user that now share a key.

SAFE BY DEFAULT: without --apply it only reports what it would change and writes nothing.

    python scripts/rekey_merchant_mappings.py              # dry run: counts only
    python scripts/rekey_merchant_mappings.py --verbose    # dry run + old -> new keys
    python scripts/rekey_merchant_mappings.py --apply      # copy the table, then re-key

--apply first copies the whole table to merchant_mappings_bak_<timestamp> inside the same
transaction, so a failure rolls everything back. Take a Neon snapshot/branch as well before
running it against production; that is the real backup, the copy only guards this script.

Re-keying a row: the original merchant text is not stored, but display_name usually holds it
("SWIGGY*ORDER8827"), so it is used when normalising it reproduces the stored key; otherwise
the stored key itself is re-keyed. Rows of one user that end up with the same key are merged:
the most recently used row survives with the summed count, the others are deleted.
Running it twice changes nothing the second time.
"""

import argparse
import asyncio
import os
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from categorizer.transaction_categorizer import merchant_key, normalize_name  # noqa: E402


@dataclass
class Row:
    id: int
    user_id: int
    merchant_key: str
    display_name: Optional[str]
    category: str
    count: int
    last_used_at: Any


@dataclass
class Plan:
    total: int
    rekeyed: Dict[int, str]          # row id -> new key (survivors and merged-away rows)
    merged_away: List[int]           # row ids to delete
    survivor_count: Dict[int, int]   # surviving row id -> summed count
    conflicts: int                   # merged groups whose members disagreed on category

    @property
    def unchanged(self) -> int:
        return self.total - len(self.rekeyed)


def new_key_for(row: Row) -> str:
    """The row's key under the shared helper."""
    if row.display_name and normalize_name(row.display_name) == normalize_name(row.merchant_key):
        candidate = merchant_key(row.display_name)
        if candidate:
            return candidate
    return merchant_key(row.merchant_key) or row.merchant_key


def plan_rekey(rows: List[Row]) -> Plan:
    """Pure function: decides what --apply would do. Touches no database."""
    groups: Dict[tuple, List[Row]] = defaultdict(list)
    for row in rows:
        groups[(row.user_id, new_key_for(row))].append(row)

    rekeyed: Dict[int, str] = {}
    merged_away: List[int] = []
    survivor_count: Dict[int, int] = {}
    conflicts = 0

    for (_user_id, key), members in groups.items():
        # Newest first; id breaks ties so the choice is deterministic.
        members.sort(key=lambda r: (r.last_used_at, r.id), reverse=True)
        survivor, rest = members[0], members[1:]
        if survivor.merchant_key != key:
            rekeyed[survivor.id] = key
        if rest:
            survivor_count[survivor.id] = sum(m.count for m in members)
            merged_away.extend(m.id for m in rest)
            if len({m.category for m in members}) > 1:
                conflicts += 1
            for m in rest:
                rekeyed[m.id] = key
    return Plan(len(rows), rekeyed, merged_away, survivor_count, conflicts)


async def run(apply: bool, verbose: bool, user_id: Optional[int]) -> None:
    from sqlalchemy import text
    from database import AsyncSessionLocal, engine

    async with AsyncSessionLocal() as db:
        sql = "SELECT id, user_id, merchant_key, display_name, category, count, last_used_at FROM merchant_mappings"
        params: Dict[str, Any] = {}
        if user_id is not None:
            sql += " WHERE user_id = :uid"
            params["uid"] = user_id
        result = await db.execute(text(sql), params)
        rows = [Row(*r) for r in result.all()]
        plan = plan_rekey(rows)
        by_id = {r.id: r for r in rows}

        print(f"merchant_mappings rows examined : {plan.total}")
        print(f"rows already in the new format  : {plan.unchanged}")
        print(f"rows whose key would change     : {len(plan.rekeyed) - len(plan.merged_away)}")
        print(f"rows merged into another row    : {len(plan.merged_away)}  (deleted)")
        print(f"merged groups with mixed categories: {plan.conflicts}  (newest correction wins)")
        if verbose:
            for rid, key in plan.rekeyed.items():
                print(f"  id {rid} user {by_id[rid].user_id}: {by_id[rid].merchant_key!r} -> {key!r}"
                      + ("  [merged away]" if rid in plan.merged_away else ""))

        if not apply:
            print("\nDRY RUN - nothing was written. Re-run with --apply to change the data.")
            await engine.dispose()
            return
        if not plan.rekeyed:
            print("\nNothing to change.")
            await engine.dispose()
            return

        backup = "merchant_mappings_bak_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        try:
            await db.execute(text(f"CREATE TABLE {backup} AS SELECT * FROM merchant_mappings"))
            for rid, key in plan.rekeyed.items():
                if rid in plan.merged_away:
                    continue
                await db.execute(
                    text("UPDATE merchant_mappings SET merchant_key = :k WHERE id = :id"), {"k": key, "id": rid}
                )
            for rid, total in plan.survivor_count.items():
                await db.execute(text("UPDATE merchant_mappings SET count = :c WHERE id = :id"), {"c": total, "id": rid})
            for rid in plan.merged_away:
                await db.execute(text("DELETE FROM merchant_mappings WHERE id = :id"), {"id": rid})
            await db.commit()
        except Exception:
            await db.rollback()
            raise
        print(f"\nAPPLIED. Copy of the old table: {backup}")
    await engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="write the changes (default is a dry run)")
    parser.add_argument("--verbose", action="store_true", help="print old -> new keys (these are merchant names)")
    parser.add_argument("--user-id", type=int, default=None, help="limit to one user")
    args = parser.parse_args()
    asyncio.run(run(args.apply, args.verbose, args.user_id))
