package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.seyedjafari.analogplus.phone.R

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: FontWeight) = Font(
    R.font.inter_variable, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val InterFamily = FontFamily(inter(FontWeight.Normal), inter(FontWeight.Medium), inter(FontWeight.SemiBold), inter(FontWeight.Bold))

private fun s(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
    TextStyle(fontFamily = InterFamily, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.sp)

private val BaseTypography = Typography()
private fun TextStyle.inter() = copy(fontFamily = InterFamily)

/** Every role uses Inter; the roles below the base block carry the app's own sizes. */
val AnalogTypography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.inter(),
    displayMedium = BaseTypography.displayMedium.inter(),
    headlineLarge = BaseTypography.headlineLarge.inter(),
    headlineMedium = BaseTypography.headlineMedium.inter(),
    titleLarge = s(20, 28, FontWeight.SemiBold),
    bodyLarge = s(16, 24, FontWeight.Normal),
    displaySmall = s(32, 40, FontWeight.SemiBold, -0.5),
    headlineSmall = s(24, 32, FontWeight.SemiBold),
    titleMedium = s(16, 24, FontWeight.SemiBold, 0.1),
    titleSmall = s(14, 20, FontWeight.Medium, 0.1),
    bodyMedium = s(14, 20, FontWeight.Normal),
    bodySmall = s(12, 16, FontWeight.Normal, 0.1),
    labelLarge = s(14, 20, FontWeight.Medium, 0.1),
    labelMedium = s(12, 16, FontWeight.Medium, 0.3),
    labelSmall = s(11, 16, FontWeight.Medium, 0.4),
)

/** For number and hex fields: digits have equal width so values do not jitter while typing. */
val TabularNumbers = TextStyle(fontFamily = InterFamily, fontFeatureSettings = "tnum")
