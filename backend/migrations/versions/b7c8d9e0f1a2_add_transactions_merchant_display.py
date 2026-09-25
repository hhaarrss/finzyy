"""add transactions.merchant_display

Revision ID: b7c8d9e0f1a2
Revises: a1b2c3d4e5f6
Create Date: 2026-09-25 12:00:00.000000

"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = "b7c8d9e0f1a2"
down_revision: Union[str, None] = "a1b2c3d4e5f6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Brand name for a recognised merchant, kept apart from the raw merchant text."""
    # Same statement as main.TRANSACTION_SCHEMA_STATEMENTS (which also runs at startup).
    op.execute("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS merchant_display VARCHAR(255)")


def downgrade() -> None:
    op.execute("ALTER TABLE transactions DROP COLUMN IF EXISTS merchant_display")
