# Delete your Analog Plus account (DRAFT, publish at the URL given to Google Play)

Fill in: **[OWNER: contact email]**. Written from the code; not tried with a real Firebase project or a real Google account.
Code check before publishing: the paragraph "Your other phones" describes the kill switch
(the `users/<uid>/meta/account` marker in `firebase/firestore.rules`), which is built and checked only against the
Firebase emulators and fakes, and it only works with the rules of `firebase/firestore.rules` deployed to the project
(owner-prerequisites item 5). The question the app asks before deleting says the same in short: other phones are signed out, and
nothing more, the next time they reach the cloud; their designs and signing keys stay; a phone that is offline stays signed in until
it reconnects. Try it with a real project (checklist G1) before you publish.

**In the app (fastest).** Open Analog Plus, go to **Account**, tap **Delete account and cloud data**, then **Delete account**
in the question "Delete your account?". Choose your Google account again when asked. This removes your account and everything
stored in the cloud for you: your saved designs, the design you were editing and the signing-key backup. Designs and the
signing key on your phone are not touched.

**If the deletion is cut off** (no connection, the app is closed, the phone restarts), the app remembers it. The Account
screen then shows "Deleting your account was not finished" with **Finish deleting** and **Keep my account**. Sync stays paused
until you choose. Finish deleting asks for your Google account again. Keep my account turns sync back on and uploads the designs
on that phone again. Your other phones were signed out when the deletion started and they stay signed out; sign in on them again.

**If you lose the phone that started a cut-off deletion** (it is lost, reset, or the app's data is cleared), the app can no longer
finish or undo the deletion: the cloud still says that the account is being deleted, so every phone that signs in to it is signed
out again. Write to **[OWNER: contact email]** from the address you signed in with, and we clear the mark or finish the deletion by
hand.

**Without the app.** Email **[OWNER: contact email]** from the address you signed in with, with the subject "Delete my Analog
Plus account". We delete the account and its data within **[OWNER: number of days, for example 30]** days and reply when it is
done. **[OWNER: you do this by hand in the Firebase console: delete the user under Authentication, then the `users/<uid>` data under
Firestore, in this order: 1. in `users/<uid>/meta/account` set `deleted` to `true` (other phones then stop writing); 2. delete the
documents in `designs`, `state` and `backup`; 3. delete `meta` (the account record); 4. delete the user under Authentication.
Deleting the user alone does not delete the Firestore data. To clear an abandoned deletion for a person who wants to keep the
account, set `deleted` back to `false` instead.]**

**What is deleted.** Your account (email, account id, your Google name and the link to your profile picture), the small record of the
account itself (whether it is in use or being deleted, and the time of the last change), every saved design, the small records of designs you deleted or renamed, the design you were editing (with the name of your phone and
the random installation id), and the signing-key backup. The name of your phone is the device name you set in Android Settings, or
the model; it can contain your own name, and it can also be part of the name of a kept copy of a design, which is deleted with the
designs.

**Until you delete the account.** When you delete or rename a saved design, a record of the old name stays in the cloud, with a
"deleted" flag and the time and no design content. Your other devices need it to learn that the design is gone, so we do not
remove it earlier. Deleting the account removes it.

**Your other phones.** When the deletion starts, the cloud marks the account as deleted first, and from then on it refuses every new write from
your other phones. Each other phone that is signed in to the account is signed out of it the next time it checks: while its app is
open and syncing, or when its next sync is refused. It keeps its local designs and its signing key. This is a sign-out inside the
app, not a cancellation of the sign-in itself: the sign-in token that Google issued to that phone stays valid until it expires
(up to an hour) and cannot be cancelled from your phone. The cloud simply has no data left for it and accepts nothing from it.
A phone that had synced with the account and whose app was closed during the deletion is signed out when you open it again. A phone that was offline for more than an
hour may be signed out by Google's own sign-in first, because its sign-in can no longer be renewed for an account that does not exist;
it then shows a plain signed-out screen without the notice. Its designs and signing key stay in both cases.

**What stays.** Nothing about you stays in our cloud after deletion, with one narrow exception: a phone that has never synced with this account before, and whose very first look at the account comes in the few seconds after another phone finished deleting it, creates the small account record again (a flag and a time, under your account id). It holds no design and nothing else about you; write to **[OWNER: contact email]** and we remove it. The cloud refuses new data from your other phones
from the moment the deletion starts. Google may keep backup copies of its databases for a
limited time, on its own schedule. Designs you shared with other people as links or QR codes are theirs.

**Warning about the signing key.** If you delete the backup and later lose your phone, the watch face already installed on your
watch can no longer be updated from a new phone: the watch only accepts updates signed with the original key. Designs and the key
on your phone stay when you delete your account, so deleting the account on a phone you still have does not cause this.
