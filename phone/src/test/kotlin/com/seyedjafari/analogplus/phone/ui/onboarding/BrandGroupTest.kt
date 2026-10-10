package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.ui.graphics.toArgb
import com.seyedjafari.analogplus.phone.ui.theme.DarkScheme
import com.seyedjafari.analogplus.phone.ui.theme.LightScheme
import com.seyedjafari.analogplus.phone.ui.theme.Palette
import com.seyedjafari.analogplus.phone.ui.theme.contrastRatio
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour groups of the generated animations and the theme role each takes. The welcome animation cycles through four colour
 * schemes, so every role it can use must read against the page in light and dark, and no group in any file may lack a role
 * (it would show as a loud placeholder).
 */
class BrandGroupTest {
    private val raw: File = generateSequence(File("").absoluteFile) { it.parentFile }.take(6)
        .flatMap { sequenceOf(File(it, "src/main/res/raw"), File(it, "phone/src/main/res/raw")) }
        .firstOrNull { it.isDirectory } ?: File("src/main/res/raw")

    private fun groupsIn(name: String): Set<String> {
        val found = HashSet<String>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if ((e["ty"] as? JsonPrimitive)?.contentOrNull == "gr") (e["nm"] as? JsonPrimitive)?.contentOrNull?.let { found += it }
                    e.values.forEach(::walk)
                }
                is JsonArray -> e.forEach(::walk)
                else -> {}
            }
        }
        walk(Json.parseToJsonElement(File(raw, "$name.json").readText()))
        return found
    }

    private val names = listOf("onboarding_welcome", "onboarding_searching", "onboarding_found", "onboarding_permission")

    @Test fun everyColourGroupInEveryFileHasAThemeRole() {
        val known = BrandGroup.entries.map { it.group }.toSet()
        names.forEach { name ->
            val groups = groupsIn(name)
            assertTrue("$name has colour groups", groups.isNotEmpty())
            assertTrue("$name uses ${groups - known}, which the app does not recolour", known.containsAll(groups))
        }
    }

    @Test fun theWelcomeAnimationUsesEveryRoleItsColourMorphNeeds() {
        assertEquals(setOf("accent", "accent2", "accent3", "ink", "track", "onaccent"), groupsIn("onboarding_welcome"))
    }

    @Test fun theMorphColoursComeFromTheThemeNotFromTheFile() {
        val roles = BrandGroup.entries.associate { it.group to it.role(LightScheme).toArgb() }
        assertEquals(Palette.lightPrimary, roles.getValue("accent"))
        assertEquals(Palette.lightTertiary, roles.getValue("accent2"))
        assertEquals(Palette.lightSecondary, roles.getValue("accent3"))
        assertEquals(Palette.lightOnSurface, roles.getValue("ink"))
        assertEquals(Palette.lightOutline, roles.getValue("track"))
        assertEquals(Palette.lightOnPrimary, roles.getValue("onaccent"))
    }

    private fun assertDrawnRolesReadOn(background: Int, scheme: androidx.compose.material3.ColorScheme, theme: String) {
        BrandGroup.entries.filter { it != BrandGroup.ONACCENT }.forEach { brand ->
            val ratio = contrastRatio(brand.role(scheme).toArgb(), background)
            assertTrue("${brand.group} on the $theme page: contrast $ratio < 3.0 (graphics need 3:1)", ratio >= 3.0)
        }
        // the hub of the hands is drawn in onaccent on top of whichever accent role the colour scheme has
        listOf(BrandGroup.ACCENT, BrandGroup.ACCENT2, BrandGroup.ACCENT3, BrandGroup.INK).forEach { under ->
            val ratio = contrastRatio(BrandGroup.ONACCENT.role(scheme).toArgb(), under.role(scheme).toArgb())
            assertTrue("onaccent on ${under.group} ($theme): contrast $ratio < 3.0", ratio >= 3.0)
        }
    }

    @Test fun everyRoleReadsAgainstTheLightPage() = assertDrawnRolesReadOn(Palette.lightBackground, LightScheme, "light")

    @Test fun everyRoleReadsAgainstTheDarkPage() = assertDrawnRolesReadOn(Palette.darkBackground, DarkScheme, "dark")
}
