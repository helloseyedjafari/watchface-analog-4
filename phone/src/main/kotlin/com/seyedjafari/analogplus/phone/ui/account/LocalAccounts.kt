package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.compositionLocalOf
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The controller seen when nothing provides one (previews, tests of a single screen): a build without Firebase, so the
 * screens never crash and offer no sign-in. One instance for the whole process, built on first use: it starts coroutines
 * (it listens to its account manager), so building a new one per read of [LocalAccounts] would leak them.
 */
private object UnavailableAccounts {
    val controller: AccountsController by lazy { AccountsController.unavailable(CoroutineScope(SupervisorJob() + Dispatchers.Default)) }
}

/**
 * The accounts controller for the screens below. `MainActivity` provides the real one (`AppModel.accounts`); without a
 * provider the screens see [UnavailableAccounts].
 */
val LocalAccounts = compositionLocalOf<AccountsController> { UnavailableAccounts.controller }
