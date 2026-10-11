# Accounts, sync and signing-key backup (Firebase) — design

Parent: `2026-10-08-builder-app-design.md` (its "Key loss" item and the "saved designs sync across phones" open
question). Depends on `2026-10-09-app-shell-design.md` (Account destination) and feeds the last page of
`2026-10-09-onboarding-design.md`.
Status: DRAFT, awaiting owner review (2026-10-09).

## Intent (agreed with the owner)

Saved designs sync between the user's devices and can be restored on a new phone. Owner decisions: **Firebase, no
own server; Google sign-in; sign-in is optional** (everything works offline and signed-out); **the signing key is
backed up encrypted with a recovery passphrase**, so the server never sees it.

Success: sign in on a second phone and see the same saved designs; replace a phone, enter the passphrase, and keep
updating the same watch face; signed-out use is unchanged; deleting the account removes all cloud data.

## Why a lost key matters

The pushed face is signed with one key, forever (`CLAUDE.md`). Without it the installed face can never be updated.
The key therefore needs a recovery path that does not trust the cloud with the plain key.

## Architecture

```
Phone (Store, local-first)  <->  CloudSync  <->  Firestore (users/{uid}/...)
        |                          |
   SigningKey (PEM)   ->  KeyVault (encrypt with passphrase)  ->  users/{uid}/backup/signingKey
```

The local `Store` stays the source of truth and works with no network or account. `CloudSync` mirrors it when
signed in. No code in `Store`, `SendController` or `facegen` knows about Firebase.

### Components

- **`AccountManager`** (interface + Firebase implementation): sign in with Google through Credential Manager and
  Firebase Auth, sign out, delete account (re-authenticates first when Firebase asks for a recent login). Exposes
  `AccountState` as a sealed type: `SignedOut`, `SigningIn`, `SignedIn(uid, email)`, `Failed(reason)`.
- **`CloudSync`** (interface + Firestore implementation): push local changes, pull remote changes, listen for
  remote changes while the app is in the foreground, expose `SyncStatus`: `OFF`, `IDLE`, `SYNCING`, `OFFLINE`,
  `ERROR(reason)`. A local journal (not Firestore's cache) is the durable offline queue and replays when the phone is online.
- **`KeyVault`** (pure Kotlin, JVM-testable): `seal(pem, passphrase) -> SealedKey` and
  `open(sealed, passphrase) -> pem | WrongPassphrase`. No Firebase dependency.
- Fakes for `AccountManager` and `CloudSync` in tests, in the same style as `KeyValueStore`'s in-memory fake.

### Data model (Firestore)

| Path | Fields |
|---|---|
| `users/{uid}/designs/{designId}` | `name`, `design` (the `DesignJson` string, photo downscaled and inline), `updatedAt` (server timestamp), `deleted` (tombstone, set on delete), `schema` (design JSON version) |
| `users/{uid}/state/current` | `design`, `updatedAt`, `deviceId` (the design being edited) |
| `users/{uid}/backup/signingKey` | `sealed` (base64 ciphertext), `kdf` (`"pbkdf2-hmac-sha256"` or the chosen KDF), `iterations`, `salt`, `iv`, `version`, `createdAt`, `keyFingerprint` (the existing `KeyFingerprint`, so a restored key can be checked against the installed face) |

- **Photos** are downscaled to watch resolution (512 px longest side, JPEG) and stored inline in the design document, which
  keeps every document far below Firestore's 1 MiB limit and **avoids Firebase Storage**. Checked 2026-10-09: Cloud
  Storage for Firebase needs the Blaze (pay-as-you-go) plan; Firestore and Auth fit the free Spark plan for this
  scale. A design restored from the cloud therefore carries the downscaled photo, not the original, and the app says so.
- Security rules: a user reads and writes only `users/{their uid}/**`; field and size validation (`name` length,
  document size, required fields, `schema` int); no listing of other users. Rules live in the repo (`firebase/`)
  and are tested with the Firebase Emulator Suite.

### Conflict rules

- Per saved design: last write wins by server `updatedAt`. A delete is a tombstone with a newer `updatedAt`, and a
  later edit on another device revives it only if that edit is newer.
- The current design is not merged. A device that finds a newer `state/current` from another device while it is
  signed in asks "Use the design from <device> or keep this one?" once per difference; the first sign-in on an empty
  device restores it without asking.
- Version counter (`face version`) is **not** synced: it belongs to the install on the watch and must only increase
  there. The restored phone reads the next version from the watch's reported version when available, otherwise
  starts above the highest version it can learn, and the push path already rejects non-increasing numbers.

### Signing-key backup and restore

- Passphrase is chosen by the user at first sign-in (or later in Account), minimum length and a strength hint.
  Never sent anywhere, never stored.
- `KeyVault.seal`: KDF (PBKDF2-HMAC-SHA256 with a high iteration count, or Argon2id if a vetted library is
  accepted at plan time) -> 256-bit key -> AES-256-GCM over the PEM with random IV and salt. KDF parameters and a
  `version` are stored with the blob so they can be raised later.
- **Restore on a new phone:** sign in -> app finds `backup/signingKey` -> asks for the passphrase -> decrypts ->
  verifies the PEM parses and its fingerprint equals the stored `keyFingerprint` -> installs it as the signing key
  (only when the phone has no key, or the user confirms replacing it). Wrong passphrase: clear message, unlimited
  retries are fine because the blob is useless without it, and a local attempt delay slows guessing against the
  client.
- **Forgotten passphrase:** the blob cannot be recovered. The user is told this up front. A reset deletes the blob,
  creates a new key and requires a new face identity (the old one can no longer be updated). **This reset path
  (the watch app only updates the single listed face, so the likely path is: remove the old face, then add the new key's face under the same package; one activation per install) must be verified on an emulator before the UI promises it**;
  it is a plan task, not an assumption.
- Backups update when the signing key changes (it should not) and are verified after upload by reading back and
  opening them locally in a self-test, so a bad backup is detected immediately and the user is told.

### Account destination (UI)

Navigation is state-based (`AppNav` from the app-shell plan; there is no Navigation Compose). The Account destination
already exists from that plan (`AccountScreen`: app info, a "Backup and sync" placeholder card, the signing-key reset);
this work replaces the placeholder card with the section below.

Signed out: benefits in two lines, Sign in with Google. Signed in: email, sync status pill with last sync time,
Back up signing key / change passphrase, Restore from backup, Sign out, Delete account (confirms, explains that
cloud data is removed and local designs stay). No data collected beyond the above.

## Security, privacy, store requirements

- Google Play Data Safety form and privacy policy (data: email address from Google sign-in, user-created designs;
  purpose: sync and backup; encrypted in transit; deletable). Policy text hosted at a public URL (owner action).
- In-app account deletion and a web page link for deletion requests (Play requirement for apps with accounts).
- Firebase App Check with Play Integrity on Firestore, so only the genuine app talks to it.
- Firestore quotas and per-user document-count/size limits enforced in rules; sync debounced (one write burst
  after edits stop, matching the existing save delay).
- The plain PEM never leaves the device and is never logged. `google-services.json` is not a secret but is kept out
  of public history if the repo goes public.

## Owner prerequisites (cannot be done by the agent)

Create the Firebase project; enable Google sign-in; add the app's debug and release SHA-1 fingerprints; download
`google-services.json`; choose Firestore location; decide the privacy-policy URL. The plan starts with a checklist for
these and the code is written against the Firebase Local Emulator Suite until they exist.

## Testing

- JVM: `KeyVault` round trip, wrong passphrase, tampered ciphertext, parameter versioning; conflict resolution and
  tombstones with the fake `CloudSync`; `AccountState` and `SyncStatus` transitions.
- Firestore rules tests (emulator): own data allowed; another user's, unauthenticated, oversized and malformed
  writes denied.
- Instrumented: Account screen states with fakes; sign-in, first restore on an empty device, passphrase dialogs.
- Auth emulator for end-to-end runs on phone emulators (they have no Google sign-in; see `CLAUDE.md`).
- Real device: sign in on the owner's phone, sync, restore on a second device or after a clear-data, confirm the
  restored key matches the installed face fingerprint.

## Out of scope

Email/password and other providers, sharing designs between users or a public gallery (needs moderation, see the
builder spec), syncing the version counter, web builder, end-to-end encryption of designs (only the key is
encrypted; designs are not secret).

## Known limits (accepted by the owner)

- **A late-landing design write.** If the connection drops during a design write, the write stays queued in memory and is sent
  when the phone is online again, before any sync pass runs. If another phone edited the same design meanwhile, the queued older
  edit is stamped later by the server and replaces the newer one everywhere. The planner never gets to compare them. The window
  is narrow (the link must drop during a write and another phone must edit the same design in that time). Accepted as built;
  the alternative is to write designs through a transaction that checks the cloud's `updatedAt`.
- **Tombstones stay.** A deleted or renamed saved design leaves a document (old name, `deleted: true`, empty design) in the cloud
  until the account is deleted. Other phones need it to learn about the deletion. The privacy policy and the deletion page say so.
- **Timing of uploads.** A saved-design change is uploaded about a second after it, the design being edited after 10 seconds of quiet or when the app is left. So another phone sees an edit of the design being edited some seconds late.
- **A backup write has no time limit.** While a backup is being written on a poor connection, sign-out waits for it (Firestore
  gives up after its own five attempts).
- **Deleting the account while another phone is signed in.** The other phone keeps a valid sign-in for up to an hour. The code does not tell an edit made there after the deletion from normal sync, so that edit is still uploaded and its data stays in the cloud until someone removes it. The deletion page and the policy tell people to sign other phones out first. This is not fixed.
- **Nothing here was run against a real Firebase project, a real Google sign-in, App Check or a real device.**

## Open items

- Reset path after a forgotten passphrase: verify on an emulator (plan task).
- KDF choice (PBKDF2 vs Argon2id) at plan time.
- Firebase BoM and Credential Manager versions pinned at plan time.
