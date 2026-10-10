package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.auth.FirebaseAuth

/**
 * For the instrumented tests that start the real `MainActivity`: signs out whoever was signed in on this device, so that a
 * sign-in left behind by an earlier run (or by the owner) cannot start a real sync against real data while a test drives the
 * app. Does nothing in a build without Firebase. Debug builds only.
 */
object FirebaseTestSession {
    fun signOut(context: Context) {
        if (FirebaseProvider.setUp(context).mode == FirebaseMode.NOT_CONFIGURED) return
        FirebaseAuth.getInstance().signOut()
    }
}
