# Analog Plus privacy policy (DRAFT)

Status: draft written from the code of branch `wt/accounts`. It has not been checked against a real Firebase project, a real
Google sign-in or a release build, and it is not legal advice. Fill in before publishing: **[OWNER: your name or company]**,
**[OWNER: contact email]**, **[OWNER: Firestore location chosen in owner-prerequisites item 5, e.g. Europe (eur3)]**,
**[OWNER: URL of the account deletion page]**, **[OWNER: date]**. Remove this paragraph when you publish.

Analog Plus is a watch face builder for Wear OS. The app works completely without an account. Signing in is optional. It adds
two things: your saved designs are kept in the cloud so they follow you to other phones, and the key that signs your watch face
can be backed up (encrypted) so a new phone can keep updating the face on your watch.

## What we collect, and only if you sign in

If you never sign in, the app does not upload your designs, your signing key or any account data. They stay on your phone.

If you sign in with Google, the following is stored in the cloud:

| Data | Why | Where |
|---|---|---|
| Your email address and an account id (uid), from Google sign-in | To know which cloud data is yours | Firebase Authentication (Google) |
| Your saved designs: the name, the design settings, and the background photo if the design has one (see below) | To keep your designs on all your devices | Cloud Firestore (Google), **[OWNER: location]** |
| A short record for each saved design you delete or rename: its name, a "deleted" flag and the time, with no design content | So your other devices learn that the design is gone | Cloud Firestore |
| The design you are editing now, with the same content as a saved design, the time, a random id of this app installation and the model name of your phone (for example "Pixel 8") | To offer "use the design from another device" | Cloud Firestore |
| Your signing key, **encrypted on your phone with a passphrase only you know**, and with it the settings needed to open it again (method name, iteration count, salt, nonce, format version), a short fingerprint of the key (16 hex characters of its hash), a random write id and the time of the write | So a replacement phone can keep updating your watch face | Cloud Firestore |
| An attestation that the request comes from the genuine app | To keep other programs out of the database | Firebase App Check with Google Play Integrity |

The app asks Google for your email address and account id only. It does not read or show your name or profile picture.
Firebase Authentication receives what Google sends when you sign in and may keep more of it in your account record than the app
uses. **[OWNER: open one test account in the Firebase console, Authentication, Users, and say here what it shows (for example
display name and photo), or delete this sentence.]**

Like any Google service, Firebase receives technical data that comes with a network request, such as your IP address, and
creates a Firebase installation id for the app. We do not use these; Google processes them to run the service. See Google's
Firebase privacy information.

**Background photos.** A photo in a synced design is shrunk before it is uploaded: the longest side is at most 512 pixels, and
it is saved as a JPEG, and the app aims for about 300 KB. A photo that is already that small is uploaded as it is. The original stays on
your phone. A design restored on another phone has the shrunk photo. If a photo cannot be read, the design is synced without
it (with a plain background) and the app tells you how many designs were synced without their photo. A design that is still
too large for the cloud after shrinking (about 900 000 bytes) is not synced and stays on that phone. At most 200 saved designs
are synced.

**What stays on your phone only.** The signing key itself (unencrypted), your passphrase (the app does not save it), the
list of what is waiting to sync, the note that an account deletion was started, and the count of wrong passphrase tries.

We do not run ads, analytics or crash reporting in this app, we do not sell or share your data, and we do not collect anything
from you when you are not signed in.

## The signing key backup

Analog Plus signs your watch face with a key. Without that key the watch refuses updates to the face. The backup is
encrypted on your phone before it is uploaded. The passphrase is never uploaded and the app does not store it, so we cannot
read your key.

The key is encrypted with AES-256-GCM. The encryption key is made from your passphrase with PBKDF2-HMAC-SHA256 and 600 000
iterations, with a random salt. The method, the iteration count, the salt and the key fingerprint are bound to the encrypted
data, so a changed backup does not open. The app only opens backups with 100 000 to 5 000 000 iterations.

The app requires a passphrase of at least 12 characters and shows an estimate of its strength. It is an estimate, not a
guarantee. The app slows down repeated wrong passphrases on your phone (the third wrong try waits 5 seconds, each further one
doubles the wait, up to 5 minutes). That delay is only on your phone. Someone who copied the encrypted backup could try
passphrases without it, so choose a long passphrase. If you forget the passphrase, nobody, including us, can recover the backup.

## How it is protected

Data travels over TLS. Google states that it encrypts Firebase data at rest. Security rules allow each signed-in user to read and write only
their own documents and check the shape and size of what is written. Only the genuine app is meant to reach the database
(App Check). **[OWNER: this is switched on only when you enforce App Check in the Firebase console, owner-prerequisites item 6.
Delete this note once it is on.]**

## How long we keep it, and deleting it

Until you delete it. In the app: Account, **Delete account and cloud data**. You are asked to choose your Google account again.
This removes your account and everything stored in the cloud for you: your saved designs and the records of deleted ones, the
design you were editing, and the signing key backup. Designs and the signing key on your phone stay. If the deletion is cut
off, the app remembers it and offers **Finish deleting** or **Keep my account**; sync stays off until you choose.

Without the app: see **[OWNER: URL of the account deletion page]**, or write to **[OWNER: contact email]**.

Signing out only stops syncing. Your cloud data stays until you delete it. Google may keep backup copies of its databases for
a limited time after deletion, on its own schedule.

## Your rights

You can ask for a copy of your data, to correct it, or to erase it, at **[OWNER: contact email]**. If you are in the UK or the
EU you may also complain to your data protection authority. The data controller is **[OWNER: your name or company]**.

## Children

The app is not directed at children under 13 and does not knowingly collect their data.

## Changes

Changes are published here with a new date. Last updated: **[OWNER: date]**.

---

## Appendix: Google Play Data safety form (answers for the signed-in features)

Written from the code. Check each line against Firebase's own data disclosures (Authentication, Firestore, App Check) before
submitting, and again whenever the app starts collecting something new. The app text that the person sees before signing in says:
"The cloud stores your email address, your designs, an encrypted copy of your signing key if you back it up, and the model
name of your phone with a random device ID." This policy lists the same things.

| Question | Answer |
|---|---|
| Does the app collect or share any user data? | Yes: collects; does not share |
| Is all collected data encrypted in transit? | Yes |
| Can users request data deletion? | Yes (in the app and on the web page) |
| Personal info: Email address | Collected; not shared; optional (only with an account); purpose: account management, app functionality |
| Personal info: User IDs | Collected (account id; random installation id); not shared; purpose: account management, app functionality |
| Personal info: Name | Not collected by the app. **[OWNER: answer after checking what Firebase Authentication keeps from the Google profile; if it keeps the name, say Collected, optional, account management.]** |
| Photos and videos: Photos | Collected (the shrunk background photo inside a synced design); not shared; optional; purpose: app functionality |
| App activity / User-generated content | Collected (watch face designs, the name of each, and the signing key backup, which is encrypted so that we cannot read it); not shared; optional; purpose: app functionality |
| Device or other IDs | Collected (random installation id; Firebase installation id used by App Check and Firebase); not shared; purpose: app functionality, security |
| Device or other: phone model name | Collected (shown on another of your devices as "the design from ..."); not shared; optional; purpose: app functionality. In the form this fits under "Device or other IDs" or "App info and performance"; choose what the form offers at submit time. **[OWNER]** |
| Financial info, location, health, contacts, messages, web history, audio, crash logs, diagnostics | Not collected |
| Is data processed ephemerally? | No for the items above (stored until deleted) |
| Is the collection required or optional? | Optional: the app works without an account |
