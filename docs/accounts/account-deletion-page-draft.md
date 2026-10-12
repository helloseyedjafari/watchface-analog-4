# Delete your Analog Plus account (DRAFT, publish at the URL given to Google Play)

Fill in: **[OWNER: contact email]**. Written from the code; not tried with a real Firebase project or a real Google account.
Code check before publishing: the paragraph "Your other phones" describes the kill switch
(the `users/<uid>/meta/account` marker in `firebase/firestore.rules`), which is built and checked only against the
Firebase emulators and fakes, and the cloud's refusal of other phones' writes works only with the rules of `firebase/firestore.rules` deployed to the project
(owner-prerequisites item 5). The question the app asks before deleting says the same in short: other phones are signed out, and
nothing more, the next time they reach the cloud; their designs and signing keys stay; a phone that is offline stays signed in until
it reconnects. Try it with a real project (checklist G1) before you publish.

**In the app (fastest).** Open Analog Plus, go to **Account**, tap **Delete account and cloud data**, then **Delete account**
in the question "Delete your account?". Choose your Google account again when asked. This removes your account and everything
stored in the cloud for you: your saved designs, the design you were editing and the signing-key backup. Designs and the
signing key on your phone are not touched.

**If the deletion is cut off** (no connection, the app is closed, the phone restarts), the app remembers it. The Account
screen then shows "Deleting your account was not finished" with **Finish deleting** and **Keep my account**. Sync stays paused
on that phone until you choose. Finish deleting asks for your Google account again, wipes whatever is left (also anything another
phone uploaded in the meantime) and removes the account. Keep my account turns sync back on and uploads the designs on that phone
again. Other phones that noticed the deletion (between its start and Keep my account) were signed out at that point and stay signed
out; sign in on them again. A phone that did not notice it is not signed out and simply syncs again.

What a cut-off deletion looks like depends on where it stopped. **Before the cloud was reached** (the app was closed or the
connection lost right at the start): nothing changed in the cloud, other phones keep syncing, and the account works as before; the
phone that started it only shows the choice. **Early** (the cloud was marked, and the data is being removed): the cloud says that the
account is being deleted, phones are signed out when they notice it, and the cloud accepts nothing new. **At the very end** (the
cloud data and the account record are gone, but the sign-in itself is not yet removed): the account is, in effect, a live account
with nothing in it. A phone that has not seen the account record before, or one that was signed out of the account earlier and
signs in again, finds the sign-in still there, creates the account record again and syncs: the account simply works again on that
phone, empty, and that phone uploads what it holds. Finish deleting, on the phone that started the deletion, wipes all of that
again and removes the sign-in; every phone that had seen the account record is then signed out with the notice the next time it
checks (one whose app has not run online for about an hour may get the plain signed-out screen instead).

**If you lose the phone that started a cut-off deletion** (it is lost, reset, or the app's data is cleared), no other phone can
finish or undo it from the app. If the cut came before the cloud was reached, nothing changed: the account works as before, with
all its data. If the cut came early, the cloud still says that the account is being deleted, so every phone that
signs in to it is signed out again: write to **[OWNER: contact email]** from the address you signed in with, and we clear the mark
or finish the deletion by hand. If the cut came at the very end, the cloud data is already gone and the account simply works again,
empty, on any phone that signs in: use it, or choose **Delete account and cloud data** again.

**Without the app.** Email **[OWNER: contact email]** from the address you signed in with, with the subject "Delete my Analog
Plus account". We delete the account and its data within **[OWNER: number of days, for example 30]** days and reply when it is
done. **[OWNER: you do this by hand in the Firebase console, in this order: 1. in Firestore, `users/<uid>/meta/account`: set `deleted`
to `true` (create the document if it is missing; `deleted` must be a real boolean, `true` here and `false` below, never the text "true"
or "false": a text value cannot be read and stops sync on every phone), so that other phones stop writing; 2. delete the documents in `designs`, `state` and `backup`; 3. delete the user under Authentication; 4. delete `meta` (the
account record), last, so that nobody who signs in meanwhile finds the account without a record. Deleting the user alone does not
delete the Firestore data. To clear an abandoned deletion for a person who wants to keep the account, set `deleted` back to `false`
instead.]**

**What is deleted.** Your account (email, account id, your Google name and the link to your profile picture), the small record of the
account itself (whether it is in use or being deleted, and the time of the last change), every saved design, the small records of designs you deleted or renamed, the design you were editing (with the name of your phone and
the random installation id), and the signing-key backup. The name of your phone is the device name you set in Android Settings, or
the model; it can contain your own name, and it can also be part of the name of a kept copy of a design, which is deleted with the
designs.

**Until you delete the account.** When you delete or rename a saved design, a record of the old name stays in the cloud, with a
"deleted" flag and the time and no design content. Your other devices need it to learn that the design is gone, so we do not
remove it earlier. Deleting the account removes it.

**Your other phones.** When the deletion starts, the cloud marks the account as deleted first, and from the moment it has done so it
refuses every new write from your other phones. Each other phone that is signed in to the account is signed out of it the next time it checks: while its app is
open and syncing, or when its next sync is refused. It keeps its local designs and its signing key. This is a sign-out inside the
app, not a cancellation of the sign-in itself: the sign-in token that Google issued to that phone stays valid until it expires
(up to an hour) and cannot be cancelled from your phone. The cloud simply has no data left for it and accepts nothing from it (apart from the case described under "What stays").
A phone that had synced with the account and whose app was closed during the deletion is signed out when you open it again (unless a phone created the account record again first, see "What stays"). A phone whose app has not run online for
more than about an hour (closed or offline) may be signed out by Google's own sign-in first, because its sign-in can no longer be
renewed for an account that does not exist; it then shows a plain signed-out screen without the notice. Its designs and signing key
stay in both cases.

**What stays.** Nothing about you stays in our cloud after deletion, with the exceptions below. They exist because the deletion
takes several steps: it marks the account deleted, removes the data, removes the account record, and only then removes the
sign-in itself.

- Before a phone creates the account record (for the sync, or for a signing-key backup) it asks Google's sign-in whether the
  account still exists, every time it is about to create it, until it has seen the record once. If the account is gone, that phone
  writes nothing and is signed out with the notice (also when it was making a signing-key backup). If there is no answer, it writes
  nothing and the sync tries again later; for a backup you try again.
- What is left is the time between the deletion removing the account record and removing the sign-in itself. It is normally short.
  If the deletion is cut off exactly between those two steps it lasts until you tap **Finish deleting** (see above). In that time a
  phone that has not seen the record finds the sign-in still there, creates the record again (a flag and a time, under your account
  id) and syncs. It can upload what it holds: its saved designs, the design being edited with its photo, the name of the phone, the
  random installation id and a signing-key backup; other phones of yours that had seen the record and are opened meanwhile then
  sync into it too. In the normal short case such a phone is signed out when its own sign-in runs out (up to an hour), without a
  notice. After a cut-off its sign-in stays valid, because the account still exists, and the phone keeps syncing until you tap
  Finish deleting, which signs it out with the notice. Finish deleting removes all of it; otherwise write to **[OWNER: contact email]**
  and we remove it.

The cloud refuses new data from your other phones from the moment the deletion starts, apart from that case. Google may keep
backup copies of its databases for a limited time, on its own schedule. Designs you shared with other people as links or QR codes
are theirs.

**Warning about the signing key.** If you delete the backup and later lose your phone, the watch face already installed on your
watch can no longer be updated from a new phone: the watch only accepts updates signed with the original key. Designs and the key
on your phone stay when you delete your account, so deleting the account on a phone you still have does not cause this.
