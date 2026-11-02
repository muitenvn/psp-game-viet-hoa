package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CyberEngineeringColorScheme = darkColorScheme(
    primary = ScoutCyan,
    onPrimary = Color(0xFF00201C),
    primaryContainer = ScoutCyanContainer,
    onPrimaryContainer = OnScoutCyanContainer,
    secondary = BulkAmber,
    onSecondary = Color(0xFF261900),
    secondaryContainer = BulkAmberContainer,
    onSecondaryContainer = OnBulkAmberContainer,
    tertiary = PatchEmerald,
    onTertiary = Color(0xFF002114),
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = ErrorCoral
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CyberEngineeringColorScheme,
        typography = Typography,
        content = content
    )
}
