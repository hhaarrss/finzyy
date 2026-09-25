"""
Insights Utility Engine.

Provides analytical computations for expense tracking, MoM comparisons,
anomaly flagging, subscription discovery, and budget notifications.
"""

from datetime import datetime, timezone, timedelta
from typing import List, Dict, Any, Optional
from sqlalchemy import select, func, and_
from sqlalchemy.ext.asyncio import AsyncSession
from models.transaction import Transaction
from models.budget import BudgetLimit
from categorizer.transaction_categorizer import normalize_category_name
import calendar


async def compare_month_spending(user_id: int, category: str, db: AsyncSession) -> Optional[float]:
    """
    Compares the current month's spending in a category vs the previous month.
    Delegates to the shared transaction aggregates service layer.

    Args:
        user_id (int): ID of the target user.
        category (str): Target expense category.
        db (AsyncSession): Active database session.

    Returns:
        Optional[float]: Percentage change in spending, or None if previous month spending is < MIN_MEANINGFUL_BASELINE.
    """
    from services.transaction_aggregates import get_mom_change
    now = datetime.now(timezone.utc)
    return await get_mom_change(db, user_id, now.year, now.month, category=category)



async def detect_anomalies(user_id: int, db: AsyncSession) -> List[Dict[str, Any]]:
    """
    Detects anomalies in the current month by delegating to services.transaction_aggregates.
    """
    from services.transaction_aggregates import get_anomalies
    now = datetime.now(timezone.utc)
    return await get_anomalies(db, user_id, now.year, now.month)


async def detect_recurring(user_id: int, db: AsyncSession) -> List[Dict[str, Any]]:
    """
    Scans transaction history to identify regular recurring expenses (subscriptions/EMIs).
    Matches based on amount variance (within 5%) and date intervals (25-35 days spacing).

    Args:
        user_id (int): Target user database ID.
        db (AsyncSession): Active database session.

    Returns:
        List[Dict[str, Any]]: Mapped monthly recurring subscriptions.
    """
    # Fetch all historical debits sorted by date ascending
    query = select(Transaction).where(
        and_(
            Transaction.user_id == user_id,
            Transaction.type == "debit"
        )
    ).order_by(Transaction.date.asc())
    
    result = await db.execute(query)
    txs = result.scalars().all()

    # Group transactions by merchant keyword (case-insensitive)
    merchant_groups: Dict[str, List[Transaction]] = {}
    for tx in txs:
        shown = tx.merchant_display or tx.merchant
        if not shown:
            continue
        m_lower = shown.lower().strip()
        if m_lower not in merchant_groups:
            merchant_groups[m_lower] = []
        merchant_groups[m_lower].append(tx)

    recurring = []
    for merchant_name, group in merchant_groups.items():
        if len(group) < 2:
            continue
            
        # Analyze pairs in consecutive windows to find repeating amounts
        for i in range(len(group)):
            for j in range(i + 1, len(group)):
                tx1 = group[i]
                tx2 = group[j]
                
                amt1 = float(tx1.amount)
                amt2 = float(tx2.amount)
                
                # Check 5% tolerance on amounts
                if amt1 == 0:
                    continue
                diff_pct = abs(amt1 - amt2) / amt1
                if diff_pct > 0.05:
                    continue
                
                # Check interval buckets: weekly (6-8 days), monthly (25-35 days), annual (350-380 days)
                day_diff = (tx2.date - tx1.date).days
                frequency = None
                if 6 <= day_diff <= 8:
                    frequency = "weekly"
                elif 25 <= day_diff <= 35:
                    frequency = "monthly"
                elif 350 <= day_diff <= 380:
                    frequency = "annual"

                if frequency:
                    display_merchant = tx2.merchant or merchant_name
                    if not any(r["merchant"].lower() == display_merchant.lower() for r in recurring):
                        recurring.append({
                            "merchant": display_merchant,
                            "amount": amt2,
                            "frequency": frequency
                        })
                    break

    return recurring


async def get_budget_alerts(user_id: int, db: AsyncSession) -> List[Dict[str, Any]]:
    """
    Identifies spending categories in the current month exceeding configured alert thresholds.
    Delegates to services.transaction_aggregates get_budget_utilization.
    """
    from services.transaction_aggregates import get_budget_utilization
    now = datetime.now(timezone.utc)
    utilization = await get_budget_utilization(db, user_id, now.year, now.month)
    return [
        {
            "category": s.category,
            "spent": round(s.spent, 2),
            "limit": s.limit,
            "percent": round(s.percent_used, 2)
        }
        for s in utilization
        if s.is_alert
    ]

