package com.seyedjafari.analogplus.phone.accounts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing but `accounts/firebase`, `AccountsFactory.kt` and the two `FirebaseBuildType.kt` files may import Firebase, Credential
 * Manager or the Google ID library: the sync, backup and account logic stay pure Kotlin and testable with fakes. Reads the sources
 * as files (the build lists them as inputs of the test task).
 */
class FirebaseIsolationTest {
    private val forbidden = Regex("""^\s*import\s+(com\.google\.firebase|androidx\.credentials|com\.google\.android\.libraries\.identity)\b""")
    private val roots = listOf("src/main/kotlin", "src/debug/kotlin", "src/release/kotlin").map(::File)

    private fun allowed(path: String) =
        "/accounts/firebase/" in path || path.endsWith("/accounts/AccountsFactory.kt")

    private fun sources() = roots.filter { it.isDirectory }.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test fun theSourcesAreFound() {
        assertTrue("main sources not found from ${File(".").absolutePath}", sources().any { it.name == "AccountsFactory.kt" })
        assertTrue("the Firebase package is not found", sources().any { "/accounts/firebase/" in it.path.replace('\\', '/') })
    }

    @Test fun noFirebaseImportOutsideTheFirebasePackage() {
        val offenders = sources().map { it.path.replace('\\', '/') }.filterNot(::allowed).flatMap { path ->
            File(path).readLines().withIndex().filter { forbidden.containsMatchIn(it.value) }.map { "$path:${it.index + 1}: ${it.value.trim()}" }
        }
        assertEquals("Firebase is imported outside accounts/firebase:\n" + offenders.joinToString("\n"), emptyList<String>(), offenders)
    }
}
