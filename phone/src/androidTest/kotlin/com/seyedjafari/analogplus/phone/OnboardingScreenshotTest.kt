package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountUiFixture
import com.seyedjafari.analogplus.phone.accounts.FakeKeySource
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.junit.runners.Parameterized

/**
 * Renders every onboarding page through the real shell gate in a pinned window (360x800 or 800x360 dp, so the result does not
 * depend on the emulator's size or orientation), in light and dark, at font scale 1 and 2, and writes the pictures to the app's
 * external files directory `onboarding-screens/` as `onboarding-<page>-<theme>-<portrait|landscape>[-2x].png`, one pixel per dp.
 * `scripts/pull_onboarding_screens.sh` runs this class and pulls the files into `docs/poc/screens/` (the app is uninstalled when a
 * Gradle instrumented run ends, taking its files with it, so the script drives the run itself).
 *
 * Animations are off (animator scale 0 is provided), so each animation shows its last frame and the pictures are the same every
 * time. The pair page shows a ready watch, and the backup page is on (accounts) so that all five pages are covered: the real
 * page, in four steps (signed out, no backup yet, a backup of another key to restore, done), each as `onboarding-backup[-<step>]-...`.
 * 8 pictures per shot, 64 in all.
 *
 * It takes about a minute, so the normal suite skips it: it runs only with the runner argument `screenshots=true` (which the
 * script passes with `-e screenshots true`; with Gradle `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`). When it
 * runs, every picture must be rendered and not blank.
 */
@RunWith(Parameterized::class)
class OnboardingScreenshotTest(
    private val shot: Shot, private val window: WindowDp, private val landscape: Boolean,
    private val fontScale: Float, private val dark: Boolean,
) {
    private val page: OnboardingPage get() = shot.page
    private val rule = createComposeRule()

    /** Each case is skipped (reported as skipped, without starting an activity) unless the run was asked for the pictures. */
    @get:Rule val chain: RuleChain = RuleChain.outerRule(onlyWhenAsked).around(rule)

    /** The art is on screen: the animation node of the page, where the page has one in this window. */
    private fun animationTagsOnPage(): List<String> = when (page) {
        OnboardingPage.WELCOME -> listOf("welcome")
        OnboardingPage.PAIR -> listOf("found")
        OnboardingPage.PERMISSION -> listOf("permission")
        OnboardingPage.TEMPLATE, OnboardingPage.BACKUP -> emptyList()
    }.map(Tags::onboardingAnimation)

    private fun fileName(): String {
        val theme = if (dark) "dark" else "light"
        val orientation = if (landscape) "landscape" else "portrait"
        return "onboarding-${shot.fileStem}-$theme-$orientation${if (fontScale == 2f) "-2x" else ""}.png"
    }

    private fun capture(): Bitmap = rule.onNodeWithTag(SHELL_WINDOW_TAG).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    @Test fun render() {
        val fixture = ShellFixture(accounts = true, startAt = OnboardingState.InProgress(page))
        // the backup page is the real one over a controller on the in-memory cloud, in the state the shot names
        val accounts = if (page == OnboardingPage.BACKUP) shot.accounts() else null
        try {
            render(fixture, accounts)
        } finally {
            accounts?.close()
        }
    }

    private fun render(fixture: ShellFixture, accounts: AccountUiFixture?) {
        fixture.show(rule, window = window, fontScale = fontScale, dark = dark, animationScale = 0f, accountsController = accounts?.controller)
        rule.waitForTag(Tags.onboardingPage(page))
        shot.readyTag?.let { rule.waitForTag(it) }
        if (page == OnboardingPage.PAIR) rule.waitForTag(Tags.PAIR_ADVICE)
        // the animation of the page is there (not dropped: with large text in a short wide window it is, see PageLayout) before anything is captured
        if (!(landscape && fontScale >= 1.5f)) animationTagsOnPage().forEach { rule.waitForTag(it) }
        // the animation file is parsed and the template previews are drawn off the main thread: wait until three pictures in a row are the same
        var last = capture()
        var same = 0
        rule.waitUntil(15_000) {
            Thread.sleep(150)
            val now = capture()
            same = if (now.sameAs(last)) same + 1 else 0
            last = now
            same >= 2
        }
        val picture = Bitmap.createScaledBitmap(last, window.width, window.height, true)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, fileName())
        file.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("${file.name} is a real picture", file.length() > 1_000)
        val pixels = IntArray(picture.width * picture.height).also { picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height) }
        assertTrue("${file.name} is not one flat colour", pixels.distinct().size > 20)
    }

    /** What a picture shows: a page, and for the backup page the step of it. */
    enum class Shot(val page: OnboardingPage, val fileStem: String, val readyTag: String? = null) {
        WELCOME(OnboardingPage.WELCOME, "welcome"),
        PAIR(OnboardingPage.PAIR, "pair"),
        PERMISSION(OnboardingPage.PERMISSION, "permission"),
        TEMPLATE(OnboardingPage.TEMPLATE, "template"),
        BACKUP(OnboardingPage.BACKUP, "backup", AccountTags.PAGE_SIGN_IN),
        BACKUP_SET_PASSPHRASE(OnboardingPage.BACKUP, "backup-set-passphrase", AccountTags.PAGE_BACK_UP),
        BACKUP_RESTORE(OnboardingPage.BACKUP, "backup-restore", AccountTags.PAGE_RESTORE),
        BACKUP_DONE(OnboardingPage.BACKUP, "backup-done", AccountTags.PAGE_DONE);

        /** The accounts behind the backup page for this picture: signed out, signed in with no backup, with a backup of another key, with one of this phone's. */
        internal fun accounts(): AccountUiFixture {
            val pemA = FakeKeySource.samplePem("A")
            val signedIn = AccountState.SignedIn("u1", "me@example.com")
            return when (this) {
                BACKUP -> AccountUiFixture(initial = AccountState.SignedOut)
                BACKUP_SET_PASSPHRASE -> AccountUiFixture(FakeKeySource(pemA), initial = signedIn)
                BACKUP_RESTORE -> AccountUiFixture(FakeKeySource(null), initial = signedIn).also { it.otherPhoneBacksUp(pemA) }
                BACKUP_DONE -> AccountUiFixture(FakeKeySource(pemA), initial = signedIn).also { it.otherPhoneBacksUp(pemA) }
                else -> error("$this has no accounts")
            }
        }
    }

    companion object {
        const val DIR = "onboarding-screens"

        /** Skips a case unless the run was asked for the pictures (see the class comment). */
        private val onlyWhenAsked = TestRule { base, _ ->
            object : Statement() {
                override fun evaluate() {
                    Assume.assumeTrue("pass the runner argument screenshots=true to render the onboarding pictures",
                        InstrumentationRegistry.getArguments().getString("screenshots") == "true")
                    base.evaluate()
                }
            }
        }

        @JvmStatic @Parameterized.Parameters(name = "{0} {1} font {3} dark={4}")
        fun cases(): List<Array<Any>> = buildList {
            for (page in Shot.entries) for (landscape in listOf(false, true)) for (font in listOf(1f, 2f)) for (dark in listOf(false, true)) {
                add(arrayOf(page, if (landscape) PHONE_LANDSCAPE else PHONE_PORTRAIT, landscape, font, dark))
            }
        }
    }
}
