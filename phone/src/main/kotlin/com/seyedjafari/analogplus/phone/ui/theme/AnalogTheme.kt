package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private fun c(argb: Int) = Color(argb)

private val LightScheme = lightColorScheme(
    primary = c(Palette.lightPrimary), onPrimary = c(Palette.lightOnPrimary),
    primaryContainer = c(Palette.lightPrimaryContainer), onPrimaryContainer = c(Palette.lightOnPrimaryContainer),
    inversePrimary = c(Palette.lightInversePrimary),
    secondary = c(Palette.lightSecondary), onSecondary = c(Palette.lightOnSecondary),
    secondaryContainer = c(Palette.lightSecondaryContainer), onSecondaryContainer = c(Palette.lightOnSecondaryContainer),
    tertiary = c(Palette.lightTertiary), onTertiary = c(Palette.lightOnTertiary),
    tertiaryContainer = c(Palette.lightTertiaryContainer), onTertiaryContainer = c(Palette.lightOnTertiaryContainer),
    background = c(Palette.lightBackground), onBackground = c(Palette.lightOnSurface),
    surface = c(Palette.lightSurface), onSurface = c(Palette.lightOnSurface),
    surfaceVariant = c(Palette.lightSurfaceVariant), onSurfaceVariant = c(Palette.lightOnSurfaceVariant),
    surfaceTint = c(Palette.lightPrimary),
    inverseSurface = c(Palette.lightInverseSurface), inverseOnSurface = c(Palette.lightInverseOnSurface),
    error = c(Palette.lightError), onError = c(Palette.lightOnError),
    errorContainer = c(Palette.lightErrorContainer), onErrorContainer = c(Palette.lightOnErrorContainer),
    outline = c(Palette.lightOutline), outlineVariant = c(Palette.lightOutlineVariant), scrim = c(Palette.scrim),
    surfaceBright = c(Palette.lightSurfaceBright), surfaceDim = c(Palette.lightSurfaceDim),
    surfaceContainer = c(Palette.lightSurfaceContainer), surfaceContainerHigh = c(Palette.lightSurfaceContainerHigh),
    surfaceContainerHighest = c(Palette.lightSurfaceContainerHighest), surfaceContainerLow = c(Palette.lightSurfaceContainerLow),
    surfaceContainerLowest = c(Palette.lightSurfaceContainerLowest),
)

private val DarkScheme = darkColorScheme(
    primary = c(Palette.darkPrimary), onPrimary = c(Palette.darkOnPrimary),
    primaryContainer = c(Palette.darkPrimaryContainer), onPrimaryContainer = c(Palette.darkOnPrimaryContainer),
    inversePrimary = c(Palette.darkInversePrimary),
    secondary = c(Palette.darkSecondary), onSecondary = c(Palette.darkOnSecondary),
    secondaryContainer = c(Palette.darkSecondaryContainer), onSecondaryContainer = c(Palette.darkOnSecondaryContainer),
    tertiary = c(Palette.darkTertiary), onTertiary = c(Palette.darkOnTertiary),
    tertiaryContainer = c(Palette.darkTertiaryContainer), onTertiaryContainer = c(Palette.darkOnTertiaryContainer),
    background = c(Palette.darkBackground), onBackground = c(Palette.darkOnSurface),
    surface = c(Palette.darkSurface), onSurface = c(Palette.darkOnSurface),
    surfaceVariant = c(Palette.darkSurfaceVariant), onSurfaceVariant = c(Palette.darkOnSurfaceVariant),
    surfaceTint = c(Palette.darkPrimary),
    inverseSurface = c(Palette.darkInverseSurface), inverseOnSurface = c(Palette.darkInverseOnSurface),
    error = c(Palette.darkError), onError = c(Palette.darkOnError),
    errorContainer = c(Palette.darkErrorContainer), onErrorContainer = c(Palette.darkOnErrorContainer),
    outline = c(Palette.darkOutline), outlineVariant = c(Palette.darkOutlineVariant), scrim = c(Palette.scrim),
    surfaceBright = c(Palette.darkSurfaceBright), surfaceDim = c(Palette.darkSurfaceDim),
    surfaceContainer = c(Palette.darkSurfaceContainer), surfaceContainerHigh = c(Palette.darkSurfaceContainerHigh),
    surfaceContainerHighest = c(Palette.darkSurfaceContainerHighest), surfaceContainerLow = c(Palette.darkSurfaceContainerLow),
    surfaceContainerLowest = c(Palette.darkSurfaceContainerLowest),
)

private val AnalogShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

/** The app theme. Dynamic colour is deliberately not used: the brand stays the same on every phone. */
@Composable
fun AnalogTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        shapes = AnalogShapes,
        typography = AnalogTypography,
        content = content,
    )
}
