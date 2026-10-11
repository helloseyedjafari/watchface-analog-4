package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production Send path on the PHONE's own classpath. Firebase brings the protobuf lite runtime and Google's face
 * validator brings the full one; `phone/build.gradle.kts` excludes the full runtime from the app's classpaths and keeps the
 * lite one, and the validator was checked to run with it. `facegen`'s own `PackagerTest` runs with the full runtime, so it
 * cannot see a break; this one builds, signs and VALIDATES a face with the classes the installed app really has. A
 * validator update that needs the full runtime would fail here (`NoClassDefFoundError` and the like surface as
 * `FaceBuildException` or an error), not first in a person's Send.
 *
 * It uses its own key file so the app's signing key is not touched, and removes the face it builds.
 */
@RunWith(AndroidJUnit4::class)
class FacePackagerClasspathTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyName = "classpath-test-${System.nanoTime()}.pem"
    private var built: File? = null

    @After fun clean() {
        built?.delete()
        File(context.filesDir, keyName).delete()
        File(context.filesDir, "$keyName.bak").delete()
    }

    @Test fun aFaceIsBuiltSignedAndValidatedWithTheProtobufRuntimeThePhoneHas() {
        val bundle = FacePackager(context, KeyStore(context, keyName)).build(DesignSettings(), 1)
        built = bundle.apk
        assertTrue("the face file is missing", bundle.apk.isFile && bundle.apk.length() > 0)
        assertTrue("the validator gave no token", bundle.token.isNotBlank())
        assertEquals(1, bundle.versionCode)
    }
}
