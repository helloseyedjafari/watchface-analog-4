package com.seyedjafari.analogplus.phone.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(val label: String, val tag: String) {
    DESIGN("Design", "nav_design"),
    LIBRARY("Library", "nav_library"),
    SEND("Send", "nav_send"),
    ACCOUNT("Account", "nav_account"),
}

/** Where Back goes from here; null means Back leaves the app. */
fun Destination.backTarget(): Destination? = if (this == Destination.DESIGN) null else Destination.DESIGN

fun Destination.icon(): ImageVector = when (this) {
    Destination.DESIGN -> Icons.Filled.Edit
    Destination.LIBRARY -> Icons.Filled.Star
    Destination.SEND -> Icons.AutoMirrored.Filled.Send
    Destination.ACCOUNT -> Icons.Filled.Person
}
