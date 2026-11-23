package app.rasidi.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rasidi.R

/** ألوان هوية رصيدي (مأخوذة من هوية طاقتي الفعلية في theme.css). */
@Immutable
data class RColors(
    val bg: Color, val surface: Color, val card: Color, val cardHi: Color, val input: Color, val soft: Color, val line: Color,
    val txt: Color, val mute: Color, val pri: Color, val priInk: Color, val priSoft: Color, val gold: Color, val goldSoft: Color,
    val danger: Color, val dangerSoft: Color, val warn: Color, val warnSoft: Color, val tab: Color, val dark: Boolean,
)

val LightColors = RColors(
    bg = Color(0xFFF4F2EB), surface = Color(0xFFFFFDF8), card = Color(0xFFFFFDF8), cardHi = Color(0xFFFFFFFF), input = Color(0xFFEFECE3),
    soft = Color(0x0A10241A), line = Color(0x1410241A), txt = Color(0xFF10241A), mute = Color(0xFF5F685D),
    pri = Color(0xFF0F5F3E), priInk = Color.White, priSoft = Color(0x140F5F3E), gold = Color(0xFFB07A0E), goldSoft = Color(0x1FD99A1E),
    danger = Color(0xFFB3261E), dangerSoft = Color(0x14B3261E), warn = Color(0xFF8A5300), warnSoft = Color(0x1FD99A1E), tab = Color(0xE6FFFDF8), dark = false,
)

val DarkColors = RColors(
    bg = Color(0xFF07080A), surface = Color(0xFF15171B), card = Color(0xFF15171B), cardHi = Color(0xFF1D2025), input = Color(0xFF0E1013),
    soft = Color(0x0BFFFFFF), line = Color(0x14FFFFFF), txt = Color(0xFFF3F5F1), mute = Color(0xFF9AA1A8),
    pri = Color(0xFF2FC77F), priInk = Color(0xFF04130B), priSoft = Color(0x1F2FC77F), gold = Color(0xFFF0B43A), goldSoft = Color(0x1FF0B43A),
    danger = Color(0xFFFF8A80), dangerSoft = Color(0x1FFF8A80), warn = Color(0xFFF0B43A), warnSoft = Color(0x1FF0B43A), tab = Color(0xD91B1E23), dark = true,
)

val LocalR = staticCompositionLocalOf { LightColors }
/** إخفاء المبالغ (خيار الخصوصية). */
val LocalHideAmounts = staticCompositionLocalOf { false }

val Cairo = FontFamily(
    Font(R.font.cairo_regular, FontWeight.Normal),
    Font(R.font.cairo_regular, FontWeight.Medium),
    Font(R.font.cairo_bold, FontWeight.SemiBold),
    Font(R.font.cairo_bold, FontWeight.Bold),
    Font(R.font.cairo_bold, FontWeight.ExtraBold),
)

/** المسافات الموحّدة 8/12/16/24. */
object Sp { val xs = 8.dp; val sm = 12.dp; val md = 16.dp; val lg = 24.dp; val radius = 24.dp }

private fun typography(): Typography {
    val b = TextStyle(fontFamily = Cairo)
    return Typography(
        displaySmall = b.copy(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 42.sp),
        headlineMedium = b.copy(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 36.sp),
        headlineSmall = b.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
        titleLarge = b.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
        titleMedium = b.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp),
        titleSmall = b.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp),
        bodyLarge = b.copy(fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = b.copy(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = b.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
        labelLarge = b.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        labelMedium = b.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
        labelSmall = b.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun RasidiTheme(themeMode: String = "system", fontScale: Float = 1f, hideAmounts: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (themeMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(primary = c.pri, onPrimary = c.priInk, background = c.bg, surface = c.surface, onSurface = c.txt, onBackground = c.txt, surfaceVariant = c.input, onSurfaceVariant = c.mute, outline = c.line, secondary = c.gold, error = c.danger, primaryContainer = c.priSoft, onPrimaryContainer = c.pri, surfaceContainer = c.card, surfaceContainerHigh = c.cardHi, surfaceContainerHighest = c.cardHi, surfaceContainerLow = c.card)
    else lightColorScheme(primary = c.pri, onPrimary = c.priInk, background = c.bg, surface = c.surface, onSurface = c.txt, onBackground = c.txt, surfaceVariant = c.input, onSurfaceVariant = c.mute, outline = c.line, secondary = c.gold, error = c.danger, primaryContainer = c.priSoft, onPrimaryContainer = c.pri, surfaceContainer = c.card, surfaceContainerHigh = c.cardHi, surfaceContainerHighest = c.cardHi, surfaceContainerLow = c.card)
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalR provides c,
        LocalHideAmounts provides hideAmounts,
        LocalLayoutDirection provides LayoutDirection.Rtl,
        LocalDensity provides Density(d.density, d.fontScale * fontScale),
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(), content = content)
    }
}
