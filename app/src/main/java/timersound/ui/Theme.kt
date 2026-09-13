package timersound.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF0A2A5C),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    surface = Color(0xFFF7F9FC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0A2A5C),
    primaryContainer = Color(0xFF1F3A68),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF1F1F1F),
)

@Composable
fun TimerSoundTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}