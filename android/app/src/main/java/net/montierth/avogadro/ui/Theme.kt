package net.montierth.avogadro.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em

/** The tokens of DESIGN.md: black felt lit by a cold LED edge, vapor greys, one amber. */
@Immutable
data class Palette(
    val dark: Boolean,
    val felt: Color,
    val felt2: Color,
    val felt3: Color,
    val vapor: Color,
    val vapor2: Color,
    val vapor3: Color,
    val visited: Color,
    val seam: Color,
    val seam2: Color,
    val led: Color,
    val amber: Color,
    val shadow: Color,
    /** The electron cloud's inks: positive and negative phase. */
    val inkPlus: Color,
    val inkMinus: Color,
    /** Dot alpha for near, middle and far dots. */
    val dotAlpha: FloatArray,
)

private val Dark = Palette(
    dark = true,
    felt = Color(0xFF04070C), felt2 = Color(0xFF0B1119), felt3 = Color(0xFF121A25),
    vapor = Color(0xFFE6EDF6), vapor2 = Color(0xFFAEB9C8), vapor3 = Color(0xFF808B9C),
    visited = Color(0xFFA7B6D3),
    seam = Color(0xFFE6EDF6).copy(alpha = 0.08f), seam2 = Color(0xFFE6EDF6).copy(alpha = 0.15f),
    led = Color(0xFF8CCAFF), amber = Color(0xFFF0B45E), shadow = Color.Black.copy(alpha = 0.55f),
    inkPlus = Color(0xFFE2EAF6), inkMinus = Color(0xFF8CCAFF),
    dotAlpha = floatArrayOf(0.8f, 0.55f, 0.32f),
)

private val Day = Palette(
    dark = false,
    felt = Color(0xFFE6EAEE), felt2 = Color(0xFFF4F6F8), felt3 = Color(0xFFDDE2E8),
    vapor = Color(0xFF0D1520), vapor2 = Color(0xFF3C4656), vapor3 = Color(0xFF566173),
    visited = Color(0xFF4A5888),
    seam = Color(0xFF0D1520).copy(alpha = 0.10f), seam2 = Color(0xFF0D1520).copy(alpha = 0.18f),
    led = Color(0xFF0A61C0), amber = Color(0xFF9A5A06), shadow = Color(0xFF0D1520).copy(alpha = 0.18f),
    inkPlus = Color(0xFF141C28), inkMinus = Color(0xFF0A61C0),
    dotAlpha = floatArrayOf(0.5f, 0.36f, 0.22f),
)

val LocalPalette = staticCompositionLocalOf { Dark }
val LocalFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
val LocalReducedMotion = staticCompositionLocalOf { false }

private val WEIGHTS = intArrayOf(230, 380, 400, 420, 450, 500, 520, 550, 600, 650)

@Composable
fun AvogadroTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val palette = if (isSystemInDarkTheme()) Dark else Day
    // One variable font (Hanken Grotesk, OFL), instanced at each weight the design uses.
    val family = remember {
        val assets = context.assets
        FontFamily(WEIGHTS.map { w ->
            Font("fonts/hanken_grotesk.ttf", assets, FontWeight(w), FontStyle.Normal, FontVariation.Settings(FontVariation.weight(w)))
        })
    }
    val reduce = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalFamily provides family,
        LocalReducedMotion provides reduce,
        LocalTextSelectionColors provides TextSelectionColors(palette.led, palette.led.copy(alpha = 0.32f)),
        content = content,
    )
}

@Composable
fun type(
    size: TextUnit,
    weight: Int,
    color: Color,
    lineHeight: TextUnit = TextUnit.Unspecified,
    tracking: TextUnit = 0.em,
): TextStyle = TextStyle(
    fontFamily = LocalFamily.current,
    fontSize = size,
    fontWeight = FontWeight(weight),
    color = color,
    lineHeight = lineHeight,
    letterSpacing = tracking,
)
