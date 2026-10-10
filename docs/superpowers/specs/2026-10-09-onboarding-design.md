# Animated onboarding — design

Parent: `2026-10-08-builder-app-design.md`. Depends on `2026-10-09-app-shell-design.md` (theme, components,
navigation). The last step uses `2026-10-09-accounts-sync-design.md`; until that ships the step is hidden.
Status: DRAFT 2026-10-09, awaiting owner review. Implemented 2026-10-10 (branch `wt/onboarding`).

## Intent (agreed with the owner)

First run teaches what the app does and gets the user to a face on their watch. Owner decisions: the screens are
**welcome animation, pair watch and install watch app, permission explainer, pick a starting template**, then
**optional sign-in with a recovery passphrase** as the last step. Animations use **Lottie**.

Success: a new user reaches "face on the watch" without reading docs; every step can be skipped or resumed; the
onboarding never blocks an existing user and never shows twice unless asked.

## Flow

Five pages in a paged flow with a progress indicator ("Step N of M"), Skip on every page, Back works. There is no swipe:
the user moves with Back, Skip and the primary button only (and the system Back button), and pages change with a
shared-axis slide (a plain change when animations are off). System Back goes one page back; on the first page a first run leaves the app, while a run restarted from Account's "Run setup again" counts Back as Skip and returns to the app (the controller keeps how the run started, `OnboardingOrigin`, in memory only: after process death a run is a first run). Only the visible page is composed, so the pairing check
starts when the connect page is reached and a neighbouring page never plays its animation. Until accounts ship the flow
has four pages (the fifth, backup, is hidden).

1. **Welcome.** Lottie loop: a watch face morphing between designs (colours, ring layout, complication slots).
   Headline and one line. Primary: Get started.
2. **Pair your watch.** Live checklist, two rows, each with a status (`CHECKING`, `OK`, `ACTION_NEEDED`, `UNKNOWN`):
   watch paired (Wear Data Layer node found) and Analog Plus watch app installed on that watch (capability
   advertised by the watch app). The phone cannot read the watch's Wear OS version, so there is no Wear OS row: an
   informational line says "Needs a Wear OS 6 watch." The check is the app shell's `WatchStatusChecker` (the same one
   the Send screen uses). Lottie for the "searching" and "found" states. Re-checks
   automatically when the app resumes. `ACTION_NEEDED` rows show the next step, in the same words as the Send screen (one string
   each, shared): no watch found, open the Galaxy Wearable or Wear OS app on the phone and make sure the watch is paired;
   watch found but the app missing, install Analog Plus on the watch. The advice is generic today: the Play Store link
   (and a debug-install hint for dev builds) arrives with the store listing. A user with no
   watch can continue (the preview and editor work without one); the Send screen then shows the same status.
3. **Permission explainer.** Explains, before the watch asks, that Analog Plus needs "set as active watch face" once,
   and that denying it is recoverable (long-press the face and pick Analog Plus). Lottie of the watch prompt
   being allowed. No permission request is made from the phone; this page only prepares the user.
4. **Pick a starting template.** Grid of Classic, Midnight, Neon, Minimal (live-rendered previews). Choosing one sets
   the current design (the same confirm-before-replace rule applies if the user already has edits).
5. **Backup and sync (optional).** Shown only when accounts ship. "Sign in with Google" or "Not now"; after sign-in,
   set the recovery passphrase (spec 3). Skippable; always reachable later from Account.

Finish goes to Design with the chosen template loaded. If the watch was found, a one-time tip points to Send: it is
shown once after Finish **or Skip**, with **Open Send** and **Got it**, and ends when either is used or when the Send destination is
opened by any other route. In a short wide window (phone landscape) the animation sits beside the text and Back,
progress, Skip and the primary button share one row; every page scrolls instead of clipping, also at font scale 2.
Account has a **Run setup again** card that restarts the flow from the first page after Completed or Skipped.

## State

- `OnboardingState`: `NOT_STARTED`, `IN_PROGRESS(page)`, `COMPLETED`, `SKIPPED` (a sealed type in code, because
  `IN_PROGRESS` carries a page; stored as one string such as `IN_PROGRESS:PERMISSION`). Stored in `Store`
  (SharedPreferences, same durable-write rules). Not a boolean: a skipped onboarding and a completed one are
  different (Account offers "Run setup again" for both).
- Shown when the state is `NOT_STARTED` **and** the app has no saved design history (an update from the POC with
  existing designs goes straight to the editor and is recorded as `SKIPPED`). The decision is made and stored on the
  first launch, because the app autosaves the default design right after it.
- Process death mid-onboarding resumes at the stored page.

## Lottie

- Library: `com.airbnb.android:lottie-compose` (pinned to 6.7.1, the version in `phone/build.gradle.kts`). Compose `LottieAnimation` + `rememberLottieComposition`.
- **Assets** (4 files in `phone/src/main/res/raw/`): `onboarding_welcome.json`, `onboarding_searching.json`,
  `onboarding_found.json`, `onboarding_permission.json`. Default (no licensing questions): **authored by us**,
  generated by a script in `tools/` from the watch-face geometry (hands sweeping, ring segments filling, slots
  appearing), exported as Lottie JSON. The owner may instead supply licensed LottieFiles assets; they drop in under the
  same names, since the code only references the raw resources.
- Theme colours are applied through Lottie dynamic properties so light and dark both work; no colour is baked into a file.
  Each colour group of a file is named after a theme role (accent = primary, accent2 = tertiary, accent3 = secondary,
  ink = onSurface, track = outline, onaccent = onPrimary). The welcome animation morphs its colours as well as its layout: it
  cross-fades four copies of the face drawn with other roles (Classic, Midnight, Neon, Minimal), one per quarter of the loop,
  so it stays inside the theme and reads against the page in light and dark.
- Each animation file is capped (target under 100 KB), checked by a test.
- Reduced motion: when animations are disabled system-wide, show the last frame as a static image (progress 1f), no loop.
  The page decides this (it follows the shell's animation scale); Lottie's own read of the system animator scale is
  switched off so the two cannot disagree.
- The welcome animation pauses when the page is not visible and when the app is in the background.

## Code structure

`ui/onboarding/`: `OnboardingScreen` (the paged flow: an `AnimatedContent` over the current page, no pager), one composable per page. `WatchStatusChecker`, `WatchStatus` and
`CheckStatus` already exist in `ui/send` (app shell plan, Task 7; the real one wraps the Wear `NodeClient` and
`CapabilityClient`, a fake drives tests and previews) and are reused, not duplicated. `OnboardingState`,
`OnboardingController` and the `Store` methods are logic and live in the root package. Navigation is state-based
(`AppNav`, no Navigation Compose): `AppShell` shows onboarding as a gate before `AppNav` while the state is
`NOT_STARTED` or `IN_PROGRESS`.

## Testing

- JVM: `OnboardingState` transitions and persistence (resume page, update-from-POC becomes `SKIPPED`); the mapping of
  `WatchStatus` to row texts and advice. The mapping of node/capability results to `WatchStatus` is tested by the app
  shell plan (`WatchStatusTest`).
- Instrumented: full pass through all pages, Skip on each page, Back, rotation and process-death resume, template
  choice sets the design, "no watch" path, reduced-motion path shows static frames.
- Asset test: all four raw files parse as Lottie compositions and stay under the size cap.
- Real devices: the pairing page is to be verified on the owner's paired phone and watch (the Data Layer has never run on
  paired devices, see `CLAUDE.md`; emulators cannot be paired). This is the first real exercise of that path, so
  allow time for findings. **Not run yet** (skipped by the owner when onboarding was built: no devices attached); the
  checklist is group 0 in `docs/poc/README.md`.

## Out of scope

Marketing carousel on the Play listing, in-app tutorial overlays on the editor, localisation of copy beyond moving
strings to resources.

## Open items

Whether the Lottie assets are ours or licensed (default: ours; no owner action needed unless they want to supply
their own).
