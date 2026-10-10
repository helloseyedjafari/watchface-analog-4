package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.rememberAnimationScale
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * "Remove animations" switched while the app runs: [rememberAnimationScale] follows the system setting (a real write to
 * `animator_duration_scale` through the shell identity) without the screen being recomposed from scratch, and the test override
 * [LocalAnimationScale] still wins over it. The rule puts the setting back as it found it.
 */
@RunWith(AndroidJUnit4::class)
class AnimationScaleLiveTest {
    private val compose = createComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(AnimatorScaleRule("1")).around(compose)

    private fun setSystemScale(value: String) { shellCommand("settings put global animator_duration_scale $value") }

    private fun showScale(override: Float? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAnimationScale provides override) {
                Column {
                    Text("scale=${rememberAnimationScale()}", Modifier.testTag("scale"))
                }
            }
        }
    }

    private fun waitForScaleText(expected: String) {
        compose.waitForTextOf("scale", expected)
    }

    @Test fun theScaleFollowsTheSettingWhileTheScreenIsOnShow() {
        showScale()
        waitForScaleText("scale=1.0")
        setSystemScale("0")
        waitForScaleText("scale=0.0")
        setSystemScale("1.5")
        waitForScaleText("scale=1.5")
    }

    @Test fun theTestOverrideStillWinsOverTheSystemSetting() {
        showScale(override = 2f)
        waitForScaleText("scale=2.0")
        setSystemScale("0")
        compose.waitForIdle()
        shellCommand("settings get global animator_duration_scale")      // give the observer every chance to (wrongly) fire
        compose.waitForIdle()
        waitForScaleText("scale=2.0")
    }
}
