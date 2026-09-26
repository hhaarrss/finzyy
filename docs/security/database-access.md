# Database access — separate logins and a development database

**Why:** today one login, `neondb_owner`, is used for everything — the live server on Render,
local development (`backend/.env`), migrations, and Claude's Neon connection. It can read and
change every row and drop every table. Anyone holding that one password has all users' data.

**Goal:**

| Who / what | Login | Can do |
|---|---|---|
| Live server (Render) | `smartspend_app` (new) | Read/write rows only. Cannot change or drop tables. |
| Migrations (Render pre-deploy) | `neondb_owner` | Everything — used only while deploying. |
| Debugging production (you, support) | `smartspend_readonly` (new) | Read only. |
| Local development | the **development branch**, not production | Anything — it holds no real users. |

Project: Neon **Smart Spend** (`tiny-cherry-87955782`), region Singapore, branch `production`
(the only branch), database `neondb`.

---

## Step 1 — Create a development database without real data

1. Neon Console → Smart Spend → **Branches → New branch**.
2. Name `dev`, parent `production`, and choose **Schema only** (copies the tables, **no rows**).
   Do *not* pick "Current data" — that would copy every user's data into dev.
3. Open the `dev` branch → **Connect** → copy its connection string.
4. In your local `backend/.env`, set `DATABASE_URL` to that `dev` string and make sure
   `APP_ENV` is **not** `production`. On start, the backend adds any missing columns and
   creates a local seed user for testing.
5. From now on, run the backend locally, tests and experiments against `dev` only.

> Note: the backend tests (`backend/tests/`) use whatever `DATABASE_URL` is set — with the
> `.env` change above they stop writing test rows into production.

## Step 2 — Create the two restricted logins (in production)

Neon Console → Smart Spend → **SQL Editor** → branch `production`, database `neondb`.

Create roles **with SQL, not in the Console's "Roles" page** — roles made there are added to
`neon_superuser`, which can read and write everything and defeats the purpose.

Generate two long random passwords first (password manager), then run, replacing the
`<...>` values:

```sql
-- The live server: rows only, no schema changes.
CREATE ROLE smartspend_app LOGIN PASSWORD '<password-1>';
GRANT CONNECT ON DATABASE neondb TO smartspend_app;
GRANT USAGE ON SCHEMA public TO smartspend_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO smartspend_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO smartspend_app;
-- Tables and sequences created by future migrations get the same rights automatically.
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO smartspend_app;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO smartspend_app;

-- Debugging: read only.
CREATE ROLE smartspend_readonly LOGIN PASSWORD '<password-2>';
GRANT CONNECT ON DATABASE neondb TO smartspend_readonly;
GRANT USAGE ON SCHEMA public TO smartspend_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO smartspend_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE neondb_owner IN SCHEMA public
    GRANT SELECT ON TABLES TO smartspend_readonly;
```

Check it worked:

```sql
SELECT rolname, pg_has_role(rolname, 'neon_superuser', 'member') AS is_superuser_member
FROM pg_roles WHERE rolname IN ('smartspend_app', 'smartspend_readonly');
-- both rows must show false
```

Optional, stricter: the read-only login doesn't need password hashes or push tokens:

```sql
REVOKE SELECT ON users FROM smartspend_readonly;
GRANT SELECT (id, email, phone_number, full_name, profile_completed_at, family_id, created_at)
    ON users TO smartspend_readonly;
```

## Step 3 — Point Render at the new login

Connection strings look like the one Neon shows under **Connect**, with the user and password
swapped: `postgresql://smartspend_app:<password-1>@<same host>/neondb?sslmode=require`
(URL-encode the password if it has special characters).

Render dashboard → SmartSpend web service → **Environment**:

1. `DATABASE_URL` → the `smartspend_app` connection string.
2. Add `MIGRATION_DATABASE_URL` → the existing `neondb_owner` connection string.
3. **Settings → Pre-Deploy Command** → set it to (or, if it's empty today, **add**):
   ```bash
   sh -c 'DATABASE_URL="$MIGRATION_DATABASE_URL" alembic upgrade head'
   ```
   so only migrations use the owner login. This step is required, not optional: with the
   restricted login the server can no longer add missing columns itself at startup, so schema
   changes must come from this pre-deploy migration.
4. **Manual Deploy → Deploy latest commit**, then check the app: Home loads, a bank SMS syncs,
   recategorising works, and Account → Delete account on a throwaway account succeeds.

Expect one harmless log line on each start in production:
`Warning: Database connectivity check failed: ... must be owner of table users`. The server tries
a few "add column if missing" statements at startup as a safety net; the app login is (correctly)
not allowed to, and the real migrations already ran in the pre-deploy step. The server keeps
running normally. (That warning text is misleading — worth a one-line code fix later so it says
"schema check skipped" instead.)

## Step 4 — Rotate the owner password and remove it from everywhere else

The owner password has been in `backend/.env` and used widely, so replace it:

1. Neon Console → **Roles → neondb_owner → Reset password**.
2. Update `MIGRATION_DATABASE_URL` on Render with the new password (only place it should live),
   and keep a copy in your password manager.
3. Make sure no `.env` on any laptop still has a production connection string.

## Step 5 — Claude's Neon connection

The Neon connector used in these sessions has admin rights on the whole project (it can read
every row and change anything). Keep it for when you need it, and disconnect it
(claude.ai connector settings) when you don't. When you only need to inspect data, give it the
`dev` branch or the `smartspend_readonly` login instead of the owner.

## Also worth knowing

- **IP allowlist:** Neon can restrict which IPs may connect at all (Project settings → Network
  security → IP Allow). It's a paid-plan feature; the project is on the free plan today, so any
  IP with the password can connect — another reason to keep the passwords tight.
- **Protect the production branch:** Branches → production → **Set as protected** (if your plan
  allows) prevents it from being deleted or reset by mistake.
- **History:** deleted data stays recoverable in Neon for 6 hours (`history_retention_seconds`).
  The privacy policy says so.
