# Builder POC: phone editor + Watch Face Push

A phone app edits a watch face (free colours and numbers, movable complication slots, ring complications), builds and
signs it on the phone, and sends it to the watch. A watch app installs it with Watch Face Push. Wear OS 6 only
(Galaxy Watch SM-L315F is the target). Design: `docs/superpowers/specs/2026-10-08-builder-app-design.md`.

Both apps share the application id `com.seyedjafari.analogplus`. The face they push is
`com.seyedjafari.analogplus.watchfacepush.analog`. The existing face (`com.seyedjafari.analogwatchface`) is untouched.

## Install on the real devices

```bash
./gradlew :phone:assembleDebug :watch:assembleDebug     # first build compiles the Rust library, about 1 minute
adb devices                                              # phone by USB or wireless debugging
adb -s <phone-serial> install -r phone/build/outputs/apk/debug/phone-debug.apk
adb connect <watch-ip>:<port>                            # watch: Developer options > Wireless debugging > Pair
adb -s <watch-serial> install -r watch/build/outputs/apk/debug/watch-debug.apk
```

- The watch drops off wireless debugging often. If `adb` says offline, reconnect and run the command again.
- Phone: Android 9 or newer. Watch: Wear OS 6 (API 36), because Watch Face Push does not exist before that.
- The permission to push watch faces (`PUSH_WATCH_FACES`) is granted when the watch app is installed. The watch app asks
  for the second one (set the pushed face as active) every time it starts while that permission is missing, and again when
  you press **Activate face**. Allow it. If you deny it when you press **Activate face**, the app explains how to pick
  Analog Plus in the watch face picker instead (long-press the face).
  (If it does not ask, `adb -s <watch-serial> shell pm grant com.seyedjafari.analogplus com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE`.)
- Phone and watch must be paired through the Galaxy Wearable / Wear OS app. The phone sends the face over the Wear Data Layer.

## Using it

**Phone navigation.** A bar at the bottom has four destinations; the phone remembers the one you were on (and the section
inside it) when you switch away, rotate the phone or the system restarts the app.

- **Design** is the editor: the preview stays pinned at the top, a row of section chips sits under it (Colours, Dial,
  Hands, Background, Complications, Rings), and **Send to watch** is always at the bottom (except while the keyboard is open). With the phone held sideways
  the preview and its hint sit on the left, and the chips, the section and **Send to watch** on the right; the bottom bar is lower
  there to leave room for the section.
- **Library** has three sections: **Templates**, **My designs** and **Share** (the old Templates and Share tabs; saving a
  design under a name is in My designs).
- **Send** shows the watch status (paired, Analog Plus installed) and the progress of a send. Pressing **Send to watch**
  in Design starts the send and opens this destination. A send keeps running if you switch destination.
- **Account** shows the app version, the (not yet available) sign-in, **Run setup again** (see First run below) and
  **Reset signing key**, which used to be in the Share tab.

Design sections. Colours are typed as `#AARRGGBB` (for example `#FF1E6BFF`); a wrong value shows a message and disables
**Send to watch**. The preview updates as you type. Complications: choose 4 or 6 slots, tap a slot to edit it, drag it to
move it (slots cannot overlap each other or the hand centre). At most 8 complication slots in total; each ring segment
counts as one. Rings: pick a layout (full, halves, thirds, quarters). Library > Templates: start from a built-in design
(Classic, Midnight, Neon, Minimal) or save your own under My designs. Library > Share: copy a link
(`analogplus://design?d=...`), load one from a link, or show the design as a QR code and scan someone else's. A tapped
link, a scanned QR code, a template or a saved design asks before it replaces the design you are editing; a link pasted
into Share and loaded with **Load design** replaces it at once. A tapped link shows its question over whichever destination is open.

Screenshots of every destination (light and dark, font scale 1.0 and 2.0) are in `docs/poc/screens/`
(`<destination>-<theme>-<scale>.png`). Onboarding is in `onboarding-<page>-<theme>-<portrait|landscape>[-2x].png` (rendered
by the instrumented test `OnboardingScreenshotTest` in a 360x800 / 800x360 dp window with animations off, 40 pictures; re-take them all with
`scripts/pull_onboarding_screens.sh`, which installs the app, runs that class (it is skipped in the normal suite, only a run with the runner argument `screenshots=true` renders them) and copies the pictures here; they show five steps
because the test turns the fifth page on, and the pair page shows a ready watch) plus real-app captures on the emulator: `onboarding-pair-nowatch-*` (no watch, "Could not
check"; the light landscape ones show Check again in view and, at font scale 2, the fade where the page continues) and `onboarding-welcome-real-dark-landscape.png`.

**First run (onboarding).** A new install shows a short paged flow before the app: welcome, connect your watch, the
one-time permission explained and a starting template. A fifth page, optional sign-in, appears only once accounts ship
(until then the flow is four pages: "Step 1 of 4"). There is no swiping: move with the primary button (Get started /
Continue), **Back** or **Skip**, and the system Back button goes to the previous page (on the first page of a first run it leaves the
app; on the first page of a run started from Account's "Run setup again" it counts as Skip and returns to the app at Design). Skip is on every page and never touches the design. Only the visible page is composed, so the pairing check does
not start until you reach the connect page. Closing the app mid-way (or process death) resumes on the same page; rotating
the phone keeps the page, and in a short wide window (phone held sideways) the animation sits beside the text and
Back, progress, Skip and the primary button share one row; at large font sizes the page scrolls and the buttons stay.

The connect page checks the Wear Data Layer when it opens, every time you come back to the app, and on **Check again**
(the same checker as Send; it gives up after 5 seconds). "OK: Found" and "OK: Installed" mean a face can be sent;
anything else names the exact next step ("Could not check" means the check failed, "Not found" that no watch is paired),
and you can always continue without a watch. Finishing (or skipping) lands on Design with the chosen template loaded.
If the watch was found, a one-time tip on Design offers **Open Send** or **Got it**; it also ends when you open Send by
any route, and no tip is shown when the watch was not found. An install that already has designs, a saved design, a version
number or a sent key (the POC) is never shown onboarding: the first launch records it as *Skipped*, and a new install is
decided the same way before the app autosaves the default design. To see it again: Account, **Run setup again** (works
after Completed and after Skipped). Choosing a template on the template page asks before replacing edits you made.

The animations are Lottie files drawn from the watch-face geometry, recoloured with the app's brand colours in light and
dark. They ignore the system animator scale inside Lottie; the page decides: with animations turned off in the system
(animator scale 0, "reduced motion") each page shows its last frame as a still image and pages change without sliding.
The welcome face also cycles its colours (Classic, Midnight, Neon, Minimal, all theme colours; `onboarding-welcome-colour-morph-{light,dark}.png`). The animations also pause when the page is not visible and when the app is in the background. To regenerate them:
`python3 tools/gen_onboarding_lottie.py` rewrites `phone/src/main/res/raw/onboarding_{welcome,searching,found,permission}.json`
(`--out DIR` writes elsewhere). `tests/test_onboarding_lottie.py` and a JVM test fail when the committed files and the
generator disagree, so edit the generator, not the JSON. To use licensed animations instead, drop files under the same four
names into `res/raw` (keep them under 100 KB; the colour groups named accent / accent2 / accent3 / ink / track / onaccent are what the app
recolours, so licensed files get their own colours unless they use those group names, and the generator test then no
longer applies and should be deleted with them).

**Always-on mode.** The background is hidden (black) and the dial is dimmed. Dark hands, ticks and numerals switch to
light grey there, and the editor shows a hint when that happens; dark slot text stays dim.

**Send.** Press **Send to watch**. The phone builds and signs the face, takes the next version number and sends it.

**Watch, first time.** Open the Analog Plus app on the watch. When it shows "Face: version N" press **Activate face**.
Activation works once per install of the watch app. After that the face is only updated in place.

**Complications.** Pushed faces carry their default providers (battery on the right, steps at the bottom, Samsung weather
on top when present, the next prayer time from the prayer times app on the left). To change a provider, long-press the
face and tap *Customize* on the watch, or use Galaxy Wearable on the phone. Ring segments: the first shows the watch
battery, the second the step count as text, the others start empty; pick providers for each in the same editor.

## Known limits

- **Data Layer not verified on real devices.** It needs the phone and watch paired and the same application id on both.
  Only the emulator relay below has been run.
- **One activation per install.** `setWatchFaceAsActive` works once. If the face stops being active, pick Analog Plus
  in the watch face picker. The app never removes the face (`removeWatchFace` is never called).
- **Version counter.** Every send uses a higher version number, kept on the phone. The watch rejects a number that is not
  higher ("version not higher").
- **No bundled default face.** The watch is empty until the phone has pushed once.
- **Signing key.** It is generated on the first send and lives in the phone app's private storage. It is excluded from
  backup and from phone-to-phone transfer. **If you uninstall the phone app (or clear its data, or move to a new phone) the key is lost, a new key is generated, and the watch
  rejects every update because it is signed with a different key. Recovery means getting the old face off the watch.
  **Uninstalling the Analog Plus watch app does NOT remove the pushed face** (checked on the Wear OS 6 emulator: the
  face package stays). Remove it with
  `adb -s <watch-serial> shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog`; then send again and, if the
  watch app's one-shot activation is used up, pick Analog Plus in the face picker. (Not tried on a real watch.) The app
  never removes a face itself. Account has "Reset signing key" for a damaged key; it only helps after the old face
  is gone from the watch.**
  Do not uninstall the phone app during the test.
- Photo backgrounds are resized and compressed to fit Bluetooth (350 KB cap); the build fails with a message if it
  cannot. Share links and QR codes do not carry the photo.
- **QR scanning** uses Google's code scanner (Play services): it needs Play services, and the first scan downloads the
  scanner module. Showing a QR code works without it; a design that is too big for a QR code says so and uses the link.
- The ring and slot complication data cannot be shown on an emulator without providers; the e2e run only checks that the
  layout is drawn.

## Checklist for the real-device test

**Group 0 below is NOT RUN yet** (the owner skipped it: no phone and watch were attached when onboarding was built). Do it,
then write what happened under "Known limits".

0. (Not run) Phone: Account, **Run setup again**. Connect page: both rows "OK" within a few seconds; the animation switches to
   the tick; note how long the check took and any message. Turn Bluetooth off on the phone, come back to the app: the rows
   change (Found becomes Not found or Could not check); turn it on, return to the app: both OK again. Permission page: read
   it, then finish; the tip offers **Open Send**. Do not uninstall the watch app to test "installed but missing" (it would use
   up the one-time activation).
1. Pair phone and watch, install both apps (above), allow the watch permissions.
2. Phone: open Analog Plus, leave the default design, press **Send to watch**. Wait for the success message with the version.
3. Watch: open Analog Plus, check "Face: version N", press **Activate face**. The face appears and looks like the Classic face.
4. Phone: Design > Colours, set the accent to `#FF1E6BFF`, press **Send to watch** again.
5. Watch: the face turns blue in place. No activation, no second face in the picker, the status shows the new version.
6. Phone: Design > Complications, drag one slot to another position, send. Check the slot moved on the watch.
7. Phone: Design > Rings, choose quarters, send. Check four arcs around the dial and the slot cap indicator.
8. Long-press the face, *Customize*, and pick a provider for a ring segment; check the arc fills.
9. Write down anything that fails (message on the phone, status on the watch).

## Emulator end-to-end run

The two emulators are not paired, so the script replaces the Data Layer: the phone debug build builds the face from an
`adb` broadcast, the script copies the APK to the watch and the watch debug build applies it. The signing, packing,
validation, push, update-in-place and activation code is the real code.

```bash
scripts/poc_e2e.sh                  # a few minutes: both emulators, 3 designs, assertions, screenshots
scripts/poc_e2e.sh --with-tests     # also unit tests and device tests of every module (slow)
```

The phone phase (install and the three builds) holds the phone emulator lock (`scripts/_lock.sh`, the same lock as
`scripts/with_phone_emulator_lock.sh`); a second run waits and says who holds it. The lock is freed when the owning
process dies. The script checks that the installed apps are byte-identical to the fresh builds and stops the emulators
it started, even when one never reached `adb`.

Needs the phone AVD `1_Pixel_8_API_33` and the watch AVD `GalaxyWatch7_API36` (started headless when not running, and
stopped again only if the script started them), Python 3 with Pillow (`pip3 install -r requirements.txt`), and the Rust
setup in `docs/poc/pack-native.md`. Screenshots go to `docs/screenshots/poc/`. The script fails when a face does not
apply, the centre disc is not the design's accent colour, the version does not rise, or more than one face is installed.
`POC_SETTLE_SECONDS` (default 25) is the wait before each screenshot; complications need about 20 s after the first
activation.

Onboarding tests: `python3 -m unittest tests.test_onboarding_lottie` (assets), JVM `./gradlew :phone:testDebugUnitTest`
(state, controller, storage, asset files), and on the phone emulator the `Onboarding*`, `PairPageTest`, `TemplatePageTest`
and `StaticPagesTest` classes, for example
`scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.seyedjafari.analogplus.phone.OnboardingFlowTest,com.seyedjafari.analogplus.phone.OnboardingGateTest`
(`connectedDebugAndroidTest` rejects `--tests`; the class argument takes a comma-separated list; an unset animator scale is the
normal state, and `OnboardingReducedMotionTest` sets it to 0 itself). The tests render through `PinnedWindow`, so they do not
depend on the emulator's orientation, and the ones that touch the real store restore it afterwards.

Device tests by hand: `./gradlew testDebugUnitTest`, then
`scripts/with_phone_emulator_lock.sh ./gradlew :facegen:connectedDebugAndroidTest :packjni:connectedDebugAndroidTest :phone:connectedDebugAndroidTest`
(phone emulator, port 5562) and `ANDROID_SERIAL=emulator-5560 ./gradlew :watch:connectedDebugAndroidTest` (watch emulator).

Before the watch device tests, remove the app from the watch emulator: `adb -s emulator-5560 uninstall com.seyedjafari.analogplus`.
`scripts/poc_e2e.sh` leaves the watch app, a pushed face (version 3 or higher) and the used activation behind, and the tests
would fail ("version not higher", activation already used). Gradle installs over the existing app and removes it only
after its own run, so only the first run after the e2e needs the uninstall (`--with-tests` does it for you).
