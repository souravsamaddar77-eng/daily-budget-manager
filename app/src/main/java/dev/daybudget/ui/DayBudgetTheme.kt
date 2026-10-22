package dev.daybudget.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import dev.daybudget.domain.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF123E39),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E9DE),
    onPrimaryContainer = Color(0xFF123E39),
    secondary = Color(0xFF4A655E),
    secondaryContainer = Color(0xFFE2ECE4),
    onSecondaryContainer = Color(0xFF203D35),
    background = Color(0xFFF7F7EE),
    onBackground = Color(0xFF172A26),
    surface = Color(0xFFFDFDF7),
    onSurface = Color(0xFF172A26),
    surfaceVariant = Color(0xFFEBEEE5),
    onSurfaceVariant = Color(0xFF4D5B53),
    outline = Color(0xFF6B7971),
    outlineVariant = Color(0xFFCDD5CB),
    error = Color(0xFFA1332F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF650D0C),
)

private val LedgerTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 44.sp, lineHeight = 52.sp, letterSpacing = (-1).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 28.sp, lineHeight = 36.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 23.sp, lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 27.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
)

private val NightColors = darkColorScheme(
    primary = Color(0xFFA6D4BE), onPrimary = Color(0xFF103B30),
    primaryContainer = Color(0xFF254D40), onPrimaryContainer = Color(0xFFD6EDDF),
    secondary = Color(0xFFB1CABB), secondaryContainer = Color(0xFF294B40),
    onSecondaryContainer = Color(0xFFD6EDDF),
    background = Color(0xFF101C19), onBackground = Color(0xFFE1EDE5),
    surface = Color(0xFF1A2B26), onSurface = Color(0xFFE1EDE5),
    surfaceVariant = Color(0xFF2B3D34), onSurfaceVariant = Color(0xFFB7C9BD),
    outline = Color(0xFF8DA294), outlineVariant = Color(0xFF405448),
    error = Color(0xFFFFB4AA), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8B201B), onErrorContainer = Color(0xFFFFDAD5),
)

@Composable
fun DayBudgetTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
    MaterialTheme(colorScheme = if (dark) NightColors else LedgerColors, typography = LedgerTypography,
        shapes = Shapes(medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(20.dp)), content = content)
}
