package com.seyedjafari.analogplus.phone.ui.nav

import androidx.annotation.DrawableRes
import com.seyedjafari.analogplus.phone.R

/** The four top-level destinations. [iconRes] is an outlined Material Symbols vector (`res/drawable/ic_nav_*.xml`), tinted by the nav bar. */
enum class Destination(val label: String, val tag: String, @DrawableRes val iconRes: Int) {
    DESIGN("Design", "nav_design", R.drawable.ic_nav_design),
    LIBRARY("Library", "nav_library", R.drawable.ic_nav_library),
    SEND("Send", "nav_send", R.drawable.ic_nav_send),
    ACCOUNT("Account", "nav_account", R.drawable.ic_nav_account),
}

/** Where Back goes from here; null means Back leaves the app. */
fun Destination.backTarget(): Destination? = if (this == Destination.DESIGN) null else Destination.DESIGN
