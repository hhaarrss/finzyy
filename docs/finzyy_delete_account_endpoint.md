# Finzyy — Account Deletion Endpoint (`DELETE /users/me`)

Implements the Play Store-required in-app account deletion. Cascades across every table that stores user data, per the audit's known schema: `transactions`, `budget_limits`, and the merchant-correction store (the audit noted this exists as both a `merchant_mappings` DB table per the handbook and `user_corrections.json` per the categorizer audit — confirm which is actually authoritative in your codebase and adjust Step 3 below accordingly; possibly both need clearing).

---

## 1. Request/response schema

```python
# backend/schemas/account.py
from pydantic import BaseModel
from typing import Optional

class DeleteAccountRequest(BaseModel):
    # Required for password-based accounts to confirm intent.
    # Optional/ignored for Google Sign-In accounts (see note below).
    password: Optional[str] = None
    confirmation_text: str  # user must type "DELETE" to confirm

class DeleteAccountResponse(BaseModel):
    success: bool
    message: str
    deleted_at: str
```

## 2. Endpoint

```python
# backend/routers/account.py
import hashlib
from datetime import datetime
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import delete, select

from backend.dependencies import get_db, get_current_user
from backend.models.user import User
from backend.models.transaction import Transaction
from backend.models.budget_limit import BudgetLimit
from backend.models.merchant_mapping import MerchantMapping  # adjust import if this lives elsewhere
from backend.schemas.account import DeleteAccountRequest, DeleteAccountResponse
from backend.auth.security import verify_password  # your existing bcrypt verify function

router = APIRouter()

@router.delete("/users/me", response_model=DeleteAccountResponse)
async def delete_my_account(
    payload: DeleteAccountRequest,
    current_user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # --- Step 1: Confirm intent ---
    if payload.confirmation_text.strip().upper() != "DELETE":
        raise HTTPException(
            status_code=status.HTTP_400_BAD_REQUEST,
            detail="Type DELETE to confirm account deletion."
        )

    # --- Step 2: Verify identity ---
    # Password-based accounts must re-enter their password.
    # Google Sign-In-only accounts have no password hash to check —
    # rely on the fact that they're already authenticated via a valid
    # JWT for this session, since there's no local password to verify.
    if current_user.hashed_password:  # None/empty for Google-only accounts
        if not payload.password or not verify_password(payload.password, current_user.hashed_password):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Incorrect password."
            )

    user_id = current_user.id

    # --- Step 3: Cascade delete all associated data ---
    # Order matters if foreign key constraints aren't set to CASCADE
    # at the DB level — delete children before the parent user row.
    await db.execute(delete(Transaction).where(Transaction.user_id == user_id))
    await db.execute(delete(BudgetLimit).where(BudgetLimit.user_id == user_id))
    await db.execute(delete(MerchantMapping).where(MerchantMapping.user_id == user_id))

    # If user_corrections is a JSON file per-user rather than a DB table,
    # also delete/clear that file here, e.g.:
    # await delete_user_corrections_file(user_id)

    # --- Step 4: Delete the user record itself ---
    await db.execute(delete(User).where(User.id == user_id))

    await db.commit()

    return DeleteAccountResponse(
        success=True,
        message="Your account and all associated data have been permanently deleted.",
        deleted_at=datetime.utcnow().isoformat() + "Z"
    )
```

## 3. Recommended: make this the actual DB-level default too

Rather than relying only on the endpoint to delete rows in the right order, set `ON DELETE CASCADE` at the schema level so deletion is enforced by the database itself, not just app logic (defense in depth — protects you even if a future code path deletes a user some other way):

```python
# In your Transaction, BudgetLimit, MerchantMapping models
user_id: Mapped[int] = mapped_column(
    ForeignKey("users.id", ondelete="CASCADE"),
    nullable=False
)
```
Then a migration:
```bash
alembic revision -m "add cascade delete on user foreign keys"
alembic upgrade head
```
With this in place, `await db.execute(delete(User).where(User.id == user_id))` alone would be sufficient — the explicit deletes in Step 3 become a safety net rather than the only mechanism.

## 4. Frontend flow (mobile app)

```
Profile → Account & Data → Delete My Account
  → Warning screen: "This will permanently delete your account,
     all transactions, budgets, and categorization history. This
     cannot be undone."
  → Password field (skipped/hidden for Google Sign-In accounts)
  → Text field: "Type DELETE to confirm"
  → [Delete My Account] button (destructive/red styling)
  → On success: log the user out locally, clear any cached JWT,
     navigate to the Sign In screen, show a brief confirmation
     toast/snackbar ("Your account has been deleted.")
```

## 5. The web-based deletion link (required alongside in-app deletion)

Google requires this **in addition to** the in-app flow, for users who've already uninstalled the app. This can be a simple static page — doesn't need to be a full web app:

```html
<!-- A minimal hosted page, e.g. on GitHub Pages -->
<h1>Delete your Finzyy account</h1>
<p>To request deletion of your Finzyy account and all associated data,
   email <a href="mailto:your.email@example.com?subject=Account Deletion Request">
   your.email@example.com</a> from the email address associated with your account.
   We will process your request and confirm deletion within [X] business days.</p>
```
Link this page from your Play Store listing's Data Safety section and from the privacy policy's deletion clause.

## 6. Testing checklist before you ship this

- [ ] Deleting an account actually removes rows from `transactions`, `budget_limits`, `merchant_mapping`/`user_corrections`, and `users` — verify by querying the DB directly after a test deletion, not just checking the API response
- [ ] A deleted user's JWT is rejected on any subsequent API call (should already work if the user row is gone and your auth dependency does a DB lookup, not just JWT signature validation)
- [ ] Google Sign-In accounts can delete without needing a password
- [ ] The confirmation text check is case-insensitive but still requires deliberate input (not a single tap)
- [ ] Test what happens if deletion is interrupted mid-way (e.g. app crash after transactions are deleted but before the user row is) — this is exactly why Section 3's DB-level `ON DELETE CASCADE` is worth doing, since a single cascading delete on the user row is atomic in a way that four separate application-level deletes are not
