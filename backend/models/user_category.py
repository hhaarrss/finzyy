"""
SQLAlchemy ORM model for a user's own spending categories.
"""

from datetime import datetime
from sqlalchemy import DateTime, ForeignKey, Integer, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column
from sqlalchemy.sql import func
from database import Base


class UserCategory(Base):
    """
    A spending category the user named themselves ("Pets", "Gym"), offered next to the
    built-in ones. Only the name is stored here: transactions, budgets and merchant
    corrections refer to a category by its name, exactly as they do for built-in categories.
    """

    __tablename__ = "user_categories"
    # Same names as the statements in main.py, which create the table on existing databases.
    __table_args__ = (UniqueConstraint("user_id", "name_key", name="uq_user_categories_user_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    # As the user typed it (trimmed), and its lower-case form for "is this a duplicate?".
    name: Mapped[str] = mapped_column(String(30), nullable=False)
    name_key: Mapped[str] = mapped_column(String(30), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
