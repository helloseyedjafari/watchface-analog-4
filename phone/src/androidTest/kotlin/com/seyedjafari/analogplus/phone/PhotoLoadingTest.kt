package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.design.BackgroundSection
import com.seyedjafari.analogplus.phone.ui.design.PhotoThumb
import com.seyedjafari.analogplus.phone.ui.library.DesignThumbnail
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec: a skeleton shimmer while the photo (or a thumbnail) loads, replaced by the real thing when it is ready. */
@RunWith(AndroidJUnit4::class)
class PhotoLoadingTest {
    @get:Rule val rule = createComposeRule()

    // every gate is opened after the test, so a read that is still waiting on a background thread cannot outlive a failed test
    private val gates = mutableListOf<CountDownLatch>()
    private fun newGate() = CountDownLatch(1).also { gates += it }

    @After fun openAllGates() {
        gates.forEach { it.countDown() }
    }

    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun jpeg(): ByteArray {
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun bitmap() = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    private val photoDesign = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO))

    @Composable
    private fun host(state: EditorState, readPhoto: (android.content.Context, Uri) -> PhotoRead? = ::readPhotoFromUri) {
        // a picker that answers at once with a made-up Uri, so the read (gated by the test) is the only slow part
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, Uri.parse("content://test/photo") as O)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            AnalogTheme { Column { BackgroundSection(state, readPhoto) } }
        }
    }

    @Test fun theSavedPhotoShowsASkeletonWhileItIsReadAtStartThenTheSection() {
        val state = EditorState(photoDesign, PhotoLoad.LOADING)
        rule.setContent { host(state) }
        rule.onNodeWithTag(Tags.field("background_loading")).assertIsDisplayed()
        state.photoLoaded()
        rule.waitForIdle()
        assertEquals(0, count(Tags.field("background_loading")))
        rule.onNodeWithText("Choose photo").assertIsDisplayed()
    }

    @Test fun theStartupSkeletonIsGoneOnceThePhotoArrives() {
        val state = EditorState(photoDesign, PhotoLoad.LOADING)
        rule.setContent { host(state) }
        rule.onNodeWithTag(Tags.field("background_loading")).assertIsDisplayed()
        state.update { it.copy(background = it.background.copy(photoJpeg = jpeg())) }
        state.photoLoaded()
        rule.waitUntil(5_000) { count(Tags.field("background_thumb")) == 1 }
        assertEquals(0, count(Tags.field("background_loading")))
    }

    @Test fun aPickedPhotoShowsASkeletonWhileItIsReadThenTheThumbnail() {
        val state = EditorState(photoDesign)
        val gate = newGate()
        val bytes = jpeg()
        rule.setContent { host(state, readPhoto = { _, _ -> gate.await(10, TimeUnit.SECONDS); PhotoRead.Ok(bytes) }) }
        assertEquals(0, count(Tags.field("background_reading")))
        rule.onNodeWithTag(Tags.field("background_pick")).performScrollTo().performClick()
        rule.waitUntil(5_000) { count(Tags.field("background_reading")) == 1 }
        assertEquals(0, count(Tags.field("background_thumb")))
        gate.countDown()
        rule.waitUntil(5_000) { count(Tags.field("background_thumb")) == 1 }
        assertEquals(0, count(Tags.field("background_reading")))
        assertArrayEquals(bytes, state.design.background.photoJpeg)
    }

    @Test fun theReadingSkeletonGoesAwayWhenThePhotoCannotBeRead() {
        val state = EditorState(photoDesign)
        val gate = newGate()
        rule.setContent { host(state, readPhoto = { _, _ -> gate.await(10, TimeUnit.SECONDS); null }) }
        rule.onNodeWithTag(Tags.field("background_pick")).performScrollTo().performClick()
        rule.waitUntil(5_000) { count(Tags.field("background_reading")) == 1 }
        gate.countDown()
        rule.waitUntil(5_000) { count(Tags.field("background_message")) == 1 }
        assertEquals(0, count(Tags.field("background_reading")))
    }

    @Test fun theThumbnailIsASkeletonUntilItIsDecoded() {
        val gate = newGate()
        val decoded = bitmap()
        rule.setContent { AnalogTheme { PhotoThumb(ByteArray(10), decode = { gate.await(10, TimeUnit.SECONDS); decoded }) } }
        rule.onNodeWithTag(Tags.field("background_thumb_loading")).assertIsDisplayed()
        assertEquals(0, count(Tags.field("background_thumb")))
        gate.countDown()
        rule.waitUntil(5_000) { count(Tags.field("background_thumb")) == 1 }
        assertEquals(0, count(Tags.field("background_thumb_loading")))
    }

    @Test fun anUndecodablePhotoReplacesTheSkeletonWithAMessage() {
        rule.setContent { AnalogTheme { PhotoThumb(ByteArray(10), decode = { null }) } }
        rule.waitUntil(5_000) { count(Tags.field("background_thumb_unavailable")) == 1 }
        assertEquals(0, count(Tags.field("background_thumb_loading")))
    }

    @Test fun aDesignThumbnailIsASkeletonUntilItIsRendered() {
        val gate = newGate()
        val rendered = bitmap()
        rule.setContent { AnalogTheme { DesignThumbnail(Templates.all.first(), 96.dp, render = { _, _, _ -> gate.await(10, TimeUnit.SECONDS); rendered }) } }
        rule.onNodeWithTag(Tags.field("design_thumb_loading")).assertIsDisplayed()
        assertEquals(0, count(Tags.field("design_thumb")))
        gate.countDown()
        rule.waitUntil(5_000) { count(Tags.field("design_thumb")) == 1 }
        assertEquals(0, count(Tags.field("design_thumb_loading")))
    }
}
