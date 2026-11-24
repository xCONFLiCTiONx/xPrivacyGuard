package cc.xconflictionx.xprivacyguard.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val GuardOrange = Color(0xFFF38020)
val GuardOrangeBright = Color(0xFFFAAD3F)
val GuardOrangeContainer = Color(0xFF2B1B0E)
val GuardBackground = Color(0xFF121214)
val GuardSurface = Color(0xFF1A1A1E)
val GuardSurfaceElevated = Color(0xFF222227)
val GuardBorder = Color(0xFF2C2C33)
val GuardTextPrimary = Color(0xFFF4F4F5)
val GuardTextSecondary = Color(0xFFA1A1AA)
val GuardSuccess = Color(0xFF22C55E)
val GuardSuccessContainer = Color(0xFF0E2918)
val GuardError = Color(0xFFEF4444)
val GuardErrorContainer = Color(0xFF331417)

private val DarkColorScheme = darkColorScheme(
    primary = GuardOrange,
    onPrimary = Color(0xFF121214),
    primaryContainer = GuardOrangeContainer,
    onPrimaryContainer = GuardOrangeBright,
    secondary = GuardOrangeBright,
    onSecondary = Color(0xFF121214),
    secondaryContainer = GuardSurfaceElevated,
    onSecondaryContainer = GuardTextPrimary,
    tertiary = GuardSuccess,
    onTertiary = Color(0xFF121214),
    tertiaryContainer = GuardSuccessContainer,
    onTertiaryContainer = GuardSuccess,
    background = GuardBackground,
    onBackground = GuardTextPrimary,
    surface = GuardSurface,
    onSurface = GuardTextPrimary,
    surfaceVariant = GuardSurfaceElevated,
    onSurfaceVariant = GuardTextSecondary,
    outline = GuardBorder,
    error = GuardError,
    onError = Color.White,
    errorContainer = GuardErrorContainer,
    onErrorContainer = GuardError
)

@Composable
fun PrivacyFirewallTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
