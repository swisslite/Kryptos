package com.kryptos.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class KColors(
    val bg: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    val accentBright: Color,
    val danger: Color,
    val success: Color,
    val hairline: Color,
    val fieldFill: Color,
    val card: Color,
    val surface: Color,
    val segment: Color,
    val incomingBubble: Color,
)

private val LightK = KColors(
    bg = Color(0xFFF1F3F6),
    textPrimary = Color(0xFF12141A),
    textSecondary = Color(0x8C12141A),
    accent = Color(0xFF3749C2),
    accentBright = Color(0xFF4F63E0),
    danger = Color(0xFFC72E38),
    success = Color(0xFF2BA467),
    hairline = Color(0x1A000000),
    fieldFill = Color(0x0A000000),
    card = Color(0xF5FFFFFF),
    surface = Color(0xFFFFFFFF),
    segment = Color(0xFFFFFFFF),
    incomingBubble = Color(0xFFFFFFFF),
)

private val DarkK = KColors(
    bg = Color(0xFF0B0D10),
    textPrimary = Color(0xFFF2F5FA),
    textSecondary = Color(0x94FFFFFF),
    accent = Color(0xFF6B85FA),
    accentBright = Color(0xFF859CFF),
    danger = Color(0xFFFF6B75),
    success = Color(0xFF33B873),
    hairline = Color(0x1AFFFFFF),
    fieldFill = Color(0x0DFFFFFF),
    card = Color(0xF5161920),
    surface = Color(0xFF14171D),
    segment = Color(0xFF343846),
    incomingBubble = Color(0xFF292B36),
)

val LocalK = staticCompositionLocalOf { LightK }

val K: KColors
    @Composable get() = LocalK.current

private val KTypography = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    )
}

@Composable
fun KryptosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val k = if (dark) DarkK else LightK
    val scheme = if (dark) {
        darkColorScheme(
            primary = k.accent, onPrimary = Color.White,
            secondary = k.accentBright, onSecondary = Color.White,
            background = k.bg, onBackground = k.textPrimary,
            surface = k.surface, onSurface = k.textPrimary,
            surfaceVariant = Color(0xFF20242D), onSurfaceVariant = k.textSecondary,
            error = k.danger, onError = Color.White,
            outline = Color(0x33FFFFFF),
        )
    } else {
        lightColorScheme(
            primary = k.accent, onPrimary = Color.White,
            secondary = k.accentBright, onSecondary = Color.White,
            background = k.bg, onBackground = k.textPrimary,
            surface = k.surface, onSurface = k.textPrimary,
            surfaceVariant = Color(0xFFE8EAF1), onSurfaceVariant = k.textSecondary,
            error = k.danger, onError = Color.White,
            outline = Color(0x33000000),
        )
    }
    CompositionLocalProvider(LocalK provides k) {
        MaterialTheme(colorScheme = scheme, typography = KTypography, shapes = KShapes, content = content)
    }
}

private val KShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
