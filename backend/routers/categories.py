"""
Router for Transaction Categories (Debit and Credit), including a user's own spending
categories.
"""

from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, HTTPException, status
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from constants.categories import CREDIT_CATEGORIES
from database import get_db
from models.budget import BudgetLimit
from models.budget_alert_log import BudgetAlertLog
from models.merchant_mapping import MerchantMapping
from models.transaction import Transaction
from models.user import User
from models.user_category import UserCategory
from services.custom_categories import (
    MAX_CUSTOM_CATEGORIES,
    CategoryNameError,
    clean_name,
    name_key,
    spending_list,
)
from utils.dependencies import get_current_user, oauth2_scheme

router = APIRouter(prefix="/categories", tags=["Categories"])


class CustomCategoryCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(..., max_length=100, description="The name as the user typed it.")


class CustomCategoryResponse(BaseModel):
    id: int
    name: str


async def get_optional_user(
    token: str = Depends(oauth2_scheme),
    db: AsyncSession = Depends(get_db),
) -> Optional[User]:
    """
    The signed-in user, or None. The built-in list has always been readable without signing
    in, so a missing or expired token must not turn that into an error: it just means no
    custom categories are added.
    """
    try:
        return await get_current_user(token=token, db=db)
    except HTTPException:
        return None


async def _custom_rows(db: AsyncSession, user_id: int):
    result = await db.execute(
        select(UserCategory).where(UserCategory.user_id == user_id).order_by(UserCategory.created_at, UserCategory.id)
    )
    return list(result.scalars().all())


@router.get("", summary="Get the debit and credit category lists")
@router.get("/", summary="Get the debit and credit category lists", include_in_schema=False)
async def get_categories(
    user: Optional[User] = Depends(get_optional_user),
    db: AsyncSession = Depends(get_db),
) -> Dict[str, Any]:
    """
    Built-in categories, plus the signed-in user's own spending categories.

    `debit` already contains the custom names (before Transfer and Other), so every screen
    that lists spending categories shows them without knowing they are custom. `custom`
    repeats them with their ids, for the screen that manages them.
    """
    custom = await _custom_rows(db, user.id) if user is not None else []
    return {
        "debit": spending_list(c.name for c in custom),
        "credit": CREDIT_CATEGORIES,
        "custom": [{"id": c.id, "name": c.name} for c in custom],
    }


@router.post(
    "/custom",
    response_model=CustomCategoryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Create one of the user's own spending categories",
)
async def create_custom_category(
    payload: CustomCategoryCreate,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> CustomCategoryResponse:
    try:
        name = clean_name(payload.name)
    except CategoryNameError as e:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(e))

    existing = await _custom_rows(db, current_user.id)
    key = name_key(name)
    if any(c.name_key == key for c in existing):
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"You already have a category called \"{name}\".")
    if len(existing) >= MAX_CUSTOM_CATEGORIES:
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail=f"You can have up to {MAX_CUSTOM_CATEGORIES} of your own categories. Delete one to add another.",
        )

    row = UserCategory(user_id=current_user.id, name=name, name_key=key)
    db.add(row)
    try:
        await db.commit()
    except IntegrityError:
        # Two requests for the same name at once: the unique constraint decides.
        await db.rollback()
        raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail=f"You already have a category called \"{name}\".")
    await db.refresh(row)
    return CustomCategoryResponse(id=row.id, name=row.name)


@router.delete("/custom/{category_id}", summary="Delete one of the user's own categories")
async def delete_custom_category(
    category_id: int,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> Dict[str, Any]:
    """
    Removes the category and keeps the payments: anything filed under it moves to "Other".
    Its budget and the merchant corrections that pointed at it are removed, so nothing keeps
    filing new payments under a name that no longer exists.
    """
    result = await db.execute(
        select(UserCategory).where(UserCategory.id == category_id, UserCategory.user_id == current_user.id)
    )
    row = result.scalars().first()
    if row is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Category not found")

    key = row.name_key
    name = row.name
    uid = current_user.id
    moved = await db.execute(
        update(Transaction)
        .where(Transaction.user_id == uid, func.lower(Transaction.category) == key)
        .values(category="Other", subcategory=None)
        .execution_options(synchronize_session=False)
    )
    await db.execute(delete(BudgetLimit).where(BudgetLimit.user_id == uid, func.lower(BudgetLimit.category) == key))
    await db.execute(delete(BudgetAlertLog).where(BudgetAlertLog.user_id == uid, func.lower(BudgetAlertLog.category) == key))
    await db.execute(delete(MerchantMapping).where(MerchantMapping.user_id == uid, func.lower(MerchantMapping.category) == key))
    await db.delete(row)
    await db.commit()
    return {"deleted": name, "moved_transactions": moved.rowcount or 0}
