"""
Shared helpers for sign-in and profile responses.
"""

import os
from datetime import timedelta
from typing import Optional

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from models.budget import OverallBudgetLimit
from models.user import User
from schemas.user import AuthResponse, UserResponse
from utils.auth import create_access_token

# The Android app has no refresh-token flow and syncs SMS in the background, so its session
# must outlive the short default JWT expiry. Configurable per deployment.
APP_SESSION_DAYS = int(os.getenv("JWT_APP_SESSION_DAYS", "30"))


def issue_auth_response(user: User, is_new_user: bool = False) -> AuthResponse:
    """Creates this backend's own JWT for [user] plus the app's routing hints."""
    subject = user.phone_number or user.email or f"user:{user.id}"
    token = create_access_token(
        data={"sub": subject, "user_id": user.id},
        expires_delta=timedelta(days=APP_SESSION_DAYS),
    )
    return AuthResponse(
        access_token=token,
        is_new_user=is_new_user,
        profile_complete=user.profile_completed_at is not None,
        phone_number=user.phone_number,
        email=user.email,
        full_name=user.full_name or "",
    )


async def get_overall_budget(db: AsyncSession, user_id: int) -> Optional[OverallBudgetLimit]:
    result = await db.execute(select(OverallBudgetLimit).where(OverallBudgetLimit.user_id == user_id))
    return result.scalars().first()


async def build_user_response(db: AsyncSession, user: User) -> UserResponse:
    """Profile payload including the overall monthly budget, which lives in its own table."""
    budget = await get_overall_budget(db, user.id)
    response = UserResponse.model_validate(user)
    response.monthly_income = float(user.monthly_income) if user.monthly_income is not None else None
    response.monthly_budget = float(budget.monthly_limit) if budget is not None else None
    return response
