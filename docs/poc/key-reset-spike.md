# Key reset spike (2026-10-11)

**Question.** If the passphrase is lost and the signing key is lost with the phone, can a person get a face signed with
a new key onto the watch without a computer?

**Method.** Steps 1-4 of the accounts plan, Task 13, run by hand on two emulators: phone `1_Pixel_8_API_33`
(emulator-5562) and watch `GalaxyWatch7_API36` (emulator-5560, Wear OS 6, API 36). They are not paired; the phone debug
build builds the face from a broadcast, adb relays the APK, the watch debug build applies it with the real Watch Face
Push code (`scripts/poc_e2e.sh --keep-emulators`, then the commands of the task). The real watch (SM-L315F) was not used.

Starting state (Step 1): `E2E PASSED`; the watch held `com.seyedjafari.analogplus.watchfacepush.analog`,
`versionCode=407554`, signer `signatures=[ab32cef6]` (key K1). The first key was K1; each later loss of the key below
(`run-as com.seyedjafari.analogplus rm files/signing-key.pem`, the file's SHA-256 changed every time) produced a new key.

## Observations

1. **Update with another key (same package).** The phone built version 407555 with the new key K2
   (`AnalogPlusBuild ok version=407555`). Applying it on the watch, whose face was signed by K1, returned
   (`AnalogPlusPush`, `"ok":false`):
   `UpdateWatchFace failed (code 1): Unknown error while updating a watch face. Typically this means that the Watch Face Push service on the watch could not be accessed. If this phone was reinstalled or its signing key was reset, the watch only accepts updates signed with the original key: remove the old Analog Plus face from the watch (uninstalling the watch app does not remove it; with adb: pm uninstall com.seyedjafari.analogplus.watchfacepush.analog) and send again.`
   (the first sentence is the system's; the rest is the app's `UPDATE_KEY_HINT`). The installed face stayed at
   `versionCode=407554`, signer `ab32cef6`. The same happened a second time with K3 against K2
   (version 407558). The system says "code 1 / unknown error", it does not name the signing key by itself.
2. **After removing the face (adb): new-key face added: yes.** `adb -s emulator-5560 shell pm uninstall
   com.seyedjafari.analogplus.watchfacepush.analog` printed `Success`; applying the K2 face then logged
   `AnalogPlusPush: {"ok":true,"versionCode":407555,"error":null}`, and `dumpsys package` showed `versionCode=407555`,
   signer `d959c6c2`. Activation: `DEBUG_ACTIVATE` returned `{"ok":true,"versionCode":407555,"error":null}` without
   asking the system again (the watch app had already used its one activation in the e2e run; see
   `PushController.activateOnce`). Screenshot `docs/screenshots/accounts/reset-after-readd.png`: the watch shows the
   system's default face with the charging screen ("Ready to go! 100%"), not an Analog Plus face. So after the old
   face was removed the new one is installed but not showing; a person would have to pick it in the face picker (I did
   not open the picker, so "it appears in the picker" is not observed here).
3. **Removal without adb.** On the watch emulator, `am start -a android.settings.APPLICATION_SETTINGS` opens
   "Apps & notifications" (Notifications, Default apps, Unused Apps, App info, App permissions, Privacy dashboard,
   App storage; screenshots `reset-watch-apps.png`, `reset-watch-apps-scrolled2.png`). **Settings lists the face: yes,**
   under **App info**, as an entry named "Analog Plus" (`reset-app-info-list.png`, `reset-app-info-list2.png`).
   There are four entries with the same name, which matches the four Analog packages on this emulator (the watch app,
   the pushed face, the old code-free face and the spike's test face; I did not check each label separately;
   `pm list packages` showed `com.seyedjafari.analogplus.watchfacepush.analog`,
   `com.seyedjafari.wfpspike.watchfacepush.analog`, `com.seyedjafari.analogwatchface`, `com.seyedjafari.analogplus`).
   A person cannot tell them apart by name; I told the entries apart by their details page: the pushed face has an
   empty Version and 98.30 kB of storage (`reset-app-info-face-details.png`), the watch app shows version 0.1 and
   8.63 MB. That identification is an inference (the package name is not shown); it is confirmed by what disappeared
   afterwards. **Uninstall offered: yes** (`reset-face-uninstall-offered.png`, the top button of the entry, with
   Force stop greyed out), then the dialog "Uninstall app?" with cancel and OK (`reset-uninstall-confirm.png`).
   After OK, `pm list packages | grep watchfacepush` showed only the spike face: the Analog Plus face was gone.
   **Uninstalling that way then adding the new-key face: worked.** With K2's face installed and a K3 face built by the
   phone (version 407558): the apply failed as in observation 1; after the Settings uninstall the apply returned
   `{"ok":true,"versionCode":407558,"error":null}` and `dumpsys package` showed `versionCode=407558`, signer `6d10bbb3`
   (neither K1's `ab32cef6` nor K2's `d959c6c2`). `DEBUG_ACTIVATE` again returned ok without a system call;
   `reset-after-ui-uninstall-readd.png` shows a plain digital clock face on black, which I did not identify as the
   Analog Plus face.
   (Before that, one earlier round removed K2's face through the same Settings screens and re-added the same K2 APK:
   also `"ok":true`.)
4. **New package name while the old face is installed:** not testable (the watch app updates the single listed face);
   not needed.

## Decision

Rule from the plan: SELF_SERVICE needs the watch's own Settings to list the pushed face package **and** offer Uninstall
for it, **and** the new-key apply to succeed after that. On the Wear OS 6 emulator all three held: the face is listed
(under the name "Analog Plus", indistinguishable from the watch app by name), Uninstall is offered and works, and the
face signed with a new key was accepted afterwards. So the result for the emulator is self-service. The hard part for a
person is picking the right "Analog Plus" entry in a list of look-alikes (the face has no version and is tiny), and then
picking the new face in the face picker; Task 14's text should say that.

RESET_RESULT: SELF_SERVICE

Not verified: Samsung One UI Watch (the owner's SM-L315F); see Task 18, checklist item "uninstall the face from the watch itself".
