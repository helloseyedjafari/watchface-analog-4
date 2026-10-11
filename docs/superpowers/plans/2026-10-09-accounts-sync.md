# Accounts, Sync and Signing-Key Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner's customers sign in with Google (optionally), have their saved designs and current design follow them to other phones, and keep the face-signing key in the cloud encrypted under a recovery passphrase, so a replaced phone can keep updating the face already on the watch; everything keeps working signed out and in a build that has no Firebase project yet.

**Architecture:** The local `Store` stays the source of truth. Two small hooks leave it: a `StoreObserver` interface and a `SyncJournal` that records edits and deletions even while signed out. A pure `planSync` decides per design (last write wins, tombstones, keep-both for histories that never met), a `SyncEngine` carries the decisions out against a `RemoteStore` interface, and `FirestoreRemoteStore` is the only code that knows Firestore paths. `KeyVault` (PBKDF2-HMAC-SHA256 + AES-256-GCM, pure JVM) seals the signing key; `BackupService` uploads it, reads it back and opens it as a self-test, and restores it with an attempt delay. `AccountsController` joins `AccountManager`, `CloudSync` and `BackupService` for the UI. All Firebase types live in `accounts/firebase`, `AccountsFactory` and two build-type files; without `google-services.json` the plugin is not applied and the app runs with accounts off. Debug builds without the file (or with a marker file) talk to the Firebase Emulator Suite, which also gives the phone emulators a sign-in.

**Tech Stack:** Kotlin 2.0.21, Compose BOM 2024.10.01 (unchanged), AGP 8.9.1 (unchanged), Firebase BoM 34.19.0 (Auth 24.2.0, Firestore 26.6.0, App Check Play Integrity and Debug 19.4.1), Google services plugin 4.5.0, AndroidX Credentials 1.6.0 with `credentials-play-services-auth` 1.6.0, Google ID library `googleid` 1.1.1, JUnit4, kotlinx-coroutines-test 1.9.0, Compose UI test, Node 20+ with `@firebase/rules-unit-testing` 5.0.0, `firebase` 12.19.0 and `firebase-tools` 15.30.0 for the rules tests.

**Spec:** `docs/superpowers/specs/2026-10-09-accounts-sync-design.md` (parents: `2026-10-08-builder-app-design.md`, section "Key loss"). Builds on `docs/superpowers/plans/2026-10-09-app-shell.md` (its `AccountScreen`, `SectionCard`, `PrimaryButton`, `SecondaryButton`, `StatusPill`, `StatusKind`, `ErrorBanner`, `Spacing`, `AnalogTheme`, `AppShell`) and plugs into `docs/superpowers/plans/2026-10-09-onboarding.md` (its `BackupPageSlot`, `OnboardingHost`, `OnboardingConfig.accountsAvailable`). Implement both of those first; Task 15 needs the onboarding plan merged, every other task needs only the app-shell plan (Tasks 1-13 need not even that).

## How this plan was checked while it was written

The code in this plan is not pasted from memory: it was written and run in a scratch copy of the repository, then copied here. What ran, and where:

- JVM tests of every pure class below (KeyVault, SyncResolver, SyncEngine with a fake cloud, BackupService, AccountsController, ...) and the unchanged `StoreTest`: all green.
- The Firestore rules and their 16 tests, on the Firestore emulator; three rules were deliberately weakened to confirm the tests catch it.
- `FirebaseEmulatorTest`, `PhotoDownscalerTest`, `KeyStoreInstallTest`, the Account UI tests and the phone-replacement end-to-end test on the `1_Pixel_8_API_33` emulator against the Auth and Firestore emulators: green. The same non-UI tests plus the whole existing POC instrumented suite were run inside a copy of this repository with the Gradle edits of Task 3 applied: green; `:phone:assembleDebug` and `:phone:assembleRelease` build with no `google-services.json`; a face was built and validated on the emulator afterwards.
- Problems that only showed up this way, now handled in the tasks: `googleid` 1.2.1 does not compile on Kotlin 2.0.21, `firebase-auth` needs `-Xskip-metadata-version-check`, the Google face validator and Firestore bring two clashing protobuf runtimes, the Auth emulator needs a cleartext exception, and a phone that stays signed in re-uploaded designs after another phone deleted the account.

**Not verified (no way to, here):** real Google sign-in and Credential Manager; a real Firebase project; App Check with Play Integrity; the Compose screens against the real app-shell components (the scratch copy used stand-ins with the same signatures; run the UI tests in Tasks 14-16 first thing); the onboarding wiring (the onboarding code did not exist yet); the outcome of the reset spike (Task 13); anything on the owner's Samsung watch.

## Global Constraints

- Phone `minSdk` 28, `compileSdk` 36, `targetSdk` 35, Java 17, Kotlin 2.0.21, AGP 8.9.1 (as in `phone/build.gradle.kts`). Package root `com.seyedjafari.analogplus.phone`; new logic in `...phone.accounts` (Firebase only in `...phone.accounts.firebase`), new UI in `...phone.ui.account`.
- **No Firebase import outside `accounts/firebase`, `AccountsFactory.kt` and the two `FirebaseBuildType.kt` files.** `Store`, `SendController`, `facegen` and every composable know nothing about it.
- Local-first: signed out, offline, or in a build without Firebase, everything that works today still works, and nothing waits on the network.
- The plain signing-key PEM never leaves the device and is never logged; the recovery passphrase is never stored or sent (not in `rememberSaveable`, not in `SharedPreferences`, not in logs). Cloud copy: PBKDF2-HMAC-SHA256, 600 000 iterations, AES-256-GCM (Task 4).
- Keep `android:allowBackup="false"` and both backup-exclusion XML files untouched: our own encrypted upload is the backup path.
- Never `removeWatchFace`; the face version counter (`Store.nextVersionCode`) is not synced; `versionCode` strictly increases.
- Firestore: a user reads and writes only `users/{their uid}/**`; a design document stays under 900 000 bytes of JSON (limit 1 MiB); photos are shrunk to a 512 px longest side and kept inline (no Cloud Storage, no Blaze plan).
- Never model three or more states as a boolean: this plan uses enums and sealed types (`SyncMark`, `EntryState`, `DesignState`, `KeyMatch`, `ChangeOrigin`, `ResetPath`, `Busy`, ...). Two-state booleans stay booleans.
- All text is plain; no raw colours, text sizes or dp literals in screens (use `MaterialTheme`, `Spacing`); touch targets at least 48 dp; every interactive node has a test tag from `AccountTags`.
- Existing tests (`StoreTest`, `SendControllerTest`, `FieldHelpersTest`, ... and the instrumented POC suite) pass without edits.
- Never `git push`. Commit locally; no Claude attribution or co-author lines in commit messages. Docs-only tasks may commit straight to `main`; tasks that touch code run in a worktree and merge to `main` when green.
- Gradle file watching misses edits under `/private/tmp`: check the APK is newer than the source.

## Review Focus

1. **A phone that stays signed in re-uploads designs after another phone deleted the account's data.** Expected: the deletion sticks and local designs stay. Tested in Task 7 (`aSyncedUntouchedDesignThatVanishedFromTheCloudIsNotWrittenBack`), Task 8 (`aPhoneStillSignedInDoesNotResurrectDataThatAnotherPhoneDeleted`) and Task 16 (account deletion in the end-to-end run).
2. **Offline, or a write that never gets an answer.** Expected: local work is never lost or blocked, status says "Offline, will retry", the change is pushed after the network returns. Tested in Task 8 (offline and retry, edits made while signed out) and Task 11 (write timeout becomes `OFFLINE`).
3. **Wrong passphrase, repeated guesses, and a restore over a key the phone already has.** Expected: a typed result, a growing delay, a confirmation naming both fingerprints before anything is replaced, the old key kept as a copy, and never a half-installed key. Tested in Tasks 4, 6 and 9.
4. **A huge photo.** Expected: the cloud copy is a small JPEG inside a document well under 1 MiB, or the design is reported as too large; never a silent failure. Tested in Task 7 (JVM size guard; instrumented 4000x3000 noise photo).
5. **Signing in on a phone that already has designs, or with a different Google account.** Expected: nothing is lost; same-name differing designs are kept both ways; a new account receives this phone's designs; the first sign-in on an empty phone restores without asking, otherwise the person is asked once per difference. Tested in Task 8.

---

## File structure

Create (paths under `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/` unless noted):

| File | Responsibility |
|---|---|
| `accounts/vault/SealedKey.kt` | The encrypted key plus its parameters; Firestore field (de)serialisation |
| `accounts/vault/KeyVault.kt` | `seal` / `open` (PBKDF2-HMAC-SHA256 + AES-256-GCM), typed `OpenResult` |
| `accounts/vault/PassphraseCheck.kt` | Strength estimate and the acceptance rule |
| `accounts/RemoteStore.kt` | `RemoteStore` interface, remote document types, `RemoteException`, error mapping |
| `accounts/DesignMapper.kt` | `designId`, design JSON for Firestore, photo downscale contract, size guard |
| `accounts/AndroidPhotoDownscaler.kt` | The Android photo shrinker |
| `accounts/SyncJournal.kt` | Durable record of local edits (a `StoreObserver`) |
| `accounts/SyncResolver.kt` | Pure per-design decision logic (`planSync`) |
| `accounts/CloudSync.kt` | `CloudSync`, `SyncStatus`, `CurrentOffer`, `CurrentApplier`, `NoCloudSync` |
| `accounts/SyncEngine.kt` | Carries the decisions out; debounce; listeners; the current-design offer |
| `accounts/KeySource.kt` | The signing key as the backup code sees it |
| `accounts/AttemptLimiter.kt` | Restore attempt delay |
| `accounts/BackupService.kt` | Upload with read-back self-test, restore plan, restore, discard |
| `accounts/AccountManager.kt` | `AccountManager`, `AccountState`, results, `UnavailableAccountManager` |
| `accounts/AccountDeletion.kt` | Re-auth, stop sync, delete cloud data, delete the account |
| `accounts/AccountsController.kt` | What the UI talks to; `AccountsUi`; the "unavailable" variant |
| `accounts/KeyResetCopy.kt` | The forgotten-passphrase texts, gated by the spike |
| `accounts/AccountsFactory.kt` | Builds the stack for the app |
| `accounts/firebase/FirebaseProvider.kt` | Which Firebase this process uses (none / production / emulator) |
| `accounts/firebase/FirestoreRemoteStore.kt` | `RemoteStore` on Firestore |
| `accounts/firebase/FirebaseAccountManager.kt` | `AccountManager` on Firebase Auth |
| `accounts/firebase/IdTokenSource.kt` | Google ID token via Credential Manager, or the emulator's fake |
| `accounts/firebase/CurrentActivity.kt` | The visible activity for the Google chooser |
| `ui/account/AccountTags.kt`, `AccountText.kt`, `LocalAccounts.kt` | Tags, pure UI text mapping, the `CompositionLocal` |
| `ui/account/PassphraseFields.kt`, `AccountDialogs.kt` | Passphrase entry and the dialogs |
| `ui/account/CloudSection.kt` | `AccountSection`: the "Backup and sync" card |
| `ui/account/BackupPage.kt` | The onboarding backup page |

Create elsewhere: `phone/src/debug/kotlin/.../accounts/firebase/FirebaseBuildType.kt`, `phone/src/release/kotlin/.../accounts/firebase/FirebaseBuildType.kt`, `phone/src/debug/res/xml/debug_network_security.xml`, `phone/src/sharedTest/kotlin/.../accounts/{FakeRemoteStore,MemoryKeyValueStore,FakeKeySource,FakeAccountManager,FakeCloudSync}.kt`, `firebase/{package.json,.firebaserc,.gitignore,firebase.json,firestore.rules,firestore.indexes.json,tests/rules.test.js}`, `scripts/accounts_e2e.sh`, `docs/accounts/*.md`, `docs/poc/key-reset-spike.md`. Tests are listed in each task.

Modify: `Store.kt` (observer hook), `facegen/.../KeyStore.kt` (`installPem`), `MainActivity.kt` (`AppModel.accounts`, `LocalAccounts`, `CurrentActivity`), `ui/account/AccountScreen.kt` (placeholder card becomes `AccountSection()`), `OnboardingState.kt` (Task 15: the flag), `phone/build.gradle.kts`, root `build.gradle.kts`, both `AndroidManifest.xml` files, `.gitignore`, `.github/workflows/ci.yml`, `docs/poc/README.md`, `CLAUDE.md`, the spec (done with this plan: navigation wording).

---

### Task 1: Owner prerequisites checklist

Docs only; commit straight to `main`. Everything the owner must do in consoles, in the order that works, and what the code needs back. The rest of this plan does not wait for it: debug builds use the emulators, release builds without `google-services.json` run with accounts off.

**Files:**
- Create: `docs/accounts/owner-prerequisites.md`

**Interfaces:**
- Produces: the file name `phone/google-services.json` (read by Gradle in Task 3), the Firebase project id (for `firebase/.firebaserc`, Task 2) and the two public URLs (privacy policy, account deletion page) that Task 17 drafts the text for.

- [ ] **Step 1: Write the checklist**

Create `docs/accounts/owner-prerequisites.md`:

````markdown
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
````

- [ ] **Step 2: Check it is complete**

Run: `grep -c '^- \[ \] \*\*' docs/accounts/owner-prerequisites.md`
Expected: `12`

- [ ] **Step 3: Commit (docs, straight to `main`)**

```bash
git add docs/accounts/owner-prerequisites.md
git commit -m "docs(accounts): owner prerequisites checklist for Firebase, Play and privacy"
```

---

### Task 2: Firestore rules, emulator config and rules tests

**Files:**
- Create: `firebase/package.json`, `firebase/.firebaserc`, `firebase/.gitignore`, `firebase/firebase.json`, `firebase/firestore.indexes.json`, `firebase/firestore.rules`, `firebase/tests/rules.test.js`, `firebase/package-lock.json` (generated)
- Modify: `.github/workflows/ci.yml` (new job)

**Interfaces:**
- Produces: the Firestore document shapes every later task writes (the Kotlin field names in Task 11 match these rules exactly):
  - `users/{uid}/designs/{id}`: `name` (1-100 chars), `design` (string, 0-950 000 chars; empty only when `deleted`), `updatedAt` (server timestamp), `deleted` (bool), `schema` (int 1-1000); `id` is 32 lowercase hex characters.
  - `users/{uid}/state/current`: `design` (1-950 000 chars), `updatedAt`, `deviceId` (1-64), `deviceLabel` (0-80), `schema`.
  - `users/{uid}/backup/signingKey`: `sealed` (1-16 384 chars), `kdf` (`"pbkdf2-hmac-sha256"`), `iterations` (int 100 000-5 000 000), `salt` (16-128 chars), `iv` (16-64 chars), `version` (int 1-1000), `createdAt` (server timestamp), `keyFingerprint` (exactly 16 chars).
  - Everything else, including every other top-level path, is denied. Rules cannot count documents, so the 200-design cap (`MAX_SYNCED_DESIGNS`, Task 7) is enforced by the app.
- Produces: `npm test` in `firebase/` (starts the Firestore emulator, runs the tests) and `npm run emulators` (Auth 9099 + Firestore 8080, rules loaded), used by Tasks 11 and 16.

- [ ] **Step 1: Write the project files, the tests and a deny-everything rules file**

`firebase/package.json`:

```json
{
  "name": "analogplus-firebase",
  "private": true,
  "description": "Firestore rules, indexes and rules tests for Analog Plus accounts and sync",
  "type": "module",
  "scripts": {
    "test": "firebase emulators:exec --only firestore --project demo-analogplus \"node --test tests/*.test.js\"",
    "emulators": "firebase emulators:start --only auth,firestore --project demo-analogplus"
  },
  "devDependencies": {
    "@firebase/rules-unit-testing": "5.0.0",
    "firebase": "12.19.0",
    "firebase-tools": "15.30.0"
  }
}
```

`firebase/firebase.json`:

```json
{
  "firestore": {
    "rules": "firestore.rules",
    "indexes": "firestore.indexes.json"
  },
  "emulators": {
    "auth": { "port": 9099 },
    "firestore": { "port": 8080 },
    "ui": { "enabled": false },
    "singleProjectMode": true
  }
}
```

`firebase/firestore.indexes.json`:

```json
{
  "indexes": [],
  "fieldOverrides": []
}
```

`firebase/.firebaserc`:

```json
{
  "projects": {
    "default": "demo-analogplus"
  }
}
```

`firebase/.gitignore`:

```
node_modules/
*.log
firestore-debug.log
ui-debug.log
firebase-debug.log
```

`firebase/tests/rules.test.js`:

```javascript
import { readFileSync } from 'node:fs';
import { after, before, beforeEach, describe, it } from 'node:test';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, Timestamp } from 'firebase/firestore';

const PROJECT = 'demo-analogplus';
const ID = '0123456789abcdef0123456789abcdef';
let env;

const design = (over = {}) => ({
  name: 'Mine', design: '{"name":"Mine"}', updatedAt: serverTimestamp(), deleted: false, schema: 1, ...over,
});
const current = (over = {}) => ({
  design: '{"name":"Mine"}', updatedAt: serverTimestamp(), deviceId: 'dev-1', deviceLabel: 'Pixel 8', schema: 1, ...over,
});
const backup = (over = {}) => ({
  sealed: 'AAAA', kdf: 'pbkdf2-hmac-sha256', iterations: 600000, salt: 'c2FsdHNhbHRzYWx0c2FsdA==',
  iv: 'aXZpdml2aXZpdml2', version: 1, createdAt: serverTimestamp(), keyFingerprint: '0123456789abcdef', ...over,
});

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT,
    firestore: { rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8') },
  });
});
after(async () => { await env.cleanup(); });
beforeEach(async () => { await env.clearFirestore(); });

const alice = () => env.authenticatedContext('alice').firestore();
const bob = () => env.authenticatedContext('bob').firestore();
const anon = () => env.unauthenticatedContext().firestore();

describe('designs', () => {
  it('lets the owner create, read, update and delete', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertSucceeds(setDoc(ref, design()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(setDoc(ref, design({ name: 'Renamed' })));
    await assertSucceeds(deleteDoc(ref));
  });
  it('accepts a tombstone with an empty design', async () => {
    await assertSucceeds(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: '', deleted: true })));
  });
  it('rejects a live design with an empty design string', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: '' })));
  });
  it('denies another signed-in user, reading and writing', async () => {
    await env.withSecurityRulesDisabled((c) => setDoc(doc(c.firestore(), `users/alice/designs/${ID}`), { name: 'x', design: '{}', updatedAt: Timestamp.now(), deleted: false, schema: 1 }));
    await assertFails(getDoc(doc(bob(), `users/alice/designs/${ID}`)));
    await assertFails(setDoc(doc(bob(), `users/alice/designs/${ID}`), design()));
    await assertFails(deleteDoc(doc(bob(), `users/alice/designs/${ID}`)));
  });
  it('denies listing another user\'s designs', async () => {
    await assertFails(getDocs(collection(bob(), 'users/alice/designs')));
    await assertSucceeds(getDocs(collection(alice(), 'users/alice/designs')));
  });
  it('denies unauthenticated access', async () => {
    await assertFails(getDoc(doc(anon(), `users/alice/designs/${ID}`)));
    await assertFails(setDoc(doc(anon(), `users/alice/designs/${ID}`), design()));
  });
  it('rejects a client-supplied updatedAt', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ updatedAt: Timestamp.fromMillis(1) })));
  });
  it('rejects an oversized design string', async () => {
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: 'x'.repeat(950001) })));
    await assertSucceeds(setDoc(doc(alice(), `users/alice/designs/${ID}`), design({ design: 'x'.repeat(900000) })));
  });
  it('rejects malformed documents', async () => {
    const ref = doc(alice(), `users/alice/designs/${ID}`);
    await assertFails(setDoc(ref, design({ extra: 1 })));
    await assertFails(setDoc(ref, design({ name: '' })));
    await assertFails(setDoc(ref, design({ name: 'n'.repeat(101) })));
    await assertFails(setDoc(ref, design({ schema: '1' })));
    await assertFails(setDoc(ref, design({ schema: 0 })));
    await assertFails(setDoc(ref, design({ deleted: 'no' })));
    const { name, ...missing } = design();
    await assertFails(setDoc(ref, missing));
  });
  it('rejects a design id that is not 32 lowercase hex characters', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/designs/not-an-id'), design()));
    await assertFails(setDoc(doc(alice(), `users/alice/designs/${ID.toUpperCase()}`), design()));
  });
});

describe('current design', () => {
  it('lets the owner write and read state/current only', async () => {
    const ref = doc(alice(), 'users/alice/state/current');
    await assertSucceeds(setDoc(ref, current()));
    await assertSucceeds(getDoc(ref));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/other'), current()));
  });
  it('rejects bad fields and other users', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ deviceId: '' })));
    await assertFails(setDoc(doc(alice(), 'users/alice/state/current'), current({ design: 'x'.repeat(950001) })));
    await assertFails(setDoc(doc(bob(), 'users/alice/state/current'), current()));
  });
});

describe('signing key backup', () => {
  it('lets the owner write and read backup/signingKey only', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertSucceeds(setDoc(ref, backup()));
    await assertSucceeds(getDoc(ref));
    await assertSucceeds(deleteDoc(ref));
    await assertFails(setDoc(doc(alice(), 'users/alice/backup/second'), backup()));
  });
  it('denies other users and anonymous callers', async () => {
    await assertFails(setDoc(doc(bob(), 'users/alice/backup/signingKey'), backup()));
    await assertFails(getDoc(doc(anon(), 'users/alice/backup/signingKey')));
  });
  it('rejects weak or unknown key derivation and malformed fields', async () => {
    const ref = doc(alice(), 'users/alice/backup/signingKey');
    await assertFails(setDoc(ref, backup({ iterations: 1000 })));
    await assertFails(setDoc(ref, backup({ iterations: 6000000 })));
    await assertFails(setDoc(ref, backup({ kdf: 'md5' })));
    await assertFails(setDoc(ref, backup({ keyFingerprint: 'short' })));
    await assertFails(setDoc(ref, backup({ sealed: 'A'.repeat(16385) })));
    await assertFails(setDoc(ref, backup({ createdAt: Timestamp.fromMillis(1) })));
    await assertFails(setDoc(ref, backup({ extra: true })));
  });
});

describe('everything else', () => {
  it('denies the user document itself and unknown collections', async () => {
    await assertFails(setDoc(doc(alice(), 'users/alice'), { a: 1 }));
    await assertFails(setDoc(doc(alice(), 'users/alice/other/x'), { a: 1 }));
    await assertFails(setDoc(doc(alice(), 'public/x'), { a: 1 }));
    await assertFails(getDoc(doc(alice(), 'public/x')));
    await assertFails(getDocs(collection(alice(), 'users')));
  });
});
```

Start with a rules file that denies everything, so the tests can fail first. `firebase/firestore.rules`:

```
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /{document=**} { allow read, write: if false; }
  }
}
```

- [ ] **Step 2: Run to verify the owner tests fail**

Run: `cd firebase && npm install && npm test`
Expected: the emulator downloads once (needs Java 21 and network), then FAIL: the "lets the owner ..." and "accepts a tombstone" style tests fail because everything is denied; the "denies ..." tests pass. The run ends with a non-zero exit. (`npm install` also writes `package-lock.json`.)

- [ ] **Step 3: Write the real rules**

`firebase/firestore.rules`:

```
rules_version = '2';

// Analog Plus: each signed-in user reads and writes only users/{their uid}/**.
// Three document shapes exist; anything else is denied, including every other top-level path.
service cloud.firestore {
  match /databases/{database}/documents {

    function isOwner(uid) {
      return request.auth != null && request.auth.uid == uid;
    }

    function isText(v, minLen, maxLen) {
      return v is string && v.size() >= minLen && v.size() <= maxLen;
    }

    // `updatedAt` must be the server's clock: a client cannot forge last-write-wins ordering.
    function stampedNow(data) {
      return data.updatedAt is timestamp && data.updatedAt == request.time;
    }

    function validDesign(data) {
      return data.keys().hasOnly(['name', 'design', 'updatedAt', 'deleted', 'schema'])
        && data.keys().hasAll(['name', 'design', 'updatedAt', 'deleted', 'schema'])
        && isText(data.name, 1, 100)
        && isText(data.design, 0, 950000)
        && data.deleted is bool
        && data.schema is int && data.schema >= 1 && data.schema <= 1000
        && stampedNow(data)
        // a live design carries its JSON; only a tombstone may have an empty one
        && (data.deleted || data.design.size() > 0);
    }

    function validCurrent(data) {
      return data.keys().hasOnly(['design', 'updatedAt', 'deviceId', 'deviceLabel', 'schema'])
        && data.keys().hasAll(['design', 'updatedAt', 'deviceId', 'deviceLabel', 'schema'])
        && isText(data.design, 1, 950000)
        && isText(data.deviceId, 1, 64)
        && isText(data.deviceLabel, 0, 80)
        && data.schema is int && data.schema >= 1 && data.schema <= 1000
        && stampedNow(data);
    }

    function validBackup(data) {
      return data.keys().hasOnly(['sealed', 'kdf', 'iterations', 'salt', 'iv', 'version', 'createdAt', 'keyFingerprint'])
        && data.keys().hasAll(['sealed', 'kdf', 'iterations', 'salt', 'iv', 'version', 'createdAt', 'keyFingerprint'])
        && isText(data.sealed, 1, 16384)
        && data.kdf in ['pbkdf2-hmac-sha256']
        && data.iterations is int && data.iterations >= 100000 && data.iterations <= 5000000
        && isText(data.salt, 16, 128)
        && isText(data.iv, 16, 64)
        && data.version is int && data.version >= 1 && data.version <= 1000
        && isText(data.keyFingerprint, 16, 16)
        && data.createdAt is timestamp && data.createdAt == request.time;
    }

    match /users/{uid}/designs/{designId} {
      allow read, delete: if isOwner(uid);
      allow create, update: if isOwner(uid)
        && designId.matches('[0-9a-f]{32}')
        && validDesign(request.resource.data);
    }

    match /users/{uid}/state/{docId} {
      allow read, delete: if isOwner(uid);
      allow create, update: if isOwner(uid)
        && docId == 'current'
        && validCurrent(request.resource.data);
    }

    match /users/{uid}/backup/{docId} {
      allow read, delete: if isOwner(uid);
      allow create, update: if isOwner(uid)
        && docId == 'signingKey'
        && validBackup(request.resource.data);
    }
  }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd firebase && npm test`
Expected: `tests 16`, `pass 16`, `fail 0`. (The `PERMISSION_DENIED` lines in the output are the expected denials.)

- [ ] **Step 5: Prove the tests bite**

Temporarily weaken three conditions and expect exactly three failing tests:

```bash
cd firebase && cp firestore.rules /tmp/rules.orig
sed -i.bak "s/return data.updatedAt is timestamp \&\& data.updatedAt == request.time;/return data.updatedAt is timestamp;/; s/designId.matches('\[0-9a-f\]{32}')/true/; s/isText(data.design, 0, 950000)/isText(data.design, 0, 9500000)/" firestore.rules
npm test 2>&1 | grep -E "^ℹ (pass|fail)"
cp /tmp/rules.orig firestore.rules && rm -f firestore.rules.bak && npm test 2>&1 | grep -E "^ℹ (pass|fail)"
```

Expected: first run `pass 13`, `fail 3`; second run `pass 16`, `fail 0`.

- [ ] **Step 6: Run the rules tests in CI**

Append this job to `.github/workflows/ci.yml` (it is a sibling of `test:` under `jobs:`; do not touch the existing job):

```bash
cat >> .github/workflows/ci.yml <<'YML'

  firestore-rules:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Node
        uses: actions/setup-node@v4
        with:
          node-version: '22'
          cache: npm
          cache-dependency-path: firebase/package-lock.json

      - name: Set up JDK 21 (the Firestore emulator needs it)
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Firestore rules tests (emulator)
        working-directory: firebase
        run: |
          npm ci
          npm test
YML
python3 -c "import yaml; print(sorted(yaml.safe_load(open('.github/workflows/ci.yml'))['jobs']))"
```

Expected: `['firestore-rules', 'test']`. (If PyYAML is missing, `pip3 install pyyaml` first, or read the file once.)

- [ ] **Step 7: Commit**

```bash
git add firebase .github/workflows/ci.yml
git commit -m "feat(firebase): Firestore rules, emulator config, rules tests and a CI job"
```

---

### Task 3: Build wiring that works with and without `google-services.json`

Adds the Firebase and Credential Manager dependencies, applies the Google services plugin only when the file exists, resolves the protobuf clash, opens the debug-only cleartext door to the emulators, and adds the shared test source set. No Kotlin code changes yet.

**Files:**
- Modify: `build.gradle.kts`, `phone/build.gradle.kts`, `phone/src/main/AndroidManifest.xml`, `phone/src/debug/AndroidManifest.xml`, `.gitignore`
- Create: `phone/src/debug/res/xml/debug_network_security.xml`

**Interfaces:**
- Produces: the dependency set Tasks 11 uses; the `src/sharedTest/kotlin` directory that both `test` and `androidTest` read (fakes live there, Tasks 5-10); the rule "no `phone/google-services.json`, no Firebase app".
- Pins (verified on Google Maven and in the Firebase release notes on 2026-10-09): BoM **34.19.0** (the newest 34.x; BoM 35.0.0 appeared two days earlier with major bumps and is left for later), `googleid` **1.1.1** (1.2.1 is compiled with Kotlin 2.4 metadata and does not compile on Kotlin 2.0.21), `androidx.credentials` **1.6.0**, Google services plugin **4.5.0**.

- [ ] **Step 1: Record the baseline**

Run: `./gradlew :phone:assembleDebug :phone:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL (this is the state the next steps must keep).

- [ ] **Step 2: Apply the Gradle and manifest edits**

In `build.gradle.kts`, replace:

```kotlin
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
```

with:

```kotlin
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
```

In `phone/build.gradle.kts`, replace:

```kotlin
    id("org.jetbrains.kotlin.plugin.compose")
}
```

with:

```kotlin
    id("org.jetbrains.kotlin.plugin.compose")
}

// Firebase is wired only when the owner has put google-services.json here (see docs/accounts/owner-prerequisites.md).
// Without it the plugin is not applied, no FirebaseApp exists, and the app builds and runs with accounts switched off.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")
```

In `phone/build.gradle.kts`, replace:

```kotlin
    kotlinOptions { jvmTarget = "17" }
```

with:

```kotlin
    // firebase-auth 24.x is compiled with Kotlin 2.3 metadata; this project builds with Kotlin 2.0.21, which would refuse to
    // read it. The flag only skips that version check (we call the Java API); remove it when Kotlin is upgraded.
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xskip-metadata-version-check" }
```

In `phone/build.gradle.kts`, replace:

```kotlin
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("androidTest").java.srcDir("src/androidTest/kotlin")
```

with:

```kotlin
        getByName("test").java.srcDir("src/test/kotlin")
        getByName("androidTest").java.srcDir("src/androidTest/kotlin")
        // fakes (FakeRemoteStore, FakeAccountManager, ...) shared by the JVM and the instrumented tests
        getByName("test").java.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").java.srcDir("src/sharedTest/kotlin")
```

In `phone/build.gradle.kts`, replace:

```kotlin
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
```

with:

```kotlin
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // accounts and sync (docs/superpowers/plans/2026-10-09-accounts-sync.md)
    implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
```

In `phone/build.gradle.kts`, replace:

```kotlin

dependencies {
```

with:

```kotlin

// Firebase brings the lite protobuf runtime (protobuf-javalite + protolite-well-known-types) and the face validator brings the
// full one (protobuf-java, via aapt2-proto). They hold the same classes, so the build fails on duplicates. Keep the lite runtime:
// the validator was checked to run with it (DebugBuildReceiver builds and validates a face).
// Only the app's own classpaths: the Android test tooling (UTP) needs protobuf-java on its classpath.
configurations.matching { it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "com.google.protobuf", module = "protobuf-java")
}

dependencies {
```

In `phone/src/main/AndroidManifest.xml`, replace:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
```

with:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
```

In `phone/src/debug/AndroidManifest.xml`, replace:

```xml
    <application>
```

with:

```xml
    <application android:networkSecurityConfig="@xml/debug_network_security">
```

Create `phone/src/debug/res/xml/debug_network_security.xml`:

`phone/src/debug/res/xml/debug_network_security.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Debug builds only: the Firebase Emulator Suite on the host is plain HTTP at 10.0.2.2. -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">10.0.2.2</domain>
    </domain-config>
</network-security-config>
```

Append to `.gitignore`:

```
# Firebase client config (not a secret, but kept out of a possibly public history; see docs/accounts/owner-prerequisites.md)
phone/google-services.json
```

Why each edit exists: the Google services plugin is declared (`apply false`) in the root file and applied in `phone/build.gradle.kts` only when the JSON is present, so a checkout without it builds. `-Xskip-metadata-version-check` is needed because `firebase-auth` 24.x ships Kotlin 2.3 metadata and this project compiles with Kotlin 2.0.21; it only skips the version check (we call the Java API). The protobuf exclusion is needed because Firestore brings the *lite* protobuf runtime while the Google face validator (`aapt2-proto`) brings the *full* one; they contain the same classes and the build stops on duplicates. It is limited to the app's own classpaths because Android's test tooling (UTP) needs `protobuf-java` on its own. `INTERNET` is not declared by the app today. The debug network config lets debug builds reach the Firebase emulators, which are plain HTTP at `10.0.2.2`.

- [ ] **Step 3: Build with no `google-services.json`**

Run: `ls phone/google-services.json; ./gradlew :phone:assembleDebug :phone:assembleRelease :phone:testDebugUnitTest :app:assembleDebug`
Expected: `ls` reports no such file; BUILD SUCCESSFUL for all four; existing JVM tests pass unedited. If `checkDebugDuplicateClasses` lists `com.google.protobuf` classes, the exclusion in `phone/build.gradle.kts` is missing or misspelled.

- [ ] **Step 4: Check that a face still builds and validates with the changed classpath**

Run (phone emulator up, see `docs/poc/README.md`; the debug receiver builds with the real packager and validator):

```bash
adb -s emulator-5562 install -r -t phone/build/outputs/apk/debug/phone-debug.apk
adb -s emulator-5562 shell am broadcast -n com.seyedjafari.analogplus/com.seyedjafari.analogplus.phone.DebugBuildReceiver -a com.seyedjafari.analogplus.DEBUG_BUILD --es template Classic | tail -1
```

Expected: `data="AnalogPlusBuild ok version=... apk=..."`. A `NoClassDefFoundError` for `com.google.protobuf...` here means the validator needs the full runtime; stop and escalate (do not paper over it).

- [ ] **Step 5: Check that the plugin applies when the file is present**

```bash
cat > phone/google-services.json <<'JSON'
{"project_info":{"project_number":"123456789012","project_id":"demo-analogplus"},
 "client":[{"client_info":{"mobilesdk_app_id":"1:123456789012:android:0123456789abcdef","android_client_info":{"package_name":"com.seyedjafari.analogplus"}},
 "oauth_client":[{"client_id":"123456789012-web.apps.googleusercontent.com","client_type":3}],
 "api_key":[{"current_key":"AIzaFakeKeyForLocalBuildOnly000000000000000"}],
 "services":{"appinvite_service":{"other_platform_oauth_client":[{"client_id":"123456789012-web.apps.googleusercontent.com","client_type":3}]}}}],
 "configuration_version":"1"}
JSON
./gradlew :phone:assembleDebug -q
"$ANDROID_HOME/build-tools/$(ls "$ANDROID_HOME/build-tools" | tail -1)/aapt2" dump resources phone/build/outputs/apk/debug/phone-debug.apk | grep -E "default_web_client_id|google_app_id"
rm phone/google-services.json
```

Expected: two lines, `string/default_web_client_id` and `string/google_app_id`. The file is removed again (it is git-ignored, but a fake key must not linger in a build you install).

- [ ] **Step 6: Commit**

```bash
git add build.gradle.kts phone/build.gradle.kts phone/src/main/AndroidManifest.xml phone/src/debug .gitignore
git commit -m "build(phone): Firebase and Credential Manager dependencies, google-services only when configured, shared test sources"
```

---

### Task 4: KeyVault, SealedKey and the passphrase rule

Pure Kotlin, no Firebase, no Android classes except through `javax.crypto` (present on the JVM and on Android API 26+).

**Files:**
- Create: `accounts/vault/SealedKey.kt`, `accounts/vault/KeyVault.kt`, `accounts/vault/PassphraseCheck.kt`
- Test: `phone/src/test/kotlin/.../accounts/vault/KeyVaultTest.kt`, `.../vault/PassphraseCheckTest.kt` (the on-device cost check is in Task 9)

**Interfaces:**
- Produces:

```kotlin
class SealedKey(val version: Int, val kdf: String, val iterations: Int, val salt: ByteArray, val iv: ByteArray, val ciphertext: ByteArray, val keyFingerprint: String) {
    fun toFields(): Map<String, Any>                                   // sealed, kdf, iterations(Long), salt, iv, version(Long), keyFingerprint; createdAt is the server's
    companion object { fun fromFields(fields: Map<String, Any?>): SealedKey }   // throws SealedKeyFormatException
}
sealed interface OpenResult { data class Opened(val pem: String); data object WrongPassphraseOrDamaged; data class Unsupported(val reason: String) }
class KeyVault(random: SecureRandom = SecureRandom(), iterations: Int = 600_000, minAcceptedIterations: Int = 100_000, maxAcceptedIterations: Int = 5_000_000) {
    fun seal(pem: String, passphrase: String, keyFingerprint: String): SealedKey
    fun open(sealed: SealedKey, passphrase: String): OpenResult
}
enum class PassphraseStrength { TOO_SHORT, WEAK, GOOD, STRONG }
fun checkPassphrase(passphrase: String): PassphraseStrength
fun PassphraseStrength.isAcceptable(): Boolean          // GOOD or better
const val MIN_PASSPHRASE_LENGTH = 12
```

Decisions (the spec left the KDF open): **PBKDF2-HMAC-SHA256, 600 000 iterations.** It ships with the JDK and Android (no third-party native code in the path that guards the signing key), and 600 000 is the OWASP Password Storage Cheat Sheet figure for it. Measured on the Pixel 8 API 33 emulator: about 1.1 s to seal and 1.0 s to open. Argon2id is stronger against GPUs, but Android has no platform implementation and no Android library was vetted for this plan, so it is not used; `version` and `kdf` are stored with every backup, so Argon2id can be added later and old backups still open (`Unsupported` is the answer for a scheme this app does not know). The passphrase rule (at least 12 characters and an estimated 60 bits) carries the rest. AES-GCM cannot tell a wrong passphrase from a changed backup, so both are `WrongPassphraseOrDamaged`, and the parameters and the key fingerprint are bound into the ciphertext as associated data, so none of them can be swapped without detection. `open` refuses iteration counts outside 100 000..5 000 000 before doing any work, so a tampered record can neither hang the phone nor make guessing cheaper through us.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/vault/KeyVaultTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.vault

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultTest {
    // 1 000 iterations keep the suite fast; the production default is checked once, below.
    private val fast = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val pem = "-----BEGIN CERTIFICATE-----\nQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5\n-----END CERTIFICATE-----\n" +
        "-----BEGIN PRIVATE KEY-----\nMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2\n-----END PRIVATE KEY-----\n"
    private val fingerprint = "0123456789abcdef"
    private val passphrase = "correct horse battery staple"

    private fun sealed() = fast.seal(pem, passphrase, fingerprint)

    private fun copy(s: SealedKey, version: Int = s.version, kdf: String = s.kdf, iterations: Int = s.iterations,
                     salt: ByteArray = s.salt, iv: ByteArray = s.iv, ct: ByteArray = s.ciphertext, fp: String = s.keyFingerprint) =
        SealedKey(version, kdf, iterations, salt, iv, ct, fp)

    @Test fun roundTrip() {
        assertEquals(OpenResult.Opened(pem), fast.open(sealed(), passphrase))
    }

    @Test fun roundTripWithTheProductionCost() {
        val vault = KeyVault()
        val s = vault.seal(pem, passphrase, fingerprint)
        assertEquals(600_000, s.iterations)
        assertEquals(OpenResult.Opened(pem), vault.open(s, passphrase))
    }

    @Test fun wrongPassphraseIsATypedResultNotAnException() {
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(sealed(), "correct horse battery stapl3"))
    }

    @Test fun emptyPassphraseDoesNotOpen() {
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(sealed(), ""))
    }

    @Test fun everyTamperedFieldIsDetected() {
        val s = sealed()
        fun flip(b: ByteArray, i: Int) = b.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = flip(s.ciphertext, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = flip(s.ciphertext, s.ciphertext.size - 1)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, iv = flip(s.iv, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, salt = flip(s.salt, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, iterations = s.iterations + 1), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, fp = "fedcba9876543210"), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = s.ciphertext.copyOf(s.ciphertext.size - 1)), passphrase))
    }

    @Test fun eachSealUsesFreshSaltAndIv() {
        val a = sealed(); val b = sealed()
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test fun theCiphertextDoesNotContainThePlainKey() {
        val text = String(sealed().ciphertext, Charsets.ISO_8859_1)
        assertFalse(text.contains("BEGIN"))
        assertFalse(text.contains("MTIzNDU2"))
    }

    @Test fun unknownVersionAndKdfAreUnsupportedNotWrongPassphrase() {
        val s = sealed()
        assertTrue(fast.open(copy(s, version = 2), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, kdf = "argon2id"), passphrase) is OpenResult.Unsupported)
    }

    @Test fun outOfRangeCostsAreRefusedBeforeAnyWork() {
        val s = sealed()
        val strict = KeyVault(iterations = 600_000)
        assertTrue(strict.open(copy(s, iterations = 1_000), passphrase) is OpenResult.Unsupported)     // too cheap
        assertTrue(strict.open(copy(s, iterations = 2_000_000_000), passphrase) is OpenResult.Unsupported) // would hang the phone
    }

    @Test fun malformedLengthsAreUnsupported() {
        val s = sealed()
        assertTrue(fast.open(copy(s, iv = ByteArray(5)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, salt = ByteArray(3)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, ct = ByteArray(2)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, ct = ByteArray(1_000_000)), passphrase) is OpenResult.Unsupported)
    }

    @Test fun unicodeNormalisationMakesComposedAndDecomposedPassphrasesEqual() {
        val composed = "café au lait 2026"
        val decomposed = "café au lait 2026"
        assertNotEquals(composed, decomposed)
        val s = fast.seal(pem, composed, fingerprint)
        assertEquals(OpenResult.Opened(pem), fast.open(s, decomposed))
    }

    @Test fun constructorRejectsACostOutsideTheAcceptedRange() {
        assertThrows(IllegalArgumentException::class.java) { KeyVault(iterations = 10) }
    }

    @Test fun sealingNothingIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { fast.seal("", passphrase, fingerprint) }
    }

    @Test fun fieldsRoundTripAsFirestoreWouldReturnThem() {
        val s = sealed()
        val fields: Map<String, Any?> = s.toFields()
        assertEquals(s, SealedKey.fromFields(fields))
        assertEquals(setOf("sealed", "kdf", "iterations", "salt", "iv", "version", "keyFingerprint"), fields.keys)
        assertTrue(fields["iterations"] is Long)
        assertTrue(fields["version"] is Long)
        assertEquals(OpenResult.Opened(pem), fast.open(SealedKey.fromFields(fields), passphrase))
    }

    @Test fun missingOrBrokenFieldsAreAFormatException() {
        val good = sealed().toFields()
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good - "salt") }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iv" to "***not base64***")) }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iterations" to "many")) }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iterations" to -5L)) }
    }

    @Test fun toStringNeverShowsKeyMaterial() {
        val s = sealed()
        assertFalse(s.toString().contains(java.util.Base64.getEncoder().encodeToString(s.ciphertext)))
    }

    @Test fun equalsComparesArrayContents() {
        val s = sealed()
        assertEquals(s, copy(s, salt = s.salt.copyOf()))
        assertArrayEquals(s.salt, copy(s).salt)
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/vault/PassphraseCheckTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseCheckTest {
    @Test fun shortIsTooShort() = assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase("Abc123!xyz"))
    @Test fun elevenCharactersIsStillTooShort() = assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase("abcdefghijk"))
    @Test fun repeatedCharacterIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("aaaaaaaaaaaaaaaa"))
    @Test fun twelveLowercaseLettersIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("qwertyuiopas"))
    @Test fun twoCharacterPatternIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("abababababababab"))
    @Test fun fourWordsIsStrong() = assertEquals(PassphraseStrength.STRONG, checkPassphrase("correct horse battery staple"))
    @Test fun mixedClassesAtTwelveIsGood() = assertEquals(PassphraseStrength.GOOD, checkPassphrase("Tr0ub4dor&3x"))
    @Test fun aWeakPrefixWithRepeatsStaysWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("shortaaaaaaaaaaaaaaaaaaaa"))
    @Test fun nonAsciiCountsAsALargePool() = assertTrue(checkPassphrase("überlange passphrase").isAcceptable())
    @Test fun weakAndShortAreNotAcceptable() {
        assertFalse(PassphraseStrength.TOO_SHORT.isAcceptable())
        assertFalse(PassphraseStrength.WEAK.isAcceptable())
        assertTrue(PassphraseStrength.GOOD.isAcceptable())
        assertTrue(PassphraseStrength.STRONG.isAcceptable())
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*KeyVaultTest*' --tests '*PassphraseCheckTest*'`
Expected: FAIL (compilation: `KeyVault`, `SealedKey`, `checkPassphrase` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/vault/SealedKey.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.vault

import java.util.Base64

/** The stored fields of a backup are damaged or incomplete. */
class SealedKeyFormatException(message: String) : IllegalArgumentException(message)

/**
 * A signing key encrypted with a passphrase, plus everything needed to open it again. [version] and [kdf] name the
 * scheme and [iterations] its cost, so a later release can raise them or add another KDF without breaking old backups.
 * [keyFingerprint] is the fingerprint of the plain key ([com.seyedjafari.analogplus.phone.keyFingerprint]); it is bound
 * to the ciphertext, so it cannot be swapped without detection.
 */
class SealedKey(
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val keyFingerprint: String,
) {
    /** The Firestore fields of `users/{uid}/backup/signingKey` except `createdAt`, which the server stamps. */
    fun toFields(): Map<String, Any> = mapOf(
        FIELD_SEALED to b64(ciphertext),
        FIELD_KDF to kdf,
        FIELD_ITERATIONS to iterations.toLong(),
        FIELD_SALT to b64(salt),
        FIELD_IV to b64(iv),
        FIELD_VERSION to version.toLong(),
        FIELD_FINGERPRINT to keyFingerprint,
    )

    override fun equals(other: Any?): Boolean =
        other is SealedKey && version == other.version && kdf == other.kdf && iterations == other.iterations &&
            salt.contentEquals(other.salt) && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext) &&
            keyFingerprint == other.keyFingerprint

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + kdf.hashCode()
        r = 31 * r + iterations
        r = 31 * r + salt.contentHashCode()
        r = 31 * r + iv.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + keyFingerprint.hashCode()
        return r
    }

    /** Never prints key material. */
    override fun toString(): String = "SealedKey(v$version, $kdf x$iterations, fingerprint=$keyFingerprint)"

    companion object {
        const val FIELD_SEALED = "sealed"
        const val FIELD_KDF = "kdf"
        const val FIELD_ITERATIONS = "iterations"
        const val FIELD_SALT = "salt"
        const val FIELD_IV = "iv"
        const val FIELD_VERSION = "version"
        const val FIELD_FINGERPRINT = "keyFingerprint"

        /**
         * Reads the fields written by [toFields] (Firestore hands numbers back as `Long`).
         * @throws SealedKeyFormatException when a field is missing, of the wrong type or not base64
         */
        fun fromFields(fields: Map<String, Any?>): SealedKey {
            fun text(name: String): String = fields[name] as? String ?: throw SealedKeyFormatException("field '$name' is missing")
            fun number(name: String): Int {
                val n = fields[name] as? Number ?: throw SealedKeyFormatException("field '$name' is missing")
                val l = n.toLong()
                if (l < 0 || l > Int.MAX_VALUE) throw SealedKeyFormatException("field '$name' is out of range")
                return l.toInt()
            }
            fun bytes(name: String): ByteArray = try {
                Base64.getDecoder().decode(text(name))
            } catch (e: IllegalArgumentException) {
                throw SealedKeyFormatException("field '$name' is not base64")
            }
            return SealedKey(
                version = number(FIELD_VERSION), kdf = text(FIELD_KDF), iterations = number(FIELD_ITERATIONS),
                salt = bytes(FIELD_SALT), iv = bytes(FIELD_IV), ciphertext = bytes(FIELD_SEALED),
                keyFingerprint = text(FIELD_FINGERPRINT),
            )
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/vault/KeyVault.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The result of opening a [SealedKey]. */
sealed interface OpenResult {
    data class Opened(val pem: String) : OpenResult

    /**
     * The authentication tag did not verify. AES-GCM cannot tell a wrong passphrase from a changed or damaged backup;
     * both end here, and the UI says so.
     */
    data object WrongPassphraseOrDamaged : OpenResult

    /** The backup uses a scheme or parameters this app does not accept (newer app version, or out-of-range values). */
    data class Unsupported(val reason: String) : OpenResult
}

/**
 * Encrypts the signing key PEM under a recovery passphrase. Pure JVM code (also runs on Android, API 26+), no Firebase.
 *
 * Scheme, version 1: passphrase (Unicode NFKC) -> PBKDF2-HMAC-SHA256, random 16-byte salt, [iterations] -> 256-bit key
 * -> AES-256-GCM, random 12-byte IV, 128-bit tag, the scheme parameters and the key fingerprint as associated data.
 *
 * Why PBKDF2 and 600 000 iterations: PBKDF2-HMAC-SHA256 ships with the JDK and Android, so there is no third-party
 * native code in the path that guards the signing key; 600 000 is the OWASP Password Storage Cheat Sheet figure for it.
 * Argon2id is stronger against GPUs but Android has no platform implementation and no library was vetted for this
 * plan. The passphrase rules ([checkPassphrase]) carry the rest of the strength, and `version`/`kdf` stored with every
 * backup allow adding Argon2id later without losing old backups.
 *
 * [minAcceptedIterations] and [maxAcceptedIterations] bound what [open] accepts from the cloud: a tampered record must
 * not make the phone hang (too high) or hand an attacker a cheaper target through us (too low).
 */
class KeyVault(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val minAcceptedIterations: Int = MIN_ACCEPTED_ITERATIONS,
    private val maxAcceptedIterations: Int = MAX_ACCEPTED_ITERATIONS,
) {
    init {
        require(iterations in minAcceptedIterations..maxAcceptedIterations) { "iterations outside the accepted range" }
    }

    /** CPU-heavy (about half a second at the default cost): call off the main thread. */
    fun seal(pem: String, passphrase: String, keyFingerprint: String): SealedKey {
        require(pem.isNotEmpty()) { "nothing to seal" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(VERSION, KDF_PBKDF2, iterations, salt, keyFingerprint))
        val ciphertext = cipher.doFinal(pem.toByteArray(Charsets.UTF_8))
        key.fill(0)
        return SealedKey(VERSION, KDF_PBKDF2, iterations, salt, iv, ciphertext, keyFingerprint)
    }

    /** CPU-heavy: call off the main thread. Never throws for bad input; see [OpenResult]. */
    fun open(sealed: SealedKey, passphrase: String): OpenResult {
        if (sealed.version != VERSION) return OpenResult.Unsupported("backup version ${sealed.version}")
        if (sealed.kdf != KDF_PBKDF2) return OpenResult.Unsupported("key derivation '${sealed.kdf}'")
        if (sealed.iterations !in minAcceptedIterations..maxAcceptedIterations) {
            return OpenResult.Unsupported("iteration count ${sealed.iterations}")
        }
        if (sealed.salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) return OpenResult.Unsupported("salt length ${sealed.salt.size}")
        if (sealed.iv.size != IV_BYTES) return OpenResult.Unsupported("iv length ${sealed.iv.size}")
        if (sealed.ciphertext.size !in TAG_BYTES..MAX_CIPHERTEXT_BYTES) return OpenResult.Unsupported("ciphertext length ${sealed.ciphertext.size}")
        val key = deriveKey(passphrase, sealed.salt, sealed.iterations)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.iv))
            cipher.updateAAD(aad(sealed.version, sealed.kdf, sealed.iterations, sealed.salt, sealed.keyFingerprint))
            OpenResult.Opened(String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            OpenResult.WrongPassphraseOrDamaged
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, cost: Int): ByteArray {
        val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toCharArray()
        val spec = PBEKeySpec(chars, salt, cost, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun aad(version: Int, kdf: String, cost: Int, salt: ByteArray, fingerprint: String): ByteArray =
        "analogplus-signing-key|v$version|$kdf|$cost|${Base64.getEncoder().encodeToString(salt)}|$fingerprint"
            .toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1
        const val KDF_PBKDF2 = "pbkdf2-hmac-sha256"
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ACCEPTED_ITERATIONS = 100_000
        const val MAX_ACCEPTED_ITERATIONS = 5_000_000
        private const val SALT_BYTES = 16
        private const val MIN_SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val KEY_BITS = 256
        private const val MAX_CIPHERTEXT_BYTES = 16 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/vault/PassphraseCheck.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.vault

import kotlin.math.ln

enum class PassphraseStrength { TOO_SHORT, WEAK, GOOD, STRONG }

/** The shortest passphrase accepted, in characters (code points). */
const val MIN_PASSPHRASE_LENGTH = 12

/** A passphrase is accepted at [PassphraseStrength.GOOD] or better. */
fun PassphraseStrength.isAcceptable(): Boolean = this >= PassphraseStrength.GOOD

/**
 * A rough guess, not a guarantee: estimated bits = effective length x log2(size of the character pool used), where
 * every character counts fully the first time it appears and a quarter after that. Four random words score STRONG;
 * `aaaaaaaaaaaa` and `qwertyuiopas` do not reach GOOD. Under 60 bits is WEAK, under 80 GOOD, otherwise STRONG.
 */
fun checkPassphrase(passphrase: String): PassphraseStrength {
    val points = passphrase.codePoints().toArray()
    if (points.size < MIN_PASSPHRASE_LENGTH) return PassphraseStrength.TOO_SHORT
    var pool = 0
    if (points.any { it in 'a'.code..'z'.code }) pool += 26
    if (points.any { it in 'A'.code..'Z'.code }) pool += 26
    if (points.any { it in '0'.code..'9'.code }) pool += 10
    if (points.any { it < 128 && !Character.isLetterOrDigit(it) }) pool += 33
    if (points.any { it >= 128 }) pool += 100
    val distinct = points.toSet().size
    // a repeated character adds little: each one beyond its first use counts a quarter
    val effectiveLength = distinct + 0.25 * (points.size - distinct)
    val bits = effectiveLength * ln(pool.toDouble()) / ln(2.0)
    return when {
        bits < 60 -> PassphraseStrength.WEAK
        bits < 80 -> PassphraseStrength.GOOD
        else -> PassphraseStrength.STRONG
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest --tests '*KeyVaultTest*' --tests '*PassphraseCheckTest*'`
Expected: PASS (17 + 10 tests; the production-cost round trip takes about a second).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/vault phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/vault
git commit -m "feat(phone): KeyVault (PBKDF2 + AES-GCM), SealedKey and the passphrase rule"
```

---

### Task 5: Store observer hook, design ids and the sync journal

**Files:**
- Modify: `Store.kt`
- Create: `accounts/DesignMapper.kt`, `accounts/SyncJournal.kt`, `phone/src/sharedTest/kotlin/.../accounts/MemoryKeyValueStore.kt`
- Test: `phone/src/test/kotlin/.../StoreObserverTest.kt`, `.../accounts/DesignMapperTest.kt`, `.../accounts/SyncJournalTest.kt`; the unchanged `StoreTest.kt`

**Interfaces:**
- Consumes: `Store`, `KeyValueStore`, `DesignJson`, `DesignSettings` (existing).
- Produces:

```kotlin
enum class ChangeOrigin { USER, SYNC }
sealed interface SavedChange { data class Saved(val name: String); data class Deleted(val name: String); data class Renamed(val oldName: String, val newName: String) }
interface StoreObserver { fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {}; fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {} }
// Store gains: addObserver, removeObserver; saveNamed/deleteNamed/saveCurrent take an optional `origin` (default USER)
fun designId(name: String): String                                   // 32 lowercase hex, SHA-256 of the trimmed NFC name
fun interface PhotoDownscaler { fun downscale(jpeg: ByteArray): ByteArray? }
sealed interface MappedDesign { data class Ok(val json: String); data class TooLarge(val bytes: Int) }
fun designToRemoteJson(design: DesignSettings, photos: PhotoDownscaler): MappedDesign
fun designFromRemoteJson(json: String): DesignSettings?
const val DESIGN_SCHEMA = 1; const val MAX_DESIGN_JSON_BYTES = 900_000; const val MAX_SYNCED_DESIGNS = 200; const val SYNC_PHOTO_MAX_SIDE = 512
enum class EntryState { LIVE, TOMBSTONE }; enum class SyncMark { CLEAN, DIRTY }; enum class BindResult { SAME_ACCOUNT, NEW_ACCOUNT }
data class JournalEntry(val name: String, val modifiedAtMs: Long, val state: EntryState, val baseRemoteAtMs: Long?, val mark: SyncMark)
class SyncJournal(kv: KeyValueStore, clock: () -> Long = System::currentTimeMillis) : StoreObserver {
    fun entries(): Map<String, JournalEntry>; fun boundUid(): String?; fun currentSeenRemoteAtMs(): Long; fun setCurrentSeenRemoteAtMs(ms: Long)
    fun bindTo(uid: String): BindResult; fun unbind(); fun reconcileWithStore(names: Collection<String>)
    fun markPushed(id: String, expectedModifiedAtMs: Long, remoteAtMs: Long); fun markApplied(id: String, name: String, state: EntryState, remoteAtMs: Long); fun drop(id: String)
}
class MemoryKeyValueStore : KeyValueStore      // sharedTest fake, public `val map`
```

Design notes. The **name is the identity** of a saved design (that is how `Store` keys them), so the document id is a hash of the name: two phones that save "Night" meet in the same document, and a rename is a tombstone for the old id plus a new document. The journal keeps the facts the cloud copy cannot: when the phone last changed each design, the server timestamp it last matched (`baseRemoteAtMs`), and whether it changed since (`mark`); deletions are `TOMBSTONE` entries. It is attached to the store in every build, signed in or not, so offline edits and deletions reach the cloud at the next sign-in. A design saved while its stored photo is still being loaded lacks that photo, so `Store.saveCurrent` does not report it (otherwise sync would upload a photo-less copy). The observers run outside the store's lock, so one may call back into the store (the sync engine does, with `ChangeOrigin.SYNC`).

- [ ] **Step 1: Write the failing tests**

`phone/src/sharedTest/kotlin/com/seyedjafari/analogplus/phone/accounts/MemoryKeyValueStore.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore

/** In-memory [KeyValueStore] shared by the JVM and instrumented tests of the accounts code. */
class MemoryKeyValueStore : KeyValueStore {
    val map = HashMap<String, Any>()
    override fun getString(key: String) = map[key] as String?
    override fun getLong(key: String, default: Long) = (map[key] as Long?) ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putLong(key: String, value: Long) { map[key] = value }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/StoreObserverTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreObserverTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Recorder : StoreObserver {
        val saved = ArrayList<Pair<SavedChange, ChangeOrigin>>()
        val currents = ArrayList<Pair<DesignSettings, ChangeOrigin>>()
        override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { saved += change to origin }
        override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) { currents += design to origin }
    }

    private fun store() = Store(FakeKeyValueStore(), File(tmp.root, "background.jpg"))
    private val d = DesignSettings(name = "Night")

    @Test fun saveDeleteAndRenameAreReported() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveNamed(" Night ", d)
        s.renameNamed("Night", "Day")
        s.deleteNamed("Day")
        assertEquals(
            listOf(
                SavedChange.Saved("Night") to ChangeOrigin.USER,
                SavedChange.Renamed("Night", "Day") to ChangeOrigin.USER,
                SavedChange.Deleted("Day") to ChangeOrigin.USER,
            ),
            r.saved,
        )
    }

    @Test fun nothingIsReportedWhenNothingChanged() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.deleteNamed("missing")
        s.saveNamed("Night", d); r.saved.clear()
        s.renameNamed("Night", "Night")
        assertTrue(r.saved.isEmpty())
    }

    @Test fun theOriginTravelsWithTheChange() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveNamed("Night", d, ChangeOrigin.SYNC)
        s.deleteNamed("Night", ChangeOrigin.SYNC)
        s.saveCurrent(d, ChangeOrigin.SYNC)
        assertEquals(listOf(ChangeOrigin.SYNC, ChangeOrigin.SYNC), r.saved.map { it.second })
        assertEquals(listOf(d to ChangeOrigin.SYNC), r.currents)
    }

    @Test fun savingTheCurrentDesignIsReported() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveCurrent(d)
        assertEquals(listOf(d to ChangeOrigin.USER), r.currents)
    }

    @Test fun aCurrentDesignSavedWhileItsPhotoIsStillLoadingIsNotReported() {
        val photo = File(tmp.root, "background.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val s = Store(FakeKeyValueStore(), photo); val r = Recorder(); s.addObserver(r)
        val loaded = s.loadCurrentWithoutPhoto()               // the photo file exists but is not in memory yet
        s.saveCurrent(loaded)
        assertTrue(r.currents.isEmpty())
        s.finishPhotoLoad(byteArrayOf(1, 2, 3))
        s.saveCurrent(loaded)
        assertEquals(1, r.currents.size)
    }

    @Test fun aFailingObserverNeitherBreaksSavingNorSilencesTheOthers() {
        val s = store(); val r = Recorder()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { error("boom") }
        })
        s.addObserver(r)
        s.saveNamed("Night", d)
        assertEquals(1, s.savedDesigns().size)
        assertEquals(1, r.saved.size)
    }

    @Test fun anObserverMayCallBackIntoTheStore() {
        val s = store()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
                if (change == SavedChange.Saved("Night")) s.saveNamed("Echo", d, ChangeOrigin.SYNC)
            }
        })
        s.saveNamed("Night", d)
        assertEquals(listOf("Night", "Echo"), s.savedDesigns().map { it.name })
    }

    @Test fun aRemovedObserverHearsNothingMore() {
        val s = store(); val r = Recorder(); s.addObserver(r); s.removeObserver(r)
        s.saveNamed("Night", d)
        assertTrue(r.saved.isEmpty())
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/DesignMapperTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignMapperTest {
    private val photoDesign = DesignSettings(
        name = "Photo", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(25 * 1024 * 1024) { 7 }),
    )

    @Test fun aDesignWithoutAPhotoRoundTrips() {
        val d = DesignSettings(name = "Night", accent = "#FF1E6BFF")
        val json = (designToRemoteJson(d, { error("no photo to shrink") }) as MappedDesign.Ok).json
        assertEquals(d, designFromRemoteJson(json))
    }

    @Test fun thePhotoIsShrunkInlineAndSurvivesTheRoundTrip() {
        val small = ByteArray(80_000) { 3 }
        val json = (designToRemoteJson(photoDesign, { small }) as MappedDesign.Ok).json
        assertTrue(json.length < 150_000)                       // base64 of 80 KB, far below the 1 MiB document limit
        assertArrayEquals(small, designFromRemoteJson(json)!!.background.photoJpeg)
    }

    @Test fun aWorstCaseShrunkPhotoStillFitsUnderTheDocumentLimit() {
        val atLimit = ByteArray(600_000) { 5 }                   // 600 KB -> about 800 KB of base64
        val r = designToRemoteJson(photoDesign, { atLimit })
        assertTrue(r is MappedDesign.Ok)
        assertTrue((r as MappedDesign.Ok).json.toByteArray().size < MAX_DESIGN_JSON_BYTES)
        assertTrue(MAX_DESIGN_JSON_BYTES < 1_048_576 - 89 - 100_000)   // headroom for name, timestamps and field names
    }

    @Test fun aTooLargeDesignIsRefusedNotTruncated() {
        val r = designToRemoteJson(photoDesign, { ByteArray(900_000) { 5 } })
        assertTrue(r is MappedDesign.TooLarge)
    }

    @Test fun anUndecodablePhotoIsDroppedAndTheBackgroundFallsBackToSolid() {
        val json = (designToRemoteJson(photoDesign, { null }) as MappedDesign.Ok).json
        val back = designFromRemoteJson(json)!!
        assertNull(back.background.photoJpeg)
        assertEquals(BackgroundKind.SOLID, back.background.kind)
    }

    @Test fun aThrowingDownscalerIsTreatedLikeAnUndecodablePhoto() {
        val json = (designToRemoteJson(photoDesign, { error("OOM-ish") }) as MappedDesign.Ok).json
        assertNull(designFromRemoteJson(json)!!.background.photoJpeg)
    }

    @Test fun damagedJsonReadsAsNullNotAnException() {
        assertNull(designFromRemoteJson("{oops"))
        assertNull(designFromRemoteJson(""))
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncJournalTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJournalTest {
    private var now = 1_000L
    private val kv = MemoryKeyValueStore()
    private fun journal() = SyncJournal(kv) { now }
    private val id = designId("Night")

    @Test fun aUserSaveIsDirtyAndStamped() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[id])
    }

    @Test fun syncOriginChangesAreNotRecordedAsUserEdits() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.SYNC)
        assertTrue(j.entries().isEmpty())
    }

    @Test fun aDeleteBecomesATombstoneThatKeepsTheBase() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        now = 2_000
        j.onSavedDesignChanged(SavedChange.Deleted("Night"), ChangeOrigin.USER)
        assertEquals(JournalEntry("Night", 2_000, EntryState.TOMBSTONE, 777, SyncMark.DIRTY), j.entries()[id])
    }

    @Test fun aRenameTombstonesTheOldNameAndCreatesTheNewOne() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        j.onSavedDesignChanged(SavedChange.Renamed("Night", "Day"), ChangeOrigin.USER)
        assertEquals(EntryState.TOMBSTONE, j.entries()[id]!!.state)
        assertEquals(JournalEntry("Day", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[designId("Day")])
    }

    @Test fun markPushedCleansOnlyIfTheDesignDidNotChangeMeanwhile() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        j.markPushed(id, expectedModifiedAtMs = 1_000, remoteAtMs = 50)
        assertEquals(SyncMark.CLEAN, j.entries()[id]!!.mark)
        now = 3_000
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        j.markPushed(id, expectedModifiedAtMs = 1_000, remoteAtMs = 60)    // a push of the older edit finishing late
        assertEquals(SyncMark.DIRTY, j.entries()[id]!!.mark)
        assertEquals(60L, j.entries()[id]!!.baseRemoteAtMs)
    }

    @Test fun bindingTheFirstOrAnotherAccountMakesEverythingUnsyncedButTheSameAccountChangesNothing() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        assertEquals(BindResult.NEW_ACCOUNT, j.bindTo("u1"))
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[id])
        j.markApplied(id, "Night", EntryState.LIVE, 888)
        assertEquals(BindResult.SAME_ACCOUNT, j.bindTo("u1"))
        assertEquals(SyncMark.CLEAN, j.entries()[id]!!.mark)
        assertEquals(BindResult.NEW_ACCOUNT, j.bindTo("u2"))
        assertNull(j.entries()[id]!!.baseRemoteAtMs)
    }

    @Test fun unbindForgetsTheAccount() {
        val j = journal()
        j.bindTo("u1"); j.setCurrentSeenRemoteAtMs(500)
        j.unbind()
        assertNull(j.boundUid())
        assertEquals(0L, j.currentSeenRemoteAtMs())
    }

    @Test fun reconcileAdoptsLegacyDesignsAndForgetsVanishedOnes() {
        val j = journal()
        j.markApplied(designId("Gone"), "Gone", EntryState.LIVE, 5)
        j.reconcileWithStore(listOf("Night"))
        assertEquals(setOf(id), j.entries().keys)
        assertEquals(SyncMark.DIRTY, j.entries()[id]!!.mark)
    }

    @Test fun tombstonesSurviveReconcile() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Deleted("Night"), ChangeOrigin.USER)
        j.reconcileWithStore(emptyList())
        assertEquals(EntryState.TOMBSTONE, j.entries()[id]!!.state)
    }

    @Test fun theCurrentSeenMarkOnlyMovesForward() {
        val j = journal()
        j.setCurrentSeenRemoteAtMs(500); j.setCurrentSeenRemoteAtMs(300)
        assertEquals(500L, j.currentSeenRemoteAtMs())
    }

    @Test fun stateSurvivesANewInstance() {
        journal().apply { bindTo("u1"); onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER) }
        val again = journal()
        assertEquals("u1", again.boundUid())
        assertEquals("Night", again.entries()[id]!!.name)
    }

    @Test fun aDamagedJournalRestartsEmptyInsteadOfThrowing() {
        kv.putString("sync_journal_v1", "{not json")
        val j = journal()
        assertTrue(j.entries().isEmpty())
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertEquals(1, j.entries().size)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*StoreObserverTest*' --tests '*DesignMapperTest*' --tests '*SyncJournalTest*'`
Expected: FAIL (compilation: `StoreObserver`, `ChangeOrigin`, `designToRemoteJson`, `SyncJournal` unresolved).

- [ ] **Step 3: Implement**

Edit `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`:

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
import java.time.Instant
```

with:

```kotlin
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
data class SavedDesign(val name: String, val design: DesignSettings)
```

with:

```kotlin
data class SavedDesign(val name: String, val design: DesignSettings)

/** Who made a change: the user in this app, or the sync engine applying something that came from the cloud. */
enum class ChangeOrigin { USER, SYNC }

/** What happened to the named designs. */
sealed interface SavedChange {
    data class Saved(val name: String) : SavedChange
    data class Deleted(val name: String) : SavedChange
    data class Renamed(val oldName: String, val newName: String) : SavedChange
}

/**
 * Hears about changes after they are stored. Called on the thread that made the change, outside [Store]'s lock, so an
 * observer may call back into the store. An observer that throws is ignored. Nothing here knows about any cloud.
 */
interface StoreObserver {
    fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {}
    fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {}
}
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
    private var lastPhotoWritten: ByteArray? = null
```

with:

```kotlin
    private val observers = CopyOnWriteArrayList<StoreObserver>()

    fun addObserver(observer: StoreObserver) { observers.add(observer) }

    fun removeObserver(observer: StoreObserver) { observers.remove(observer) }

    private fun notifyObservers(call: (StoreObserver) -> Unit) {
        for (o in observers) try { call(o) } catch (e: Exception) { /* a faulty observer must not break saving */ }
    }

    private var lastPhotoWritten: ByteArray? = null
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
    fun saveCurrent(d: DesignSettings) = synchronized(LOCK) {
        // While the stored photo is still being read, a design without photo must not delete it.
        if (!(photoPending && d.background.photoJpeg == null)) {
            if (d.background.photoJpeg != null) photoPending = false
            savePhoto(d.background.photoJpeg)
        }
        kv.putString(KEY_CURRENT, DesignJson.toJson(withoutPhoto(d)))
    }
```

with:

```kotlin
    fun saveCurrent(d: DesignSettings, origin: ChangeOrigin = ChangeOrigin.USER) {
        val complete = synchronized(LOCK) { saveCurrentLocked(d) }
        // A design saved while its stored photo is still being read lacks that photo: observers must not mistake it for the real one.
        if (complete) notifyObservers { it.onCurrentSaved(d, origin) }
    }

    /** Returns false when [d] was saved without its photo only because the stored photo has not been loaded yet. */
    private fun saveCurrentLocked(d: DesignSettings): Boolean {
        // While the stored photo is still being read, a design without photo must not delete it.
        val photoStillLoading = photoPending && d.background.photoJpeg == null
        if (!photoStillLoading) {
            if (d.background.photoJpeg != null) photoPending = false
            savePhoto(d.background.photoJpeg)
        }
        kv.putString(KEY_CURRENT, DesignJson.toJson(withoutPhoto(d)))
        return !photoStillLoading
    }
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
    fun saveNamed(name: String, d: DesignSettings) = synchronized(LOCK) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = LinkedHashMap(readSaved())
        all[clean] = DesignJson.toJson(withoutPhoto(d))
        writeSaved(all)
    }

    fun deleteNamed(name: String) = synchronized(LOCK) {
        val all = LinkedHashMap(readSaved())
        if (all.remove(name) != null) writeSaved(all)
    }
```

with:

```kotlin
    fun saveNamed(name: String, d: DesignSettings, origin: ChangeOrigin = ChangeOrigin.USER) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        synchronized(LOCK) {
            val all = LinkedHashMap(readSaved())
            all[clean] = DesignJson.toJson(withoutPhoto(d))
            writeSaved(all)
        }
        notifyObservers { it.onSavedDesignChanged(SavedChange.Saved(clean), origin) }
    }

    fun deleteNamed(name: String, origin: ChangeOrigin = ChangeOrigin.USER) {
        val removed = synchronized(LOCK) {
            val all = LinkedHashMap(readSaved())
            (all.remove(name) != null).also { if (it) writeSaved(all) }
        }
        if (removed) notifyObservers { it.onSavedDesignChanged(SavedChange.Deleted(name), origin) }
    }
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt`, replace:

```kotlin
    fun renameNamed(old: String, new: String) = synchronized(LOCK) {
        val clean = new.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = readSaved()
        require(old in all) { "No saved design called '$old'" }
        require(clean == old || clean !in all) { "A design called '$clean' already exists" }
        if (clean != old) writeSaved(all.entries.associate { (k, v) -> (if (k == old) clean else k) to v })
    }
```

with:

```kotlin
    fun renameNamed(old: String, new: String) {
        val clean = new.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val changed = synchronized(LOCK) {
            val all = readSaved()
            require(old in all) { "No saved design called '$old'" }
            require(clean == old || clean !in all) { "A design called '$clean' already exists" }
            (clean != old).also { if (it) writeSaved(all.entries.associate { (k, v) -> (if (k == old) clean else k) to v }) }
        }
        if (changed) notifyObservers { it.onSavedDesignChanged(SavedChange.Renamed(old, clean), ChangeOrigin.USER) }
    }
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/DesignMapper.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.security.MessageDigest
import java.text.Normalizer

/** Version of the `design` JSON written to Firestore (the `schema` field). */
const val DESIGN_SCHEMA = 1

/** A design JSON larger than this is not synced: Firestore documents stop at 1 MiB and rules allow 950 000 characters. */
const val MAX_DESIGN_JSON_BYTES = 900_000

/** The client-side cap on synced saved designs (Firestore rules cannot count documents). */
const val MAX_SYNCED_DESIGNS = 200

/** The longest side of a synced photo in pixels. The watch screens are 432 to 480 px; 512 covers them after cropping. */
const val SYNC_PHOTO_MAX_SIDE = 512

/** Shrinks a JPEG for the cloud. Null when the bytes cannot be decoded. Android implementation: [AndroidPhotoDownscaler]. */
fun interface PhotoDownscaler {
    fun downscale(jpeg: ByteArray): ByteArray?
}

sealed interface MappedDesign {
    data class Ok(val json: String) : MappedDesign
    data class TooLarge(val bytes: Int) : MappedDesign
}

/**
 * The Firestore document id of a saved design: 32 lowercase hex characters from the SHA-256 of its trimmed, NFC-normalised
 * name. The name is the identity (that is how [com.seyedjafari.analogplus.phone.Store] keys designs), so two phones that
 * save "Night" meet in the same document, and a rename is a tombstone for the old id plus a new document.
 */
fun designId(name: String): String {
    val normalised = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
    val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

/**
 * The `design` string for Firestore. A photo is shrunk by [photos] and kept inline (no Cloud Storage); a photo that
 * cannot be decoded is dropped and the background falls back to a solid colour. Never throws.
 */
fun designToRemoteJson(design: DesignSettings, photos: PhotoDownscaler): MappedDesign {
    val photo = design.background.photoJpeg
    val prepared = if (photo == null) {
        design
    } else {
        val small = try { photos.downscale(photo) } catch (e: Exception) { null }
        if (small != null) {
            design.copy(background = design.background.copy(photoJpeg = small))
        } else {
            val kind = if (design.background.kind == BackgroundKind.PHOTO) BackgroundKind.SOLID else design.background.kind
            design.copy(background = design.background.copy(photoJpeg = null, kind = kind))
        }
    }
    val json = DesignJson.toJson(prepared)
    val bytes = json.toByteArray(Charsets.UTF_8).size
    return if (bytes <= MAX_DESIGN_JSON_BYTES) MappedDesign.Ok(json) else MappedDesign.TooLarge(bytes)
}

/** Reads a `design` string; null when it is damaged. Does not validate the design (the editor shows what is wrong). */
fun designFromRemoteJson(json: String): DesignSettings? = try { DesignJson.fromJson(json) } catch (e: Exception) { null }
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncJournal.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.StoreObserver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class EntryState { LIVE, TOMBSTONE }

/** [DIRTY] means the local side changed since the last sync of this design. */
enum class SyncMark { CLEAN, DIRTY }

/**
 * What the app remembers about one saved design for sync. [modifiedAtMs] is the device clock at the last local change.
 * [baseRemoteAtMs] is the server `updatedAt` the design had when it last matched the cloud (null: never synced).
 */
data class JournalEntry(
    val name: String, val modifiedAtMs: Long, val state: EntryState, val baseRemoteAtMs: Long?, val mark: SyncMark,
)

enum class BindResult { SAME_ACCOUNT, NEW_ACCOUNT }

/**
 * The durable record of local changes, kept even while signed out so that offline edits and deletions reach the cloud
 * later. It listens to the [com.seyedjafari.analogplus.phone.Store] (user changes only; the sync engine records its own
 * changes with [markApplied]) and holds no Firebase types. Stored as one JSON object under `sync_journal_v1`.
 */
class SyncJournal(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) : StoreObserver {
    private val lock = Any()

    private class Data(
        var boundUid: String? = null,
        var currentSeenRemoteAtMs: Long = 0,
        val entries: LinkedHashMap<String, JournalEntry> = LinkedHashMap(),
    )

    fun entries(): Map<String, JournalEntry> = synchronized(lock) { read().entries.toMap() }

    fun boundUid(): String? = synchronized(lock) { read().boundUid }

    fun currentSeenRemoteAtMs(): Long = synchronized(lock) { read().currentSeenRemoteAtMs }

    fun setCurrentSeenRemoteAtMs(ms: Long) = synchronized(lock) {
        val d = read()
        if (ms > d.currentSeenRemoteAtMs) { d.currentSeenRemoteAtMs = ms; write(d) }
    }

    /**
     * Ties the journal to the signed-in account. For another account than before (or the first ever) every design is
     * treated as never synced, so it is merged into that account; for the same account nothing changes.
     */
    fun bindTo(uid: String): BindResult = synchronized(lock) {
        val d = read()
        if (d.boundUid == uid) return BindResult.SAME_ACCOUNT
        d.boundUid = uid
        d.currentSeenRemoteAtMs = 0
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
        BindResult.NEW_ACCOUNT
    }

    /** Forgets the account and every base (after the account was deleted); local designs stay and count as unsynced. */
    fun unbind() = synchronized(lock) {
        val d = read()
        d.boundUid = null
        d.currentSeenRemoteAtMs = 0
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
    }

    /**
     * Makes the journal match the store's names: a saved design without an entry (made before sync existed) becomes a
     * dirty live entry stamped now; a live entry whose design is gone is removed.
     */
    fun reconcileWithStore(names: Collection<String>) = synchronized(lock) {
        val d = read()
        var changed = false
        val ids = names.associateBy { designId(it) }
        for ((id, name) in ids) if (id !in d.entries) {
            d.entries[id] = JournalEntry(name, clock(), EntryState.LIVE, null, SyncMark.DIRTY); changed = true
        }
        for ((id, e) in d.entries.toMap()) if (e.state == EntryState.LIVE && id !in ids) { d.entries.remove(id); changed = true }
        if (changed) write(d)
    }

    override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER) return
        synchronized(lock) {
            val d = read()
            val now = clock()
            fun touch(name: String, state: EntryState) {
                val id = designId(name)
                d.entries[id] = JournalEntry(name, now, state, d.entries[id]?.baseRemoteAtMs, SyncMark.DIRTY)
            }
            when (change) {
                is SavedChange.Saved -> touch(change.name, EntryState.LIVE)
                is SavedChange.Deleted -> touch(change.name, EntryState.TOMBSTONE)
                is SavedChange.Renamed -> { touch(change.oldName, EntryState.TOMBSTONE); touch(change.newName, EntryState.LIVE) }
            }
            write(d)
        }
    }

    /** The engine pushed [id] and the server stamped [remoteAtMs]. Stays dirty if the design changed meanwhile. */
    fun markPushed(id: String, expectedModifiedAtMs: Long, remoteAtMs: Long) = synchronized(lock) {
        val d = read()
        val e = d.entries[id] ?: return
        d.entries[id] = if (e.modifiedAtMs == expectedModifiedAtMs) e.copy(baseRemoteAtMs = remoteAtMs, mark = SyncMark.CLEAN)
        else e.copy(baseRemoteAtMs = remoteAtMs)
        write(d)
    }

    /** The engine made the local side equal to the cloud copy stamped [remoteAtMs]. */
    fun markApplied(id: String, name: String, state: EntryState, remoteAtMs: Long) = synchronized(lock) {
        val d = read()
        d.entries[id] = JournalEntry(name, d.entries[id]?.modifiedAtMs ?: clock(), state, remoteAtMs, SyncMark.CLEAN)
        write(d)
    }

    fun drop(id: String) = synchronized(lock) {
        val d = read()
        if (d.entries.remove(id) != null) write(d)
    }

    private fun read(): Data {
        val text = kv.getString(KEY) ?: return Data()
        return try {
            val o = Json.parseToJsonElement(text).jsonObject
            val data = Data(
                boundUid = o["boundUid"]?.jsonPrimitive?.contentOrNull,
                currentSeenRemoteAtMs = o["currentSeenRemoteAtMs"]?.jsonPrimitive?.longOrNull ?: 0,
            )
            for ((id, v) in o["entries"]?.jsonObject ?: JsonObject(emptyMap())) {
                val e = v.jsonObject
                data.entries[id] = JournalEntry(
                    name = e.getValue("name").jsonPrimitive.content,
                    modifiedAtMs = e.getValue("modifiedAtMs").jsonPrimitive.content.toLong(),
                    state = EntryState.valueOf(e.getValue("state").jsonPrimitive.content),
                    baseRemoteAtMs = e["baseRemoteAtMs"]?.jsonPrimitive?.longOrNull,
                    mark = SyncMark.valueOf(e.getValue("mark").jsonPrimitive.content),
                )
            }
            data
        } catch (e: Exception) {
            Data() // a damaged journal restarts empty: every design then counts as new and is merged, never lost
        }
    }

    private fun write(d: Data) {
        val o = buildJsonObject {
            put("boundUid", d.boundUid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("currentSeenRemoteAtMs", d.currentSeenRemoteAtMs)
            put("entries", buildJsonObject {
                for ((id, e) in d.entries) put(id, buildJsonObject {
                    put("name", e.name)
                    put("modifiedAtMs", e.modifiedAtMs)
                    put("state", e.state.name)
                    put("baseRemoteAtMs", e.baseRemoteAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("mark", e.mark.name)
                })
            })
        }
        kv.putString(KEY, o.toString())
    }

    private companion object {
        const val KEY = "sync_journal_v1"
    }
}
```

- [ ] **Step 4: Run to verify they pass, including the untouched `StoreTest`**

Run: `./gradlew :phone:testDebugUnitTest --tests '*StoreObserverTest*' --tests '*DesignMapperTest*' --tests '*SyncJournalTest*' --tests '*StoreTest*'`
Expected: PASS (8 + 7 + 12 new tests; `StoreTest` as before, no edits to it).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/Store.kt phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts phone/src/sharedTest phone/src/test/kotlin
git commit -m "feat(phone): store observer hook, design ids, cloud design JSON and the sync journal"
```

---

### Task 6: `KeyStore.installPem`

The restore needs a way to put a recovered key in place. The existing `KeyStore` has `pem()`, `hasKey()` and `resetKey()` only, and `looksLikeKeyPem` is `internal` to `facegen`, so the validating install method lives in `facegen` itself.

**Files:**
- Modify: `facegen/src/main/kotlin/com/seyedjafari/analogplus/facegen/KeyStore.kt`
- Test: `facegen/src/androidTest/kotlin/com/seyedjafari/analogplus/facegen/KeyStoreInstallTest.kt`

**Interfaces:**
- Produces: `fun KeyStore.installPem(pem: String)`: validates with `looksLikeKeyPem`, copies an existing key to `<fileName>.bak` first (one generation; if the copy fails nothing is replaced), writes atomically with the existing `writeAtomically`, throws `SigningKeyException` for an incomplete key or an I/O failure.

- [ ] **Step 1: Write the failing test**

`facegen/src/androidTest/kotlin/com/seyedjafari/analogplus/facegen/KeyStoreInstallTest.kt`:

```kotlin
package com.seyedjafari.analogplus.facegen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyStoreInstallTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "install-test-${System.nanoTime()}.pem"
    private lateinit var store: KeyStore
    private val pemA = samplePem("A")
    private val pemB = samplePem("B")

    /** A PEM-shaped text that differs per [seed]: a certificate block and a private key block, each with a long enough base64 body. */
    private fun samplePem(seed: String): String {
        fun body(kind: String) = java.util.Base64.getEncoder().encodeToString("$kind-$seed-0123456789abcdef0123456789".toByteArray())
        return "-----BEGIN CERTIFICATE-----\n${body("cert")}\n-----END CERTIFICATE-----\n" +
            "-----BEGIN PRIVATE KEY-----\n${body("key")}\n-----END PRIVATE KEY-----\n"
    }

    @Before fun setUp() { store = KeyStore(context, name) }

    @After fun cleanUp() {
        listOf(name, "$name.bak", "$name.tmp").forEach { File(context.filesDir, it).delete() }
    }

    @Test fun installsAKeyOnAPhoneWithoutOne() {
        assertFalse(store.hasKey())
        store.installPem(pemA)
        assertTrue(store.hasKey())
        assertEquals(pemA, store.pem())
    }

    @Test fun replacingKeepsTheOldKeyAsABackupCopy() {
        store.installPem(pemA)
        store.installPem(pemB)
        assertEquals(pemB, store.pem())
        assertEquals(pemA, File(context.filesDir, "$name.bak").readText())
    }

    @Test fun anIncompleteKeyIsRefusedAndTheExistingKeyIsUntouched() {
        store.installPem(pemA)
        assertThrows(SigningKeyException::class.java) { store.installPem("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n") }
        assertThrows(SigningKeyException::class.java) { store.installPem("") }
        assertEquals(pemA, store.pem())
    }

    @Test fun aDamagedKeyFileCanBeReplacedByARestore() {
        File(context.filesDir, name).writeText("garbage")
        assertThrows(SigningKeyException::class.java) { store.pem() }
        store.installPem(pemA)
        assertEquals(pemA, store.pem())
    }

    @Test fun noTempFileIsLeftBehind() {
        store.installPem(pemA)
        assertFalse(File(context.filesDir, "$name.tmp").exists())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run (phone emulator up): `ANDROID_SERIAL=emulator-5562 ./gradlew :facegen:connectedDebugAndroidTest --tests '*KeyStoreInstallTest*'`
Expected: FAIL (compilation: `installPem` unresolved).

- [ ] **Step 3: Implement**

In `KeyStore.kt`, insert this method directly above `private fun damaged(...)`:

```kotlin
    /**
     * Makes [pem] (restored from a backup) the signing key. It must hold a complete certificate and private key. The key
     * being replaced, if there is one, is first copied to `<fileName>.bak` (one generation) so a mistaken restore can be
     * undone by hand; if that copy fails nothing is replaced. The new file is written atomically.
     * @throws SigningKeyException if [pem] is not a complete key, or the old key could not be kept, or the write failed
     */
    fun installPem(pem: String) {
        synchronized(LOCK) {
            if (!looksLikeKeyPem(pem)) throw SigningKeyException("That is not a complete signing key (certificate and private key).")
            val f = file
            try {
                if (f.exists()) {
                    Files.copy(f.toPath(), appContext.getFileStreamPath("$fileName.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                writeAtomically(pem)
            } catch (e: IOException) {
                throw SigningKeyException("The restored key could not be saved (${e.message}).", e)
            }
        }
    }
```

- [ ] **Step 4: Run to verify it passes, with the rest of the facegen device tests**

Run: `ANDROID_SERIAL=emulator-5562 ./gradlew :facegen:connectedDebugAndroidTest`
Expected: PASS (the 5 new tests plus the existing ones, 50 in total when this was written).

- [ ] **Step 5: Commit**

```bash
git add facegen/src/main/kotlin/com/seyedjafari/analogplus/facegen/KeyStore.kt facegen/src/androidTest/kotlin/com/seyedjafari/analogplus/facegen/KeyStoreInstallTest.kt
git commit -m "feat(facegen): KeyStore.installPem, validated, atomic, keeps one copy of the replaced key"
```

---

### Task 7: Remote model, conflict resolution, the photo shrinker

**Files:**
- Create: `accounts/RemoteStore.kt`, `accounts/SyncResolver.kt`, `accounts/AndroidPhotoDownscaler.kt`, `phone/src/sharedTest/kotlin/.../accounts/FakeRemoteStore.kt`
- Test: `phone/src/test/kotlin/.../accounts/SyncResolverTest.kt`; `phone/src/androidTest/kotlin/.../accounts/PhotoDownscalerTest.kt`

**Interfaces:**
- Consumes: `EntryState`, `SyncMark`, `designFromRemoteJson`, `PhotoDownscaler`, `SYNC_PHOTO_MAX_SIDE`, `decodeThumbnail` (existing, `internal` in `PhotoSupport.kt`), `SealedKey` (Task 4).
- Produces:

```kotlin
enum class DesignState { LIVE, TOMBSTONE }
data class RemoteDesign(val id: String, val name: String, val designJson: String, val updatedAtMs: Long, val state: DesignState, val schema: Int)
data class RemoteCurrent(val designJson: String, val updatedAtMs: Long, val deviceId: String, val deviceLabel: String)
data class RemoteBackup(val sealed: SealedKey, val createdAtMs: Long)
enum class RemoteErrorKind { OFFLINE, PERMISSION, QUOTA, OTHER }
class RemoteException(val kind: RemoteErrorKind, message: String, cause: Throwable? = null) : Exception
interface RemoteStore {
    suspend fun fetchDesigns(uid: String): List<RemoteDesign>
    fun observeDesigns(uid: String): Flow<List<RemoteDesign>>
    suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign
    suspend fun getCurrent(uid: String): RemoteCurrent?
    fun observeCurrent(uid: String): Flow<RemoteCurrent?>
    suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent
    suspend fun getBackup(uid: String): RemoteBackup?
    suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup
    suspend fun deleteBackup(uid: String)
    suspend fun deleteAllUserData(uid: String)
}
fun remoteErrorKind(firestoreCodeName: String): RemoteErrorKind
data class LocalDesignEntry(val id: String, val name: String, val design: DesignSettings?, val state: EntryState, val modifiedAtMs: Long, val baseRemoteAtMs: Long?, val mark: SyncMark)
sealed interface SyncAction { PushLive, PushTombstone, ApplyRemoteLive, ApplyRemoteTombstone, RecordBase, Drop, KeepBoth }   // each has `id`
fun planSync(local: List<LocalDesignEntry>, remote: List<RemoteDesign>, takenNames: Set<String>): List<SyncAction>
fun uniqueName(base: String, taken: Set<String>): String
class AndroidPhotoDownscaler : PhotoDownscaler
class FakeRemoteStore(var serverNow: Long = 1_000_000) : RemoteStore     // sharedTest: logical server clock, failWith, seedDesign/seedCurrent/seedBackup, designsOf/backupOf, designWrites/currentWrites
```

The rules `planSync` implements (decided, spec "Conflict rules"): a side changed when the phone's entry is `DIRTY` (local) or the cloud `updatedAt` is newer than the base the phone last saw (cloud). Only one side changed: that side wins. Both changed: the later edit wins, comparing the phone's edit time with the server's `updatedAt` (ties go to the cloud, so two phones converge); a deletion is an edit, so a newer edit elsewhere revives the design and a newer deletion beats an older edit. Two additions to the spec, both to avoid losing data: (1) a design with no shared history that differs on both sides is kept **both ways** (the cloud copy takes the name, this phone's copy becomes "<name> (this phone)"); (2) a design the phone synced and has not touched since, which is then missing in the cloud, was removed there (the account's data was deleted from another phone) and is **not** written back. Device-clock skew between phones can reorder two edits made within seconds of each other; that is accepted for a design editor.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncResolverTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResolverTest {
    private val a = DesignSettings(name = "A")
    private val b = DesignSettings(name = "B", accent = "#FF1E6BFF")
    private fun remote(id: String, d: DesignSettings, at: Long, state: DesignState = DesignState.LIVE, name: String = "n") =
        RemoteDesign(id, name, if (state == DesignState.LIVE) DesignJson.toJson(d) else "", at, state, 1)
    private fun local(
        id: String, d: DesignSettings?, modified: Long, base: Long?, mark: SyncMark, state: EntryState = EntryState.LIVE, name: String = "n",
    ) = LocalDesignEntry(id, name, d, state, modified, base, mark)
    private fun plan(l: List<LocalDesignEntry>, r: List<RemoteDesign>) = planSync(l, r, l.map { it.name }.toSet())

    @Test fun nothingToDoWhenBothSidesAgree() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(remote("1", a, 500))))
    }

    @Test fun dirtyLocalOnlyPushes() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), listOf(remote("1", a, 500))))
    }

    @Test fun newerRemoteOnlyIsApplied() {
        val r = remote("1", b, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun designOnlyInTheCloudIsApplied() {
        val r = remote("1", a, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(emptyList(), listOf(r)))
    }

    @Test fun designOnlyOnThePhoneIsPushed() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", a, 10, null, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun bothChangedLaterEditWins_localNewer() {
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 1_500))))
    }

    @Test fun bothChangedLaterEditWins_remoteNewer() {
        val r = remote("1", a, 3_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun tieGoesToTheCloud() {
        val r = remote("1", a, 2_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun localDeleteIsPushedAsATombstone() {
        val l = local("1", null, 2_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", a, 500))))
    }

    @Test fun remoteTombstoneDeletesACleanLocalDesign() {
        val r = remote("1", a, 900, DesignState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteTombstone("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun aNewerEditElsewhereRevivesALocallyDeletedDesign() {
        val r = remote("1", b, 5_000)
        val l = local("1", null, modified = 2_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun anOlderEditElsewhereDoesNotReviveALocallyDeletedDesign() {
        val l = local("1", null, modified = 9_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", b, 5_000))))
    }

    @Test fun aLocalEditNewerThanARemoteTombstoneRevivesIt() {
        val l = local("1", b, modified = 9_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun deletedOnBothSidesOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000, DesignState.TOMBSTONE)
        val l = local("1", null, 9_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun aSyncedUntouchedDesignThatVanishedFromTheCloudIsNotWrittenBack() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), emptyList()))
    }

    @Test fun aSyncedDesignEditedAfterTheCloudCopyVanishedIsPushedAgain() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun aTombstoneForSomethingTheCloudNeverHadIsForgotten() {
        assertEquals(listOf(SyncAction.Drop("1")), plan(listOf(local("1", null, 9_000, null, SyncMark.DIRTY, EntryState.TOMBSTONE)), emptyList()))
    }

    @Test fun aRemoteTombstoneForAnUnknownDesignIsIgnored() {
        assertEquals(emptyList<SyncAction>(), plan(emptyList(), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun identicalContentOnBothSidesWithoutHistoryOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY)), listOf(r)))
    }

    @Test fun differingContentWithoutHistoryKeepsBoth() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        assertEquals(listOf(SyncAction.KeepBoth("1", r, "Night (this phone)")), plan(listOf(l), listOf(r)))
    }

    @Test fun keepBothPicksAFreeName() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val taken = setOf("Night", "Night (this phone)")
        assertEquals("Night (this phone) 2", (planSync(listOf(l), listOf(r), taken).single() as SyncAction.KeepBoth).copyName)
    }

    @Test fun unreadableRemoteJsonIsNeverTreatedAsEqual() {
        val bad = RemoteDesign("1", "n", "{not json", 5_000, DesignState.LIVE, 1)
        val actions = plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY)), listOf(bad))
        assertTrue(actions.single() is SyncAction.KeepBoth || actions.single() is SyncAction.PushLive)
    }

    @Test fun designIdIsStableTrimmedAndFirestoreSafe() {
        assertEquals(designId("Night"), designId("  Night "))
        assertEquals(designId("café"), designId("café"))
        assertTrue(designId("Night").matches(Regex("[0-9a-f]{32}")))
        assertTrue(designId("a/b") != designId("a"))
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/PhotoDownscalerTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDownscalerTest {
    /** Random noise is the worst case for JPEG: it does not compress. */
    private fun noiseJpeg(width: Int, height: Int, quality: Int = 95): ByteArray {
        val random = Random(1)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    @Test fun aBigNoisyPhotoBecomesASmallJpegWithinTheSizeBudget() {
        val original = noiseJpeg(4000, 3000)
        assertTrue("test photo should be several MB, was ${original.size}", original.size > 3_000_000)
        val small = AndroidPhotoDownscaler().downscale(original)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(small, 0, small.size, bounds)
        assertTrue("longest side ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= SYNC_PHOTO_MAX_SIDE)
        assertTrue("JPEG was ${small.size} bytes", small.size < 600_000)
    }

    @Test fun theWholeDesignDocumentStaysUnderTheLimitWithTheWorstCasePhoto() {
        val design = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = noiseJpeg(4000, 3000)))
        val mapped = designToRemoteJson(design, AndroidPhotoDownscaler())
        assertTrue("expected Ok, was $mapped", mapped is MappedDesign.Ok)
        val bytes = (mapped as MappedDesign.Ok).json.toByteArray().size
        assertTrue("document JSON was $bytes bytes", bytes < 900_000)
    }

    @Test fun aSmallPhotoIsNotEnlarged() {
        val small = AndroidPhotoDownscaler().downscale(noiseJpeg(200, 100))!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(small, 0, small.size, bounds)
        assertEquals(200, bounds.outWidth)
        assertEquals(100, bounds.outHeight)
    }

    @Test fun garbageIsNullNotACrash() {
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(1000) { 9 }))
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*SyncResolverTest*'`
Expected: FAIL (compilation: `planSync`, `RemoteDesign` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/RemoteStore.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.Flow

enum class DesignState { LIVE, TOMBSTONE }

/** `users/{uid}/designs/{id}`. [updatedAtMs] is the server's clock. A tombstone has an empty [designJson]. */
data class RemoteDesign(
    val id: String, val name: String, val designJson: String, val updatedAtMs: Long, val state: DesignState, val schema: Int,
)

/** `users/{uid}/state/current`. */
data class RemoteCurrent(val designJson: String, val updatedAtMs: Long, val deviceId: String, val deviceLabel: String)

/** `users/{uid}/backup/signingKey`. */
data class RemoteBackup(val sealed: SealedKey, val createdAtMs: Long)

enum class RemoteErrorKind { OFFLINE, PERMISSION, QUOTA, OTHER }

class RemoteException(val kind: RemoteErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The few cloud operations sync and backup need. The Firestore implementation is the only code that knows paths and
 * field names; fakes implement this for tests. Functions throw [RemoteException] (and cancellation) only.
 */
interface RemoteStore {
    suspend fun fetchDesigns(uid: String): List<RemoteDesign>

    /** Emits the full list whenever the server's copy changes (not for the app's own unconfirmed writes). */
    fun observeDesigns(uid: String): Flow<List<RemoteDesign>>

    /** Writes with the server's timestamp and returns the document as the server stored it. */
    suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign

    suspend fun getCurrent(uid: String): RemoteCurrent?
    fun observeCurrent(uid: String): Flow<RemoteCurrent?>
    suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent

    suspend fun getBackup(uid: String): RemoteBackup?
    suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup
    suspend fun deleteBackup(uid: String)

    /** Removes every document of the user (designs, current, backup). Used by account deletion. */
    suspend fun deleteAllUserData(uid: String)
}

/** Maps a Firestore status code name (`FirebaseFirestoreException.Code.name`) to a [RemoteErrorKind]. */
fun remoteErrorKind(firestoreCodeName: String): RemoteErrorKind = when (firestoreCodeName) {
    "UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED" -> RemoteErrorKind.OFFLINE
    "PERMISSION_DENIED", "UNAUTHENTICATED" -> RemoteErrorKind.PERMISSION
    "RESOURCE_EXHAUSTED" -> RemoteErrorKind.QUOTA
    else -> RemoteErrorKind.OTHER
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncResolver.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings

/** One saved design as the phone knows it: the store's design joined with the journal entry. [design] is null for a tombstone. */
data class LocalDesignEntry(
    val id: String,
    val name: String,
    val design: DesignSettings?,
    val state: EntryState,
    val modifiedAtMs: Long,
    val baseRemoteAtMs: Long?,
    val mark: SyncMark,
)

/** What to do about one design. Decided by [planSync], carried out by the sync engine. */
sealed interface SyncAction {
    val id: String

    /** Write the local design (or its deletion) to the cloud. */
    data class PushLive(override val id: String) : SyncAction
    data class PushTombstone(override val id: String) : SyncAction

    /** Make the phone match the cloud copy. */
    data class ApplyRemoteLive(override val id: String, val remote: RemoteDesign) : SyncAction
    data class ApplyRemoteTombstone(override val id: String, val remote: RemoteDesign) : SyncAction

    /** Both sides already agree; remember the cloud timestamp. */
    data class RecordBase(override val id: String, val remote: RemoteDesign) : SyncAction

    /** A deleted design the cloud never had: forget it. */
    data class Drop(override val id: String) : SyncAction

    /** A design with no shared history differs on both sides: the cloud copy takes the name, this phone's copy is kept under [copyName]. */
    data class KeepBoth(override val id: String, val remote: RemoteDesign, val copyName: String) : SyncAction
}

/**
 * Per-design last write wins (pure, no I/O).
 *
 * A side "changed" when the phone's entry is dirty (local) or the cloud `updatedAt` is newer than the base the phone
 * last saw (cloud). One side changed: that side wins. Both changed: the later edit wins, comparing the phone's edit time
 * with the server's `updatedAt` (ties go to the cloud); a deletion is just an edit, so a newer edit on the other side
 * revives the design. Designs the phone never synced that differ on both sides are kept both ways ([SyncAction.KeepBoth]).
 * A synced, untouched design that vanished from the cloud is left alone (see the comment in the code).
 */
fun planSync(local: List<LocalDesignEntry>, remote: List<RemoteDesign>, takenNames: Set<String>): List<SyncAction> {
    val localById = local.associateBy { it.id }
    val remoteById = remote.associateBy { it.id }
    val taken = takenNames.toMutableSet()
    val actions = ArrayList<SyncAction>()
    for (id in (localById.keys + remoteById.keys)) {
        val l = localById[id]
        val r = remoteById[id]
        when {
            l == null && r == null -> Unit
            r == null -> when (l!!.state) {
                // A design this phone already synced and has not touched since, now missing in the cloud, was removed
                // there (another phone deleted the account's data). Writing it back would undo that deletion.
                EntryState.LIVE -> if (l.mark == SyncMark.CLEAN && l.baseRemoteAtMs != null) Unit else actions += SyncAction.PushLive(id)
                EntryState.TOMBSTONE -> actions += SyncAction.Drop(id)
            }
            l == null -> if (r.state == DesignState.LIVE) actions += SyncAction.ApplyRemoteLive(id, r)
            else -> actions += planOne(l, r, taken)
        }
    }
    return actions
}

private fun planOne(l: LocalDesignEntry, r: RemoteDesign, taken: MutableSet<String>): List<SyncAction> {
    val id = l.id
    val remoteChanged = l.baseRemoteAtMs == null || r.updatedAtMs > l.baseRemoteAtMs
    val localChanged = l.mark == SyncMark.DIRTY
    fun push() = if (l.state == EntryState.LIVE) SyncAction.PushLive(id) else SyncAction.PushTombstone(id)
    fun take() = if (r.state == DesignState.LIVE) SyncAction.ApplyRemoteLive(id, r) else SyncAction.ApplyRemoteTombstone(id, r)
    fun sameContent(): Boolean {
        if (l.state == EntryState.TOMBSTONE && r.state == DesignState.TOMBSTONE) return true
        if (l.state == EntryState.LIVE && r.state == DesignState.LIVE) {
            val remoteDesign = designFromRemoteJson(r.designJson)
            return remoteDesign != null && remoteDesign == l.design
        }
        return false
    }
    return when {
        !remoteChanged && !localChanged -> emptyList()
        localChanged && !remoteChanged -> listOf(push())
        !localChanged -> if (sameContent()) listOf(SyncAction.RecordBase(id, r)) else listOf(take())
        sameContent() -> listOf(SyncAction.RecordBase(id, r))
        l.baseRemoteAtMs == null && l.state == EntryState.LIVE && r.state == DesignState.LIVE -> {
            val copy = uniqueName("${l.name} (this phone)", taken)
            taken += copy
            listOf(SyncAction.KeepBoth(id, r, copy))
        }
        l.modifiedAtMs > r.updatedAtMs -> listOf(push())
        else -> listOf(take())
    }
}

/** [base], or "[base] 2", "[base] 3" ... the first one not in [taken]. */
fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AndroidPhotoDownscaler.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import android.graphics.Bitmap
import com.seyedjafari.analogplus.phone.decodeThumbnail
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a photo so that the longest side is at most [SYNC_PHOTO_MAX_SIDE] pixels and the JPEG is about [TARGET_BYTES]
 * or less, trying qualities 85, 70, 55 and 40. Decoding goes through [decodeThumbnail], which never decodes the full
 * image, applies the EXIF rotation and returns null for anything undecodable (then so does this).
 */
class AndroidPhotoDownscaler : PhotoDownscaler {
    override fun downscale(jpeg: ByteArray): ByteArray? {
        val decoded = decodeThumbnail(jpeg, SYNC_PHOTO_MAX_SIDE) ?: return null
        val longest = max(decoded.width, decoded.height)
        val bitmap = if (longest <= SYNC_PHOTO_MAX_SIDE) decoded else {
            val scale = SYNC_PHOTO_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true,
            )
        }
        var last: ByteArray? = null
        for (quality in QUALITIES) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            last = out.toByteArray()
            if (last.size <= TARGET_BYTES) return last
        }
        return last
    }

    private companion object {
        const val TARGET_BYTES = 300_000
        val QUALITIES = intArrayOf(85, 70, 55, 40)
    }
}
```

`phone/src/sharedTest/kotlin/com/seyedjafari/analogplus/phone/accounts/FakeRemoteStore.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [RemoteStore] with a logical server clock: every write advances [serverNow] by 1 000 ms and stamps it,
 * so tests control ordering without sleeping. [failWith] makes every call throw until cleared.
 */
class FakeRemoteStore(var serverNow: Long = 1_000_000) : RemoteStore {
    private val designs = MutableStateFlow<Map<String, Map<String, RemoteDesign>>>(emptyMap())
    private val currents = MutableStateFlow<Map<String, RemoteCurrent>>(emptyMap())
    private val backups = HashMap<String, RemoteBackup>()

    var failWith: RemoteException? = null
    val designWrites = ArrayList<RemoteDesign>()
    val currentWrites = ArrayList<RemoteCurrent>()
    var deletedAllFor: String? = null
    var failDeleteAll: RemoteException? = null

    private fun check() { failWith?.let { throw it } }
    private fun stamp(): Long { serverNow += 1_000; return serverNow }

    fun designsOf(uid: String): Map<String, RemoteDesign> = designs.value[uid] ?: emptyMap()

    /** Puts a document straight in, as if another phone wrote it. */
    fun seedDesign(uid: String, d: RemoteDesign) { designs.value = designs.value + (uid to (designsOf(uid) + (d.id to d))) }
    fun seedCurrent(uid: String, c: RemoteCurrent) { currents.value = currents.value + (uid to c) }
    fun backupOf(uid: String): RemoteBackup? = backups[uid]
    fun seedBackup(uid: String, b: RemoteBackup) { backups[uid] = b }

    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> { check(); return designsOf(uid).values.toList() }

    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = designs.map { check(); it[uid]?.values?.toList() ?: emptyList() }

    override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
        check()
        val d = RemoteDesign(id, name, designJson, stamp(), state, schema)
        designWrites += d
        seedDesign(uid, d)
        return d
    }

    override suspend fun getCurrent(uid: String): RemoteCurrent? { check(); return currents.value[uid] }
    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = currents.map { check(); it[uid] }

    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent {
        check()
        val c = RemoteCurrent(designJson, stamp(), deviceId, deviceLabel)
        currentWrites += c
        seedCurrent(uid, c)
        return c
    }

    override suspend fun getBackup(uid: String): RemoteBackup? { check(); return backups[uid] }

    override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup {
        check()
        return RemoteBackup(sealed, stamp()).also { backups[uid] = it }
    }

    override suspend fun deleteBackup(uid: String) { check(); backups.remove(uid) }

    override suspend fun deleteAllUserData(uid: String) {
        failDeleteAll?.let { throw it }
        check()
        designs.value = designs.value - uid
        currents.value = currents.value - uid
        backups.remove(uid)
        deletedAllFor = uid
    }
}
```

- [ ] **Step 4: Run to verify the JVM tests pass**

Run: `./gradlew :phone:testDebugUnitTest --tests '*SyncResolverTest*'`
Expected: PASS (23 tests).

- [ ] **Step 5: Run the photo tests on the phone emulator**

Run: `scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.seyedjafari.analogplus.phone.accounts.PhotoDownscalerTest`
Expected: PASS (4 tests: a 4000x3000 random-noise JPEG, the worst case for JPEG, ends up within 512 px and the whole design document under 900 000 bytes; a small photo is not enlarged; garbage gives null).

- [ ] **Step 6: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts phone/src/sharedTest phone/src/test/kotlin phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/PhotoDownscalerTest.kt
git commit -m "feat(phone): remote document model, per-design conflict resolution and the photo shrinker"
```

---

### Task 8: `CloudSync` and the sync engine

> Later change (final review): the kept-both copy is named "<name> (<device name>)", or "<name> (copy)" when the phone has no name, not "<name> (this phone)" as the code and tests below say; the design being edited is uploaded after 10 seconds of quiet or when the app is left (not after every edit burst); a pass about the design being edited alone no longer reads the saved designs. The text below is the plan as first written.

**Files:**
- Create: `accounts/CloudSync.kt`, `accounts/SyncEngine.kt`
- Test: `phone/src/test/kotlin/.../accounts/SyncEngineTest.kt`

**Interfaces:**
- Consumes: `Store`, `StoreObserver`, `ChangeOrigin`, `SavedChange`, `SyncJournal`, `RemoteStore`, `planSync`, `designToRemoteJson`, `designFromRemoteJson`, `designId`, `PhotoDownscaler`, `FakeRemoteStore`, `MemoryKeyValueStore`.
- Produces:

```kotlin
sealed interface SyncStatus { data object Off; data object Syncing; data class Idle(val lastSyncedAtMs: Long); data object Offline; data class Error(val reason: String) }
data class CurrentOffer(val fromDevice: String, val design: DesignSettings, val remoteUpdatedAtMs: Long)
enum class OfferChoice { USE_REMOTE, KEEP_MINE }
fun interface CurrentApplier { fun apply(design: DesignSettings) }
interface CloudSync {
    val status: StateFlow<SyncStatus>; val currentOffer: StateFlow<CurrentOffer?>
    fun start(uid: String); fun stop(); suspend fun syncNow(); fun resolveOffer(choice: OfferChoice)
}
class NoCloudSync : CloudSync
class SyncEngine(
    store: Store, journal: SyncJournal, remote: RemoteStore, scope: CoroutineScope, photos: PhotoDownscaler, applier: CurrentApplier,
    currentDesign: () -> DesignSettings, deviceId: String, deviceLabel: String, clock: () -> Long = System::currentTimeMillis,
    io: CoroutineDispatcher = Dispatchers.IO, debounceMs: Long = 1_000, retryMs: Long = 30_000,
) : CloudSync, StoreObserver
```

Behaviour the tests pin (the store's own autosave waits 400 ms; a sync pass waits `debounceMs` = 1 s after the last edit, so a burst of typing is one write): `start(uid)` binds the journal, does one full pass, reads the current design, then listens; local edits are debounced (a trailing debounce: one pass after edits stop for `debounceMs`); the engine's own writes into the store carry `ChangeOrigin.SYNC` so nothing is echoed back; **first sign-in on an empty phone** (no saved designs and the default current design, account not seen before) restores the current design without asking, every other case asks once per remote difference (`CurrentOffer`), and `OfferChoice.KEEP_MINE` pushes this phone's design; an offline failure shows `Offline` and retries after `retryMs`; a refusal (`PERMISSION`) shows an error and does not loop; more than `MAX_SYNCED_DESIGNS` live designs are not pushed (the status says so, nothing is deleted locally); stopping keeps the journal.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncEngineTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val day = DesignSettings(name = "Day", accent = "#FFFFAA00")

    /** One phone: its own store, journal and engine, all on the virtual clock of [scope]. */
    private inner class Phone(val remote: FakeRemoteStore, scope: CoroutineScope, val id: String, var now: () -> Long) {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journal = SyncJournal(MemoryKeyValueStore()) { now() }
        var current: DesignSettings = DesignSettings()
        val applied = ArrayList<DesignSettings>()
        val engine = SyncEngine(
            store, journal, remote, scope, { it }, { applied += it; current = it }, { current },
            deviceId = id, deviceLabel = "Phone $id", clock = { now() }, io = Dispatchers.Unconfined, debounceMs = 1_000, retryMs = 30_000,
        )
        init { store.addObserver(journal); store.addObserver(engine) }
        fun names() = store.savedDesigns().map { it.name }.sorted()
    }

    private fun TestScope.phone(remote: FakeRemoteStore, id: String, clock: () -> Long = { testScheduler.currentTime + 5_000_000 }) =
        Phone(remote, backgroundScope, id, clock)

    @Test fun firstSignInOnAnEmptyPhoneRestoresDesignsAndTheCurrentDesignWithoutAsking() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night); a.store.saveNamed("Day", day)
        a.current = night
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(night, com.seyedjafari.analogplus.phone.ChangeOrigin.USER)
        a.engine.syncNow()

        val b = phone(remote, "b")
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Day", "Night"), b.names())
        assertEquals(night, b.store.savedDesigns().first { it.name == "Night" }.design)
        assertEquals(listOf(night), b.applied)
        assertNull(b.engine.currentOffer.value)
        assertTrue(b.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aPhoneWithItsOwnWorkIsAskedBeforeItsCurrentDesignIsReplaced() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b")
        b.current = day
        b.engine.start("u1"); runCurrent()
        assertTrue(b.applied.isEmpty())
        val offer = b.engine.currentOffer.value
        assertNotNull(offer)
        assertEquals("Pixel 8", offer!!.fromDevice)
        assertEquals(night, offer.design)
    }

    @Test fun useRemoteAppliesTheOfferAndItIsAskedOnlyOncePerDifference() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.USE_REMOTE); runCurrent()
        assertEquals(listOf(night), b.applied)
        assertNull(b.engine.currentOffer.value)
        // the same remote document arriving again (listener replay, next pass) must not ask again
        b.current = day
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8")); runCurrent()
        assertNull(b.engine.currentOffer.value)
        // a newer edit elsewhere asks again
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 3_000_000, "other", "Pixel 8")); runCurrent()
        assertNotNull(b.engine.currentOffer.value)
    }

    @Test fun keepMineAnswersTheOfferAndPushesThisPhonesDesign() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        advanceTimeBy(1_500); runCurrent()
        assertNull(b.engine.currentOffer.value)
        assertEquals(DesignJson.toJson(day), remote.currentWrites.last().designJson)
        assertEquals("b", remote.currentWrites.last().deviceId)
    }

    @Test fun anEditIsPushedOnceAfterTheDebounceNotOnEveryKeystroke() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        advanceTimeBy(500); a.store.saveNamed("Night", night.copy(accent = "#FF000001"))
        advanceTimeBy(500); a.store.saveNamed("Night", night.copy(accent = "#FF000002"))
        advanceTimeBy(900); runCurrent()
        assertEquals(0, remote.designWrites.size)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, remote.designWrites.size)
        assertEquals(night.copy(accent = "#FF000002"), DesignJson.fromJson(remote.designWrites.single().designJson))
    }

    @Test fun aDeleteReachesTheOtherPhoneAsATombstone() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())

        a.store.deleteNamed("Night")
        advanceTimeBy(1_500); runCurrent()
        assertEquals(DesignState.TOMBSTONE, remote.designsOf("u1").values.single().state)
        b.engine.syncNow()
        assertEquals(emptyList<String>(), b.names())
    }

    @Test fun aRenameIsATombstoneForTheOldNameAndANewDocument() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        a.store.renameNamed("Night", "Midnight")
        advanceTimeBy(1_500); runCurrent()
        b.engine.syncNow()
        assertEquals(listOf("Midnight"), b.names())
    }

    @Test fun twoPhonesEditingTheSameDesignTheLaterEditWins() = runTest {
        val remote = FakeRemoteStore()
        var clockA = 5_000_000L; var clockB = 5_000_000L
        val a = phone(remote, "a") { clockA }; val b = phone(remote, "b") { clockB }
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())
        // both edit while "offline" (no pass runs because time does not advance past the debounce), b later than a
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        clockA = 6_000_000; a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        clockB = 7_000_000; b.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        advanceTimeBy(1_500); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Offline)
        remote.failWith = null
        a.engine.syncNow(); b.engine.syncNow(); a.engine.syncNow()
        assertEquals("#FF222222", a.store.savedDesigns().single().design.accent)
        assertEquals("#FF222222", b.store.savedDesigns().single().design.accent)
    }

    @Test fun applyingTheCloudCopyDoesNotWriteItBack() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, remote.designWrites.size)   // a's push only; b applied it and stayed quiet
    }

    @Test fun offlineKeepsLocalWorkAndPushesItWhenTheNetworkReturns() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.store.saveNamed("Night", night)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertEquals(listOf("Night"), a.names())
        remote.failWith = null
        advanceTimeBy(31_000); runCurrent()          // the retry fires
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun editsMadeWhileSignedOutAreUploadedAtTheNextSignIn() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)            // nobody is signed in; the journal still records it
        a.store.saveNamed("Day", day)
        a.store.deleteNamed("Day")
        a.engine.start("u1"); runCurrent()
        val docs = remote.designsOf("u1").values
        assertEquals(DesignState.LIVE, docs.single { it.name == "Night" }.state)
        assertTrue(docs.none { it.name == "Day" && it.state == DesignState.LIVE })
    }

    @Test fun signingIntoAnotherAccountMergesThisPhonesDesignsIntoIt() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        a.engine.stop()
        a.engine.start("u2"); runCurrent()
        assertEquals(1, remote.designsOf("u2").size)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun differingDesignWithTheSameNameAndNoHistoryIsKeptBothWays() = runTest {
        val remote = FakeRemoteStore()
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night", "Night (this phone)"), a.names())
        assertEquals("#FF222222", a.store.savedDesigns().first { it.name == "Night" }.design.accent)
        assertEquals("#FF111111", a.store.savedDesigns().first { it.name == "Night (this phone)" }.design.accent)
        assertEquals(2, remote.designsOf("u1").size)
    }

    @Test fun nothingBeyondTheLimitIsPushed() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        repeat(MAX_SYNCED_DESIGNS + 3) { a.store.saveNamed("D$it", night.copy(name = "D$it")) }
        a.engine.start("u1"); runCurrent()
        assertEquals(MAX_SYNCED_DESIGNS, remote.designsOf("u1").size)
        assertTrue(a.engine.status.value is SyncStatus.Error)
        assertEquals(MAX_SYNCED_DESIGNS + 3, a.store.savedDesigns().size)   // nothing is deleted locally
    }

    @Test fun aPermissionErrorIsShownAndTheListenersAreNotRestarted() = runTest {
        val remote = FakeRemoteStore()
        remote.failWith = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Error)
        advanceTimeBy(120_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Error)
    }

    @Test fun aPhoneStillSignedInDoesNotResurrectDataThatAnotherPhoneDeleted() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        remote.deleteAllUserData("u1")                 // the account was deleted on a third phone
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.designsOf("u1").size)
        assertEquals(listOf("Night"), a.names())       // local designs stay
        assertEquals(listOf("Night"), b.names())
    }

    @Test fun stopGoesOffAndStopsListening() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        a.engine.stop()
        assertEquals(SyncStatus.Off, a.engine.status.value)
        a.store.saveNamed("Night", night)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.designWrites.size)
        assertFalse(a.journal.entries().isEmpty())     // but the journal remembers it for the next sign-in
    }

    @Test fun aPhotoIsShrunkInsideTheCurrentDesignDocument() = runTest {
        val remote = FakeRemoteStore()
        val big = ByteArray(5_000_000) { 1 }
        val a = phone(remote, "a")
        val withPhoto = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = big))
        val shrinking = Phone(remote, backgroundScope, "a2") { 5_000_000L }
        val engine = SyncEngine(
            shrinking.store, shrinking.journal, remote, backgroundScope, { ByteArray(1_000) { 2 } }, { }, { withPhoto },
            "a2", "Phone", io = Dispatchers.Unconfined, debounceMs = 1_000,
        )
        engine.start("u1"); runCurrent()
        engine.onCurrentSaved(withPhoto, com.seyedjafari.analogplus.phone.ChangeOrigin.USER)
        engine.syncNow()
        val json = remote.currentWrites.single().designJson
        assertTrue(json.length < 10_000)
        assertEquals(1_000, DesignJson.fromJson(json).background.photoJpeg!!.size)
        a.engine.stop()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*SyncEngineTest*'`
Expected: FAIL (compilation: `SyncEngine`, `CloudSync` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/CloudSync.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SyncStatus {
    /** Signed out, or no cloud in this build. */
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    data class Idle(val lastSyncedAtMs: Long) : SyncStatus
    data object Offline : SyncStatus
    data class Error(val reason: String) : SyncStatus
}

/** The current design on another device is newer than the one here and differs from it. Asked once per difference. */
data class CurrentOffer(val fromDevice: String, val design: DesignSettings, val remoteUpdatedAtMs: Long)

enum class OfferChoice { USE_REMOTE, KEEP_MINE }

/** Puts a design from the cloud into the editor. */
fun interface CurrentApplier {
    fun apply(design: DesignSettings)
}

/**
 * Mirrors the local [com.seyedjafari.analogplus.phone.Store] to the cloud for the signed-in user. The store stays the
 * source of truth and works without any of this.
 */
interface CloudSync {
    val status: StateFlow<SyncStatus>
    val currentOffer: StateFlow<CurrentOffer?>

    /** Begins syncing for [uid]: a first full pass, then live updates. Replaces a running sync. */
    fun start(uid: String)

    /** Stops syncing (sign-out). Local designs stay. */
    fun stop()

    /** One full pass now; returns when it finished (the result is in [status]). */
    suspend fun syncNow()

    fun resolveOffer(choice: OfferChoice)
}

/** Used when there is no cloud: always [SyncStatus.Off]. */
class NoCloudSync : CloudSync {
    override val status: StateFlow<SyncStatus> = MutableStateFlow<SyncStatus>(SyncStatus.Off).asStateFlow()
    override val currentOffer: StateFlow<CurrentOffer?> = MutableStateFlow<CurrentOffer?>(null).asStateFlow()
    override fun start(uid: String) {}
    override fun stop() {}
    override suspend fun syncNow() {}
    override fun resolveOffer(choice: OfferChoice) {}
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncEngine.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.StoreObserver
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [CloudSync] over a [RemoteStore]: reads the local side from the [Store] and the [SyncJournal], decides with
 * [planSync], and carries the decisions out. Also a [StoreObserver] so that local edits schedule a debounced pass.
 *
 * Why the journal and not Firestore's offline queue: the phone must remember offline edits and deletions across app
 * restarts, and the journal does, so the Firestore client uses a memory cache only (see `FirebaseProvider`).
 *
 * [io] runs the blocking store work. [deviceId] is stable per install; [deviceLabel] is shown in the "newer from another
 * device" question.
 */
class SyncEngine(
    private val store: Store,
    private val journal: SyncJournal,
    private val remote: RemoteStore,
    private val scope: CoroutineScope,
    private val photos: PhotoDownscaler,
    private val applier: CurrentApplier,
    private val currentDesign: () -> DesignSettings,
    private val deviceId: String,
    private val deviceLabel: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 1_000,
    private val retryMs: Long = 30_000,
) : CloudSync, StoreObserver {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _offer = MutableStateFlow<CurrentOffer?>(null)
    override val currentOffer: StateFlow<CurrentOffer?> = _offer.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var uid: String? = null
    private var session: Job? = null
    @Volatile private var trigger: Channel<Unit>? = null
    private val pendingCurrent = AtomicReference<DesignSettings?>(null)
    private var lastPushedCurrent: DesignSettings? = null
    private var lastRemoteSignature: List<Triple<String, Long, DesignState>>? = null

    override fun start(uid: String) {
        stop()
        this.uid = uid
        _status.value = SyncStatus.Syncing
        val channel = Channel<Unit>(Channel.CONFLATED)
        trigger = channel
        session = scope.launch {
            val silent = withContext(io) { journal.boundUid() != uid && isEmptyDevice() }
            withContext(io) { journal.bindTo(uid) }
            syncNow()
            guarded { onRemoteCurrent(remote.getCurrent(uid), silent) }
            launch {
                for (t in channel) {
                    do { delay(debounceMs) } while (channel.tryReceive().isSuccess)   // waits until edits stop for debounceMs
                    syncNow()
                }
            }
            launch { keepRunning { remote.observeDesigns(uid).collect { list -> onRemoteDesigns(list) } } }
            launch { keepRunning { remote.observeCurrent(uid).collect { rc -> guarded { onRemoteCurrent(rc, silent = false) } } } }
        }
    }

    override fun stop() {
        session?.cancel()
        session = null
        trigger?.close()
        trigger = null
        uid = null
        lastRemoteSignature = null
        lastPushedCurrent = null
        pendingCurrent.set(null)
        _offer.value = null
        _status.value = SyncStatus.Off
    }

    override suspend fun syncNow() {
        val id = uid ?: return
        guarded {
            val designs = remote.fetchDesigns(id)
            reconcile(id, designs)
            pushCurrentIfNeeded(id)
        }
    }

    override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
        if (origin == ChangeOrigin.USER) trigger?.trySend(Unit)
    }

    override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER || uid == null) return
        pendingCurrent.set(design)
        trigger?.trySend(Unit)
    }

    override fun resolveOffer(choice: OfferChoice) {
        val offer = _offer.value ?: return
        _offer.value = null
        journal.setCurrentSeenRemoteAtMs(offer.remoteUpdatedAtMs)
        when (choice) {
            OfferChoice.USE_REMOTE -> { lastPushedCurrent = offer.design; applier.apply(offer.design) }
            OfferChoice.KEEP_MINE -> {
                lastPushedCurrent = null
                pendingCurrent.set(currentDesign())
                trigger?.trySend(Unit)
            }
        }
    }

    // ---- a pass -------------------------------------------------------------------------------------------------

    private suspend fun onRemoteDesigns(list: List<RemoteDesign>) {
        val id = uid ?: return
        val signature = list.map { Triple(it.id, it.updatedAtMs, it.state) }.sortedBy { it.first }
        if (signature == lastRemoteSignature) return
        guarded { reconcile(id, list) }
    }

    private suspend fun reconcile(uid: String, remoteList: List<RemoteDesign>) = mutex.withLock {
        _status.value = SyncStatus.Syncing
        val local = withContext(io) { localEntries() }
        val planned = planSync(local, remoteList, local.mapTo(HashSet()) { it.name })
        val actions = capNewDesigns(planned, remoteList)
        val byId = local.associateBy { it.id }
        var skipped = 0
        for (action in actions) if (!execute(uid, action, byId[action.id])) skipped++
        lastRemoteSignature = remoteList.map { Triple(it.id, it.updatedAtMs, it.state) }.sortedBy { it.first }
        _status.value = when {
            skipped > 0 -> SyncStatus.Error("$skipped saved design(s) could not be synced (unreadable or too large).")
            planned.size != actions.size -> SyncStatus.Error("Sync keeps at most $MAX_SYNCED_DESIGNS saved designs; delete some to sync the rest.")
            else -> SyncStatus.Idle(clock())
        }
    }

    /** Drops pushes of designs the cloud does not have yet once the cloud holds [MAX_SYNCED_DESIGNS] live designs. */
    private fun capNewDesigns(actions: List<SyncAction>, remoteList: List<RemoteDesign>): List<SyncAction> {
        val known = remoteList.mapTo(HashSet()) { it.id }
        var live = remoteList.count { it.state == DesignState.LIVE }
        return actions.filter { a ->
            if (a is SyncAction.PushLive && a.id !in known) { if (live >= MAX_SYNCED_DESIGNS) false else { live++; true } } else true
        }
    }

    /** Carries out one decision. Returns false when the design was skipped (unreadable in the cloud, or too large to push). */
    private suspend fun execute(uid: String, action: SyncAction, local: LocalDesignEntry?): Boolean {
        when (action) {
            is SyncAction.PushLive -> {
                val e = local!!
                val json = (designToRemoteJson(e.design!!, photos) as? MappedDesign.Ok)?.json ?: return false
                val written = remote.putDesign(uid, e.id, e.name, json, DesignState.LIVE, DESIGN_SCHEMA)
                withContext(io) { journal.markPushed(e.id, e.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.PushTombstone -> {
                val e = local!!
                val written = remote.putDesign(uid, e.id, e.name, "", DesignState.TOMBSTONE, DESIGN_SCHEMA)
                withContext(io) { journal.markPushed(e.id, e.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.ApplyRemoteLive -> {
                val design = designFromRemoteJson(action.remote.designJson) ?: return false
                withContext(io) {
                    store.saveNamed(action.remote.name, design, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs)
                }
            }
            is SyncAction.ApplyRemoteTombstone -> withContext(io) {
                store.deleteNamed(action.remote.name, ChangeOrigin.SYNC)
                journal.markApplied(action.id, action.remote.name, EntryState.TOMBSTONE, action.remote.updatedAtMs)
            }
            is SyncAction.RecordBase -> withContext(io) {
                val state = if (action.remote.state == DesignState.LIVE) EntryState.LIVE else EntryState.TOMBSTONE
                journal.markApplied(action.id, action.remote.name, state, action.remote.updatedAtMs)
            }
            is SyncAction.Drop -> withContext(io) { journal.drop(action.id) }
            is SyncAction.KeepBoth -> {
                val mine = local!!.design!!
                val theirs = designFromRemoteJson(action.remote.designJson) ?: return false
                withContext(io) {
                    store.saveNamed(action.remote.name, theirs, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs)
                    store.saveNamed(action.copyName, mine, ChangeOrigin.SYNC)
                }
                val json = (designToRemoteJson(mine, photos) as? MappedDesign.Ok)?.json ?: return false
                val copyId = designId(action.copyName)
                val written = remote.putDesign(uid, copyId, action.copyName, json, DesignState.LIVE, DESIGN_SCHEMA)
                withContext(io) { journal.markApplied(copyId, action.copyName, EntryState.LIVE, written.updatedAtMs) }
            }
        }
        return true
    }

    private fun localEntries(): List<LocalDesignEntry> {
        val saved = store.savedDesigns()
        journal.reconcileWithStore(saved.map { it.name })
        val byId = saved.associateBy { designId(it.name) }
        return journal.entries().map { (id, e) ->
            LocalDesignEntry(id, e.name, byId[id]?.design, e.state, e.modifiedAtMs, e.baseRemoteAtMs, e.mark)
        }
    }

    private fun isEmptyDevice(): Boolean = store.savedDesigns().isEmpty() && currentDesign() == DesignSettings()

    // ---- the current design -------------------------------------------------------------------------------------

    private suspend fun pushCurrentIfNeeded(uid: String) {
        val d = pendingCurrent.getAndSet(null) ?: return
        if (d == lastPushedCurrent) return
        val json = (designToRemoteJson(d, photos) as? MappedDesign.Ok)?.json
        if (json == null) {
            _status.value = SyncStatus.Error("The design you are editing is too large to sync.")
            return
        }
        val written = remote.putCurrent(uid, json, deviceId, deviceLabel)
        lastPushedCurrent = d
        withContext(io) { journal.setCurrentSeenRemoteAtMs(written.updatedAtMs) }
    }

    private suspend fun onRemoteCurrent(rc: RemoteCurrent?, silent: Boolean) {
        rc ?: return
        if (rc.deviceId == deviceId) { journal.setCurrentSeenRemoteAtMs(rc.updatedAtMs); return }
        if (rc.updatedAtMs <= journal.currentSeenRemoteAtMs()) return
        if (_offer.value?.remoteUpdatedAtMs == rc.updatedAtMs) return
        val design = designFromRemoteJson(rc.designJson) ?: return
        if (design == currentDesign()) { journal.setCurrentSeenRemoteAtMs(rc.updatedAtMs); return }
        if (silent) {
            journal.setCurrentSeenRemoteAtMs(rc.updatedAtMs)
            lastPushedCurrent = design
            applier.apply(design)
        } else {
            _offer.value = CurrentOffer(rc.deviceLabel.ifBlank { "another device" }, design, rc.updatedAtMs)
        }
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    /** Runs [block], turning remote failures into [status]; an offline failure schedules a retry. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            _status.value = when (e.kind) {
                RemoteErrorKind.OFFLINE -> SyncStatus.Offline
                RemoteErrorKind.PERMISSION -> SyncStatus.Error("The cloud refused the request (sign out and in again).")
                RemoteErrorKind.QUOTA -> SyncStatus.Error("The cloud limit was reached; try again later.")
                RemoteErrorKind.OTHER -> SyncStatus.Error(e.message ?: "Sync failed.")
            }
            if (e.kind == RemoteErrorKind.OFFLINE) trigger?.let { scope.launch { delay(retryMs); it.trySend(Unit) } }
        } catch (e: Exception) {
            _status.value = SyncStatus.Error("Sync failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Collects a listener; when the network drops, reports it and restarts the listener after [retryMs]. */
    private suspend fun keepRunning(listen: suspend () -> Unit) {
        while (scope.isActive) {
            try {
                listen()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RemoteException) {
                if (e.kind != RemoteErrorKind.OFFLINE) {
                    _status.value = SyncStatus.Error(e.message ?: "Sync failed.")
                    return   // a refusal does not fix itself; the next sign-in or "Sync now" starts over
                }
                _status.value = SyncStatus.Offline
            }
            delay(retryMs)
        }
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest --tests '*SyncEngineTest*'`
Expected: PASS (18 tests, all on virtual time, no sleeping).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/CloudSync.kt phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncEngine.kt phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/SyncEngineTest.kt
git commit -m "feat(phone): CloudSync and the sync engine (debounce, tombstones, offers, offline retry)"
```

---

### Task 9: Signing-key backup and restore

**Files:**
- Create: `accounts/KeySource.kt`, `accounts/AttemptLimiter.kt`, `accounts/BackupService.kt`, `phone/src/sharedTest/kotlin/.../accounts/FakeKeySource.kt`
- Test: `phone/src/test/kotlin/.../accounts/BackupServiceTest.kt`, `.../AttemptLimiterTest.kt`; `phone/src/androidTest/kotlin/.../accounts/KeyVaultCostTest.kt`

**Interfaces:**
- Consumes: `KeyVault`, `OpenResult`, `SealedKey`, `RemoteStore`, `FakeRemoteStore`, `keyFingerprint(pem)` (existing, `KeyFingerprint.kt`), `SigningKeyException`, `KeyStore.installPem` (Task 6), `MemoryKeyValueStore`.
- Produces:

```kotlin
interface KeySource { fun hasKey(): Boolean; fun pem(): String; fun installPem(pem: String) }
class KeyStoreKeySource(keyStore: KeyStore) : KeySource
class AttemptLimiter(kv: KeyValueStore, clock: () -> Long = System::currentTimeMillis) { fun waitMs(): Long; fun recordFailure(); fun recordSuccess(); companion { fun delayAfter(failures: Long): Long } }
enum class KeyMatch { SAME, DIFFERENT, NO_LOCAL_KEY }
sealed interface BackupStatus { SignedOut; Checking; NotBackedUp; BackedUp(fingerprint, createdAtMs, match: KeyMatch); Unknown(reason) }
sealed interface UploadResult { Ok(fingerprint); NotSignedIn; KeyUnreadable(message); SelfTestFailed; Failed(kind: RemoteErrorKind, message) }
sealed interface RestorePlan { NotSignedIn; NoBackup; Unknown(reason); InstallNew(backupFingerprint); AlreadyHaveThisKey(fingerprint); ReplaceLocal(localFingerprint, backupFingerprint); ReplaceDamaged(backupFingerprint) }
sealed interface RestoreResult { Restored(fingerprint); NotSignedIn; NoBackup; WrongPassphrase(nextWaitMs); Throttled(waitMs); Unsupported(reason); LocalKeyChanged; FingerprintMismatch; KeyRejected(message); Failed(message) }
class BackupService(remote: RemoteStore, vault: KeyVault, keys: KeySource, limiter: AttemptLimiter, cpu: CoroutineDispatcher = Dispatchers.Default) {
    suspend fun status(uid: String): BackupStatus
    suspend fun upload(uid: String, passphrase: String): UploadResult
    suspend fun plan(uid: String): RestorePlan
    suspend fun restore(uid: String, passphrase: String, plan: RestorePlan): RestoreResult
    suspend fun discard(uid: String): RemoteErrorKind?       // null = deleted
}
class FakeKeySource(stored: String? = null, generated: String = samplePem("generated")) : KeySource { var damaged; var installs; var previousKey; companion { fun samplePem(seed: String): String } }
```

Behaviour the tests pin (spec "Signing-key backup and restore"): **upload** seals, opens its own result, writes, **reads the document back and opens it again** with the passphrase; on any mismatch it puts the previous backup back and reports `SelfTestFailed`, so a bad backup is found at once and never replaces a good one. **plan** is decided before the passphrase is asked for, so the UI can confirm a replacement first. **restore** checks the attempt delay, opens the backup, **verifies the key inside has the fingerprint the backup states**, re-checks that this phone's key is still the one the plan was made for (`LocalKeyChanged` otherwise), then installs (the previous key stays as a `.bak` copy, Task 6). A wrong passphrase is a typed result and grows the delay (free for two tries, 5 s, 10 s, ... up to 5 min, kept across restarts); an unsupported backup does not count as a guess.

- [ ] **Step 1: Write the failing tests**

`phone/src/sharedTest/kotlin/com/seyedjafari/analogplus/phone/accounts/FakeKeySource.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.SigningKeyException

/** An in-memory [KeySource]. [pem] creates [generated] on first use like the real key store. */
class FakeKeySource(private var stored: String? = null, private val generated: String = samplePem("generated")) : KeySource {
    var damaged = false
    var installs = 0
    var previousKey: String? = null

    override fun hasKey(): Boolean = stored != null || damaged

    override fun pem(): String {
        if (damaged) throw SigningKeyException("The saved signing key is damaged.")
        return stored ?: generated.also { stored = it }
    }

    override fun installPem(pem: String) {
        if (!pem.contains("BEGIN CERTIFICATE") || !pem.contains("BEGIN PRIVATE KEY")) throw SigningKeyException("That is not a complete signing key.")
        previousKey = stored
        stored = pem
        damaged = false
        installs++
    }

    companion object {
        /** A PEM-shaped text that differs per [seed]. */
        fun samplePem(seed: String): String =
            "-----BEGIN CERTIFICATE-----\n${java.util.Base64.getEncoder().encodeToString("cert-$seed-0123456789abcdef0123456789".toByteArray())}\n-----END CERTIFICATE-----\n" +
                "-----BEGIN PRIVATE KEY-----\n${java.util.Base64.getEncoder().encodeToString("key-$seed-0123456789abcdef0123456789".toByteArray())}\n-----END PRIVATE KEY-----\n"
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/BackupServiceTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {
    private val remote = FakeRemoteStore()
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private var now = 1_000_000L
    private val limiter = AttemptLimiter(MemoryKeyValueStore()) { now }
    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val passphrase = "correct horse battery staple"

    private fun service(keys: FakeKeySource) = BackupService(remote, vault, keys, limiter, Dispatchers.Unconfined)
    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test fun uploadStoresASealedKeyThatOpensWithThePassphraseOnly() {
        val keys = FakeKeySource(pemA)
        val r = run { service(keys).upload("u1", passphrase) }
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), r)
        val stored = remote.backupOf("u1")!!.sealed
        assertEquals(keyFingerprint(pemA), stored.keyFingerprint)
        assertFalse(String(stored.ciphertext, Charsets.ISO_8859_1).contains("BEGIN"))
    }

    @Test fun uploadCreatesTheKeyWhenThePhoneHasNoneYet() {
        val keys = FakeKeySource(null)
        val r = run { service(keys).upload("u1", passphrase) }
        assertTrue(r is UploadResult.Ok)
        assertTrue(keys.hasKey())
    }

    @Test fun aDamagedLocalKeyIsReportedAndNothingIsUploaded() {
        val keys = FakeKeySource(pemA).apply { damaged = true }
        assertTrue(run { service(keys).upload("u1", passphrase) } is UploadResult.KeyUnreadable)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun offlineUploadFailsCleanly() {
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val r = run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "offline"), r)
    }

    @Test fun aBackupThatDoesNotReadBackIsDetectedAndThePreviousOneIsPutBack() {
        val keys = FakeKeySource(pemA)
        run { service(keys).upload("u1", passphrase) }
        val good = remote.backupOf("u1")!!.sealed
        // a store that corrupts what it keeps
        val corrupting = object : RemoteStore by remote {
            private var writes = 0
            override suspend fun putBackup(uid: String, sealed: com.seyedjafari.analogplus.phone.accounts.vault.SealedKey): RemoteBackup {
                writes++
                val damaged = if (writes == 1) com.seyedjafari.analogplus.phone.accounts.vault.SealedKey(
                    sealed.version, sealed.kdf, sealed.iterations, sealed.salt, sealed.iv, sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, sealed.keyFingerprint,
                ) else sealed
                return remote.putBackup(uid, damaged)
            }
        }
        val r = run { BackupService(corrupting, vault, keys, limiter, Dispatchers.Unconfined).upload("u1", "another passphrase 99") }
        assertEquals(UploadResult.SelfTestFailed, r)
        assertEquals(good, remote.backupOf("u1")!!.sealed)
    }

    @Test fun statusSaysNotBackedUpBackedUpAndWhetherTheKeysMatch() {
        val keys = FakeKeySource(pemA)
        assertEquals(BackupStatus.NotBackedUp, run { service(keys).status("u1") })
        run { service(keys).upload("u1", passphrase) }
        val same = run { service(keys).status("u1") } as BackupStatus.BackedUp
        assertEquals(KeyMatch.SAME, same.match)
        assertEquals(KeyMatch.DIFFERENT, (run { service(FakeKeySource(pemB)).status("u1") } as BackupStatus.BackedUp).match)
        assertEquals(KeyMatch.NO_LOCAL_KEY, (run { service(FakeKeySource(null)).status("u1") } as BackupStatus.BackedUp).match)
    }

    @Test fun statusIsUnknownNotNotBackedUpWhenTheCloudCannotBeAsked() {
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertTrue(run { service(FakeKeySource(pemA)).status("u1") } is BackupStatus.Unknown)
    }

    @Test fun restoreOnAPhoneWithoutAKeyInstallsItAndTheFingerprintMatches() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val fresh = FakeKeySource(null)
        val s = service(fresh)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.InstallNew(keyFingerprint(pemA)), plan)
        assertEquals(RestoreResult.Restored(keyFingerprint(pemA)), run { s.restore("u1", passphrase, plan) })
        assertEquals(pemA, fresh.pem())
    }

    @Test fun restoreWithTheWrongPassphraseInstallsNothing() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val fresh = FakeKeySource(null)
        val s = service(fresh)
        val r = run { s.restore("u1", "wrong passphrase 123", s.plan("u1")) }
        assertTrue(r is RestoreResult.WrongPassphrase)
        assertFalse(fresh.hasKey())
    }

    @Test fun anExistingDifferentKeyNeedsConfirmationAndIsKeptAsABackupCopy() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val phone = FakeKeySource(pemB)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.ReplaceLocal(keyFingerprint(pemB), keyFingerprint(pemA)), plan)
        assertEquals(0, phone.installs)                 // planning changes nothing
        assertEquals(RestoreResult.Restored(keyFingerprint(pemA)), run { s.restore("u1", passphrase, plan) })
        assertEquals(pemA, phone.pem())
        assertEquals(pemB, phone.previousKey)
    }

    @Test fun thePhoneAlreadyHavingTheKeyIsNotReplaced() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val phone = FakeKeySource(pemA)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.AlreadyHaveThisKey(keyFingerprint(pemA)), plan)
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(0, phone.installs)
    }

    @Test fun aKeyThatAppearedAfterThePlanWasMadeIsNotOverwrittenSilently() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }                 // InstallNew
        phone.installPem(pemB)                          // a send created a key meanwhile
        assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(pemB, phone.pem())
    }

    @Test fun aDamagedLocalKeyIsReplacedOnlyAfterThePlanSaidSo() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val phone = FakeKeySource(pemB).apply { damaged = true }
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.ReplaceDamaged(keyFingerprint(pemA)), plan)
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(pemA, phone.pem())
    }

    @Test fun aBackupWhoseKeyDoesNotMatchItsFingerprintIsRefused() {
        // sealed under the right passphrase but claiming another key's fingerprint is impossible to forge (it is in the
        // authenticated data), so build the mismatch honestly: seal pemB while stating pemA's fingerprint
        val sealed = vault.seal(pemB, passphrase, keyFingerprint(pemA))
        remote.seedBackup("u1", RemoteBackup(sealed, 5))
        val phone = FakeKeySource(null)
        val s = service(phone)
        assertEquals(RestoreResult.FingerprintMismatch, run { s.restore("u1", passphrase, s.plan("u1")) })
        assertFalse(phone.hasKey())
    }

    @Test fun noBackupIsNoBackup() {
        val s = service(FakeKeySource(null))
        assertEquals(RestorePlan.NoBackup, run { s.plan("u1") })
        assertEquals(RestoreResult.NoBackup, run { s.restore("u1", passphrase, RestorePlan.NoBackup) })
    }

    @Test fun repeatedWrongPassphrasesAreThrottledAndACorrectOneResets() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        repeat(2) { assertTrue(run { s.restore("u1", "bad passphrase $it xx", plan) } is RestoreResult.WrongPassphrase) }
        val third = run { s.restore("u1", "bad passphrase 3 xx", plan) } as RestoreResult.WrongPassphrase
        assertEquals(5_000, third.nextWaitMs)
        assertEquals(RestoreResult.Throttled(5_000), run { s.restore("u1", passphrase, plan) })   // even the right one waits
        now += 5_001
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(0, limiter.waitMs())
    }

    @Test fun anUnsupportedBackupDoesNotCountAsAWrongGuess() {
        val sealed = vault.seal(pemA, passphrase, keyFingerprint(pemA))
        val newer = com.seyedjafari.analogplus.phone.accounts.vault.SealedKey(2, sealed.kdf, sealed.iterations, sealed.salt, sealed.iv, sealed.ciphertext, sealed.keyFingerprint)
        remote.seedBackup("u1", RemoteBackup(newer, 5))
        val s = service(FakeKeySource(null))
        repeat(5) { assertTrue(run { s.restore("u1", passphrase, RestorePlan.InstallNew("x")) } is RestoreResult.Unsupported) }
        assertEquals(0, limiter.waitMs())
    }

    @Test fun discardDeletesTheBackupAndReportsFailures() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase) }
        assertNull(run { service(FakeKeySource(null)).discard("u1") })
        assertNull(remote.backupOf("u1"))
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(RemoteErrorKind.OFFLINE, run { service(FakeKeySource(null)).discard("u1") })
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/AttemptLimiterTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptLimiterTest {
    private var now = 1_000_000L
    private val kv = MemoryKeyValueStore()
    private fun limiter() = AttemptLimiter(kv) { now }

    @Test fun twoFailuresAreFree() {
        val l = limiter()
        l.recordFailure(); l.recordFailure()
        assertEquals(0, l.waitMs())
    }

    @Test fun delaysDoubleUpToFiveMinutes() {
        assertEquals(listOf(0L, 0L, 5_000L, 10_000L, 20_000L, 40_000L), (1L..6L).map { AttemptLimiter.delayAfter(it) })
        assertEquals(300_000L, AttemptLimiter.delayAfter(30))
        assertEquals(300_000L, AttemptLimiter.delayAfter(10_000))
    }

    @Test fun theWaitCountsDownWithTheClock() {
        val l = limiter()
        repeat(3) { l.recordFailure() }
        assertEquals(5_000, l.waitMs())
        now += 2_000
        assertEquals(3_000, l.waitMs())
        now += 3_000
        assertEquals(0, l.waitMs())
    }

    @Test fun theCountSurvivesARestart() {
        repeat(3) { limiter().recordFailure() }
        assertEquals(5_000, limiter().waitMs())
    }

    @Test fun successResets() {
        val l = limiter()
        repeat(4) { l.recordFailure() }
        l.recordSuccess()
        assertEquals(0, l.waitMs())
        l.recordFailure()
        assertEquals(0, l.waitMs())
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/KeyVaultCostTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The production cost on a device: the same vault, 600 000 iterations, must stay usable (a person waits for it). */
@RunWith(AndroidJUnit4::class)
class KeyVaultCostTest {
    @Test fun sealAndOpenAtTheProductionCostTakeSecondsNotMinutes() {
        val pem = FakeKeySource.samplePem("cost")
        val vault = KeyVault()
        val start = System.nanoTime()
        val sealed = vault.seal(pem, "correct horse battery staple", "0123456789abcdef")
        val sealMs = (System.nanoTime() - start) / 1_000_000
        val openStart = System.nanoTime()
        assertEquals(OpenResult.Opened(pem), vault.open(sealed, "correct horse battery staple"))
        val openMs = (System.nanoTime() - openStart) / 1_000_000
        Log.i("KeyVaultCost", "seal $sealMs ms, open $openMs ms at ${sealed.iterations} iterations")
        assertTrue("seal took $sealMs ms", sealMs < 8_000)
        assertTrue("open took $openMs ms", openMs < 8_000)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*BackupServiceTest*' --tests '*AttemptLimiterTest*'`
Expected: FAIL (compilation: `BackupService`, `AttemptLimiter`, `KeySource` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/KeySource.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.KeyStore

/** The signing key as the backup code sees it. [KeyStoreKeySource] is the real one; tests use a fake. */
interface KeySource {
    fun hasKey(): Boolean

    /** The PEM; generates a key on first use (about a second: off the main thread). Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when damaged. */
    fun pem(): String

    /** Replaces the key with [pem]. Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when it is not a complete key. */
    fun installPem(pem: String)
}

class KeyStoreKeySource(private val keyStore: KeyStore) : KeySource {
    override fun hasKey(): Boolean = keyStore.hasKey()
    override fun pem(): String = keyStore.pem()
    override fun installPem(pem: String) = keyStore.installPem(pem)
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AttemptLimiter.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore

/**
 * Slows down repeated wrong passphrases on this phone. The first two failures are free, the third waits 5 s and each
 * further one doubles the wait up to 5 minutes. It protects against fumbling and casual guessing through the app, not
 * against someone who copies the encrypted backup (that is what the passphrase rules and the KDF cost are for).
 * The count survives restarts; a correct passphrase resets it.
 */
class AttemptLimiter(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    /** Milliseconds until the next attempt is allowed; 0 when one is allowed now. */
    fun waitMs(): Long {
        val failures = kv.getLong(KEY_FAILURES, 0)
        if (failures < FREE_ATTEMPTS + 1) return 0
        val last = kv.getLong(KEY_LAST_AT, 0)
        return maxOf(0, last + delayAfter(failures) - clock())
    }

    fun recordFailure() {
        kv.putLong(KEY_FAILURES, kv.getLong(KEY_FAILURES, 0) + 1)
        kv.putLong(KEY_LAST_AT, clock())
    }

    fun recordSuccess() {
        kv.putLong(KEY_FAILURES, 0)
        kv.putLong(KEY_LAST_AT, 0)
    }

    companion object {
        private const val KEY_FAILURES = "restore_failures"
        private const val KEY_LAST_AT = "restore_last_failure_at"
        private const val FREE_ATTEMPTS = 2
        private const val FIRST_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS = 300_000L

        fun delayAfter(failures: Long): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)
            return minOf(MAX_DELAY_MS, FIRST_DELAY_MS shl doublings.toInt())
        }
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/BackupService.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.SigningKeyException
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class KeyMatch { SAME, DIFFERENT, NO_LOCAL_KEY }

sealed interface BackupStatus {
    data object SignedOut : BackupStatus
    data object Checking : BackupStatus
    data object NotBackedUp : BackupStatus
    data class BackedUp(val fingerprint: String, val createdAtMs: Long, val match: KeyMatch) : BackupStatus
    /** The cloud could not be asked (offline, refused); says nothing about whether a backup exists. */
    data class Unknown(val reason: String) : BackupStatus
}

sealed interface UploadResult {
    data class Ok(val fingerprint: String) : UploadResult
    data object NotSignedIn : UploadResult
    data class KeyUnreadable(val message: String) : UploadResult
    /** The backup was written but reading it back did not give the same key; the previous backup was put back. */
    data object SelfTestFailed : UploadResult
    data class Failed(val kind: RemoteErrorKind, val message: String) : UploadResult
}

/** What a restore would do, decided before the passphrase is asked for. */
sealed interface RestorePlan {
    data object NotSignedIn : RestorePlan
    data object NoBackup : RestorePlan
    data class Unknown(val reason: String) : RestorePlan
    /** This phone has no key: the backup becomes its key. */
    data class InstallNew(val backupFingerprint: String) : RestorePlan
    /** This phone already has exactly this key. */
    data class AlreadyHaveThisKey(val fingerprint: String) : RestorePlan
    /** This phone has another key; replacing it needs the user's confirmation. */
    data class ReplaceLocal(val localFingerprint: String, val backupFingerprint: String) : RestorePlan
    /** This phone's key file is damaged. */
    data class ReplaceDamaged(val backupFingerprint: String) : RestorePlan
}

sealed interface RestoreResult {
    data class Restored(val fingerprint: String) : RestoreResult
    data object NotSignedIn : RestoreResult
    data object NoBackup : RestoreResult
    /** [nextWaitMs] is the wait before the next try (0: none). The passphrase is wrong, or the backup was changed. */
    data class WrongPassphrase(val nextWaitMs: Long) : RestoreResult
    data class Throttled(val waitMs: Long) : RestoreResult
    data class Unsupported(val reason: String) : RestoreResult
    /** The key on this phone is not the one the plan was made for; ask again. */
    data object LocalKeyChanged : RestoreResult
    /** The passphrase opened the backup but the key inside is not the one the backup says it holds. */
    data object FingerprintMismatch : RestoreResult
    data class KeyRejected(val message: String) : RestoreResult
    data class Failed(val message: String) : RestoreResult
}

/**
 * Backs the signing key up (encrypted by [KeyVault]) and restores it. The plain key never leaves [KeySource] except
 * inside this class's stack frames and is never logged; the passphrase is never stored.
 * [cpu] runs the key derivation (about half a second at the production cost).
 */
class BackupService(
    private val remote: RemoteStore,
    private val vault: KeyVault,
    private val keys: KeySource,
    private val limiter: AttemptLimiter,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun status(uid: String): BackupStatus = try {
        val backup = remote.getBackup(uid)
        if (backup == null) BackupStatus.NotBackedUp else {
            val local = withContext(cpu) { localFingerprint() }
            val match = when (local) {
                is LocalKey.None -> KeyMatch.NO_LOCAL_KEY
                is LocalKey.Damaged -> KeyMatch.DIFFERENT
                is LocalKey.Present -> if (local.fingerprint == backup.sealed.keyFingerprint) KeyMatch.SAME else KeyMatch.DIFFERENT
            }
            BackupStatus.BackedUp(backup.sealed.keyFingerprint, backup.createdAtMs, match)
        }
    } catch (e: RemoteException) {
        BackupStatus.Unknown(e.message ?: "The cloud could not be reached.")
    }

    /**
     * Seals this phone's key with [passphrase] and stores it, then reads it back and opens it to prove it works. If the
     * proof fails the previous backup (if any) is written back. Creates the key first if the phone has none yet.
     */
    suspend fun upload(uid: String, passphrase: String): UploadResult {
        val pem = try { withContext(cpu) { keys.pem() } } catch (e: SigningKeyException) {
            return UploadResult.KeyUnreadable(e.message ?: "The signing key could not be read.")
        }
        val fingerprint = keyFingerprint(pem)
        val sealed = withContext(cpu) { vault.seal(pem, passphrase, fingerprint) }
        if (withContext(cpu) { vault.open(sealed, passphrase) } != OpenResult.Opened(pem)) return UploadResult.SelfTestFailed
        return try {
            val previous = remote.getBackup(uid)
            remote.putBackup(uid, sealed)
            val readBack = remote.getBackup(uid)
            val proven = readBack != null && readBack.sealed == sealed &&
                withContext(cpu) { vault.open(readBack.sealed, passphrase) } == OpenResult.Opened(pem)
            if (proven) UploadResult.Ok(fingerprint) else {
                if (previous != null) try { remote.putBackup(uid, previous.sealed) } catch (e: RemoteException) { /* best effort */ }
                UploadResult.SelfTestFailed
            }
        } catch (e: RemoteException) {
            UploadResult.Failed(e.kind, e.message ?: "The backup could not be saved.")
        }
    }

    suspend fun plan(uid: String): RestorePlan = try {
        val backup = remote.getBackup(uid)
        if (backup == null) RestorePlan.NoBackup else {
            val theirs = backup.sealed.keyFingerprint
            when (val local = withContext(cpu) { localFingerprint() }) {
                is LocalKey.None -> RestorePlan.InstallNew(theirs)
                is LocalKey.Damaged -> RestorePlan.ReplaceDamaged(theirs)
                is LocalKey.Present -> if (local.fingerprint == theirs) RestorePlan.AlreadyHaveThisKey(theirs)
                else RestorePlan.ReplaceLocal(local.fingerprint, theirs)
            }
        }
    } catch (e: RemoteException) {
        RestorePlan.Unknown(e.message ?: "The cloud could not be reached.")
    }

    /**
     * Opens the backup with [passphrase], checks that the key inside has the fingerprint the backup states, and makes it
     * this phone's key as [plan] said it would. The caller shows the confirmation that [RestorePlan.ReplaceLocal] and
     * [RestorePlan.ReplaceDamaged] require before calling this.
     */
    suspend fun restore(uid: String, passphrase: String, plan: RestorePlan): RestoreResult {
        val wait = limiter.waitMs()
        if (wait > 0) return RestoreResult.Throttled(wait)
        val backup = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return RestoreResult.Failed(e.message ?: "The cloud could not be reached.")
        } ?: return RestoreResult.NoBackup
        val pem = when (val opened = withContext(cpu) { vault.open(backup.sealed, passphrase) }) {
            is OpenResult.Opened -> opened.pem
            OpenResult.WrongPassphraseOrDamaged -> { limiter.recordFailure(); return RestoreResult.WrongPassphrase(limiter.waitMs()) }
            is OpenResult.Unsupported -> return RestoreResult.Unsupported(opened.reason)
        }
        limiter.recordSuccess()
        val fingerprint = keyFingerprint(pem)
        if (fingerprint != backup.sealed.keyFingerprint) return RestoreResult.FingerprintMismatch
        val local = withContext(cpu) { localFingerprint() }
        val expected = when (plan) {
            is RestorePlan.InstallNew -> local is LocalKey.None
            is RestorePlan.AlreadyHaveThisKey -> local is LocalKey.Present && local.fingerprint == fingerprint
            is RestorePlan.ReplaceLocal -> local is LocalKey.Present && local.fingerprint == plan.localFingerprint
            is RestorePlan.ReplaceDamaged -> local is LocalKey.Damaged
            else -> false
        }
        if (!expected) return RestoreResult.LocalKeyChanged
        if (plan is RestorePlan.AlreadyHaveThisKey) return RestoreResult.Restored(fingerprint)
        return try {
            withContext(cpu) { keys.installPem(pem) }
            RestoreResult.Restored(fingerprint)
        } catch (e: SigningKeyException) {
            RestoreResult.KeyRejected(e.message ?: "The restored key was not accepted.")
        }
    }

    /** Forgotten passphrase: the backup can never be opened, so it is deleted. Does not touch this phone's key. */
    suspend fun discard(uid: String): RemoteErrorKind? = try { remote.deleteBackup(uid); null } catch (e: RemoteException) { e.kind }

    private sealed interface LocalKey {
        data object None : LocalKey
        data object Damaged : LocalKey
        data class Present(val fingerprint: String) : LocalKey
    }

    private fun localFingerprint(): LocalKey {
        if (!keys.hasKey()) return LocalKey.None
        return try { LocalKey.Present(keyFingerprint(keys.pem())) } catch (e: SigningKeyException) { LocalKey.Damaged }
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :phone:testDebugUnitTest --tests '*BackupServiceTest*' --tests '*AttemptLimiterTest*'`
Expected: PASS (18 + 5 tests).

- [ ] **Step 5: Measure the production cost on a device**

Run: `scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.seyedjafari.analogplus.phone.accounts.KeyVaultCostTest && adb -s emulator-5562 logcat -d -s KeyVaultCost:I | tail -1`
Expected: PASS and a line like `seal 1122 ms, open 1029 ms at 600000 iterations`. If a real phone later needs more than about 3 s, lower nothing: raise the minimum phone class in the Play listing or accept it; a backup is made once.

- [ ] **Step 6: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts phone/src/sharedTest phone/src/test/kotlin phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/KeyVaultCostTest.kt
git commit -m "feat(phone): signing-key backup with read-back self-test, restore plan and attempt delay"
```

---

### Task 10: Accounts, account deletion and the controller the UI talks to

**Files:**
- Create: `accounts/AccountManager.kt`, `accounts/AccountDeletion.kt`, `accounts/AccountsController.kt`, `phone/src/sharedTest/kotlin/.../accounts/FakeAccountManager.kt`, `.../FakeCloudSync.kt`
- Test: `phone/src/test/kotlin/.../accounts/AccountDeletionTest.kt`, `.../AccountsControllerTest.kt`

**Interfaces:**
- Consumes: Tasks 5, 7, 8, 9.
- Produces:

```kotlin
enum class AccountFailure { NETWORK, NO_GOOGLE_ACCOUNT, NOT_CONFIGURED, UNKNOWN }
sealed interface AccountState { SignedOut; SigningIn; data class SignedIn(val uid: String, val email: String?); data class Failed(val reason: AccountFailure, val message: String) }
enum class AccountsAvailability { AVAILABLE, NOT_CONFIGURED }
sealed interface ReauthResult { Ok; Cancelled; data class Failed(val message: String) }
sealed interface DeleteUserResult { Deleted; NeedsRecentLogin; data class Failed(val message: String) }
interface AccountManager { val state: StateFlow<AccountState>; suspend fun signIn(); suspend fun reauthenticate(): ReauthResult; suspend fun deleteUser(): DeleteUserResult; suspend fun signOut() }
class UnavailableAccountManager : AccountManager
sealed interface DeletionResult { Done; Cancelled; ReauthFailed(message); DataFailed(message); AccountFailed(message) }
class AccountDeletion(accounts: AccountManager, remote: RemoteStore, sync: CloudSync, journal: SyncJournal) { suspend fun delete(uid: String): DeletionResult }
enum class Busy { NONE, BACKING_UP, RESTORING, DELETING }; enum class NoticeKind { INFO, ERROR }; data class Notice(val text: String, val kind: NoticeKind)
data class AccountsUi(val availability: AccountsAvailability, val account: AccountState, val sync: SyncStatus, val backup: BackupStatus, val busy: Busy, val notice: Notice?, val offer: CurrentOffer?)
class AccountsController(val availability: AccountsAvailability, accounts: AccountManager, sync: CloudSync, backup: BackupService, deletion: AccountDeletion, scope: CoroutineScope) {
    val ui: StateFlow<AccountsUi>
    fun signIn(); fun signOut(); fun dismissNotice(); fun resolveOffer(choice: OfferChoice); fun refresh()
    suspend fun refreshBackup(); suspend fun backUp(passphrase: String): UploadResult; suspend fun planRestore(): RestorePlan
    suspend fun restore(passphrase: String, plan: RestorePlan): RestoreResult; suspend fun discardBackup(): Boolean; suspend fun deleteAccount(): DeletionResult
    companion object { fun unavailable(scope: CoroutineScope): AccountsController }
}
class FakeAccountManager(initial: AccountState = AccountState.SignedOut) : AccountManager     // sharedTest: nextSignIn/nextReauth/nextDelete, signInCalls/reauthCalls/deleteCalls, force(state)
class FakeCloudSync : CloudSync                                                                 // sharedTest: started, stops, choices, setStatus(), setOffer()
```

Deletion order matters and is tested: the Google account is asked for **first** (Firebase refuses to delete an account without a recent sign-in, and asking afterwards would leave the data deleted and the account standing), then sync stops (nothing may write back), then the cloud data goes, then the account. Local designs and the local signing key are never touched. If the data cannot be deleted the account is kept and sync restarts; if only the account cannot be deleted, sync stays off and a retry finishes the job.

- [ ] **Step 1: Write the failing tests**

`phone/src/sharedTest/kotlin/com/seyedjafari/analogplus/phone/accounts/FakeAccountManager.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A scriptable [AccountManager]: set the `next...` outcomes before the call. */
class FakeAccountManager(initial: AccountState = AccountState.SignedOut) : AccountManager {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    var nextSignIn: AccountState = AccountState.SignedIn("u1", "me@example.com")
    var nextReauth: ReauthResult = ReauthResult.Ok
    var nextDelete: DeleteUserResult = DeleteUserResult.Deleted
    var signInCalls = 0
    var reauthCalls = 0
    var deleteCalls = 0

    override suspend fun signIn() {
        signInCalls++
        _state.value = AccountState.SigningIn
        _state.value = nextSignIn
    }

    override suspend fun reauthenticate(): ReauthResult { reauthCalls++; return nextReauth }

    override suspend fun deleteUser(): DeleteUserResult {
        deleteCalls++
        if (nextDelete == DeleteUserResult.Deleted) _state.value = AccountState.SignedOut
        return nextDelete
    }

    override suspend fun signOut() { _state.value = AccountState.SignedOut }

    fun force(state: AccountState) { _state.value = state }
}
```

`phone/src/sharedTest/kotlin/com/seyedjafari/analogplus/phone/accounts/FakeCloudSync.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A [CloudSync] that only records calls and lets tests set [status] and the offer. */
class FakeCloudSync : CloudSync {
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    private val _offer = MutableStateFlow<CurrentOffer?>(null)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val currentOffer: StateFlow<CurrentOffer?> = _offer.asStateFlow()

    val started = ArrayList<String>()
    var stops = 0
    var syncNowCalls = 0
    val choices = ArrayList<OfferChoice>()

    override fun start(uid: String) { started += uid; _status.value = SyncStatus.Syncing }
    override fun stop() { stops++; _status.value = SyncStatus.Off }
    override suspend fun syncNow() { syncNowCalls++ }
    override fun resolveOffer(choice: OfferChoice) { choices += choice; _offer.value = null }

    fun setStatus(s: SyncStatus) { _status.value = s }
    fun setOffer(o: CurrentOffer?) { _offer.value = o }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountDeletionTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionTest {
    private val accounts = FakeAccountManager(AccountState.SignedIn("u1", "me@example.com"))
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val journal = SyncJournal(MemoryKeyValueStore())
    private val deletion = AccountDeletion(accounts, remote, sync, journal)

    init {
        remote.seedDesign("u1", RemoteDesign("a".repeat(32), "Night", "{}", 5, DesignState.LIVE, 1))
        journal.bindTo("u1")
    }

    private fun delete() = runBlocking { deletion.delete("u1") }

    @Test fun happyPathReauthsThenDeletesDataThenTheAccountAndForgetsTheBinding() {
        assertEquals(DeletionResult.Done, delete())
        assertEquals("u1", remote.deletedAllFor)
        assertTrue(remote.designsOf("u1").isEmpty())
        assertEquals(1, accounts.deleteCalls)
        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertNull(journal.boundUid())
        assertEquals(1, sync.stops)
    }

    @Test fun aCancelledReauthDeletesNothingAndLeavesSyncAlone() {
        accounts.nextReauth = ReauthResult.Cancelled
        assertEquals(DeletionResult.Cancelled, delete())
        assertEquals(1, remote.designsOf("u1").size)
        assertEquals(0, sync.stops)
        assertEquals(0, accounts.deleteCalls)
    }

    @Test fun aFailedReauthDeletesNothing() {
        accounts.nextReauth = ReauthResult.Failed("no network")
        assertEquals(DeletionResult.ReauthFailed("no network"), delete())
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun aDataFailureKeepsTheAccountAndRestartsSync() {
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DeletionResult.DataFailed("offline"), delete())
        assertEquals(0, accounts.deleteCalls)
        assertEquals(listOf("u1"), sync.started)
    }

    @Test fun anAccountFailureAfterTheDataIsGoneKeepsSyncOffSoNothingIsResurrected() {
        accounts.nextDelete = DeleteUserResult.Failed("try later")
        assertEquals(DeletionResult.AccountFailed("try later"), delete())
        assertTrue(remote.designsOf("u1").isEmpty())
        assertTrue(sync.started.isEmpty())
        accounts.nextDelete = DeleteUserResult.Deleted
        assertEquals(DeletionResult.Done, delete())      // trying again finishes the job
    }

    @Test fun needingARecentLoginIsReportedAsAnAccountFailure() {
        accounts.nextDelete = DeleteUserResult.NeedsRecentLogin
        assertTrue(delete() is DeletionResult.AccountFailed)
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsControllerTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsControllerTest {
    private val accounts = FakeAccountManager()
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val keys = FakeKeySource(FakeKeySource.samplePem("A"))
    private val journal = SyncJournal(MemoryKeyValueStore())
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val passphrase = "correct horse battery staple"

    private fun TestScope.controller() = AccountsController(
        AccountsAvailability.AVAILABLE, accounts, sync,
        BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
        AccountDeletion(accounts, remote, sync, journal), backgroundScope,
    )

    @Test fun startsSignedOutWithNoSyncAndNoBackupStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        val ui = c.ui.value
        assertEquals(AccountState.SignedOut, ui.account)
        assertEquals(SyncStatus.Off, ui.sync)
        assertEquals(BackupStatus.SignedOut, ui.backup)
        assertEquals(Busy.NONE, ui.busy)
    }

    @Test fun signingInStartsSyncAndChecksTheBackup() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertEquals(listOf("u1"), sync.started)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun signingOutStopsSyncAndClearsTheBackupStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.signOut()
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
        assertTrue(sync.stops >= 1)
    }

    @Test fun aFailedSignInIsShownAsFailed() = runTest(UnconfinedTestDispatcher()) {
        accounts.nextSignIn = AccountState.Failed(AccountFailure.NETWORK, "offline")
        val c = controller()
        c.signIn()
        assertEquals(AccountState.Failed(AccountFailure.NETWORK, "offline"), c.ui.value.account)
        assertTrue(sync.started.isEmpty())
    }

    @Test fun syncStatusAndTheOfferFlowThrough() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        sync.setStatus(SyncStatus.Idle(5))
        assertEquals(SyncStatus.Idle(5), c.ui.value.sync)
        val offer = CurrentOffer("Pixel 8", DesignSettings(), 9)
        sync.setOffer(offer)
        assertEquals(offer, c.ui.value.offer)
        c.resolveOffer(OfferChoice.USE_REMOTE)
        assertNull(c.ui.value.offer)
        assertEquals(listOf(OfferChoice.USE_REMOTE), sync.choices)
    }

    @Test fun backingUpReportsAndRefreshesTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val r = c.backUp(passphrase)
        assertTrue(r is UploadResult.Ok)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(NoticeKind.INFO, c.ui.value.notice!!.kind)
        assertEquals(KeyMatch.SAME, (c.ui.value.backup as BackupStatus.BackedUp).match)
    }

    @Test fun backingUpSignedOutIsRefused() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(UploadResult.NotSignedIn, controller().backUp(passphrase))
    }

    @Test fun anOfflineBackupExplainsItself() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        c.backUp(passphrase)
        assertEquals(NoticeKind.ERROR, c.ui.value.notice!!.kind)
        assertTrue(c.ui.value.notice!!.text.contains("offline"))
    }

    @Test fun restoringReportsAWrongPassphraseWithoutLeavingBusy() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.backUp(passphrase)
        val plan = c.planRestore()
        val r = c.restore("not the passphrase 1", plan)
        assertTrue(r is RestoreResult.WrongPassphrase)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(NoticeKind.ERROR, c.ui.value.notice!!.kind)
    }

    @Test fun discardingAnUnreadableBackupRefreshesTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.backUp(passphrase)
        assertTrue(c.discardBackup())
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun deletingTheAccountSignsOutAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(DeletionResult.Done, c.deleteAccount())
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertTrue(c.ui.value.notice!!.text.contains("deleted"))
    }

    @Test fun theUnavailableControllerOffersNoSignIn() = runTest(UnconfinedTestDispatcher()) {
        val c = AccountsController.unavailable(backgroundScope)
        assertEquals(AccountsAvailability.NOT_CONFIGURED, c.ui.value.availability)
        c.signIn()
        assertEquals(AccountFailure.NOT_CONFIGURED, (c.ui.value.account as AccountState.Failed).reason)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*AccountDeletionTest*' --tests '*AccountsControllerTest*'`
Expected: FAIL (compilation: `AccountManager`, `AccountDeletion`, `AccountsController` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountManager.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a sign-in did not work, in terms a person can act on. */
enum class AccountFailure { NETWORK, NO_GOOGLE_ACCOUNT, NOT_CONFIGURED, UNKNOWN }

sealed interface AccountState {
    data object SignedOut : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val uid: String, val email: String?) : AccountState
    data class Failed(val reason: AccountFailure, val message: String) : AccountState
}

/** Whether this build can talk to a Firebase project at all (`google-services.json` was present at build time). */
enum class AccountsAvailability { AVAILABLE, NOT_CONFIGURED }

sealed interface ReauthResult {
    data object Ok : ReauthResult
    data object Cancelled : ReauthResult
    data class Failed(val message: String) : ReauthResult
}

sealed interface DeleteUserResult {
    data object Deleted : DeleteUserResult
    data object NeedsRecentLogin : DeleteUserResult
    data class Failed(val message: String) : DeleteUserResult
}

/**
 * Who is signed in. The Firebase implementation lives in `accounts/firebase`; nothing else in the app imports Firebase.
 * Every function reports problems through its result or through [state]; none throws (except cancellation).
 */
interface AccountManager {
    val state: StateFlow<AccountState>

    /** Shows the Google account chooser. Ends in [AccountState.SignedIn], [AccountState.SignedOut] (cancelled) or [AccountState.Failed]. */
    suspend fun signIn()

    /** Asks for the Google account again; required before deleting the account. */
    suspend fun reauthenticate(): ReauthResult

    /** Deletes the Firebase Auth user. Call [reauthenticate] first. On success [state] becomes [AccountState.SignedOut]. */
    suspend fun deleteUser(): DeleteUserResult

    suspend fun signOut()
}

/** Used when the build has no Firebase project: the Account screen explains it and offers no sign-in. */
class UnavailableAccountManager : AccountManager {
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val state: StateFlow<AccountState> = _state.asStateFlow()
    override suspend fun signIn() {
        _state.value = AccountState.Failed(AccountFailure.NOT_CONFIGURED, "Sign-in is not set up in this build.")
    }
    override suspend fun reauthenticate(): ReauthResult = ReauthResult.Failed("Sign-in is not set up in this build.")
    override suspend fun deleteUser(): DeleteUserResult = DeleteUserResult.Failed("Sign-in is not set up in this build.")
    override suspend fun signOut() { _state.value = AccountState.SignedOut }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountDeletion.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts


sealed interface DeletionResult {
    data object Done : DeletionResult
    data object Cancelled : DeletionResult
    /** Nothing was deleted. */
    data class ReauthFailed(val message: String) : DeletionResult
    /** Nothing was deleted (or only part of the cloud data; trying again finishes it). Sync is running again. */
    data class DataFailed(val message: String) : DeletionResult
    /** The cloud data is gone but the sign-in account is not; trying again finishes it. Sync stays off. */
    data class AccountFailed(val message: String) : DeletionResult
}

/**
 * Deletes everything the cloud holds for the user, then the account. Order matters: the Google account is asked for
 * again first (Firebase refuses to delete an account without a recent sign-in, and asking later would leave the data
 * deleted and the account standing), then sync is stopped so nothing is written back, then the data, then the user.
 * Local designs and the local signing key are never touched.
 */
class AccountDeletion(
    private val accounts: AccountManager,
    private val remote: RemoteStore,
    private val sync: CloudSync,
    private val journal: SyncJournal,
) {
    suspend fun delete(uid: String): DeletionResult {
        when (val r = accounts.reauthenticate()) {
            ReauthResult.Ok -> Unit
            ReauthResult.Cancelled -> return DeletionResult.Cancelled
            is ReauthResult.Failed -> return DeletionResult.ReauthFailed(r.message)
        }
        sync.stop()
        try {
            remote.deleteAllUserData(uid)
        } catch (e: RemoteException) {
            sync.start(uid)
            return DeletionResult.DataFailed(e.message ?: "The cloud data could not be deleted.")
        }
        return when (val r = accounts.deleteUser()) {
            DeleteUserResult.Deleted -> { journal.unbind(); DeletionResult.Done }
            DeleteUserResult.NeedsRecentLogin -> DeletionResult.AccountFailed("Please sign in again, then delete the account.")
            is DeleteUserResult.Failed -> DeletionResult.AccountFailed(r.message)
        }
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsController.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the person is waiting for right now. */
enum class Busy { NONE, BACKING_UP, RESTORING, DELETING }

enum class NoticeKind { INFO, ERROR }

/** A message shown once under the account section until dismissed. */
data class Notice(val text: String, val kind: NoticeKind)

/** Everything the Account screen and the onboarding backup page draw from. */
data class AccountsUi(
    val availability: AccountsAvailability,
    val account: AccountState,
    val sync: SyncStatus,
    val backup: BackupStatus,
    val busy: Busy,
    val notice: Notice?,
    val offer: CurrentOffer?,
)

/**
 * The one object the UI talks to. It joins [AccountManager], [CloudSync] and [BackupService]: signing in starts sync and
 * checks the backup, signing out stops sync, and every action reports through [ui]. Pure Kotlin, no Firebase.
 */
class AccountsController(
    val availability: AccountsAvailability,
    private val accounts: AccountManager,
    private val sync: CloudSync,
    private val backup: BackupService,
    private val deletion: AccountDeletion,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(
        AccountsUi(availability, accounts.state.value, sync.status.value, BackupStatus.SignedOut, Busy.NONE, null, sync.currentOffer.value),
    )
    val ui: StateFlow<AccountsUi> = _ui.asStateFlow()

    init {
        scope.launch { accounts.state.collect { onAccountState(it) } }
        scope.launch { sync.status.collect { s -> _ui.update { it.copy(sync = s) } } }
        scope.launch { sync.currentOffer.collect { o -> _ui.update { it.copy(offer = o) } } }
    }

    private fun uid(): String? = (accounts.state.value as? AccountState.SignedIn)?.uid

    private suspend fun onAccountState(state: AccountState) {
        _ui.update { it.copy(account = state) }
        when (state) {
            is AccountState.SignedIn -> {
                sync.start(state.uid)
                refreshBackup()
            }
            else -> {
                sync.stop()
                _ui.update { it.copy(backup = BackupStatus.SignedOut) }
            }
        }
    }

    fun signIn() { scope.launch { accounts.signIn() } }

    fun signOut() { scope.launch { accounts.signOut() } }

    fun dismissNotice() { _ui.update { it.copy(notice = null) } }

    fun resolveOffer(choice: OfferChoice) = sync.resolveOffer(choice)

    /** Re-reads the cloud copy now and re-checks the backup. */
    fun refresh() {
        scope.launch {
            sync.syncNow()
            refreshBackup()
        }
    }

    suspend fun refreshBackup() {
        val uid = uid() ?: return
        _ui.update { it.copy(backup = BackupStatus.Checking) }
        val status = backup.status(uid)
        _ui.update { it.copy(backup = status) }
    }

    /** Encrypts this phone's signing key with [passphrase] and stores it. Also used to change the passphrase. */
    suspend fun backUp(passphrase: String): UploadResult {
        val uid = uid() ?: return UploadResult.NotSignedIn
        _ui.update { it.copy(busy = Busy.BACKING_UP, notice = null) }
        val result = backup.upload(uid, passphrase)
        _ui.update { it.copy(busy = Busy.NONE, notice = noticeFor(result)) }
        refreshBackup()
        return result
    }

    suspend fun planRestore(): RestorePlan {
        val uid = uid() ?: return RestorePlan.NotSignedIn
        _ui.update { it.copy(busy = Busy.RESTORING) }
        val plan = backup.plan(uid)
        _ui.update {
            it.copy(
                busy = Busy.NONE,
                notice = when (plan) {
                    RestorePlan.NoBackup -> Notice("There is no backup for this account yet.", NoticeKind.ERROR)
                    is RestorePlan.Unknown -> Notice("The backup could not be checked: ${plan.reason}", NoticeKind.ERROR)
                    RestorePlan.NotSignedIn -> Notice("Sign in first.", NoticeKind.ERROR)
                    else -> it.notice
                },
            )
        }
        return plan
    }

    suspend fun restore(passphrase: String, plan: RestorePlan): RestoreResult {
        val uid = uid() ?: return RestoreResult.NotSignedIn
        _ui.update { it.copy(busy = Busy.RESTORING, notice = null) }
        val result = backup.restore(uid, passphrase, plan)
        _ui.update { it.copy(busy = Busy.NONE, notice = noticeFor(result)) }
        if (result is RestoreResult.Restored) refreshBackup()
        return result
    }

    /** Forgotten passphrase: deletes the unreadable backup. The screen shows [KeyResetCopy] before calling this. */
    suspend fun discardBackup(): Boolean {
        val uid = uid() ?: return false
        val failure = backup.discard(uid)
        _ui.update {
            it.copy(notice = if (failure == null) Notice("The backup was deleted. Back the key up again with a new passphrase when you like.", NoticeKind.INFO)
            else Notice("The backup could not be deleted. Check your connection and try again.", NoticeKind.ERROR))
        }
        refreshBackup()
        return failure == null
    }

    suspend fun deleteAccount(): DeletionResult {
        val uid = uid() ?: return DeletionResult.ReauthFailed("You are not signed in.")
        _ui.update { it.copy(busy = Busy.DELETING, notice = null) }
        val result = deletion.delete(uid)
        _ui.update {
            it.copy(
                busy = Busy.NONE,
                notice = when (result) {
                    DeletionResult.Done -> Notice("Your account and its cloud data were deleted. Designs on this phone are untouched.", NoticeKind.INFO)
                    DeletionResult.Cancelled -> null
                    is DeletionResult.ReauthFailed -> Notice("Nothing was deleted: ${result.message}", NoticeKind.ERROR)
                    is DeletionResult.DataFailed -> Notice("The cloud data could not be deleted (${result.message}). Nothing changed.", NoticeKind.ERROR)
                    is DeletionResult.AccountFailed -> Notice("Your cloud data was deleted but the account was not (${result.message}). Try again.", NoticeKind.ERROR)
                },
            )
        }
        return result
    }

    private fun noticeFor(r: UploadResult): Notice = when (r) {
        is UploadResult.Ok -> Notice("Signing key backed up and checked (key ${r.fingerprint}).", NoticeKind.INFO)
        UploadResult.NotSignedIn -> Notice("Sign in first.", NoticeKind.ERROR)
        is UploadResult.KeyUnreadable -> Notice(r.message, NoticeKind.ERROR)
        UploadResult.SelfTestFailed -> Notice("The backup could not be read back correctly, so it was not kept. Try again.", NoticeKind.ERROR)
        is UploadResult.Failed -> Notice(
            if (r.kind == RemoteErrorKind.OFFLINE) "You are offline. The backup was not saved." else "The backup was not saved: ${r.message}",
            NoticeKind.ERROR,
        )
    }

    private fun noticeFor(r: RestoreResult): Notice? = when (r) {
        is RestoreResult.Restored -> Notice("Signing key restored (key ${r.fingerprint}). Send to watch will now update your existing face.", NoticeKind.INFO)
        RestoreResult.NotSignedIn -> Notice("Sign in first.", NoticeKind.ERROR)
        RestoreResult.NoBackup -> Notice("There is no backup for this account.", NoticeKind.ERROR)
        is RestoreResult.WrongPassphrase -> Notice("That passphrase did not open the backup. Check it and try again.", NoticeKind.ERROR)
        is RestoreResult.Throttled -> Notice("Too many wrong tries. Wait ${(r.waitMs + 999) / 1000} seconds.", NoticeKind.ERROR)
        is RestoreResult.Unsupported -> Notice("This backup was made by a newer version of the app (${r.reason}). Update Analog Plus.", NoticeKind.ERROR)
        RestoreResult.LocalKeyChanged -> Notice("This phone's signing key changed while you were typing. Start the restore again.", NoticeKind.ERROR)
        RestoreResult.FingerprintMismatch -> Notice("The backup opened but holds a different key than it should, so it was not used.", NoticeKind.ERROR)
        is RestoreResult.KeyRejected -> Notice(r.message, NoticeKind.ERROR)
        is RestoreResult.Failed -> Notice("The restore failed: ${r.message}", NoticeKind.ERROR)
    }

    companion object {
        /** A controller for a build without Firebase: signed out for good, sync off, every cloud call refused. */
        fun unavailable(scope: CoroutineScope): AccountsController {
            val accounts = UnavailableAccountManager()
            val sync = NoCloudSync()
            val remote = NoRemoteStore()
            val journal = SyncJournal(NoPersistKeyValueStore())
            return AccountsController(
                AccountsAvailability.NOT_CONFIGURED, accounts, sync,
                BackupService(remote, com.seyedjafari.analogplus.phone.accounts.vault.KeyVault(), NoKeySource(), AttemptLimiter(NoPersistKeyValueStore())),
                AccountDeletion(accounts, remote, sync, journal), scope,
            )
        }
    }
}

/** A [RemoteStore] for builds without a cloud: every call fails with [RemoteErrorKind.OTHER]. */
class NoRemoteStore : RemoteStore {
    private fun fail(): Nothing = throw RemoteException(RemoteErrorKind.OTHER, "The cloud is not set up in this build.")
    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> = fail()
    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = emptyFlow()
    override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign = fail()
    override suspend fun getCurrent(uid: String): RemoteCurrent? = fail()
    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = emptyFlow()
    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent = fail()
    override suspend fun getBackup(uid: String): RemoteBackup? = fail()
    override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup = fail()
    override suspend fun deleteBackup(uid: String) = fail()
    override suspend fun deleteAllUserData(uid: String) = fail()
}

/** A [KeySource] for builds without a cloud: it has no key and never creates one. */
class NoKeySource : KeySource {
    override fun hasKey(): Boolean = false
    override fun pem(): String = throw com.seyedjafari.analogplus.facegen.SigningKeyException("Backups are not available in this build.")
    override fun installPem(pem: String) = throw com.seyedjafari.analogplus.facegen.SigningKeyException("Backups are not available in this build.")
}

/** A [com.seyedjafari.analogplus.phone.KeyValueStore] that keeps nothing, for the unavailable controller. */
class NoPersistKeyValueStore : com.seyedjafari.analogplus.phone.KeyValueStore {
    override fun getString(key: String): String? = null
    override fun getLong(key: String, default: Long): Long = default
    override fun putString(key: String, value: String) {}
    override fun putLong(key: String, value: Long) {}
}
```

- [ ] **Step 4: Run to verify they pass, then the whole JVM suite**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: PASS (6 + 12 new; every earlier test too).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts phone/src/sharedTest phone/src/test/kotlin
git commit -m "feat(phone): account model, account deletion and the accounts controller"
```

---

### Task 11: Firebase implementations, emulator-backed tests and the e2e script

The only code that imports Firebase. Everything above it is already tested against fakes; this task proves the real implementations against the real Firestore rules and the Auth emulator.

**Files:**
- Create: `accounts/firebase/{FirebaseProvider,FirestoreRemoteStore,FirebaseAccountManager,IdTokenSource,CurrentActivity}.kt`, `accounts/AccountsFactory.kt`, `phone/src/debug/kotlin/.../accounts/firebase/FirebaseBuildType.kt`, `phone/src/release/kotlin/.../accounts/firebase/FirebaseBuildType.kt`, `scripts/accounts_e2e.sh`
- Test: `phone/src/test/kotlin/.../accounts/RemoteErrorKindTest.kt`; `phone/src/androidTest/kotlin/.../accounts/FirebaseEmulatorTest.kt`, `.../AccountsFactoryTest.kt`

**Interfaces:**
- Consumes: Tasks 3, 4, 7-10.
- Produces:

```kotlin
enum class FirebaseMode { NOT_CONFIGURED, PRODUCTION, EMULATOR }
data class FirebaseSetup(val mode: FirebaseMode, val webClientId: String) { val availability: AccountsAvailability }
object FirebaseProvider { const val EMULATOR_HOST = "10.0.2.2"; const val AUTH_EMULATOR_PORT = 9099; const val FIRESTORE_EMULATOR_PORT = 8080; const val EMULATOR_PROJECT_ID = "demo-analogplus"; fun setUp(context: Context): FirebaseSetup }
// per build type (debug: marker file or no google-services.json; release: never):
object EmulatorSwitch { fun requested(context: Context): Boolean }; fun emulatorEmail(context: Context): String; object AppCheckInstaller { fun install() }
fun interface IdTokenSource { suspend fun fetchIdToken(): String? }       // null = the person closed the chooser
class NoGoogleAccountException : Exception
class GoogleIdTokenSource(credentialManager: CredentialManager, webClientId: String, activity: () -> Context?) : IdTokenSource
class EmulatorIdTokenSource(email: () -> String) : IdTokenSource
class FirebaseAccountManager(auth: FirebaseAuth, tokens: IdTokenSource, credentialManager: CredentialManager?) : AccountManager
class FirestoreRemoteStore(db: FirebaseFirestore, writeTimeoutMs: Long = 15_000) : RemoteStore
object CurrentActivity { fun set(activity: Activity); fun clear(activity: Activity); fun get(): Activity? }
object AccountsFactory { fun create(context: Context, store: Store, scope: CoroutineScope, currentDesign: () -> DesignSettings, applier: CurrentApplier, keyStore: KeyStore = KeyStore(context), syncKv: KeyValueStore = <prefs "analogplus_sync">): AccountsController }
```

Design notes. **Sign in with Google** uses Credential Manager's `GetSignInWithGoogleOption` (the explicit "Sign in with Google" button flow) with the web client id from `default_web_client_id`, then `GoogleAuthProvider.getCredential(idToken, null)` into Firebase Auth. **The Firestore cache is memory-only** (`MemoryCacheSettings`): the sync journal is the durable offline queue, and with a persistent cache a write issued offline would also be queued by Firestore and `await()` would hang; writes therefore give up after 15 s and report `OFFLINE`. **App Check**: release installs the Play Integrity provider, debug installs the debug provider (it logs a token to register once; Task 1 item 6); enforcement is switched on in the console, not in code. **Emulator mode** (debug only) initialises a `demo-analogplus` Firebase app, points Auth and Firestore at `10.0.2.2`, and uses `EmulatorIdTokenSource`: the Auth emulator accepts a JSON string as a Google ID token, so the sign-in button works on an emulator with no Google account (the account is the first line of the marker file `files/use-firebase-emulator`, default `tester@example.com`). The "tracks the signed-in account" logic lives in `AccountsController`; this layer only translates.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/RemoteErrorKindTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteErrorKindTest {
    @Test fun networkishCodesAreOffline() {
        listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED").forEach { assertEquals(it, RemoteErrorKind.OFFLINE, remoteErrorKind(it)) }
    }

    @Test fun refusalsArePermission() {
        listOf("PERMISSION_DENIED", "UNAUTHENTICATED").forEach { assertEquals(it, RemoteErrorKind.PERMISSION, remoteErrorKind(it)) }
    }

    @Test fun quotaIsQuotaAndTheRestIsOther() {
        assertEquals(RemoteErrorKind.QUOTA, remoteErrorKind("RESOURCE_EXHAUSTED"))
        listOf("NOT_FOUND", "ALREADY_EXISTS", "INTERNAL", "ABORTED", "whatever").forEach { assertEquals(it, RemoteErrorKind.OTHER, remoteErrorKind(it)) }
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/FirebaseEmulatorTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseAccountManager
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.google.firebase.firestore.FirebaseFirestore
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Firebase code against the Firebase Emulator Suite on the host (`cd firebase && npm run emulators`), with
 * the rules from `firebase/firestore.rules` loaded. Skipped when the emulators are not reachable.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseEmulatorTest {
    private lateinit var auth: FirebaseAuth
    private lateinit var remote: FirestoreRemoteStore

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(FirebaseProvider.EMULATOR_HOST, port), 1_500) }; true
    } catch (e: Exception) { false }

    @Before fun setUp() {
        assumeTrue("Firebase emulators are not running", reachable(FirebaseProvider.AUTH_EMULATOR_PORT) && reachable(FirebaseProvider.FIRESTORE_EMULATOR_PORT))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(FirebaseMode.EMULATOR, FirebaseProvider.setUp(context).mode)
        auth = FirebaseAuth.getInstance()
        remote = FirestoreRemoteStore(FirebaseFirestore.getInstance(), writeTimeoutMs = 10_000)
    }

    /** The Auth emulator accepts a JSON string as a Google ID token. */
    private fun fakeToken(sub: String, email: String) = """{"sub":"$sub","email":"$email","email_verified":true}"""

    private fun manager(sub: String) = FirebaseAccountManager(auth, IdTokenSource { fakeToken(sub, "$sub@example.com") }, null)

    private suspend fun signInAs(sub: String): String {
        val m = manager(sub)
        m.signIn()
        return (m.state.value as AccountState.SignedIn).uid
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(60_000) { block() } }

    @Test fun signInReauthAndDeleteAgainstTheAuthEmulator() = run {
        val m = manager("auth-${System.nanoTime()}")
        m.signIn()
        val signedIn = m.state.value as AccountState.SignedIn
        assertTrue(signedIn.email!!.endsWith("@example.com"))
        assertEquals(ReauthResult.Ok, m.reauthenticate())
        assertEquals(DeleteUserResult.Deleted, m.deleteUser())
        assertEquals(AccountState.SignedOut, m.state.value)
        assertNull(auth.currentUser)
    }

    @Test fun designsRoundTripThroughFirestoreAndTheRulesAcceptTheRealDocuments() = run {
        val uid = signInAs("designs-${System.nanoTime()}")
        val id = designId("Night")
        val json = DesignJson.toJson(DesignSettings(name = "Night"))
        val written = remote.putDesign(uid, id, "Night", json, DesignState.LIVE, DESIGN_SCHEMA)
        assertEquals(json, written.designJson)
        assertTrue(written.updatedAtMs > 1_700_000_000_000)
        val tomb = remote.putDesign(uid, id, "Night", "", DesignState.TOMBSTONE, DESIGN_SCHEMA)
        assertEquals(DesignState.TOMBSTONE, tomb.state)
        assertTrue(tomb.updatedAtMs >= written.updatedAtMs)
        assertEquals(listOf(DesignState.TOMBSTONE), remote.fetchDesigns(uid).map { it.state })
        assertEquals(1, remote.observeDesigns(uid).first().size)
    }

    @Test fun theCurrentDesignRoundTrips() = run {
        val uid = signInAs("current-${System.nanoTime()}")
        assertNull(remote.getCurrent(uid))
        val put = remote.putCurrent(uid, DesignJson.toJson(DesignSettings()), "device-1", "Pixel 8")
        assertEquals(put, remote.getCurrent(uid))
        assertEquals("device-1", remote.observeCurrent(uid).first()!!.deviceId)
    }

    @Test fun aSealedKeyBackupRoundTripsAndStillOpens() = run {
        val uid = signInAs("backup-${System.nanoTime()}")
        val vault = KeyVault(iterations = 100_000)
        val pem = FakeKeySource.samplePem("emu")
        val sealed = vault.seal(pem, "correct horse battery staple", "0123456789abcdef")
        assertNull(remote.getBackup(uid))
        val stored = remote.putBackup(uid, sealed)
        assertEquals(sealed, stored.sealed)
        assertEquals(OpenResult.Opened(pem), vault.open(remote.getBackup(uid)!!.sealed, "correct horse battery staple"))
        remote.deleteBackup(uid)
        assertNull(remote.getBackup(uid))
    }

    @Test fun anotherUsersDataIsRefusedWithAPermissionError() = run {
        val alice = signInAs("alice-${System.nanoTime()}")
        remote.putDesign(alice, designId("Night"), "Night", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        signInAs("bob-${System.nanoTime()}")
        try {
            remote.fetchDesigns(alice)
            fail("bob must not read alice's designs")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
    }

    @Test fun anOversizedDesignIsRefusedByTheRules() = run {
        val uid = signInAs("big-${System.nanoTime()}")
        try {
            remote.putDesign(uid, designId("Big"), "Big", "x".repeat(960_000), DesignState.LIVE, DESIGN_SCHEMA)
            fail("rules must refuse a design over 950 000 characters")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
    }

    @Test fun deleteAllUserDataLeavesNothingBehind() = run {
        val uid = signInAs("wipe-${System.nanoTime()}")
        repeat(3) { remote.putDesign(uid, designId("D$it"), "D$it", "{}", DesignState.LIVE, DESIGN_SCHEMA) }
        remote.putCurrent(uid, "{}", "d", "Pixel")
        remote.putBackup(uid, KeyVault(iterations = 100_000).seal(FakeKeySource.samplePem("w"), "correct horse battery staple", "0123456789abcdef"))
        remote.deleteAllUserData(uid)
        assertTrue(remote.fetchDesigns(uid).isEmpty())
        assertNull(remote.getCurrent(uid))
        assertNull(remote.getBackup(uid))
        assertNotNull(auth.currentUser)
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsFactoryTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The factory with hermetic storage: it attaches the journal to the store, and a debug build always has some Firebase to talk to. */
@RunWith(AndroidJUnit4::class)
class AccountsFactoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = System.nanoTime()

    @After fun stop() {
        scope.coroutineContext[Job]?.cancel()
        File(context.filesDir, "factory-$tag.pem").delete()
    }

    @Test fun aDebugBuildHasAccountsAvailableAndTheJournalRecordsEditsFromTheStart() {
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "factory-$tag.jpg"))
        val syncKv = MemoryKeyValueStore()
        val controller = AccountsFactory.create(
            context, store, scope, { DesignSettings() }, CurrentApplier { }, KeyStore(context, "factory-$tag.pem"), syncKv,
        )
        // google-services.json present: the owner's project; absent: the emulator. Either way sign-in is offered.
        assertEquals(AccountsAvailability.AVAILABLE, controller.availability)
        assertEquals(AccountState.SignedOut, controller.ui.value.account)
        store.saveNamed("Night", DesignSettings(name = "Night"))
        assertTrue(SyncJournal(syncKv).entries().containsKey(designId("Night")))
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:compileDebugAndroidTestKotlin`
Expected: FAIL (compilation: `FirebaseProvider`, `FirestoreRemoteStore`, `FirebaseAccountManager`, `AccountsFactory` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/FirebaseProvider.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability

enum class FirebaseMode { NOT_CONFIGURED, PRODUCTION, EMULATOR }

/** What [FirebaseProvider.setUp] found. [webClientId] is the OAuth web client Google sign-in needs ("" unless PRODUCTION). */
data class FirebaseSetup(val mode: FirebaseMode, val webClientId: String) {
    val availability: AccountsAvailability
        get() = if (mode == FirebaseMode.NOT_CONFIGURED) AccountsAvailability.NOT_CONFIGURED else AccountsAvailability.AVAILABLE
}

/**
 * The only place that decides which Firebase this process talks to.
 *
 * - **NOT_CONFIGURED**: the build had no `google-services.json`, so there is no default `FirebaseApp`; the app runs with
 *   accounts switched off and nothing in this package is used.
 * - **PRODUCTION**: the owner's project, with App Check ([AppCheckInstaller]).
 * - **EMULATOR** (debug builds only, see [EmulatorSwitch]): a `demo-analogplus` project against the Firebase Emulator
 *   Suite on the host (10.0.2.2 from an Android emulator); no Google account or network needed.
 *
 * Firestore uses a memory-only cache: the sync journal is the durable offline queue.
 */
object FirebaseProvider {
    const val EMULATOR_HOST = "10.0.2.2"
    const val AUTH_EMULATOR_PORT = 9099
    const val FIRESTORE_EMULATOR_PORT = 8080
    const val EMULATOR_PROJECT_ID = "demo-analogplus"

    @Volatile private var cached: FirebaseSetup? = null

    @Synchronized
    fun setUp(context: Context): FirebaseSetup {
        cached?.let { return it }
        val app = context.applicationContext
        val setup = when {
            EmulatorSwitch.requested(app) -> setUpEmulator(app)
            FirebaseApp.getApps(app).isEmpty() -> FirebaseSetup(FirebaseMode.NOT_CONFIGURED, "")
            else -> setUpProduction(app)
        }
        cached = setup
        return setup
    }

    private fun memoryOnly(db: FirebaseFirestore) {
        db.firestoreSettings = FirebaseFirestoreSettings.Builder(db.firestoreSettings)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    private fun setUpProduction(app: Context): FirebaseSetup {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        if (id == 0) return FirebaseSetup(FirebaseMode.NOT_CONFIGURED, "")   // google-services.json without a web OAuth client
        AppCheckInstaller.install()
        memoryOnly(FirebaseFirestore.getInstance())
        return FirebaseSetup(FirebaseMode.PRODUCTION, app.getString(id))
    }

    private fun setUpEmulator(app: Context): FirebaseSetup {
        if (FirebaseApp.getApps(app).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(EMULATOR_PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("fake-api-key-for-the-emulator")
                .build()
            FirebaseApp.initializeApp(app, options)
        }
        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
        val db = FirebaseFirestore.getInstance()
        memoryOnly(db)
        db.useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
        return FirebaseSetup(FirebaseMode.EMULATOR, "")
    }
}
```

`phone/src/debug/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/FirebaseBuildType.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds may talk to the Firebase Emulator Suite instead of a real project: when no `google-services.json` was
 * present at build time (nothing else to talk to), or when the marker file `files/use-firebase-emulator` exists
 * (`adb shell run-as com.seyedjafari.analogplus touch files/use-firebase-emulator`).
 */
object EmulatorSwitch {
    fun requested(context: Context): Boolean =
        context.getFileStreamPath("use-firebase-emulator").exists() ||
            com.google.firebase.FirebaseApp.getApps(context).isEmpty()
}

/** The account the fake Google chooser signs in as: the first line of the marker file, else a default. */
fun emulatorEmail(context: Context): String =
    try { context.getFileStreamPath("use-firebase-emulator").readLines().firstOrNull()?.trim()?.takeIf { it.contains("@") } } catch (e: Exception) { null }
        ?: "tester@example.com"

/** Debug builds use the App Check debug provider: it logs a token that is registered once in the Firebase console. */
object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
```

`phone/src/release/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/FirebaseBuildType.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds never use the emulator. */
object EmulatorSwitch {
    @Suppress("UNUSED_PARAMETER")
    fun requested(context: Context): Boolean = false
}

/** Never used in release builds (there is no emulator mode); present so shared code compiles. */
@Suppress("UNUSED_PARAMETER")
fun emulatorEmail(context: Context): String = "unused@example.com"

/** Release builds attest with Play Integrity; Firestore enforcement is switched on in the Firebase console. */
object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/IdTokenSource.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** The person has no Google account on this phone. */
class NoGoogleAccountException : Exception("No Google account found on this phone.")

/** Something that can hand Firebase a Google ID token: the real Google chooser, or a fake one for the Auth emulator. */
fun interface IdTokenSource {
    /** The ID token, or null when the person closed the chooser. Throws [NoGoogleAccountException] when there is no account. */
    suspend fun fetchIdToken(): String?
}

/** "Sign in with Google" through Credential Manager. [activity] must return an Activity context (the chooser is a UI). */
class GoogleIdTokenSource(
    private val credentialManager: CredentialManager,
    private val webClientId: String,
    private val activity: () -> Context?,
) : IdTokenSource {
    override suspend fun fetchIdToken(): String? {
        val context = activity() ?: throw IllegalStateException("The app is not on screen.")
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build())
            .build()
        val credential = try {
            credentialManager.getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return null
        } catch (e: NoCredentialException) {
            throw NoGoogleAccountException()
        }
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            return GoogleIdTokenCredential.createFrom(credential.data).idToken
        }
        throw IllegalStateException("Unexpected credential type ${credential.type}")
    }
}

/**
 * For the Firebase Auth emulator only: it accepts a JSON string as a Google ID token, so no Google account or chooser is
 * needed. [FirebaseProvider] hands this source out in emulator mode, which release builds never have.
 */
class EmulatorIdTokenSource(private val email: () -> String) : IdTokenSource {
    override suspend fun fetchIdToken(): String? {
        val address = email()
        return """{"sub":"${address.substringBefore('@')}","email":"$address","email_verified":true}"""
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/FirebaseAccountManager.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.AccountManager
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.DeleteUserResult
import com.seyedjafari.analogplus.phone.accounts.ReauthResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** [AccountManager] on Firebase Auth with a Google ID token from [tokens]. */
class FirebaseAccountManager(
    private val auth: FirebaseAuth,
    private val tokens: IdTokenSource,
    private val credentialManager: CredentialManager?,
) : AccountManager {
    private val _state = MutableStateFlow(stateOf(auth.currentUser))
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        auth.addAuthStateListener { a ->
            val user = a.currentUser
            if (user != null) _state.value = stateOf(user) else if (_state.value is AccountState.SignedIn) _state.value = AccountState.SignedOut
        }
    }

    override suspend fun signIn() {
        _state.value = AccountState.SigningIn
        _state.value = try {
            val token = tokens.fetchIdToken()
            if (token == null) AccountState.SignedOut else {
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                stateOf(auth.currentUser)
            }
        } catch (e: CancellationException) {
            _state.value = AccountState.SignedOut
            throw e
        } catch (e: NoGoogleAccountException) {
            AccountState.Failed(AccountFailure.NO_GOOGLE_ACCOUNT, "No Google account was found on this phone. Add one in Settings, then try again.")
        } catch (e: FirebaseNetworkException) {
            AccountState.Failed(AccountFailure.NETWORK, "No connection. Check your network and try again.")
        } catch (e: FirebaseAuthUserCollisionException) {
            AccountState.Failed(AccountFailure.UNKNOWN, "This Google account is already linked to another sign-in method.")
        } catch (e: Exception) {
            AccountState.Failed(AccountFailure.UNKNOWN, "Sign-in failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override suspend fun reauthenticate(): ReauthResult {
        val user = auth.currentUser ?: return ReauthResult.Failed("You are not signed in.")
        return try {
            val token = tokens.fetchIdToken() ?: return ReauthResult.Cancelled
            user.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await()
            ReauthResult.Ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoGoogleAccountException) {
            ReauthResult.Failed("No Google account was found on this phone.")
        } catch (e: FirebaseNetworkException) {
            ReauthResult.Failed("No connection. Check your network and try again.")
        } catch (e: Exception) {
            ReauthResult.Failed("Could not confirm your account: ${e.message ?: e.javaClass.simpleName}. Choose the account you are signed in with.")
        }
    }

    override suspend fun deleteUser(): DeleteUserResult {
        val user = auth.currentUser ?: return DeleteUserResult.Deleted
        return try {
            user.delete().await()
            auth.signOut()
            _state.value = AccountState.SignedOut
            DeleteUserResult.Deleted
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            DeleteUserResult.NeedsRecentLogin
        } catch (e: FirebaseNetworkException) {
            DeleteUserResult.Failed("No connection. Check your network and try again.")
        } catch (e: Exception) {
            DeleteUserResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    override suspend fun signOut() {
        auth.signOut()
        _state.value = AccountState.SignedOut
        try { credentialManager?.clearCredentialState(ClearCredentialStateRequest()) } catch (e: Exception) { /* nothing cached is fine */ }
    }

    private fun stateOf(user: FirebaseUser?): AccountState =
        if (user == null) AccountState.SignedOut else AccountState.SignedIn(user.uid, user.email)
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/FirestoreRemoteStore.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.seyedjafari.analogplus.phone.accounts.DESIGN_SCHEMA
import com.seyedjafari.analogplus.phone.accounts.DesignState
import com.seyedjafari.analogplus.phone.accounts.RemoteBackup
import com.seyedjafari.analogplus.phone.accounts.RemoteCurrent
import com.seyedjafari.analogplus.phone.accounts.RemoteDesign
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.RemoteException
import com.seyedjafari.analogplus.phone.accounts.RemoteStore
import com.seyedjafari.analogplus.phone.accounts.remoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKeyFormatException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RemoteStore] on Cloud Firestore: `users/{uid}/designs/{id}`, `users/{uid}/state/current`, `users/{uid}/backup/signingKey`.
 * Field names and types match `firebase/firestore.rules`, which is tested against the emulator.
 *
 * With a memory-only cache a write issued offline waits in memory and `await()` would hang, so writes give up after
 * [writeTimeoutMs] and report [RemoteErrorKind.OFFLINE]; the sync journal keeps the change and tries again.
 */
class FirestoreRemoteStore(private val db: FirebaseFirestore, private val writeTimeoutMs: Long = 15_000) : RemoteStore {
    private fun user(uid: String) = db.collection("users").document(uid)
    private fun designs(uid: String) = user(uid).collection("designs")
    private fun currentDoc(uid: String) = user(uid).collection("state").document("current")
    private fun backupDoc(uid: String) = user(uid).collection("backup").document("signingKey")

    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> = mapErrors {
        designs(uid).get(Source.SERVER).await().documents.mapNotNull(::toDesign)
    }

    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = callbackFlow {
        val registration = designs(uid).addSnapshotListener { snapshot, error ->
            when {
                error != null -> close(wrap(error))
                // the app's own writes that the server has not confirmed yet have no server timestamp
                snapshot != null && !snapshot.metadata.hasPendingWrites() -> trySend(snapshot.documents.mapNotNull(::toDesign))
            }
        }
        awaitClose { registration.remove() }
    }

    override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign =
        mapErrors {
            val ref = designs(uid).document(id)
            val data = mapOf(
                "name" to name, "design" to designJson, "updatedAt" to FieldValue.serverTimestamp(),
                "deleted" to (state == DesignState.TOMBSTONE), "schema" to schema.toLong(),
            )
            confirmed { ref.set(data).await() }
            toDesign(ref.get(Source.SERVER).await()) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable design.")
        }

    override suspend fun getCurrent(uid: String): RemoteCurrent? = mapErrors { toCurrent(currentDoc(uid).get(Source.SERVER).await()) }

    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = callbackFlow {
        val registration = currentDoc(uid).addSnapshotListener { snapshot, error ->
            when {
                error != null -> close(wrap(error))
                snapshot != null && !snapshot.metadata.hasPendingWrites() -> trySend(toCurrent(snapshot))
            }
        }
        awaitClose { registration.remove() }
    }

    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent = mapErrors {
        val ref = currentDoc(uid)
        val data = mapOf(
            "design" to designJson, "updatedAt" to FieldValue.serverTimestamp(), "deviceId" to deviceId,
            "deviceLabel" to deviceLabel, "schema" to DESIGN_SCHEMA.toLong(),
        )
        confirmed { ref.set(data).await() }
        toCurrent(ref.get(Source.SERVER).await()) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable design.")
    }

    override suspend fun getBackup(uid: String): RemoteBackup? = mapErrors { toBackup(backupDoc(uid).get(Source.SERVER).await()) }

    override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup = mapErrors {
        val ref = backupDoc(uid)
        confirmed { ref.set(sealed.toFields() + ("createdAt" to FieldValue.serverTimestamp())).await() }
        toBackup(ref.get(Source.SERVER).await()) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable backup.")
    }

    override suspend fun deleteBackup(uid: String) = mapErrors { confirmed { backupDoc(uid).delete().await() } }

    override suspend fun deleteAllUserData(uid: String) = mapErrors {
        for (collection in listOf(designs(uid), user(uid).collection("state"), user(uid).collection("backup"))) {
            val docs = collection.get(Source.SERVER).await().documents
            for (chunk in docs.chunked(BATCH_SIZE)) {
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                confirmed { batch.commit().await() }
            }
        }
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    private fun ms(t: Timestamp): Long = t.seconds * 1000 + t.nanoseconds / 1_000_000

    private fun toDesign(d: DocumentSnapshot): RemoteDesign? {
        val name = d.getString("name") ?: return null
        val design = d.getString("design") ?: return null
        val at = d.getTimestamp("updatedAt") ?: return null
        val deleted = d.getBoolean("deleted") ?: return null
        return RemoteDesign(
            d.id, name, design, ms(at), if (deleted) DesignState.TOMBSTONE else DesignState.LIVE, (d.getLong("schema") ?: return null).toInt(),
        )
    }

    private fun toCurrent(d: DocumentSnapshot): RemoteCurrent? {
        if (!d.exists()) return null
        return RemoteCurrent(
            d.getString("design") ?: return null, ms(d.getTimestamp("updatedAt") ?: return null),
            d.getString("deviceId") ?: return null, d.getString("deviceLabel") ?: "",
        )
    }

    private fun toBackup(d: DocumentSnapshot): RemoteBackup? {
        if (!d.exists()) return null
        val data = d.data ?: return null
        val at = d.getTimestamp("createdAt") ?: return null
        return try {
            RemoteBackup(SealedKey.fromFields(data), ms(at))
        } catch (e: SealedKeyFormatException) {
            throw RemoteException(RemoteErrorKind.OTHER, "The stored backup is damaged (${e.message}).", e)
        }
    }

    // ---- errors -------------------------------------------------------------------------------------------------

    private suspend fun confirmed(write: suspend () -> Unit) {
        withTimeoutOrNull(writeTimeoutMs) { write() } ?: throw RemoteException(RemoteErrorKind.OFFLINE, "The cloud did not answer in time.")
    }

    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: FirebaseFirestoreException) {
        throw wrap(e)
    }

    private fun wrap(e: Throwable): RemoteException = when (e) {
        is RemoteException -> e
        is FirebaseFirestoreException -> RemoteException(remoteErrorKind(e.code.name), e.message ?: "Cloud error", e)
        else -> RemoteException(RemoteErrorKind.OTHER, e.message ?: "Cloud error", e)
    }

    private companion object {
        const val BATCH_SIZE = 400
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/firebase/CurrentActivity.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts.firebase

import android.app.Activity
import java.lang.ref.WeakReference

/** The visible activity, for the Google account chooser (Credential Manager needs an Activity context). Weakly held. */
object CurrentActivity {
    @Volatile private var ref: WeakReference<Activity>? = null

    fun set(activity: Activity) { ref = WeakReference(activity) }

    fun clear(activity: Activity) { if (ref?.get() === activity) ref = null }

    fun get(): Activity? = ref?.get()
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsFactory.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import android.content.Context
import android.os.Build
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.PrefsKeyValueStore
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.firebase.CurrentActivity
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorIdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseAccountManager
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.firebase.GoogleIdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.emulatorEmail
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the accounts stack for the app. The only place (with `accounts/firebase`) that touches Firebase types.
 *
 * Whatever the outcome, the [SyncJournal] is attached to [store] so that edits made while signed out (or in a build
 * without Firebase) are remembered for a later sign-in.
 */
object AccountsFactory {
    private const val SYNC_PREFS = "analogplus_sync"
    private const val KEY_DEVICE_ID = "device_id"

    fun create(
        context: Context,
        store: Store,
        scope: CoroutineScope,
        currentDesign: () -> DesignSettings,
        applier: CurrentApplier,
        keyStore: KeyStore = KeyStore(context),
        syncKv: KeyValueStore = PrefsKeyValueStore(context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)),
    ): AccountsController {
        val app = context.applicationContext
        val journal = SyncJournal(syncKv)
        store.addObserver(journal)
        val setup = FirebaseProvider.setUp(app)
        if (setup.mode == FirebaseMode.NOT_CONFIGURED) return AccountsController.unavailable(scope)

        val remote = FirestoreRemoteStore(FirebaseFirestore.getInstance())
        val engine = SyncEngine(
            store, journal, remote, scope, AndroidPhotoDownscaler(), applier, currentDesign,
            deviceId = deviceId(syncKv), deviceLabel = Build.MODEL ?: "Android phone",
        )
        store.addObserver(engine)
        val credentialManager = CredentialManager.create(app)
        val tokens = when (setup.mode) {
            FirebaseMode.EMULATOR -> EmulatorIdTokenSource { emulatorEmail(app) }
            else -> GoogleIdTokenSource(credentialManager, setup.webClientId) { CurrentActivity.get() }
        }
        val accounts = FirebaseAccountManager(FirebaseAuth.getInstance(), tokens, credentialManager)
        val backup = BackupService(remote, KeyVault(), KeyStoreKeySource(keyStore), AttemptLimiter(syncKv))
        return AccountsController(
            setup.availability, accounts, engine, backup, AccountDeletion(accounts, remote, engine, journal), scope,
        )
    }

    private fun deviceId(kv: KeyValueStore): String =
        kv.getString(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { kv.putString(KEY_DEVICE_ID, it) }
}
```

`scripts/accounts_e2e.sh`:

```bash
#!/usr/bin/env bash
# Runs the accounts tests that need the Firebase Emulator Suite, on the phone emulator:
#   FirebaseEmulatorTest   the real Firestore / Auth code against the emulators, with firebase/firestore.rules loaded
#   AccountsEndToEndTest   replacing a phone through the real Account UI (sign in, sync, back up, restore, delete account)
# The phone emulators have no Google sign-in, so the debug build signs in through the Auth emulator (see FirebaseProvider).
#
#   scripts/accounts_e2e.sh [--keep-emulators]
#
# Starts the Firebase emulators (Auth 9099, Firestore 8080) and the phone AVD when they are not running, takes the phone
# emulator lock for the Gradle run, and stops what it started (unless --keep-emulators). Needs Node 20+, Java 21 and
# `npm ci` already run in firebase/ (done here when node_modules is missing).
set -euo pipefail
source "$(dirname "$0")/_env.sh"

PHONE_AVD="1_Pixel_8_API_33"; PHONE_PORT=5562; PHONE="emulator-$PHONE_PORT"
FB="$ROOT/firebase"
KEEP=no
for a in "$@"; do
  case "$a" in
    --keep-emulators) KEEP=yes ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

FB_PID=""; AVD_STARTED=no
step() { printf '\n== %s\n' "$*"; }
die()  { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
cleanup() {
  if [ "$KEEP" = no ]; then
    if [ -n "$FB_PID" ]; then pkill -P "$FB_PID" 2>/dev/null || true; kill "$FB_PID" 2>/dev/null || true; fi
    if [ "$AVD_STARTED" = yes ]; then "$ADB" -s "$PHONE" emu kill >/dev/null 2>&1 || true; fi
  fi
}
trap cleanup EXIT

step "Firebase emulators"
[ -d "$FB/node_modules" ] || (cd "$FB" && npm ci)
if port_open 8080 && port_open 9099; then
  echo "   already running"
else
  (cd "$FB" && npm run emulators >"$ROOT/firebase/emulators.log" 2>&1) &
  FB_PID=$!
  for _ in $(seq 1 60); do port_open 8080 && port_open 9099 && break; sleep 1; done
  port_open 8080 && port_open 9099 || die "the Firebase emulators did not start (see firebase/emulators.log)"
fi

step "Phone emulator"
if "$ADB" devices | grep -q "^$PHONE[[:space:]]*device"; then
  echo "   already running"
else
  "$EMULATOR" -avd "$PHONE_AVD" -port "$PHONE_PORT" -no-window -no-audio -no-snapshot -no-boot-anim >"$ROOT/tools/emulator-$PHONE_AVD.log" 2>&1 &
  AVD_STARTED=yes
  "$ADB" -s "$PHONE" wait-for-device
  until [ "$("$ADB" -s "$PHONE" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
fi

step "Accounts tests (emulator-backed)"
cd "$ROOT"
scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=com.seyedjafari.analogplus.phone.accounts
echo "accounts e2e: PASS"
```

Make the script executable: `chmod +x scripts/accounts_e2e.sh`.

- [ ] **Step 4: Run the JVM tests and build both variants**

Run: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug :phone:assembleRelease`
Expected: PASS and BUILD SUCCESSFUL, with no `google-services.json` present (release then has accounts off at run time; debug uses the emulator).

- [ ] **Step 5: Run the emulator-backed tests**

Run: `scripts/accounts_e2e.sh`
Expected: it starts the Firebase emulators and the phone AVD if they are not running, runs every instrumented test in package `...phone.accounts`, prints `accounts e2e: PASS`, and stops what it started. At this point that is `FirebaseEmulatorTest` (7: sign-in/re-auth/delete against the Auth emulator; designs, tombstones, current design, a sealed-key backup round trip, another user denied with `PERMISSION`, an oversize design refused by the real rules, delete-all), `PhotoDownscalerTest` (4), `KeyVaultCostTest` (1) and `AccountsFactoryTest` (1). A `PERMISSION_DENIED` from a write that should succeed means a field name or type in `FirestoreRemoteStore` disagrees with `firebase/firestore.rules` (Task 2); fix the Kotlin, not the rules.

- [ ] **Step 6: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts phone/src/debug phone/src/release phone/src/test/kotlin phone/src/androidTest/kotlin scripts/accounts_e2e.sh
git commit -m "feat(phone): Firebase Auth and Firestore implementations, emulator mode, App Check, e2e script"
```

---

### Task 12: Wire accounts into the app

**Files:**
- Modify: `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/MainActivity.kt`
- Create: `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/LocalAccounts.kt` (its text is in Task 14; create it now)

**Interfaces:**
- Consumes: `AccountsFactory.create` (Task 11), `LocalAccounts`, `CurrentActivity`.
- Produces: `AppModel.accounts: AccountsController`; every composable below `setContent` can read `LocalAccounts.current`.

- [ ] **Step 1: Write the failing test**

There is nothing new to assert here that Task 11's `AccountsFactoryTest` does not already (hermetic storage); the wiring itself is three edits in `MainActivity.kt`, checked by compiling and by the end-to-end test of Task 16. State that in the commit message and do not add a test that launches `MainActivity` (it would autosave a design into the installed app's real preferences).

- [ ] **Step 2: Create `LocalAccounts.kt`**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/LocalAccounts.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.compositionLocalOf
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The accounts controller for the screens below. `MainActivity` provides the real one; without a provider (previews,
 * older tests) the screens see a controller for a build without Firebase, so they never crash and offer no sign-in.
 */
val LocalAccounts = compositionLocalOf<AccountsController> {
    AccountsController.unavailable(CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
```

- [ ] **Step 3: Edit `MainActivity.kt`**

1. In `AppModel`, directly after the line `val state = EditorState(store.loadCurrentWithoutPhoto())` (so, above `val onboarding = OnboardingController(store)` if the onboarding plan has already landed; Task 15 relies on this order), insert:

```kotlin
    /** Accounts, sync and signing-key backup. A build without google-services.json gets a controller with accounts switched off. */
    val accounts: AccountsController = AccountsFactory.create(
        app, store, viewModelScope, currentDesign = { state.design }, applier = CurrentApplier { design -> state.replace(design) },
    )
```

and add the imports `com.seyedjafari.analogplus.phone.accounts.AccountsController`, `...AccountsFactory`, `...CurrentApplier`.

2. In `MainActivity`, add (merge with the existing `onStop`, which calls `model.saveNow()`):

```kotlin
    override fun onStart() {
        super.onStart()
        CurrentActivity.set(this)      // the Google account chooser needs a visible Activity
    }

    override fun onStop() {
        super.onStop()
        model.saveNow()
        CurrentActivity.clear(this)
    }
```

with the import `com.seyedjafari.analogplus.phone.accounts.firebase.CurrentActivity`.

3. In `setContent { ... }`, wrap what is there (plan 1: the `AppShell(...)` call; before plan 1: the `AnalogPlusTheme { ... }` block) without changing it:

```kotlin
        setContent {
            CompositionLocalProvider(LocalAccounts provides model.accounts) {
                // the existing content, unchanged
            }
        }
```

with the imports `androidx.compose.runtime.CompositionLocalProvider` and `com.seyedjafari.analogplus.phone.ui.account.LocalAccounts`.

- [ ] **Step 4: Build and run everything that exists**

Run: `./gradlew :phone:testDebugUnitTest :phone:assembleDebug :phone:assembleRelease && scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest`
Expected: PASS. The existing POC and app-shell instrumented tests are unaffected (they do not provide `LocalAccounts`, and nothing below `AppShell` reads it yet).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin/com/seyedjafari/analogplus/phone/MainActivity.kt phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/LocalAccounts.kt
git commit -m "feat(phone): AppModel owns the accounts controller; LocalAccounts and the visible activity are provided"
```

---

### Task 13: Spike: what really happens after a forgotten passphrase

The spec's open item. Do not write any UI promise before this has run: the reset text in Task 14 is chosen by its result, and a test there fails if the text and the finding disagree.

Question: **if the passphrase is lost and the signing key is lost with the phone, can a person get a new key's face onto the watch without a computer?** What is already known (`CLAUDE.md`, the handoff): a face is signed by one key forever; the pushed package is `com.seyedjafari.analogplus.watchfacepush.analog` (`FaceXml.PACKAGE_NAME`, a constant); the watch app only ever updates the one face it lists; `removeWatchFace` is never called because it burns the one activation; uninstalling the watch app leaves the face installed; the watch app stores that it has already activated once. What is not known: whether a person can remove the face from the watch itself, and what the watch does with a face signed by another key. (The spec also floated "a new face name/package". That cannot work with today's watch app, which updates the single listed face and has no way to add a second; so the reset path is "remove the old face, then add the new key's face under the same package".)

**Files:**
- Create: `docs/poc/key-reset-spike.md`

**Interfaces:**
- Produces: a line of the exact form `RESET_RESULT: SELF_SERVICE` or `RESET_RESULT: NEEDS_ADB` in `docs/poc/key-reset-spike.md`, read by `KeyResetCopyTest` (Task 14). Decision rule:
  - `SELF_SERVICE`: the Wear OS emulator's own Settings lists the pushed face package **and** offers Uninstall for it, **and** after uninstalling it Step 3's apply of the new-key face succeeds.
  - `NEEDS_ADB`: anything else (no list entry, no uninstall option, or the new-key apply still fails).
  The result must say so honestly even if the emulator is not a Samsung watch; Task 18's real-device checklist re-checks it on the Galaxy Watch and flips the line (and `KeyResetCopy.CURRENT`) if the real watch disagrees.

- [ ] **Step 1: Get both emulators into the known starting state**

Run: `scripts/poc_e2e.sh --keep-emulators`
Expected: `E2E PASSED`; the phone emulator (5562) and watch emulator (5560) are running, the watch holds the pushed face signed by key K1 and its highest version `N`. Write down `N` and the signer:

```bash
adb -s emulator-5560 shell dumpsys package com.seyedjafari.analogplus.watchfacepush.analog | grep -E "versionCode|signatures|Signing" | head -5
```

- [ ] **Step 2: Lose the key, build with a new one, try to update (the known failure)**

```bash
APP=com.seyedjafari.analogplus
adb -s emulator-5562 shell run-as $APP rm files/signing-key.pem          # the phone is "replaced": no key
adb -s emulator-5562 logcat -c
adb -s emulator-5562 shell am broadcast -n $APP/$APP.phone.DebugBuildReceiver -a $APP.DEBUG_BUILD --es template Classic | tail -1
adb -s emulator-5562 pull /sdcard/Android/data/$APP/files/relay/face.apk /tmp/face-k2.apk
TOKEN=$(adb -s emulator-5562 shell cat /sdcard/Android/data/$APP/files/relay/face.token | tr -d '[:space:]')
VERSION=$(adb -s emulator-5562 shell cat /sdcard/Android/data/$APP/files/relay/face.version | tr -d '[:space:]')
adb -s emulator-5560 push /tmp/face-k2.apk /data/local/tmp/face.apk
adb -s emulator-5560 logcat -c
adb -s emulator-5560 shell "am broadcast -n $APP/$APP.watch.DebugApplyReceiver -a $APP.DEBUG_APPLY --es path /data/local/tmp/face.apk --es token '$TOKEN' --ei versionCode $VERSION" | tail -1
adb -s emulator-5560 logcat -d -s AnalogPlusPush:I | tail -1
```

Expected: `"ok":false` with an error naming `UpdateWatchFace` and the signing key. Record the exact text (the app shows it to the user as `KEY_MISMATCH_TEXT`).

- [ ] **Step 3: Remove the old face from the watch (adb path), add the new-key face**

```bash
adb -s emulator-5560 shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog
adb -s emulator-5560 logcat -c
adb -s emulator-5560 shell "am broadcast -n $APP/$APP.watch.DebugApplyReceiver -a $APP.DEBUG_APPLY --es path /data/local/tmp/face.apk --es token '$TOKEN' --ei versionCode $VERSION" | tail -1
sleep 30; adb -s emulator-5560 logcat -d -s AnalogPlusPush:I | tail -1
adb -s emulator-5560 shell am broadcast -n $APP/$APP.watch.DebugApplyReceiver -a $APP.DEBUG_ACTIVATE | tail -1
mkdir -p docs/screenshots/accounts && adb -s emulator-5560 exec-out screencap -p > docs/screenshots/accounts/reset-after-readd.png
```

Expected: the apply returns `"ok":true` (the new-key face is added), and `DEBUG_ACTIVATE` reports ok without activating again (the watch app remembers it activated once; `PushController.activateOnce` does not call the system a second time). Look at the screenshot: the new face is installed but the previous face is probably still showing; a person would pick it in the face picker. Record both facts.

- [ ] **Step 4: Can a person remove the face without adb? (the decisive question)**

Re-add a K1-style face first if you need one installed (repeat Step 3's apply). Then, on the watch emulator:

```bash
adb -s emulator-5560 shell am start -a android.settings.APPLICATION_SETTINGS
adb -s emulator-5560 shell uiautomator dump /sdcard/ui.xml && adb -s emulator-5560 pull /sdcard/ui.xml /tmp/ui-apps.xml
grep -o 'text="[^"]*"' /tmp/ui-apps.xml | head -30
adb -s emulator-5560 exec-out screencap -p > docs/screenshots/accounts/reset-watch-apps.png
```

Scroll the list (`adb -s emulator-5560 shell input swipe 200 300 200 100`, then dump again) and look for "Analog Plus" (the pushed face may appear under the watch app's name or not at all). If an entry exists, open it, and look for an Uninstall action (screenshot each screen into `docs/screenshots/accounts/`). If you uninstall through the UI, repeat Step 3's apply to see that the new-key face is accepted.

- [ ] **Step 5: Write the finding**

Create `docs/poc/key-reset-spike.md` with these sections, filled with what you observed (commands run, exact log lines, screenshot names):

```markdown
# Key reset spike (<date>)

Question, method (Steps 1-4 of the accounts plan, Task 13), emulators used (phone 1_Pixel_8_API_33, watch GalaxyWatch7_API36).

## Observations
1. Update with another key (same package): <the exact error text>
2. After removing the face (adb): new-key face added: <yes/no, log line>; activation: <what DEBUG_ACTIVATE returned; what the screenshot shows>
3. Removal without adb: Settings lists the face: <yes/no>; Uninstall offered: <yes/no>; uninstalling that way then adding the new-key face: <worked/not tried/failed>
4. New package name while the old face is installed: not testable (the watch app updates the single listed face); not needed.

## Decision
<one or two sentences applying the rule from the plan>

RESET_RESULT: <SELF_SERVICE or NEEDS_ADB>

Not verified: Samsung One UI Watch (the owner's SM-L315F); see Task 18, checklist item "uninstall the face from the watch itself".
```

The `RESET_RESULT:` line must be alone on its line, exactly one of the two values.

- [ ] **Step 6: Commit (docs, straight to `main`)**

```bash
git add docs/poc/key-reset-spike.md docs/screenshots/accounts/reset-*.png
git commit -m "docs(poc): key reset spike, what really happens after a forgotten passphrase"
```

---

### Task 14: The Account screen: states, dialogs and the gated reset text

Needs the app-shell plan merged (`SectionCard`, `PrimaryButton`, `SecondaryButton`, `StatusPill`, `StatusKind`, `ErrorBanner`, `Spacing`, `AnalogTheme`, `AccountScreen`) and Task 13's finding. The Compose code below was compiled and run against stand-ins with these exact signatures; **run the UI tests first** and fix any mismatch with the real components in the UI files, not in the logic.

**Files:**
- Create: `ui/account/AccountTags.kt`, `ui/account/AccountText.kt`, `ui/account/PassphraseFields.kt`, `ui/account/AccountDialogs.kt`, `ui/account/CloudSection.kt`, `accounts/KeyResetCopy.kt` (`LocalAccounts.kt` exists since Task 12)
- Modify: `ui/account/AccountScreen.kt`
- Test: `phone/src/test/kotlin/.../ui/account/AccountTextTest.kt`, `phone/src/test/kotlin/.../accounts/KeyResetCopyTest.kt`; `phone/src/androidTest/kotlin/.../accounts/AccountSectionTest.kt`, `.../AccountScreenAccountsTest.kt`

**Interfaces:**
- Consumes: `AccountsController` and `AccountsUi` (Task 10), `LocalAccounts` (Task 12), plan 1's components.
- Produces:

```kotlin
@Composable fun AccountSection(modifier: Modifier = Modifier)               // the "Backup and sync" card; reads LocalAccounts.current
sealed interface CloudDialog { None; SetPassphrase; Replace(plan); Enter(plan, error); Forgot(plan); Delete }
@Composable fun CloudDialogHost(dialog: CloudDialog, onChange: (CloudDialog) -> Unit, accounts: AccountsController)
suspend fun beginRestore(accounts: AccountsController): CloudDialog
class PassphraseState(confirmTwice: Boolean) { var passphrase; var again; val strength; val valid; fun clear() }
@Composable fun rememberPassphraseState(confirmTwice: Boolean): PassphraseState
@Composable fun PassphraseFields(state: PassphraseState, confirmTwice: Boolean, errorText: String?, modifier: Modifier = Modifier)
object AccountTags { SIGN_IN, EMAIL, SYNC_STATUS, BACKUP_STATUS, BACK_UP, RESTORE, SYNC_NOW, SIGN_OUT, DELETE, NOTICE, PASSPHRASE, PASSPHRASE_CONFIRM, PASSPHRASE_STRENGTH, PASSPHRASE_SUBMIT, PASSPHRASE_ERROR, PASSPHRASE_CANCEL, FORGOT, REPLACE_CONFIRM, REPLACE_CANCEL, RESET_CONFIRM, RESET_CANCEL, RESET_NEW_PASSPHRASE, DELETE_CONFIRM, DELETE_CANCEL, OFFER_USE, OFFER_KEEP, NOT_CONFIGURED, SIGNING_IN, SIGN_IN_ERROR, PAGE_* }
enum class ResetPath { SELF_SERVICE, NEEDS_ADB }; data class ResetCopy(title, body, confirmLabel, cancelLabel)
object KeyResetCopy { val CURRENT: ResetPath; fun copyFor(path: ResetPath): ResetCopy; fun current(): ResetCopy }
fun relativeTime(thenMs: Long, nowMs: Long): String; data class PillText(text, kind); fun syncPill(status, nowMs): PillText; fun backupPill(status): PillText; fun restoreErrorText(result): String?
```

The states the screen draws (spec "Account destination"): **not configured** (a build without Firebase: one sentence, no button); **signed out** (benefits in two lines, "Sign in with Google", the failure reason if the last attempt failed); **signing in**; **signed in**: email, a sync pill (off / syncing / synced <time> / offline, will retry / error), a backup pill (checking / not backed up / backed up with the key's fingerprint / "another key than this phone" / "a backup exists; this phone has no key yet" / could not check), then Back up or Change passphrase, Restore, Sync now, Sign out, Delete account and cloud data; dialogs for the passphrase (twice, with a strength line and the "nobody can recover it" warning; never kept across rotation), restore (a replace-the-key confirmation naming both fingerprints comes **before** the passphrase is asked for), forgotten passphrase (three variants, below), delete account, and the "design from another device" question. Forgotten passphrase: if this phone still holds the backed-up key, just choose a new passphrase (nothing is lost); if it holds another key, offer to delete the unreadable backup and back this phone's key up instead; if it holds no usable key, show `KeyResetCopy.current()` and, on confirm, delete the backup. **Both reset texts are written out below; the one in force is `KeyResetCopy.CURRENT`, which must equal the spike's `RESET_RESULT`.**

- [ ] **Step 1: Write the failing tests**

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/ui/account/AccountTextTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTextTest {
    @Test fun relativeTimeReadsLikeAPerson() {
        val now = 10_000_000L
        assertEquals("just now", relativeTime(now - 5_000, now))
        assertEquals("just now", relativeTime(now + 5_000, now))      // a clock a little ahead never shows a negative age
        assertEquals("5 min ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("3 h ago", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2 days ago", relativeTime(now - 2 * 86_400_000L, now))
    }

    @Test fun everySyncStatusHasWordsAndALook() {
        assertEquals(PillText("Sync is off", StatusKind.NEUTRAL), syncPill(SyncStatus.Off, 0))
        assertEquals(PillText("Syncing", StatusKind.BUSY), syncPill(SyncStatus.Syncing, 0))
        assertEquals(PillText("Synced 5 min ago", StatusKind.OK), syncPill(SyncStatus.Idle(1_000), 1_000 + 5 * 60_000))
        assertEquals(PillText("Offline, will retry", StatusKind.WARNING), syncPill(SyncStatus.Offline, 0))
        assertEquals(PillText("Sync failed", StatusKind.ERROR), syncPill(SyncStatus.Error("Sync failed"), 0))
    }

    @Test fun backupPillsSeparateNotBackedUpFromCouldNotCheck() {
        assertEquals(StatusKind.WARNING, backupPill(BackupStatus.NotBackedUp).kind)
        assertEquals(StatusKind.NEUTRAL, backupPill(BackupStatus.Unknown("offline")).kind)
        assertEquals("Could not check the backup", backupPill(BackupStatus.Unknown("offline")).text)
        assertEquals(StatusKind.BUSY, backupPill(BackupStatus.Checking).kind)
        assertEquals(StatusKind.NEUTRAL, backupPill(BackupStatus.SignedOut).kind)
    }

    @Test fun backupPillsNameTheKeyMatch() {
        val same = backupPill(BackupStatus.BackedUp("0123456789abcdef", 1, KeyMatch.SAME))
        assertEquals(StatusKind.OK, same.kind)
        assertTrue(same.text.contains("0123456789abcdef"))
        assertEquals(StatusKind.WARNING, backupPill(BackupStatus.BackedUp("0123456789abcdef", 1, KeyMatch.DIFFERENT)).kind)
        assertTrue(backupPill(BackupStatus.BackedUp("0123456789abcdef", 1, KeyMatch.NO_LOCAL_KEY)).text.contains("A backup exists"))
        assertTrue(backupPill(BackupStatus.BackedUp("0123456789abcdef", 1, KeyMatch.DIFFERENT)).text.contains("another key than this phone"))
    }

    @Test fun restoreErrorsExplainWhatToDoAndSuccessHasNone() {
        assertNull(restoreErrorText(RestoreResult.Restored("0123456789abcdef")))
        assertTrue(restoreErrorText(RestoreResult.WrongPassphrase(0))!!.contains("Check it and try again"))
        assertTrue(restoreErrorText(RestoreResult.WrongPassphrase(5_000))!!.contains("Wait 5 seconds"))
        assertEquals("Too many wrong tries. Wait 6 seconds.", restoreErrorText(RestoreResult.Throttled(5_001)))
        assertTrue(restoreErrorText(RestoreResult.Unsupported("backup version 2"))!!.contains("Update Analog Plus"))
        assertTrue(restoreErrorText(RestoreResult.LocalKeyChanged)!!.contains("start the restore again"))
        assertTrue(restoreErrorText(RestoreResult.FingerprintMismatch)!!.contains("different key"))
    }
}
```

`phone/src/test/kotlin/com/seyedjafari/analogplus/phone/accounts/KeyResetCopyTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the `RESET_RESULT:` line the emulator spike wrote. */
fun parseResetResult(text: String): ResetPath? {
    val value = Regex("^RESET_RESULT:\\s*(\\w+)\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return null
    return ResetPath.entries.firstOrNull { it.name == value }
}

class KeyResetCopyTest {
    @Test fun theTwoTextsAreDifferentAndEachNamesWhatTheUserMustDo() {
        val a = KeyResetCopy.copyFor(ResetPath.SELF_SERVICE)
        val b = KeyResetCopy.copyFor(ResetPath.NEEDS_ADB)
        assertNotEquals(a.body, b.body)
        assertTrue(a.body.contains("uninstall") && !a.body.contains("adb"))
        assertTrue(b.body.contains("adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog"))
        listOf(a, b).forEach { assertTrue(it.body.contains("saved designs are not affected")) }
    }

    @Test fun theFindingLineIsParsed() {
        assertEquals(ResetPath.SELF_SERVICE, parseResetResult("# x\nRESET_RESULT: SELF_SERVICE\nmore"))
        assertEquals(ResetPath.NEEDS_ADB, parseResetResult("RESET_RESULT: NEEDS_ADB"))
        assertEquals(null, parseResetResult("RESET_RESULT: SOMETHING_ELSE"))
        assertEquals(null, parseResetResult("no line here"))
    }

    /** The gate: the text on screen must be the one the spike justified. Run from the module directory (`phone/`). */
    @Test fun theScreenPromisesOnlyWhatTheSpikeProved() {
        val doc = File("../docs/poc/key-reset-spike.md")
        assertTrue("write docs/poc/key-reset-spike.md first (accounts plan, Task 11)", doc.isFile)
        val finding = parseResetResult(doc.readText())
        assertNotNull("docs/poc/key-reset-spike.md has no 'RESET_RESULT: SELF_SERVICE|NEEDS_ADB' line", finding)
        assertEquals("KeyResetCopy.CURRENT must equal the spike's RESET_RESULT", finding, KeyResetCopy.CURRENT)
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountSectionTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountSectionTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accounts = FakeAccountManager()
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val passphrase = "correct horse battery staple"

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun show(keys: FakeKeySource, availability: AccountsAvailability = AccountsAvailability.AVAILABLE) {
        val controller = AccountsController(
            availability, accounts, sync,
            BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Default),
            AccountDeletion(accounts, remote, sync, SyncJournal(MemoryKeyValueStore())), scope,
        )
        rule.setContent {
            AnalogTheme {
                CompositionLocalProvider(LocalAccounts provides controller) {
                    // as in AccountScreen: the section lives in a scrolling column
                    Column(Modifier.verticalScroll(rememberScrollState())) { AccountSection() }
                }
            }
        }
    }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun signIn() {
        rule.onNodeWithTag(AccountTags.SIGN_IN).performClick()
        waitForTag(AccountTags.EMAIL)
    }

    /** Another phone already backed up key A under [passphrase]. */
    private fun seedBackupOfKeyA() = runBlocking {
        BackupService(remote, vault, FakeKeySource(pemA), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined).upload("u1", passphrase)
    }

    @Test fun signedOutExplainsTheBenefitAndOffersSignIn() {
        show(FakeKeySource(pemA))
        rule.onNodeWithText("Everything works without signing in.", substring = true).assertExists()
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertIsEnabled()
        rule.onNodeWithTag(AccountTags.EMAIL).assertDoesNotExist()
    }

    @Test fun signingInShowsTheEmailTheSyncPillAndTheBackupPill() {
        show(FakeKeySource(pemA))
        signIn()
        rule.onNodeWithTag(AccountTags.EMAIL).assertTextEquals("Signed in as me@example.com")
        rule.onNodeWithTag(AccountTags.SYNC_STATUS).assertExists()
        waitForText("Signing key is not backed up")
    }

    @Test fun aFailedSignInShowsTheReasonAndKeepsTheButton() {
        accounts.nextSignIn = AccountState.Failed(AccountFailure.NO_GOOGLE_ACCOUNT, "No Google account was found on this phone.")
        show(FakeKeySource(pemA))
        rule.onNodeWithTag(AccountTags.SIGN_IN).performClick()
        waitForTag(AccountTags.SIGN_IN_ERROR)
        rule.onNodeWithTag(AccountTags.SIGN_IN_ERROR).assertTextContains("No Google account", substring = true)
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertExists()
    }

    @Test fun withoutFirebaseTheSectionSaysSoAndOffersNoSignIn() {
        show(FakeKeySource(pemA), AccountsAvailability.NOT_CONFIGURED)
        rule.onNodeWithTag(AccountTags.NOT_CONFIGURED).assertExists()
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertDoesNotExist()
    }

    @Test fun theSyncPillFollowsTheSyncStatus() {
        show(FakeKeySource(pemA))
        signIn()
        sync.setStatus(SyncStatus.Offline)
        waitForText("Offline, will retry")
        sync.setStatus(SyncStatus.Error("The cloud limit was reached; try again later."))
        waitForText("The cloud limit was reached")
        sync.setStatus(SyncStatus.Idle(System.currentTimeMillis()))
        waitForText("Synced just now")
    }

    @Test fun aWeakOrMismatchedPassphraseCannotBeSubmitted() {
        show(FakeKeySource(pemA))
        signIn()
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("short")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_STRENGTH).assertTextContains("Too short", substring = true)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("aaaaaaaaaaaaaaaaaaaa")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_STRENGTH).assertTextContains("Weak", substring = true)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
    }

    @Test fun backingUpWithAGoodMatchingPassphraseStoresTheBackupAndShowsItAsDone() {
        val keys = FakeKeySource(pemA)
        show(keys)
        signIn()
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput("something else entirely")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput("")
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("")
        // retype the confirmation so that it matches: clear by replacing is not available, so use a fresh dialog
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsEnabled().performClick()
        waitForText("Signing key backed up")
        assertNotNull(remote.backupOf("u1"))
        assertEquals(com.seyedjafari.analogplus.phone.keyFingerprint(pemA), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aBackupOnAPhoneWithoutAKeyIsRestoredWithThePassphraseAfterAWrongTry() {
        seedBackupOfKeyA()
        val keys = FakeKeySource(null)
        show(keys)
        signIn()
        waitForText("A backup exists")
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("not the passphrase")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PASSPHRASE_ERROR)
        assertFalse(keys.hasKey())
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(pemA, keys.pem())
        waitForText("Signing key restored")
    }

    @Test fun replacingAnotherKeyAsksFirstAndKeepsACopyOfTheOldOne() {
        seedBackupOfKeyA()
        val keys = FakeKeySource(pemB)
        show(keys)
        signIn()
        waitForText("another key than this phone")
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForText("Replace the signing key on this phone?")
        rule.onNodeWithTag(AccountTags.REPLACE_CANCEL).performClick()
        waitUntilGone(AccountTags.REPLACE_CANCEL)
        assertEquals(pemB, keys.pem())
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.REPLACE_CONFIRM)
        rule.onNodeWithTag(AccountTags.REPLACE_CONFIRM).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(pemA, keys.pem())
        assertEquals(pemB, keys.previousKey)
    }

    @Test fun forgettingThePassphraseOnANewPhoneShowsTheSpikeApprovedTextAndDeletesTheBackup() {
        seedBackupOfKeyA()
        show(FakeKeySource(null))
        signIn()
        waitForText("A backup exists")
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.FORGOT)
        rule.onNodeWithTag(AccountTags.FORGOT).performClick()
        waitForText(KeyResetCopy.current().title)
        rule.onNodeWithText(KeyResetCopy.current().confirmLabel).assertExists()
        rule.onNodeWithTag(AccountTags.RESET_CONFIRM).performClick()
        waitForText("Signing key is not backed up")
        assertNull(remote.backupOf("u1"))
    }

    @Test fun forgettingThePassphraseWhileThePhoneStillHasTheKeyOffersANewPassphraseInstead() {
        seedBackupOfKeyA()
        show(FakeKeySource(pemA))
        signIn()
        waitForText("Signing key backed up")
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.FORGOT)
        rule.onNodeWithTag(AccountTags.FORGOT).performClick()
        waitForText("You do not need the old passphrase")
        rule.onNodeWithTag(AccountTags.RESET_NEW_PASSPHRASE).performClick()
        waitForTag(AccountTags.PASSPHRASE_CONFIRM)
    }

    @Test fun theOfferDialogAsksWhichDesignToUse() {
        show(FakeKeySource(pemA))
        signIn()
        sync.setOffer(CurrentOffer("Pixel 8", DesignSettings(name = "Night"), 5))
        waitForTag(AccountTags.OFFER_USE)
        rule.onNodeWithText("Use the design from Pixel 8?").assertExists()
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).performClick()
        assertEquals(listOf(OfferChoice.KEEP_MINE), sync.choices)
    }

    @Test fun deletingTheAccountAsksFirstThenRemovesTheCloudDataAndSignsOut() {
        show(FakeKeySource(pemA))
        signIn()
        rule.onNodeWithTag(AccountTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithText("Delete your account?").assertExists()
        rule.onNodeWithTag(AccountTags.DELETE_CANCEL).performClick()
        assertEquals(0, accounts.deleteCalls)
        rule.onNodeWithTag(AccountTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.DELETE_CONFIRM).performClick()
        waitForTag(AccountTags.SIGN_IN)
        assertEquals(1, accounts.deleteCalls)
        assertEquals("u1", remote.deletedAllFor)
        waitForText("Your account and its cloud data were deleted")
    }

    @Test fun signingOutReturnsToTheSignedOutCardAndDeletesNothing() {
        show(FakeKeySource(pemA))
        signIn()
        rule.onNodeWithTag(AccountTags.SIGN_OUT).performScrollTo().performClick()
        waitForTag(AccountTags.SIGN_IN)
        assertNull(remote.deletedAllFor)
        assertTrue(sync.stops >= 1)
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountScreenAccountsTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Account destination as a whole: the cloud card sits between the app card and the signing-key reset. */
@RunWith(AndroidJUnit4::class)
class AccountScreenAccountsTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    @Test fun withoutAProviderTheScreenSaysSignInIsNotSetUpAndStillHostsTheKeyReset() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNodeWithTag(AccountTags.NOT_CONFIGURED).performScrollTo().assertExists()
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertDoesNotExist()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertExists()
    }

    @Test fun withAControllerTheScreenOffersSignInAboveTheKeyReset() {
        val controller = AccountsController(
            AccountsAvailability.AVAILABLE, FakeAccountManager(), FakeCloudSync(),
            BackupService(FakeRemoteStore(), KeyVault(iterations = 1_000, minAcceptedIterations = 1_000), FakeKeySource(null), AttemptLimiter(MemoryKeyValueStore())),
            AccountDeletion(FakeAccountManager(), FakeRemoteStore(), FakeCloudSync(), SyncJournal(MemoryKeyValueStore())), scope,
        )
        rule.setContent { AnalogTheme { CompositionLocalProvider(LocalAccounts provides controller) { AccountScreen() } } }
        rule.onNodeWithTag(AccountTags.SIGN_IN).performScrollTo().assertExists()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertExists()
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:testDebugUnitTest --tests '*AccountTextTest*' --tests '*KeyResetCopyTest*'`
Expected: FAIL (compilation: `relativeTime`, `KeyResetCopy` unresolved).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/AccountTags.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

/** Test tags of the Account section, its dialogs and the onboarding backup page. */
object AccountTags {
    const val NOT_CONFIGURED = "account_not_configured"
    const val SIGN_IN = "account_sign_in"
    const val SIGNING_IN = "account_signing_in"
    const val SIGN_IN_ERROR = "account_sign_in_error"
    const val EMAIL = "account_email"
    const val SYNC_STATUS = "account_sync_status"
    const val BACKUP_STATUS = "account_backup_status"
    const val BACK_UP = "account_back_up"
    const val RESTORE = "account_restore"
    const val SYNC_NOW = "account_sync_now"
    const val SIGN_OUT = "account_sign_out"
    const val DELETE = "account_delete"
    const val NOTICE = "account_notice"

    const val PASSPHRASE = "account_passphrase"
    const val PASSPHRASE_CONFIRM = "account_passphrase_confirm"
    const val PASSPHRASE_STRENGTH = "account_passphrase_strength"
    const val PASSPHRASE_SUBMIT = "account_passphrase_submit"
    const val PASSPHRASE_ERROR = "account_passphrase_error"
    const val PASSPHRASE_CANCEL = "account_passphrase_cancel"
    const val FORGOT = "account_forgot_passphrase"

    const val REPLACE_CONFIRM = "account_replace_confirm"
    const val REPLACE_CANCEL = "account_replace_cancel"
    const val RESET_CONFIRM = "account_reset_confirm"
    const val RESET_CANCEL = "account_reset_cancel"
    const val RESET_NEW_PASSPHRASE = "account_reset_new_passphrase"
    const val DELETE_CONFIRM = "account_delete_confirm"
    const val DELETE_CANCEL = "account_delete_cancel"
    const val OFFER_USE = "account_offer_use"
    const val OFFER_KEEP = "account_offer_keep"

    const val PAGE_SIGN_IN = "backup_page_sign_in"
    const val PAGE_SKIP = "backup_page_skip"
    const val PAGE_DONE = "backup_page_done"
    const val PAGE_RESTORE = "backup_page_restore"
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/AccountText.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.ui.components.StatusKind

/** A pill's words and look. */
data class PillText(val text: String, val kind: StatusKind)

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun relativeTime(thenMs: Long, nowMs: Long): String {
    val seconds = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} days ago"
    }
}

fun syncPill(status: SyncStatus, nowMs: Long): PillText = when (status) {
    SyncStatus.Off -> PillText("Sync is off", StatusKind.NEUTRAL)
    SyncStatus.Syncing -> PillText("Syncing", StatusKind.BUSY)
    is SyncStatus.Idle -> PillText("Synced ${relativeTime(status.lastSyncedAtMs, nowMs)}", StatusKind.OK)
    SyncStatus.Offline -> PillText("Offline, will retry", StatusKind.WARNING)
    is SyncStatus.Error -> PillText(status.reason, StatusKind.ERROR)
}

fun backupPill(status: BackupStatus): PillText = when (status) {
    BackupStatus.SignedOut -> PillText("Sign in to back up the signing key", StatusKind.NEUTRAL)
    BackupStatus.Checking -> PillText("Checking the backup", StatusKind.BUSY)
    BackupStatus.NotBackedUp -> PillText("Signing key is not backed up", StatusKind.WARNING)
    is BackupStatus.Unknown -> PillText("Could not check the backup", StatusKind.NEUTRAL)
    is BackupStatus.BackedUp -> when (status.match) {
        KeyMatch.SAME -> PillText("Signing key backed up (key ${status.fingerprint})", StatusKind.OK)
        KeyMatch.DIFFERENT -> PillText("The backup holds another key than this phone (${status.fingerprint})", StatusKind.WARNING)
        KeyMatch.NO_LOCAL_KEY -> PillText("A backup exists; this phone has no key yet", StatusKind.WARNING)
    }
}

/** The line shown inside the passphrase dialog after a failed restore; null when the restore worked. */
fun restoreErrorText(result: RestoreResult): String? = when (result) {
    is RestoreResult.Restored -> null
    is RestoreResult.WrongPassphrase -> "That passphrase did not open the backup." +
        if (result.nextWaitMs > 0) " Wait ${(result.nextWaitMs + 999) / 1000} seconds before trying again." else " Check it and try again."
    is RestoreResult.Throttled -> "Too many wrong tries. Wait ${(result.waitMs + 999) / 1000} seconds."
    RestoreResult.NotSignedIn -> "Sign in first."
    RestoreResult.NoBackup -> "There is no backup for this account."
    is RestoreResult.Unsupported -> "This backup was made by a newer version of the app (${result.reason}). Update Analog Plus."
    RestoreResult.LocalKeyChanged -> "This phone's signing key changed meanwhile. Close this and start the restore again."
    RestoreResult.FingerprintMismatch -> "The backup opened but holds a different key than it should, so it was not used."
    is RestoreResult.KeyRejected -> result.message
    is RestoreResult.Failed -> "The restore failed: ${result.message}"
}

const val PASSPHRASE_ADVICE =
    "Choose a passphrase of at least 12 characters; four random words is a good one. Write it down and keep it somewhere " +
        "safe. If you forget it the backup cannot be opened, and nobody can recover it for you, not even us."
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/accounts/KeyResetCopy.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

/**
 * What a forgotten passphrase leads to, as found by the emulator spike (`docs/poc/key-reset-spike.md`, line
 * `RESET_RESULT:`). SELF_SERVICE: the old face can be removed on the watch itself, so the person can fix it alone.
 * NEEDS_ADB: removal needs a computer. [KeyResetCopyTest] fails when this file and the finding disagree, so the screen
 * can never promise more than the spike proved.
 */
enum class ResetPath { SELF_SERVICE, NEEDS_ADB }

data class ResetCopy(val title: String, val body: String, val confirmLabel: String, val cancelLabel: String)

object KeyResetCopy {
    /** The conservative text until the spike has proven self-service removal. */
    val CURRENT: ResetPath = ResetPath.NEEDS_ADB

    private const val COMMON =
        "Without the passphrase the backup can never be opened, so it will be deleted. Analog Plus makes a new signing key " +
            "the next time you send a face. Your saved designs are not affected.\n\n" +
            "The face that is on your watch now was signed with the old key, so the watch refuses updates to it."

    fun copyFor(path: ResetPath): ResetCopy = when (path) {
        ResetPath.SELF_SERVICE -> ResetCopy(
            title = "Start over without the passphrase?",
            body = COMMON + " To fix that, open Settings on the watch, then Apps, find the Analog Plus watch face and uninstall " +
                "it. Then press Send to watch again. If the new face does not switch on by itself, long-press the watch face and choose Analog Plus.",
            confirmLabel = "Delete backup and start over",
            cancelLabel = "Keep the backup",
        )
        ResetPath.NEEDS_ADB -> ResetCopy(
            title = "Start over without the passphrase?",
            body = COMMON + " That old face cannot be removed from the watch itself; it needs a computer with adb, the Android " +
                "debug tool: adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog. Until it is removed, " +
                "Send to watch will keep failing. If you cannot do that, keep the backup and keep looking for the passphrase.",
            confirmLabel = "Delete backup anyway",
            cancelLabel = "Keep the backup",
        )
    }

    fun current(): ResetCopy = copyFor(CURRENT)
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/PassphraseFields.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import com.seyedjafari.analogplus.phone.accounts.vault.checkPassphrase
import com.seyedjafari.analogplus.phone.accounts.vault.isAcceptable
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * What the person typed. Held with `remember`, not `rememberSaveable`: a passphrase must not be written into the saved
 * instance state. [confirmTwice] is for choosing a passphrase (two equal, acceptable entries); entering an existing one
 * only needs something typed.
 */
@Stable
class PassphraseState(private val confirmTwice: Boolean) {
    var passphrase by mutableStateOf("")
    var again by mutableStateOf("")

    val strength: PassphraseStrength get() = checkPassphrase(passphrase)
    val valid: Boolean get() = if (confirmTwice) strength.isAcceptable() && passphrase == again else passphrase.isNotEmpty()

    fun clear() { passphrase = ""; again = "" }
}

@Composable
fun rememberPassphraseState(confirmTwice: Boolean): PassphraseState = remember { PassphraseState(confirmTwice) }

fun strengthText(s: PassphraseStrength): String = when (s) {
    PassphraseStrength.TOO_SHORT -> "Too short: use at least 12 characters"
    PassphraseStrength.WEAK -> "Weak: add more words or characters"
    PassphraseStrength.GOOD -> "Good"
    PassphraseStrength.STRONG -> "Strong"
}

@Composable
fun PassphraseFields(state: PassphraseState, confirmTwice: Boolean, errorText: String?, modifier: Modifier = Modifier) {
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = state.passphrase, onValueChange = { state.passphrase = it }, singleLine = true,
            label = { Text(if (confirmTwice) "New passphrase" else "Passphrase") },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard,
            modifier = Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE),
        )
        if (confirmTwice) {
            if (state.passphrase.isNotEmpty()) {
                Text(strengthText(state.strength), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AccountTags.PASSPHRASE_STRENGTH))
            }
            OutlinedTextField(
                value = state.again, onValueChange = { state.again = it }, singleLine = true,
                label = { Text("Type it again") },
                isError = state.again.isNotEmpty() && state.again != state.passphrase,
                supportingText = { if (state.again.isNotEmpty() && state.again != state.passphrase) Text("The two entries differ") },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE_CONFIRM),
            )
        }
        if (errorText != null) ErrorBanner(errorText, Modifier.testTag(AccountTags.PASSPHRASE_ERROR))
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/AccountDialogs.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.dialogName
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.OfferChoice
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Choose a passphrase and back the signing key up (also used to change the passphrase). */
@Composable
fun SetPassphraseDialog(busy: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberPassphraseState(confirmTwice = true)
    AlertDialog(
        onDismissRequest = { state.clear(); onDismiss() },
        title = { Text("Back up your signing key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(PASSPHRASE_ADVICE, style = MaterialTheme.typography.bodyMedium)
                PassphraseFields(state, confirmTwice = true, errorText = null)
            }
        },
        confirmButton = {
            TextButton(onClick = { val p = state.passphrase; state.clear(); onSubmit(p) }, enabled = state.valid && !busy, modifier = Modifier.testTag(AccountTags.PASSPHRASE_SUBMIT)) {
                Text("Back up")
            }
        },
        dismissButton = { TextButton(onClick = { state.clear(); onDismiss() }, modifier = Modifier.testTag(AccountTags.PASSPHRASE_CANCEL)) { Text("Cancel") } },
    )
}

/** Type the passphrase of an existing backup. [errorText] is the last failure; [onForgot] opens [ForgotPassphraseDialog]. */
@Composable
fun EnterPassphraseDialog(busy: Boolean, errorText: String?, onSubmit: (String) -> Unit, onForgot: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberPassphraseState(confirmTwice = false)
    AlertDialog(
        onDismissRequest = { state.clear(); onDismiss() },
        title = { Text("Restore your signing key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("Enter the recovery passphrase you chose when you backed the key up.", style = MaterialTheme.typography.bodyMedium)
                PassphraseFields(state, confirmTwice = false, errorText = errorText)
                TextButton(onClick = onForgot, modifier = Modifier.testTag(AccountTags.FORGOT)) { Text("I forgot the passphrase") }
            }
        },
        confirmButton = {
            TextButton(onClick = { val p = state.passphrase; state.clear(); onSubmit(p) }, enabled = state.valid && !busy, modifier = Modifier.testTag(AccountTags.PASSPHRASE_SUBMIT)) {
                Text("Restore")
            }
        },
        dismissButton = { TextButton(onClick = { state.clear(); onDismiss() }, modifier = Modifier.testTag(AccountTags.PASSPHRASE_CANCEL)) { Text("Cancel") } },
    )
}

/** Shown before the passphrase is asked for when the restore would replace a key this phone already has. */
@Composable
fun ReplaceKeyDialog(plan: RestorePlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val text = when (plan) {
        is RestorePlan.ReplaceLocal ->
            "This phone already has a signing key (${plan.localFingerprint}). The backup holds a different key (${plan.backupFingerprint}). " +
                "Replace it only if the face on your watch was signed with the backed-up key. The key now on this phone is kept in a copy inside the app."
        is RestorePlan.ReplaceDamaged ->
            "The signing key saved on this phone is damaged. Replace it with the backed-up key (${plan.backupFingerprint})?"
        else -> ""
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace the signing key on this phone?") },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.testTag(AccountTags.REPLACE_CONFIRM)) { Text("Replace") } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag(AccountTags.REPLACE_CANCEL)) { Text("Cancel") } },
    )
}

/**
 * Forgotten passphrase. What can be offered depends on this phone's key:
 * - it holds the backed-up key: just choose a new passphrase ([onNewPassphrase]);
 * - it holds another key: delete the unreadable backup and back this phone's key up instead ([onDiscard]);
 * - it holds no usable key: delete the backup and start over; the wording is [KeyResetCopy.current], which the spike
 *   (`docs/poc/key-reset-spike.md`) decides.
 */
@Composable
fun ForgotPassphraseDialog(plan: RestorePlan, onNewPassphrase: () -> Unit, onDiscard: () -> Unit, onDismiss: () -> Unit) {
    when (plan) {
        is RestorePlan.AlreadyHaveThisKey -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("You do not need the old passphrase") },
            text = { Text("This phone still has your signing key. Choose a new passphrase and the backup is replaced.") },
            confirmButton = { TextButton(onClick = onNewPassphrase, modifier = Modifier.testTag(AccountTags.RESET_NEW_PASSPHRASE)) { Text("Choose a new passphrase") } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(AccountTags.RESET_CANCEL)) { Text("Cancel") } },
        )
        is RestorePlan.ReplaceLocal -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete the old backup?") },
            text = { Text("Without the passphrase the backup cannot be opened. You can delete it and back up the key that is on this phone instead.") },
            confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag(AccountTags.RESET_CONFIRM)) { Text("Delete old backup") } },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(AccountTags.RESET_CANCEL)) { Text("Keep it") } },
        )
        else -> {
            val copy = KeyResetCopy.current()
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(copy.title) },
                text = { Text(copy.body) },
                confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.testTag(AccountTags.RESET_CONFIRM)) { Text(copy.confirmLabel) } },
                dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(AccountTags.RESET_CANCEL)) { Text(copy.cancelLabel) } },
            )
        }
    }
}

@Composable
fun DeleteAccountDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            Text(
                "This deletes your account and everything stored in the cloud: your saved designs, the design you are editing " +
                    "and the signing-key backup. Designs and the signing key on this phone stay. You will be asked to choose your " +
                    "Google account again to confirm.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.testTag(AccountTags.DELETE_CONFIRM)) { Text("Delete account") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag(AccountTags.DELETE_CANCEL)) { Text("Cancel") } },
    )
}

@Composable
fun OfferDialog(offer: CurrentOffer, onUse: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Use the design from ${offer.fromDevice}?") },
        text = { Text("Another device saved a different version of the design you are editing (\"${dialogName(offer.design.name)}\"). Use that one here, or keep the one on this phone?") },
        confirmButton = { TextButton(onClick = onUse, modifier = Modifier.testTag(AccountTags.OFFER_USE)) { Text("Use theirs") } },
        dismissButton = { TextButton(onClick = onKeep, modifier = Modifier.testTag(AccountTags.OFFER_KEEP)) { Text("Keep mine") } },
    )
}

/** Which dialog of the account flows is open. Not saved across rotation: nothing typed in them may be persisted. */
sealed interface CloudDialog {
    data object None : CloudDialog
    data object SetPassphrase : CloudDialog
    data class Replace(val plan: RestorePlan) : CloudDialog
    data class Enter(val plan: RestorePlan, val error: String?) : CloudDialog
    data class Forgot(val plan: RestorePlan) : CloudDialog
    data object Delete : CloudDialog
}

/** Plans a restore and returns the dialog that starts it; [CloudDialog.None] when the controller already put the reason in its notice. */
suspend fun beginRestore(accounts: AccountsController): CloudDialog = when (val plan = accounts.planRestore()) {
    is RestorePlan.InstallNew, is RestorePlan.AlreadyHaveThisKey -> CloudDialog.Enter(plan, null)
    is RestorePlan.ReplaceLocal, is RestorePlan.ReplaceDamaged -> CloudDialog.Replace(plan)
    else -> CloudDialog.None
}

/** Shows the open dialog and moves between them; shared by the Account section and the onboarding page. */
@Composable
fun CloudDialogHost(dialog: CloudDialog, onChange: (CloudDialog) -> Unit, accounts: AccountsController) {
    val scope = rememberCoroutineScope()
    val ui by accounts.ui.collectAsState()
    val busy = ui.busy != Busy.NONE
    ui.offer?.let { offer ->
        OfferDialog(offer, onUse = { accounts.resolveOffer(OfferChoice.USE_REMOTE) }, onKeep = { accounts.resolveOffer(OfferChoice.KEEP_MINE) })
    }
    when (val d = dialog) {
        CloudDialog.None -> Unit
        CloudDialog.SetPassphrase -> SetPassphraseDialog(
            busy = busy,
            onSubmit = { passphrase -> onChange(CloudDialog.None); scope.launch { accounts.backUp(passphrase) } },
            onDismiss = { onChange(CloudDialog.None) },
        )
        is CloudDialog.Replace -> ReplaceKeyDialog(d.plan, onConfirm = { onChange(CloudDialog.Enter(d.plan, null)) }, onCancel = { onChange(CloudDialog.None) })
        is CloudDialog.Enter -> EnterPassphraseDialog(
            busy = busy, errorText = d.error,
            onSubmit = { passphrase ->
                scope.launch {
                    val result = accounts.restore(passphrase, d.plan)
                    onChange(if (result is RestoreResult.Restored) CloudDialog.None else CloudDialog.Enter(d.plan, restoreErrorText(result)))
                }
            },
            onForgot = { onChange(CloudDialog.Forgot(d.plan)) },
            onDismiss = { onChange(CloudDialog.None) },
        )
        is CloudDialog.Forgot -> ForgotPassphraseDialog(
            d.plan,
            onNewPassphrase = { onChange(CloudDialog.SetPassphrase) },
            onDiscard = { onChange(CloudDialog.None); scope.launch { accounts.discardBackup() } },
            onDismiss = { onChange(CloudDialog.Enter(d.plan, null)) },
        )
        CloudDialog.Delete -> DeleteAccountDialog(
            busy = busy,
            onConfirm = { onChange(CloudDialog.None); scope.launch { accounts.deleteAccount() } },
            onDismiss = { onChange(CloudDialog.None) },
        )
    }
}
```

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/CloudSection.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.AccountsUi
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/** The "Backup and sync" card of the Account destination. */
@Composable
fun AccountSection(modifier: Modifier = Modifier) {
    val accounts = LocalAccounts.current
    val ui by accounts.ui.collectAsState()
    var dialog by remember { mutableStateOf<CloudDialog>(CloudDialog.None) }
    val scope = rememberCoroutineScope()

    SectionCard("Backup and sync", modifier) {
        when {
            ui.availability == AccountsAvailability.NOT_CONFIGURED -> Text(
                "Sign-in is not set up in this build. Your designs are saved on this phone only.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(AccountTags.NOT_CONFIGURED),
            )
            else -> when (val account = ui.account) {
                AccountState.SignedOut -> SignedOutContent(accounts, null)
                is AccountState.Failed -> SignedOutContent(accounts, account.message)
                AccountState.SigningIn -> Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(AccountTags.SIGNING_IN)) {
                    CircularProgressIndicator(Modifier.size(Spacing.xl))
                    Text("Signing in...")
                }
                is AccountState.SignedIn -> SignedInContent(
                    ui, account,
                    onSync = { accounts.refresh() },
                    onSignOut = { accounts.signOut() },
                    onBackUp = { dialog = CloudDialog.SetPassphrase },
                    onRestore = { scope.launch { dialog = beginRestore(accounts) } },
                    onDelete = { dialog = CloudDialog.Delete },
                )
            }
        }
        ui.notice?.let { n ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag(AccountTags.NOTICE)) {
                if (n.kind == NoticeKind.ERROR) ErrorBanner(n.text, Modifier.weight(1f)) else Text(n.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { accounts.dismissNotice() }) { Text("OK") }
            }
        }
    }

    CloudDialogHost(dialog, { dialog = it }, accounts)
}

@Composable
private fun SignedOutContent(accounts: AccountsController, failure: String?) {
    Text(
        "Sign in with Google to keep your saved designs on all your devices and to back up your signing key, so a new phone " +
            "can keep updating the face on your watch. Everything works without signing in.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        "Only your email address and the designs you create are stored. You can delete them at any time.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (failure != null) ErrorBanner(failure, Modifier.testTag(AccountTags.SIGN_IN_ERROR))
    PrimaryButton("Sign in with Google", { accounts.signIn() }, Modifier.fillMaxWidth().testTag(AccountTags.SIGN_IN))
}

@Composable
private fun SignedInContent(
    ui: AccountsUi, account: AccountState.SignedIn,
    onSync: () -> Unit, onSignOut: () -> Unit, onBackUp: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit,
) {
    val busy = ui.busy != Busy.NONE
    Text("Signed in as ${account.email ?: "your Google account"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(AccountTags.EMAIL))
    val sync = syncPill(ui.sync, System.currentTimeMillis())
    StatusPill(sync.text, sync.kind, Modifier.testTag(AccountTags.SYNC_STATUS))
    val backup = backupPill(ui.backup)
    StatusPill(backup.text, backup.kind, Modifier.testTag(AccountTags.BACKUP_STATUS))
    if (busy) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(Spacing.xl))
            Text(
                when (ui.busy) {
                    Busy.BACKING_UP -> "Backing up and checking the backup..."
                    Busy.RESTORING -> "Restoring..."
                    Busy.DELETING -> "Deleting your account..."
                    Busy.NONE -> ""
                },
            )
        }
    }
    val backedUp = ui.backup is BackupStatus.BackedUp
    val needsRestore = (ui.backup as? BackupStatus.BackedUp)?.match.let { it == KeyMatch.NO_LOCAL_KEY }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        if (needsRestore) PrimaryButton("Restore signing key from backup", onRestore, Modifier.fillMaxWidth().testTag(AccountTags.RESTORE), enabled = !busy)
        PrimaryButton(
            if (backedUp) "Change passphrase" else "Back up signing key", onBackUp,
            Modifier.fillMaxWidth().testTag(AccountTags.BACK_UP), enabled = !busy && ui.backup !is BackupStatus.SignedOut,
        )
        if (backedUp && !needsRestore) SecondaryButton("Restore signing key from backup", onRestore, Modifier.fillMaxWidth().testTag(AccountTags.RESTORE), enabled = !busy)
        SecondaryButton("Sync now", onSync, Modifier.fillMaxWidth().testTag(AccountTags.SYNC_NOW), enabled = !busy)
        SecondaryButton("Sign out", onSignOut, Modifier.fillMaxWidth().testTag(AccountTags.SIGN_OUT), enabled = !busy)
        TextButton(onClick = onDelete, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETE)) {
            Text("Delete account and cloud data", color = MaterialTheme.colorScheme.error)
        }
    }
    Text("Signing out keeps your designs on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
```

In `ui/account/AccountScreen.kt` (plan 1, Task 8) replace the whole `SectionCard("Backup and sync") { ... }` block, the one that holds `StatusPill("Sign-in is coming", StatusKind.NEUTRAL)`, with:

```kotlin
        AccountSection()
```

and delete the `StatusPill` and `StatusKind` imports if nothing else in the file uses them. Keep every other card (the app card, the signing-key section, and the onboarding plan's "Setup" card if it already landed). Find the block with `grep -n "Backup and sync" phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/AccountScreen.kt`.

**Choose the reset text from the finding.** Run `grep '^RESET_RESULT:' docs/poc/key-reset-spike.md`. The file `KeyResetCopy.kt` ships with `CURRENT = ResetPath.NEEDS_ADB` (the text that promises less). If the finding says `SELF_SERVICE`, change that one line to `val CURRENT: ResetPath = ResetPath.SELF_SERVICE`.

- [ ] **Step 4: Run the JVM tests, including the gate**

Run: `./gradlew :phone:testDebugUnitTest`
Expected: PASS (5 + 3 new). `theScreenPromisesOnlyWhatTheSpikeProved` fails with a message naming the file if the finding is missing or disagrees with `KeyResetCopy.CURRENT`; that is the gate working. (It reads `../docs/poc/key-reset-spike.md` relative to the `phone/` module directory, where Gradle runs unit tests.)

- [ ] **Step 5: Run the UI tests on the phone emulator**

Run: `scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.seyedjafari.analogplus.phone.accounts`
Expected: PASS (14 + 2 new here; the emulator-backed classes from Task 11 run too and need the Firebase emulators, which `scripts/accounts_e2e.sh` starts; without them they skip). The Account section tests cover: signed-out and not-configured cards, the failed-sign-in reason, sync-pill states, weak and mismatched passphrases cannot be submitted, a good passphrase stores and shows the backup, restore on a phone with no key (wrong passphrase then right), replace confirmation with a kept copy, forgotten passphrase in its three variants (the reset text equals `KeyResetCopy.current()`), the other-device offer, deleting the account (asks first, then signs out), signing out.

- [ ] **Step 6: Look at it, in both themes and at font scale 2.0**

```bash
adb -s emulator-5562 shell settings put system font_scale 2.0
adb -s emulator-5562 shell cmd uimode night yes
```

Open Account on the debug build (it is in emulator mode), sign in, open each dialog, and take screenshots (`adb exec-out screencap -p > docs/screenshots/accounts/<name>.png`). Check: nothing clipped that blocks a button, the passphrase dialog scrolls instead of hiding "Back up", contrast holds. Fix in the owning file. Reset with `settings put system font_scale 1.0` and `cmd uimode night no`.

- [ ] **Step 7: Commit**

```bash
git add phone/src/main/kotlin phone/src/test/kotlin phone/src/androidTest/kotlin docs/screenshots/accounts
git commit -m "feat(phone): Account screen sign-in, sync and backup UI, passphrase dialogs, reset text gated by the spike"
```

---

### Task 15: The onboarding backup page

Needs the onboarding plan merged (`BackupPageSlot`, `OnboardingHost`, `OnboardingConfig`, `OnboardingController`, `OnboardingScreen`). **Contract used (from that plan, Tasks 1, 9, 10, 11):** `typealias BackupPageSlot = @Composable (onDone: () -> Unit) -> Unit`; `class OnboardingHost(val controller: OnboardingController, val backupPage: BackupPageSlot = { onDone -> BackupPagePlaceholder(onDone) })`; `object OnboardingConfig { val accountsAvailable: Boolean = false }` in `OnboardingState.kt`; the page list is `onboardingPages(OnboardingConfig.accountsAvailable)` and is read when `OnboardingController` is constructed; the backup page brings its own buttons and calls `onDone()` when finished or declined. If the merged onboarding code names anything differently, follow the merged code and keep this task's behaviour.

**Files:**
- Modify: `OnboardingState.kt` (the flag), `MainActivity.kt` (set the flag, pass the page)
- Use: `ui/account/BackupPage.kt` (created below)
- Test: `phone/src/androidTest/kotlin/.../accounts/BackupPageTest.kt`, `.../accounts/OnboardingBackupSlotTest.kt`

**Interfaces:**
- Produces: `@Composable fun BackupPage(onDone: () -> Unit)` (package `ui.account`; reads `LocalAccounts.current`); `OnboardingConfig.accountsAvailable` becomes a `@Volatile var`, set once in `AppModel` to `accounts.availability == AccountsAvailability.AVAILABLE`, so a build without Firebase keeps the four-page flow.

What the page does (spec, onboarding step 5): "Sign in with Google" or "Not now"; after sign-in, set the recovery passphrase (same rules as the Account screen), "Skip for now" at any step; a returning user whose account already has a backup is offered "Restore my signing key" (same replace-confirmation and passphrase dialogs) instead of a new passphrase; "Done" once the key is backed up.

- [ ] **Step 1: Write the failing tests**

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/BackupPageTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.BackupPage
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPageTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accounts = FakeAccountManager()
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val pemA = FakeKeySource.samplePem("A")
    private val passphrase = "correct horse battery staple"
    private var done = 0

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    private fun show(keys: FakeKeySource) {
        val controller = AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Default),
            AccountDeletion(accounts, remote, sync, SyncJournal(MemoryKeyValueStore())), scope,
        )
        rule.setContent { AnalogTheme { CompositionLocalProvider(LocalAccounts provides controller) { BackupPage(onDone = { done++ }) } } }
    }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun notNowSkipsWithoutSigningIn() {
        show(FakeKeySource(pemA))
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performClick()
        assertEquals(1, done)
        assertEquals(0, accounts.signInCalls)
    }

    @Test fun signInThenAPassphraseBacksTheKeyUpAndDoneFinishesThePage() {
        show(FakeKeySource(pemA))
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PAGE_DONE)
        assertNotNull(remote.backupOf("u1"))
        rule.onNodeWithTag(AccountTags.PAGE_DONE).performClick()
        assertEquals(1, done)
    }

    @Test fun skippingAfterSignInLeavesNoBackup() {
        show(FakeKeySource(pemA))
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performClick()
        assertEquals(1, done)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun anExistingBackupOffersTheRestoreInsteadOfANewPassphrase() {
        runBlocking { BackupService(remote, vault, FakeKeySource(pemA), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined).upload("u1", passphrase) }
        val keys = FakeKeySource(null)
        show(keys)
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.PAGE_RESTORE)
        rule.onNodeWithTag(AccountTags.PAGE_RESTORE).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PAGE_DONE)
        assertEquals(pemA, keys.pem())
    }

    @Test fun aFailedSignInShowsTheReasonAndStillAllowsNotNow() {
        accounts.nextSignIn = AccountState.Failed(AccountFailure.NETWORK, "No connection. Check your network and try again.")
        show(FakeKeySource(pemA))
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.SIGN_IN_ERROR)
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performClick()
        assertEquals(1, done)
        rule.onNodeWithTag(AccountTags.SIGN_IN_ERROR).assertExists()
        assertEquals(true, rule.onAllNodes(hasText("No connection", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }
}
```

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/OnboardingBackupSlotTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.OnboardingController
import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.OnboardingState
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.onboardingPages
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.BackupPage
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingScreen
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The accounts plan's page really is the last page of the onboarding flow, and declining it finishes setup. */
@RunWith(AndroidJUnit4::class)
class OnboardingBackupSlotTest {
    @get:Rule val rule = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @After fun stop() { scope.coroutineContext[Job]?.cancel() }

    @Test fun notNowOnTheBackupPageCompletesSetup() {
        val store = Store(MemoryKeyValueStore(), File(cache, "slot-${System.nanoTime()}.jpg"))
        store.saveOnboardingState(OnboardingState.InProgress(OnboardingPage.BACKUP))
        val controller = OnboardingController(store, onboardingPages(accountsAvailable = true))
        val accounts = AccountsController(
            AccountsAvailability.AVAILABLE, FakeAccountManager(), FakeCloudSync(),
            BackupService(FakeRemoteStore(), KeyVault(iterations = 1_000, minAcceptedIterations = 1_000), FakeKeySource(null), AttemptLimiter(MemoryKeyValueStore())),
            AccountDeletion(FakeAccountManager(), FakeRemoteStore(), FakeCloudSync(), SyncJournal(MemoryKeyValueStore())), scope,
        )
        rule.setContent {
            AnalogTheme {
                CompositionLocalProvider(LocalAccounts provides accounts) {
                    OnboardingScreen(controller, { onDone -> BackupPage(onDone) }, EditorState(), { WatchStatus(CheckStatus.OK, CheckStatus.OK) })
                }
            }
        }
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performClick()
        assertEquals(OnboardingState.Completed, controller.state.value)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :phone:compileDebugAndroidTestKotlin`
Expected: FAIL (compilation: `BackupPage` unresolved in `OnboardingBackupSlotTest`, if `BackupPage.kt` does not exist yet; or `OnboardingConfig.accountsAvailable` is a `val`, which this task changes).

- [ ] **Step 3: Implement**

`phone/src/main/kotlin/com/seyedjafari/analogplus/phone/ui/account/BackupPage.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * The last page of onboarding: sign in with Google, then choose a recovery passphrase. Optional at every step
 * ("Not now" and "Skip for now" call [onDone] without doing anything); everything here is also in the Account screen.
 * A returning user whose account already holds a backup is offered the restore instead.
 */
@Composable
fun BackupPage(onDone: () -> Unit) {
    val accounts = LocalAccounts.current
    val ui by accounts.ui.collectAsState()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<CloudDialog>(CloudDialog.None) }
    val passphrase = rememberPassphraseState(confirmTwice = true)

    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text("Keep your designs safe", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Optional. Sign in with Google and your saved designs follow you to a new phone, and your signing key is backed up " +
                "(encrypted with a passphrase only you know), so a new phone can keep updating the face on your watch.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (val account = ui.account) {
            AccountState.SignedOut, is AccountState.Failed -> {
                if (account is AccountState.Failed) ErrorBanner(account.message, Modifier.testTag(AccountTags.SIGN_IN_ERROR))
                PrimaryButton("Sign in with Google", { accounts.signIn() }, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SIGN_IN))
                SecondaryButton("Not now", onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SKIP))
            }
            AccountState.SigningIn -> Text("Signing in...", modifier = Modifier.testTag(AccountTags.SIGNING_IN))
            is AccountState.SignedIn -> {
                Text("Signed in as ${account.email ?: "your Google account"}", modifier = Modifier.testTag(AccountTags.EMAIL))
                val backup = ui.backup
                when {
                    backup is BackupStatus.BackedUp && backup.match == KeyMatch.SAME -> {
                        Text("Your signing key is backed up.", modifier = Modifier.testTag(AccountTags.BACKUP_STATUS))
                        PrimaryButton("Done", onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_DONE))
                    }
                    backup is BackupStatus.BackedUp -> {
                        Text("We found a backup of a signing key for this account.", modifier = Modifier.testTag(AccountTags.BACKUP_STATUS))
                        PrimaryButton(
                            "Restore my signing key", { scope.launch { dialog = beginRestore(accounts) } },
                            Modifier.fillMaxWidth().testTag(AccountTags.PAGE_RESTORE), enabled = ui.busy == Busy.NONE,
                        )
                        SecondaryButton("Skip for now", onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SKIP))
                    }
                    backup is BackupStatus.NotBackedUp -> {
                        Text(PASSPHRASE_ADVICE, style = MaterialTheme.typography.bodySmall)
                        PassphraseFields(passphrase, confirmTwice = true, errorText = ui.notice?.takeIf { it.kind == NoticeKind.ERROR }?.text)
                        PrimaryButton(
                            "Back up my signing key",
                            { val p = passphrase.passphrase; passphrase.clear(); scope.launch { accounts.backUp(p) } },
                            Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE_SUBMIT),
                            enabled = passphrase.valid, loading = ui.busy == Busy.BACKING_UP,
                        )
                        SecondaryButton("Skip for now", onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SKIP))
                    }
                    else -> {
                        Text("Checking for a backup...", modifier = Modifier.testTag(AccountTags.BACKUP_STATUS))
                        SecondaryButton("Skip for now", onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SKIP))
                    }
                }
            }
        }
    }
    CloudDialogHost(dialog, { dialog = it }, accounts)
}
```

In `phone/src/main/kotlin/com/seyedjafari/analogplus/phone/OnboardingState.kt`, inside `object OnboardingConfig`, replace

```kotlin
    val accountsAvailable: Boolean = false
```

with

```kotlin
    @Volatile var accountsAvailable: Boolean = false
```

and change the comment above it to say that `AppModel` sets it when the build can reach a Firebase project.

In `MainActivity.kt`:

1. Extend the `accounts` property added in Task 12 so that it ends with `.also { ... }` (this keeps the flag set before `OnboardingController` is constructed, which reads it):

```kotlin
    val accounts: AccountsController = AccountsFactory.create(
        app, store, viewModelScope, currentDesign = { state.design }, applier = CurrentApplier { design -> state.replace(design) },
    ).also { OnboardingConfig.accountsAvailable = it.availability == AccountsAvailability.AVAILABLE }
```

   with the import `com.seyedjafari.analogplus.phone.accounts.AccountsAvailability`. Check the order: `val accounts` must be declared **above** `val onboarding = OnboardingController(store)`.

2. Where `setContent` creates the host (`val host = remember { OnboardingHost(model.onboarding) }`), pass the page:

```kotlin
        val host = remember { OnboardingHost(model.onboarding, backupPage = { onDone -> BackupPage(onDone) }) }
```

   with the import `com.seyedjafari.analogplus.phone.ui.account.BackupPage`.

- [ ] **Step 4: Run to verify they pass, then the onboarding suites**

Run: `./gradlew :phone:testDebugUnitTest && scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest`
Expected: PASS: 5 new `BackupPageTest` tests (not now / skip without signing in; sign in then passphrase then Done; skip after sign-in leaves no backup; an existing backup offers the restore; a failed sign-in shows the reason and still allows Not now), the slot test, and every onboarding test unchanged (`accountsAreOffByDefault` in the onboarding JVM tests still holds: a JVM test process never builds an `AppModel`, so the flag keeps its default).

- [ ] **Step 5: Commit**

```bash
git add phone/src/main/kotlin phone/src/androidTest/kotlin
git commit -m "feat(phone): onboarding backup page, accounts flag follows the build's Firebase setup"
```

---

### Task 16: Replacing a phone, end to end

The whole story through the real Account UI, the real Firebase code and the Firebase emulators: phone 1 signs in, saves a design and backs the key up; phone 2 (fresh storage, no key) signs in, receives the design, restores the key and proves it has the same fingerprint; the account is deleted and the cloud is empty.

**Files:**
- Test: `phone/src/androidTest/kotlin/.../accounts/AccountsEndToEndTest.kt`

**Interfaces:**
- Consumes: everything above. It builds two controllers with `AccountsFactory.create(...)`, each with its own `Store`, `KeyStore` file and journal storage, and swaps them under one `AccountSection` to play "phone 1" then "phone 2". The Auth emulator treats the same email as the same account, so both phones are one user. Deletion is checked through the Firestore emulator's REST API with its `owner` token, which skips the rules.

- [ ] **Step 1: Write the test**

`phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsEndToEndTest.kt`:

```kotlin
package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.keyFingerprint
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replacing a phone, end to end, with the real Firebase code against the Firebase Emulator Suite and the real Account UI:
 * phone 1 signs in, saves a design, backs the signing key up; phone 2 (fresh storage, no key) signs in, receives the
 * design, restores the key (fingerprint equal), and deletes the account (cloud data gone).
 * Needs `cd firebase && npm run emulators`; skipped when they are not running.
 */
@RunWith(AndroidJUnit4::class)
class AccountsEndToEndTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val passphrase = "correct horse battery staple"
    private val tag = System.nanoTime()
    private val email = "e2e-$tag@example.com"
    private val marker get() = context.getFileStreamPath("use-firebase-emulator")

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(FirebaseProvider.EMULATOR_HOST, port), 1_500) }; true
    } catch (e: Exception) { false }

    @Before fun setUp() {
        assumeTrue("Firebase emulators are not running", reachable(FirebaseProvider.AUTH_EMULATOR_PORT) && reachable(FirebaseProvider.FIRESTORE_EMULATOR_PORT))
        marker.writeText("$email\n")
        FirebaseProvider.setUp(context)
        FirebaseAuth.getInstance().signOut()
    }

    @After fun cleanUp() {
        marker.delete()
        scope.coroutineContext[Job]?.cancel()
        listOf("e2e1-$tag.pem", "e2e2-$tag.pem").forEach { File(context.filesDir, it).delete(); File(context.filesDir, "$it.bak").delete() }
    }

    private fun waitForTag(t: String) = rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(t: String) = rule.waitUntil(15_000) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(t: String) = rule.waitUntil(15_000) { rule.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isEmpty() }
    private fun await(what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!check()) { if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what"); Thread.sleep(100) }
    }

    /** Lists a Firestore path through the emulator's REST API with the "owner" token, which skips the security rules. */
    private fun ownerCount(path: String): Int {
        val url = URL("http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/v1/projects/${FirebaseProvider.EMULATOR_PROJECT_ID}/databases/(default)/documents/$path")
        val connection = url.openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer owner")
        val body = connection.inputStream.bufferedReader().readText()
        return Regex("\"name\"").findAll(body).count()
    }

    private class Phone(val store: Store, val keys: KeyStore, val controller: AccountsController)

    private fun phone(n: Int, withKey: String?): Phone {
        val keys = KeyStore(context, "e2e$n-$tag.pem")
        if (withKey != null) keys.installPem(withKey)
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "e2e$n-$tag.jpg"))
        val controller = AccountsFactory.create(context, store, scope, { DesignSettings() }, CurrentApplier { }, keys, MemoryKeyValueStore())
        return Phone(store, keys, controller)
    }

    @Test fun replacingAPhoneKeepsDesignsAndTheSigningKey() {
        val pem = FakeKeySource.samplePem("e2e-$tag")
        val phone1 = phone(1, pem)
        var shown by mutableStateOf(phone1.controller)
        rule.setContent {
            AnalogTheme { CompositionLocalProvider(LocalAccounts provides shown) { Column(Modifier.verticalScroll(rememberScrollState())) { AccountSection() } } }
        }

        // phone 1: sign in with the fake Google chooser (emulator mode), save a design, back the key up
        rule.onNodeWithTag(AccountTags.SIGN_IN).performClick()
        waitForText("Signed in as $email")
        val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
        phone1.store.saveNamed("Night", night)
        val uid = FirebaseAuth.getInstance().currentUser!!.uid
        val remote = FirestoreRemoteStore(FirebaseFirestore.getInstance())
        await("the design to reach the cloud") { runBlocking { remote.fetchDesigns(uid) }.any { it.name == "Night" } }
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForText("Signing key backed up (key")
        val backup = runBlocking { remote.getBackup(uid) }!!
        assertEquals(keyFingerprint(pem), backup.sealed.keyFingerprint)

        // phone 2: new storage, no key, same Google account
        rule.onNodeWithTag(AccountTags.SIGN_OUT).performScrollTo().performClick()
        waitForTag(AccountTags.SIGN_IN)
        val phone2 = phone(2, null)
        rule.runOnUiThread { shown = phone2.controller }
        rule.onNodeWithTag(AccountTags.SIGN_IN).performClick()
        waitForText("Signed in as $email")
        assertEquals(uid, FirebaseAuth.getInstance().currentUser!!.uid)
        await("the design to arrive on phone 2") { phone2.store.savedDesigns().any { it.name == "Night" && it.design == night } }
        waitForText("A backup exists")
        assertTrue(!phone2.keys.hasKey())
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForText("Signing key restored")
        assertEquals(keyFingerprint(pem), keyFingerprint(phone2.keys.pem()))

        // delete the account: cloud data is gone, local designs and key stay
        assertTrue(ownerCount("users/$uid/designs") > 0)
        rule.onNodeWithTag(AccountTags.DELETE).performScrollTo().performClick()
        rule.onNodeWithTag(AccountTags.DELETE_CONFIRM).performClick()
        waitForText("Your account and its cloud data were deleted")
        assertNull(FirebaseAuth.getInstance().currentUser)
        assertEquals(0, ownerCount("users/$uid/designs"))
        assertEquals(0, ownerCount("users/$uid/backup"))
        assertEquals(0, ownerCount("users/$uid/state"))
        assertTrue(phone2.store.savedDesigns().any { it.name == "Night" })
        assertNotNull(phone2.keys.pem())
        assertNotEquals("", uid)
    }
}
```

- [ ] **Step 2: Run it**

Run: `scripts/accounts_e2e.sh`
Expected: `accounts e2e: PASS`, including `replacingAPhoneKeepsDesignsAndTheSigningKey`. (Without the Firebase emulators the test skips, it does not fail: `assumeTrue` guards it.) A failure at "the design to arrive on phone 2" is the sync engine; at "A backup exists" the status check; at the final `ownerCount` assertions the deletion order.

- [ ] **Step 3: Commit**

```bash
git add phone/src/androidTest/kotlin/com/seyedjafari/analogplus/phone/accounts/AccountsEndToEndTest.kt
git commit -m "test(phone): replacing a phone end to end against the Firebase emulators"
```

---

### Task 17: Documents: privacy policy, deletion page, README, CLAUDE.md, real-device checklist

Docs only; commit straight to `main`.

**Files:**
- Create: `docs/accounts/privacy-policy-draft.md`, `docs/accounts/account-deletion-page-draft.md`, `docs/accounts/real-device-checklist.md`
- Modify: `docs/poc/README.md`, `CLAUDE.md`

- [ ] **Step 1: Privacy policy draft (with the Play Data Safety answers)**

Create `docs/accounts/privacy-policy-draft.md`. Fields in `[OWNER: ...]` brackets are the only things the owner must fill in before publishing it (Task 1 item 8).

````markdown
# Analog Plus privacy policy (DRAFT)

Fill in before publishing: **[OWNER: your name or company]**, **[OWNER: contact email]**, **[OWNER: Firestore location chosen in Task 1, e.g. Europe (eur3)]**, **[OWNER: date]**.

Analog Plus is a watch face builder for Wear OS. The app works completely without an account. Signing in is optional and only adds
two things: your saved designs are kept in the cloud so they follow you to other phones, and the key that signs your watch face is
backed up so a new phone can keep updating the face on your watch.

## What we collect, and only if you sign in

| Data | Why | Where |
|---|---|---|
| Your email address and an account id, from Google sign-in | To know which cloud data is yours | Firebase Authentication (Google) |
| Your saved watch face designs (name and settings) and the design you are currently editing, including its background photo shrunk to at most 512 pixels | To sync your designs between your devices | Cloud Firestore (Google), **[OWNER: location]** |
| The name of your phone model and a random id of this app installation | To tell you which device a design came from | Cloud Firestore |
| Your signing key, **encrypted on your phone with a passphrase only you know** | So a replacement phone can keep updating your watch face | Cloud Firestore |
| An attestation that the request comes from the genuine app | To keep other programs out of the database | Firebase App Check with Google Play Integrity |

We cannot read your signing key: it is encrypted on your phone before it is uploaded, and the passphrase never leaves your phone.
If you forget the passphrase nobody, including us, can recover the backup.

We do not run ads, analytics or crash reporting in this app, we do not sell or share your data, and we do not collect anything
when you are not signed in.

## How it is protected

Data travels over TLS and is stored encrypted at rest by Google. Security rules allow each signed-in user to read and write only
their own documents. The signing key is additionally encrypted with PBKDF2-HMAC-SHA256 (600 000 iterations) and AES-256-GCM before it
leaves your phone.

## How long we keep it, and deleting it

Until you delete it. In the app: Account, **Delete account and cloud data** removes your account and everything stored in the cloud;
designs and the signing key on your phone stay. Without the app: see **[OWNER: URL of the account deletion page]**, or write to
**[OWNER: contact email]**. Signing out only stops syncing.

## Your rights

You can ask for a copy of your data, to correct it, or to erase it, at **[OWNER: contact email]**. If you are in the UK or the EU you
may also complain to your data protection authority. The data controller is **[OWNER: your name or company]**.

## Children

The app is not directed at children under 13 and does not knowingly collect their data.

## Changes

Changes are published here with a new date. Last updated: **[OWNER: date]**.

---

## Appendix: Google Play Data safety form (answers for the signed-in features)

Check each line against Firebase's own data disclosures (Authentication, Firestore, App Check) before submitting, and again whenever the
app starts collecting something new.

| Question | Answer |
|---|---|
| Does the app collect or share any user data? | Yes: collects; does not share |
| Is all collected data encrypted in transit? | Yes |
| Can users request data deletion? | Yes (in the app and on the web page) |
| Personal info: Email address | Collected; not shared; optional (only with an account); purpose: account management, app functionality |
| Personal info: User IDs | Collected; not shared; purpose: account management, app functionality |
| Photos and videos: Photos | Collected (background photo inside a synced design, shrunk); not shared; optional; purpose: app functionality |
| App activity / User-generated content | Collected (watch face designs); not shared; optional; purpose: app functionality |
| Device or other IDs | Collected (random installation id; Firebase installation id used by App Check); not shared; purpose: app functionality, security |
| Financial info, location, health, contacts, messages, web history, audio | Not collected |
| Is data processed ephemerally? | No for the items above (stored until deleted) |
````

- [ ] **Step 2: Account deletion page draft**

Create `docs/accounts/account-deletion-page-draft.md`:

````markdown
# Delete your Analog Plus account (DRAFT, publish at the URL given to Google Play)

Fill in: **[OWNER: contact email]**.

**In the app (fastest).** Open Analog Plus, go to **Account**, tap **Delete account and cloud data**, confirm, and choose your Google
account again when asked. This removes your account and everything stored in the cloud for you: your saved designs, the design you
were editing, and the encrypted signing-key backup. Designs and the signing key on your phone are not touched.

**Without the app.** Email **[OWNER: contact email]** from the address you signed in with, with the subject "Delete my Analog Plus
account". We delete the account and its data within 30 days and reply when it is done.

**What stays.** Nothing about you stays in our cloud after deletion. Backups of the cloud database made by Google for disaster
recovery expire on Google's schedule. Copies of designs you shared as links or QR codes with other people are theirs.

**Warning about the signing key.** If you delete the backup and later lose your phone, the watch face already installed on your
watch can no longer be updated from a new phone (the watch only accepts updates signed with the original key).
````

- [ ] **Step 3: Real-device checklist**

Create `docs/accounts/real-device-checklist.md`:

````markdown
# Accounts and sync: real-device checklist

Needs `phone/google-services.json` from the owner's Firebase project (`docs/accounts/owner-prerequisites.md`, items 1-5) and a debug or
release build installed on a real phone (Google Play services and a Google account on it). Tick each line; write down anything that fails.

- [ ] 1. Account, **Sign in with Google**: the Google chooser lists your account; after choosing it the card shows "Signed in as <email>"
  and a sync pill that ends at "Synced just now".
- [ ] 2. Save a design in Library. Within a few seconds Firebase console, Firestore, `users/<uid>/designs` shows a document with that
  design's name and a `deleted: false`.
- [ ] 3. Account, **Back up signing key**, choose a passphrase twice: the backup pill reads "Signing key backed up (key <16 hex>)". The
  console shows `users/<uid>/backup/signingKey` with `sealed`, `salt`, `iv`, `iterations: 600000` and no readable key. Note the
  fingerprint.
- [ ] 4. Install the app on a **second phone** (or clear the first phone's app data), sign in with the same Google account: the saved design
  appears without asking; the Account card says "A backup exists; this phone has no key yet".
- [ ] 5. **Restore signing key from backup**, a wrong passphrase first (a clear message, then a wait after the third try), then the right
  one: "Signing key restored (key ...)" with the **same fingerprint as in item 3**. Send to watch from this phone: the watch accepts the
  update in place (no key-mismatch message, version rises).
- [ ] 6. Edit the design on phone 2 and open the app on phone 1: the saved design updates; the design being edited triggers "Use the
  design from <phone> or keep this one?" exactly once.
- [ ] 7. Switch phone 1 to airplane mode, edit and delete a saved design, switch back: the pill goes "Offline, will retry" then "Synced",
  and the other phone sees the change.
- [ ] 8. **Forgotten passphrase** (new phone, no key): open Restore, choose "I forgot the passphrase": the text equals the one in
  `KeyResetCopy` for the spike result. Do **not** confirm unless you want to lose the backup.
- [ ] 9. **Key reset on the Samsung watch** (the spike's open question for real hardware): Galaxy Watch, Settings, Apps: is the Analog Plus
  watch face listed, and is there an Uninstall action? If yes and uninstalling it lets a new-key face be added, the spike result can be
  `SELF_SERVICE`; if no, it must be `NEEDS_ADB`. If this disagrees with `docs/poc/key-reset-spike.md`, edit that file's `RESET_RESULT:`
  line and `KeyResetCopy.CURRENT` together (the gate test in Task 14 enforces the pair).
- [ ] 10. **Delete account and cloud data**: you are asked to choose your Google account again; afterwards the card is signed out, the
  console shows no `users/<uid>` data and the Authentication list no longer has you; designs and the signing key on the phone are
  still there and Send to watch still works.
- [ ] 11. App Check: after enforcement is switched on (owner prerequisites item 6), a release build from Play (or an internal test
  track) still syncs; a debug build without a registered debug token is refused.
- [ ] 12. Sign-in unavailable cases: airplane mode at "Sign in" shows "No connection..."; removing the Google account from the phone
  shows the "No Google account" message.
````

- [ ] **Step 4: README and CLAUDE.md**

Append to `docs/poc/README.md`, before "## Checklist for the real-device test":

````markdown
## Accounts and sync (optional)

Signing in with Google is optional. It syncs saved designs and the design being edited between phones and backs up the
signing key encrypted with a recovery passphrase. Design: `docs/superpowers/specs/2026-10-09-accounts-sync-design.md`; plan:
`docs/superpowers/plans/2026-10-09-accounts-sync.md`; what only the owner can do (Firebase project, SHA-1s, privacy policy):
`docs/accounts/owner-prerequisites.md`; real-device checklist: `docs/accounts/real-device-checklist.md`.

- **Without `phone/google-services.json`** the app builds and runs with accounts off (the Account card says sign-in is not set up).
  **Debug builds without it** use the Firebase Emulator Suite instead, so the sign-in button works on the phone emulator with no Google
  account: `cd firebase && npm ci && npm run emulators` (Auth 9099, Firestore 8080), then use the app. The account is the first line of
  `files/use-firebase-emulator` in the app's storage (`adb shell run-as com.seyedjafari.analogplus sh -c 'echo me@example.com > files/use-firebase-emulator'`),
  default `tester@example.com`; the file also forces emulator mode in a debug build that has the real `google-services.json`.
- **Tests that need the emulators:** `scripts/accounts_e2e.sh` (starts them, runs `FirebaseEmulatorTest` and `AccountsEndToEndTest`
  on the phone emulator, stops them). Firestore rules: `cd firebase && npm test`.
- **Signing key backup:** Account, Back up signing key (choose a passphrase; it is checked by reading the backup back and opening it).
  On a new phone: sign in, Restore signing key from backup. The old key, if any, is kept in `signing-key.pem.bak` in the app's storage.
  **Forgotten passphrase:** see `docs/poc/key-reset-spike.md` for what really happens on the watch; the app's text follows that finding.
- Not synced on purpose: the face version counter (it belongs to the install on the watch). Photos are shrunk to 512 px in the cloud copy.
````

In the "Signing key." bullet under "## Known limits", append: `Since the accounts work, the key can be backed up (encrypted) and restored on a new phone; without a backup the paragraph above still applies.`

Append to `CLAUDE.md`, under "Rules that cost us time":

````markdown
- Accounts (Firebase): no `phone/google-services.json` means accounts are off (release) or emulator mode (debug); never add a Firebase import outside `accounts/firebase`, `AccountsFactory.kt` and the `FirebaseBuildType.kt` files. `phone/build.gradle.kts` carries two workarounds with reasons in comments: `-Xskip-metadata-version-check` (firebase-auth is built with newer Kotlin) and the `protobuf-java` exclusion (Firestore's lite runtime vs the face validator's full one). Firestore uses a memory cache on purpose (the sync journal is the offline queue). Never log or store the signing PEM or the recovery passphrase. Rules and their tests: `firebase/`, run by CI. Details: `docs/superpowers/plans/2026-10-09-accounts-sync.md`, owner tasks: `docs/accounts/owner-prerequisites.md`.
````

and add to "Read first" (after item 5): `6. docs/superpowers/plans/2026-10-09-accounts-sync.md and docs/poc/key-reset-spike.md: optional accounts, sync and encrypted key backup (Firebase); status and verification notes are in the plan`.

- [ ] **Step 5: Commit (docs, straight to `main`)**

```bash
git add docs/accounts docs/poc/README.md CLAUDE.md
git commit -m "docs(accounts): privacy policy and deletion page drafts, real-device checklist, README and CLAUDE.md"
```

---

### Task 18: Final gates and the real-device run

**Files:** none (verification). Anything that fails is fixed in the file that owns it, in this task, before reporting done.

- [ ] **Step 1: Everything on the JVM, both build types, no `google-services.json`**

Run: `ls phone/google-services.json 2>&1 | head -1; ./gradlew :phone:testDebugUnitTest :facegen:testDebugUnitTest :phone:assembleDebug :phone:assembleRelease :watch:assembleDebug :app:assembleDebug`
Expected: no such file; all PASS / BUILD SUCCESSFUL.

- [ ] **Step 2: The whole instrumented suite, then the emulator-backed accounts tests**

Run: `scripts/with_phone_emulator_lock.sh ./gradlew :facegen:connectedDebugAndroidTest :phone:connectedDebugAndroidTest` then `scripts/accounts_e2e.sh`
Expected: all PASS; in the second run nothing is skipped.

- [ ] **Step 3: Rules and the original POC end-to-end**

Run: `(cd firebase && npm test) && scripts/poc_e2e.sh`
Expected: 16 rules tests pass; `E2E PASSED` (a face still builds, validates, installs and updates in place with the new classpath). `scripts/poc_e2e.sh` rewrites `docs/screenshots/poc/*.png`; run `git checkout docs/screenshots/poc` afterwards unless the images changed on purpose.

- [ ] **Step 4: Real devices**

Work through `docs/accounts/real-device-checklist.md` with the owner's `google-services.json`. Items 1-7 and 10 verify sync, backup, restore and deletion; item 9 settles the reset spike for the Samsung watch. Update `docs/poc/key-reset-spike.md` and `KeyResetCopy.CURRENT` together if item 9 disagrees with the emulator.

- [ ] **Step 5: Merge**

Merge the worktree branch into `main`, delete the worktree and its branch. Do not push.

---

## Self-review

**Spec coverage** (`2026-10-09-accounts-sync-design.md`):

- *AccountManager (interface + Firebase implementation + fake), AccountState sealed type:* Tasks 10 (interface, `AccountState`, `FakeAccountManager`), 11 (`FirebaseAccountManager`, Credential Manager + Firebase Auth, re-auth before deletion).
- *CloudSync (interface, SyncStatus, Firestore implementation, fake, listener, debounce):* Tasks 7 (`RemoteStore`, `FakeRemoteStore`), 8 (`CloudSync`, `SyncStatus`, `SyncEngine`), 10 (`FakeCloudSync`), 11 (`FirestoreRemoteStore`). The Firestore implementation is the `RemoteStore` under the generic `SyncEngine`; there is no separate `FirestoreCloudSync` class (deviation, below).
- *KeyVault pure Kotlin, KDF choice, versioned parameters, typed wrong passphrase, tamper detection:* Task 4.
- *Data model (designs, state/current, backup/signingKey), photos inline at watch resolution, rules with size and field validation, rules tested on the emulator:* Tasks 2, 5 (mapper), 7 (shrinker), 11.
- *Conflict rules (per-design last write wins, tombstones, current-design prompt once per difference, first sign-in on an empty device restores silently, version counter not synced):* Tasks 7, 8. Counter: untouched by design (Global Constraints).
- *Signing-key backup and restore (passphrase never stored, fingerprint check, confirm before replacing, attempt delay, read-back self-test, backups update when the key changes):* Tasks 6, 9, 14. "Backups update when the key changes (it should not)": `upload` can be run again at any time from Change passphrase; there is no automatic re-upload, because the key never changes outside an explicit reset or restore (a Signing-key reset on its own deletes no backup; only "I forgot the passphrase" does, after a confirmation).
- *Forgotten passphrase / reset spike, UI text gated on its result:* Tasks 13, 14 (two texts written out in `KeyResetCopy`, one gate test).
- *Account destination UI states; onboarding backup page:* Tasks 14, 15.
- *Play requirements: Data Safety, privacy policy, in-app deletion + web page, App Check:* Tasks 1, 11 (App Check code), 17 (drafts).
- *Account deletion removes Firestore data then the Auth user:* Tasks 10, 11, 16.
- *Testing list: JVM, rules, instrumented UI, Auth emulator end to end, real device:* Tasks 4-10 (JVM), 2 (rules), 11/14/15/16 (instrumented), 16 (+ `scripts/accounts_e2e.sh`), 17/18 (real-device checklist).
- *Owner prerequisites + code written against the emulator until they exist:* Tasks 1, 3, 11.

**Placeholder scan.** No TBD/TODO. The two things deliberately left to the executor are data, not code: the observations in `docs/poc/key-reset-spike.md` (Task 13), and the `[OWNER: ...]` fields in the privacy policy draft (Task 17), which only the owner knows.

**Type consistency.** Names used across tasks are defined where first produced: `SealedKey`/`KeyVault`/`OpenResult` (4); `StoreObserver`, `ChangeOrigin`, `SavedChange`, `designId`, `designToRemoteJson`, `SyncJournal`, `EntryState`, `SyncMark`, `MemoryKeyValueStore` (5); `KeyStore.installPem` (6); `RemoteStore` and its documents, `planSync`, `SyncAction`, `FakeRemoteStore`, `AndroidPhotoDownscaler` (7); `CloudSync`, `SyncStatus`, `CurrentOffer`, `OfferChoice`, `CurrentApplier`, `SyncEngine` (8); `KeySource`, `AttemptLimiter`, `BackupService` and its result types, `FakeKeySource` (9); `AccountManager`, `AccountState`, `AccountsController`, `AccountsUi`, `Busy`, `Notice`, `DeletionResult`, `FakeAccountManager`, `FakeCloudSync` (10); `FirebaseProvider`, `FirestoreRemoteStore`, `FirebaseAccountManager`, `IdTokenSource`, `CurrentActivity`, `AccountsFactory` (11); `LocalAccounts` (12); `AccountSection`, `CloudDialog`, `AccountTags`, `KeyResetCopy` (14); `BackupPage` (15). The app-shell names used as-is: `AnalogTheme`, `Spacing`, `SectionCard`, `PrimaryButton`, `SecondaryButton`, `StatusPill`, `StatusKind`, `ErrorBanner`, `AccountScreen(modifier, keyStoreFactory)`, `AppShell(state, store, send, watch, pendingLink, onPendingLinkResolved)`. Onboarding names used as-is: `BackupPageSlot`, `OnboardingHost`, `OnboardingConfig.accountsAvailable`, `OnboardingController`, `OnboardingScreen`, `OnboardingState`, `OnboardingPage`, `onboardingPages`.

**Deviations from the spec and from the sibling plans** (each also in the summary to the owner):

1. **Navigation wording in the spec is corrected** (state-based `AppNav`, the Account destination already exists); done as a minimal text fix.
2. **`FirestoreCloudSync` does not exist as a class**: `SyncEngine` is the `CloudSync` implementation over the `RemoteStore` interface, which `FirestoreRemoteStore` implements. This is what makes the conflict logic testable on the JVM with the real engine instead of a mock.
3. **Firestore offline persistence is not used** (memory cache only): the spec says "Firestore offline persistence queues writes made offline"; the sync journal does that job durably across restarts and avoids a hanging `await()`.
4. **Rules cannot enforce a document-count limit**; the 200-design cap is in the client (`MAX_SYNCED_DESIGNS`), plus the Spark quotas and App Check.
5. **Photo size**: 512 px longest side (the spec says about 450 px) so 480 px watch screens are covered after cropping; the document stays far below the limit (instrumented test).
6. **Two additions to the conflict rules** to avoid data loss: keep-both for designs with no shared history that differ, and "a synced, untouched design missing in the cloud is not written back" (found by the end-to-end test).
7. **`state/current` has two extra fields**, `deviceLabel` and `schema`; **named designs carry no photo** today (the store never saved one), so photos travel only with the current design, shrunk.
8. **"New face name/package" for the reset is dropped**: the watch app can only update the single listed face. The reset path is "remove the old face, add the new key's face under the same package", and the spike decides how the person can do the removal.
9. **Plan 1's `AccountScreen` signature is unchanged**; the controller reaches the screens through `LocalAccounts` so `AppShell`'s parameters (and plan 1's and the onboarding plan's tests) stay as they are. `OnboardingConfig.accountsAvailable` changes from `val` to `@Volatile var` (the onboarding plan expected this plan to flip it).
10. **Build-level workarounds** that the spec did not foresee: `-Xskip-metadata-version-check`, the `protobuf-java` exclusion on the app's classpaths, `googleid` 1.1.1 instead of 1.2.1, BoM 34.19.0 instead of the 2-day-old 35.0.0, a debug-only cleartext exception for `10.0.2.2`, and the new `INTERNET` permission.

**Review Focus coverage:** items 1-5 map to tests in Tasks 7, 8, 9, 11, 16 as listed at the top.
