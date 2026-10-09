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
    background = c(Palette.lightBackground), onBackground = c(Palette.lightOnSurface),
    surface = c(Palette.lightSurface), onSurface = c(Palette.lightOnSurface),
    surfaceVariant = c(Palette.lightSurfaceVariant), onSurfaceVariant = c(Palette.lightOnSurfaceVariant),
    outline = c(Palette.lightOutline), error = c(Palette.lightError),
)

private val DarkScheme = darkColorScheme(
    primary = c(Palette.darkPrimary), onPrimary = c(Palette.darkOnPrimary),
    primaryContainer = c(Palette.darkPrimaryContainer), onPrimaryContainer = c(Palette.darkOnPrimaryContainer),
    background = c(Palette.darkBackground), onBackground = c(Palette.darkOnSurface),
    surface = c(Palette.darkSurface), onSurface = c(Palette.darkOnSurface),
    surfaceVariant = c(Palette.darkSurfaceVariant), onSurfaceVariant = c(Palette.darkOnSurfaceVariant),
    outline = c(Palette.darkOutline), error = c(Palette.darkError),
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
