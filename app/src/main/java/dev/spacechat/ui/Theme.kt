package dev.spacechat.ui

import android.os.Build
import android.app.Activity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun SpaceTheme(mode: String = "system", accent: String = "cyan", dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val primary = when (accent) { "violet" -> Color(0xFFC2ACFF); "mint" -> Color(0xFF8CE2BF); else -> Color(0xFF7CDBEA) }
    val colors = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        dark -> darkColorScheme(primary = primary, secondary = Color(0xFFC2ACFF), background = Color(0xFF0C1018), surface = Color(0xFF111722), surfaceVariant = Color(0xFF202938), onPrimary = Color(0xFF00363E))
        else -> lightColorScheme(primary = when(accent) { "violet" -> Color(0xFF6742AD); "mint" -> Color(0xFF006C4C); else -> Color(0xFF006A77) }, background = Color(0xFFF5F7FC), surface = Color(0xFFF5F7FC))
    }
    val view = LocalView.current
    if(!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.let { activity ->
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
