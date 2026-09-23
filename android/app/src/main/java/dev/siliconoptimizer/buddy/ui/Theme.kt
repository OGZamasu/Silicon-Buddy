package dev.siliconoptimizer.buddy.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F2E8),
    onPrimaryContainer = Color(0xFF004D43),
    secondary = Color(0xFF426A72),
    secondaryContainer = Color(0xFFDCEEF0),
    onSecondaryContainer = Color(0xFF224B52),
    tertiary = Color(0xFF745B9B),
    background = Color(0xFFF3F7F5),
    onBackground = Color(0xFF172D2B),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172D2B),
    surfaceVariant = Color(0xFFE9F0ED),
    onSurfaceVariant = Color(0xFF506460),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAF9),
    surfaceContainerHigh = Color(0xFFE9F0ED),
    surfaceContainerHighest = Color(0xFFE1EAE6),
    outline = Color(0xFF5D716A),
    outlineVariant = Color(0xFFD5E2DC),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF7ADCC2),
    onPrimary = Color(0xFF00392F),
    primaryContainer = Color(0xFF174D41),
    onPrimaryContainer = Color(0xFFB7F3DF),
    secondary = Color(0xFFA5D4DC),
    secondaryContainer = Color(0xFF24474E),
    onSecondaryContainer = Color(0xFFC6EBF0),
    tertiary = Color(0xFFD0B9F2),
    background = Color(0xFF0C1718),
    onBackground = Color(0xFFE7F1ED),
    surface = Color(0xFF142426),
    onSurface = Color(0xFFE7F1ED),
    surfaceVariant = Color(0xFF233638),
    onSurfaceVariant = Color(0xFFB0C5BD),
    surfaceContainer = Color(0xFF142426),
    surfaceContainerLow = Color(0xFF112022),
    surfaceContainerHigh = Color(0xFF1D3032),
    surfaceContainerHighest = Color(0xFF293E40),
    outline = Color(0xFF90A79F),
    outlineVariant = Color(0xFF2D4443),
)

private val BuddyTypography = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

/** Consistent teal identity, with native light/dark behavior and scalable system type. */
@Composable
fun SiliconBuddyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BuddyTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
