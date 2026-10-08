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
  you press **Activate face**. Allow it. If you deny it, the app explains how to pick Analog Plus in the watch face picker
  instead (long-press the face).
  (If it does not ask, `adb -s <watch-serial> shell pm grant com.seyedjafari.analogplus com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE`.)
- Phone and watch must be paired through the Galaxy Wearable / Wear OS app. The phone sends the face over the Wear Data Layer.

## Using it

**Phone, editor.** Tabs: Colours, Dial, Hands, Background, Complications, Rings, Templates, Share. Colours are typed as
`#AARRGGBB` (for example `#FF1E6BFF`); a wrong value shows a message and disables **Send to watch**. The preview updates
as you type. Complications: choose 4 or 6 slots, tap a slot to edit it, drag it to move it (slots cannot overlap each
other or the hand centre). At most 8 complication slots in total; each ring segment counts as one. Rings: pick a layout
(full, halves, thirds, quarters). Templates: start from a built-in design or save your own. Share: copy a link
(`analogplus://design?d=...`), load one from a link, or show the design as a QR code and scan someone else's. Loading a
link, a QR code, a template or a saved design always asks before it replaces the design you are editing. Templates:
Classic, Midnight, Neon, Minimal.

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
  rejects every update because it is signed with a different key. Recovery means getting the old face off the watch (not tried on a real
  watch in the POC): first uninstall the Analog Plus watch app, which removes the face it pushed and resets the one-shot
  activation, then install the watch app again and press **Activate face** after the next send. Only if the face is still
  there, try `adb -s <watch-serial> shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog` (that leaves the
  watch app's "activated" flag set, so activation stays used and the face must be picked in the face picker). The app
  never removes a face itself. The Share tab has "Reset signing key" for a damaged key; it only helps after the old face
  is gone from the watch.**
  Do not uninstall the phone app during the test.
- Photo backgrounds are resized and compressed to fit Bluetooth (350 KB cap); the build fails with a message if it
  cannot. Share links and QR codes do not carry the photo.
- **QR scanning** uses Google's code scanner (Play services): it needs Play services, and the first scan downloads the
  scanner module. Showing a QR code works without it; a design that is too big for a QR code says so and uses the link.
- The ring and slot complication data cannot be shown on an emulator without providers; the e2e run only checks that the
  layout is drawn.

## Checklist for the real-device test

1. Pair phone and watch, install both apps (above), allow the watch permissions.
2. Phone: open Analog Plus, leave the default design, press **Send to watch**. Wait for the success message with the version.
3. Watch: open Analog Plus, check "Face: version N", press **Activate face**. The face appears and looks like the Classic face.
4. Phone: Colours tab, set the accent to `#FF1E6BFF`, press **Send to watch** again.
5. Watch: the face turns blue in place. No activation, no second face in the picker, the status shows the new version.
6. Phone: Complications tab, drag one slot to another position, send. Check the slot moved on the watch.
7. Phone: Rings tab, choose quarters, send. Check four arcs around the dial and the slot cap indicator.
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

Device tests by hand: `./gradlew testDebugUnitTest`, then
`scripts/with_phone_emulator_lock.sh ./gradlew :facegen:connectedDebugAndroidTest :packjni:connectedDebugAndroidTest :phone:connectedDebugAndroidTest`
(phone emulator, port 5562) and `ANDROID_SERIAL=emulator-5560 ./gradlew :watch:connectedDebugAndroidTest` (watch emulator).

Before the watch device tests, remove the app from the watch emulator: `adb -s emulator-5560 uninstall com.seyedjafari.analogplus`.
`scripts/poc_e2e.sh` leaves the watch app, a pushed face (version 3 or higher) and the used activation behind, and the tests
would fail ("version not higher", activation already used). Gradle installs over the existing app and removes it only
after its own run, so only the first run after the e2e needs the uninstall (`--with-tests` does it for you).
