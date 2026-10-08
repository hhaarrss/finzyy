# SmartSpend Privacy Policy (draft)

> **Published version:** [`docs/privacy-policy.html`](../privacy-policy.html) is the finished
> page, in the same style as the live site. Copy it to the `hhaarrss/smart-spend` repo, which
> serves `https://hhaarrss.github.io/smart-spend/privacy-policy.html` (the URL the app and Play
> Console use). This Markdown file is the readable source — keep the two in step, and keep both
> in step with [data-safety.md](data-safety.md).
>
> Still worth doing: have it read by someone who knows India's DPDP Act, and add the Render
> region to the providers table once you've checked it in the Render dashboard.

**Effective date:** 28 September 2026
**Who we are:** SmartSpend is developed and operated by Harsh Rabadiya ("we").
**Contact:** smartspend4support@gmail.com

SmartSpend is an expense tracker. It reads the payment SMS your bank sends you, turns them into
transactions, and shows you where your money goes.

## 1. The short version

- Your SMS messages are read **on your phone**. The text of an SMS is never sent to us.
- From bank SMS only, we send the payment details (amount, merchant, date, bank, last 4 digits of
  the account, UPI reference) to our server so the app can categorise them and show your spending.
- We don't sell your data, and we don't use it for advertising.
- You can delete your account and all its data from the app at any time.

## 2. What we collect

**Account details.** Your mobile number (verified by a one-time code) or your Google account
email; your name. Older accounts may have an email and password — passwords are stored only as a
one-way hash, never in readable form.

**Profile details you choose to add.** Date of birth, gender, city, occupation, monthly income
and a monthly budget. All optional.

**Transactions.** For each payment: amount, whether money went out or came in, merchant name,
date, category, bank name, the last 4 digits of the account, and the UPI reference number. These
come from bank SMS (see section 3) or from what you enter yourself, together with any notes you
add. We also keep your category corrections (for example "file this merchant under Food") so the
app categorises that merchant the same way next time.

**Budgets and family groups** you create.

**Device data.** A notification token so we can send you budget alerts.

**Crash reports.** If the app crashes, a report is sent so we can fix it: what went wrong in the
code, your phone model, Android version, app version and an install identifier. It does not
include your SMS, your transactions, your name or your phone number. We don't use advertising
tools, and we don't track how you use the app.

## 3. How the app uses SMS

If you allow it, SmartSpend reads SMS as they arrive, checks your inbox in the background for
bank messages it may have missed (for example while your phone was offline), and — when you ask
it to — imports older ones already in your inbox. It checks who each message is from and
ignores everything that isn't from a known bank or payment sender.
Each bank message is read on your phone, the payment details listed above are extracted on your
phone, and only those details are sent to us. **The message text itself never leaves your phone
and is never stored by us.** You can turn this off at any time by removing the SMS permission in
your phone's settings; you can still add transactions by hand.

## 4. Why we use it

- To run the app: record your transactions, categorise them, show totals, trends and insights,
  track budgets and send you budget alerts.
- To keep your account working and secure: sign-in, and stopping duplicate transactions.

We don't use your data for advertising and we don't sell it.

## 5. Who processes your data for us

We use these providers to run SmartSpend. They process data on our instructions and not for
their own purposes:

| Provider | What for | Where |
|---|---|---|
| Neon (database) | Stores your account, profile and transactions | Singapore |
| Render (server hosting) | Runs the SmartSpend backend | Render cloud data centres |
| Google Firebase | Phone-number verification, Google sign-in, push notifications, crash reports | Google's global infrastructure |

We may disclose data if the law requires it (for example a valid order from a court or government
authority).

## 6. How we protect it

- All data between the app and our server is encrypted in transit (HTTPS).
- Passwords are stored only as salted one-way hashes.
- On your phone, your sign-in token and any not-yet-synced payments are encrypted with a key held
  in your phone's secure hardware keystore; the app's data is excluded from phone backups.
- The database is encrypted at rest by our hosting provider, and access to it is restricted to
  the people who need it to run and support the service.

No system is perfectly secure, but we work to protect your data and will tell you, as the law
requires, if a breach affects it.

## 7. How long we keep it

We keep your data while your account exists. When you delete your account, we delete your
profile, transactions, budgets, category corrections, alerts and your Firebase sign-in record.
Deleted data may remain in our database provider's short-term recovery history for up to
6 hours before it is gone for good.

## 8. Your choices and rights

- **See and correct** your data in the app (Account, and each transaction).
- **Delete your account** in the app (Account → Privacy & legal → Delete account) or by requesting it at
  https://hhaarrss.github.io/smart-spend/delete-account.html.
- **Stop SMS reading** by removing the permission in your phone settings.
- **Turn off notifications** in the app or in your phone settings.
- **Withdraw consent, ask a question or raise a grievance** by writing to Harsh Rabadiya at
  smartspend4support@gmail.com. We aim to reply within 7 days.

## 9. Children

SmartSpend is not meant for anyone under 18, and we don't knowingly collect data from children.

## 10. Changes

If we change this policy we'll update the date at the top, and tell you in the app if the change
is significant.
