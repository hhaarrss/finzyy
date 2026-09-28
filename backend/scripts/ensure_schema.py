"""
Applies the idempotent schema statements from main.py (ADD COLUMN IF NOT EXISTS, ...).

Production has never been managed by Alembic (there is no alembic_version table), so these
statements are what keep its schema current. The live server runs them at startup too, but
once it connects with the restricted `smartspend_app` login it isn't allowed to ALTER tables.
Run this with the owner login right before the server starts:

    DATABASE_URL="$MIGRATION_DATABASE_URL" python scripts/ensure_schema.py

See docs/security/database-access.md.
"""

import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from main import ensure_auth_schema  # noqa: E402


if __name__ == "__main__":
    asyncio.run(ensure_auth_schema())
    print("Schema statements applied.")
