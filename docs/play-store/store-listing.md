# Play Console — store listing text (draft)

Play expects the SMS use to be described where users and reviewers read about the app, in the
same terms as the in-app disclosure, the privacy policy and the permission declaration. The text
below is written to match all four. Change the app name once it is decided (see
[release-checklist.md](release-checklist.md)).

## Short description (80 characters)

> Track your spending automatically from your bank's payment SMS.

## Full description

> SmartSpend is an expense tracker for Android that builds your spending ledger from the payment
> SMS your bank already sends you.
>
> **How it works**
> - With your permission, SmartSpend reads SMS from recognized bank senders on your phone and picks
>   out each payment: the amount, the merchant, the date, the bank and the last 4 digits of the
>   account.
> - Each payment is categorized (food, groceries, travel and so on). If a category is wrong, change
>   it once and SmartSpend files that merchant the same way next time.
> - See your spending by day, week and month, set budgets, and get an alert as you approach one.
>
> **What leaves your phone**
> - The text of your messages is read on your phone and is not uploaded. Messages that are not from
>   a recognized bank sender are ignored.
> - For each bank payment, only the amount, merchant, date, bank, last 4 digits of the account and
>   UPI reference are sent to our server over an encrypted connection.
> - We do not sell your data or use it for advertising.
>
> **Your control**
> - SMS access is optional. Without it you can add payments by hand.
> - You can delete your account and all its data from the app (Account → Privacy & legal → Delete
>   account), or on our website.
>
> SmartSpend reads SMS permission (READ_SMS, RECEIVE_SMS) only for this purpose: tracking and
> managing your budget from bank payment messages.

## Links to fill in

- Privacy policy: https://hhaarrss.github.io/finzyy/privacy-policy.html
- Account deletion page: https://hhaarrss.github.io/finzyy/delete-account.html

## Keep these consistent

| Where | Must say |
|---|---|
| This listing | SMS from bank senders are read for budget tracking; text stays on the phone; parsed fields are sent |
| In-app disclosure (`SmsConsentScreen.kt`) | Same, before the permission prompt |
| Privacy policy, section 3 | Same |
| Permissions Declaration Form | Same, plus the demo video ([sms-permission-declaration.md](sms-permission-declaration.md)) |
| Data safety form | Financial info collected; SMS or MMS not collected ([data-safety.md](data-safety.md)) |
