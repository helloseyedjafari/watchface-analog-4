# Accounts and sync: what only the owner can do

Plan: `docs/superpowers/plans/2026-10-09-accounts-sync.md`. Nothing here blocks building or testing: debug builds talk to the
Firebase Emulator Suite on your computer, and a release build without `phone/google-services.json` simply has accounts switched
off. Do these in order; each item says what to hand back to the code.

- [ ] **1. Create the Firebase project.** console.firebase.google.com, Add project, name it "Analog Plus". Turn Google Analytics
  **off** (the app collects nothing else, and the Data Safety form stays short). Start on the free **Spark** plan: the design
  needs neither Cloud Storage nor Cloud Functions, so no billing account is needed to build and test. Whether to stay on Spark for
  launch is item 12. Note the **project id**.
- [ ] **2. Register the Android app.** Project settings, Your apps, Add app, Android. Package name `com.seyedjafari.analogplus`.
  Add the **SHA-1 and the SHA-256** of every key the app will be signed with (Google sign-in refuses an unknown key; App Check
  with Play Integrity needs the SHA-256 of the app-signing key):
  - debug: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep -E 'SHA1|SHA256'`
  - release, once the app is on Play: Play Console, your app, Test and release, Setup, App signing: copy the SHA-1 and SHA-256 of the
    **App signing key certificate**, and also both of the **Upload key certificate** (for builds you sign yourself).
    (`phone/build.gradle.kts` has no release signing configuration yet; add one before the first release build.)
- [ ] **3. Turn on Google sign-in.** Build, Authentication, Get started, Sign-in method, Google, Enable. Set the public-facing
  name ("Analog Plus") and a support email. This creates the **web OAuth client** the app needs (`default_web_client_id`).
  Add no other provider.
- [ ] **4. Download `google-services.json`** (Project settings, Your apps, the Android app) and save it as
  **`phone/google-services.json`**. Download it again after every SHA-1 or SHA-256 you add. It is not a secret, but `.gitignore` keeps it
  out of the repository by default (Task 3); say if you want it committed instead. With the file present, the next build wires
  Firebase in; without it nothing breaks.
- [ ] **5. Create Firestore.** Build, Firestore Database, Create database, **production mode**. Pick the location now; it
  **cannot be changed later**: `eur3` (Europe, multi-region) or `europe-west2` (London) suit a UK-first launch. Then publish the
  rules and indexes from the repository:
  `cd firebase && npm ci && npx firebase login && npx firebase deploy --only firestore --project <project-id>`.
  Put the project id into `firebase/.firebaserc` as a second alias (`"prod": "<project-id>"`).
  **Deploy the rules again every time `firebase/firestore.rules` changes** (run `cd firebase && npm test` first). The app's
  protection of a deleted account ("kill switch") lives in those rules and needs two things together: the rules deployed, and
  the account's **marker document** `users/<uid>/meta/account` in Firestore. The app creates the marker itself when it starts
  syncing for an account (you create nothing by hand); the rules then refuse every write of designs, the current design and the
  signing-key backup unless that marker exists and is not marked deleted, and the deletion marks it first. With older rules
  (without `meta/account`) the app cannot create its marker: sync reports that the cloud refused it, and **Delete account also
  fails** (marking the account deleted is refused, the deletion stays pending), which matters for Google Play's deletion rule.
  With the rules left in "test mode" everything works and other phones still sign themselves out when they see the marker, but the
  server no longer refuses their writes, so an edit from a phone that has not yet noticed still goes through. After
  deploying, open Firestore, Rules in the console and check that the published text contains `meta/account`. This stops the other phones
  from writing and signs them out; it is not a revocation of their Google/Firebase tokens (that would need a Cloud Function on
  the Blaze plan, not built).
- [ ] **6. App Check (Play Integrity).** Build, App Check, Apps, register the Android app with the **Play Integrity** provider
  (needs the app in Play Console and the Cloud project linked to it: Play Console, Test and release, App integrity; the app's
  **app-signing SHA-256** from item 2 must be registered in Project settings).
  Register a **debug token** for your own debug builds: run a debug build once with the project's `google-services.json`, find the
  log line "Enter this debug secret into the allow list in the Firebase Console" (`adb logcat | grep -i appcheck`), and add the
  token under App Check, Apps, Manage debug tokens. The debug token is **new for every installation of the debug app**: after a reinstall or
  Clear data (also each time Android Studio or Gradle reinstalls the app with its data removed) the log shows a new one and you
  register it again. (The Firebase setting for a fixed secret, `firebaseAppCheckDebugSecret`, is an argument of instrumented tests
  only and does not apply to a debug build you start by hand.) Watch App Check's request metrics for a few days, **then** switch Firestore
  to *Enforced*. Enforcing earlier locks out your own test builds.
- [ ] **7. Privacy policy at a public URL.** Host the text of `docs/accounts/privacy-policy-draft.md` on any public page. First
  fill in every `[OWNER: ...]` in it (your name or company, contact email, the Firestore location from item 5, the URL of the
  deletion page from item 10, dates) and answer the two questions it asks about what Firebase Authentication keeps from the Google
  profile (look at a test account in the console), then remove its draft status paragraph. Put the URL in the OAuth consent screen (item 8) and in Play Console, Policy and
  programs, App content, Privacy policy.
- [ ] **8. Google Auth Platform / OAuth consent screen.** console.cloud.google.com, the same project, Google Auth Platform,
  Branding: app name, logo, support email, and the privacy policy link (item 7). Complete **brand verification** so that the
  app name, not a raw project id, shows in the Google account chooser.
- [ ] **9. Play Data Safety form.** After item 7 (it needs the privacy policy URL). Play Console, App content, Data safety. The
  answers are in the appendix of `docs/accounts/privacy-policy-draft.md`; copy them into the form and settle the lines marked
  `[OWNER]` there (the Name row, and the phone-name row: the device name the person set in Android Settings, or the model when none
  is set). Re-check them whenever the app collects something new.
- [ ] **10. Account deletion page.** Play requires an in-app way **and** a web URL for deleting an account. The in-app way
  is in the app (Account, Delete account and cloud data). Host `docs/accounts/account-deletion-page-draft.md` as a public page
  (fill in its `[OWNER: ...]` places) and enter its URL in Play Console, App content, Data deletion. The page promises that a
  request by email is answered by hand: you delete the user under Authentication and the `users/<uid>` data under Firestore
  yourself, in the app's order: 1. in `users/<uid>/meta/account` set `deleted` to `true` (other phones then stop writing); 2. delete
  the documents in `designs`, `state` and `backup`; 3. delete `meta` (the account marker); 4. delete the user under Authentication.
  Decide how many days you promise. The same page also tells people to write to you when a cut-off deletion cannot be finished or
  undone in the app (the phone that started it was lost or reset: every phone that signs in is signed out while the marker says
  `deleted: true`). To clear such a mark and keep the account, set `deleted` to `false` in `users/<uid>/meta/account`; to finish it,
  do the four steps above.
- [ ] **11. Usage watch.** The Spark plan has **no budget alerts**. After launch, look at the Usage tab for Firestore and
  Authentication now and then (quotas change, read them there). For email alerts, upgrade to **Blaze** (pay as you go, still
  keeps the free allowance) and set a budget alert. The number of documents per user is small; the one large document is the design being edited when it has a photo (item 12).
- [ ] **12. Choose Spark or Blaze before launch.** The plan avoided a billing account, but the quotas of the free plan may be
  used up faster than the document count suggests. **This is an estimate from reading the code at commit c403885, not a
  measurement, and Firebase's allowances change: read the current ones in the console.** What the app sends:
  - **Saved designs carry no photo** (the photo exists only in the design being edited). A saved design is a few kilobytes of
    text; the app reads them (and deletes them with an account) in pages of 50 and writes them one document at a time. So the saved designs cost **document reads**, not data: each full pass
    (when the app opens, each time it comes back to the front, "Sync now") reads every saved design once (and every record of a
    deleted one), and starting the live updates reads them once more. That is about two reads per saved design per session; 20
    saved designs are about 40 reads. Every write also costs **one extra read**, because the rules look up the account marker
    (the kill switch of item 5). The deletion of an account reads and deletes every document of it, in pages.
  - **The design being edited is the one big document when it has a photo.** The photo is shrunk to at most 512 pixels and about
    300 kB, about 400 kB inside the document. The design is uploaded after 10 seconds of quiet or when you leave the app (a
    slider drag is one upload), the upload is read back (about the same size again), and it reaches every other phone of the
    same account that has the app open. With one such phone that is roughly 0.8 MB of data out of Firestore per upload. Without a
    photo it is a few kilobytes.
  - If Spark allows about 10 GiB of data out per month for the whole project and 50 000 document reads a day (check this), that is
    roughly 13 000 photo uploads a month for all users together (an editing session of 20 uploads is about 16 MB, so about 600
    such sessions use the month's data allowance), and about 1 250 sessions a day of a user with 20 saved designs for the read
    allowance. A few hundred active users stay well inside this; thousands do not. Measure it after launch (checklist item G3).
  - As understood from Firebase's rules, an exhausted Spark quota stops Firestore until it resets (the day for reads, the month for
    data out), and every user sees "Cloud limit reached".
  - On **Blaze** the same traffic costs cents at this size, with a budget alert (item 11), but it needs a billing account. Choose
    one, or stay on Spark and watch the Usage tab (item 11) after launch.
- [ ] **13. Check the texts against the real project.** Once items 1-6 are done, read `docs/accounts/privacy-policy-draft.md`
  once against the Firebase console (the location, what an Authentication user record shows, whether App Check is enforced) and
  fix the draft where it disagrees. Nothing in these drafts has been checked against a real project.
- [ ] **14. Run the real-device checklist.** `docs/accounts/real-device-checklist.md` is NOT RUN. It needs items 1-5 done and a
  phone; the Galaxy Watch part (E2) settles the key-reset spike, which was seen on an emulator only.
- [ ] **15. Tell the agent** (one message): the project id, the Firestore location, whether `google-services.json` may be
  committed, and the two URLs.

Until item 4 is done: `./gradlew :phone:assembleDebug` gives a debug app that signs in through the **Auth emulator**
(`scripts/accounts_e2e.sh` starts the emulators and runs the emulator-backed tests); `:phone:assembleRelease` gives an app with the Account card saying sign-in is not set up.

After item 4 (once `phone/google-services.json` exists) a debug build talks to YOUR project, not the emulator, unless the
marker file `files/use-firebase-emulator` exists in the app. The instrumented tests do not depend on that: their runner
(`AccountsTestRunner`) writes the marker before the app starts, so `connectedDebugAndroidTest` and `scripts/accounts_e2e.sh`
keep using the emulators. To use the emulators by hand with the file present:
`adb shell run-as com.seyedjafari.analogplus sh -c 'echo tester@example.com > files/use-firebase-emulator'`, then restart the app.
