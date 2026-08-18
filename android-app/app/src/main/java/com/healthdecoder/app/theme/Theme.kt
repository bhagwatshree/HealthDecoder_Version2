package com.healthdecoder.app.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.healthdecoder.app.local.AppSettings

private val DarkColorScheme = darkColorScheme(
    primary = MedicalTealLight,
    onPrimary = MedicalOnSurface,
    primaryContainer = MedicalNavy,
    onPrimaryContainer = MedicalTealLight,
    secondary = MedicalNavyLight,
    onSecondary = MedicalOnSurface,
    secondaryContainer = MedicalDarkSurfaceVariant,
    onSecondaryContainer = MedicalNavyLight,
    tertiary = MedicalAmberLight,
    onTertiary = MedicalOnSurface,
    tertiaryContainer = MedicalDarkSurfaceVariant,
    onTertiaryContainer = MedicalAmberLight,
    background = MedicalDarkBackground,
    onBackground = MedicalOnPrimary,
    surface = MedicalDarkSurface,
    onSurface = MedicalOnPrimary,
    surfaceVariant = MedicalDarkSurfaceVariant,
    onSurfaceVariant = MedicalOutlineVariant,
    outline = MedicalOutline,
    outlineVariant = MedicalOnSurfaceVariant,
)

private val LightColorScheme =
    lightColorScheme(
        primary = MedicalTeal,
        onPrimary = MedicalOnPrimary,
        primaryContainer = MedicalSurfaceVariant,
        onPrimaryContainer = MedicalNavy,
        secondary = MedicalNavy,
        onSecondary = MedicalOnSecondary,
        secondaryContainer = MedicalSurfaceVariant,
        onSecondaryContainer = MedicalNavy,
        tertiary = MedicalAmber,
        onTertiary = MedicalOnPrimary,
        tertiaryContainer = MedicalSurfaceVariant,
        onTertiaryContainer = MedicalNavy,
        background = MedicalBackground,
        onBackground = MedicalOnBackground,
        surface = MedicalSurface,
        onSurface = MedicalOnSurface,
        surfaceVariant = MedicalSurfaceVariant,
        onSurfaceVariant = MedicalOnSurfaceVariant,
        outline = MedicalOutline,
        outlineVariant = MedicalOutlineVariant,
    )

@Composable
fun MedicalScannerTheme(
    darkTheme: Boolean? = null,
    // Disabled: medical app should maintain consistent branding regardless of wallpaper
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(AppSettings.getThemeMode(context)) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("medical_scanner_prefs", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode") {
                themeMode = AppSettings.getThemeMode(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    val isDark = darkTheme ?: when (themeMode) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

