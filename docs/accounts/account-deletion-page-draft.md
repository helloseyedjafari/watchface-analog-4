# Delete your Analog Plus account (DRAFT, publish at the URL given to Google Play)

Fill in: **[OWNER: contact email]**. Written from the code; not tried with a real Firebase project or a real Google account.

**In the app (fastest).** Open Analog Plus, go to **Account**, tap **Delete account and cloud data**, then **Delete account**
in the question "Delete your account?". Choose your Google account again when asked. This removes your account and everything
stored in the cloud for you: your saved designs, the design you were editing and the signing-key backup. Designs and the
signing key on your phone are not touched.

**If the deletion is cut off** (no connection, the app is closed, the phone restarts), the app remembers it. The Account
screen then shows "Deleting your account was not finished" with **Finish deleting** and **Keep my account**. Sync stays paused
until you choose. Finish deleting asks for your Google account again. Keep my account turns sync back on and uploads the designs
on that phone again.

**Without the app.** Email **[OWNER: contact email]** from the address you signed in with, with the subject "Delete my Analog
Plus account". We delete the account and its data within **[OWNER: number of days, for example 30]** days and reply when it is
done. **[OWNER: you do this by hand in the Firebase console: delete the user under Authentication, then the `users/<uid>` data under
Firestore, including the `designs`, `state` and `backup` collections. Deleting the user alone does not delete the Firestore data.]**

**What is deleted.** Your account (email, account id, your Google name and the link to your profile picture), every saved
design, the small records of designs you deleted or renamed, the design you were editing (with the model name of your phone and
the random installation id), and the signing-key backup.

**Until you delete the account.** When you delete or rename a saved design, a record of the old name stays in the cloud, with a
"deleted" flag and the time and no design content. Your other devices need it to learn that the design is gone, so we do not
remove it earlier. Deleting the account removes it.

**Sign out other phones first.** If another phone is still signed in to the same account, sign it out before you delete. A phone
that is still signed in can keep a valid sign-in for up to an hour after the deletion and may upload the design it shows during
that time. If that happens, write to **[OWNER: contact email]** and we remove it.

**What stays.** Nothing about you stays in our cloud after deletion. Google may keep backup copies of its databases for a
limited time, on its own schedule. Designs you shared with other people as links or QR codes are theirs.

**Warning about the signing key.** If you delete the backup and later lose your phone, the watch face already installed on your
watch can no longer be updated from a new phone: the watch only accepts updates signed with the original key. Designs and the key
on your phone stay when you delete your account, so deleting the account on a phone you still have does not cause this.
