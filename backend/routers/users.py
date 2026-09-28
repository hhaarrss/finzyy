"""
FastAPI router for user settings and FCM token registration.
"""

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession
from starlette.concurrency import run_in_threadpool

from database import get_db
from models.budget import BudgetLimit, OverallBudgetLimit
from models.budget_alert_log import BudgetAlertLog
from models.family import FamilyGroup
from models.merchant_mapping import MerchantMapping
from models.transaction import Transaction
from models.user import User
from schemas.account import DeleteAccountRequest, DeleteAccountResponse
from schemas.user import ProfileUpdate, UserResponse
from services.accounts import build_user_response, get_overall_budget
from utils.auth import verify_password
from utils.firebase_admin_client import delete_firebase_users
from utils.dependencies import get_current_user


router = APIRouter(prefix="/users", tags=["Users"])


class FCMTokenRequest(BaseModel):
    """Payload for saving FCM push notification token."""
    fcm_token: str = Field(..., max_length=500, description="Firebase Cloud Messaging device token.")


@router.post(
    "/fcm-token",
    status_code=status.HTTP_200_OK,
    summary="Register FCM push notification device token"
)
async def update_fcm_token(
    payload: FCMTokenRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db)
):
    """
    Saves or updates the user's FCM device token for push notifications.
    """
    current_user.fcm_token = payload.fcm_token
    await db.commit()
    return {"message": "FCM device token registered successfully"}


@router.get(
    "/me",
    response_model=UserResponse,
    summary="Get the signed-in user's profile",
)
async def get_my_profile(
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> UserResponse:
    """Returns the profile, including whether profile setup has been completed."""
    return await build_user_response(db, current_user)


@router.put(
    "/me/profile",
    response_model=UserResponse,
    summary="Create or update the signed-in user's profile",
)
async def update_my_profile(
    payload: ProfileUpdate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> UserResponse:
    """
    Saves the details from the profile setup / edit screen and marks the profile complete.
    The monthly budget is stored as the user's overall budget, so the Budget screen is set up
    straight away.
    """
    if payload.email is not None:
        email = payload.email.lower()
        result = await db.execute(
            select(User).where(func.lower(User.email) == email, User.id != current_user.id)
        )
        if result.scalars().first() is not None:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="This email is already used by another account.",
            )
        current_user.email = email

    current_user.full_name = payload.full_name.strip()
    current_user.date_of_birth = payload.date_of_birth
    current_user.gender = payload.gender
    current_user.city = payload.city.strip() if payload.city else None
    current_user.occupation = payload.occupation.strip() if payload.occupation else None
    current_user.monthly_income = payload.monthly_income
    if current_user.profile_completed_at is None:
        current_user.profile_completed_at = datetime.now(timezone.utc)

    if payload.monthly_budget is not None:
        budget = await get_overall_budget(db, current_user.id)
        if budget is None:
            db.add(OverallBudgetLimit(user_id=current_user.id, monthly_limit=payload.monthly_budget))
        else:
            budget.monthly_limit = payload.monthly_budget

    await db.commit()
    await db.refresh(current_user)
    return await build_user_response(db, current_user)


@router.delete(
    "/me",
    response_model=DeleteAccountResponse,
    status_code=status.HTTP_200_OK,
    summary="Permanently delete the authenticated account and its data",
)
async def delete_my_account(
    payload: DeleteAccountRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> DeleteAccountResponse:
    """Delete the current account after explicit confirmation and re-authentication."""
    if payload.confirmation_text.strip().upper() != "DELETE":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Type DELETE to confirm account deletion.",
        )

    # Google-only accounts may not have a local password hash. The existing
    # JWT is the available authentication factor for those accounts.
    if current_user.hashed_password:
        if not payload.password or not verify_password(
            payload.password,
            current_user.hashed_password,
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Incorrect password.",
                headers={"WWW-Authenticate": "Bearer"},
            )

    user_id = current_user.id
    # Read before the row is deleted; used to remove the matching Firebase sign-in users.
    phone_number = current_user.phone_number
    email = current_user.email

    # Explicit child deletes make the endpoint safe even where an existing
    # deployment has not yet applied all database-level cascade constraints.
    await db.execute(delete(Transaction).where(Transaction.user_id == user_id))
    await db.execute(delete(BudgetLimit).where(BudgetLimit.user_id == user_id))
    await db.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user_id))
    await db.execute(delete(BudgetAlertLog).where(BudgetAlertLog.user_id == user_id))
    await db.execute(delete(OverallBudgetLimit).where(OverallBudgetLimit.user_id == user_id))
    # A family group this user runs with nobody else in it would outlive the account (the FK
    # only nulls its admin), keeping the name they typed. Groups other people are in stay.
    other_members = (
        select(func.count(User.id))
        .where(User.family_id == FamilyGroup.id, User.id != user_id)
        .scalar_subquery()
    )
    await db.execute(
        delete(FamilyGroup)
        .where(FamilyGroup.admin_user_id == user_id, other_members == 0)
        .execution_options(synchronize_session=False)
    )
    await db.execute(delete(User).where(User.id == user_id))
    await db.commit()

    # After the commit, so a Firebase outage can't block or roll back deleting the data. The
    # Admin SDK is blocking, hence the thread pool.
    await run_in_threadpool(delete_firebase_users, phone_number, email)

    return DeleteAccountResponse(
        success=True,
        message="Your account and all associated data have been permanently deleted.",
        deleted_at=datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    )
