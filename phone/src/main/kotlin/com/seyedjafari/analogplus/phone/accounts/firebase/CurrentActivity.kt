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
