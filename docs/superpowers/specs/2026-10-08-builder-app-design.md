# Analog Plus builder — design

Date: 2026-10-08
Status: DRAFT 2026-10-08, awaiting owner review.
Parents: `2026-10-02-analog-watchface-design.md` (the original face) and
`2026-10-08-configurable-watchface-design.md` (code-free configurable face, ON HOLD; it is the
fallback if the builder cannot ship, and its settings catalogue is an input here). Neither is edited.

## Goal

Let the user build the watch face they want, on their phone: onboarding on first install, then an
editor with many options and **free values** (any hex colour, numbers for sizes, widths, positions),
movable complications, and ring complications. The face is generated on the phone and pushed to the
watch. A default face (today's Analog Plus) is always available out of the box, and the normal
system customisation (picking complication providers in Galaxy Wearable) keeps working.

Success criteria:

1. Without the phone app, the watch app alone gives the user the default face, identical to today's.
2. With the phone app, an edit reaches the watch and is visible within seconds, with the face staying
   active (no re-activation prompt).
3. Any colour or number the user enters is reflected exactly in the rendered face.
4. Everything is generated and verified on-device by Google's own validator before it is pushed.
5. The default configuration renders pixel-for-pixel like the current face (image comparison test).

## Why a builder, and what the spikes proved

A watch face has no code, so it cannot read settings at runtime; the only inputs it can read are
fixed options declared inside it. Free values therefore mean generating a new face and replacing the
installed one. Wear OS 6 provides **Watch Face Push** for exactly this. Spikes on 2026-10-08
(throwaway code, outside the repo) established:

| Fact | Evidence |
|---|---|
| Watch Face Push works on the owner's Galaxy Watch (SM-L315F, Wear OS 6) | Real watch: list, add, activate and in-place update all succeeded; the pushed face rendered with working complications |
| One slot per marketplace app on Wear OS 6 | `remainingSlots` was 1, then 0 |
| Activation is one-shot per app | `setWatchFaceAsActive` works once; after removing the face, a second attempt failed with "maximum number of attempts ... reached". Reinstalling the watch app reset it on the emulator. |
| `updateWatchFace` into the same slot keeps the face active | Real watch and emulator |
| A pushed face must pass Google's push validator and carry its token | `validator-push-cli` and `validator-push-android` (both on Google Maven) |
| A pushed APK may contain only the manifest, `resources.arsc`, `res/**` and `META-INF/**` | A Gradle build adds an empty `classes.dex` and fails validation |
| A pushed face needs `res/xml/watch_face_info.xml` (preview) | Without it the system "could not parse" the face and activation failed with error code 1 |
| Package names must be `<marketplace app id>.watchfacepush.<name>`; the face is signed with a different key from the app; updates must use the same signer | Docs and spike |
| The validator runs on Android as a library | 10 of 10 checks on a phone emulator in about 2.5 s; needs the phone app minSdk 28 |
| Google's `pack` (Rust) builds, aligns and signs a face APK from a plain-text manifest and resource files, and runs on Android | Cross-compiled for arm64, built a valid face in 0.03 s on a phone emulator; validator 10 of 10; installed and activated on a Wear OS 6 emulator |
| New or changed images, including a recoloured second hand, work with `pack` | Second hand regenerated in the accent colour; rendered correctly |
| Updates need a higher `versionCode`; `versionCode` lives in the manifest | Spike |

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Architecture | Phone app (editor, generator) + watch app (installer, default face) + generated faces pushed with Watch Face Push | Only route to free values and onboarding |
| Minimum watch OS | **Wear OS 6** | Push API requires it; owner decision 2026-10-08 |
| Phone | Android 9 (API 28) and newer | Validator library minimum; Wear OS companion is Android-centred |
| Face packaging | **`pack` as a native library** (JNI), not template patching | Owner decision: adding any file or resource is possible; Android blocks running a downloaded executable in new apps, so the CLI form is spike-only. Google ships a Java example (`pack-from-java`). |
| Signing key | Generated once on the phone, persisted, PEM form for `pack` | `pack` needs a PEM and its random key is slow; updates need the same signer |
| Backgrounds incl. user photo | Photo is picked on the phone, resized and **embedded in the face APK** | No dependence on WFF v4 photo settings or the system photo picker; size kept small for Bluetooth transfer |
| Second hand and other raster parts | Regenerated on the phone in the chosen colour | Owner decision: the red second hand next to a custom accent looks wrong |
| Complication provider choice | Stays in the system editor (Galaxy Wearable) | Providers cannot be chosen from a phone app |
| Fixed options | The face may still declare some fixed options for the system editor; undecided which, see open items | "Normal customisations that all watch faces have" |

## Architecture

```
phone app (Kotlin, Android 9+)             watch app (Kotlin, Wear OS 6)
  onboarding, editor, live preview            default face (bundled APK, validated token)
  settings model (typed values)               Watch Face Push client
  face generator (XML + images)               permission prompt, one-time activation
  pack (native lib) -> signed APK             receives APK + token, updateWatchFace
  validator (library) -> token                reports result back
  signing key (persisted)
              \                                  /
               Wear Data Layer (file + messages)
```

- **Shared module** (`facegen`, Kotlin): settings model, XML generator, image renderers. Runs on the
  JVM for unit tests and on Android. It replaces the Python generators over time; until it
  reproduces the default face exactly, the Python output is the reference for the parity test.
- **Phone app and watch app share one application id** (`com.seyedjafari.analogplus`), required for
  Data Layer addressing and for the `.watchfacepush.` package rule. The face id is
  `com.seyedjafari.analogplus.watchfacepush.analog`.
- **Version codes:** monotonic counter kept on the phone; the face manifest `versionCode` is set per
  build. The watch rejects a package whose version is not higher than the installed one.
- **Never remove the pushed face.** Only update. Removal forfeits activation.
- **First run (watch):** install the bundled default face, ask for the active-face permission,
  activate it once. Installing a default does not activate it by itself. If the user declines the
  permission (one allowed refusal), the app explains how to pick the face manually.
- **Edit flow:** phone builds, signs and validates; sends the APK and token over the Data Layer; the
  watch calls `updateWatchFace`; the watch reports success or the failure reason; the phone shows it.
- **Key loss:** a face signed by a lost key cannot be updated. Recovery design (backup of the key,
  or a clearly explained reset) is an open item.

## Settings model

Typed values, not presets: colour (any ARGB), integer or decimal in a range, choice, toggle, image.
Each setting has a default that reproduces today's face. The catalogue from the on-hold spec carries
over, with free values where it listed fixed choices:

- **Colour:** accent (drives second hand, centre disc, battery icon), hand, tick, numeral and
  complication-text colours.
- **Background:** solid, gradient, bundled textures, user photo, with a dim amount; hidden in ambient.
- **Dial:** tick width, tick length, minute ticks on or off, 5-minute marker shape, numerals (all,
  quarters, off), numeral size.
- **Hands and cap:** hand colour and size, inlay, second hand (sweep, tick, hidden), centre-cap style.
- **Complications:** text colour and size, titles and icons on or off, progress arcs.
- **Ambient:** dimness, complications visible or hidden.
- **Presets:** saved named designs on the phone, with a few built in.

## Complication layouts

A layout is a set of slots; the generator emits one `ComplicationSlot` per slot with a bounding shape.

1. **Movable slots:** 4 or 6 slots placed anywhere inside the dial, dragged in the editor. Each slot
   has a size, a bounding shape (box, oval, rounded box) and an optional background (any colour or
   shape, or an image) drawn behind it. The editor prevents overlaps with each other and with the
   hand-centre area, and warns about overlap with numerals.
2. **Full ring:** one slot following the dial edge.
3. **Two half rings.** 4. **Three third rings.** 5. **Four quarter rings.**
   Ring slots use the format's arc bounding shape (`BoundingArc`: centre, size, start and end
   angle, thickness). For rings the user can set colour, track colour, thickness, gaps between
   segments, round or flat ends, gradient, start angle and direction.
- **Data:** arcs suit range-style providers (ranged value, goal progress); the arc fills to the
  value. Text and icon providers cannot fill an arc; such an arc shows its text at the arc midpoint,
  or the picker offers only range-capable providers. Multi-colour segments use the weighted-elements
  type. Ranged values must be clamped to min and max before drawing (out-of-range values crash).
- **Cap:** a face may have at most 8 complication slots in total (Google's slot reference); ring
  segments count. The editor enforces it, for example four quarter rings plus up to four inner slots.
- Slot identifiers are stable across rebuilds so the system can keep the user's provider choices.
  Whether choices survive a rebuild with a changed layout is unproven (spike).

## Compatibility

- **Works on:** Wear OS 6 watches. Verified: the owner's Galaxy Watch and Google's Wear OS 6
  emulator. Reported as receiving or shipping with Wear OS 6 (October 2025 reports): Pixel Watch 2,
  3 and 4; Galaxy Watch 4 to 8, Ultra and FE; Xiaomi Watch 5. No evidence found for TicWatch, Fossil
  or OnePlus Watch 2.
- **Does not work on:** Wear OS 5 and older (no push API), watches not running Wear OS, iPhone.
- **Fallback for older watches:** the on-hold code-free face, if ever published as a normal face.
- Device coverage should be re-checked on current sources before any store listing.

## Testing

1. **No devices (CI):** generator tests (settings produce expected XML and images), default-parity
   image test, `pack` output passes the validator, and the transport behind an interface with an
   in-memory fake for the sync logic (queueing, retry, version ordering).
2. **Single device, scripted over `adb`:** phone emulator builds, signs and validates; watch emulator
   pushes, activates and updates in place; scripts assert on logs and on pixels (for example the
   disc colour at the centre, the second hand colour).
3. **Paired phone and watch:** real devices (the owner's phone and Galaxy Watch). An edit on the
   phone appears on the watch, the face stays active, the screenshot matches. Emulator pairing needs
   Google sign-in on both emulators, so it cannot run in CI.
4. **Failure cases:** watch disconnected mid-transfer (queue and retry), watch app missing, validator
   failure, update during ambient mode, a transfer of about 170 KB or more over Bluetooth, permission
   refused, phone reinstalled (key lost), slot taken by another face, version not higher.

## Resource budgets

Google's Wear OS quality guidance: 100 MB of assets in interactive mode and 10 MB in ambient, and
the APK travels over Bluetooth, so it is kept small (compress images, resize photos to the screen
size, limit textures). Each plan checks the generated face against both.

## Plan breakdown

0. **Spikes (throwaway):**
   - S1 Data Layer transport on the owner's real phone and watch (file transfer plus control messages).
   - S2 Arc prototype: a ranged-value provider drawn as an arc sweep with `BoundingArc`, including
     full, half, third and quarter layouts.
   - S3 Do the user's complication choices survive a face update, with the same layout and with a
     changed one.
   - S4 `pack` as a JNI library inside a real Android app (the `pack-from-java` example).
   - S5 Data Layer addressing requirements (shared application id, signing) in practice.
1. **Foundation:** project structure (`phone`, `watch`, `facegen`, native module), persistent signing
   key, `pack` JNI, validator integration, Kotlin generator reproducing the default face with the
   parity test.
2. **Watch app:** bundled default face, first-run install and one-time activation, update path,
   result reporting.
3. **Sync:** Data Layer transport, queueing, retry, version ordering.
4. **Editor and onboarding:** phone UI, live preview, saved designs.
5. **Appearance settings:** colours, hands and cap, dial, numerals, backgrounds including photo.
6. **Complication layouts:** movable slots and backgrounds, then rings.
7. **Complication styling, ambient options, presets.**

Store readiness is a separate track (below).

## Store readiness (separate track)

Signing and release workflows exist for the current face. Still needed: store graphics and
screenshots, privacy policy, Wear OS form-factor setup in Play Console, a decision on how distinct
the default look is from Samsung's stock analog face (the dial is measured from it; clones of
official faces get reported, no case involving a generic analog face was found), and a check of
Google's Watch Face Push policy page and current account and testing requirements for new developers.
The Play policies for apps that install watch faces must be read before the first upload.

## Changes to earlier specs

| Earlier decision | Now |
|---|---|
| Non-goal: Play Store listing and companion app | Both are goals |
| WFF v3, minimum Wear OS 5.1 | Minimum Wear OS 6; WFF version chosen per feature (photo handling no longer needs v4 settings) |
| Code-free, `hasCode="false"` for the whole project | Only the generated faces are code-free; the phone and watch apps are normal apps |
| Python generators | Kept as the parity reference, replaced by the Kotlin `facegen` module |

## Open items

- Which fixed options, if any, the face declares for the system editor.
- Key backup and recovery after a phone reinstall.
- Whether to ship saved designs sync across phones (not in scope now).
- Monetisation (free first is the working assumption).
- Whether the default face is bundled as a prebuilt APK or generated on first run from the same
  generator (the spec prefers bundling, so the face works if generation ever fails).

## Risks

| Risk | Handling |
|---|---|
| Data Layer cannot move a file of this size reliably | Spike S1; compress; fall back to chunked messages |
| Complication choices reset on update | Spike S3; stable slot ids; if unavoidable, warn the user and re-prompt |
| `pack` as a library is harder than the CLI | Spike S4 before committing; the CLI proved the build itself |
| One-shot activation is consumed by a failed first run | Treat permission and activation as an explicit onboarding step; never remove the face |
| Another face is activated by the user and the app can no longer set itself active | Explain manual selection; updates continue to apply to the installed face |
| Audience limited to Wear OS 6 | Owner accepted; recheck coverage before listing |
| Look-alike of Samsung's face on the store | Decide before listing |
