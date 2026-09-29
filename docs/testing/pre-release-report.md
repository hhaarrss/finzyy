# Pre-release test report — 29 Sep 2026

Scope: code, API, database. UI testing on a device was skipped at the owner's request — see the
manual checklist at the end. Everything below was run against `main` at `15073ea`, plus the fixes
in this branch.

## Results at a glance

| Layer | What ran | Result |
|---|---|---|
| Backend static | pyflakes, bandit, pip-audit | 1 real bug (undefined function), 3 false alarms, 1 dependency advisory |
| Backend tests | pytest, no database | 59 pass. 31 need a database and were not run (see "Not covered") |
| New regression tests | `tests/test_route_security.py` | 3 pass on the fix; all 3 fail on the old code |
| Live API probes | forged tokens, CORS, headers, bad input, route inventory | 2 critical holes, rest OK |
| Android | unit tests, Lint (release) | 14/14 pass; Lint 3 errors → 0 (107 style warnings left) |
| Database | schema and index review | 1 missing index added; backup-table privacy risk flagged |

## Fixed in this branch

**Critical — reading other users' payments.** Any user could join any family with
`POST /family/join {"family_id": N}` (numbers are sequential, no invite or approval), and then
`GET /transactions/?user_id=X` returned the full history of any member of that family.
→ Cross-user reads are refused outright (the app never uses them), and the family routes are not
registered in production until an invite/approval flow exists.

**Critical — public demo login in production.** `POST /seed/reset` was callable by any signed-in
user and created `demo@example.com` with a password written in this repo, in a family group.
→ Not registered in production.

**High — amount edits were silently dropped.** Two handlers were registered on
`PATCH /transactions/{id}`; FastAPI used the first, whose schema has no `amount`, `date` or
`notes`. Editing a payment's amount in the app saved nothing.
→ The `/items/{id}` handler no longer claims that route; `edit_transaction` answers it and also
keeps the old behaviour for a category change (marks it reviewed and teaches the categoriser).

**Medium — categoriser never learned from `/items/{id}`.** It called `save_user_correction`,
which doesn't exist; the NameError was swallowed. → Shared `record_merchant_correction` helper,
used by `/recategorize`, `/items/{id}` and `edit_transaction`.

**Low — API map public in production.** `/docs`, `/redoc`, `/openapi.json` are now
development-only.

**Performance — missing index.** `transactions(user_id, date)` added (model + idempotent startup
statement, so the next deploy creates it).

**Android Lint errors.** `windowLightNavigationBar` marked API 27+; telephony declared optional so
Play doesn't hide the app from tablets and Chromebooks.

## Checked and fine

- Every transaction route with an ID checks ownership.
- Forged JWTs (`alg: none`, wrong HS256 key, garbage) → 401 on the live server.
- CORS: a preflight from an untrusted origin is refused.
- HTTP redirects to HTTPS (301). Malformed input → clean 422, no stack trace.
- No secrets committed (keys, private keys, passwords in code).
- bandit's 3 SQL findings in `scripts/` are false positives: only constants, a timestamp and
  integer IDs are interpolated; user input is bound as a parameter.

## Needs a decision or manual check

1. **Backup tables may keep deleted users' data.** `scripts/recategorize_needs_review.py` and
   `scripts/rekey_merchant_mappings.py` create `transactions_bak_*` / `merchant_mappings_bak_*`
   tables that account deletion doesn't touch. If they were ever run on production, check and drop:
   `SELECT tablename FROM pg_tables WHERE tablename LIKE '%\_bak\_%';`
   The scripts should write backups somewhere deletion can reach, or delete them afterwards.
2. **Leftover demo/seed accounts in production.** If `/seed/reset` was ever called, or the backend
   ever started in production without `APP_ENV=production`:
   `SELECT id, email, created_at FROM users WHERE email IN ('demo@example.com', 'your1_email@example.com');`
   Delete any rows found (account deletion cascades to their data).
3. **`ecdsa` advisory (PYSEC-2026-1325), no fixed version.** Pulled in by `python-jose`. JWTs here
   are HS256, which doesn't use ecdsa, so exposure is low. Longer term, `python-jose` could be
   replaced by `PyJWT` (auth code — owner's call).
4. **No rate limiting** on the API. Firebase rate-limits OTP; `/auth/login` (email + password) has
   no limit. Add one before advertising email login.
5. **No security headers** (HSTS, nosniff). Low risk for an API only the app calls.

## Not covered (and how to cover it)

- **31 database-backed backend tests** didn't run: Docker Desktop on this machine exits before its
  engine starts, and the Neon connector was disconnected. Point `backend/.env` at the Neon `dev`
  branch (see `docs/security/database-access.md` step 5), then run
  `python -m pytest tests -q` from `backend/`.
- **Two-account API tests against a real database** (same reason).
- **UI on a device** — skipped by request. Manual checklist below.

## Manual UI checklist (release build from Play Internal testing)

Use the **release** build — R8 only runs on release, and mistakes there look like empty screens.

**New account**
- [ ] Sign up with a new phone number → profile setup → Home shows the guided tour (7 stops,
      Next/Skip work, tour doesn't return after restart).
- [ ] SMS consent screen shows, then the permission prompt; declining still lets you add payments.
- [ ] Notification permission prompt appears on the next launch.

**Money flows**
- [ ] A real bank SMS creates a transaction; the notification shows amount · merchant and a
      Categorize button.
- [ ] Categorize from the notification → sheet over the current app → "Filed under …".
- [ ] Add a payment by hand; **edit its amount** and reopen it — the new amount must stick (this
      was the dropped-edit bug).
- [ ] Delete a payment. Home total, Trends and Categories all agree afterwards.
- [ ] Set an overall budget and a category budget; Home bar and Budget screen agree.

**Conditions**
- [ ] Airplane mode: receive a bank SMS → turn network back on → it syncs.
- [ ] Kill the app from recents, reopen: still signed in, nothing lost.
- [ ] Dark mode and light mode on every screen.
- [ ] Largest font size: nothing clipped on Home, Add, Account.
- [ ] Small/low-end phone if you have one: scrolling on Home and Trends is smooth.

**Account**
- [ ] Log out and back in.
- [ ] Delete a throwaway account (Account → Privacy & legal → Delete account): signed out, and
      signing in again with that number starts a fresh account.
