package org.openmuscle.connect.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = OmAccent,
    onPrimary = OmBackground,
    background = OmBackground,
    onBackground = OmOnDark,
    surface = OmSurface,
    onSurface = OmOnDark,
    surfaceVariant = OmSurface,
    onSurfaceVariant = OmMuted,
)

@Composable
fun OpenMuscleConnectTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
