package com.leagueoftext.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Paleta inspirada en Riot Mobile: fons gairebé negre, targetes grises i accent daurat. */
object RiotColors {
    val Background = Color(0xFF111113)
    val Surface = Color(0xFF1C1C1F)
    val SurfaceHigh = Color(0xFF26262A)
    val Chip = Color(0xFF2C2C30)
    val Divider = Color(0xFF2E2E33)
    val TextPrimary = Color(0xFFF5F5F7)
    val TextSecondary = Color(0xFF9B9BA1)
    val TextTertiary = Color(0xFF6C6C72)
    val Gold = Color(0xFFC89B3C)
    val GoldLight = Color(0xFFF0E6D2)
    val Live = Color(0xFFE84057)
    val BlueSide = Color(0xFF4A9EFF)
    val RedSide = Color(0xFFE84057)
    val Win = Color(0xFF0AC8B9)
}

private val ColorScheme = darkColorScheme(
    primary = RiotColors.Gold,
    onPrimary = Color.Black,
    secondary = RiotColors.GoldLight,
    background = RiotColors.Background,
    onBackground = RiotColors.TextPrimary,
    surface = RiotColors.Surface,
    onSurface = RiotColors.TextPrimary,
    surfaceVariant = RiotColors.SurfaceHigh,
    onSurfaceVariant = RiotColors.TextSecondary,
    outline = RiotColors.Divider,
    error = RiotColors.Live,
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
)

@Composable
fun LeagueOfTextTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ColorScheme, typography = AppTypography, content = content)
}
