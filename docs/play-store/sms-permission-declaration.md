# Play Console — Permissions Declaration Form: SMS (draft)

Play Console → App content → **Sensitive app permissions → SMS and Call Log permissions**.

## Does SmartSpend qualify?

Yes, by a named exception. Google's policy ("Use of SMS or Call Log permission groups", checked
Sept 2026) lists **"SMS-based money management — for example, apps that track and manage
budget"** as an exception eligible for `READ_SMS` and `RECEIVE_SMS`, subject to review. Those are
the only two SMS permissions the app requests. It is not a guaranteed approval: the reviewer
checks that SMS access is the app's *core* feature, that it's disclosed before the permission
prompt, and the policy's spyware rule — *"budgeting apps may not exfiltrate or share
non-financial or personal SMS history of a user."* SmartSpend's design meets that rule; the
answers below say how.

## Form answers

**Core functionality:** SMS-based money management

**Permissions declared:** `READ_SMS`, `RECEIVE_SMS`

**Describe how your app uses these permissions** (paste; ~250 words):

> SmartSpend is an expense tracker for India. Its core feature is automatic expense tracking
> from the payment alerts banks send by SMS: when a user pays by UPI or card, the bank's SMS is
> turned into a categorised transaction so the user sees their spending and budgets without
> typing anything in.
>
> RECEIVE_SMS: detects a bank transaction alert as it arrives.
> READ_SMS: catches bank alerts that arrived while the phone was offline or the app was not
> running, and — only when the user asks — imports recent bank alerts already in the inbox so
> their history is complete from day one.
>
> Every message is checked against a list of known bank and payment sender IDs on the device;
> messages from any other sender are ignored and never processed. Bank alerts are parsed on the
> device, and only the extracted payment fields (amount, debit/credit, merchant, date, bank,
> last 4 digits of the account, UPI reference) are sent to our server over HTTPS. The SMS text
> itself is never uploaded or stored. Personal conversations and non-financial messages are
> never read into the app's data, transmitted or shared.
>
> Before any permission prompt, the app shows a full-screen disclosure explaining this and
> links to the privacy policy. Users can decline and still use the app by adding transactions
> manually.

**Is SMS access required for the app's core functionality?** Yes — without it the app can only
be used by manual entry, which removes its main purpose.

**Why can't you use an alternative?** No alternative API gives an app the bank's transaction
alerts. The SMS Retriever / User Consent APIs cover one-time verification codes only; there is
no Android API for reading bank alerts, and Indian banks don't offer a consumer API for this.

**Video link** (Play requires one): record ~1 minute on a real phone and upload it as an
unlisted YouTube video:
1. Fresh sign-in → the **"We use transaction SMS…"** disclosure screen (hold it on screen).
2. Tap Continue → the Android SMS permission prompt → Allow.
3. A real bank SMS arrives → the transaction appears in the app, categorised.
4. Show the alternative: declining still lets you add a transaction by hand.

## Make sure these match before you submit

- **Store listing description** must lead with the SMS feature ("tracks your spending
  automatically from your bank's SMS alerts…"). The policy requires the core feature to be
  prominently described.
- **Privacy policy** section 3 says the same as the form (see [privacy-policy.md](privacy-policy.md)).
- **Data safety**: *Financial info* declared; *SMS or MMS* answered as not collected
  (see [data-safety.md](data-safety.md)).
- **In-app disclosure** (`SmsConsentScreen.kt`) says "Only messages from recognized bank
  senders are parsed, on your device" and that the app "also checks your inbox in the background
  for bank SMS it missed while your phone was offline" — matching this form.
- If you ever send SMS text to the server, add a new SMS use, or add a new SDK that could read
  SMS data, you must submit this form again.
