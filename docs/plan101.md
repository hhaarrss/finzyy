# Finzyy — Implementation Plan

## Project Context
Finzyy is an AI-augmented personal finance tracker for the Indian market.
It automatically detects bank SMS transactions, categorizes spending, and
provides real-time analytics via Android app and React web dashboard.

Current State:
- FastAPI backend running with PostgreSQL + Redis + Celery
- Android app with SMS detection and BroadcastReceiver working
- React web dashboard with basic transaction view and charts
- JWT authentication working
- Bulk SMS ingestion endpoint working
- Basic categorization engine working

Exclusions for this implementation phase:
- No login/signup UI changes (testing phase — existing auth stays as is)
- No WhatsApp / Telegram / third party messaging alerts (FCM push only)

---

## PHASE 1 — Core Trust Features (Week 1)

### 1.1 Delete and Edit Transactions

**Why:** Users must be able to fix mistakes. Without this the app feels
untrustworthy and incomplete. This is a non-negotiable basic feature.

**Backend changes:**

Add these two endpoints in `routers/transactions.py`:

PATCH /transactions/{transaction_id}

JWT protected
User can only edit their own transactions (verify user_id matches)
Editable fields: category, merchant, amount, date, notes
Non-editable fields: bank, account_last4, hash_fingerprint, source
Return updated transaction object
If transaction_id not found return 404
If transaction belongs to different user return 403

DELETE /transactions/{transaction_id}

JWT protected
User can only delete their own transactions (verify user_id matches)
Hard delete from database
Return 200 with message "Transaction deleted successfully"
If transaction_id not found return 404
If transaction belongs to different user return 403

**React dashboard changes:**

In the transactions list, each transaction card must have:
- Edit icon (pencil) on the right side of each transaction card
- Delete icon (trash) on the right side of each transaction card
- Clicking edit opens an inline edit form or a modal with editable fields
- Editable fields in form: merchant name, category dropdown, amount, date, notes
- Category dropdown must use the canonical category list (defined in section 1.3)
- Save button calls PATCH endpoint and updates the card in place without page reload
- Cancel button dismisses the form without saving
- Clicking delete shows a confirmation dialog "Delete this transaction?"
- Confirm calls DELETE endpoint and removes card from list with fade animation
- Show success toast notification after edit or delete

**Android app changes:**

In TransactionsFragment, each transaction item must have:
- Long press on transaction item opens a bottom sheet with two options:
  "Edit Transaction" and "Delete Transaction"
- Edit opens a new EditTransactionActivity with pre-filled fields
- Fields: merchant, category spinner, amount, date picker, notes
- Save button calls PATCH endpoint and refreshes the list
- Delete shows AlertDialog confirmation then calls DELETE endpoint
- After delete, remove item from RecyclerView with animation

---

### 1.2 Password Reset Flow

**Why:** If a user forgets their password and cannot reset it, they uninstall
the app permanently. This is a critical retention feature.

**Backend changes:**

Add these endpoints in `routers/auth.py`:

POST /auth/forgot-password

Accepts: { "email": "user@example.com" }
Generate a secure 6-digit OTP using secrets.randbelow(1000000)
Store OTP in Redis with key "pwd_reset:{email}" and TTL of 10 minutes
Send OTP to user email using FastAPI background task
Use smtplib or fastapi-mail library
Return: { "message": "OTP sent to your email" }
If email not found in database return 404
Do not reveal whether email exists or not for security
(return same message regardless)

POST /auth/verify-otp

Accepts: { "email": "user@example.com", "otp": "123456" }
Check Redis for key "pwd_reset:{email}"
If OTP matches generate a password reset token (JWT with 15 min expiry)
Delete the OTP from Redis after successful verification
Return: { "reset_token": "eyJ..." }
If OTP wrong or expired return 400 with message "Invalid or expired OTP"

POST /auth/reset-password

Accepts: { "reset_token": "eyJ...", "new_password": "newpass123" }
Verify reset token is valid and not expired
Validate new password minimum 8 characters
Hash new password with bcrypt and update in database
Invalidate the reset token after use
Return: { "message": "Password reset successful" }

**Email configuration:**

Add these to `.env.example`:

SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-email@gmail.com
SMTP_PASSWORD=your-app-password
SMTP_FROM_EMAIL=noreply@smartspend.app


**React dashboard changes:**

On the login page add:
- "Forgot Password?" link below the password field
- Clicking opens ForgotPasswordPage component
- Step 1: Email input form → submit calls POST /auth/forgot-password
- Step 2: OTP input (6 boxes, one digit each) → submit calls POST /auth/verify-otp
- Step 3: New password + confirm password form → submit calls POST /auth/reset-password
- Show progress indicator (Step 1 of 3, Step 2 of 3, Step 3 of 3)
- After successful reset redirect to login page with success message
- OTP boxes must auto-advance to next box on digit entry
- OTP boxes must support paste of full 6-digit code

---

### 1.3 Consistent Category List

**Why:** Currently "Food & Dining" on web, "Food" on Android, and "food" in
API creates confusion and breaks category matching across the system.

**Define one canonical category list used everywhere:**

Categories (use exactly these strings in database, API, Android, and React):

Food
Transport
Shopping
Entertainment
Utilities
Healthcare
Education
Travel
Rent
Transfer
Investment
Salary
Refund
Other

**Backend changes:**

Create new file `utils/categories.py`:
```python
CATEGORIES = [
    "Food",
    "Transport", 
    "Shopping",
    "Entertainment",
    "Utilities",
    "Healthcare",
    "Education",
    "Travel",
    "Rent",
    "Transfer",
    "Investment",
    "Salary",
    "Refund",
    "Other"
]
```

Add new endpoint in `routers/transactions.py`:

GET /categories

No auth required
Returns { "categories": [...list of canonical categories...] }
This is the single source of truth for all clients

Update categorizer to use only categories from this list.
Update all existing transactions in database to map old category names
to new canonical names (write a migration script).

**React dashboard changes:**

- Fetch categories from GET /categories on app load
- Store in React Context so all components use the same list
- Replace all hardcoded category strings with values from context
- Add Transaction form category dropdown must use this list
- Edit transaction form category dropdown must use this list
- Category filter chips must use this list
- Remove "Food & Dining" — replace with "Food" everywhere

**Android changes:**

- Fetch categories from GET /categories on app start
- Cache the list in SharedPreferences
- All category spinners and dropdowns use this cached list
- Category display labels use exactly these strings

---

## PHASE 2 — P2P vs Merchant Separation (Week 1-2)

### 2.1 Separate UPI Transfers from Merchant Spending

**Why:** When a user sends ₹5,000 to a friend via UPI, it shows as ₹5,000
expense in Food or Other. This makes total spending look inflated and
untrustworthy. Users must see "real spending" separately from transfers.

**Backend changes:**

Add `is_transfer` boolean column to transactions table via Alembic migration:

ALTER TABLE transactions ADD COLUMN is_transfer BOOLEAN DEFAULT FALSE;
ALTER TABLE transactions ADD COLUMN transfer_to VARCHAR(255);


Update SMS parser to detect P2P transfers:

Detection rules for P2P transfer:

A transaction is a P2P transfer if ANY of these are true:

Merchant/payee name looks like a person name (not a known merchant)
UPI ID contains personal patterns like:
name@okaxis, name@ybl, name@ibl, name@paytm
Does NOT match known merchant UPI patterns
SMS contains words like "paid to", "sent to", "transferred to"
followed by a person name
Amount does not match any known merchant amount patterns

Known merchant UPI patterns (not transfers):

swiggy@, zomato@, amazon@, flipkart@, irctc@,
uber@, ola@, netflix@, hotstar@, paytm merchant@

Update GET /transactions/ endpoint:
- Add query param `include_transfers: bool = True`
- When `include_transfers=False` exclude transactions where is_transfer=True
- Update summary endpoint to show two totals:

{
"total_spent": 15000,
"merchant_spent": 11000,
"transfer_sent": 4000,
"transfer_received": 2000
}


**React dashboard changes:**

On the main dashboard:
- Add a toggle switch "Include Transfers" — default OFF
- When OFF, transfers are excluded from all charts and totals
- Show two stat cards at top:
  - "Spent on merchants: ₹11,000"
  - "Transferred to people: ₹4,000"
- Transfer transactions show a different icon (arrow icon instead of category icon)
- In transaction list, transfer rows have a subtle different background color
- "Transfer" category in donut chart shown in gray to visually separate it

**Android changes:**

- HomeFragment shows two amounts:
  "Merchant Spend: ₹11,000" (primary, large)
  "Transfers: ₹4,000" (secondary, smaller, gray)
- Toggle in TransactionsFragment to show/hide transfers
- Transfer transactions show person icon instead of category icon

---

## PHASE 3 — Real Budget Alerts via Push Notification (Week 2)

### 3.1 Firebase FCM Budget Alerts

**Why:** "You hit 80% of Food budget this month" is what brings users back
daily. This is the most important retention feature in the entire app.

**Alert types to implement (FCM push only):**

Alert 1 — 80% Budget Warning:
Trigger: When category spend crosses 80% of monthly limit
Title: "⚠️ Budget Alert — {category}"
Body: "You've used ₹{spent} of ₹{limit} ({percent}%)
in {category} this month. ₹{remaining} remaining."
When to send: Immediately after transaction is saved that crosses 80%

Alert 2 — 100% Budget Exceeded:
Trigger: When category spend crosses 100% of monthly limit
Title: "🚨 Budget Exceeded — {category}"
Body: "You've exceeded your {category} budget by ₹{over_amount}.
Limit was ₹{limit}, spent ₹{spent}."
When to send: Immediately after transaction that crosses 100%

Alert 3 — Daily Summary (9 PM every day):
Trigger: Celery beat scheduled task at 21:00 IST daily
Title: "📊 Today's Spending Summary"
Body: "You spent ₹{today_total} today across {count} transactions.
Top category: {top_category} (₹{top_amount})"

Alert 4 — Weekly Budget Status (Monday 9 AM):
Trigger: Celery beat scheduled task every Monday at 09:00 IST
Title: "📅 Weekly Budget Check"
Body: "Week {week_number}: ₹{month_spent} of ₹{month_budget} used.
{days_left} days left this month."

Alert 5 — Monthly Summary (1st of every month 8 AM):
Trigger: Celery beat scheduled task on 1st of month at 08:00 IST
Title: "📈 {previous_month} Summary"
Body: "Last month you spent ₹{total}.
Most spent on: {top_category}.
This month's budget is reset and ready."


**Backend changes:**

Create `utils/notifications.py`:
```python
- send_fcm_notification(user_id, title, body, data={}) function
- Get user's FCM token from users table
- Call Firebase Admin SDK to send notification
- Log notification in notifications_log table
- Handle FCM token not found gracefully (skip silently)
```

Add `fcm_token` column to users table via migration:

ALTER TABLE users ADD COLUMN fcm_token VARCHAR(500);


Add endpoint to save FCM token:

POST /users/fcm-token

JWT protected
Accepts: { "fcm_token": "..." }
Updates fcm_token in users table for current user
Called by Android app on every app start

Update transaction save logic in `routers/transactions.py`:
After saving every transaction, call a Celery task:

check_budget_and_alert.delay(user_id, category, transaction_amount)


Create Celery task `tasks/budget_alerts.py`:

check_budget_and_alert(user_id, category, transaction_amount):

Get current month spend for this category for this user
Get budget limit for this category for this user
If no budget limit set, skip
Calculate percentage used
If percentage crosses 80% for first time this month → send Alert 1
If percentage crosses 100% for first time this month → send Alert 2
Track "alert already sent" in Redis to avoid duplicate alerts:
Key: "budget_alert:{user_id}:{category}:{month}:{80 or 100}"
TTL: until end of month

Create scheduled Celery tasks in `tasks/scheduled.py`:

send_daily_summaries() — runs at 21:00 IST every day
send_weekly_budget_check() — runs at 09:00 IST every Monday
send_monthly_summaries() — runs at 08:00 IST on 1st of every month


Configure Celery beat schedule in `celery_app.py`:
```python
beat_schedule = {
    'daily-summary': {
        'task': 'tasks.scheduled.send_daily_summaries',
        'schedule': crontab(hour=21, minute=0),
    },
    'weekly-check': {
        'task': 'tasks.scheduled.send_weekly_budget_check', 
        'schedule': crontab(hour=9, minute=0, day_of_week=1),
    },
    'monthly-summary': {
        'task': 'tasks.scheduled.send_monthly_summaries',
        'schedule': crontab(hour=8, minute=0, day_of_month=1),
    },
}
```

**Android changes:**

On app start in `MainActivity.onCreate()`:
```kotlin
- Initialize Firebase
- Get FCM token using FirebaseMessaging.getInstance().token
- Call POST /users/fcm-token with the token
- Refresh token on FirebaseMessagingService.onNewToken()
```

Create `SmartSpendFirebaseMessagingService.kt`:
```kotlin
- Extend FirebaseMessagingService
- Override onMessageReceived(message: RemoteMessage)
- Build rich notification with:
  - Large icon: app icon
  - Color: based on alert type (orange for warning, red for exceeded)
  - Priority: HIGH for budget exceeded, DEFAULT for daily summary
  - Auto cancel: true
  - Vibration: true for budget alerts
- Register in AndroidManifest.xml with MESSAGING_EVENT intent filter
```

---

## PHASE 4 — Family / Household Spending (Week 2-3)

### 4.1 Family UI (Backend Already Exists)

**Why:** Shared household finance is the #1 reason Indian families would
choose Finzyy over competitors. Backend create/join already works.
This phase builds the complete UI for it.

**Backend additions:**

Add these endpoints in `routers/family.py`:

GET /family/members

JWT protected
Returns all members of the current user's family group
Response: {
"family_id": 1,
"family_name": "Rabadiya Family",
"members": [
{
"user_id": 1,
"full_name": "Harsh Rabadiya",
"email": "harsh@gmail.com",
"role": "admin",
"this_month_spent": 12400.00,
"transaction_count": 34
}
]
}

GET /family/summary

JWT protected
Returns combined spending summary for the entire family
Query params: month (int), year (int)
Response: {
"total_family_spent": 28000.00,
"member_breakdown": [
{ "name": "Harsh", "spent": 12400, "percentage": 44.3 },
{ "name": "Member 2", "spent": 15600, "percentage": 55.7 }
],
"category_breakdown": [
{ "category": "Food", "total": 8000, "by_member": {...} }
],
"shared_budgets": [...]
}

POST /family/budget

JWT protected, admin only
Create a shared family budget limit
Accepts: { "category": "Food", "monthly_limit": 10000 }
Shared budget applies to combined family spending
Returns created budget object

GET /family/transactions

JWT protected
Returns all transactions from all family members
Same pagination as regular transactions (10 per page)
Each transaction shows member name and avatar initials
Query params: page, limit, category, month, year

**React dashboard changes:**

Add Family section to the sidebar/navigation.

FamilyOverviewPage component:

Top section — Family summary cards:

"Family Total This Month: ₹28,000"
"Members: 3 active"
"Shared Budgets: 4 categories"

Member Breakdown section:

Horizontal bar chart showing each member's contribution
Each bar a different color per member
Shows name, amount, and percentage

Combined Category Chart:

Donut chart of family spending by category
Toggle: "Show by Member" switches to stacked bar chart
showing each category split by who spent what

Family Transactions List:

Same paginated list as personal transactions
Each row shows member avatar (initials circle) + name
Color coded by member
Latest first
Filter by member dropdown at top

Shared Budgets section:

Progress bars for each shared budget
Shows combined family spend vs family limit
Red/orange/green color coding same as personal budgets

Invite Member flow:
"Invite Member" button in family section
Opens modal with family invite code (6 character alphanumeric)
Copy button to copy the code
Share button to share via native share
Invited member enters code in their app to join
Admin gets notification when someone joins

**Android changes:**

Add FamilyFragment to bottom navigation (person-group icon):

FamilyFragment screens:

Screen 1 — Not in a family yet:

"Create Family Group" button
"Join Family Group" button with code input field
Simple illustration explaining the feature

Screen 2 — Family home (after joining):

Family name at top
Member list with avatars and this-month spend for each
"Family Total: ₹28,000" large card
Tap any member to see their transactions

Screen 3 — Family transactions:

Combined list of all member transactions
Avatar initials before each transaction
Filter by member chip row at top

---

## PHASE 5 — Needs Review Game Loop (Week 3)

### 5.1 Make Uncategorized Transactions a Daily Ritual

**Why:** Every time a user fixes a wrong category the system gets smarter.
This is the core personalization loop. Making it feel like a quick daily
game instead of a chore is what drives retention.

**Backend changes:**

Add endpoint:

GET /transactions/needs-review

JWT protected
Returns transactions where category = "Other" OR
review_status = "needs_review"
Sorted by created_at DESC
Returns count in header: X-Needs-Review-Count
Response: {
"count": 7,
"transactions": [...],
"message": "Fix these 7 transactions to improve accuracy"
}

PATCH /transactions/{id}/categorize

JWT protected
Accepts: { "category": "Food", "merchant_alias": "pratiktod" }
Updates transaction category
If merchant_alias provided, saves to merchant_mappings table:
merchant_name/VPA → category mapping
Sets review_status = "reviewed"
Returns: { "updated": true, "learned": true/false }

**React dashboard changes:**

Add "Needs Review" badge on sidebar navigation item:
- Red badge with count e.g. "Needs Review (7)"
- Badge disappears when count reaches 0

NeedsReviewPage component:

Header: "Fix {count} transactions → Finzyy gets smarter 🧠"
Progress bar: showing how many reviewed out of total flagged today

For each transaction show a card:

Merchant name (large)
Amount and date
Current category: "Unknown / Other"
Row of category chips below: Food, Transport, Shopping...
(all canonical categories as tappable chips)
Tapping a chip instantly categorizes and slides the card away
Satisfying animation when card is dismissed
Running total: "3 of 7 fixed ✓"

Bottom:

"All done for today! 🎉" message when all reviewed
Show how many merchants were learned:
"Finzyy learned 3 new merchants today"

**Android changes:**

In HomeFragment add "Needs Review" card at top (only when count > 0):

Card design:

Orange accent color
"🔍 {count} transactions need your input"
Subtext: "Tap to fix — takes under 1 minute"
Tap opens NeedsReviewActivity

NeedsReviewActivity:

Full screen swipeable card stack (like Tinder cards)
Each card shows merchant + amount
Category chips below
Swipe right = mark as Other/skip
Tap chip = categorize and advance to next card
Progress indicator at top (3/7)
Confetti animation when all done
"Great job! Finzyy learned {n} merchants today 🎉"

Push notification for Needs Review:

Trigger: Daily at 7 PM if user has > 3 unreviewed transactions
Title: "🔍 3 transactions need your input"
Body: "Fix them in under a minute → Finzyy gets smarter"
Tap action: Opens NeedsReviewActivity directly


---

## PHASE 6 — Recurring / EMI / Subscription Board (Week 3-4)

### 6.1 Surface Fixed Commitments

**Why:** Most users don't know their total fixed monthly commitments.
Showing "Your fixed expenses: ₹18,400" is a high-value insight that
requires zero extra data entry from the user.

**Backend changes:**

Add recurring detection logic in `utils/recurring_detector.py`:

detect_recurring_transactions(user_id):

Fetch last 3 months of transactions
Group by merchant name
A transaction is recurring if:
Same merchant appears in 2 or more consecutive months
Amount is within 10% variation each month
Date is within 5 days variation each month
Return list of recurring transactions with:
merchant name
average amount
frequency (monthly/weekly)
next expected date
category
is_confirmed (user confirmed it as recurring)

Add endpoints:

GET /recurring

JWT protected
Returns detected recurring transactions
Response: {
"total_fixed_monthly": 18400,
"recurring": [
{
"merchant": "Netflix",
"amount": 649,
"frequency": "monthly",
"next_date": "2026-06-15",
"category": "Entertainment",
"is_confirmed": true
}
]
}

PATCH /recurring/{merchant}/confirm

User confirms a detected recurring transaction
Sets is_confirmed = true in recurring_patterns table

DELETE /recurring/{merchant}

User marks a transaction as NOT recurring
Removes from recurring detection for this merchant

**React dashboard changes:**

Add "Subscriptions & EMIs" page:

Summary card at top:
"Fixed monthly commitments: ₹18,400"
Breakdown: Rent ₹12,000 · Subscriptions ₹1,947 · EMIs ₹4,453

Three sections:

Rent & Fixed:
Rent, maintenance, insurance
Annual total shown
Subscriptions:
Netflix, Spotify, Hotstar, gym etc
Each shows logo placeholder, name, amount, next renewal date
Color coded: green = renewed recently, orange = due soon, red = overdue
EMIs:
Loan EMIs detected from SMS patterns
Shows estimated months remaining if detectable

Bottom: "Unconfirmed — is this recurring?" section

List of detected but unconfirmed recurring transactions
Confirm / Not Recurring buttons for each

---

## PHASE 7 — Weekly Digest (Week 4)

### 7.1 Sunday Weekly Summary

**Why:** One simple insight every Sunday keeps users engaged and creates
shareable content for organic marketing.

**Backend changes:**

Add Celery scheduled task in `tasks/scheduled.py`:

send_weekly_digest() — runs every Sunday at 10:00 AM IST

For each user generate:
{
"week_dates": "Aug 11 - Aug 17",
"total_spent": 4200,
"vs_last_week": +12.5,
"top_category": "Food",
"top_category_amount": 1800,
"transaction_count": 18,
"biggest_transaction": { "merchant": "Amazon", "amount": 2300 },
"under_budget_categories": ["Transport", "Entertainment"],
"over_budget_categories": ["Food"],
"saving_tip": "You spent 40% more on Food this week vs last week.
Consider cooking at home 2 extra days."
}


Send as FCM push notification:

Title: "📊 Your Week in Money — Aug 11-17"
Body: "₹4,200 spent · Top: Food ₹1,800 ·
18 transactions this week"
Tap: Opens WeeklyDigestScreen


**React dashboard changes:**

Add WeeklyDigestPage that shows a beautifully designed weekly card:

Card design (also used for sharing):

Week date range at top
Large total spend number
Up/down arrow vs last week with percentage
3 category highlights (top 3)
Biggest single transaction
Budget status — green checkmarks for under budget
One insight/tip at the bottom
"Share" button that generates a PNG of this card
for sharing on WhatsApp/Instagram

**Android changes:**

WeeklyDigestActivity:
Same card design as web
Native share button using Android ShareCompat
Shareable image generated using Canvas API
Tapping notification opens this activity

---

## PHASE 8 — Statement / PDF Import (Week 4-5)

### 8.1 PDF Bank Statement Import

**Why:** Opens the app to iOS users, desktop users, and people with
secondary bank accounts that don't send SMS alerts.

**Backend changes:**

Add endpoint:

POST /import/pdf

JWT protected
Accepts multipart/form-data with PDF file
Max file size: 10MB
Supported banks: HDFC, ICICI, SBI, Axis, Kotak, Yes Bank
Process:
Extract text from PDF using pdfplumber library
Detect bank format from PDF content
Parse transaction rows using bank-specific table parsers
Run each transaction through existing categorizer
Deduplicate against existing transactions using hash fingerprint
Return import summary:
{
"total_found": 45,
"total_imported": 38,
"duplicates_skipped": 7,
"needs_review": 12,
"transactions": [...]
}

Create `utils/pdf_parser.py`:

Bank statement parsers for each bank:

HDFCStatementParser — parses HDFC Net Banking PDF format
ICICIStatementParser — parses ICICI statement PDF format
SBIStatementParser — parses SBI e-statement format
AxisStatementParser — parses Axis bank statement PDF

Each parser implements:

detect(text) — returns True if PDF matches this bank format
parse(text) — returns list of raw transaction dicts

**React dashboard changes:**

Add Import page:

Drag and drop zone for PDF upload
Bank auto-detection message after upload starts
Progress bar during processing
Import summary screen after processing:

"38 transactions imported successfully"
"7 duplicates skipped (already in your account)"
"12 transactions need category review"
"Go to Needs Review" button
List preview of imported transactions

---

## PHASE 9 — Goals and Challenges (Week 5)

### 9.1 Savings Goals with Streaks

**Why:** Goals give users a reason to open the app every day. Streaks
create habit. This is the engagement layer on top of the core utility.

**Backend changes:**

Create new table `goals` via migration:

goals:

id
user_id
title (e.g. "Save ₹5,000 this month")
goal_type (spend_under / save_amount / category_limit)
target_amount
category (optional, for category_limit type)
start_date
end_date
current_progress
is_completed
created_at

Add endpoints:

POST /goals — create a new goal
GET /goals — list active goals with progress
PATCH /goals/{id} — update goal
DELETE /goals/{id} — delete goal
GET /goals/streak — get current streak (days under budget)


**React dashboard changes:**

GoalsPage:

Active goals section:

Each goal shown as a card with progress ring
Title, target, current progress, days remaining
Color: green when on track, orange when behind, red when failed

Create goal flow:

3 preset goal templates:
"Stay under ₹{X} this month"
"Spend less than ₹{X} on {category}"
"Save ₹{X} by {date}"
Custom goal option

Streak card:

"🔥 12 day streak — under budget every day!"
Calendar heatmap showing budget status each day
(green = under budget, red = over budget)

---

## PHASE 10 — Real Upgrade Tiers (Week 5-6)

### 10.1 Remove Fake Upgrade Button — Build Real Paywall

**Why:** A fake "Upgrade Now" button with no actual plan hurts trust
and makes the app look unfinished.

**Plan tiers:**

FREE tier (default for all users):

Personal SMS sync
3 months transaction history
5 budget categories
Basic dashboard
Daily spending chart
FCM push notifications

PRO tier (₹99/month or ₹799/year):

Everything in Free
Family seats (up to 5 members)
Unlimited transaction history
Unlimited budget categories
PDF statement import
Weekly digest
Goals and challenges
Advanced insights (MoM comparison, anomaly detection)
CSV export
Priority support

**Backend changes:**

Add `plan` column to users table:

ALTER TABLE users ADD COLUMN plan VARCHAR(20) DEFAULT 'free';
ALTER TABLE users ADD COLUMN plan_expires_at TIMESTAMP;


Add middleware to check plan limits:
Enforce history limit for free users (3 months)
Enforce category limit for free users (5 categories)
Return 403 with { "error": "upgrade_required", "feature": "..." }
when free user tries to access pro feature

**React dashboard changes:**

UpgradePage:

Side by side Free vs Pro comparison table

Clear checkmarks and crosses for each feature
"Most Popular" badge on Pro annual plan
"Start Free Trial" button (7 day free trial of Pro)
Payment integration placeholder (Razorpay recommended for India)

Upgrade prompts in app:

When free user tries to add 6th budget category:
Inline prompt "Upgrade to Pro for unlimited categories"
When free user tries to view transactions older than 3 months:
Inline prompt with upgrade CTA
Never block core functionality — only limit extended features

---

## Non-Functional Requirements

### Performance
- All API endpoints must respond in under 500ms for p95
- Transaction list pagination must use database-level LIMIT/OFFSET
- Merchant lookup table must be cached in Redis with 1 hour TTL
- FCM notifications must be sent via Celery task (never block API response)

### Security
- All endpoints except /auth/login and /categories must require JWT
- User can never access another user's data (enforce user_id check on every query)
- FCM tokens must be refreshed on every app start
- OTP for password reset must expire after 10 minutes
- PDF uploads must be scanned for file type (reject non-PDF)
- File upload size limit: 10MB

### Error Handling
- Every endpoint must return proper HTTP status codes
- 400 for validation errors with descriptive message
- 401 for unauthorized
- 403 for forbidden (wrong user)
- 404 for not found
- 500 for server errors (never expose stack trace in production)
- All Celery tasks must have retry logic (max 3 retries with exponential backoff)

### Database
- Add database indexes on:
  transactions.user_id
  transactions.date
  transactions.category
  transactions.created_at
  merchant_mappings.merchant_name
- All new tables must have created_at and updated_at timestamps
- All migrations must be reversible (include downgrade() in Alembic)

---

## File Structure Changes

New files to create:

backend/
├── utils/
│ ├── categories.py (canonical category list)
│ ├── notifications.py (FCM helper)
│ ├── recurring_detector.py (recurring transaction detection)
│ └── pdf_parser.py (PDF statement parser)
├── tasks/
│ ├── budget_alerts.py (budget threshold Celery tasks)
│ └── scheduled.py (daily/weekly/monthly Celery tasks)
└── routers/
├── family.py (family endpoints — update existing)
├── recurring.py (new)
├── goals.py (new)
├── import_router.py (new — PDF import)
└── upgrade.py (new — plan management)


---

## Implementation Order for Antigravity

Implement phases strictly in this order.
Complete and test each phase before starting the next.
Each phase is independent and deployable on its own.

1. Phase 1 — Delete/Edit + Password Reset + Categories (foundation)
2. Phase 2 — P2P vs Merchant separation (trust)
3. Phase 3 — FCM Budget Alerts (retention)
4. Phase 4 — Family UI (differentiation)
5. Phase 5 — Needs Review game loop (personalization)
6. Phase 6 — Recurring/Subscription board (insight)
7. Phase 7 — Weekly Digest (engagement)
8. Phase 8 — PDF Import (reach)
9. Phase 9 — Goals and Challenges (habit)
10. Phase 10 — Upgrade Tiers (monetization)