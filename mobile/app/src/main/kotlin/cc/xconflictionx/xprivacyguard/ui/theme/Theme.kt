package cc.xconflictionx.xprivacyguard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1E88E5),
    secondary = Color(0xFF64B5F6),
    tertiary = Color(0xFF90CAF9),
    primaryContainer = Color(0xFF0D3B66),
    secondaryContainer = Color(0xFF1E4E79),
    tertiaryContainer = Color(0xFF285F8F),
    background = Color(0xFF111827),
    surface = Color(0xFF111827),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onPrimaryContainer = Color(0xFFE3F2FD),
    onSecondaryContainer = Color(0xFFE3F2FD),
    onTertiaryContainer = Color(0xFFE3F2FD),
    onBackground = Color(0xFFE3F2FD),
    onSurface = Color(0xFFE3F2FD)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    secondary = Color(0xFF42A5F5),
    tertiary = Color(0xFF90CAF9),
    primaryContainer = Color(0xFFDCEEFF),
    secondaryContainer = Color(0xFFE3F2FD),
    tertiaryContainer = Color(0xFFD6EAF8),
    background = Color(0xFFF5FAFF),
    surface = Color(0xFFF5FAFF),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onPrimaryContainer = Color(0xFF0F172A),
    onSecondaryContainer = Color(0xFF0F172A),
    onTertiaryContainer = Color(0xFF0F172A),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A)
)

@Composable
fun PrivacyFirewallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
