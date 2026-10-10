"""
Daily recap and the daily "guess your week" question.

Both are built from the user's own transactions, and both think in the user's local day:
transactions are stored in UTC, but "today" and "this week" mean the Indian calendar day,
so a payment at 11pm IST belongs to that day and not to the next UTC one.
"""

import calendar
import random
from datetime import date, datetime, time, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple
from zoneinfo import ZoneInfo

from sqlalchemy import and_, select
from sqlalchemy.ext.asyncio import AsyncSession

from categorizer.transaction_categorizer import normalize_category_name
from models.budget import OverallBudgetLimit
from models.transaction import Transaction
from services.transaction_aggregates import EXCLUDED_CATEGORY_PLACEHOLDERS

LOCAL_TZ = ZoneInfo("Asia/Kolkata")


def local_now() -> datetime:
    return datetime.now(LOCAL_TZ)


def day_bounds(day: date) -> Tuple[datetime, datetime]:
    """The UTC range covering one local calendar day."""
    start = datetime.combine(day, time.min, tzinfo=LOCAL_TZ)
    return start.astimezone(timezone.utc), (start + timedelta(days=1)).astimezone(timezone.utc)


def week_bounds(day: date) -> Tuple[datetime, datetime]:
    """Monday 00:00 up to the end of [day], in UTC."""
    monday = day - timedelta(days=day.weekday())
    start, _ = day_bounds(monday)
    _, end = day_bounds(day)
    return start, end


async def _debits(db: AsyncSession, user_id: int, start: datetime, end: datetime) -> List[Transaction]:
    """Money actually leaving the account in a window, transfers to people included."""
    result = await db.execute(
        select(Transaction).where(
            and_(
                Transaction.user_id == user_id,
                Transaction.type == "debit",
                Transaction.date >= start,
                Transaction.date < end,
            )
        )
    )
    return list(result.scalars().all())


async def _overall_budget(db: AsyncSession, user_id: int) -> Optional[float]:
    result = await db.execute(select(OverallBudgetLimit).where(OverallBudgetLimit.user_id == user_id))
    row = result.scalars().first()
    return float(row.monthly_limit) if row else None


def _rupees(amount: float) -> str:
    """₹1,234 — whole rupees; paise are noise in a notification."""
    return "₹" + f"{round(amount):,}"


async def build_recap(db: AsyncSession, user_id: int) -> Optional[Dict[str, str]]:
    """
    One evening line about today and the month so far, or None when there is nothing worth
    saying — an account with no spending this month gets no notification rather than a
    "you spent ₹0" nag.
    """
    today = local_now().date()
    day_start, day_end = day_bounds(today)
    month_start, _ = day_bounds(today.replace(day=1))

    today_debits = await _debits(db, user_id, day_start, day_end)
    month_debits = await _debits(db, user_id, month_start, day_end)
    if not month_debits:
        return None

    today_total = sum(float(t.amount) for t in today_debits)
    month_total = sum(float(t.amount) for t in month_debits)
    budget = await _overall_budget(db, user_id)

    if today_debits:
        count = len(today_debits)
        title = f"Today: {_rupees(today_total)}"
        detail = f"{count} payment{'s' if count != 1 else ''} today."
    else:
        title = "Nothing spent today"
        detail = "No payments today."

    month_name = calendar.month_name[today.month]
    if budget:
        left = budget - month_total
        if left >= 0:
            body = f"{detail} {month_name}: {_rupees(month_total)} of {_rupees(budget)} — {_rupees(left)} left."
        else:
            body = f"{detail} {month_name}: {_rupees(month_total)}, which is {_rupees(-left)} over your budget."
    else:
        body = f"{detail} {month_name} so far: {_rupees(month_total)}."

    return {"title": title, "body": body}


def _nice(amount: float) -> int:
    """Rounds to something a person would say: 240, 1,250, 12,500."""
    if amount < 100:
        return max(10, int(round(amount / 10.0)) * 10)
    if amount < 1000:
        return int(round(amount / 10.0)) * 10
    if amount < 10000:
        return int(round(amount / 50.0)) * 50
    return int(round(amount / 500.0)) * 500


async def build_daily_question(db: AsyncSession, user_id: int) -> Optional[Dict[str, Any]]:
    """
    "How much did you spend on Food this week?" with three amounts to choose from.

    The category is whatever the user has spent most on this week, so the question is
    different for everyone and changes as the week goes on. The options are shuffled with a
    seed fixed to the user and the day, so re-opening the screen doesn't reshuffle them.
    """
    today = local_now().date()
    week_start, week_end = week_bounds(today)
    this_week = await _debits(db, user_id, week_start, week_end)
    if not this_week:
        return None

    # Group by canonical category, ignoring the "not sorted yet" placeholders — a question
    # about "Other" teaches the user nothing.
    totals: Dict[str, float] = {}
    for tx in this_week:
        name = normalize_category_name(tx.category or "")
        if name.lower() in EXCLUDED_CATEGORY_PLACEHOLDERS:
            continue
        totals[name] = totals.get(name, 0.0) + float(tx.amount)
    if not totals:
        return None

    category, actual = max(totals.items(), key=lambda kv: kv[1])
    if actual < 50:  # too small to be interesting
        return None

    # Two distractors either side of the real number, far enough apart to be a real guess.
    options = {_nice(actual), _nice(actual * 0.55), _nice(actual * 1.8)}
    while len(options) < 3:  # tiny amounts can round into each other
        options.add(_nice(actual * random.Random(len(options)).uniform(2.2, 3.0)))
    ordered = sorted(options)
    random.Random(f"{user_id}:{today.isoformat()}").shuffle(ordered)

    answer = _nice(actual)
    last_week_day = today - timedelta(days=7)
    lw_start, _ = week_bounds(last_week_day)
    _, lw_end = day_bounds(last_week_day)
    last_week = await _debits(db, user_id, lw_start, lw_end)
    last_week_total = sum(
        float(t.amount) for t in last_week
        if normalize_category_name(t.category or "") == category
    )

    if last_week_total > 0:
        diff = actual - last_week_total
        if abs(diff) < last_week_total * 0.1:
            comparison = f"About the same as this time last week ({_rupees(last_week_total)})."
        elif diff > 0:
            comparison = f"{_rupees(diff)} more than this time last week."
        else:
            comparison = f"{_rupees(-diff)} less than this time last week."
    else:
        comparison = "Nothing in this category at this point last week."

    return {
        "date": today.isoformat(),
        "category": category,
        "prompt": f"How much have you spent on {category} this week?",
        "options": ordered,
        "answer": answer,
        "actual_amount": round(actual, 2),
        "transaction_count": sum(
            1 for t in this_week if normalize_category_name(t.category or "") == category
        ),
        "comparison": comparison,
    }
