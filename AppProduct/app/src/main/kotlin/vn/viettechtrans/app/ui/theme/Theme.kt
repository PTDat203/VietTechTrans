package vn.viettechtrans.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.viettechtrans.app.R

/*
 * Visual identity (2026-10-02): "modern, fresh" in the spirit of Material 3 Expressive —
 * indigo→violet brand gradient, large rounded shapes, bold Be Vietnam Pro type (made
 * for Vietnamese diacritics, OFL, bundled so it works offline). Brand colors instead of
 * dynamic wallpaper colors so the app looks the same on every phone.
 */

private val Indigo = Color(0xFF4F46E5)
private val Violet = Color(0xFF8B5CF6)
private val Cyan = Color(0xFF06B6D4)
private val Coral = Color(0xFFF97366)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E1FF),
    onPrimaryContainer = Color(0xFF15106B),
    secondary = Cyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDF5FC),
    onSecondaryContainer = Color(0xFF00363F),
    tertiary = Violet,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0E6FF),
    onTertiaryContainer = Color(0xFF2E0C6B),
    error = Color(0xFFE5484D),
    background = Color(0xFFF7F7FC),
    onBackground = Color(0xFF15161E),
    surface = Color(0xFFF7F7FC),
    onSurface = Color(0xFF15161E),
    surfaceVariant = Color(0xFFE6E6F0),
    onSurfaceVariant = Color(0xFF4A4B5C),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F2F9),
    surfaceContainer = Color(0xFFECECF5),
    surfaceContainerHigh = Color(0xFFE6E6F1),
    surfaceContainerHighest = Color(0xFFE0E0EC),
    outline = Color(0xFF7B7C8F),
    outlineVariant = Color(0xFFCBCBDA),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5A3FF),
    onPrimary = Color(0xFF1C1784),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE3E1FF),
    secondary = Color(0xFF67E3F9),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF0E4A56),
    onSecondaryContainer = Color(0xFFCDF5FC),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF2E0C6B),
    tertiaryContainer = Color(0xFF4C2A9A),
    onTertiaryContainer = Color(0xFFF0E6FF),
    error = Color(0xFFFF8A8E),
    background = Color(0xFF0F1017),
    onBackground = Color(0xFFE6E6F0),
    surface = Color(0xFF0F1017),
    onSurface = Color(0xFFE6E6F0),
    surfaceVariant = Color(0xFF2A2B38),
    onSurfaceVariant = Color(0xFFC7C7D6),
    surfaceContainerLowest = Color(0xFF0A0B11),
    surfaceContainerLow = Color(0xFF16171F),
    surfaceContainer = Color(0xFF1B1C25),
    surfaceContainerHigh = Color(0xFF242530),
    surfaceContainerHighest = Color(0xFF2F303C),
    outline = Color(0xFF8E8FA3),
    outlineVariant = Color(0xFF3B3C4A),
)

val BeVietnamPro = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = BeVietnamPro,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/** Line heights ≥ 1.4× so stacked Vietnamese diacritics (Ặ, Ỡ, Ừ) never clip. */
private val AppTypography = Typography(
    displaySmall = style(34, FontWeight.Bold, 46),
    headlineLarge = style(30, FontWeight.Bold, 42),
    headlineMedium = style(26, FontWeight.Bold, 36),
    headlineSmall = style(22, FontWeight.SemiBold, 32),
    titleLarge = style(20, FontWeight.SemiBold, 28),
    titleMedium = style(17, FontWeight.SemiBold, 25),
    titleSmall = style(15, FontWeight.SemiBold, 22),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(15, FontWeight.Normal, 22),
    bodySmall = style(13, FontWeight.Normal, 19),
    labelLarge = style(15, FontWeight.SemiBold, 21),
    labelMedium = style(13, FontWeight.Medium, 18),
    labelSmall = style(12, FontWeight.Medium, 17, 0.2),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Brand gradients used by headers, the mic buttons and highlighted cards. */
@Immutable
data class VttGradients(
    val brand: Brush,
    val brandSoft: Brush,
    val partyA: Brush,
    val partyB: Brush,
    val listening: Brush,
)

private fun gradients(dark: Boolean) = VttGradients(
    brand = Brush.linearGradient(listOf(Indigo, Violet)),
    brandSoft = Brush.verticalGradient(
        if (dark) listOf(Color(0xFF231E5C), Color(0xFF0F1017)) else listOf(Color(0xFFE7E5FF), Color(0xFFF7F7FC)),
    ),
    partyA = Brush.linearGradient(listOf(Indigo, Violet)),
    partyB = Brush.linearGradient(listOf(Color(0xFF0891B2), Cyan)),
    listening = Brush.linearGradient(listOf(Coral, Color(0xFFE5484D))),
)

val LocalGradients = staticCompositionLocalOf { gradients(false) }

/** Follows the system dark mode. */
@Composable
fun VttTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalGradients provides gradients(dark)) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
