# Release checklist — backend deploy and first Play upload

## 1. Deploy the backend to Render

There is no `render.yaml`; the Render service (`firstproject-smartspend.onrender.com`) is
configured in the Render dashboard.

1. Merge the PR into `main` on GitHub.
2. Render dashboard → the SmartSpend web service → **Settings → Build & Deploy**:
   - **Branch** should be `main`.
   - If **Auto-Deploy** is *Yes*, the merge deploys by itself — watch the **Events** tab.
   - If it is *No*: **Manual Deploy → Deploy latest commit**.
3. When the deploy is **Live**, check:
   - `https://firstproject-smartspend.onrender.com/docs` loads.
   - The app still loads Home, and a new bank SMS still syncs.
4. `celery` and `redis` were removed from `requirements.txt`. If the Render **Start Command**
   mentions `celery`, or there is a separate Render *worker* service running
   `celery -A celery_app ...`, delete that worker service — the file it runs no longer exists.
5. Account deletion now also removes the user's Firebase sign-in. That needs
   `FIREBASE_SERVICE_ACCOUNT_JSON` in the service's **Environment** — it is already required for
   phone sign-in, so it should be there. If it's missing, deletion still removes all data but
   logs `[Firebase] Admin SDK not configured`.

No database migration is needed for this release.

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
- Account deletion web link: `https://hhaarrss.github.io/smart-spend/delete-account.html` already exists (email request, which Play accepts) — fix its wording first (see privacy-policy.md).

## 5. Before every release

- Test the release `.aab` on a real phone first (Play Console → Internal testing track), not
  only a debug build: R8 only runs on release, and mistakes there show up as empty screens.
