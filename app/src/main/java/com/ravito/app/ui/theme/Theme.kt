package com.ravito.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF0F5132)
val GreenLight = Color(0xFFD7EBDF)
val Amber = Color(0xFFF2A900)
val Danger = Color(0xFFB3261E)

private val colors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenLight,
    onPrimaryContainer = Color(0xFF062716),
    secondary = Color(0xFF7A5900),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE8B0),
    onSecondaryContainer = Color(0xFF261A00),
    background = Color(0xFFF7F8F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF0EC),
    error = Danger,
)

@Composable
fun RavitoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
