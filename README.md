# Health Decoder

Photograph a lab report or prescription and get it back as something you can actually use: every
test value extracted and explained in plain language, medicines turned into reminders, results
charted over time, and a one-page brief to hand your doctor at the next visit. Built for Indian
lab formats and available in 11 languages.

Android app + serverless backend. Records live encrypted on the phone and the app works fully
without an account.

**Current release:** 1.3.0 (versionCode 9) · minSdk 24 (Android 7.0) · targetSdk 36
**Repo:** [github.com/bhagwatshree/HealthDecoder](https://github.com/bhagwatshree/HealthDecoder)

---

> ### ⚕️ Medical disclaimer
>
> Health Decoder is **not a medical device** and does **not** provide medical advice, diagnosis,
> or treatment. It summarises documents you give it, using AI that can be wrong. Always consult a
> qualified physician before making any healthcare decision or changing any medication.
>
> The app enforces this: a blocking disclaimer must be accepted before the first launch reaches
> the Home screen, translated into English, Hindi, Telugu, Tamil, Bengali and Marathi
> (`Navigation.kt`). Personalized health tips are restricted to general lifestyle suggestions —
> diet, hydration, sleep, movement — and never name a medicine or a dose.

---

## What it does

**Capture** — Photograph a report page by page, import an existing photo/PDF/Word file, scan the
QR code printed on most Indian lab reports to pull the official digital copy, or link a Gmail
inbox and let it find report attachments on its own. Nothing auto-imports; found reports wait on
the Scan screen until you tap Analyze.

**Understand** — AI reads every test value, medicine, and date off the page. Each result is
flagged against its reference range, explained in plain language, and translated into the
patient's language.

**Track** — Prescriptions become medicine reminders that fire at the right time, including
awkward schedules like twice-a-week. Discharge summaries produce follow-up appointments
automatically. Pending tests get their own due-date reminders.

**Compare** — Trends charts each test over time on-device, standardising readings from different
labs onto one unit so the line stays honest. Compare takes two report files (image or PDF),
analyses both, and reports what actually changed between them.

**Share** — Doctor Brief condenses the active patient into one shareable pre-visit page: what
needs attention, current medicines, upcoming appointment, recent history.

**Multi-patient** — One phone covers a family. Every screen scopes to the active patient, chosen
from the Home header.

### Screens

| | | |
|---|---|---|
| ![Scan](docs/journey_1_scan_light.png) | ![Extraction](docs/journey_2_extraction_light.png) | ![Trends](docs/journey_3_trends_light.png) |
| Scan | Extraction | Trends |
| ![Doctor Brief](docs/journey_4_doctorbrief_light.png) | ![Reminders](docs/journey_5_reminders_light.png) | ![Records](docs/journey_6_records_light.png) |
| Doctor Brief | Reminders | Records |

Dark-mode variants of each are in [`docs/`](docs/). Note these were captured on 2026-08-03, before
the bottom-navigation refactor — they show the flows accurately but not the current chrome, and
are due a refresh before store listing.

An interactive HTML prototype of all 13 screens, plus a Figma-style canvas that maps each
component to its Compose source and design tokens, is at [`mockup/index.html`](mockup/index.html)
— open it in any browser, no build step.

## Navigation

Six first-class destinations, reachable from the bottom navigation bar on every one of them
(`AppBottomNavBar.kt`): **Home · Chat · Trends · Compare · Brief · Settings**.

Home itself (`HomeScreen.kt`) carries:

- **Top bar** — logo and app name, a language picker (globe), and the profile/account icon.
- **Patient selector** — the active family member; everything downstream scopes to them.
- **Health tip card** — rotates every 2 minutes. Once the patient has reports with abnormal
  results it shows tips derived from *their own* values (rule-based, on-device, no AI call —
  `PersonalizedTips.kt`); otherwise it falls back to a general list.
- **Six action tiles** — Scan Report, Records, Medication Reminders, Doctor Appointments,
  Medications, Pending Tests.
- **Smart Health Lens** — live camera: point at a medicine strip, get it identified and explained.
- **Background scan progress** — analysis continues if you navigate away.

Find Doctors / Labs / Hospitals (`DiscoveryScreen.kt`) are built but gated off in production
behind `isBackendReady` in `HomeScreen.kt`; the discovery backend isn't live yet.

## Architecture

```
Android app (Kotlin/Compose)          Backend (Express on AWS Lambda)        Data
─────────────────────────────         ───────────────────────────────        ────────────────
Room/SQLite + SQLCipher          ──►  /api/ai/*      AI proxy           ──►  Gemini (vision)
  all medical records, on-device      /api/auth/*    accounts, JWT           Sarvam (Indic OCR,
Compose UI, 11 languages              /api/translations  UI strings           translation)
On-device engines:                    /api/health-tips                   ──►  Neon Postgres
  DashboardEngine  trends                                                     accounts, ui_translations,
  PersonalizedTips health tips                                                health_tips, usage events
  UnitConverter    lab units
```

**No AI key ships in the APK.** All Gemini/Sarvam calls go through the backend proxy, which holds
the keys and meters usage. Users may optionally attach their own Gemini key (BYOK) to lift the
shared free-tier daily cap; it's encrypted at rest with AES-256-GCM.

**Medical records never leave the device** except when the user explicitly shares or exports
them. The backend stores accounts, translations, tip content and usage metering — not reports.

## Repo structure

```
android-app/    Kotlin/Compose Android app (com.healthdecoder.app)
backend/        Express API, deployed as a single AWS Lambda (see backend/DEPLOY.md)
mockup/         Interactive clickable prototype + design-token inspector
releases/       Hand-picked APK builds for direct download (see releases/README.md)
docs/           Screenshots, blog post, migration notes
```

The local-only cost dashboard (AWS/Gemini/Sarvam/Firebase spend) lives in its own repo:
[github.com/bhagwatshree/Health_Decoder_Admin](https://github.com/bhagwatshree/Health_Decoder_Admin).
A native iOS port is being scaffolded in
[HealthDecoder_Version2](https://github.com/bhagwatshree/HealthDecoder_Version2).

## Prerequisites

- Node.js 20+
- AWS CLI + AWS SAM CLI with credentials configured (`aws configure`) — to deploy the backend
- A [Neon](https://neon.tech) Postgres database (free tier is enough)
- Android Studio, or just JDK 17 + `gradlew`, for the app
- A Firebase project with Phone Authentication enabled, only if you want phone-OTP login

## Backend

### Local development

```bash
cd backend
npm install
cp .env.example .env   # fill in DATABASE_URL (or PG* vars), API keys, JWT_SECRET, etc.
node migrate.js        # creates/updates tables on your Postgres instance
npm run dev            # Express on http://localhost:3000
```

Every `.env` value is documented inline in `backend/.env.example`. At minimum:

| Variable | Purpose |
|---|---|
| `DATABASE_URL` (or `PG*`) | Postgres connection |
| `GEMINI_API_KEY` / `GEMINI_API_KEYS` | Vision extraction — [Google AI Studio](https://aistudio.google.com/apikey). The plural form is a pool; capacity scales with the number of keys. |
| `JWT_SECRET`, `ENCRYPTION_KEY` | Any long random strings |
| `SARVAM_API_KEY` | Indic OCR + translation — [Sarvam dashboard](https://dashboard.sarvam.ai). Optional; those features degrade gracefully without it. |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Only for phone-OTP auth |
| `FREE_TIER_DAILY_LIMIT` | Daily scan cap for users on the shared key pool |

### Deploying

The backend runs as one Lambda behind API Gateway via AWS SAM. First-time setup — Neon project,
AWS credentials, guided deploy — is in **[backend/DEPLOY.md](backend/DEPLOY.md)**.

```bash
cd backend
npm run deploy      # sam build && sam deploy
```

Pushes to `master` under `backend/**` also deploy via GitHub Actions
([`.github/workflows/backend-deploy.yml`](.github/workflows/backend-deploy.yml)).

After deploying, run `node migrate.js` against the same `DATABASE_URL` whenever the schema
changes. The printed `ApiUrl` stays stable across ordinary redeploys — it only changes if the
stack is torn down and recreated, in which case update `DEFAULT_SERVER_URL` in
`android-app/app/src/main/java/com/healthdecoder/app/network/NetworkModule.kt`.

Note Lambda's 4KB total environment-variable limit — see the comments in `backend/template.yaml`
before adding more configuration.

### Phone-OTP login (optional)

Phone signup/login verifies a Firebase Phone Auth ID token **server-side** — the client's claim
of a verified number is never trusted. To enable:

1. Firebase Console → Project Settings → Service Accounts → Generate new private key.
2. Paste the JSON as one line into `FIREBASE_SERVICE_ACCOUNT_JSON` in `.env` (local) and into the
   `FirebaseServiceAccountJson` deploy parameter (`backend/samconfig.toml`, gitignored — set it
   via `sam deploy --guided` once).
3. Check **Authentication → Settings → SMS region policy**. New Firebase projects allow *no*
   regions by default, which silently blocks every OTP SMS. Allow the countries you expect users in.

Leave it blank to skip phone auth; email/password works regardless.

### Google Sign-In & Gmail scanning (optional)

"Continue with Google" and "Link Google Account" (Gmail scanning for report attachments) both
need a Google Cloud OAuth setup — full walkthrough in
**[backend/GOOGLE_SIGNIN_SETUP.md](backend/GOOGLE_SIGNIN_SETUP.md)**. Skip it and both features
stay hidden; other login methods are unaffected.

Once linked (Settings → Email Report Scanner), the app looks for PDF report attachments in recent
mail (subject keywords + `has:attachment`, see `EmailScanWorker.kt`) via the Gmail API
(`GmailApiClient.kt`), or plain IMAP for the "Other" provider option:

- **Scan Now** checks the last 2 days on demand.
- A daily background check (default 7 PM, configurable) covers the prior day.
- Anything found appears as a "Found in email" card on the **Scan** screen — you review and tap
  **Analyze** per report. Nothing imports itself.
- **Clear Email Scan History** resets dedup tracking so reports are re-detected.

## Android app

1. Place a `google-services.json` from your Firebase project at
   `android-app/app/google-services.json`. Required for phone-OTP to work; the app degrades to
   email/password if it's a placeholder. Gitignored — per-developer, never committed.
2. Point the app at your backend: `DEFAULT_SERVER_URL` in
   `android-app/app/src/main/java/com/healthdecoder/app/network/NetworkModule.kt`. A user-set
   override in Settings wins over this default at runtime.
3. Build:

   ```bash
   cd android-app
   ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
   ```

   Or open the folder in Android Studio to run on a device.

### Languages

11 supported: English, Hindi, Marathi, Gujarati, Tamil, Telugu, Kannada, Bengali, Punjabi,
Malayalam, Odia. On first launch the app picks up the device's **active keyboard** language (not
the system display language — `DeviceLanguageDetector.kt`), falling back to English.

Three tiers, deliberately separate — see the header comment in `UiTranslations.kt` before adding
strings:

| Content | Path | Changing it |
|---|---|---|
| **UI chrome** — buttons, titles, empty states | `tr()` → `ui_translations` table, falling back to the bundled `UiTranslations.kt` maps | DB edit reaches every install without a release; new strings need a release |
| **Content that changes without a release** — health tips, canonical test names | `trDynamic()` → translated once at runtime via the backend's Sarvam proxy, then cached on-device (`DynamicTranslations.kt`) | Automatic |
| **AI output** — chat answers, report explanations | translated live in `MedicalEngine`/`LanguageUtil` | Automatic |

Strings carrying a runtime value are stored as positional templates (`"Check Now (%1$d)"`) and
rendered with `trFormat()`. Interpolating before the lookup produces a different string every
call and can never match a key.

The bundled translations are AI-generated and have **not** been reviewed by native speakers —
worth a review pass before a wide release.

### Scanning a report

- **Camera** — photograph a printed report or prescription, page by page.
- **From Device** — import an existing photo, PDF, or Word document.
- **Scan QR Code** — point at the QR printed on a lab report (`QrScannerScreen.kt`, on-device via
  CameraX + ML Kit). Most labs link that QR to the official digital copy, which the app downloads
  and imports through the normal pipeline. If it opens a portal page needing login instead, the
  app hands off to the browser so you can download and import via "From Device".

### Trends & lab units

Trends charts each test over time, computed entirely on-device (`DashboardEngine.kt`) — no server
round-trip. Readings from different labs are standardised to one unit per test so a single line
stays comparable: pick the system once in **Settings → Lab Units** (Indian/conventional by
default — mg/dL, g/dL — or International/SI — mmol/L, µmol/L), and reports printed in the other
unit are converted onto the chart (`UnitConverter.kt`) while the report screen still shows the
value exactly as printed. Each chart shades the healthy reference band, parsed from the report
and converted into the plotted unit.

### Adding data without spending API quota

Each scan is either **Analyze & Scan** (runs AI extraction) or **Upload Only (No Scan)**, which
stores files with zero API calls — useful for bulk-archiving old records. Upload-only reports get
an **Analyze Now** button on their detail screen to run AI later. A report's original file can
also be shared or downloaded from that screen.

### Fixing a mis-scanned patient or medicine name

Handwriting gets misread, so both correct in one place and cascade everywhere:

- **Settings → Fix / Merge Patient** merges one patient name into another across their reports,
  trends, reminders, intake logs and pending tests.
- Editing a medicine in the Medication Tracker (including its name) updates every report carrying
  it for that patient and re-keys its reminder schedule and intake logs, so nothing orphans.

### Backup vs. transferring records between phones

Two different mechanisms, both under **Settings**:

- **Full Backup & Restore** snapshots the whole encrypted database to one file for the *same*
  device, optionally auto-syncing to a Google Drive/OneDrive/Dropbox folder. Restore **replaces**
  this device's data. The file is not portable to another phone — it's encrypted with a
  per-device key. Optionally password-protect it; there is no recovery if that password is lost.
- **Transfer Records** produces a *portable* file carrying each report's full analysis — values,
  comparison, insights, cached detailed analysis — so importing it on another phone **merges** it
  in (deduped by report id) without re-running, or re-paying for, the AI. Exports a single
  patient or only what changed since last time (delta), and shares straight to WhatsApp, email,
  etc. See `ExportManager.kt`.

## Privacy & data handling

- **Medical records stay on the device.** Reports, images, values, reminders and family profiles
  live in the local encrypted database. The backend never receives or stores them.
- **Images are sent to the AI provider only during analysis**, through the backend proxy, to
  produce the extraction — they are not retained server-side.
- **An account is optional.** Everything works signed-out; sign-in only adds cross-device sync and
  a personal API key.
- **What the backend does store:** account records, UI translations, health-tip content, and
  per-call usage metering (provider, operation, tokens, estimated cost).
- **Account deletion** removes the account server-side and the local records on the device.

⚠️ **Before store submission:** this repo has no `PRIVACY.md` and no hosted privacy policy. Google
Play requires a published privacy policy URL for any app handling health data, plus a completed
Data Safety form. See the release checklist below.

## Security

- **On-device database encryption** — the local records database (Room/SQLite) is encrypted at
  rest with SQLCipher. The passphrase is a random 256-bit key generated on first launch and held
  in `EncryptedSharedPreferences` backed by the Android Keystore, hardware-backed where the device
  supports it (`SecureKeyManager.kt` / `LocalStore.kt`).
- **No AI keys in the APK** — all Gemini/Sarvam traffic goes through the backend proxy.
- **Passwords** are bcrypt-hashed (`backend/auth.js`); never stored or logged in plain text.
- **Sessions** are stateless JWTs signed with `JWT_SECRET`, sent as a bearer token and verified on
  every authenticated request. A stale or invalid token forces the app back to login.
- **Phone-OTP** re-verifies the Firebase ID token server-side (`verifyPhoneIdToken` in `auth.js`)
  before treating a number as confirmed.
- **User-supplied API keys** are encrypted at rest with AES-256-GCM, keyed by `ENCRYPTION_KEY`.
- **Biometric login** is opt-in and only unlocks a token already issued by a real password/OTP
  login — a local shortcut, not a separate auth mechanism.
- **Transport** — the deployed backend is reachable only over HTTPS; there is no plain-HTTP path.
- **Secrets stay out of git** — `backend/.env`, `backend/samconfig.toml` (real deploy parameters:
  DB URL, API keys, JWT/encryption secrets, Firebase service account, Google OAuth client secret)
  and `android-app/app/google-services.json` are all gitignored. Never commit these.

## Cost tracking

Every Gemini/Sarvam/Firebase call the backend makes is logged — provider, operation, tokens,
estimated cost — to the `api_usage_events` table (`backend/usageTracker.js`, `backend/pricing.js`).
Point the separate dashboard project at the same `DATABASE_URL` for spend by
provider/operation/model plus a CloudWatch-based AWS estimate.

## Release checklist

Known gaps between this repo and a Play Store submission:

- [ ] **Signing keystore** — none exists yet; `releases/` has no signed APK (`releases/README.md`).
- [ ] **Privacy policy** — write it, host it, and put the URL in the Play listing.
- [ ] **Data Safety form** — required for health data.
- [ ] **License** — the repo has no `LICENSE` file, so it is currently "all rights reserved" by
      default. Add one that matches your intent.
- [ ] **Screenshot refresh** — `docs/journey_*.png` predate the bottom-navigation refactor.
- [ ] **Native-speaker review** of the bundled translations in `UiTranslations.kt`.
- [ ] **Bump `versionCode`/`versionName`** in `android-app/app/build.gradle.kts` for each upload.

## License

No license file is present. Until one is added, all rights are reserved and the code carries no
grant of use, modification, or redistribution.
