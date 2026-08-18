# Implementation Plan — Health Tips, Ad Placeholder & MedLife-Style UI Refactor

Branch: `feature/health-tips-ui-refactor` (this repo, checked out at `D:\Medical_Enhanced`)
Base: `master` @ `bfe09b4` ("Fix Server Settings crash and remove unused Advertising ID permission")

This supersedes the earlier `implementation_plan.md` draft (AI-generated mockup plan). That draft
is kept for its color/asset references only — several of its assumptions were revised after review;
see "Changes from the original draft" at the bottom.

---

## Scope (agreed)

1. Home screen header restructure — logo + profile icon, `Welcome` / `Hello, [Name] ▼` greeting.
2. Health Tip card — rotating tips, **not** real ads (see "Ads: explicitly deferred" below).
3. 6-tile action grid — same 6 tiles, same colors as today. No visual change here.
4. 6-tab bottom navigation bar — Home / Chat / Trends / Compare / Brief / Settings.
5. Dark mode: tile/tab colors preserved (not flattened to uniform dark cards).
6. Family-profile dropdown: no "Everyone" option, ever.
7. First-time sign-in discoverability popup.
8. Same visual language applied consistently across all screens, not just Home.

## Ads: explicitly deferred, not in this branch

Real AdMob integration is **out of scope** for this branch. Reasons, from the plan review:
- The app currently declares **Ads: No** and **Advertising ID: No** to Play Console (the latter
  required actually removing a permission — see `master`'s `bfe09b4` commit). Real ads reverse
  both declarations and require new Data Safety disclosures, a consent-management flow (UMP SDK),
  and a real (not sample/placeholder) AdMob account — none of which exist yet.
- The original draft's ad unit IDs (`ca-app-pub-3940256099942544/...`) are Google's public
  **sample/test IDs**, not a real account's IDs.
- Redoing those declarations while the first Play Console review is in flight risks delaying it.

What ships instead: a **Health Tip card** — same visual slot, rotating local content, no ad SDK,
no advertising ID, no new Play Console work. If/when real ads are wanted later, that's a separate
branch with its own AdMob account setup and a fresh policy-declaration pass.

---

## Current state (read from `HomeScreen.kt` on this branch before any changes)

- Header today: `IconButton` (account) + `IconButton` (chat) as `navigationIcon`; title is a
  clickable `Row` showing `"${profile.avatarEmoji} ${profile.name}"` or `"👨‍👩‍👧 Everyone"`,
  opening a `DropdownMenu` with an **"Everyone"** item, one item per `FamilyProfile`, and a
  "Manage / edit family" item. `actions` holds Refresh, Compare, and the language picker icon.
- State: `profiles: List<FamilyProfile>`, `selectedProfile: FamilyProfile?` (null = Everyone today),
  loaded in a `LaunchedEffect(familyReload)` from `LocalRepository.familyMembers(context)` and
  `AppSettings.getActivePatient(context)`.
- Grid: 6 real tiles today (`Scan Report`, `Records`, `Medication Reminders`, `Doctor Appointments`,
  `Medications`, `Pending Tests`) — colors already match the approved mockup exactly, e.g.
  `Scan Report` = `containerColor 0xFFE8F5E9` / `contentColor 0xFF2E7D32`. Three more tiles
  (`Find Doctors/Labs/Hospitals`) exist behind an `isBackendReady = false` flag — permanently
  hidden today, unaffected by this work. `Trends`, `Smart Health Lens`, `Doctor Brief` are also in
  the `actions` list but rendered in the same grid — **these need to move**, since the new design
  puts `Trends` on the bottom bar and doesn't have room for `Smart Health Lens` in a 6-tile grid.
- No bottom navigation bar exists today — `Chat` is a floating action button; `Compare`, `Trends`,
  `Doctor Brief`, `Smart Health Lens`, `Account` are all reached via top-bar icons or grid tiles.
- No display-name source without a network call (`AccountScreen.kt` fetches `acc.firstName` /
  `acc.lastName` live from the backend; nothing is cached locally). Confirms the "Welcome" fallback
  below is the right call rather than trying to show a cached account name.

---

## 1. Header: `Welcome` / `Hello, Name ▼`, no "Everyone"

**Logic** (agreed in review — two states only, not three):
- `profiles.isEmpty()` → show **"Welcome"**, no dropdown arrow (nothing to switch between yet).
  Tapping it opens the "Add family member" flow directly (reuses `FamilyManagerDialog`).
- `profiles.isNotEmpty()` → show **"Hello, [selectedProfile.name] ▼"**, defaulting to the first
  added profile if `selectedProfile` is null (never "Everyone" — see below).
- Dropdown menu (when profiles exist): lists only real `FamilyProfile` entries + "Add family
  member" action at the bottom. **No "Everyone" `DropdownMenuItem` — remove it entirely.**
- `AppSettings.setActivePatient(context, null)` (the "Everyone" write path) is no longer reachable
  from this screen. `selectedProfile` should never be null once `profiles` is non-empty — default
  to `profiles.first()` (or the persisted `getActivePatient` if it still matches a real profile).

**Downstream effect to check during implementation:** anything else that currently reads
`getActivePatient() == null` as "show everyone's data" (worth a repo-wide grep before touching
`AppSettings`) needs to keep working for existing installs that currently have `null` persisted —
treat `null` as "use `profiles.first()`" rather than assuming a fresh migration.

**Top bar layout change:**
- Left: `HD` logo (`ic_health_decoder_logo.xml`, already exists) + "Health Decoder" wordmark.
- Right: profile/account icon button (`Icons.Default.AccountCircle` → `onNavigateToAccount`,
  same target as today's left-side account icon, just moved).
- Chat icon (today's second left icon) — moves to the bottom nav's Chat tab instead of the top bar,
  since Chat becomes a first-class nav destination (see §3).
- **Voice Search (decided):** drop the Home screen's `ExtendedFloatingActionButton` entirely — fold
  Voice Search into `ChatScreen.kt` itself (e.g. a mic icon in the chat input row/toolbar) now that
  Chat is a first-class bottom-nav destination rather than something reached via a Home FAB.
- **Refresh (decided):** moves next to the patient name/greeting in the header — e.g. a small icon
  right after `Hello, [Name] ▼` (or after `Welcome`) — rather than living in the top bar's `actions`
  slot on the far right. Reads naturally as "refresh this person's data" since it's now anchored to
  the name it refreshes.
- Compare icon: moves to the bottom nav (see §3).
- Language picker icon: stays in the top bar `actions`.

---

## 2. Health Tip card

Renamed from the draft's "SPONSORED HEALTH INSIGHT" — that wording implies paid content, which
would contradict the Ads: No declaration. Use **"HEALTH TIP"** instead.

- New file: `ui/HealthTipCard.kt` (draft called it `HealthTipAndAdWidget.kt`; dropping "Ad" from
  the name since there's no ad path in this branch).
- Local, hardcoded rotation of tip strings (all wrapped in `tr()` for translation, same pattern as
  everything else in the app) — e.g. hydration before blood draws, taking meds with/without food,
  keeping a symptom log before a doctor visit. Reasonable starting set: 8–10 tips, rotate on a
  timer (`LaunchedEffect` + `delay`, e.g. every 15s) or on each Home screen visit — pick whichever
  reads less jarring during implementation.
- Visual: same card slot/position as the mockup (`HEALTH TIP` label, bold headline, description,
  "Learn More" button) — "Learn More" can open a short detail sheet/dialog with the full tip text,
  or simply be dropped if there's nothing more to show for a given tip (decide per-tip during
  implementation; don't ship a dead-end button).
- Placed directly below the header, above the action grid — matches the mockup position.

---

## 3. 6-tile grid + 6-tab bottom nav

**Grid** — same 6 real tiles as today, **unchanged colors**:

| Tile | Container | Content |
|---|---|---|
| Scan Report | `0xFFE8F5E9` | `0xFF2E7D32` |
| Records | `0xFFECEFF1` | `0xFF455A64` |
| Medication Reminders | `0xFFFFF3E0` | `0xFFE65100` |
| Doctor Appointments | `0xFFE8EAF6` | `0xFF283593` |
| Medications | `0xFFF3E5F5` | `0xFF6A1B9A` |
| Pending Tests | `0xFFFFF9C4` | `0xFFC62828` |

`Trends`, `Smart Health Lens`, `Doctor Brief` come **out** of the grid (Trends → bottom tab;
Doctor Brief → bottom tab).

**Smart Health Lens placement (decided):** not a floating overlay icon (would compete with the
Voice Search FAB for space and overlap scrolling content) and not a 7th grid tile (breaks the
6-tile rule). Instead, a slim tappable banner/row — visually distinct from the square tiles,
e.g. `🔬 Try Smart Health Lens — live camera scan` — placed directly below the Health Tip card
(or below the grid; pick whichever reads better once both are actually laid out) so it sits near
Scan Report conceptually (capture-then-analyze vs. live-analyze) without taking a full tile slot.

**Bottom nav** — new file `ui/components/AppBottomNavBar.kt`, 6 tabs:

| Tab | Target | Icon (existing Material icon, not custom art) |
|---|---|---|
| Home | `HomeScreen` | `Icons.Default.Home` |
| Chat | `ChatScreen` | `Icons.AutoMirrored.Filled.Chat` |
| Trends | `TrendsScreen` | `Icons.AutoMirrored.Filled.ShowChart` |
| Compare | `CompareScreen` | `Icons.Default.Balance` or `Icons.AutoMirrored.Filled.CompareArrows` |
| Brief | `DoctorBriefScreen` | `Icons.AutoMirrored.Filled.Article` |
| Settings | `AccountScreen` | `Icons.Default.Settings` |

Use **monochrome Material icons with a single accent color for the active tab** (the app's
existing teal, seen throughout `AccountScreen.kt`), not the colorful multi-hue icon-per-tab style
in the mockup — that reads as visually busier than the rest of the app's Material 3 language and
doesn't match any existing screen. This is a deliberate deviation from the mockup, flagged during
review, not an oversight.

**Dark mode — the one thing to actively fix vs. the mockup:** the "final" mockup variant flattened
every tile to an identical dark card, losing the color cues light mode has. Keep each tile's
`containerColor`/`contentColor` pair in dark mode too, just via `MaterialTheme`-aware variants
(e.g. blend the existing hue into the dark surface rather than dropping it) rather than reusing
one neutral dark card for all 6. Same principle for the bottom nav — active-tab accent should stay
visible/distinct in dark mode, not just barely-there gray-on-black.

Wire `AppBottomNavBar` into every screen's `Scaffold(bottomBar = ...)` that's one of the 6
destinations — not just Home — so navigation is consistent app-wide (this is the "apply to all
screens" part of the ask).

---

## 4. First-time sign-in discoverability popup

Problem being solved: a first-time user doesn't see where to sign in, since sign-in is optional
and the app is fully usable without it — there's no prominent "Sign In" affordance by default.

Proposed approach (for review during implementation, not fully speced here since no mockup covers
it): a one-time, dismissible prompt shown on first Home screen visit for a user who is (a) not
signed in and (b) hasn't dismissed it before — pointing at the profile icon in the top-right corner
("Tap here to sign in and sync across devices" or similar), using a lightweight tooltip/popup
rather than a blocking dialog. Persist "seen" state the same way `AppSettings.isOnboardingSeen` /
`setOnboardingSeen` already works for the onboarding carousel, so it only shows once. Exact
copy/visual treatment to be finalized during implementation — flagging the mechanism, not
prescribing pixel-level design here.

---

## Files touched (expected)

- `ui/HomeScreen.kt` — header, grid trim, tip card slot, bottom nav wiring, popup.
- `ui/HealthTipCard.kt` — new.
- `ui/components/AppBottomNavBar.kt` — new.
- `ui/ChatScreen.kt`, `ui/TrendsScreen.kt`, `ui/CompareScreen.kt`, `ui/DoctorBriefScreen.kt`,
  `ui/AccountScreen.kt` — add `AppBottomNavBar` to each `Scaffold`.
- `local/AppSettings.kt` — new key for "sign-in popup seen" (mirrors existing onboarding-seen
  pattern), and confirm the `getActivePatient() == null` fallback behavior noted in §1.
- Possibly `Navigation.kt` if any nav-graph wiring needs to change for the new bottom-nav-driven
  flow — check when wiring `AppBottomNavBar` into each screen.

## Verification plan

- `./gradlew.bat :app:compileDebugKotlin` after each screen is touched, not just at the end.
- Device-check both light and dark mode on Home specifically for the dark-mode tile-color fix.
- Confirm the profile dropdown never renders an "Everyone" item, on a fresh install (zero
  profiles) and after adding one/multiple profiles.
- Confirm `Welcome` shows correctly for a fresh install with zero profiles, and that adding the
  first profile flips it to `Hello, [Name]` without needing an app restart.
- `./gradlew.bat app:assembleRelease` + `app:bundleRelease` at the end, matching the draft's
  original verification step — confirm zero R8/compile issues before this is considered ready to
  merge back to `master`.

---

## Changes from the original `implementation_plan.md` draft

- Real AdMob integration removed from scope entirely (policy conflict with current Play Console
  declarations) — replaced with a local Health Tip card, no ad SDK.
- "SPONSORED HEALTH INSIGHT" → "HEALTH TIP" (accuracy — nothing is actually sponsored).
- Resolved the draft's internal 5-tab-vs-6-tab inconsistency (two contradictory mockups existed) —
  going with 6 grid tiles + 6 bottom tabs, per your explicit confirmation.
- "Everyone" dropdown option removed entirely, with an implicit-profile-free two-state header
  (`Welcome` / `Hello, Name`) instead of the mockup's always-shown `Hello, Sarah J.`.
- Bottom nav icons: monochrome + single accent color instead of the mockup's colorful per-tab
  icons, to match the app's existing Material 3 visual language.
- Dark mode explicitly required to preserve per-tile color distinction — the "final" mockup
  variant's flattened dark cards are not being carried over as-is.
- Added the first-time sign-in popup, which wasn't in the original draft at all.
- Smart Health Lens has no assigned home in the new layout — explicitly flagged as unresolved
  rather than silently dropped.
