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
- [ ] **6. App Check (Play Integrity).** Build, App Check, Apps, register the Android app with the **Play Integrity** provider
  (needs the app in Play Console and the Cloud project linked to it: Play Console, Test and release, App integrity; the app's
  **app-signing SHA-256** from item 2 must be registered in Project settings).
  Register a **debug token** for your own debug builds: run a debug build once with the project's `google-services.json`, find the
  log line "Enter this debug secret into the allow list in the Firebase Console" (`adb logcat | grep -i appcheck`), and add the
  token under App Check, Apps, Manage debug tokens. The debug token is **new for every installation of the debug app**: after a reinstall or
  Clear data you register a new one (or fix a secret with the `firebaseAppCheckDebugSecret` setting, see the Firebase App Check
  debug-provider documentation). Watch App Check's request metrics for a few days, **then** switch Firestore
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
  `[OWNER]` there (name, phone model). Re-check them whenever the app collects something new.
- [ ] **10. Account deletion page.** Play requires an in-app way **and** a web URL for deleting an account. The in-app way
  is in the app (Account, Delete account and cloud data). Host `docs/accounts/account-deletion-page-draft.md` as a public page
  (fill in its `[OWNER: ...]` places) and enter its URL in Play Console, App content, Data deletion. The page promises that a
  request by email is answered by hand: you delete the user under Authentication and the `users/<uid>` data under Firestore
  yourself, so decide how many days you promise.
- [ ] **11. Usage watch.** The Spark plan has **no budget alerts**. After launch, look at the Usage tab for Firestore and
  Authentication now and then (quotas change, read them there). For email alerts, upgrade to **Blaze** (pay as you go, still
  keeps the free allowance) and set a budget alert. The number of documents per user is small, but a design with a photo is large (item 12).
- [ ] **12. Choose Spark or Blaze before launch.** The plan avoided a billing account, but the traffic of a photo design may use up
  the free quotas faster than the document count suggests. **This is an estimate from reading the code as it was at commit 8a550b0 (before the final review fixes), not a
  measurement, and Firebase's allowances change: read the current ones in the console.** The estimate:
  - In that code, every time editing paused (about 1 second after the last change), the app uploaded the design being edited and read it back.
    With a photo that is about 400 kB each way, plus about 400 kB to every other phone of the same account that has the app open.
    That is roughly 0.8 MB or more of data out of Firestore per pause, and the app also re-reads every saved design (and the
    records of deleted ones) each time.
  - **Since the fixes** the design being edited is uploaded after 10 seconds of quiet or when you leave the app, and a pass about that
    design alone no longer reads the saved designs. A slider drag is now one upload instead of dozens. The upload is still read back
    (about the same size again) and still reaches other phones that have the app open. So the traffic **should be much lower, but
    this has not been re-measured**; the numbers below are the old ones and are an upper bound only until someone measures (checklist
    item G3).
  - If Spark allows about 10 GiB of data out per month for the whole project and 50 000 document reads a day (check this), that is
    roughly 13 000 pauses a month for all users together. An editing session of about 100 pauses is about 80 MB, so about 130 such
    sessions would use the month's allowance.
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
