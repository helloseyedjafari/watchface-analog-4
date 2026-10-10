package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.StringRes
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
}
