# Accounts and sync: real-device checklist

**Status: NOT RUN. Every line below is NOT RUN.** The owner chose to skip real devices when the accounts work was built. Nothing
here has been tried on a real phone, a real watch, with a real Google sign-in, with App Check, or against a real Firebase
project. The only verification so far is: JVM tests, the Firestore rules tests and the instrumented tests on a phone emulator
against the Firebase Emulator Suite (`scripts/accounts_e2e.sh`, `cd firebase && npm test`), and the key-reset spike on a Wear OS
emulator (`docs/poc/key-reset-spike.md`). Tick a line only after you did it and write what you saw next to it.

Needs `phone/google-services.json` from the owner's Firebase project (`docs/accounts/owner-prerequisites.md`, items 1-5) and a
build installed on a real phone with Google Play services and a Google account on it. With the file present a debug build uses
YOUR project unless the app's `files/use-firebase-emulator` marker exists (it must not, for this checklist). A debug build
needs its App Check debug token registered (item 6 of the prerequisites) once enforcement is on, and the token is new after every reinstall. Run each Android group on
both Android 13 or lower and Android 14 or newer if you have both phones; note the version beside each result.

Texts below are the English texts of the app (`phone/src/main/res/values/strings.xml`). If a text in the app differs from a quote here, the app's `strings.xml` is right and the quote is out of date.

## A. Sign in, sync, backup, restore

- [ ] **NOT RUN** A1. Account, **Sign in with Google**: the Google chooser lists your account. After choosing it the card shows
  "Signed in as <email>" and the sync pill ends at "Synced just now". Note which sheet you saw (Android 14 and newer: the system
  sheet; Android 13 and lower: a Play services sheet).
- [ ] **NOT RUN** A2. Save a design in Library. Within a few seconds the Firebase console, Firestore, `users/<uid>/designs` shows
  a document with that design's name and `deleted: false`. Delete the design in the app: the document stays with `deleted: true`
  and an empty `design`, and still holds the name (accepted: it goes only when the account is deleted, see C1). A saved design
  has **no photo** in the console even if the design you were editing had one.
- [ ] **NOT RUN** A3. Account, **Back up signing key**, choose a passphrase twice, **Back up**. While it works the card says
  "Backing up and checking the backup...". Then the pill reads "Signing key backed up (key <16 hex>)". The console shows
  `users/<uid>/backup/signingKey` with `sealed`, `kdf: pbkdf2-hmac-sha256`, `iterations: 600000`, `salt`, `iv`, `version`,
  `keyFingerprint`, `writeId`, `createdAt` and no readable key. Write down the fingerprint.
- [ ] **NOT RUN** A4. On a **second phone** (or after clearing the first phone's app data) sign in with the same Google account.
  The saved designs appear. The pill says "A backup exists; this phone has no key yet".
- [ ] **NOT RUN** A5. **Restore signing key from backup**. First type a wrong passphrase twice: "That passphrase did not open the
  backup. Check it and try again." The third wrong try shows "That passphrase did not open the backup. Wait 5 seconds before
  trying again." Then type the right one: "Signing key restored (key ...)" with the **same fingerprint as in A3**. Send to watch
  from this phone: the watch accepts the update in place (no key-mismatch message, the version rises).
- [ ] **NOT RUN** A6. Edit the design you are editing on phone 2, wait about 15 seconds without touching it (or leave the app, which uploads at once), then open the app on phone 1. The saved designs update. The
  edited design asks whether to use the design from the other phone (the title names that phone by the name set in its Settings, else its model), with **Use theirs**
  and **Keep mine**, once per difference. Read the question: does it say which design is on this phone and which one the other
  phone saved, and that **Use theirs** replaces the one here? **Keep mine** is the filled (default) button; a tap outside the dialog or the system Back button also means Keep mine. The question
  appears on any tab (Design, Library, Send, Account), not only on Design.
- [ ] **NOT RUN** A7. Phone 1 in airplane mode: edit a saved design and delete another. The pill shows "Offline, will retry".
  Switch airplane mode off: the pill goes to "Synced ..." and phone 2 sees both changes.
- [ ] **NOT RUN** A8. **Sign out**: "Signing out keeps your designs on this phone." Sign in again: the same state comes back.
  **Sync now** works.

## B. The Google prompt on a real phone

- [ ] **NOT RUN** B1. The Credential Manager flow with a real account, on **Android 13 or lower** (a hidden Play services
  activity) **and** on **Android 14 or newer** (the system sheet): choose an account; cancel with Back; tap outside the sheet.
  Cancel leaves the card signed out without an error and the button works again at once.
- [ ] **NOT RUN** B2. **Reopening the app while the Google prompt is open** (the app's main screen is `singleTask`): open the
  prompt, then press Home and tap the launcher icon again, and separately tap an `analogplus://design?...` link. The prompt must
  end as cancelled. "Signing in..." must not stay on screen. Repeat with the prompt from **Delete account and cloud data**:
  "Deleting your account..." must not stay on screen and nothing is deleted. Check Android 13 or lower especially.
- [ ] **NOT RUN** B3. **Changing the configuration while the prompt is open**: rotate the phone, switch dark mode, and use
  split-screen (or fold) while the prompt is open, then choose an account. You must end up signed in as the account you picked,
  not signed out and not as another account.
- [ ] **NOT RUN** B4. Sign-in problems: airplane mode at **Sign in with Google** shows "Sign-in needs an internet connection.
  Check it and try again." A phone with no Google account shows "No Google account was found on this phone. Add one in the
  phone's settings, then try again."

## C. Deleting the account

- [ ] **NOT RUN** C1. **Delete account and cloud data**, then **Delete account** in "Delete your account?". You are asked to
  choose your Google account again. Afterwards: "Your account and its cloud data were deleted. Designs and the signing key on
  this phone are untouched." The card is signed out, the console shows no `users/<uid>` documents, the Authentication list no
  longer has you, and designs and the signing key on the phone are still there. Send to watch still works.
- [ ] **NOT RUN** C2. **Interrupted deletion**: start the deletion and kill the app while "Deleting your account..." is shown
  (swipe it away from recents, or switch airplane mode on). Reopen the app: the card says "Deleting your account was not
  finished" with **Finish deleting** and **Keep my account**, and sync is paused. **Finish deleting** asks for Google again
  and completes (as C1). Repeat once and choose **Keep my account** instead: "Your account was kept. Sync is on again and the
  designs on this phone are uploaded again." and the designs are back in the console. With a second phone signed in to the same
  account whose app is OPEN during the cut-off, and only if the console shows `deleted: true` while it is cut off: that phone was
  signed out when it noticed (notice as in G1) and stays signed out after Keep; signing in on it again works and it syncs. A phone
  with its app closed or offline during that time is not signed out and simply syncs again; if the console still shows
  `deleted: false` the cut came before the account was marked, and no other phone is affected.
- [ ] **NOT RUN** C3. The signing-key backup is one of the things C1 removes: after C1 sign in again with the same Google
  account. There is no backup ("Signing key is not backed up").
- [ ] **NOT RUN** C4. **Abandoned deletion, cut off early.** Do not clear a phone's app data for this: that throws away its signing key,
  and a cut-off wipe may already have deleted the backup (if that phone pushed the face to your watch, Send then fails for good
  until `adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog`). Instead: start the deletion on phone 1 and cut it
  off (airplane mode while "Deleting your account..."). If the console `users/<uid>/meta/account` still says `deleted: false`, the
  cut came before the account was marked (as in C2): use **Finish deleting** (or Keep) and repeat. When it says `deleted: true`,
  sign in with the same account on a second phone (or on an install that does not matter): it is signed out again with the notice,
  and so is any other phone that signs in. This is the expected, documented limit (the app cannot finish or undo it from a phone
  without the pending mark). Set `deleted` to `false` in the console (a real boolean, not the text "false"): signing in works
  again. Phone 1 still holds its pending deletion: turn its connection back on (it is still in airplane mode from the cut, and Keep
  and Finish need the cloud), then tap **Keep my account** (or **Finish deleting**) to settle it. If `users/<uid>/meta/account` is
  missing altogether (and the user is still in Authentication), the cut came at the very end: continue with C5 from "Sign in".
  If the user is already gone from Authentication the deletion completed: start over with another throwaway account.
- [ ] **NOT RUN** C5. **Abandoned deletion, cut off at the very end** (no signing key is lost). Use an account that does not matter.
  Start the deletion on phone 1 and cut it off early as in C4 (`deleted: true`). In the console delete the documents in `designs`,
  `state` and `backup` and then `meta` (the account record), and leave the user in Authentication: this is the state of a deletion
  that stopped just before the sign-in was removed. Sign in with the same account on a second phone that never synced with it (or
  that was signed out earlier): it finds the user, creates `meta/account` again (`deleted: false`) and syncs; the account works
  again, empty, and that phone's designs are uploaded. Then on phone 1 tap **Finish deleting**: the console empties again (also what
  phone 2 uploaded), the user disappears from Authentication, and phone 2 is signed out (with the notice if its app is open; an app
  that is only opened later after more than an hour may show a plain signed-out card). Turn phone 1's connection back on before
  tapping Finish deleting (it is still in airplane mode from the cut). If the cut gave `deleted: false` (it came before the cloud was
  reached) or the user is already gone from Authentication, this state was not reached: start over with another throwaway account.

## D. First run (onboarding) with accounts

- [ ] **NOT RUN** D1. A fresh install (or Account, **Run setup again**) on a build with `google-services.json` shows five
  pages ("Step 5 of 5" on the last). Page 5 is optional: **Sign in with Google**, then **Back up my signing key** (passphrase
  twice), then **Done**. On a second phone the same page offers **Restore my signing key**.
- [ ] **NOT RUN** D2. On page 5: **Back** returns to page 4; rotating the phone and killing the app keep the page; a build
  without `google-services.json` keeps four pages (a debug build has five anyway: it falls back to the emulators). Ways out of the page, and what each one does (write down which buttons you
  see on each step; the page's own buttons and the screen's own **Skip** are different buttons and may not all be shown):
  the page's own button before signing in ("Not now") and after signing in ("Skip for now") ends setup as **completed**, and
  **Done** after a backup does too; the screen's own **Skip** is not drawn on this page (the page's buttons are the ways out).
  The app opens on Design with the design untouched, and Account, **Run setup again** starts the flow again.
- [ ] **NOT RUN** D3. (From the onboarding plan, earlier skipped) On the owner's paired phone and watch: Account, **Run setup
  again**, page 2 "Connect your watch": both rows "OK" within a few seconds; the animation switches to the tick. Note how long
  the check took. Turn Bluetooth off on the phone and come back: the rows change (Found becomes Not found or Could not check);
  turn it on and return: both OK again.
- [ ] **NOT RUN** D4. (From the onboarding plan, earlier skipped) Look at all onboarding pages on the owner's phone in light and
  dark mode and at a large font size. Is anything clipped, is the primary button always reachable, is the text readable?
- [ ] **NOT RUN** D5. (From the POC, earlier skipped) The Data Layer transfer on a really paired phone and watch: **Send to
  watch** from the phone installs the face on the watch. Until this is run, `docs/poc/README.md` keeps its "Data Layer not
  verified on real devices" bullet.

## E. Forgotten passphrase and the Galaxy Watch (the reset spike)

- [ ] **NOT RUN** E1. On a phone with no key and a backup in the cloud: **Restore signing key from backup**, then **I forgot the
  passphrase**. The title is "Start over without the passphrase?". The text matches the spike result (SELF_SERVICE: it tells you
  to uninstall the watch face on the watch itself; NEEDS_ADB: it tells you that a computer with adb is needed). Do **not** confirm
  **Delete backup and start over** (or **Delete backup anyway**) unless you want to lose the backup.
- [ ] **NOT RUN** E2. **On the Galaxy Watch (SM-L315F), what the spike could not show.** The spike result SELF_SERVICE was seen
  on the **Wear OS 6 emulator only**. There, Settings, Apps & notifications, App info lists **several** entries named "Analog
  Plus" (the watch app and the faces; four on that emulator). The pushed face is the one with no version number and about 98 kB of
  storage, and it offers **Uninstall**. After uninstalling it, a face signed with a new key was accepted. On the real watch
  check, with the owner's test faces in mind (ask before removing anything, see `CLAUDE.md`):
  1. Open the watch's Settings and find its list of apps. Is the pushed face listed? How many "Analog Plus" entries are there, and
     can you tell the face from the watch app (version number, storage size)?
  2. Is **Uninstall** offered for the face entry?
  3. After uninstalling it, does **Send to watch** from a phone with a new key succeed, and do you have to pick the face in the
     watch face picker (press and hold the watch face, choose Analog Plus)?
  4. If any answer is no, the app's text ("account_reset_self_remove" and "account_reset_self_pick" in `strings.xml`) promises too
     much. Edit `RESET_RESULT:` in `docs/poc/key-reset-spike.md` to `NEEDS_ADB` and `KeyResetCopy.CURRENT` to `ResetPath.NEEDS_ADB`
     together (`KeyResetCopyTest` fails when they differ). That is a code change, not a document change.

## F. App Check and Play Integrity

- [ ] **NOT RUN** F1. After App Check enforcement is switched on for Firestore (owner prerequisites item 6): a **release** build
  that came from Play (an internal test track is enough) still signs in and syncs.
- [ ] **NOT RUN** F2. A debug build whose debug token is **not** registered is refused ("The cloud refused the sync" or similar);
  after registering the token it syncs again.
- [ ] **NOT RUN** F3. A sideloaded release build that Play does not know (the signing SHA-256 is not registered) is refused.

## G. Found by the final review (not tried anywhere)

- [ ] **NOT RUN** G1. **Another phone still signed in during a deletion.** The kill switch (the `users/<uid>/meta/account` marker
  and its rules in `firebase/firestore.rules`) is built and tested only against the emulators; the project needs those rules
  deployed (owner-prerequisites item 5). The question "Delete your account?" says that other phones are signed out, and nothing
  more, the next time they reach the cloud, that their designs and signing keys stay, and that an offline phone stays signed in until
  it reconnects: read it. Then:
  1. Sign in on two phones with the same account and keep phone 2's app open. Delete the account on phone 1. Phone 2 should be
     signed out of the account on its next check (within about a minute while the app is open, or when it next tries to sync), keep
     its saved designs and signing key, and show the notice "This account is being deleted, or was deleted, on another phone, so
     this phone was signed out. The designs and the signing key on this phone are kept." An edit made on phone 2 after the deletion
     must not appear in the console (`users/<uid>` stays empty, also after 15 seconds).
  2. Repeat with phone 2's app force-stopped (swipe it away) before the deletion. Wait for the visible end of the deletion (the
     console shows no `users/<uid>` documents and the user is gone from Authentication), then open phone 2 a few minutes after you
     stopped it (so well within the hour its sign-in lasts): it must end signed out with the same notice and must not create
     anything in the console.
  3. Repeat with phone 2 offline during the deletion and online afterwards. Within about an hour of the last time its app ran
     online, the notice shows when it reconnects. After more than an hour (also when the app was simply closed that long) its
     sign-in can no longer be renewed (the account is gone), and Google's own sign-in may sign it out first: it then shows a plain
     signed-out card WITHOUT the notice. That is expected; designs and key stay.
  4. A phone that signs in with the same Google account after the deletion finished gets a NEW account (a new uid): its cloud starts
     empty and then receives only the designs that are on that phone. No backup and none of the deleted designs' records (the old
     names) come back; if you read the designs in the console as "the account came back", check that the uid is a new one.
  Phone 2's Google sign-in token itself is not revoked and lasts up to an hour; that is expected, the cloud refuses its writes.
- [ ] **NOT RUN** G2. **Many saved designs on a slow link.** Saved designs carry no photo (a few kilobytes each); the design being
  edited may carry one (up to a few hundred kilobytes). With 100 to 200 saved designs and a design with a photo on a slow
  connection (a poor mobile signal): open the app. Does the first sync show "Offline, will retry" although the connection works?
  Then **Delete account and cloud data** with the same data: does it end with the message that your cloud data is deleted but
  Google wants you to confirm again, so you need **Finish deleting**? Note how long each took.
- [ ] **NOT RUN** G3. **Traffic.** Edit a design with a photo for one minute (move a slider with pauses of a few seconds), then leave the app. Then read the Usage tab
  for Firestore: how many reads and how much data out? The design being edited should be uploaded once after 10 seconds of quiet or
  when you leave, not after every pause. This feeds owner-prerequisites item 12.
- [ ] **NOT RUN** G4. **A design name over 100 characters.** The name field now stops at 100 characters, so this can only be tried
  with a design saved before that limit: on a build from before the accounts work (main 9237d82) save a design under a name of 120
  characters, then update to this build and sign in. What does the sync pill say, does it ever say it is synced, and does it name
  the design (the first 40 characters of its name)? Delete the design: the pill should settle.
- [ ] **NOT RUN** G5. **Two phones, same name, different content.** On two phones that never synced, save a design called
  "Night" with different colours, then sign both in. Both versions must survive; the copy is named after a phone: the device name set in Settings (like "Night (Sara's phone)"), the
  model when no name is set, or "Night (copy)" when the phone has no name at all. Write down the name and which phone it names.
  Check the other phone sees the same names. If two phones have the same name, the second copy gets a number.
- [ ] **NOT RUN** G17. **The device name.** Set phone 1's device name (Settings, About phone, Device name) to a name that contains
  a person's name, then do A6 and G5. The name appears on the other phone in the question and in the copy's name. Check the
  console: `users/<uid>/state/current` holds that name in `deviceLabel`, and the copy's design document holds it in `name`. The
  privacy policy says this; does the in-app text before signing in say the same?
- [ ] **NOT RUN** G18. **A photo with location data.** Pick a photo taken with the camera and its location turned on as the
  background of the design being edited, wait for the upload, and copy the photo out of the console (the `design` field holds it as
  text). It must show no EXIF, GPS or XMP data (open it with an EXIF viewer). Try one that is bigger than 512 pixels and one that is
  already small, and a photo that was taken sideways: it must come back upright.
- [ ] **NOT RUN** G6. **Editing during the first sync.** On phone 2, open the app and move a slider at once, while phone 1 has saved a
  newer design being edited. Does phone 2 still ask the question from A6, and does it not overwrite phone 1's design before you answer?
- [ ] **NOT RUN** G7. **A new phone at the end of setup.** On a new phone with an account that has data, go through setup, choose a
  template on page 4, and sign in on page 5. The question from A6 may appear straight after signing in; note when, how it reads,
  and that **Keep mine** is the default.
- [ ] **NOT RUN** G8. **Reset signing key, then the backup line.** Signed in with a backup of this phone's key, use **Reset signing
  key** in the Signing key card. The backup line must stop saying that this phone's key is backed up.
- [ ] **NOT RUN** G9. **Wrong passphrases and the clock.** Make three wrong tries (the wait is 5 seconds). Then set the phone's clock
  back by a day and try again: the wait must end within 5 minutes, not last until the old time comes round again. Set the clock
  back to automatic.
- [ ] **NOT RUN** G10. **A passphrase with accents or other non-ASCII characters.** Back up on phone 1 with such a passphrase
  (for example "Café über 2026 river"), restore on phone 2 (ideally another Android version). The restore must succeed.
- [ ] **NOT RUN** G11. **Almost no free storage.** On a phone that has no signing key yet, fill the storage and tap **Back up
  signing key** and choose a passphrase. The app must not crash; it should say the key could not be saved or backed up.
- [ ] **NOT RUN** G12. **A backup cut off by the connection.** Turn airplane mode on while "Backing up and checking the backup..."
  is shown. Read the message that follows. Then look at the backup line and the console: which passphrase opens what is stored?
  The message must not tell you that nothing changed if the console shows a new backup.
- [ ] **NOT RUN** G13. **Replacing a key by restore.** On a phone that already has a different key, restore the backup and confirm
  the replacement. Read the confirmation: does it make clear that the replaced key cannot be put back from the app? After it, the
  Send to watch result depends on which key the face on the watch was signed with.
- [ ] **NOT RUN** G14. **Send after a new phone, before restoring.** On a new phone without a key, with a backup in the cloud and a
  face already on the watch, press **Send to watch** before restoring. Read the error. Does it point to **Restore signing key from
  backup**, or only to uninstalling the face? Also read the Signing key card and its reset question.
- [ ] **NOT RUN** G15. **Screen reader and large text.**
  - With TalkBack on, make three wrong passphrase tries: is the waiting time read out once, and not again every second?
  - At font size 2 upright, open the reset question (**I forgot the passphrase**): can you scroll to the full text, and is there a
    sign that more text is below?
  - In dark mode, is **Keep my account** on the "Deleting your account was not finished" card easy to read?
- [ ] **NOT RUN** G16. **A phone with the wrong clock.** Set the clock of phone 2 two hours ahead (manually). Edit a saved design on
  phone 1, then a different change to the same design on phone 2, then the reverse order. Which edit wins each time? Write it down;
  the planner compares phone clocks for edits it cannot order otherwise. Set the clock back to automatic.

## Known limits (accepted by the owner, not failures)

- **A backup write has no time limit.** While "Backing up and checking the backup..." is shown on a flaky connection, **Sign
  out** waits for the write to end. Firestore itself gives up after its own five attempts. The owner accepted this. Before launch
  consider making sign-out stop waiting and having the next sign-in check and undo a backup that was never proven. This is not run
  and not scheduled.
- A deleted or renamed saved design leaves a small record (old name, deleted flag, time) in the cloud until the account is deleted.
  The owner accepted this and the privacy policy says it.
- An interrupted design write can land later and replace a newer edit from another phone (accepted; accounts spec, Known limits).
- Restoring a backup over a different key leaves a copy of the replaced key on the phone that the app cannot put back.
- The face version counter is not synced (it belongs to the install on the watch). The cloud holds designs, the design being edited and the key backup only.
