"""
Shared Data-Service Layer for Transaction Aggregates.

Provides the single source of truth for cross-page metrics (Dashboard, Budget Limits, Insights),
guaranteeing live consistency, normalized category grouping, exclusion of review-status placeholders,
and baseline threshold guards for percentage calculations.
"""

import calendar
from datetime import datetime, timezone
from typing import Optional, Dict, List, Any
from pydantic import BaseModel
from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from models.transaction import Transaction
from models.budget import BudgetLimit
from categorizer.transaction_categorizer import normalize_category_name

# Named constant for near-zero baseline guard: return None if baseline comparison is < ₹100
MIN_MEANINGFUL_BASELINE: float = 100.0

# Categories and review status placeholders excluded from category-based breakdowns
EXCLUDED_CATEGORY_PLACEHOLDERS: set[str] = {
    "needs review",
    "needs_review",
    "other",
    "unassigned",
    "miscellaneous",
}


class BudgetStatus(BaseModel):
    """Structured Pydantic model for category budget limit utilization."""
    category: str
    spent: float
    limit: float
    percent_used: float
    is_alert: bool
    is_family_limit: bool = False


def get_budget_utilization_tone(percent_used: float) -> str:
    """
    Maps budget utilization to the UI severity contract.

    Contract:
    - <80% = normal
    - 80-100% = amber
    - >100% = red
    """
    if percent_used > 100.0:
        return "red"
    if percent_used >= 80.0:
        return "amber"
    return "normal"


async def get_category_totals(
    db: AsyncSession,
    user_id: int,
    year: int,
    month: int,
    include_transfers: bool = False,
) -> Dict[str, float]:
    """
    Computes total debit spending grouped by canonical category for a given month.

    Rules:
    - Normalizes category names via normalize_category_name() BEFORE grouping.
    - Excludes rows where category is in EXCLUDED_CATEGORY_PLACEHOLDERS from per-category breakdowns.
    - Queries the `transactions` table directly.

    Args:
        db (AsyncSession): Active async database session.
        user_id (int): Target user database ID.
        year (int): Target year (e.g. 2026).
        month (int): Target month (1-12).
        include_transfers (bool): If True, includes P2P transfer debits in category breakdown.

    Returns:
        Dict[str, float]: Dictionary mapping normalized category names to total spent (sorted DESC).
    """
    last_day = calendar.monthrange(year, month)[1]
    start_dt = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    end_dt = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    conditions = [
        Transaction.user_id == user_id,
        Transaction.type == "debit",
        Transaction.date >= start_dt,
        Transaction.date <= end_dt,
    ]

    if not include_transfers:
        conditions.append(Transaction.is_transfer == False)
        conditions.append(func.lower(Transaction.category) != "transfer")

    query = select(Transaction).where(and_(*conditions))
    res = await db.execute(query)
    debits = list(res.scalars().all())

    totals: Dict[str, float] = {}
    for tx in debits:
        # Defensive fallback: Transactions are normalized at write time, but we retain
        # read-time normalization here to guard against any legacy or un-migrated records.
        raw_cat = (tx.category or "").strip()
        norm_cat = normalize_category_name(raw_cat)
        if norm_cat.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS:
            continue
        totals[norm_cat] = round(totals.get(norm_cat, 0.0) + float(tx.amount), 2)

    return dict(sorted(totals.items(), key=lambda x: x[1], reverse=True))


async def get_mom_change(
    db: AsyncSession,
    user_id: int,
    year: int,
    month: int,
    category: Optional[str] = None,
    include_transfers: bool = False,
) -> Optional[float]:
    """
    Calculates Month-over-Month spending percentage change.

    Rules:
    - If category is None: computes overall spending MoM change across all categories.
    - If category is provided: computes MoM change for that specific normalized category.
    - Baseline Guard: If previous month spending < MIN_MEANINGFUL_BASELINE (₹100), returns None.

    Args:
        db (AsyncSession): Active async database session.
        user_id (int): Target user database ID.
        year (int): Target year (e.g. 2026).
        month (int): Target month (1-12).
        category (Optional[str]): Normalized category name. If None, calculates total MoM change.
        include_transfers (bool): If True, includes P2P transfers in total/category spend.

    Returns:
        Optional[float]: MoM percentage change rounded to 1 decimal place, or None if
                         previous month spending is below MIN_MEANINGFUL_BASELINE (₹100).
    """
    last_day = calendar.monthrange(year, month)[1]
    cur_start = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    cur_end = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    if month == 1:
        prev_month = 12
        prev_year = year - 1
    else:
        prev_month = month - 1
        prev_year = year
    prev_last_day = calendar.monthrange(prev_year, prev_month)[1]
    prev_start = datetime(prev_year, prev_month, 1, 0, 0, 0, tzinfo=timezone.utc)
    prev_end = datetime(prev_year, prev_month, prev_last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    # Current month debits query
    cur_conds = [
        Transaction.user_id == user_id,
        Transaction.type == "debit",
        Transaction.date >= cur_start,
        Transaction.date <= cur_end,
    ]
    if not include_transfers:
        cur_conds.append(Transaction.is_transfer == False)
        cur_conds.append(func.lower(Transaction.category) != "transfer")

    cur_query = select(Transaction).where(and_(*cur_conds))
    cur_res = await db.execute(cur_query)
    cur_txs = list(cur_res.scalars().all())

    # Previous month debits query
    prev_conds = [
        Transaction.user_id == user_id,
        Transaction.type == "debit",
        Transaction.date >= prev_start,
        Transaction.date <= prev_end,
    ]
    if not include_transfers:
        prev_conds.append(Transaction.is_transfer == False)
        prev_conds.append(func.lower(Transaction.category) != "transfer")

    prev_query = select(Transaction).where(and_(*prev_conds))
    prev_res = await db.execute(prev_query)
    prev_txs = list(prev_res.scalars().all())

    if category:
        target_norm = normalize_category_name(category).lower()
        if target_norm in EXCLUDED_CATEGORY_PLACEHOLDERS:
            return None

        # Defensive fallback: Retain normalize_category_name() comparison to safely handle legacy data
        cur_spent = sum(
            float(tx.amount) for tx in cur_txs
            if normalize_category_name(tx.category or "").lower() == target_norm
        )
        prev_spent = sum(
            float(tx.amount) for tx in prev_txs
            if normalize_category_name(tx.category or "").lower() == target_norm
        )
    else:
        # Overall spend counts every debit, including ones still waiting for review,
        # so it stays consistent with total_spent in get_monthly_overview().
        cur_spent = sum(float(tx.amount) for tx in cur_txs)
        prev_spent = sum(float(tx.amount) for tx in prev_txs)

    # Near-zero baseline guard
    if prev_spent < MIN_MEANINGFUL_BASELINE:
        return None

    return round(((cur_spent - prev_spent) / prev_spent) * 100.0, 1)


async def get_budget_utilization(
    db: AsyncSession,
    user_id: int,
    year: int,
    month: int,
) -> List[BudgetStatus]:
    """
    Single source of truth for category budget limits vs live transaction debits.

    Rules:
    - Joins `budget_limits` with current month debits from `transactions`.
    - Applies normalize_category_name() to both budget limits and transactions.
    - Excludes EXCLUDED_CATEGORY_PLACEHOLDERS from category status lists.
    - Ensures no category appears more than once per user/month.
    - If duplicates exist pre-fix, merges by summing spent and taking min(limit).

    Args:
        db (AsyncSession): Active async database session.
        user_id (int): Target user database ID.
        year (int): Target year (e.g. 2026).
        month (int): Target month (1-12).

    Returns:
        List[BudgetStatus]: Status per configured category budget limit without duplicate categories.
    """
    budgets_query = select(BudgetLimit).where(BudgetLimit.user_id == user_id)
    budgets_res = await db.execute(budgets_query)
    raw_budgets = list(budgets_res.scalars().all())

    if not raw_budgets:
        return []

    # Map normalized category -> consolidated budget metadata (min limit)
    budgets_map: Dict[str, Dict[str, Any]] = {}
    for b in raw_budgets:
        raw_cat = (b.category or "").strip()
        cat_key = normalize_category_name(raw_cat)
        if (
            not cat_key
            or cat_key.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS
            or raw_cat.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS
        ):
            continue
        limit_val = float(b.monthly_limit) if b.monthly_limit else 0.0
        if limit_val <= 0:
            continue
        alert_val = float(b.alert_at_percent or 80.0)

        if cat_key not in budgets_map:
            budgets_map[cat_key] = {
                "category": cat_key,
                "limit": limit_val,
                "alert_at_percent": alert_val,
                "is_family_limit": bool(b.is_family_limit),
            }
        else:
            # If duplicate budget limits exist pre-fix, merge by taking min(limit)
            budgets_map[cat_key]["limit"] = min(budgets_map[cat_key]["limit"], limit_val)
            budgets_map[cat_key]["alert_at_percent"] = min(budgets_map[cat_key]["alert_at_percent"], alert_val)
            budgets_map[cat_key]["is_family_limit"] = (
                budgets_map[cat_key]["is_family_limit"] or bool(b.is_family_limit)
            )

    category_spent_map = await get_category_totals(db, user_id, year, month, include_transfers=False)

    merged_statuses: Dict[str, BudgetStatus] = {}
    for cat_norm, budget_info in budgets_map.items():
        limit_val = budget_info["limit"]
        spent_val = category_spent_map.get(cat_norm, 0.0)
        pct_used = round((spent_val / limit_val) * 100.0, 1) if limit_val > 0 else 0.0
        alert_thresh = budget_info["alert_at_percent"]
        is_alert = pct_used >= alert_thresh

        if cat_norm in merged_statuses:
            prev = merged_statuses[cat_norm]
            new_spent = round(prev.spent + spent_val, 2)
            new_limit = min(prev.limit, limit_val)
            new_pct = round((new_spent / new_limit) * 100.0, 1) if new_limit > 0 else 0.0
            merged_statuses[cat_norm] = BudgetStatus(
                category=cat_norm,
                spent=new_spent,
                limit=new_limit,
                percent_used=new_pct,
                is_alert=new_pct >= alert_thresh or prev.is_alert,
                is_family_limit=prev.is_family_limit or budget_info["is_family_limit"],
            )
        else:
            merged_statuses[cat_norm] = BudgetStatus(
                category=cat_norm,
                spent=spent_val,
                limit=limit_val,
                percent_used=pct_used,
                is_alert=is_alert,
                is_family_limit=budget_info["is_family_limit"],
            )

    return sorted(merged_statuses.values(), key=lambda x: x.percent_used, reverse=True)


async def get_anomalies(
    db: AsyncSession,
    user_id: int,
    year: int,
    month: int,
) -> List[Dict[str, Any]]:
    """
    Detects transaction spikes (>2x historical category avg) and budget breaches (>100% limit).

    Rules:
    - Reuses get_budget_utilization() to detect budget breaches, eliminating duplicate logic.
    - Applies category normalization and excludes review-status placeholders.

    Args:
        db (AsyncSession): Active async database session.
        user_id (int): Target user database ID.
        year (int): Target year (e.g. 2026).
        month (int): Target month (1-12).

    Returns:
        List[Dict[str, Any]]: List of flagged anomaly dictionaries.
    """
    last_day = calendar.monthrange(year, month)[1]
    cur_start = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    cur_end = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    # 1. Rolling category averages prior to current month
    hist_tx_query = select(Transaction).where(
        and_(
            Transaction.user_id == user_id,
            Transaction.type == "debit",
            Transaction.date < cur_start,
        )
    )
    hist_res = await db.execute(hist_tx_query)
    hist_txs = list(hist_res.scalars().all())

    category_sums: Dict[str, float] = {}
    category_counts: Dict[str, int] = {}
    for tx in hist_txs:
        cat_norm = normalize_category_name(tx.category or "")
        if cat_norm.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS:
            continue
        category_sums[cat_norm] = category_sums.get(cat_norm, 0.0) + float(tx.amount)
        category_counts[cat_norm] = category_counts.get(cat_norm, 0) + 1

    category_averages: Dict[str, float] = {
        cat: category_sums[cat] / category_counts[cat]
        for cat in category_sums if category_counts[cat] > 0
    }

    # 2. Target month debits
    cur_tx_query = select(Transaction).where(
        and_(
            Transaction.user_id == user_id,
            Transaction.type == "debit",
            Transaction.date >= cur_start,
            Transaction.date <= cur_end,
        )
    )
    cur_res = await db.execute(cur_tx_query)
    cur_txs = list(cur_res.scalars().all())

    anomalies: List[Dict[str, Any]] = []
    spike_totals: Dict[str, Dict[str, Any]] = {}

    for tx in cur_txs:
        cat_norm = normalize_category_name(tx.category or "")
        if cat_norm.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS:
            continue

        hist_avg = category_averages.get(cat_norm)
        if hist_avg and hist_avg >= 100.0:
            tx_amount = float(tx.amount)
            if tx_amount >= 2 * hist_avg:
                spike = spike_totals.setdefault(
                    cat_norm.lower(),
                    {
                        "kind": "spike",
                        "merchant": f"{cat_norm} spending spike",
                        "amount": 0.0,
                        "avg": round(hist_avg, 2),
                        "category": cat_norm,
                        "date": tx.date.isoformat() if hasattr(tx.date, "isoformat") else str(tx.date),
                    },
                )
                spike["amount"] += tx_amount

    anomalies.extend(
        {**spike, "amount": round(spike["amount"], 2)}
        for spike in spike_totals.values()
    )

    # 3. Use get_budget_utilization to detect breaches (>100%)
    budget_statuses = await get_budget_utilization(db, user_id, year, month)
    seen_keys = set()
    for status in budget_statuses:
        if status.percent_used > 100.0:
            breach_key = (f"Budget Exceeded: {status.category}", status.spent)
            if breach_key not in seen_keys:
                seen_keys.add(breach_key)
                anomalies.append({
                    "kind": "budget",
                    "merchant": f"Over Budget ({status.category})",
                    "amount": round(status.spent, 2),
                    "avg": status.limit,
                    "category": status.category,
                    "date": cur_end.isoformat(),
                })

    return anomalies


async def get_monthly_overview(
    db: AsyncSession,
    user_id: int,
    year: int,
    month: int,
    include_transfers: bool = False,
) -> Dict[str, Any]:
    """
    Unified Monthly Executive Summary.

    Args:
        db (AsyncSession): Active async database session.
        user_id (int): Target user database ID.
        year (int): Target year (e.g. 2026).
        month (int): Target month (1-12).
        include_transfers (bool): Whether P2P transfers are included in overall outflow.

    Returns:
        Dict[str, Any]: Executive summary payload.
    """
    last_day = calendar.monthrange(year, month)[1]
    start_dt = datetime(year, month, 1, 0, 0, 0, tzinfo=timezone.utc)
    end_dt = datetime(year, month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    query = select(Transaction).where(
        and_(
            Transaction.user_id == user_id,
            Transaction.date >= start_dt,
            Transaction.date <= end_dt,
        )
    )
    res = await db.execute(query)
    txs = list(res.scalars().all())

    debits = [t for t in txs if t.type == "debit"]
    credits = [t for t in txs if t.type == "credit"]

    # Uncategorised / needs-review debits are real money out, so they count towards
    # total_spent straight away (they're only left out of the per-category breakdown).
    merchant_debits = [
        t for t in debits
        if not t.is_transfer
        and (t.category or "").lower() != "transfer"
    ]
    transfer_debits = [t for t in debits if t.is_transfer or (t.category or "").lower() == "transfer"]

    merchant_spent = round(sum(float(t.amount) for t in merchant_debits), 2)
    transfer_sent = round(sum(float(t.amount) for t in transfer_debits), 2)
    total_spent = round(merchant_spent + transfer_sent, 2) if include_transfers else merchant_spent

    total_income = round(sum(float(t.amount) for t in credits), 2)
    net_savings = round(total_income - total_spent, 2)

    mom_change = await get_mom_change(db, user_id, year, month, category=None, include_transfers=include_transfers)
    category_totals = await get_category_totals(db, user_id, year, month, include_transfers=include_transfers)

    needs_review_count = len([
        t for t in txs
        if t.review_status == "needs_review" or (t.category or "").lower() in EXCLUDED_CATEGORY_PLACEHOLDERS
    ])

    return {
        "month": month,
        "year": year,
        "total_spent": total_spent,
        "merchant_spent": merchant_spent,
        "transfer_sent": transfer_sent,
        "total_income": total_income,
        "net_savings": net_savings,
        "mom_change_percent": mom_change,
        "total_tx_count": len(txs),
        "needs_review_count": needs_review_count,
        "category_totals": category_totals,
    }
