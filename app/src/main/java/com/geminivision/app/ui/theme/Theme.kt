package com.geminivision.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val GeminiBlue = Color(0xFF5B9BFF)
val GeminiViolet = Color(0xFFA78BFA)
val GeminiCyan = Color(0xFF38BDF8)
val LiveEmerald = Color(0xFF34D399)
val AlertCoral = Color(0xFFF87171)
val SurfaceObsidian = Color(0xFF0A0D14)
val SurfaceCard = Color(0xFF141926)
val SurfaceElevated = Color(0xFF1E2638)

private val DarkColors = darkColorScheme(
    primary = GeminiBlue,
    onPrimary = Color(0xFF041326),
    primaryContainer = Color(0xFF1A2D4F),
    onPrimaryContainer = Color(0xFFDCE9FF),
    secondary = GeminiViolet,
    onSecondary = Color(0xFF1B0E33),
    secondaryContainer = Color(0xFF2B2145),
    onSecondaryContainer = Color(0xFFE9DDFF),
    tertiary = LiveEmerald,
    background = SurfaceObsidian,
    onBackground = Color(0xFFF1F5F9),
    surface = SurfaceObsidian,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = SurfaceCard,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2F3A52),
    error = AlertCoral
)

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun GeminiVisionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content
    )
}
