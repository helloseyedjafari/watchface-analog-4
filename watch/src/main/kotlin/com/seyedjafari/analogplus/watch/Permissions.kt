package com.seyedjafari.analogplus.watch

import android.content.pm.PackageManager

internal const val PERM_PUSH = "com.google.wear.permission.PUSH_WATCH_FACES"
internal const val PERM_ACTIVE = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"

internal const val PERMISSION_DENIED_NOTE =
    "Permission needed to activate the face. You can also pick Analog Plus in the watch face picker: " +
        "long-press the watch face, swipe to Analog Plus."

/**
 * The permissions that are still not granted. Asks the system instead of reading the request's result arrays: when
 * Android interrupts a request it delivers EMPTY arrays, which would otherwise look like "nothing denied".
 */
internal fun missingPermissions(isGranted: (String) -> Boolean): List<String> =
    listOf(PERM_PUSH, PERM_ACTIVE).filterNot(isGranted)

internal fun isGranted(result: Int) = result == PackageManager.PERMISSION_GRANTED
