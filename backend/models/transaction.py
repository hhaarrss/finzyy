"""
SQLAlchemy ORM model for Transaction.
"""

from datetime import datetime
from typing import Optional, TYPE_CHECKING
from sqlalchemy import Integer, Numeric, String, DateTime, ForeignKey, Date, Text, Boolean
from sqlalchemy.orm import Mapped, mapped_column, relationship, validates
from sqlalchemy.sql import func
from database import Base

if TYPE_CHECKING:
    from models.user import User


class Transaction(Base):
    """
    Transaction model representing a financial debit or credit entry.
    """

    __tablename__ = "transactions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    amount: Mapped[float] = mapped_column(Numeric(12, 2), nullable=False)
    type: Mapped[str] = mapped_column(
        String(20), nullable=False
    )  # e.g., 'debit' or 'credit'
    category: Mapped[str] = mapped_column(String(100), default="Miscellaneous", server_default="Miscellaneous", nullable=False, index=True)
    # Exactly what the bank / user gave us. Never replaced by a brand match.
    merchant: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    # Clean brand name ("Swiggy") when the brand itself was recognised in `merchant`; else NULL.
    merchant_display: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    subcategory: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    upi_ref: Mapped[Optional[str]] = mapped_column(String(100), nullable=True)
    source: Mapped[Optional[str]] = mapped_column(
        String(100), default="manual", nullable=True
    )  # which step matched ('sms', 'aa', 'manual')
    confidence: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)  # high / medium / low / none
    review_status: Mapped[Optional[str]] = mapped_column(
        String(50), default="reviewed", server_default="reviewed", nullable=True, index=True
    )  # needs_review / reviewed / auto_categorized
    bank: Mapped[Optional[str]] = mapped_column(String(50), nullable=True)
    account_last4: Mapped[Optional[str]] = mapped_column(String(4), nullable=True)
    date: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, index=True
    )
    hash_fingerprint: Mapped[Optional[str]] = mapped_column(
        String(64), unique=True, index=True, nullable=True
    )
    notes: Mapped[Optional[str]] = mapped_column(Text, nullable=True)
    is_transfer: Mapped[bool] = mapped_column(
        Boolean, default=False, server_default="false", nullable=False, index=True
    )
    transfer_to: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    # Relationships
    user: Mapped["User"] = relationship("User", back_populates="transactions")

    @validates("category")
    def validate_category(self, key: str, value: Optional[str]) -> str:
        """
        Validates and normalizes category name at write time to ensure canonical values.
        """
        from categorizer.transaction_categorizer import normalize_category_name
        if not value:
            return "Other"
        return normalize_category_name(value)
