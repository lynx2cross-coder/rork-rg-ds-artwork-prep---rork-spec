package com.rork.rgdsartworkprep.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val RgdsColorScheme = darkColorScheme(
    primary = AnbernicOrange,
    onPrimary = Ink,
    primaryContainer = AnbernicOrangeDim,
    onPrimaryContainer = AnbernicOrange,
    secondary = TextSecondary,
    onSecondary = TextPrimary,
    background = Graphite,
    onBackground = TextPrimary,
    surface = Graphite,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteElevated,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = GraphiteElevated,
    surfaceContainerHigh = GraphiteHigh,
    surfaceContainerHighest = GraphiteHigh,
    surfaceContainerLow = GraphiteElevated,
    surfaceContainerLowest = Graphite,
    outline = HairlineBorder,
    outlineVariant = HairlineBorder,
    error = StatusRed,
    onError = Ink,
    errorContainer = StatusRedDim,
    onErrorContainer = StatusRed,
    scrim = Ink,
)

private val RgdsTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 17.sp,
    ),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RgdsColorScheme,
        typography = RgdsTypography,
        content = content,
    )
}
