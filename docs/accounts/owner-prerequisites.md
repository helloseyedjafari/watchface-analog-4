# Accounts and sync: what only the owner can do

Plan: `docs/superpowers/plans/2026-10-09-accounts-sync.md`. Nothing here blocks building or testing: debug builds talk to the
Firebase Emulator Suite on your computer, and a release build without `phone/google-services.json` simply has accounts switched
off. Do these in order; each item says what to hand back to the code.

- [ ] **1. Create the Firebase project.** console.firebase.google.com, Add project, name it "Analog Plus". Turn Google Analytics
  **off** (the app collects nothing else, and the Data Safety form stays short). Stay on the free **Spark** plan: the design
  needs neither Cloud Storage nor Cloud Functions, so no billing account is required. Note the **project id**.
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
  token under App Check, Apps, Manage debug tokens. Watch App Check's request metrics for a few days, **then** switch Firestore
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
  keeps the free allowance) and set a budget alert. This design uses a handful of small documents per user.
- [ ] **12. Check the texts against the real project.** Once items 1-6 are done, read `docs/accounts/privacy-policy-draft.md`
  once against the Firebase console (the location, what an Authentication user record shows, whether App Check is enforced) and
  fix the draft where it disagrees. Nothing in these drafts has been checked against a real project.
- [ ] **13. Run the real-device checklist.** `docs/accounts/real-device-checklist.md` is NOT RUN. It needs items 1-5 done and a
  phone; the Galaxy Watch part (E2) settles the key-reset spike, which was seen on an emulator only.
- [ ] **14. Tell the agent** (one message): the project id, the Firestore location, whether `google-services.json` may be
  committed, and the two URLs.

Until item 4 is done: `./gradlew :phone:assembleDebug` gives a debug app that signs in through the **Auth emulator**
(`scripts/accounts_e2e.sh` starts the emulators and runs the emulator-backed tests); `:phone:assembleRelease` gives an app with the Account card saying sign-in is not set up.

After item 4 (once `phone/google-services.json` exists) a debug build talks to YOUR project, not the emulator, unless the
marker file `files/use-firebase-emulator` exists in the app. The instrumented tests do not depend on that: their runner
(`AccountsTestRunner`) writes the marker before the app starts, so `connectedDebugAndroidTest` and `scripts/accounts_e2e.sh`
keep using the emulators. To use the emulators by hand with the file present:
`adb shell run-as com.seyedjafari.analogplus sh -c 'echo tester@example.com > files/use-firebase-emulator'`, then restart the app.
