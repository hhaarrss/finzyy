"""
SQLAlchemy ORM model for User.
"""

from datetime import date, datetime
from decimal import Decimal
from typing import List, Optional, TYPE_CHECKING
from sqlalchemy import Date, Integer, Numeric, String, DateTime, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.sql import func
from database import Base

if TYPE_CHECKING:
    from models.family import FamilyGroup
    from models.transaction import Transaction
    from models.budget import BudgetLimit
    from models.merchant_mapping import MerchantMapping


class User(Base):
    """
    User model representing a registered system user.
    """

    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    # Phone+OTP users may have no email or password; email/password accounts from before
    # phone login keep both.
    email: Mapped[Optional[str]] = mapped_column(
        String(255), unique=True, index=True, nullable=True
    )
    hashed_password: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    # E.164 format (e.g. "+919876543210"), verified through Firebase Phone Auth.
    phone_number: Mapped[Optional[str]] = mapped_column(
        String(20), unique=True, index=True, nullable=True
    )
    full_name: Mapped[str] = mapped_column(String(100), nullable=False, default="", server_default="")

    # Profile details collected on the profile setup screen after first sign-in.
    date_of_birth: Mapped[Optional[date]] = mapped_column(Date, nullable=True)
    gender: Mapped[Optional[str]] = mapped_column(String(30), nullable=True)
    city: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    occupation: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    monthly_income: Mapped[Optional[Decimal]] = mapped_column(Numeric(12, 2), nullable=True)
    profile_completed_at: Mapped[Optional[datetime]] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    family_id: Mapped[Optional[int]] = mapped_column(
        Integer, ForeignKey("family_groups.id", ondelete="SET NULL"), nullable=True
    )
    fcm_token: Mapped[Optional[str]] = mapped_column(String(500), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    # Relationships
    family: Mapped[Optional["FamilyGroup"]] = relationship(
        "FamilyGroup",
        back_populates="members",
        foreign_keys=[family_id],
    )
    
    transactions: Mapped[List["Transaction"]] = relationship(
        "Transaction",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    
    budgets: Mapped[List["BudgetLimit"]] = relationship(
        "BudgetLimit",
        back_populates="user",
        cascade="all, delete-orphan",
    )

    merchant_mappings: Mapped[List["MerchantMapping"]] = relationship(
        "MerchantMapping",
        back_populates="user",
        cascade="all, delete-orphan",
    )
