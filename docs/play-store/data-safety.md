# Play Console — Data safety form (draft)

Answers are based on the code as of the privacy-hardening branch. Re-check this page whenever
the app starts collecting something new — a wrong answer is a policy violation even if the app
itself is fine.

"Collected" in Play's sense means *sent off the phone* (to our backend, Firebase, etc.).
Data that stays on the phone — the SMS text itself — is not "collected".

## Section 1 — Data collection and security

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** |
| Is all of the user data collected by your app encrypted in transit? | **Yes** — release builds are HTTPS-only |
| Which of the following methods of account creation does your app support? | Phone number (OTP), Google sign-in; older accounts: email + password |
| Do you provide a way for users to request that their data is deleted? | **Yes** — in the app (Account → Privacy & legal → Delete account) and on the web: `https://hhaarrss.github.io/smart-spend/delete-account.html` (email request) |

## Section 2 — Data types

"Shared" = given to a third party for *their own* use. Google Firebase and our hosting
(Render, Neon) process data *for us*, which Play counts as service providers, **not sharing**.
So every row below is **Collected: Yes, Shared: No**.

| Data type (Play's category) | What exactly | Required or optional | Purposes |
|---|---|---|---|
| Personal info → **Name** | Full name from profile setup | Required | App functionality, Account management |
| Personal info → **Email address** | Google sign-in email; email for older accounts; optional email in profile | Optional (required for Google/email sign-in) | Account management, App functionality |
| Personal info → **Phone number** | Verified via OTP (Firebase) | Required for phone sign-in | Account management |
| Personal info → **Other info** | Date of birth, gender, city, occupation — all optional in profile setup | Optional | App functionality (personalised insights) |
| Financial info → **Purchase history** | Each payment: amount, debit/credit, merchant name, date, category | Required (core feature) | App functionality |
| Financial info → **Other financial info** | Bank name, last 4 digits of the account, UPI reference number, monthly income (optional), budgets you set | Required (bank/UPI fields come with each payment; income is optional) | App functionality |
| App activity → **Other user-generated content** | Notes you add to a transaction; family group names | Optional | App functionality |
| Device or other IDs → **Device or other IDs** | Firebase Cloud Messaging token (for budget alerts); Crashlytics installation ID (ties a crash report to an install, not to a person) | Required | App functionality (notifications), Analytics (crash reports) |
| App info and performance → **Crash logs** | Stack trace of a crash, with device model, Android version and app version (Firebase Crashlytics) | Required | Analytics |
| App info and performance → **Diagnostics** | App start and session timing that Crashlytics records to work out how many sessions ended in a crash | Required | Analytics |

For each row Play also asks **"Is this data processed ephemerally?"** → **No** (it's stored),
and **"Is this data collection required, or can users choose?"** → use the column above.

### Messages → SMS or MMS: answer **No (not collected)**, and here's why

The app *reads* bank SMS on the phone, but the message text never leaves the phone: it is parsed
on-device and only the extracted payment fields (listed under Financial info above) are sent.
Play defines "collected" as transmitted off the device, so SMS is not collected — the derived
financial fields are, and they're declared. Say this plainly in the SMS permission declaration
too (see [sms-permission-declaration.md](sms-permission-declaration.md)), so the two answers
back each other up.

## Things that would change these answers

- **Crash reporting is in the app (Firebase Crashlytics); usage analytics is not.** So
  *App info and performance → Crash logs* and *Diagnostics* are **collected** for the *Analytics*
  purpose, and *App activity → App interactions* is **not collected**. Crashlytics is off in
  debug builds, is given no user id, and gets no custom logs, so no SMS or transaction data is
  attached to a report. Adding Firebase Analytics, or attaching a user id or logs to crash
  reports, means updating this form first. Check the answers against Google's own table:
  https://firebase.google.com/docs/android/play-data-disclosure
- A new field in a profile/transaction API, a new SDK (ads, crash reporting), or sending any
  SMS text to the server — all require updating this form first.
