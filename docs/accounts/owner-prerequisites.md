# Accounts and sync: what only the owner can do

Plan: `docs/superpowers/plans/2026-10-09-accounts-sync.md`. Nothing here blocks building or testing: debug builds talk to the
Firebase Emulator Suite on your computer, and a release build without `phone/google-services.json` simply has accounts switched
off. Do these in order; each item says what to hand back to the code.

- [ ] **1. Create the Firebase project.** console.firebase.google.com, Add project, name it "Analog Plus". Turn Google Analytics
  **off** (the app collects nothing else, and the Data Safety form stays short). Stay on the free **Spark** plan: the design
  needs neither Cloud Storage nor Cloud Functions, so no billing account is required. Note the **project id**.
- [ ] **2. Register the Android app.** Project settings, Your apps, Add app, Android. Package name `com.seyedjafari.analogplus`.
  Add the **SHA-1** of every key the app will be signed with (Google sign-in refuses an unknown key):
  - debug: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android | grep -E 'SHA1|SHA256'`
  - release, once the app is on Play: Play Console, your app, Test and release, Setup, App signing: copy the SHA-1 of the
    **App signing key certificate**, and also the SHA-1 of the **Upload key certificate** (for builds you sign yourself).
    (`phone/build.gradle.kts` has no release signing configuration yet; add one before the first release build.)
- [ ] **3. Turn on Google sign-in.** Build, Authentication, Get started, Sign-in method, Google, Enable. Set the public-facing
  name ("Analog Plus") and a support email. This creates the **web OAuth client** the app needs (`default_web_client_id`).
  Add no other provider.
- [ ] **4. Download `google-services.json`** (Project settings, Your apps, the Android app) and save it as
  **`phone/google-services.json`**. Download it again after every SHA-1 you add. It is not a secret, but `.gitignore` keeps it
  out of the repository by default (Task 3); say if you want it committed instead. With the file present, the next build wires
  Firebase in; without it nothing breaks.
- [ ] **5. Create Firestore.** Build, Firestore Database, Create database, **production mode**. Pick the location now; it
  **cannot be changed later**: `eur3` (Europe, multi-region) or `europe-west2` (London) suit a UK-first launch. Then publish the
  rules and indexes from the repository:
  `cd firebase && npm ci && npx firebase login && npx firebase deploy --only firestore --project <project-id>`.
  Put the project id into `firebase/.firebaserc` as a second alias (`"prod": "<project-id>"`).
- [ ] **6. App Check (Play Integrity).** Build, App Check, Apps, register the Android app with the **Play Integrity** provider
  (needs the app in Play Console and the Cloud project linked to it: Play Console, Test and release, App integrity).
  Register a **debug token** for your own debug builds: run a debug build once with the project's `google-services.json`, find the
  log line "Enter this debug secret into the allow list in the Firebase Console" (`adb logcat | grep -i appcheck`), and add the
  token under App Check, Apps, Manage debug tokens. Watch App Check's request metrics for a few days, **then** switch Firestore
  to *Enforced*. Enforcing earlier locks out your own test builds.
- [ ] **7. Google Auth Platform / OAuth consent screen.** console.cloud.google.com, the same project, Google Auth Platform,
  Branding: app name, logo, support email, and the privacy policy link (item 8). Complete **brand verification** so that the
  app name, not a raw project id, shows in the Google account chooser.
- [ ] **8. Privacy policy at a public URL.** Host the text of `docs/accounts/privacy-policy-draft.md` (Task 17 writes it; fill in
  your name and contact email) on any public page. Put the URL in the OAuth consent screen (item 7) and in Play Console, Policy and
  programs, App content, Privacy policy.
- [ ] **9. Play Data Safety form.** Play Console, App content, Data safety. The answers are in the appendix of the privacy policy
  draft. Re-check them whenever the app collects something new.
- [ ] **10. Account deletion page.** Play requires an in-app way **and** a web URL for deleting an account. The in-app way
  exists (Account, Delete account). Host `docs/accounts/account-deletion-page-draft.md` as a public page and enter its URL in
  Play Console, App content, Data deletion.
- [ ] **11. Usage alerts.** Firebase console, Usage and billing: look at the Spark quotas for Firestore and Authentication
  (numbers change, read them there) and set the email alert. This design uses a handful of small documents per user.
- [ ] **12. Tell the agent** (one message): the project id, the Firestore location, whether `google-services.json` may be
  committed, and the two URLs.

Until item 4 is done: `./gradlew :phone:assembleDebug` gives a debug app that signs in through the **Auth emulator**
(`scripts/accounts_e2e.sh` starts it); `:phone:assembleRelease` gives an app with the Account card saying sign-in is not set up.
