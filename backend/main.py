"""
Smart Expense Tracker API - Main Entry Point.

Initializes the FastAPI application, sets up CORS middleware,
registers all feature routers, and registers lifetime event handlers.
"""

import os
from pathlib import Path
from dotenv import load_dotenv, find_dotenv

def mask_url(url: str | None) -> str:
    """
    Mask sensitive user credentials (like password) in the database connection URL.

    Args:
        url (str | None): The database connection URL to mask.

    Returns:
        str: The masked database URL or a placeholder if invalid/missing.
    """
    if not url:
        return "None"
    try:
        if "@" in url:
            parts = url.split("@")
            prefix = parts[0]
            suffix = parts[1]
            if "://" in prefix:
                scheme, auth = prefix.split("://", 1)
                if ":" in auth:
                    username, _ = auth.split(":", 1)
                    return f"{scheme}://{username}:*****@{suffix}"
                return f"{scheme}://*****@{suffix}"
            return f"*****@{suffix}"
        return "*****"
    except Exception:
        return "*****"

# Find and load .env file from any directory
dotenv_path = find_dotenv(usecwd=True)
if not dotenv_path:
    dotenv_path = str(Path(__file__).resolve().parent / '.env')

# Do NOT override system environment variables (e.g., Render Dashboard environment variables)
load_dotenv(dotenv_path, override=False)

DATABASE_URL = os.getenv("DATABASE_URL")

# Fallback: if DATABASE_URL is still not found, try the parent directory
if not DATABASE_URL:
    parent_dotenv_path = str(Path(__file__).resolve().parent.parent / '.env')
    if os.path.exists(parent_dotenv_path):
        dotenv_path = parent_dotenv_path
        load_dotenv(dotenv_path, override=False)
        DATABASE_URL = os.getenv("DATABASE_URL")

print(f"Debug: Loaded env from: {dotenv_path} | DATABASE_URL: {mask_url(DATABASE_URL)}")

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

# Import routers
from routers.auth import router as auth_router
from routers.transactions import router as transactions_router
from routers.budget import router as budget_router
from routers.family import router as family_router
from routers.insights import router as insights_router
from routers.seed import router as seed_router
from routers.categories import router as categories_router
from routers.users import router as users_router
from routers.home import router as home_router

# Import database engine for startup check
from database import engine

# App Configuration
APP_ENV = os.getenv("APP_ENV", "development")
APP_PORT = int(os.getenv("APP_PORT", "8000"))

# Create FastAPI Instance
app = FastAPI(
    title="Smart Expense Tracker API",
    description=(
        "FastAPI Backend with async/await, SQLAlchemy, PostgreSQL, "
        "JWT Authentication, Redis + Celery workers, and Alembic migrations."
    ),
    version="1.0.0",
    docs_url="/docs",
    redoc_url="/redoc",
)

# CORS: the only client is the Android app, which ignores CORS, so no browser origin is
# allowed by default. Set ALLOWED_ORIGINS (comma-separated) only if a browser client returns.
raw_origins = os.getenv("ALLOWED_ORIGINS", "")
ALLOWED_ORIGINS = [origin.strip() for origin in raw_origins.split(",") if origin.strip()]

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Global exception handler to ensure 500 errors return proper JSON
# (prevents bare 500s from stripping CORS headers in the browser)
from fastapi import Request
from fastapi.responses import JSONResponse
import traceback as tb

@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """
    Catch-all handler for unhandled exceptions.
    Returns a JSON response so CORSMiddleware can attach headers properly.
    """
    print(f"[UNHANDLED ERROR] {request.method} {request.url}")
    tb.print_exception(type(exc), exc, exc.__traceback__)
    return JSONResponse(
        status_code=500,
        content={"detail": f"Internal server error: {str(exc)}"},
    )


# Register routers
app.include_router(auth_router)
app.include_router(transactions_router)
app.include_router(budget_router)
app.include_router(family_router)
app.include_router(insights_router)
app.include_router(seed_router)
app.include_router(categories_router)
app.include_router(users_router)
app.include_router(home_router)


# Phone sign-in + profile columns. Every statement is idempotent, so this is safe to run on
# every start in every environment — it keeps an existing database working even where the
# Alembic migration (a1b2c3d4e5f6) has not been run as a pre-deploy step.
AUTH_SCHEMA_STATEMENTS = [
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


async def ensure_auth_schema() -> None:
    from sqlalchemy import text
    async with engine.begin() as conn:
        for statement in AUTH_SCHEMA_STATEMENTS:
            await conn.execute(text(statement))


@app.on_event("startup")
async def on_startup() -> None:
    print("Initializing Smart Expense Tracker Backend...")

    if APP_ENV == "production":
        # In production, schema is managed by Alembic migrations (pre-deploy command).
        # Skip dev-only ALTER TABLE mutations and seed user creation.
        print("Production mode — skipping dev schema mutations and seed user.")
        # Still verify DB connectivity
        try:
            async with engine.begin() as conn:
                from sqlalchemy import text
                await conn.execute(text("SELECT 1"))
            print("Database connection verified.")
            await ensure_auth_schema()
        except Exception as e:
            print(f"Warning: Database connectivity check failed: {e}")
        return

    try:
        from database import Base, AsyncSessionLocal
        from models.user import User
        from models.transaction import Transaction
        from models.budget import BudgetLimit, OverallBudgetLimit
        from models.merchant_mapping import MerchantMapping
        from utils.auth import hash_password
        from sqlalchemy import select

        async with engine.begin() as conn:
            from sqlalchemy import text
            # Create tables first (no-op if they already exist)
            await conn.run_sync(Base.metadata.create_all)
            # Then add any columns not yet in the schema
            await conn.execute(text("ALTER TABLE users ADD COLUMN IF NOT EXISTS fcm_token VARCHAR(500);"))
            await conn.execute(text("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS is_transfer BOOLEAN DEFAULT FALSE;"))
            await conn.execute(text("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS transfer_to VARCHAR(255);"))
            await conn.execute(text("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS notes TEXT;"))
        await ensure_auth_schema()
        print("Database connection successfully established, schema migrated, and tables verified.")

        async with AsyncSessionLocal() as db:
            res = await db.execute(select(User).where(User.email == "your1_email@example.com"))
            if not res.scalars().first():
                user = User(
                    email="your1_email@example.com",
                    hashed_password=hash_password("YourPassword123!"),
                    full_name="User One",
                    is_active=True
                )
                db.add(user)
                await db.commit()
                print("Default seed user created: your1_email@example.com")
    except Exception as e:
        print(f"Warning: Database initialization during startup error: {e}")

@app.get("/", tags=["Health"])
async def root() -> dict:
    """
    Health check endpoint returning system status and current environment.

    Returns:
        dict: Standard system status message.
    """
    return {
        "status": "healthy",
        "app": "Smart Expense Tracker API",
        "version": "1.0.0",
        "environment": APP_ENV,
    }
