package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.R

/**
 * Every onboarding string, by the id of its resource in `res/values/strings.xml` (keys `onboarding_<page>_<purpose>`).
 * No English lives in code: the pure helpers behind a page (`pairStep`, `pairRows`, later `primaryLabel`, ...) return these
 * ids, a composable resolves them with `stringResource(id)`, and a JVM test says what the user reads with
 * `StringsXml.text(id)` (test sources). A new page adds its ids here and its strings to strings.xml.
 */
object OnboardingText {
    // pairing page
    @StringRes val PAIR_TITLE = R.string.onboarding_pair_title
    @StringRes val PAIR_BODY = R.string.onboarding_pair_body
    @StringRes val PAIR_WATCH_NOTE = R.string.onboarding_pair_watch_note
    @StringRes val PAIR_NO_WATCH_YET = R.string.onboarding_pair_no_watch_yet
    @StringRes val PAIR_ROW_WATCH = R.string.onboarding_pair_row_watch
    @StringRes val PAIR_ROW_APP = R.string.onboarding_pair_row_app
    @StringRes val PAIR_ROW_APP_WAITING = R.string.onboarding_pair_row_app_waiting
    @StringRes val PAIR_CHECK_AGAIN = R.string.onboarding_pair_check_again
    @StringRes val PAIR_ADVICE_READY = R.string.onboarding_pair_advice_ready
    @StringRes val PAIR_ADVICE_OPEN_WEARABLE_APP = R.string.onboarding_pair_advice_open_wearable_app
    @StringRes val PAIR_ADVICE_INSTALL_WATCH_APP = R.string.onboarding_pair_advice_install_watch_app
    @StringRes val PAIR_ADVICE_COULD_NOT_CHECK = R.string.onboarding_pair_advice_could_not_check

    // template page
    @StringRes val TEMPLATE_TITLE = R.string.onboarding_template_title
    @StringRes val TEMPLATE_BODY = R.string.onboarding_template_body
    @StringRes val TEMPLATE_SELECTED = R.string.onboarding_template_selected

    // welcome page
    @StringRes val WELCOME_TITLE = R.string.onboarding_welcome_title
    @StringRes val WELCOME_BODY = R.string.onboarding_welcome_body

    // permission page
    @StringRes val PERMISSION_TITLE = R.string.onboarding_permission_title
    /** The three lines of the permission page, in reading order (each a string resource id). */
    val PERMISSION_STEPS: List<Int> = listOf(
        R.string.onboarding_permission_step_allow,
        R.string.onboarding_permission_step_deny,
        R.string.onboarding_permission_step_later,
    )

    // backup page
    @StringRes val BACKUP_TITLE = R.string.onboarding_backup_title
    @StringRes val BACKUP_PLACEHOLDER_BODY = R.string.onboarding_backup_placeholder_body
    @StringRes val BACKUP_NOT_NOW = R.string.onboarding_backup_not_now

    // navigation
    @StringRes val NAV_GET_STARTED = R.string.onboarding_nav_get_started
    @StringRes val NAV_CONTINUE = R.string.onboarding_nav_continue
    @StringRes val NAV_FINISH = R.string.onboarding_nav_finish
    @StringRes val NAV_STEP_OF = R.string.onboarding_nav_step_of

    // the screen around the pages
    @StringRes val SCREEN_SKIP = R.string.onboarding_screen_skip
    @StringRes val SCREEN_BACK = R.string.onboarding_screen_back
}

/** The wording of the primary button on [page]; null when the page brings its own buttons (the backup page). */
@StringRes
fun OnboardingText.primaryLabel(page: OnboardingPage, isLast: Boolean): Int? = when (page) {
    OnboardingPage.WELCOME -> NAV_GET_STARTED
    OnboardingPage.PAIR, OnboardingPage.PERMISSION -> NAV_CONTINUE
    OnboardingPage.TEMPLATE -> if (isLast) NAV_FINISH else NAV_CONTINUE
    OnboardingPage.BACKUP -> null          // the page has its own buttons ("Sign in", "Not now")
}

/** "Step 2 of 4": the format [res] with its two arguments (`stringResource(step.res, *step.args)`). [index] is zero-based. */
class StepText(@StringRes val res: Int, val number: Int, val count: Int) {
    val args: Array<Any> get() = arrayOf(number, count)
}

fun OnboardingText.stepOf(index: Int, count: Int): StepText = StepText(NAV_STEP_OF, index + 1, count)
