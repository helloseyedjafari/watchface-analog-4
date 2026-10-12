package com.seyedjafari.analogplus.phone.accounts

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.seyedjafari.analogplus.phone.PHONE_PORTRAIT
import com.seyedjafari.analogplus.phone.PinnedWindow
import com.seyedjafari.analogplus.phone.SHELL_WINDOW_TAG
import com.seyedjafari.analogplus.phone.WindowDp
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.account.LocalNowMs
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/** The cloud with a gate in front of the backup write, so a test can look at the screen while an upload is under way. */
internal class GatedRemote(val inner: FakeRemoteStore) : RemoteStore by inner {
    @Volatile var putGate: CompletableDeferred<Unit>? = null

    /** While set, every read of the backup waits for it: a restore (and the status refresh after it) is held under way. */
    @Volatile var readGate: CompletableDeferred<Unit>? = null

    /** How often the backup was read (a restore reads it once, and the status refresh after it once more). */
    val reads = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun getBackup(uid: String): RemoteBackup? {
        reads.incrementAndGet()
        readGate?.await()
        return inner.getBackup(uid)
    }

    override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
        putGate?.await()
        return inner.putBackup(uid, sealed, writeId, replacing)
    }
}

/** The time of the screens that are pinned in tests: 1 Jan 2030, 12:00 UTC. */
internal const val PINNED_NOW = 1_893_499_200_000L

/**
 * A real [AccountsController] over the in-memory fakes of the account manager, the cloud and the sync (no Firebase, no emulator
 * needed), with a clock the test sets: the same clock is given to the screen ([LocalNowMs]) and to the attempt limiter.
 * [show] draws the Backup and sync card in a pinned window.
 */
internal class AccountUiFixture(
    val keys: FakeKeySource = FakeKeySource(FakeKeySource.samplePem("A")),
    availability: AccountsAvailability = AccountsAvailability.AVAILABLE,
    initial: AccountState = AccountState.SignedIn("u1", "me@example.com"),
    pendingDeletion: Boolean = false,
) {
    // like the app's view model scope: the controller is called from, and answers on, the main thread
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val accounts = FakeAccountManager(initial)
    val remote = FakeRemoteStore()
    val gated = GatedRemote(remote)
    val sync = FakeCloudSync()
    val clock = AtomicLong(PINNED_NOW)

    /** Milliseconds the screen's clock moves on at every READ (0: it stands still). A real clock runs between two reads; this makes a test see that gap. */
    val readStepMs = AtomicLong(0)
    val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    val marks = DeletionMarks(MemoryKeyValueStore())
    val passphrase = "correct horse battery staple"

    init {
        if (pendingDeletion) check(marks.mark("u1"))
    }

    val controller = AccountsController(
        availability, accounts, sync,
        backupServiceForTest(gated, vault, keys, AttemptLimiter(MemoryKeyValueStore(), clock::get), Dispatchers.Default),
        AccountDeletion(accounts, gated, sync, SyncJournal(MemoryKeyValueStore()), marks, Dispatchers.Default), scope,
    )

    fun close() { scope.coroutineContext[Job]?.cancel() }

    /**
     * Another phone of the account backed up [pem] (the sealing is the real one, with a cheap cost). [refresh] makes this phone
     * read the backup status again, as a sync or a sign-in would; false leaves its line as it was (a change it has not seen yet).
     */
    fun otherPhoneBacksUp(pem: String, expecting: BackupExpectation = BackupExpectation.None, refresh: Boolean = true) {
        val other = backupServiceForTest(remote, vault, FakeKeySource(pem), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined)
        val result = kotlinx.coroutines.runBlocking { other.upload("u1", passphrase, expecting) }
        check(result is UploadResult.Ok) { "the seeded backup was not written: $result" }
        if (refresh) kotlinx.coroutines.runBlocking { controller.refreshBackup() }
    }

    /**
     * Draws the card (in a scrolling column, as in the Account screen) in [window]. [dark] true / false forces the theme, null
     * follows the device; [fontScale] null follows the device; the animations are off so that nothing moves. [scrolls] false
     * is for content that scrolls by itself (the whole Account screen).
     */
    fun show(
        rule: ComposeContentTestRule, window: WindowDp = PHONE_PORTRAIT, fontScale: Float? = null, dark: Boolean? = null,
        scrolls: Boolean = true, content: @Composable () -> Unit = { AccountSection() },
    ) {
        rule.setContent { Provided(window, fontScale, dark, scrolls, content) }
    }

    /** What [show] sets as content, for a test that sets it itself (a state restoration). */
    @Composable
    fun Provided(
        window: WindowDp = PHONE_PORTRAIT, fontScale: Float? = null, dark: Boolean? = null,
        scrolls: Boolean = true, content: @Composable () -> Unit = { AccountSection() },
    ) {
        val base = LocalDensity.current
        val configuration = LocalConfiguration.current
        val themed = if (dark == null) configuration else Configuration(configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        CompositionLocalProvider(
            LocalAnimationScale provides 0f, LocalDensity provides Density(base.density, fontScale ?: base.fontScale),
            LocalConfiguration provides themed, LocalAccounts provides controller, LocalNowMs provides { clock.getAndAdd(readStepMs.get()) },
        ) {
            AnalogTheme {
                PinnedWindow(window) {
                    if (scrolls) {
                        Column(Modifier.fillMaxSize().testTag(SHELL_WINDOW_TAG).verticalScroll(rememberScrollState()).padding(Spacing.lg)) { content() }
                    } else {
                        Box(Modifier.fillMaxSize().testTag(SHELL_WINDOW_TAG)) { content() }
                    }
                }
            }
        }
    }
}

/** The font scale a text node was laid out with: what the user sees, whichever window the text is in. */
internal fun SemanticsNodeInteraction.fontScaleOfText(): Float {
    val results = mutableListOf<TextLayoutResult>()
    val action = fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
    check(action != null && action(results) && results.isNotEmpty()) { "the node has no text layout" }
    return results.first().layoutInput.density.fontScale
}

/** The colour a text node was drawn in (the label of a button on a coloured container, say). */
internal fun SemanticsNodeInteraction.colourOfText(): androidx.compose.ui.graphics.Color {
    val results = mutableListOf<TextLayoutResult>()
    val action = fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
    check(action != null && action(results) && results.isNotEmpty()) { "the node has no text layout" }
    return results.first().layoutInput.style.color
}

/** Top of the keyboard's window on the screen, or null when the device shows none (a hardware keyboard setting). */
internal fun keyboardTopOnScreen(): Int? = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
    .also { ua -> ua.serviceInfo = ua.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS } }
    .windows
    .firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    ?.let { val r = android.graphics.Rect(); it.getBoundsInScreen(r); r.top }

/**
 * Waits until the on-screen keyboard has come up and settled. A dialog shrinks by the keyboard's inset frame by frame while the
 * keyboard slides in, and Compose's idling does not wait for that animation, so a test that scrolls first races it. Returns
 * after at most [timeoutMs] also when no keyboard shows (nothing to wait for then).
 */
internal fun ComposeContentTestRule.waitForTheKeyboardToSettle(timeoutMs: Long = 5_000) {
    val end = System.currentTimeMillis() + timeoutMs
    while (keyboardTopOnScreen() == null && System.currentTimeMillis() < end) Thread.sleep(50)
    var last = -1
    var steady = 0
    while (steady < 4 && System.currentTimeMillis() < end) {
        val top = keyboardTopOnScreen() ?: -1
        steady = if (top == last) steady + 1 else 0
        last = top
        Thread.sleep(100)
    }
    waitForIdle()
}
