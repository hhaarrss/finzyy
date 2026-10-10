"""
Daily recap push and the daily money question.

The recap is sent by an external scheduler (a GitHub Actions workflow) calling
POST /internal/daily-recap with the shared secret in X-Cron-Secret. Render's free plan has
no cron, and Celery would need Redis plus an always-on worker, so a protected endpoint is
the cheapest reliable trigger.
"""

import os
from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from database import get_db
from models.user import User
from services.daily_recap import build_daily_question, build_recap
from utils.dependencies import get_current_user
from utils.notifications import send_fcm_notification

router = APIRouter(tags=["Engagement"])

CRON_SECRET = os.getenv("CRON_SECRET", "").strip()


def _check_cron_secret(provided: Optional[str]) -> None:
    """
    Compared in constant time: a plain == leaks the secret one character at a time to anyone
    who can measure the response.
    """
    import hmac

    if not CRON_SECRET:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Scheduled jobs are not configured on this server (CRON_SECRET is unset).",
        )
    if not provided or not hmac.compare_digest(provided, CRON_SECRET):
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Not allowed.")


@router.post(
    "/internal/daily-recap",
    summary="Send every user their evening spending recap (scheduler only)",
)
async def send_daily_recaps(
    x_cron_secret: Optional[str] = Header(None, alias="X-Cron-Secret"),
    db: AsyncSession = Depends(get_db),
) -> Dict[str, Any]:
    """
    Sends one push per user who has a notification token and has spent something this month.
    Returns counts only — never names or amounts, since the scheduler's logs are not private.
    """
    _check_cron_secret(x_cron_secret)

    result = await db.execute(select(User).where(User.fcm_token.isnot(None)))
    users = list(result.scalars().all())

    sent = skipped = failed = 0
    for user in users:
        try:
            recap = await build_recap(db, user.id)
        except Exception:
            failed += 1
            continue
        if recap is None:
            skipped += 1  # nothing spent this month; no notification
            continue
        ok = await send_fcm_notification(
            fcm_token=user.fcm_token,
            title=recap["title"],
            body=recap["body"],
            data={"type": "daily_recap", "title": recap["title"], "body": recap["body"]},
        )
        if ok:
            sent += 1
        else:
            failed += 1

    return {"users": len(users), "sent": sent, "skipped": skipped, "failed": failed}


@router.get(
    "/engagement/daily-question",
    summary="Today's question about the signed-in user's own spending",
)
async def daily_question(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Dict[str, Any]:
    """
    A guess-your-own-spending question, built from this week's transactions. `available` is
    false when there isn't enough spending yet, and the app hides the card.
    """
    question = await build_daily_question(db, current_user.id)
    if question is None:
        return {"available": False}
    return {"available": True, **question}
