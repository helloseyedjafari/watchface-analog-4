package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingAnimation
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Every onboarding animation, in the light and the dark scheme, is drawn only in theme colours at a still frame (reduced
 * motion, so the picture is deterministic): no loud placeholder colour of the generated files remains, and each brand colour
 * group that is on show (accent = primary, accent2 = tertiary, accent3 = secondary, ink = onSurface, track = outline, onaccent
 * = onPrimary) shows up in its theme colour, while the groups that are not on show do not.
 *
 * The four files are looked at on their last frame, where every group the file contains is on show, except in the welcome
 * animation: it morphs through four colour schemes (Classic, Midnight, Neon, Minimal), each its own copy of the face drawn
 * with other roles, so it is looked at on its last frame (Classic, as at the start of the loop) and in the middle of each of
 * the other three schemes, with the roles each uses. The background is a green that is none of the colours, so a colour can
 * never be "found" in the backdrop.
 */
@RunWith(Parameterized::class)
class OnboardingBrandColoursTest(
    private val name: String, @RawRes private val resId: Int, private val dark: Boolean,
    private val progress: Float, private val onShow: List<String>?,
) {
    @get:Rule val rule = createComposeRule()

    private val backdrop = Color(0xFF2E7D32)
    private val placeholders = listOf(
        0xFFFF00FF.toInt(), 0xFFFFE600.toInt(), 0xFF00E6E6.toInt(), 0xFFFF8000.toInt(),     // accent, ink, track, onaccent
        0xFF80FF00.toInt(), 0xFF0080FF.toInt(),                                             // accent2, accent3
    )

    /**
     * The brand groups the file has (by their `nm` in the JSON), each with the highest opacity any of its fills or strokes
     * has (1f = opaque). A translucent group never shows its exact theme colour: it is blended with what lies under it.
     */
    private fun groupsInFile(): Map<String, Float> {
        val json = InstrumentationRegistry.getInstrumentation().targetContext.resources.openRawResource(resId).bufferedReader().use { it.readText() }
        val found = HashMap<String, Float>()
        fun opacityOf(o: JSONObject?): Float {
            val k = o?.opt("k") ?: return 1f
            val v = if (k is JSONArray) (0 until k.length()).maxOf { i -> (k.getJSONObject(i).optJSONArray("s")?.optDouble(0) ?: 0.0) } else (k as Number).toDouble()
            return (v / 100.0).toFloat()
        }
        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val nm = node.optString("nm")
                    if (node.optString("ty") == "gr" && nm in GROUPS) {
                        val paints = node.getJSONArray("it").let { items -> (0 until items.length()).map { items.getJSONObject(it) } }
                            .filter { it.optString("ty") == "fl" || it.optString("ty") == "st" }
                        found[nm] = maxOf(found[nm] ?: 0f, paints.maxOf { opacityOf(it.optJSONObject("o")) })
                    }
                    node.keys().forEach { walk(node.get(it)) }
                }
                is JSONArray -> (0 until node.length()).forEach { walk(node.get(it)) }
            }
        }
        walk(JSONObject(json))
        return found
    }

    private fun blend(under: Int, over: Int, alpha: Float): Int {
        val channels = listOf(16, 8, 0).map { shift ->
            val u = (under shr shift) and 0xFF
            val o = (over shr shift) and 0xFF
            Math.round(u + (o - u) * alpha) shl shift
        }
        return (0xFF shl 24) or channels.sum()
    }

    private fun capture(): Bitmap = rule.onNodeWithTag("box").captureToImage().asAndroidBitmap()

    private fun near(pixel: Int, argb: Int, tol: Int = 12): Boolean =
        listOf(16, 8, 0).all { shift -> Math.abs(((pixel shr shift) and 0xFF) - ((argb shr shift) and 0xFF)) <= tol }

    private fun count(b: Bitmap, argb: Int, tolerance: Int = 12): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { near(it, argb, tolerance) }
    }

    @Test fun onlyThemeColoursAreDrawn() {
        val expected = HashMap<String, Int>()
        rule.setContent {
            AnalogTheme(darkTheme = dark) {
                val scheme = MaterialTheme.colorScheme
                expected["accent"] = scheme.primary.toArgb()
                expected["ink"] = scheme.onSurface.toArgb()
                expected["track"] = scheme.outline.toArgb()
                expected["onaccent"] = scheme.onPrimary.toArgb()
                expected["accent2"] = scheme.tertiary.toArgb()
                expected["accent3"] = scheme.secondary.toArgb()
                Box(Modifier.size(240.dp).testTag("box").background(backdrop)) {
                    OnboardingAnimation(resId, Tags.onboardingAnimation(name), Modifier.fillMaxSize(), animatorScale = 0f, stillProgress = progress)
                }
            }
        }
        val inFile = groupsInFile()
        assertTrue("$name: the JSON has an accent group (the parse of the file names is right)", "accent" in inFile)
        val groups = if (onShow == null) inFile else inFile.filterKeys { it in onShow }
        assertEquals("$name: every group named in the test is in the file", onShow?.toSet() ?: inFile.keys, groups.keys)
        // the file is parsed off the main thread: wait until the first role that is on show has been drawn
        val first = onShow?.first() ?: "accent"
        rule.waitUntil(10_000) { count(capture(), expected.getValue(first)) > MIN_PIXELS }
        val image = capture()
        val back = backdrop.toArgb()
        // what a group looks like: its theme colour, blended by its opacity over the backdrop or over another translucent group
        val counts = groups.mapValues { (group, opacity) ->
            val colour = expected.getValue(group)
            val unders = listOf(back) + groups.filterKeys { it != group }.map { (other, o) -> blend(back, expected.getValue(other), o) }
            val looks = if (opacity >= 1f) listOf(colour) else unders.map { blend(it, colour, opacity) }
            val px = IntArray(image.width * image.height)
            image.getPixels(px, 0, image.width, 0, 0, image.width, image.height)
            px.count { p -> looks.any { near(p, it, TOLERANCE) } }
        }
        groups.keys.forEach { group ->
            assertTrue("$name (${if (dark) "dark" else "light"}) at $progress: $group is drawn in its theme colour, counts $counts", counts.getValue(group) > MIN_PIXELS)
        }
        // the roles of the other colour schemes are not on show: this is what makes it a morph and not one fixed set of colours
        (inFile.keys - groups.keys).forEach { group ->
            assertTrue("$name at $progress: $group is not on show, yet ${count(image, expected.getValue(group), EXACT)} pixels have its colour", count(image, expected.getValue(group), EXACT) < MIN_PIXELS)
        }
        placeholders.forEach { placeholder ->
            assertEquals("$name: a placeholder colour is still visible: ${Integer.toHexString(placeholder)}", 0, count(image, placeholder))
        }
    }

    companion object {
        /** The smallest brand shape (the hub of a hand) still draws more than this many pixels at 240 dp. */
        private const val MIN_PIXELS = 20
        private const val TOLERANCE = 14
        /** For "this colour is not drawn": the colour itself, not what an anti-aliased edge of two other colours happens to come near. */
        private const val EXACT = 3
        private val GROUPS = listOf("accent", "accent2", "accent3", "ink", "track", "onaccent")

        /**
         * The welcome loop is 240 frames in four quarters, one colour scheme each, with a 20 frame cross-fade at the end of a quarter
         * (tools/gen_onboarding_lottie.py: SCHEMES). The middle of a quarter is a pure scheme: frame 20 (Classic), 80 (Midnight), 140
         * (Neon), 200 (Minimal) of 240. The last frame is Classic again (the loop seam).
         */
        private val WELCOME_SCHEMES = listOf(
            "classic" to (20 / 240f to listOf("accent", "ink", "track", "onaccent")),
            "midnight" to (80 / 240f to listOf("accent3", "accent", "track", "onaccent")),
            "neon" to (140 / 240f to listOf("accent2", "accent", "track", "onaccent")),
            "minimal" to (200 / 240f to listOf("ink", "track", "onaccent")),
        )

        @JvmStatic @Parameterized.Parameters(name = "{0} dark={2} at {3}")
        fun cases(): List<Array<Any?>> {
            val lastFrame = listOf(
                "welcome" to R.raw.onboarding_welcome, "searching" to R.raw.onboarding_searching,
                "found" to R.raw.onboarding_found, "permission" to R.raw.onboarding_permission,
            ).flatMap { (n, id) ->
                // the last frame of welcome is Classic: only the roles of that scheme are on show
                val onShow = if (n == "welcome") listOf("accent", "ink", "track", "onaccent") else null
                listOf(false, true).map { arrayOf<Any?>(n, id, it, 1f, onShow) }
            }
            val morph = WELCOME_SCHEMES.flatMap { (_, at) ->
                listOf(false, true).map { arrayOf<Any?>("welcome", R.raw.onboarding_welcome, it, at.first, at.second) }
            }
            return lastFrame + morph
        }
    }
}
