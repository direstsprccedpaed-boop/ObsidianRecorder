package com.spasfonk.obsidianrecorder.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ObsidianBackground = Color(0xFF0B0E11)
val ObsidianSurface = Color(0xFF151A1F)
val ObsidianBorder = Color(0xFF2A2F36)
val EmeraldAccent = Color(0xFF34D399)
val ElectricBlue = Color(0xFF3B82F6)
val TextPrimary = Color(0xFFE5E7EB)
val TextSecondary = Color(0xFF9CA3AF)
val DangerRed = Color(0xFFF87171)

private val ObsidianColorScheme = darkColorScheme(
    background = ObsidianBackground,
    surface = ObsidianSurface,
    primary = EmeraldAccent,
    secondary = ElectricBlue,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    error = DangerRed
)

@Composable
fun ObsidianRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ObsidianColorScheme, content = content)
}
