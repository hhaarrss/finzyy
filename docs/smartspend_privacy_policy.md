# Finzyy — Privacy Policy

*Placeholder fields marked in [brackets] — fill in before publishing. This draft is based on Finzyy's actual documented architecture (SMS parsing, PostgreSQL storage, JWT/Firebase auth). Have this reviewed against your actual final implementation before it goes live — this is a strong starting draft, not a substitute for your own final check that it matches exactly what the shipped app does.*

**Last updated:** [DATE]
**Effective for:** Finzyy, developed by [YOUR NAME / COMPANY NAME]

---

## 1. Who we are

Finzyy is an expense-tracking application for Android that helps users understand their spending by automatically reading and categorizing bank transaction SMS alerts.

**Developer contact:** [your.email@example.com]

If you have any privacy-related questions or requests, you can reach us at the email above.

## 2. What data we collect

### SMS content (with your permission)
If you grant SMS read permission, Finzyy reads incoming SMS messages **only from a whitelisted list of known bank sender IDs** (e.g. HDFCBK, ICICIB, SBIPSG, AXISBK). We extract only the following from these messages: transaction amount, transaction type (debit/credit), merchant or payee name, masked account number (last 4 digits only), and date. The full raw SMS text may be temporarily stored to support the categorization pipeline and can be viewed by you in the transaction detail screen.

**We do not read, store, or process SMS messages from any sender that is not on this bank whitelist.** Personal messages, OTPs from non-financial senders, and messages from unrecognized numbers are never accessed or stored.

### Account information
When you create an account, we collect your email address and, if you sign in with Google, basic profile information (name, email) provided by Google Sign-In via Firebase Authentication. Passwords (for email/password accounts) are never stored in plain text — they are hashed using bcrypt before storage.

### Transaction and financial data
Transaction records you create manually, or that are derived from parsed SMS or uploaded bank statements, including amount, category, merchant, date, and masked account identifiers.

### Usage data
Basic app usage and crash diagnostics may be collected to help us fix bugs and improve reliability. [Confirm and specify if you add Firebase Crashlytics or any analytics SDK — this must match your Data Safety form declaration exactly.]

## 3. How we use your data

- To automatically detect and categorize your transactions
- To generate the spending analytics, budgets, and insights shown in the app
- To authenticate you and keep your account secure
- To respond to support requests
- We do **not** use your financial or SMS data for advertising, and we do **not** sell your data to third parties.

## 4. Data storage and security

- Your data is stored in a PostgreSQL database, accessed only through our backend API.
- All data in transit between the app and our servers is encrypted using HTTPS/TLS.
- Account passwords are hashed with bcrypt and never stored or transmitted in plain text.
- Authentication uses JWT (JSON Web Token) bearer tokens; Google Sign-In is handled via Firebase Authentication, and Firebase ID tokens are verified server-side before any Finzyy session is issued.
- Account numbers are always stored and displayed in masked form (e.g. `XX373`) — full account numbers are never stored.

## 5. Data sharing

We do not share, sell, or rent your personal or financial data to third parties for marketing or advertising purposes. We may disclose data only:
- When required by law, regulation, or legal process
- To prevent fraud, security incidents, or abuse of the service
- With service providers strictly necessary to operate the app (e.g. our cloud hosting provider, [Firebase/Google for authentication]) — these providers are bound to only process data on our behalf and not for their own purposes

## 6. Your rights and controls

- **Access:** you can view all your stored transaction and account data within the app.
- **Export:** you can export your transaction data as a CSV file from within the app.
- **Correction:** you can edit or recategorize any transaction at any time.
- **Deletion:** you can delete your account and all associated data at any time from **Profile → Account & Data → Delete My Account**. This permanently removes your account, transactions, budget limits, and merchant categorization history from our systems. If you're unable to access the app, you may also request deletion by emailing [your.email@example.com] or via [web deletion link URL].
  - Some limited data may be retained beyond deletion only where required for fraud prevention, security, or legal/regulatory compliance, and will be deleted once that requirement no longer applies.

## 7. Permissions

| Permission | Why we need it | Optional? |
|---|---|---|
| SMS (`READ_SMS`, `RECEIVE_SMS`) | To detect bank transaction alerts and auto-log transactions | Yes — you can deny this and use manual entry / bank statement import instead |
| Internet | To sync your data with our backend so it's available across devices | No — required for core functionality |

## 8. Children's privacy

Finzyy is not directed at children under 13 (or the applicable minimum age in your jurisdiction), and we do not knowingly collect data from children.

## 9. Changes to this policy

We may update this privacy policy from time to time. Material changes will be reflected with an updated "Last updated" date above, and significant changes will be communicated in-app.

## 10. Contact us

For any questions, concerns, or data requests related to this privacy policy, contact: **[your.email@example.com]**
