package com.seyedjafari.analogplus.watch

import android.content.pm.PackageManager

internal const val PERM_PUSH = "com.google.wear.permission.PUSH_WATCH_FACES"
internal const val PERM_ACTIVE = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"

internal const val PERMISSION_DENIED_NOTE =
    "Permission needed to activate the face. You can also pick Analog Plus in the watch face picker: " +
        "long-press the watch face, swipe to Analog Plus."

/** The permissions the user did not grant. A missing result (the request was interrupted) counts as denied. */
internal fun deniedPermissions(permissions: Array<out String>, grantResults: IntArray): List<String> =
    permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
