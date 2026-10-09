# Release checklist — backend deploy and first Play upload

## 1. Deploy the backend to Render

There is no `render.yaml`; the Render service (`firstproject-smartspend.onrender.com`) is
configured in the Render dashboard. As of 28 Sep 2026 it was still serving an old build hours
after merges to `main`, so auto-deploy appears to be **off**.

1. Render dashboard → the Finzyy web service → **Settings → Build & Deploy**: check the
   repository is `smartspend4support-eng/firstproject` and the branch is `main`. Turn
   **Auto-Deploy** on if you want merges to go live by themselves.
2. **Manual Deploy → Deploy latest commit**, and watch **Events/Logs** until it's *Live*.
3. Confirm the new build is live: `https://firstproject-smartspend.onrender.com/openapi.json`
   → `info.description` must **not** mention "Redis + Celery".
4. **Environment** — check these while you're there:
   - `JWT_SECRET_KEY` must be a new value, not the one that was committed to git history
     (see PROJECT_NOTES). Changing it signs everyone out once.
   - `APP_ENV=production`.
   - `FIREBASE_SERVICE_ACCOUNT_JSON` is set (phone sign-in and the Firebase part of account
     deletion need it).
5. If there is a separate Render *worker* service running `celery -A celery_app ...`, delete it:
   that file no longer exists.
6. Then switch the server to the restricted database login — Step 3 of
   [database-access.md](../security/database-access.md).
7. Test on a phone: Home loads, a bank SMS syncs, recategorise works, and deleting a throwaway
   account works.

No Alembic migration is part of this (production isn't managed by Alembic — see
database-access.md).

## 2. Create the Play upload key (once, by you — never share or commit it)

Run in `android/` (JDK's `keytool`; Android Studio ships one under `jbr/bin`):

```bash
keytool -genkeypair -v -keystore upload-key.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

It asks for a password and your name/organisation. Then create `android/keystore.properties`:

```properties
storeFile=upload-key.jks
storePassword=<the password you chose>
keyAlias=upload
keyPassword=<the password you chose>
```

Both files are git-ignored. **Back up `upload-key.jks` and the password somewhere safe
outside the repo** (password manager). If it's lost, you must ask Google to reset the upload key.

In Play Console, keep **Play App Signing** on (the default for new apps): Google holds the real
app-signing key; this file is only the *upload* key.

## 3. Build what you upload

```bash
./gradlew :app:bundleRelease
```

Upload `android/app/build/outputs/bundle/release/app-release.aab`.

- **Crash reports:** R8 obfuscates the release build. The `.aab` carries the R8 mapping file
  inside it, so Play de-obfuscates crash reports automatically — nothing to upload by hand.
  (Only if you ever upload an `.apk` instead do you need to upload
  `app/build/outputs/mapping/release/mapping.txt` yourself.)
- **Every upload needs a higher `versionCode`** in `android/app/build.gradle.kts`
  (currently `1`). Bump `versionName` too for users to see.

## 4. Play Console forms (drafts in this folder)

- Data safety → [data-safety.md](data-safety.md)
- Privacy policy (must be a public URL) → [privacy-policy.md](privacy-policy.md)
- Permissions Declaration Form for SMS → [sms-permission-declaration.md](sms-permission-declaration.md)
- Account deletion web link: `https://hhaarrss.github.io/finzyy/delete-account.html` (email request, which Play accepts) — corrected and live.

## 5. Before every release

- Test the release `.aab` on a real phone first (Play Console → Internal testing track), not
  only a debug build: R8 only runs on release, and mistakes there show up as empty screens.
