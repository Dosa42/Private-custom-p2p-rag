package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TrinityDarkColorScheme = darkColorScheme(
  primary = TrinityCyan,
  onPrimary = TrinityDeepNavy,
  primaryContainer = TrinityCardNavy,
  onPrimaryContainer = TrinityCyan,
  secondary = TrinityElectricBlue,
  onSecondary = TrinityDeepNavy,
  secondaryContainer = TrinitySurfaceNavy,
  onSecondaryContainer = TrinityElectricBlue,
  tertiary = TrinityAccentGold,
  onTertiary = TrinityDeepNavy,
  background = TrinityDeepNavy,
  onBackground = TextPrimary,
  surface = TrinitySurfaceNavy,
  onSurface = TextPrimary,
  surfaceVariant = TrinityCardNavy,
  onSurfaceVariant = TextSecondary,
  error = TrinityAlertRed,
  onError = Color.White
)

private val TrinityLightColorScheme = TrinityDarkColorScheme // High-tech apps are best in focused dark cyber aesthetic

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = TrinityDarkColorScheme,
    typography = Typography,
    content = content
  )
}

