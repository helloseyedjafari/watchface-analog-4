package com.seyedjafari.analogplus.phone.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** Android Studio previews of one composable in the light and in the dark theme. The preview functions are private, next to what they show. */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
@Preview(name = "light", showBackground = true)
@Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
annotation class LightDarkPreview

/** The theme, the page colour and some padding around a previewed component. */
@Composable
internal fun PreviewFrame(content: @Composable () -> Unit) {
    AnalogTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(Spacing.lg)) { content() }
        }
    }
}
