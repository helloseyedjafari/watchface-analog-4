package com.seyedjafari.analogplus.phone.accounts

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.account.AccountMessage
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.CloudCallbacks
import com.seyedjafari.analogplus.phone.ui.account.CloudDialog
import com.seyedjafari.analogplus.phone.ui.account.CloudSectionContent
import com.seyedjafari.analogplus.phone.ui.account.ConfirmDialog
import com.seyedjafari.analogplus.phone.ui.account.ConfirmKind
import com.seyedjafari.analogplus.phone.ui.account.DeleteAccountDialog
import com.seyedjafari.analogplus.phone.ui.account.EnterPassphraseDialog
import com.seyedjafari.analogplus.phone.ui.account.ForgotPassphraseDialog
import com.seyedjafari.analogplus.phone.ui.account.LocalNowMs
import com.seyedjafari.analogplus.phone.ui.account.OfferDialog
import com.seyedjafari.analogplus.phone.ui.account.SetPassphraseDialog
import com.seyedjafari.analogplus.phone.ui.account.reAskGoneText
import com.seyedjafari.analogplus.phone.ui.account.reAskSameText
import com.seyedjafari.analogplus.phone.ui.account.replaceBackupText
import com.seyedjafari.analogplus.phone.ui.account.replaceKeyText
import com.seyedjafari.analogplus.phone.ui.account.restoreErrorText
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.model.Statement

/**
 * Pictures of the Backup and sync card in every state and of every dialog, taken of the real screen (the display, so the
 * dialogs and the keyboard are in them) in light and dark, upright and sideways, at font scale 1 and 2. The files go to the app's
 * external files directory `account-screens/` as `account-<scene>-<light|dark>-<portrait|landscape>[-2x].png`
 * at two fifths of the screen size (about 1 pixel per dp), system bars cut off. `scripts/pull_account_screens.sh` runs this class and pulls them into
 * `docs/screenshots/accounts/`.
 *
 * It takes a few minutes, so the normal suite skips it: it runs only with the runner argument `screenshots=true`.
 */
@RunWith(Parameterized::class)
class AccountScreenshotTest(private val variant: Variant) {
    data class Variant(val dark: Boolean, val landscape: Boolean, val fontScale: Float) {
        val suffix get() = "${if (dark) "dark" else "light"}-${if (landscape) "landscape" else "portrait"}${if (fontScale == 2f) "-2x" else ""}"
        override fun toString() = suffix
    }

    private val rule = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(onlyWhenAsked).around(rule)

    /** One picture: the [content], an optional [act] on the screen before it is taken (typing into a field). */
    private class Scene(val name: String, val act: (androidx.compose.ui.test.junit4.ComposeTestRule) -> Unit = {}, val content: @Composable () -> Unit)

    private val now = 1_893_499_200_000L
    private val cloudFp = "0123456789abcdef"
    private val phoneFp = "fedcba9876543210"
    private val signedIn = AccountState.SignedIn("u1", "me@example.com")

    private fun ui(
        backup: BackupStatus, account: AccountState = signedIn, sync: SyncStatus = SyncStatus.Idle(now - 300_000), busy: Busy = Busy.NONE,
        notice: Notice? = null, deletion: DeletionState = DeletionState.NONE, availability: AccountsAvailability = AccountsAvailability.AVAILABLE,
    ) = AccountsUi(availability, account, sync, backup, busy, notice, null, deletion)

    @Composable
    private fun Card(ui: AccountsUi, note: AccountMessage? = null) = CloudSectionContent(ui, now, note, CloudCallbacks.None)

    private fun backedUp(match: KeyMatch) = BackupStatus.BackedUp(cloudFp, now - 3_600_000, match, "w1")

    private val dialogCard: @Composable () -> Unit = { Card(ui(backedUp(KeyMatch.DIFFERENT))) }

    private val scenes: List<Scene> = listOf(
        Scene("card-not-configured") { Card(ui(BackupStatus.SignedOut, AccountState.SignedOut, SyncStatus.Off, availability = AccountsAvailability.NOT_CONFIGURED)) },
        Scene("card-signed-out") { Card(ui(BackupStatus.SignedOut, AccountState.SignedOut, SyncStatus.Off)) },
        Scene("card-signed-out-failed") { Card(ui(BackupStatus.SignedOut, AccountState.Failed(AccountFailure.NO_GOOGLE_ACCOUNT, "NoCredentialException: no credentials available"), SyncStatus.Off)) },
        Scene("card-signing-in") { Card(ui(BackupStatus.SignedOut, AccountState.SigningIn, SyncStatus.Off)) },
        Scene("card-not-backed-up") { Card(ui(BackupStatus.NotBackedUp)) },
        Scene("card-same-key") { Card(ui(backedUp(KeyMatch.SAME), notice = Notice.KeyBackedUp(cloudFp))) },
        Scene("card-different-key") { Card(ui(backedUp(KeyMatch.DIFFERENT))) },
        Scene("card-no-local-key") { Card(ui(backedUp(KeyMatch.NO_LOCAL_KEY))) },
        Scene("card-damaged-key") { Card(ui(backedUp(KeyMatch.LOCAL_DAMAGED))) },
        Scene("card-offline") { Card(ui(BackupStatus.Unknown(RemoteErrorKind.OFFLINE, "UNAVAILABLE: Unable to resolve host"), sync = SyncStatus.Offline)) },
        Scene("card-busy") { Card(ui(BackupStatus.Checking, busy = Busy.BACKING_UP)) },
        Scene("card-sync-problem") {
            Card(
                ui(
                    backedUp(KeyMatch.SAME), sync = SyncStatus.Error(SyncProblem.QUOTA, detail = "RESOURCE_EXHAUSTED: quota"),
                    notice = Notice.BackupFailed(RemoteErrorKind.OFFLINE, "UNAVAILABLE: Unable to resolve host"),
                ),
            )
        },
        Scene("card-expired-sign-in") { Card(ui(backedUp(KeyMatch.SAME), sync = SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED))) },
        Scene("card-pending-deletion") {
            Card(ui(BackupStatus.NotBackedUp, sync = SyncStatus.Off, deletion = DeletionState.PENDING, notice = Notice.DeleteDataFailed(RemoteErrorKind.OFFLINE, "UNAVAILABLE: no route")))
        },
        Scene("dlg-set-passphrase") { dialogCard(); SetPassphraseDialog(busy = false, onSubmit = {}, onDismiss = {}) },
        Scene(
            "dlg-set-passphrase-typing",
            act = {
                it.onNodeWithTag(AccountTags.PASSPHRASE).performClick()
                it.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("aaaaaaaaaaaaaaaa")
                it.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput("aaaaaaaaaaaaaaab")
            },
        ) { dialogCard(); SetPassphraseDialog(busy = false, onSubmit = {}, onDismiss = {}) },
        Scene("dlg-enter-wrong") {
            dialogCard()
            EnterPassphraseDialog(
                CloudDialog.Enter(RestorePlan.InstallNew(cloudFp), restoreErrorText(RestoreResult.WrongPassphrase(0)), 0, 1),
                busy = false, restoreWaitMs = { 0 }, onSubmit = {}, onForgot = {}, onDismiss = {},
            )
        },
        Scene("dlg-enter-waiting") {
            dialogCard()
            EnterPassphraseDialog(
                CloudDialog.Enter(RestorePlan.InstallNew(cloudFp), restoreErrorText(RestoreResult.WrongPassphrase(30_000)), 30_000, 3),
                busy = false, restoreWaitMs = { 0 }, onSubmit = {}, onForgot = {}, onDismiss = {},
            )
        },
        Scene("dlg-replace-key") {
            dialogCard()
            ConfirmDialog(CloudDialog.Confirm(ConfirmKind.REPLACE_KEY, replaceKeyText(RestorePlan.ReplaceLocal(phoneFp, cloudFp))!!, CloudDialog.None), {}, {})
        },
        Scene("dlg-replace-backup") {
            dialogCard()
            ConfirmDialog(CloudDialog.Confirm(ConfirmKind.REPLACE_BACKUP, replaceBackupText(cloudFp, phoneFp), CloudDialog.None), {}, {})
        },
        Scene("dlg-reask-gone") { dialogCard(); ConfirmDialog(CloudDialog.Confirm(ConfirmKind.REPLACE_BACKUP, reAskGoneText(), CloudDialog.None, destructive = false), {}, {}) },
        Scene("dlg-reask-same") { dialogCard(); ConfirmDialog(CloudDialog.Confirm(ConfirmKind.REPLACE_BACKUP, reAskSameText(cloudFp), CloudDialog.None), {}, {}) },
        Scene("dlg-forgot-new") { dialogCard(); ForgotPassphraseDialog(CloudDialog.Forgot(RestorePlan.AlreadyHaveThisKey(cloudFp), "w1"), busy = false, onConfirm = {}, onBack = {}) },
        Scene("dlg-forgot-replace-backup") { dialogCard(); ForgotPassphraseDialog(CloudDialog.Forgot(RestorePlan.ReplaceLocal(phoneFp, cloudFp), "w1"), busy = false, onConfirm = {}, onBack = {}) },
        Scene("dlg-forgot-reset") { dialogCard(); ForgotPassphraseDialog(CloudDialog.Forgot(RestorePlan.InstallNew(cloudFp), "w1"), busy = false, onConfirm = {}, onBack = {}) },
        Scene("dlg-forgot-damaged") { dialogCard(); ForgotPassphraseDialog(CloudDialog.Forgot(RestorePlan.ReplaceDamaged(cloudFp), "w1"), busy = false, onConfirm = {}, onBack = {}) },
        Scene("dlg-delete") { dialogCard(); DeleteAccountDialog(busy = false, onConfirm = {}, onDismiss = {}) },
        Scene("dlg-offer") { dialogCard(); OfferDialog(CurrentOffer("Pixel 8", DesignSettings(name = "Night"), now), {}, {}) },
    )

    /** The room the status bar and the navigation bar take, in pixels (cut off, so the clock is not in a picture). */
    private fun systemBars(scenario: ActivityScenario<ComponentActivity>): androidx.core.graphics.Insets {
        var bars = androidx.core.graphics.Insets.NONE
        scenario.onActivity { activity ->
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
            if (insets != null) bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        return bars
    }

    private fun screenshot(): Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()

    @Test fun render() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            if (variant.landscape) {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                rule.waitUntil(15_000) {
                    var landscape = false
                    scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    landscape
                }
            }
            for (scene in scenes) {
                scenario.onActivity { activity ->
                    activity.setContent {
                        val base = LocalDensity.current
                        val configuration = LocalConfiguration.current
                        val themed = Configuration(configuration).apply {
                            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (variant.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                        }
                        CompositionLocalProvider(
                            LocalAnimationScale provides 0f, LocalDensity provides Density(base.density, variant.fontScale),
                            LocalConfiguration provides themed, LocalNowMs provides { now },
                        ) {
                            AnalogTheme {
                                Column(
                                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.systemBars)
                                        .verticalScroll(rememberScrollState()).padding(Spacing.lg),
                                ) { scene.content() }
                            }
                        }
                    }
                }
                rule.waitForIdle()
                scene.act(rule)
                rule.waitForIdle()
                // the first frames of a dialog window and of the keyboard arrive late: wait until three pictures in a row are the same
                var last = screenshot()
                var same = 0
                rule.waitUntil(15_000) {
                    Thread.sleep(300)
                    val picture = screenshot()
                    same = if (picture.sameAs(last)) same + 1 else 0
                    last = picture
                    same >= 2
                }
                val bars = systemBars(scenario)
                val cropped = Bitmap.createBitmap(last, bars.left, bars.top, last.width - bars.left - bars.right, last.height - bars.top - bars.bottom)
                val scaled = Bitmap.createScaledBitmap(cropped, cropped.width * 2 / 5, cropped.height * 2 / 5, true)
                val file = File(dir, "account-${scene.name}-${variant.suffix}.png")
                file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                assertTrue("${file.name} is a real picture", file.length() > 1_000)
            }
        }
    }

    companion object {
        const val DIR = "account-screens"

        /** Skips a case unless the run was asked for the pictures (see the class comment). */
        private val onlyWhenAsked = TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    Assume.assumeTrue("pass the runner argument screenshots=true to render the account pictures",
                        InstrumentationRegistry.getArguments().getString("screenshots") == "true")
                    base.evaluate()
                }
            }
        }

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun variants() = listOf(
            false, true,
        ).flatMap { dark -> listOf(false, true).flatMap { landscape -> listOf(1f, 2f).map { font -> arrayOf<Any>(Variant(dark, landscape, font)) } } }
    }
}
