"""
Router for the Home Screen data aggregation endpoint.

Provides the unified payload consumed by the mobile/web Home screen:
- Monthly overview (total spend, income, net savings, MoM change)
- Last 5 transactions
- Top 4 category totals (from shared service layer, Needs Review excluded)
- Budget utilization snapshot (top 4)

Auth stub compatible: resolves via get_current_user() which honours AUTH_STUB=true.
"""

from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from database import get_db
from models.user import User
from models.transaction import Transaction
from utils.dependencies import get_current_user
from services.transaction_aggregates import (
    get_monthly_overview,
    get_category_totals,
    get_budget_utilization,
)

router = APIRouter(prefix="/home", tags=["Home Screen"])


@router.get("", summary="Unified Home Screen data payload")
@router.get("/", summary="Unified Home Screen data payload", include_in_schema=False)
async def get_home_data(
    month: Optional[int] = Query(None, ge=1, le=12, description="Target month (1-12), defaults to current month"),
    year: Optional[int] = Query(None, ge=2000, le=2100, description="Target year, defaults to current year"),
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Dict[str, Any]:
    """
    Returns a single consolidated payload for the Home screen.

    Sections:
    - user: display name and id
    - overview: total_spent, total_income, net_savings, mom_change_percent
    - recent_transactions: last 5 transactions (all types)
    - top_categories: top 4 categories by spend (Needs Review excluded)
    - budget_snapshot: top 4 budget utilization entries by percent_used

    Args:
        month (Optional[int]): Target month override (1-12). Defaults to current month.
        year (Optional[int]): Target year override. Defaults to current year.
        current_user (User): Authenticated user (or stub user when AUTH_STUB=true).
        db (AsyncSession): Active async database session.

    Returns:
        Dict[str, Any]: Consolidated Home screen payload.
    """
    now = datetime.now(tz=timezone.utc)
    target_month: int = month if month is not None else now.month
    target_year: int = year if year is not None else now.year

    # 1. Monthly overview (spend, income, MoM change, needs_review_count)
    overview = await get_monthly_overview(
        db, current_user.id, target_year, target_month, include_transfers=False
    )

    # 2. Last 5 transactions (all types, chronological desc)
    recent_query = (
        select(Transaction)
        .where(Transaction.user_id == current_user.id)
        .order_by(Transaction.date.desc())
        .limit(5)
    )
    recent_res = await db.execute(recent_query)
    recent_txs = list(recent_res.scalars().all())

    recent_list: List[Dict[str, Any]] = []
    for tx in recent_txs:
        recent_list.append({
            "id": tx.id,
            "amount": float(tx.amount),
            "type": tx.type,
            "category": tx.category,
            "merchant": tx.merchant_display or tx.merchant,
            "merchant_raw": tx.merchant,
            "date": tx.date.isoformat() if tx.date else None,
            "review_status": tx.review_status,
        })

    # 3. Top 4 category totals (Needs Review already excluded by service layer)
    cat_totals = await get_category_totals(
        db, current_user.id, target_year, target_month, include_transfers=False
    )
    top_categories = [
        {"category": cat, "spent": amount}
        for cat, amount in list(cat_totals.items())[:4]
    ]

    # 4. Budget snapshot — top 4 by percent_used
    budget_statuses = await get_budget_utilization(db, current_user.id, target_year, target_month)
    budget_snapshot = [
        {
            "category": b.category,
            "spent": b.spent,
            "limit": b.limit,
            "percent_used": b.percent_used,
            "is_alert": b.is_alert,
        }
        for b in budget_statuses[:4]
    ]

    return {
        "user": {
            "id": current_user.id,
            "full_name": current_user.full_name,
            "email": current_user.email,
        },
        "month": target_month,
        "year": target_year,
        "overview": {
            "total_spent": overview["total_spent"],
            "total_income": overview["total_income"],
            "net_savings": overview["net_savings"],
            "mom_change_percent": overview["mom_change_percent"],
            "needs_review_count": overview["needs_review_count"],
        },
        "recent_transactions": recent_list,
        "top_categories": top_categories,
        "budget_snapshot": budget_snapshot,
    }
