# SmartSpend Privacy Policy (draft)

> **Before publishing:** fill every `[PLACEHOLDER]`, have it reviewed by someone qualified in
> Indian data-protection law (the Digital Personal Data Protection Act, 2023 applies to you), and
> host it at a public URL (Play requires a link, not a PDF). Keep it in step with
> [data-safety.md](data-safety.md) — the two must say the same thing.
>
> **A policy is already live** at `https://hhaarrss.github.io/smart-spend/privacy-policy.html`
> (the app links to it from the SMS consent screen and Account). It lives in the separate
> `hhaarrss/smart-spend` GitHub Pages repo. Replace its content with this draft. What's wrong
> with the live one today:
> - It still shows `[DATE]` and `[YOUR NAME / COMPANY NAME]` placeholders.
> - It promises **CSV export** and mentions **uploaded bank statements** — the app has neither.
> - Wrong deletion path ("Profile → Privacy & Data → Delete My Account"); the app's is
>   **Account → Delete account**.
> - It leaves out data the app does collect: bank name, UPI reference, the optional profile
>   fields (date of birth, gender, city, occupation, income), Google sign-in email, the push
>   token, and Firebase Analytics usage data.
> - It doesn't mention the background inbox check for missed bank SMS.
> - Children: says under 13; for a finance app, 18 is the safer line (and matches this draft).
> - The companion **delete-account.html** page has the same wrong in-app path, and should list
>   profile details, family groups and the Firebase sign-in record among what gets deleted.

**Effective date:** [DATE]
**Who we are:** SmartSpend is operated by [LEGAL NAME OF OWNER / COMPANY], [ADDRESS] ("we").
**Contact:** [smartspend4support@gmail.com — confirm this is the address you want public]

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

**Device and usage data.** A notification token so we can send you budget alerts, and app usage
data collected automatically by Google Firebase Analytics (app opens, screens viewed, device
model, OS version, an app-instance identifier). [Remove this paragraph if Firebase Analytics is
removed from the app.]

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
- To understand how the app is used so we can improve it (Firebase Analytics).

We don't use your data for advertising and we don't sell it.

## 5. Who processes your data for us

We use these providers to run SmartSpend. They process data on our instructions and not for
their own purposes:

| Provider | What for | Where |
|---|---|---|
| Neon (database) | Stores your account, profile and transactions | Singapore |
| Render (server hosting) | Runs the SmartSpend backend | [REGION — check the Render service's region] |
| Google Firebase | Phone-number verification, Google sign-in, push notifications, usage analytics | Google's global infrastructure |

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
- **Delete your account** in the app (Account → Delete account) or by requesting it at
  https://hhaarrss.github.io/smart-spend/delete-account.html.
- **Stop SMS reading** by removing the permission in your phone settings.
- **Turn off notifications** in the app or in your phone settings.
- **Withdraw consent, ask a question or make a complaint** by contacting us at the address
  above. [If required under the DPDP Act / rules: name and contact of your Grievance Officer.]

## 9. Children

SmartSpend is not meant for anyone under 18, and we don't knowingly collect data from children.

## 10. Changes

If we change this policy we'll update the date at the top, and tell you in the app if the change
is significant.
