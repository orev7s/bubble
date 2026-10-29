package com.accio.whisperbubble

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF23211F),
    onPrimary = Color(0xFFF7F5F0),
    secondary = Color(0xFF8A857C),
    onSecondary = Color(0xFFF7F5F0),
    background = Color(0xFFF7F5F0),
    onBackground = Color(0xFF23211F),
    surface = Color(0xFFF7F5F0),
    onSurface = Color(0xFF23211F),
    surfaceVariant = Color(0xFFEDE9E1),
    onSurfaceVariant = Color(0xFF23211F),
    outline = Color(0xFFDCD7CD),
    error = Color(0xFFE0483A)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2EFE9),
    onPrimary = Color(0xFF16150F),
    secondary = Color(0xFF9C968B),
    onSecondary = Color(0xFF16150F),
    background = Color(0xFF16150F),
    onBackground = Color(0xFFF2EFE9),
    surface = Color(0xFF16150F),
    onSurface = Color(0xFFF2EFE9),
    surfaceVariant = Color(0xFF26241E),
    onSurfaceVariant = Color(0xFFF2EFE9),
    outline = Color(0xFF3A362F),
    error = Color(0xFFFF7A6B)
)

@Composable
fun BubbleTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}
