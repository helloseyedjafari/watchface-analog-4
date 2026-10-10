package com.seyedjafari.analogplus.phone.accounts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing but `accounts/firebase`, `AccountsFactory.kt` and the build-type files in `accounts/firebase` of `src/debug` and
 * `src/release` may mention Firebase, Credential Manager or the Google ID library: the sync, backup and account logic stay
 * pure Kotlin and testable with fakes. Reads the sources as files (the build lists them as inputs of the test task). Any
 * mention counts, not only an `import`: a fully-qualified name in code would bypass the rule otherwise.
 */
class FirebaseIsolationTest {
    private val forbidden = Regex("""\b(com\.google\.firebase|androidx\.credentials|com\.google\.android\.libraries\.identity)\b""")
    private val roots = listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin").map(::File)

    private fun allowed(path: String) =
        "/accounts/firebase/" in path || path.endsWith("/accounts/AccountsFactory.kt")

    private fun sources() = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test fun theSourcesAreFound() {
        for (root in roots) assertTrue("$root not found from ${File(".").absolutePath}", root.isDirectory)
        assertTrue(sources().any { it.name == "AccountsFactory.kt" })
        assertTrue("the Firebase package is not found", sources().any { "/accounts/firebase/" in it.path.replace('\\', '/') })
        for (variant in listOf("debug", "release")) {
            assertTrue("no $variant build-type file found", sources().any { it.path.replace('\\', '/').contains("src/$variant/") && it.name == "FirebaseBuildType.kt" })
        }
    }

    @Test fun noFirebaseMentionOutsideTheFirebasePackage() {
        val offenders = sources().map { it.path.replace('\\', '/') }.filterNot(::allowed).flatMap { path ->
            File(path).readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "$path:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals("Firebase is mentioned outside accounts/firebase:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }
}
