# Finzyy — Project Notes

Read this file fully before starting any task. It contains architectural
decisions, current status, and hard rules — do not deviate from these
without explicit confirmation from the developer.

## What this project is

Android expense tracker for the Indian UPI/banking ecosystem. Reads bank
SMS passively (on-device), categorizes transactions automatically via a
5-layer waterfall, and gives budgeting/insights. Backend: FastAPI +
PostgreSQL. Mobile: Kotlin + Jetpack Compose. Mobile-only — the React web
dashboard (frontend/) was removed in Sept 2026, along with its CORS origins.

## Ownership split — DO NOT CROSS THESE LINES

- **Colleague owns:** Welcome → Login → OTP entry (mobile UI),
  `/auth/login`, `/auth/register`, JWT issuance, rate limiting on those
  two endpoints. **Do not modify these files or endpoints unless
  explicitly told to in this session's task.**
- **Developer (primary user) owns:** everything else — Home, Add
  Transaction, Budget, Categories, Trends, Insights, Account, SMS
  parsing/permission flow, deployment, migrations.

## Current status (update this section after every completed phase)

Done and verified:
- Privacy hardening, merged 28 Sep 2026 (PR #2): HTTPS-only release builds,
  Keystore-encrypted token + offline SMS queue, backup off, R8 on (with
  Gson keep rules — verified in the release dex), full account deletion
  incl. Firebase Auth record, no personal data in logs/500 responses,
  Celery/Redis raw-SMS task removed, Firebase Analytics removed. Play drafts
  in `docs/play-store/`, DB access plan in `docs/security/database-access.md`.
- Neon: `dev` branch (empty, marker table `__dev_branch_marker`) and
  restricted roles `smartspend_app` / `smartspend_readonly` created on
  production 28 Sep 2026, without passwords. Production has no
  `alembic_version`; its schema is kept by `scripts/ensure_schema.py`
  (never run `alembic upgrade head` there).
- Transaction notifications with Categorize action (on main); guided tour
  for new accounts (PR #4). Visit count + month total: PR #3, open.
- Backend data layer: single source of truth service layer
  (`transaction_aggregates.py`), category dedup, MIN_MEANINGFUL_BASELINE
  guard (₹100) for MoM %, "Needs Review" excluded from category
  breakdowns, credit-side categories added (Salary, Refund, Interest,
  Bank Deposit, Investment Return, Reimbursement, Cashback, Other Credit)
- Category normalization happens at WRITE time (not just read time) —
  `@validates("category")` on Transaction and MerchantMapping models,
  plus normalization in every router write path. Read-time normalization
  in aggregates is kept as a defensive fallback, not removed.
- Android: Home, Add Transaction, Budget, Categories (+ Merchants tab +
  drill-down), Trends (Chart + Insights tabs, filter panel), Account
  page (Privacy Policy/Terms/Support/Delete links, working delete flow)
  — all built and device-verified with screenshots
- `DELETE /users/me` — cascades across transactions, budget limits,
  merchant mappings. Tested end-to-end against real DB rows.
- On-device SMS parser (`SmsTransactionParser.kt`) — ported from real
  `backend/utils/sms_parser.py` regex patterns, not invented. Backend
  `ingest-sms` schema uses `extra="forbid"`, rejects any raw SMS field.
  No `raw_sms` (or any SMS-text) column exists in production — checked
  28 Sep 2026 — so the raw-SMS purge migration has nothing left to do.
- Hosted compliance pages (GitHub Pages): Privacy Policy, Terms of
  Service, Support/FAQ, Account Deletion — all live, HTTP 200.
- Security audit pass 1: CORS fixed (was `["*"]` + credentials, invalid
  per spec), hardcoded JWT_SECRET_KEY removed from docker-compose.yml
  — **but the OLD secret was committed twice to git history and must
  be treated as permanently compromised. A new JWT_SECRET_KEY must be
  generated and used in Render, never reuse the old value.**
- Fixed: `DEV_SKIP_AUTH` was set in `defaultConfig`, which applies to
  every build type including `release`. That flag also gates the
  cleartext LAN dev-backend URL and (as of this same branch) a fabricated
  login token, so a release build as shipped would have skipped real
  login, minted a fake session, and sent transaction data over plain
  HTTP to a hardcoded LAN IP. Moved to per-buildType in
  `android/app/build.gradle.kts`: `true` in `debug`, `false` in
  `release` — a release build now falls through to the real login flow
  and the production HTTPS backend, same as before `DEV_SKIP_AUTH`
  existed. Not build-verified (same sandbox limitation as everything
  else on this branch) — confirm a `release` build variant actually
  compiles and routes correctly before shipping one.

Design system (built, not yet device-verified):
- `ui/theme/` is now a real token system instead of the Android Studio
  template (it was still `Purple80`/`Pink40`, which nothing referenced —
  that is why every screen hardcoded hex): `Color.kt` (palette),
  `FinzyyColors.kt` (semantic tokens M3 has no slot for — positive /
  negative / caution / accent / inkMuted / subtleSurface / hairline),
  `CategoryPalette.kt` (per-category accent + chip tint, looked up by name
  so a server-added category degrades instead of crashing), `Type.kt`
  (chunky scale, display 56sp down to 10sp labels, tight negative tracking
  on large sizes, `TabularAmount` for decimal-aligned money), `Shape.kt`
  (12–34dp radii), `Theme.kt` (light + dark schemes, both provided via
  CompositionLocal). Read tokens as `FinzyyTheme.colors.x` /
  `FinzyyTheme.categories[name]`.
- **Material You dynamic color was removed deliberately.** It overrides the
  palette with the user's wallpaper colours, which would erase the brand
  direction on every device that supports it.
- `ui/components/MerchantAvatar.kt` is the single visual representation of a
  transaction, resolving brand mark → category glyph → monogram. The brand
  and glyph tiers currently return null, so everything renders the monogram
  chip in the category accent — correct, just less specific. Both are
  single-function seams; artwork drops in there and every screen picks it up.
- Artwork still needed: the 14 category glyphs. Note before adding merchant
  brand marks — Swiggy/Amazon/Uber logos are trademarks, so bundling them in
  a shipped APK needs a deliberate call on usage rights.
- Home is rebuilt on the system and is the reference screen: hero spend card
  (the one number the screen exists for), a MoM pill phrased as rupees rather
  than "-12.4% MoM", an income-usage bar, and a burn-rate/days-left insight
  strip. Zero hardcoded hex remains in it.
- **Known issue, deliberately not fixed yet:** `res/values/themes.xml` sets
  `Theme.Finzyy` to `Theme.Material3.Dark.NoActionBar` with a hardcoded
  dark window/status/nav bar. In light mode that mismatches the Compose
  background and shows a dark launch flash. The proper fix is a DayNight
  parent plus `values-night/`, but that also restyles the legacy XML login
  screens (colleague's surface, which assumes dark), so it belongs with the
  legacy-flow retirement rather than as an unverifiable change now.
- (Superseded by the Sept 2026 redesign below — all post-login screens now use tokens.)

Visual direction — "Ledger" (approved Sept 2026, supersedes "playful & chunky" below):
- Reference: https://claude.ai/artifact/XeRhdgewS4ZJesaQ5TbM47 (private to the developer).
- Dark is primary and black-and-white like CRED (#000 ground, #0E0E0E / #171717 surfaces,
  #262626 hairlines, #F4F4F0 ink). Light is cool stone #F1F1EE — deliberately not cream.
  One engraved old-gold accent (#B8965A) for the hero guilloché and a single active state.
- Colour only means money: green in, red over budget, amber approaching. Categories are not
  colour-coded (CategoryPalette is monochrome); share-of-total uses `shareShades` by rank with
  labelled rows beside it. Donut chart retired for `ShareStrip`.
- Type roles: `WideFamily` (Archivo 125% width) for amounts/titles, `MonoFamily` (JetBrains
  Mono) for statement detail/eyebrows, system sans for body. Both are placeholders in
  ui/theme/Fonts.kt until the font files are bundled in res/font.
- Lists are ledger rows split by hairlines, not cards. `Guilloche()` (Compose) and
  res/drawable/guilloche_rosette.xml (generated, XML login) share one geometry.

Design direction (decided, pending visual design pass):
- Full UI redesign of every screen. Current screens are functional but
  generic; the goal is a distinct product feel, not a template look.
- Visual direction: **playful & chunky** — oversized bold numbers, thick
  rounded cards, bright accents, hierarchy from scale rather than
  decoration. Explicitly not corporate/banking-formal.
- **Light and dark designed together from day one**, not dark retrofitted
  later. Note this conflicts with the current code, where every screen
  hardcodes inline `Color(0xFF...)` literals instead of theme tokens —
  that has to be refactored onto `ui/theme/` tokens as part of the redesign,
  or dark mode is not achievable.
- Icon system, three-tier with fallback: (1) real merchant brand marks for
  recognised merchants, (2) duotone geometric category icons with local
  specificity (auto-rickshaw for Transport, chai glass for Food) as
  fallback, (3) monogram chip in the category accent colour for unknown
  merchants. Chosen over sticker-illustrative (needs a new asset per
  merchant, noisy in dense lists) and soft-3D clay (heaviest, hard to keep
  consistent across both themes).
- Splits: shown on Home as a "Coming soon" preview card only (UI, no behaviour) — the feature itself is still future scope.
- Still to design: welcome screen with motion, animated app logo / launch
  sequence, and a "dashboard updated" popup shown after a transaction syncs.

UI redesign — Sept 2026 (code complete; see verification note):
- **The Compose app is now the real post-login UI.** Before this, `DEV_SKIP_AUTH=false` meant every
  real user got the legacy XML dashboard (Home/Add/Budget/Insights/Profile tabs in
  `activity_main.xml` + `MainActivity`), and the Compose screens only ran in the dev stub path.
  `MainActivity.showDashboard()` now hands the window to `FinzyyNavHost`; the XML dashboard
  section, its adapters/chart views/item layouts were deleted. **Login/register/Google sign-in
  functions were carried over byte-for-byte (verified by script) — colleague's scope untouched.**
- Auth for Compose calls: `SessionStore` + `AuthHeaderInterceptor` (OkHttp) add the stored JWT to
  any request without one, and report 401s so `MainActivity` returns to login. This is why the
  `...NoAuth` Retrofit methods now work against production. No endpoint or auth logic changed.
- Flow (hub-and-spoke from Home, no bottom nav): Home → Search, Account, Add transaction, Budget
  plan, Trends (+ right-side filter panel), Spending breakdown (Categories | Merchants, shared
  period chips) → Category detail (6-month bar chart doubles as month picker), Insights.
  Transaction detail is a bottom sheet (recategorize = merchant learning, edit, delete) — this
  restores the edit/delete/review actions the XML dashboard had.
- Insights page (new design): month pace (projection, safe-to-spend/day with a budget), budget
  alerts, MoM movers, recurring, spikes — all from existing `/insights/summary` + `/home`.
- Category palette re-stepped onto a colour-blind-validated 8-hue set (old Food/Transport and
  Education/Entertainment pairs failed CVD checks). Donut caps at top 5 + Other.
- Theme toggle (System/Light/Dark) is real (`ThemePreference`); notification toggle now gates
  FCM display. "Sync existing SMS" (old dashboard button) moved to `sms/HistoricalSmsSync.kt`,
  used by Account and by the consent screen's scanning step (which was a timed pause before).
- First sign-in shows the SMS disclosure screen once, replacing the old unexplained permission
  prompt fired at every launch.
- Search matches merchant/category/bank/amount over the last 6 months client-side (the list
  endpoint has no text query). "From SMS" = row has bank or card digits; `source` is the
  categoriser's origin, not SMS-vs-manual.
- Blog URL is a placeholder (product site root) — replace `BLOG_URL` in `AccountScreen.kt`.
  Phone row reads `user_phone` / `user_phone_verified` prefs, which nothing writes yet (lands
  with colleague's OTP login).

In progress / not yet done:
- Navigation rebuilt on Navigation Compose (`ui/navigation/`): `Destination`
  enum holds the route ids, `FinzyyNavHost` owns the graph. Replaces the
  hand-rolled `when(route)` + `DevRoute` enum that lived in `MainActivity`,
  which had no back stack (system back exited the app from any screen) and
  lost its position on process death. Also fixed while in there: a
  double-tap during a transition pushed the destination twice (guarded by
  dropping nav events from a non-RESUMED entry); the SMS consent screen now
  pops off the back stack on every outcome so back from Home can't re-enter
  the disclosure flow; logout clears the whole back stack, which is where
  the auth graph attaches once real login exists. **Navigation Compose is
  pinned at 2.8.4 in `libs.versions.toml` — this is the one line that could
  not be verified here (Google Maven is unreachable from the sandbox), bump
  it if the toolchain complains.**
- Permission state is now lifecycle-aware (`ui/permission/SmsPermissionState.kt`).
  Two real bugs fixed: (1) the Home auto-sync card read the permission once
  at composition, so granting from system Settings and returning left it
  showing "Off" indefinitely — it now re-reads on every resume; (2)
  `context as? Activity` in the consent screen returns null whenever
  LocalContext is a ContextWrapper, which silently forced
  `shouldShowRequestPermissionRationale` to false and could misroute a
  first-time denial to the "permanently denied" screen — replaced with a
  proper `findComponentActivity()` unwrap. The permanently-denied screen
  also now detects an out-of-band grant from Settings and proceeds instead
  of continuing to claim the permission is blocked.
- Fixed: dev backend base URL was hardcoded to `http://127.0.0.1:8000/`
  in `BackendService.kt`, which only works when the app and the backend
  run on the same machine (emulator + `adb reverse`, or a desktop
  build). On a real physical phone, 127.0.0.1 is the phone itself, so
  it always failed with "failed to connect to /127.0.0.1:8000" —
  this is what "Could not load Home" traced back to. Now configurable:
  `android/app/build.gradle.kts` injects `BuildConfig.DEV_BACKEND_BASE_URL`
  from (in order) a `-PdevBackendBaseUrl` flag, `dev.backend.base.url`
  in `android/local.properties` (gitignored, per-machine), or a LAN
  fallback. `BackendService.kt` now reads that field instead of a
  hardcoded string. Still requires: the backend actually running and
  reachable (`docker-compose up` in `backend/`, binds `0.0.0.0:8000`
  already) and phone + dev machine on the same Wi-Fi network with the
  firewall allowing port 8000 — set `dev.backend.base.url` in
  `local.properties` if the dev machine's LAN IP isn't
  `192.168.29.227` (the fallback baked in, taken from a prior working
  value already present in the app's network security config).
- Fixed: real incoming SMS were parsed correctly but never reached the
  backend. Root cause — the dev Compose entry point (`DEV_SKIP_AUTH=true`
  in `MainActivity.kt`) skips the login flow entirely and never wrote a
  `jwt_token` to SharedPreferences, but `SmsReceiver.sendToBackend()`
  bails out early (logs "No JWT token found, cannot ingest SMS") whenever
  that key is empty. So SMS auto-sync silently did nothing end-to-end,
  even with permission granted and a real bank SMS arriving, no error
  surfaced anywhere. Fix: seed a stub token on startup in the dev branch
  — the backend's `AUTH_STUB` mode ignores the token's contents and
  always resolves to the seeded stub user, so any non-empty value
  satisfies the client-side check. Still unverified on a real device
  (same sandbox network limitation as above) — needs an actual bank SMS
  received on a test device to confirm it now reaches the backend and
  shows up on Home.
- SMS auto-sync consent/permission screen — **code written, NOT yet
  build- or device-verified.** Lives at
  `android/app/src/main/java/com/smartspend/app/ui/permission/SmsConsentScreen.kt`
  on branch `claude/sms-consent-auto-sync-vlewm3` (not merged, no PR
  opened yet). Implements: inline disclosure copy + Privacy Policy link,
  skip-if-already-granted check via `ContextCompat.checkSelfPermission`,
  and all three permission outcomes (granted → brief "Scanning your
  messages..." state → Home with auto-sync active; first denial → Home
  in manual-entry mode; permanently denied →
  `shouldShowRequestPermissionRationale == false` after a prior request →
  in-screen message + deep link to the app's system permission page).
  Home now has a live "SMS auto-sync" card wired to launch this screen
  (`HomeScreen.kt`, `MainActivity.kt` `DevRoute.SmsConsent`). **Could not
  build or screenshot in this cloud dev sandbox** — its egress
  policy blocks `dl.google.com`, which hosts the Android Gradle Plugin,
  so no Gradle build is possible there at all. Per this project's
  verification standard, this must be built and screenshotted on a real
  device/emulator for all 3 outcomes, and `SmsReceiver` confirmed firing
  on a real incoming bank SMS, before it counts as done.
- Auth stub (`AUTH_STUB=true`) still used for local dev — real auth is
  colleague's work, not yet integrated
- Render production deployment — still serving an old build as of
  28 Sep 2026 (auto-deploy from `main` appears to be off); needs a manual
  deploy, then the restricted-login switch in docs/security/database-access.md
- Production fingerprint purge migration — MUST NOT
  run until Render deployment is confirmed working AND colleague's
  auth work is stable
- Second security audit pass — scheduled for after colleague's auth
  work lands
- CSV export — backend endpoint does not exist yet
- App name — "Finzyy" is taken on Play Store by 6+ apps, needs a
  final decision before Play Store submission (candidates discussed:
  Paisa Pilot, AutoLedger, PocketPilot, SpendSync, NudgeSpend,
  TrackLess, RupeeRadar — none verified for availability)

## Hard rules — never do these without explicit confirmation

1. **Never run the raw-SMS purge or fingerprint purge migrations**
   against production without a fresh backup immediately before, and
   explicit approval for each step separately (purge, verify, THEN
   drop column — never chain them).
2. **Never modify auth/login/register/JWT-issuance code** — colleague's
   scope. Flag if a task seems to require touching it instead of doing so.
3. **Never invent new regex/parsing logic for SMS** — the real Python
   source in `backend/utils/sms_parser.py` is the source of truth. Port
   from it, don't approximate, unless explicitly told the source doesn't
   cover a case and a new pattern is needed (then flag it explicitly,
   don't silently add it).
4. **Never add a new backend endpoint without flagging it** — most
   screens should reuse existing service-layer functions
   (`transaction_aggregates.py`). If a new endpoint is genuinely needed,
   say so explicitly in the report rather than quietly creating one.
5. **Never assume `AUTH_STUB=true` is safe** — it must hard-fail if
   `APP_ENV=production` is also set. Don't weaken or remove this guard.
6. **Category list is canonical** — do not introduce new category
   strings outside `CANONICAL_CATEGORY_MAP` without updating that map
   first. Both debit and credit category sets exist; validate
   `category` against `transaction_type` at write time.

## Architecture patterns to follow

- **Single source of truth:** any number shown on more than one screen
  (spend totals, category breakdowns, budget utilization) must come
  from the same `transaction_aggregates.py` function, called by both
  screens — never two independent queries computing "the same" number.
- **Write-time, not read-time, normalization:** category normalization
  happens when data is saved, not just when it's displayed. This
  applies to any new write path added in the future too.
- **No raw SMS ever reaches the backend or gets stored.** Parsing is
  on-device only. If a task involves SMS in any way, confirm this
  constraint is preserved.

## Verification standard — apply this to every task

Do not report a task as "done" based on a passing build or test count
alone. For any UI change: take a screenshot on a real device/emulator
and confirm visually, not just that it compiled. For any data-layer
change: query the actual database to confirm the real row-level effect,
not just that an endpoint returned 200. For any claim about what code
does: quote the actual relevant lines in the report, not a paraphrase.

If the current execution environment cannot build or run the app (e.g.
network egress restrictions), say so explicitly and mark the change as
unverified rather than reporting it as done.

## Where to find more detail

- `docs/` folder (repo root) — hosted compliance pages source (privacy
  policy, terms, support, delete-account HTML)
- Ask the developer for the full master specification doc if deeper
  context on any past decision is needed — this file is a condensed
  summary, not the complete history.
