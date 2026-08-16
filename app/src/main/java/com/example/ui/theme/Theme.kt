package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val IdeColorScheme =
  darkColorScheme(
    primary = IdePrimary,
    onPrimary = IdeOnPrimary,
    background = IdeBackground,
    onBackground = IdeTextPrimary,
    surface = IdeSurface,
    onSurface = IdeTextPrimary,
    surfaceVariant = IdeSurfaceVariant,
    onSurfaceVariant = IdeTextSecondary,
    error = IdeError,
    outline = IdeBorder
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = true,
  // Dynamic color is available on Android 12+
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = IdeColorScheme

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
