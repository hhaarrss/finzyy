"""
Re-run categorisation on transactions that are still waiting in "needs review".

Why: Layer 1 (the user's own corrections) and the newer keyword rules only apply to payments
that arrive *after* they are deployed. Payments already sitting in needs-review were filed
under the old logic and stay there until something re-runs it. This does that, once.

For every transaction whose review_status is 'needs_review' it applies, in order:
  1. the user's own correction for that merchant (same key and same type check as ingest)
  2. the automatic layers (brand list, debit keyword rules, MCC) - only if they give a
     confident answer; anything that would still be "Needs Review" is left alone.
Transactions already reviewed (by the user or automatically) are never touched, and neither
are merchant names, amounts or dates.

SAFE BY DEFAULT: without --apply it only reports counts and writes nothing.

    python scripts/recategorize_needs_review.py               # dry run: counts only
    python scripts/recategorize_needs_review.py --verbose     # dry run + merchant -> category
    python scripts/recategorize_needs_review.py --apply       # copy affected rows, then update

--apply first copies the rows it will change into transactions_bak_<timestamp> in the same
transaction, so a failure rolls everything back. Take a Neon snapshot before running it on
production; that is the real backup.
"""

import argparse
import asyncio
import os
import sys
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from categorizer.transaction_categorizer import (  # noqa: E402
    categorize_transaction,
    merchant_key,
    normalize_category_name,
)
from utils.categories import validate_category_matches_type  # noqa: E402
from utils.transfer_detector import detect_p2p_transfer  # noqa: E402


@dataclass
class Tx:
    id: int
    user_id: int
    type: str
    merchant: Optional[str]


@dataclass
class Mapping:
    category: str
    subcategory: Optional[str]


@dataclass
class Decision:
    tx_id: int
    outcome: str  # "user_correction" | "automatic" | "unchanged"
    category: Optional[str] = None
    subcategory: Optional[str] = None
    source: Optional[str] = None
    confidence: Optional[str] = None
    review_status: Optional[str] = None
    is_transfer: bool = False
    transfer_to: Optional[str] = None


def decide(tx: Tx, mappings: Dict[Tuple[int, str], Mapping]) -> Decision:
    """Pure function: what should happen to one needs-review transaction. Touches no database."""
    tx_type = (tx.type or "").lower()

    # 1. The user's own correction, unless its category can't apply to this kind of payment
    #    (a debit category on a credit, or the reverse).
    mapping = mappings.get((tx.user_id, merchant_key(tx.merchant)))
    if mapping is not None and validate_category_matches_type(mapping.category, tx_type):
        category = normalize_category_name(mapping.category)
        is_transfer, recipient = (False, None)
        if category.lower() == "transfer":
            is_transfer, recipient = detect_p2p_transfer(tx.merchant, category=category)
        return Decision(tx.id, "user_correction", category, mapping.subcategory, "user_correction", "high",
                        "reviewed", is_transfer, recipient)

    # 2. The automatic layers, but only a confident answer replaces "needs review".
    if tx.merchant:
        result = categorize_transaction(tx.merchant, tx_type)
        category = normalize_category_name(result.get("category"))
        confident = (
            result.get("source") != "fallback"
            and result.get("confidence") in ("high", "medium")
            and category.lower() not in ("miscellaneous", "needs review", "other")
            and validate_category_matches_type(category, tx_type)
        )
        if confident:
            return Decision(tx.id, "automatic", category, result.get("subcategory"), result.get("source"),
                            result.get("confidence"), "auto_categorized")

    return Decision(tx.id, "unchanged")


async def run(apply: bool, verbose: bool, user_id: Optional[int]) -> None:
    from sqlalchemy import text
    from database import AsyncSessionLocal

    async with AsyncSessionLocal() as db:
        params: Dict[str, Any] = {}
        where = "WHERE review_status = 'needs_review'"
        if user_id is not None:
            where += " AND user_id = :uid"
            params["uid"] = user_id
        rows = (await db.execute(text(f"SELECT id, user_id, type, merchant FROM transactions {where}"), params)).all()
        txs = [Tx(*r) for r in rows]

        mappings: Dict[Tuple[int, str], Mapping] = {}
        # Oldest first so the newest mapping for a key wins, as it does at ingest.
        for uid, key, category, sub in (await db.execute(text(
            "SELECT user_id, merchant_key, category, subcategory FROM merchant_mappings ORDER BY last_used_at, id"
        ))).all():
            mappings[(uid, key)] = Mapping(category, sub)

        decisions = [decide(t, mappings) for t in txs]
        changed = [d for d in decisions if d.outcome != "unchanged"]
        by_id = {t.id: t for t in txs}

        print(f"transactions in needs-review        : {len(txs)}")
        print(f"  would be filed by a user correction: {sum(d.outcome == 'user_correction' for d in decisions)}")
        print(f"  would be filed by automatic rules  : {sum(d.outcome == 'automatic' for d in decisions)}")
        print(f"  would stay in needs-review         : {sum(d.outcome == 'unchanged' for d in decisions)}")
        print("  by category:", dict(Counter(d.category for d in changed)))
        if verbose:
            for d in changed:
                print(f"  id {d.tx_id} {by_id[d.tx_id].type}: {by_id[d.tx_id].merchant!r} -> {d.category} ({d.outcome})")

        if not apply:
            print("\nDRY RUN - nothing was written. Re-run with --apply to change the data.")
            return
        if not changed:
            print("\nNothing to change.")
            return

        backup = "transactions_bak_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        ids = ",".join(str(d.tx_id) for d in changed)
        try:
            await db.execute(text(f"CREATE TABLE {backup} AS SELECT * FROM transactions WHERE id IN ({ids})"))
            for d in changed:
                await db.execute(
                    text(
                        "UPDATE transactions SET category = :c, subcategory = :s, source = :src, confidence = :conf, "
                        "review_status = :rs, is_transfer = :it, transfer_to = :tt WHERE id = :id AND review_status = 'needs_review'"
                    ),
                    {"c": d.category, "s": d.subcategory, "src": d.source, "conf": d.confidence,
                     "rs": d.review_status, "it": d.is_transfer, "tt": d.transfer_to, "id": d.tx_id},
                )
            await db.commit()
        except Exception:
            await db.rollback()
            raise
        print(f"\nAPPLIED to {len(changed)} rows. Copy of the old rows: {backup}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--apply", action="store_true", help="write the changes (default is a dry run)")
    parser.add_argument("--verbose", action="store_true", help="print merchant -> category (these are merchant names)")
    parser.add_argument("--user-id", type=int, default=None, help="limit to one user")
    args = parser.parse_args()
    asyncio.run(run(args.apply, args.verbose, args.user_id))
