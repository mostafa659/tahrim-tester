package com.example.tahrimtester

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

/** خط المصحف: Scheherazade New (SIL OFL) يغطي علامات التشكيل والتنوين المفتوح. */
val QuranFont = FontFamily(
    Font(R.font.quran_regular, FontWeight.Normal),
    Font(R.font.quran_semibold, FontWeight.SemiBold),
    Font(R.font.quran_semibold, FontWeight.Bold)
)

/** ألوان دلالية خاصة بالتلاوة: سليم / للمراجعة / موضع القارئ / ذهبي زخرفي. */
class QColors(
    val good: Color, val goodBg: Color,
    val review: Color, val reviewBg: Color,
    val gold: Color, val goldBg: Color
)

private val LightQ = QColors(
    good = Color(0xFF1E7A4F), goodBg = Color(0xFFDDF0E3),
    review = Color(0xFFB3412E), reviewBg = Color(0xFFF8E0D9),
    gold = Color(0xFFA9792A), goldBg = Color(0xFFF2E6C8)
)
private val DarkQ = QColors(
    good = Color(0xFF6FD3A0), goodBg = Color(0xFF173B2C),
    review = Color(0xFFF0907A), reviewBg = Color(0xFF43221B),
    gold = Color(0xFFE0B96B), goldBg = Color(0xFF3A311B)
)

val LocalQ = staticCompositionLocalOf { LightQ }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6F5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EBE2), onPrimaryContainer = Color(0xFF0B3A2E),
    secondary = Color(0xFF8A6A2E), onSecondary = Color.White,
    background = Color(0xFFFAF5EA), onBackground = Color(0xFF1E2822),
    surface = Color(0xFFFAF5EA), onSurface = Color(0xFF1E2822),
    surfaceVariant = Color(0xFFEFE6D2), onSurfaceVariant = Color(0xFF55604F),
    surfaceContainerLow = Color(0xFFF4ECD9), surfaceContainer = Color(0xFFF1E8D3),
    surfaceContainerHigh = Color(0xFFEDE3CC),
    outline = Color(0xFFC9BC9C), outlineVariant = Color(0xFFE0D5B8),
    error = Color(0xFFB3412E)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5FC3A5), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF17463A), onPrimaryContainer = Color(0xFFCDEFE2),
    secondary = Color(0xFFE0B96B), onSecondary = Color(0xFF3A2C08),
    background = Color(0xFF0E1613), onBackground = Color(0xFFEAE6D9),
    surface = Color(0xFF0E1613), onSurface = Color(0xFFEAE6D9),
    surfaceVariant = Color(0xFF1C2A25), onSurfaceVariant = Color(0xFFB5BEB1),
    surfaceContainerLow = Color(0xFF131E1A), surfaceContainer = Color(0xFF17231F),
    surfaceContainerHigh = Color(0xFF1C2A25),
    outline = Color(0xFF45554E), outlineVariant = Color(0xFF2A3A33),
    error = Color(0xFFF0907A)
)

@Composable
fun TahrimTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = scheme.background.toArgb()
                window.navigationBarColor = scheme.background.toArgb()
                val c = WindowCompat.getInsetsController(window, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalQ provides (if (dark) DarkQ else LightQ)) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
