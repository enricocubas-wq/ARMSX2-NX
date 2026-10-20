package app.faxina.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Brand {
    val Mint = Color(0xFF2EE6A6)
    val MintDeep = Color(0xFF0B8F68)
    val Blue = Color(0xFF4DA3FF)
    val Violet = Color(0xFFB07CFF)
    val Pink = Color(0xFFFF7AB6)
    val Amber = Color(0xFFFFB547)
    val Teal = Color(0xFF5BE0D6)
    val Slate = Color(0xFF6B7A90)
    val Red = Color(0xFFFF6B6B)
}

private val Dark: ColorScheme = darkColorScheme(
    primary = Brand.Mint,
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0F3A2F),
    onPrimaryContainer = Color(0xFF9AF5D6),
    secondary = Brand.Blue,
    onSecondary = Color(0xFF00315C),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF1A2431),
    onSurfaceVariant = Color(0xFF9AACC0),
    surfaceContainerLowest = Color(0xFF080B0F),
    surfaceContainerLow = Color(0xFF0F151D),
    surfaceContainer = Color(0xFF131B25),
    surfaceContainerHigh = Color(0xFF1A2431),
    surfaceContainerHighest = Color(0xFF212D3C),
    outline = Color(0xFF3A4A5E),
    outlineVariant = Color(0xFF243142),
    error = Brand.Red,
    onError = Color(0xFF3B0000),
    errorContainer = Color(0xFF4A1B1B),
    onErrorContainer = Color(0xFFFFD6D6),
)

private val Light: ColorScheme = lightColorScheme(
    primary = Brand.MintDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F7E9),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF1F6FD0),
    onSecondary = Color.White,
    background = Color(0xFFF3F6FA),
    onBackground = Color(0xFF0E1621),
    surface = Color(0xFFF3F6FA),
    onSurface = Color(0xFF0E1621),
    surfaceVariant = Color(0xFFE3EAF2),
    onSurfaceVariant = Color(0xFF52627A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEDF2F8),
    surfaceContainerHighest = Color(0xFFE3EAF2),
    outline = Color(0xFFB5C2D2),
    outlineVariant = Color(0xFFDCE4EE),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFE0E0),
    onErrorContainer = Color(0xFF5A0000),
)

private val Type = Typography().let { t ->
    t.copy(
        displayLarge = t.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
        displayMedium = t.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Bold),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    )
}

@Composable
fun FaxinaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type,
        content = content,
    )
}
