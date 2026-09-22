"""phone sign-in and profile fields on users

Revision ID: a1b2c3d4e5f6
Revises: 7a8b9c0d1e2f
Create Date: 2026-09-22 20:00:00.000000

"""
from typing import Sequence, Union

from alembic import op


# revision identifiers, used by Alembic.
revision: str = "a1b2c3d4e5f6"
down_revision: Union[str, None] = "7a8b9c0d1e2f"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


# Kept identical to main.AUTH_SCHEMA_STATEMENTS (which also runs at startup); all idempotent.
STATEMENTS = [
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20)",
    "CREATE UNIQUE INDEX IF NOT EXISTS ix_users_phone_number ON users (phone_number)",
    "ALTER TABLE users ALTER COLUMN email DROP NOT NULL",
    "ALTER TABLE users ALTER COLUMN hashed_password DROP NOT NULL",
    "ALTER TABLE users ALTER COLUMN full_name SET DEFAULT ''",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS date_of_birth DATE",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS gender VARCHAR(30)",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS city VARCHAR(100)",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS occupation VARCHAR(100)",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS monthly_income NUMERIC(12, 2)",
    "ALTER TABLE users ADD COLUMN IF NOT EXISTS profile_completed_at TIMESTAMP WITH TIME ZONE",
]


def upgrade() -> None:
    """Adds phone login + profile columns to users."""
    for statement in STATEMENTS:
        op.execute(statement)


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_users_phone_number")
    for column in (
        "phone_number",
        "date_of_birth",
        "gender",
        "city",
        "occupation",
        "monthly_income",
        "profile_completed_at",
    ):
        op.execute(f"ALTER TABLE users DROP COLUMN IF EXISTS {column}")
