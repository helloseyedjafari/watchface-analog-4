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
| Your email address, an account id (uid), your Google name and the link to your Google profile picture, all sent by Google when you sign in | To know which cloud data is yours | Firebase Authentication (Google) |
| Your saved designs: the name and the design settings. A saved design carries no background photo | To keep your designs on all your devices | Cloud Firestore (Google), **[OWNER: location]** |
| A short record for each saved design you delete or rename: its old name, a "deleted" flag and the time, with no design content. It stays until you delete your account (see below) | So your other devices learn that the design is gone | Cloud Firestore |
| The design you are editing now, with its background photo if it has one (shrunk, see below), the time, a random id of this app installation and the model name of your phone (for example "Pixel 8") | To offer "use the design from another device" | Cloud Firestore |
| Your signing key, **encrypted on your phone with a passphrase only you know**, and with it the settings needed to open it again (method name, iteration count, salt, nonce, format version), a short fingerprint of the key (16 hex characters of its hash), a random write id and the time of the write | So a replacement phone can keep updating your watch face | Cloud Firestore |
| An attestation that the request comes from the genuine app | To keep other programs out of the database | Firebase App Check with Google Play Integrity |

Google sends your name and the link to your profile picture together with your email address. Firebase Authentication stores
them in your account record. The app itself shows only your email address. **[OWNER: confirm in the Firebase console,
Authentication, Users, that a test account shows a display name and a photo, and change this paragraph if it does not.]**

Like any Google service, Firebase receives technical data that comes with a network request, such as your IP address, and
creates a Firebase installation id for the app. We do not use these; Google processes them to run the service. See Google's
Firebase privacy information.

**Deleted and renamed designs.** When you delete or rename a saved design, a record of the old name stays in the cloud: the
name, a "deleted" flag and the time, with no design content. It stays until you delete your account. We keep it because your
other devices need it to learn that the design is gone, and we do not remove it earlier. The design's id in the cloud is also
computed from its name.

**Background photos.** Only the design you are editing is uploaded with its photo. Saved designs have no photo, on your phone or
in the cloud. The photo is shrunk before it is uploaded: the longest side is at most 512 pixels, it is saved as a JPEG, and the
app aims for about 300 KB. A photo that is already that small is uploaded as it is. The original stays on your phone. When you
use the design from another device, you get the shrunk photo. If a photo cannot be read, the design you are editing is
uploaded without it (with a plain background). A design that is still too large for the cloud after shrinking (about 900 000
bytes) is not synced and stays on that phone. At most 200 saved designs are synced.

**What stays on your phone only.** The signing key itself (unencrypted), your passphrase (the app does not save it), the
list of what is waiting to sync, the note that an account deletion was started, and the count of wrong passphrase tries. When
you restore a signing key from the backup over a different key on that phone, a copy of the replaced key (unencrypted) stays in
the app's private storage. The app cannot put it back, and it goes when you uninstall the app or clear its data.

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
guarantee: a passphrase made of a word, a year and a symbol can be rated good and still be easy to guess. Four random words are
better. The app slows down repeated wrong passphrases on your phone (the third wrong try waits 5 seconds, each further one
doubles the wait, up to 5 minutes). That delay is only on your phone. Someone who copied the encrypted backup could try
passphrases without it, so choose a long passphrase. If you forget the passphrase, nobody, including us, can recover the backup.

## How it is protected

Data travels over TLS. Google states that it encrypts Firebase data at rest. Security rules allow each signed-in user to read and write only
their own documents and check the shape and size of what is written. Only the genuine app is meant to reach the database
(App Check). **[OWNER: this is switched on only when you enforce App Check in the Firebase console, owner-prerequisites item 6.
Delete this note once it is on.]**

## How long we keep it, and deleting it

Until you delete it. The one exception is the record of a design you deleted or renamed: it stays too (see above) and is removed
only when you delete your account. In the app: Account, **Delete account and cloud data**. You are asked to choose your Google
account again. This removes your account (with your name and the profile picture link) and everything stored in the cloud for
you: your saved designs and the records of deleted ones, the design you were editing, and the signing key backup. Designs and the signing key on your phone stay. If the deletion is cut
off, the app remembers it and offers **Finish deleting** or **Keep my account**; sync stays off until you choose.

Without the app: see **[OWNER: URL of the account deletion page]**, or write to **[OWNER: contact email]**.

If another phone is still signed in to the same account, sign it out first. That phone keeps a valid sign-in for up to an hour
after the account is deleted, and the app on it cannot tell that the account is gone. An edit made on it in that time is still
uploaded and stays in the cloud although the account is deleted. The app does not stop this. Write to us and we remove it.

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
submitting, and again whenever the app starts collecting something new. The text the app shows before signing in (the Account card) says: "The cloud stores your email address, your name and profile
picture from your Google account, your designs (the names of designs you delete or rename are kept until you delete your account),
the encrypted backup of your signing key, the model of your phone and a random device ID. You can delete all of it at any
time." This policy says all of that. It also lists more: the design you are editing with its shrunk photo, a short fingerprint of
the key, the settings and write id of the backup, the App Check attestation and the Firebase installation id. Keep the two
texts from contradicting each other when either changes.

| Question | Answer |
|---|---|
| Does the app collect or share any user data? | Yes: collects; does not share |
| Is all collected data encrypted in transit? | Yes |
| Can users request data deletion? | Yes (in the app and on the web page); deleting the account removes everything above |
| Personal info: Email address | Collected; not shared; optional (only with an account); purpose: account management, app functionality |
| Personal info: User IDs | Collected (account id; random installation id); not shared; purpose: account management, app functionality |
| Personal info: Name | Collected (the Google display name, stored by Firebase Authentication); not shared; optional (only with an account); purpose: account management. **[OWNER: confirm]** |
| Photos and videos: Photos | Collected (the shrunk background photo inside the design being edited; the link to the Google profile picture stored by Firebase Authentication); not shared; optional; purpose: app functionality, account management. **[OWNER: confirm whether the profile picture link belongs here or under another category the form offers]** |
| App activity / User-generated content | Collected (watch face designs and the name of each, the names of deleted designs until the account is deleted, and the signing key backup, which is encrypted so that we cannot read it); not shared; optional; purpose: app functionality |
| Device or other IDs | Collected (random installation id; Firebase installation id used by App Check and Firebase); not shared; purpose: app functionality, security |
| Device or other: phone model name | Collected (shown on another of your devices as "the design from ..."); not shared; optional; purpose: app functionality. In the form this fits under "Device or other IDs" or "App info and performance"; choose what the form offers at submit time. **[OWNER]** |
| Financial info, location, health, contacts, messages, web history, audio, crash logs, diagnostics | Not collected |
| Is data processed ephemerally? | No for the items above (stored until deleted) |
| Is the collection required or optional? | Optional: the app works without an account |
