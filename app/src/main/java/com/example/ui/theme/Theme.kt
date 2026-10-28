package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RilotDarkColorScheme = darkColorScheme(
    primary = ReelCoral,
    onPrimary = TextPrimary,
    primaryContainer = ReelCoralSoft,
    onPrimaryContainer = TextPrimary,
    secondary = CyberMint,
    onSecondary = ObsidianBg,
    secondaryContainer = CyberMintSoft,
    onSecondaryContainer = CyberMint,
    tertiary = ElectricViolet,
    onTertiary = TextPrimary,
    tertiaryContainer = ElectricVioletSoft,
    onTertiaryContainer = TextPrimary,
    background = ObsidianBg,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = ObsidianCardElevated,
    outline = ObsidianBorder,
    error = CrimsonError,
    onError = TextPrimary,
    errorContainer = CrimsonErrorSoft,
    onErrorContainer = CrimsonError
)

@Composable
fun RilotTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RilotDarkColorScheme,
        typography = Typography,
        content = content
    )
}
