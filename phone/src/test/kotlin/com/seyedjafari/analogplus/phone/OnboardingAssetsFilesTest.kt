package com.seyedjafari.analogplus.phone

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The raw files the app references exist, are valid JSON compositions and stay under the size cap. */
class OnboardingAssetsFilesTest {
    /** Gradle runs unit tests from the module directory; fall back to the repo root and to walking up, so an IDE works too. */
    private val raw: File = generateSequence(File("").absoluteFile) { it.parentFile }.take(6)
        .flatMap { sequenceOf(File(it, "src/main/res/raw"), File(it, "phone/src/main/res/raw")) }
        .firstOrNull { it.isDirectory } ?: File("src/main/res/raw")
    private val names = listOf("onboarding_welcome", "onboarding_searching", "onboarding_found", "onboarding_permission")

    @Test fun allFourFilesExistAndStayUnder100Kb() {
        names.forEach { name ->
            val f = File(raw, "$name.json")
            assertTrue("missing $f (run tools/gen_onboarding_lottie.py)", f.isFile)
            assertTrue("$name is ${f.length()} bytes", f.length() < 100_000)
        }
    }

    @Test fun eachIsALottieCompositionHeaderWithLayers() {
        names.forEach { name ->
            val root = Json.parseToJsonElement(File(raw, "$name.json").readText()).jsonObject
            assertEquals(name, 480, root.getValue("w").jsonPrimitive.int)
            assertEquals(name, 480, root.getValue("h").jsonPrimitive.int)
            assertTrue(name, root.getValue("op").jsonPrimitive.int > root.getValue("ip").jsonPrimitive.int)
            val layers: JsonArray = root.getValue("layers").jsonArray
            assertTrue("$name has no layers", layers.isNotEmpty())
        }
    }
}
