# Database access — separate logins and a development database

**Why:** until September 2026 one login, `neondb_owner`, was used for everything — the live
server on Render, local development (`backend/.env`), schema changes, and Claude's Neon
connection. It can read and change every row and drop every table.

**Target:**

| Who / what | Login | Can do |
|---|---|---|
| Live server (Render) | `smartspend_app` | Read/write rows only. Cannot change, empty or drop tables. |
| Schema changes (just before the server starts) | `neondb_owner` | Everything — used only by `scripts/ensure_schema.py`. |
| Debugging production (you, support) | `smartspend_readonly` | Read only; can't see password hashes or push tokens. |
| Local development and tests | the `dev` branch | Anything — it holds no real users. |

Project: Neon **Smart Spend** (`tiny-cherry-87955782`), region Singapore, database `neondb`.

**How the schema is managed:** production has never been run through Alembic (there is no
`alembic_version` table). Its schema is kept current by the idempotent `ADD COLUMN IF NOT EXISTS`
statements in `backend/main.py`, which `scripts/ensure_schema.py` runs. **Do not run
`alembic upgrade head` against production** — it would start from the first migration and try to
create tables that already exist.

---

## Status

| Step | Status |
|---|---|
| 1. `dev` branch with no user data | **Done** (28 Sep 2026) |
| 2. `smartspend_app` and `smartspend_readonly` logins | **Created, without passwords** (28 Sep 2026) — you set the passwords |
| 3. Render uses `smartspend_app` | To do — you |
| 4. Rotate the owner password | To do — you, after step 3 |
| 5. Local `.env` points at `dev` | To do — you |

---

## Step 1 — Development branch (done)

Branch **`dev`** (`br-green-mouse-az5aa9db`) was created from production and then emptied: all
7 tables have 0 rows. It contains a table `__dev_branch_marker`; scripts that clean up or reset
data check for that table so they can never run against production by mistake. Don't create it
on production.

> The copy's own 6-hour recovery history held the copied rows until about 13:45 UTC on
> 28 Sep 2026; after that the branch holds no user data at all.

To use it: Neon Console → Branches → `dev` → **Connect** → copy the connection string.

## Step 2 — Set the passwords for the two logins

Both logins exist on the `production` branch with exactly the rights in the table above, and were
checked: neither is a member of `neon_superuser`, neither can TRUNCATE or create tables, and
`smartspend_readonly` cannot read `users.hashed_password` or `users.fcm_token`. **They have no
password yet, so nobody can log in with them.**

Neon Console → Smart Spend → **SQL Editor** → branch `production`, database `neondb`. Generate
two long random passwords in your password manager (Neon rejects weak ones), then run:

```sql
ALTER ROLE smartspend_app PASSWORD '<password-1>';
ALTER ROLE smartspend_readonly PASSWORD '<password-2>';
```

Don't create or edit these roles on the Console's **Roles** page — roles managed there are added
to `neon_superuser`, which can read and write everything and defeats the purpose.

## Step 3 — Point Render at the restricted login

A connection string for it is the one Neon shows under **Connect** with the user and password
swapped: `postgresql://smartspend_app:<password-1>@<same host>/neondb?sslmode=require`
(URL-encode the password if it has special characters).

Render dashboard → SmartSpend web service:

1. **Environment**
   - `DATABASE_URL` → the `smartspend_app` connection string.
   - Add `MIGRATION_DATABASE_URL` → the current `neondb_owner` connection string (what
     `DATABASE_URL` holds today).
2. **If the service is built from `backend/Dockerfile`** (Settings shows *Runtime: Docker*),
   there's nothing more to set: once `MIGRATION_DATABASE_URL` exists, the container applies
   schema changes with it and then starts the server with `DATABASE_URL`.
   **If it's a Python runtime**, set **Settings → Start Command** to:
   ```bash
   sh -c 'DATABASE_URL="$MIGRATION_DATABASE_URL" python scripts/ensure_schema.py && uvicorn main:app --host 0.0.0.0 --port ${PORT:-8000}'
   ```
3. **Manual Deploy → Deploy latest commit**. In the logs you should see
   `Schema statements applied.`, then the server starting, then one expected line:
   `Schema safety net skipped (ProgrammingError); expecting scripts/ensure_schema.py to have run.`
4. Check the app: Home loads, a bank SMS syncs, recategorising works, and
   Account → Privacy & legal → Delete account works on a throwaway account.

If anything fails, set `DATABASE_URL` back to the owner string and redeploy — nothing in the
database changes when you switch logins.

## Step 4 — Rotate the owner password (after step 3 works)

The owner password has been in `backend/.env` and used widely:

1. Neon Console → **Roles → neondb_owner → Reset password**.
2. Put the new string in `MIGRATION_DATABASE_URL` on Render (the only place it should live) and
   in your password manager. Redeploy.
3. Make sure no `.env` on any laptop still has a production connection string.

## Step 5 — Local development against `dev`

In your local `backend/.env`, set `DATABASE_URL` to the `dev` branch string and make sure
`APP_ENV` is **not** `production`. On start the backend adds any missing columns and creates a
local seed user. The backend tests (`backend/tests/`) use this `DATABASE_URL` too — today they
would write test rows into production, so do this before running them.

## Step 6 — Claude's Neon connection

The Neon connector used in these sessions has admin rights on the whole project. Disconnect it
(claude.ai connector settings) when you don't need it. For inspecting data, the `dev` branch or
the `smartspend_readonly` login is enough.

## Also worth knowing

- **IP allowlist:** Neon can restrict which IPs may connect (Project settings → Network security
  → IP Allow). It's a paid-plan feature; on the free plan any IP with the password can connect.
- **Protect the production branch:** Branches → production → **Set as protected** (if your plan
  allows) prevents it from being deleted or reset by mistake.
- **History:** deleted data stays recoverable in Neon for 6 hours. The privacy policy says so.
