# HealthDecoder iOS App

Native Swift & SwiftUI port of the Android app, aiming for full feature parity. It's being built
in phases — see `Phase 1 status` below for what currently works.

**Architecture**: like Android, all patient data lives only on this device, in an encrypted local
SQLite database (SQLCipher, key stored in the iOS Keychain). The AWS backend (`../backend/`) is
used only for account login/signup and issuing a Gemini/Sarvam API key + free-tier quota — the
actual AI scan/chat calls go straight from the phone to Google/Sarvam, never through the backend.

## Prerequisites

- A Mac with the full **Xcode** app (not just Command Line Tools) — Xcode 15+, iOS 17 SDK.
- [XcodeGen](https://github.com/yonaskolb/XcodeGen): `brew install xcodegen`

This project's `.xcodeproj` is **not committed** — it's generated from `project.yml` so the
project structure stays reviewable as text and regenerates cleanly if files are added/removed.

## Getting started

```bash
cd ios-app
cp Secrets.xcconfig.example Secrets.xcconfig   # then fill in GEMINI_API_KEY at minimum
xcodegen generate
open HealthDecoder.xcodeproj
```

Build (⌘B) and run (⌘R) on a Simulator or a connected device.

- `Secrets.xcconfig` (gitignored, per-machine) needs at least `GEMINI_API_KEY` — get a free one
  at [aistudio.google.com/apikey](https://aistudio.google.com/apikey) — or the scan pipeline
  can't extract anything. This mirrors Android's `local.properties` requirement.
- Re-run `xcodegen generate` any time `project.yml` changes, or a Swift file is added/removed
  under `HealthDecoder/` (XcodeGen picks up new files by folder convention — no manual
  "Add Files to project" step needed, unlike a hand-maintained `.xcodeproj`).

## Phase 1 status (current)

Implemented and expected to work end-to-end once built:
- Email/password signup & login against the same backend Android uses.
- Encrypted local database (SQLCipher via Keychain-stored passphrase) for reports, pending
  tests, and medication logs — same schema shape as Android's Room database.
- Home dashboard tile grid (Scan, Records fully wired; other tiles are placeholders until their
  phase lands).
- Scan pipeline: camera capture or photo-library import → on-device Gemini vision OCR (same
  model/endpoint/retry logic as Android's `GeminiClient.kt`) → saved as an encrypted local
  report, with duplicate-scan detection via page-hash comparison.
- Records list and Report Detail (view/edit/delete, "Analyze Now" for upload-only reports).

**Deliberately not yet implemented** (later phases): medicine/appointment reminders & local
notifications, Medication Tracker, Pending Tests, Trends charts, AI Chat, QR scanner, Smart
Health Lens, Compare, Doctor Brief, backup/cloud-sync/portable export, Gmail report scanning,
biometric login, phone-OTP and Google sign-in (no Firebase SPM dependency is linked yet — those
buttons are hidden, not broken), and non-English localization.

*Note: this app cannot be built or tested from the environment that generated it (no full Xcode
installed there) — expect to fix a handful of compile errors on first build, most likely around
the exact SPM product name Xcode assigns to the SQLCipher package (see the comment at the top of
`HealthDecoder/Local/Database.swift`).*
