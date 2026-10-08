package com.vikramanantha.sunsetter.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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

/** Brand green (also the launcher icon background); used when dynamic color isn't available. */
private val Brand = Color(0xFF306C52)

private val LightFallback = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F1D2), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357), secondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFF3D6373), tertiaryContainer = Color(0xFFC1E8FB),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFF99D5B6), onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF15523B), onPrimaryContainer = Color(0xFFB5F1D2),
    secondary = Color(0xFFB3CCBE), secondaryContainer = Color(0xFF354B40),
    tertiary = Color(0xFFA5CCDF), tertiaryContainer = Color(0xFF244C5B),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val AppTypography = Typography().run {
    copy(
        displayMedium = displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** Tabular figures so numbers don't jiggle as they change. */
val Numbers = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun SunsetterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, shapes = AppShapes, typography = AppTypography, content = content)
}
