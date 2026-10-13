package com.mvvtracker.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// MVV brand-inspired palette (blue/teal, matching the S-Bahn identity)
private val MvvBlue = Color(0xFF00629B)
private val MvvAccent = Color(0xFF00A99D)

private val LightColors = lightColorScheme(
    primary = MvvBlue,
    secondary = MvvAccent,
    tertiary = Color(0xFF2E7D32)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FC3E8),
    secondary = MvvAccent,
    tertiary = Color(0xFF81C784)
)

@Composable
fun MvvTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Material You: adopt the device wallpaper palette on Android 12+.
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
