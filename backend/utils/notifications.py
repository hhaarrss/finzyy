"""
Notification & FCM Dispatch Engine.

Sends FCM push notifications for 80% & 100% budget threshold alerts,
and logs dispatched notifications to prevent duplicate alerts within the same month.
"""

import os
import calendar
from datetime import datetime, timezone
from typing import Optional, Dict, Any
from sqlalchemy import select, and_, func
from sqlalchemy.ext.asyncio import AsyncSession

from models.user import User
from models.budget import BudgetLimit
from models.transaction import Transaction
from models.budget_alert_log import BudgetAlertLog


async def send_fcm_notification(
    fcm_token: Optional[str],
    title: str,
    body: str,
    data: Optional[Dict[str, str]] = None
) -> bool:
    """
    Dispatches a push notification via Firebase Admin SDK or HTTP payload.
    Falls back gracefully if FCM token or credentials are not configured.
    """
    if not fcm_token or fcm_token.strip() == "":
        return False

    try:
        from firebase_admin import messaging
        from starlette.concurrency import run_in_threadpool

        # Makes sure the Admin SDK has been initialised with the service account; without it
        # messaging.send() raises about a missing default app.
        from utils.firebase_admin_client import ensure_firebase_app

        if ensure_firebase_app() is None:
            print("[Notifications] FCM not configured (no service account); alert not delivered")
            return False

        message = messaging.Message(
            notification=messaging.Notification(
                title=title,
                body=body,
            ),
            data=data or {},
            token=fcm_token,
        )
        # messaging.send() is a blocking HTTP call; off the event loop so one slow send
        # doesn't stall every other request (the daily recap sends one per user in a loop).
        await run_in_threadpool(messaging.send, message)
        return True
    except Exception as e:
        # No title, body or token here: the alert text carries the user's spending figures.
        print(f"[Notifications] FCM send failed ({type(e).__name__}); alert not delivered")
        return False


async def check_budget_and_alert(
    db: AsyncSession,
    user_id: int,
    category: str,
    transaction_amount: float
) -> Optional[str]:
    """
    Evaluates category spending against budget limit after a transaction is created.
    If spend crosses 80% or 100%, dispatches a FCM notification and records it in DB.

    Returns:
        Optional[str]: '80_percent', '100_percent', or None
    """
    if not category or category.lower() == "transfer":
        return None

    now = datetime.now(timezone.utc)
    current_month = now.month
    current_year = now.year

    # 1. Fetch budget limit for this category
    budget_query = select(BudgetLimit).where(
        and_(
            BudgetLimit.user_id == user_id,
            func.lower(BudgetLimit.category) == category.lower()
        )
    )
    b_res = await db.execute(budget_query)
    budget = b_res.scalars().first()

    if not budget or float(budget.monthly_limit) <= 0:
        return None

    limit = float(budget.monthly_limit)

    # 2. Fetch total month spend for this category
    last_day = calendar.monthrange(current_year, current_month)[1]
    start_dt = datetime(current_year, current_month, 1, 0, 0, 0, tzinfo=timezone.utc)
    end_dt = datetime(current_year, current_month, last_day, 23, 59, 59, 999999, tzinfo=timezone.utc)

    spend_query = select(func.coalesce(func.sum(Transaction.amount), 0.0)).where(
        and_(
            Transaction.user_id == user_id,
            Transaction.type == "debit",
            func.lower(Transaction.category) == category.lower(),
            Transaction.date >= start_dt,
            Transaction.date <= end_dt,
            Transaction.is_transfer == False
        )
    )
    s_res = await db.execute(spend_query)
    total_spent = float(s_res.scalar() or 0.0)

    used_pct = (total_spent / limit) * 100.0

    # 3. Check existing alerts for this month
    logs_query = select(BudgetAlertLog.alert_type).where(
        and_(
            BudgetAlertLog.user_id == user_id,
            func.lower(BudgetAlertLog.category) == category.lower(),
            BudgetAlertLog.month == current_month,
            BudgetAlertLog.year == current_year
        )
    )
    l_res = await db.execute(logs_query)
    sent_alerts = set(l_res.scalars().all())

    # Fetch user for FCM token
    u_res = await db.execute(select(User).where(User.id == user_id))
    user = u_res.scalars().first()
    fcm_token = user.fcm_token if user else None

    # 4. Trigger Alert 2 (100% Exceeded)
    if used_pct >= 100.0 and "100_percent" not in sent_alerts:
        over_amt = total_spent - limit
        title = f"🚨 Budget Exceeded — {category}"
        body = f"You've exceeded your {category} budget by ₹{over_amt:.2f}. Limit was ₹{limit:.2f}, spent ₹{total_spent:.2f}."

        await send_fcm_notification(fcm_token, title, body, {"type": "budget_exceeded", "category": category})
        
        db.add(BudgetAlertLog(
            user_id=user_id,
            category=category,
            month=current_month,
            year=current_year,
            alert_type="100_percent"
        ))
        await db.flush()
        return "100_percent"

    # 5. Trigger Alert 1 (80% Warning)
    if used_pct >= 80.0 and "80_percent" not in sent_alerts:
        remaining = limit - total_spent
        title = f"⚠️ Budget Alert — {category}"
        body = f"You've used ₹{total_spent:.2f} of ₹{limit:.2f} ({used_pct:.1f}%) in {category} this month. ₹{remaining:.2f} remaining."

        await send_fcm_notification(fcm_token, title, body, {"type": "budget_warning", "category": category})

        db.add(BudgetAlertLog(
            user_id=user_id,
            category=category,
            month=current_month,
            year=current_year,
            alert_type="80_percent"
        ))
        await db.flush()
        return "80_percent"

    return None
