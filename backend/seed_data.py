"""
Database Seed Script for Smart Expense Tracker.

Populates realistic testing data across 3 full months (June 2026, July 2026, August 2026)
to demonstrate multi-month MoM analytics, 3-month spending trends, subscription tracking, and budget alerts.
"""

import asyncio
from datetime import datetime, timezone
from sqlalchemy import select, delete
from database import AsyncSessionLocal
from models.user import User
from models.transaction import Transaction
from models.budget import BudgetLimit
from models.family import FamilyGroup
import os
import secrets

from utils.auth import hash_password


async def seed_database() -> None:
    """
    Asynchronously seeds the PostgreSQL database with 3 full months of test data for presentation.
    """
    async with AsyncSessionLocal() as session:
        print("[SEED] Starting database seeding process...")

        # 1. Check or create demo user
        user_email = "demo@localhost.test"
        demo_password = os.getenv("DEMO_SEED_PASSWORD", "").strip() or secrets.token_urlsafe(12)
        result = await session.execute(select(User).where(User.email == user_email))
        user = result.scalars().first()

        if not user:
            print(f"[SEED] Creating demo user: {user_email}")
            user = User(
                email=user_email,
                hashed_password=hash_password(demo_password),
                full_name="Test User",
            )
            print(f"[SEED] Demo password (shown once): {demo_password}")
            session.add(user)
            await session.flush()
        else:
            print(f"[SEED] Found existing user: {user.full_name} ({user.email})")

        # 2. Check or create Family Group
        family_query = await session.execute(select(FamilyGroup).where(FamilyGroup.name == "Rabadiya Family"))
        family = family_query.scalars().first()

        if not family:
            print("[SEED] Creating Family Group: Rabadiya Family")
            family = FamilyGroup(name="Rabadiya Family", admin_user_id=user.id)
            session.add(family)
            await session.flush()

        user.family_id = family.id

        # 3. Clean existing transactions & budgets for user to ensure clean seed
        await session.execute(delete(Transaction).where(Transaction.user_id == user.id))
        await session.execute(delete(BudgetLimit).where(BudgetLimit.user_id == user.id))
        await session.flush()

        # 4. Seed Category Budget Limits
        budgets_data = [
            {"category": "Food", "monthly_limit": 7000.0, "alert_at_percent": 80.0, "is_family_limit": False},
            {"category": "Shopping", "monthly_limit": 12000.0, "alert_at_percent": 80.0, "is_family_limit": True},
            {"category": "Travel", "monthly_limit": 15000.0, "alert_at_percent": 80.0, "is_family_limit": False},
            {"category": "Utilities", "monthly_limit": 6000.0, "alert_at_percent": 80.0, "is_family_limit": True},
            {"category": "Entertainment", "monthly_limit": 4000.0, "alert_at_percent": 80.0, "is_family_limit": False},
            {"category": "Healthcare", "monthly_limit": 5000.0, "alert_at_percent": 80.0, "is_family_limit": False},
        ]

        for b in budgets_data:
            budget_obj = BudgetLimit(
                user_id=user.id,
                category=b["category"],
                monthly_limit=b["monthly_limit"],
                alert_at_percent=b["alert_at_percent"],
                is_family_limit=b["is_family_limit"],
            )
            session.add(budget_obj)

        print("[SEED] Budget limits configured successfully.")

        # 5. Seed 3 Months of Transactions (June 2026, July 2026, August 2026)
        transactions_data = [
            # --- AUGUST 2026 (Current Month) ---
            {
                "amount": 95000.00,
                "type": "credit",
                "category": "Salary",
                "merchant": "TechCorp Pvt Ltd",
                "source": "sms",
                "date": datetime(2026, 8, 1, 9, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 580.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Swiggy",
                "source": "sms",
                "date": datetime(2026, 8, 2, 13, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2200.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Shell Fuel Station",
                "source": "manual",
                "date": datetime(2026, 8, 3, 10, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 4800.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Amazon India",
                "source": "sms",
                "date": datetime(2026, 8, 4, 11, 45, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1199.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Airtel Broadband",
                "source": "manual",
                "date": datetime(2026, 8, 4, 16, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1250.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Zomato Gourmet",
                "source": "manual",
                "date": datetime(2026, 8, 5, 20, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2500.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "BookMyShow Concert",
                "source": "manual",
                "date": datetime(2026, 8, 6, 18, 20, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 8500.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "IndiGo Flight Tickets",
                "source": "manual",
                "date": datetime(2026, 8, 7, 14, 10, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 3200.00,
                "type": "debit",
                "category": "Healthcare",
                "merchant": "Max Healthcare Dental",
                "source": "manual",
                "date": datetime(2026, 8, 7, 17, 45, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 3850.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Nature's Basket Groceries",
                "source": "manual",
                "date": datetime(2026, 8, 8, 12, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 6500.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Zara Apparel",
                "source": "manual",
                "date": datetime(2026, 8, 8, 19, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 3100.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Electricity Board",
                "source": "manual",
                "date": datetime(2026, 8, 9, 9, 10, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 750.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Uber Premier",
                "source": "manual",
                "date": datetime(2026, 8, 9, 21, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 420.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Starbucks Coffee",
                "source": "manual",
                "date": datetime(2026, 8, 10, 15, 40, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2400.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Flipkart Electronics",
                "source": "manual",
                "date": datetime(2026, 8, 11, 10, 25, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 649.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Netflix India",
                "source": "manual",
                "date": datetime(2026, 8, 11, 14, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 119.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "Spotify Premium",
                "source": "manual",
                "date": datetime(2026, 8, 11, 16, 30, 0, tzinfo=timezone.utc),
            },

            # --- JULY 2026 (Month -1) ---
            {
                "amount": 95000.00,
                "type": "credit",
                "category": "Salary",
                "merchant": "TechCorp Pvt Ltd",
                "source": "manual",
                "date": datetime(2026, 7, 1, 9, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1199.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Airtel Broadband",
                "source": "manual",
                "date": datetime(2026, 7, 4, 16, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 450.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Swiggy",
                "source": "manual",
                "date": datetime(2026, 7, 5, 13, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2000.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Shell Fuel Station",
                "source": "manual",
                "date": datetime(2026, 7, 8, 10, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 3200.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Amazon Online",
                "source": "manual",
                "date": datetime(2026, 7, 10, 11, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2850.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Electricity Board",
                "source": "manual",
                "date": datetime(2026, 7, 10, 14, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 620.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Zomato",
                "source": "manual",
                "date": datetime(2026, 7, 12, 20, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1100.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "PVR Cinemas",
                "source": "manual",
                "date": datetime(2026, 7, 14, 18, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 650.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Uber Rides",
                "source": "manual",
                "date": datetime(2026, 7, 15, 11, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 649.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Netflix India",
                "source": "manual",
                "date": datetime(2026, 7, 15, 14, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 119.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "Spotify Premium",
                "source": "manual",
                "date": datetime(2026, 7, 16, 16, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2500.00,
                "type": "debit",
                "category": "Food",
                "merchant": "D-Mart Groceries",
                "source": "manual",
                "date": datetime(2026, 7, 18, 12, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 850.00,
                "type": "debit",
                "category": "Healthcare",
                "merchant": "Apollo Pharmacy",
                "source": "manual",
                "date": datetime(2026, 7, 19, 15, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2100.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Myntra Fashion",
                "source": "manual",
                "date": datetime(2026, 7, 20, 17, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 420.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Ola Cabs",
                "source": "manual",
                "date": datetime(2026, 7, 22, 9, 45, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 380.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Starbucks",
                "source": "manual",
                "date": datetime(2026, 7, 25, 16, 10, 0, tzinfo=timezone.utc),
            },

            # --- JUNE 2026 (Month -2) ---
            {
                "amount": 95000.00,
                "type": "credit",
                "category": "Salary",
                "merchant": "TechCorp Pvt Ltd",
                "source": "manual",
                "date": datetime(2026, 6, 1, 9, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1199.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Airtel Broadband",
                "source": "manual",
                "date": datetime(2026, 6, 5, 16, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 400.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Swiggy",
                "source": "manual",
                "date": datetime(2026, 6, 5, 13, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1800.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Shell Fuel Station",
                "source": "manual",
                "date": datetime(2026, 6, 8, 10, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2600.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Electricity Board",
                "source": "manual",
                "date": datetime(2026, 6, 10, 14, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 2800.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Amazon Online",
                "source": "manual",
                "date": datetime(2026, 6, 11, 11, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 550.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Zomato",
                "source": "manual",
                "date": datetime(2026, 6, 14, 20, 15, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 649.00,
                "type": "debit",
                "category": "Utilities",
                "merchant": "Netflix India",
                "source": "manual",
                "date": datetime(2026, 6, 15, 14, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 119.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "Spotify Premium",
                "source": "manual",
                "date": datetime(2026, 6, 16, 16, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 900.00,
                "type": "debit",
                "category": "Entertainment",
                "merchant": "PVR Cinemas",
                "source": "manual",
                "date": datetime(2026, 6, 17, 18, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 600.00,
                "type": "debit",
                "category": "Healthcare",
                "merchant": "Apollo Pharmacy",
                "source": "manual",
                "date": datetime(2026, 6, 19, 15, 30, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1850.00,
                "type": "debit",
                "category": "Food",
                "merchant": "D-Mart Groceries",
                "source": "manual",
                "date": datetime(2026, 6, 20, 12, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 500.00,
                "type": "debit",
                "category": "Travel",
                "merchant": "Uber Rides",
                "source": "manual",
                "date": datetime(2026, 6, 22, 9, 45, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 1900.00,
                "type": "debit",
                "category": "Shopping",
                "merchant": "Myntra Fashion",
                "source": "manual",
                "date": datetime(2026, 6, 24, 17, 0, 0, tzinfo=timezone.utc),
            },
            {
                "amount": 350.00,
                "type": "debit",
                "category": "Food",
                "merchant": "Starbucks",
                "source": "manual",
                "date": datetime(2026, 6, 27, 16, 10, 0, tzinfo=timezone.utc),
            },
        ]

        for tx in transactions_data:
            tx_obj = Transaction(
                user_id=user.id,
                amount=tx["amount"],
                type=tx["type"],
                category=tx["category"],
                merchant=tx["merchant"],
                source=tx.get("source", "manual"),
                date=tx["date"],
            )
            session.add(tx_obj)

        await session.commit()
        print(f"[SEED] Successfully populated {len(transactions_data)} transactions across June, July & August 2026!")
        print("[SEED] Database seeding complete! Demo login: demo@localhost.test "
              "(password: DEMO_SEED_PASSWORD, or the one-time value printed when the user was created)")


if __name__ == "__main__":
    asyncio.run(seed_database())
