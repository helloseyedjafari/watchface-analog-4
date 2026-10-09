package com.seyedjafari.analogplus.phone.ui.nav

import androidx.annotation.DrawableRes
import com.seyedjafari.analogplus.phone.R
import androidx.annotation.StringRes

/**
 * The four top-level destinations. [labelRes] is the displayed (localisable) name; [label] is its English default, kept for
 * code that must not need Android resources (tags, JVM tests); an instrumented test keeps the two in step. [iconRes] is an outlined Material Symbols vector (`res/drawable/ic_nav_*.xml`), tinted by the nav bar. */
enum class Destination(val label: String, @StringRes val labelRes: Int, val tag: String, @DrawableRes val iconRes: Int) {
    DESIGN("Design", R.string.nav_design, "nav_design", R.drawable.ic_nav_design),
    LIBRARY("Library", R.string.nav_library, "nav_library", R.drawable.ic_nav_library),
    SEND("Send", R.string.nav_send, "nav_send", R.drawable.ic_nav_send),
    ACCOUNT("Account", R.string.nav_account, "nav_account", R.drawable.ic_nav_account),
}

/** Where Back goes from here; null means Back leaves the app. */
fun Destination.backTarget(): Destination? = if (this == Destination.DESIGN) null else Destination.DESIGN
