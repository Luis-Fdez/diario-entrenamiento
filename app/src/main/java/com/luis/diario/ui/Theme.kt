package com.luis.diario.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Solo tema claro: papel cálido y tinta roja.
val Paper = Color(0xFFFAF7F2)
val PaperDeep = Color(0xFFF1EBE1)
val Ink = Color(0xFF8C2F1B)
val InkSoft = Color(0xFFF3DDD5)
val TextInk = Color(0xFF2B2522)
val Muted = Color(0xFF7A6F68)
val Line = Color(0xFFE2D9CC)

private val Colors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = InkSoft,
    onPrimaryContainer = Color(0xFF4A160A),
    secondary = Color(0xFF5E6B5A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E8DD),
    onSecondaryContainer = Color(0xFF1E261C),
    background = Paper,
    onBackground = TextInk,
    surface = Paper,
    onSurface = TextInk,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = Muted,
    surfaceContainer = PaperDeep,
    surfaceContainerHigh = Color(0xFFEDE5D9),
    surfaceContainerHighest = Color(0xFFE8DFD2),
    surfaceContainerLow = Color(0xFFF6F1EA),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFFB9ADA0),
    outlineVariant = Line,
    error = Color(0xFFB3261E),
)

private val Serif = FontFamily.Serif

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun DiarioTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = AppTypography, content = content)
}
