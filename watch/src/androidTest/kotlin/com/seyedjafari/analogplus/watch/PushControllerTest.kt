package com.seyedjafari.analogplus.watch

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Runs on the Wear OS 6 watch emulator against the real Watch Face Push service.
 * Faces: v1 = androidTest/assets/face_v1.apk + face_v1.token,
 *        v2 = androidTest/assets/face_v2.apk + face_v2.token (see scripts/build_default_face.sh --help).
 * Needs a clean watch emulator: Gradle installs over the existing app and uninstalls it only after the run, so a face
 * or an activation left by an earlier run (for example scripts/poc_e2e.sh, which leaves a phone-signed face of version 3
 * or higher) makes these tests fail ("version not higher", activation already used). Run
 * `adb -s emulator-5560 uninstall com.seyedjafari.analogplus` before the first run (see docs/poc/README.md).
 */
class PushControllerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext
    private val testAssets = instrumentation.context.assets

    private lateinit var system: CountingBackend
    private lateinit var controller: PushController
    private lateinit var v1: File
    private lateinit var v2: File
    private lateinit var tokenV1: String
    private lateinit var tokenV2: String

    @Before fun setUp() {
        for (p in listOf("com.google.wear.permission.PUSH_WATCH_FACES", "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE")) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${target.packageName} $p").close()
        }
        val prefs = target.getSharedPreferences("pushtest", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        system = CountingBackend(LibraryPushBackend(target))
        controller = PushController(system, PrefsPushStore(prefs))
        v1 = copy(testAssets.open("face_v1.apk"), "v1.apk")
        v2 = copy(testAssets.open("face_v2.apk"), "v2.apk")
        tokenV1 = testAssets.open("face_v1.token").bufferedReader().readText().trim()
        tokenV2 = testAssets.open("face_v2.token").bufferedReader().readText().trim()
    }

    @Test fun addThenUpdateKeepsOneFaceAndRejectsLowerVersion() = runBlocking {
        val before = system.list()
        if (before.isEmpty()) {
            assertEquals("add v1", com.seyedjafari.analogplus.protocol.ResultMessage(true, 1, null), controller.apply(v1, tokenV1, 1))
        }
        val up = controller.apply(v2, tokenV2, 2)
        assertTrue("update to v2: ${up.error}", up.ok)
        assertEquals(2, up.versionCode)

        val faces = system.list()
        assertEquals(1, faces.size)
        assertEquals(2L, faces.single().versionCode)
        assertEquals("com.seyedjafari.analogplus.watchfacepush.analog", faces.single().packageName)

        val addsAndUpdates = system.adds + system.updates
        val lower = controller.apply(v1, tokenV1, 1)
        assertFalse(lower.ok)
        assertEquals("version not higher", lower.error)
        assertEquals("a rejected version must not reach the system", addsAndUpdates, system.adds + system.updates)
        assertEquals(2L, system.list().single().versionCode)
    }

    @Test fun activateOnceCallsTheSystemOnce() = runBlocking {
        if (system.list().isEmpty()) assertTrue(controller.apply(v1, tokenV1, 1).ok)
        val first = controller.activateOnce()
        assertTrue("first activation: ${first.error}", first.ok)
        val second = controller.activateOnce()
        assertTrue(second.ok)
        assertEquals(null, second.error)
        assertEquals(1, system.setActives)
        assertEquals(ActiveState.ACTIVE, controller.status().active)
    }

    private fun copy(input: java.io.InputStream, name: String): File {
        val f = File(target.cacheDir, name)
        input.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }
}

/** Counts the calls that reach the real service. */
private class CountingBackend(private val real: PushBackend) : PushBackend {
    var adds = 0
    var updates = 0
    var setActives = 0
    override suspend fun list() = real.list()
    override suspend fun add(apk: File, token: String) = real.add(apk, token).also { adds++ }
    override suspend fun update(slotId: String, apk: File, token: String) = real.update(slotId, apk, token).also { updates++ }
    override suspend fun setActive(slotId: String) = real.setActive(slotId).also { setActives++ }
    override suspend fun isActive(packageName: String) = real.isActive(packageName)
}
