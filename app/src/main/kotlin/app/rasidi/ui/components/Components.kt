package app.rasidi.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.rasidi.core.explain.Explanation
import app.rasidi.core.money.Fmt
import app.rasidi.core.money.Money
import app.rasidi.ui.theme.LocalHideAmounts
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import java.math.BigDecimal

@Composable
fun RCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, tint: Color? = null, contentDesc: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalR.current
    val shape = RoundedCornerShape(Sp.radius)
    var m = modifier.fillMaxWidth().clip(shape).background(tint ?: if (c.dark) c.cardHi else c.card).border(1.dp, c.line, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (contentDesc != null) m = m.semantics(mergeDescendants = true) { contentDescription = contentDesc }
    Column(m.padding(Sp.md), content = content)
}

@Composable
fun BigTitle(text: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier.padding(horizontal = 4.dp, vertical = Sp.xs)) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = LocalR.current.txt, modifier = Modifier.semantics { heading() })
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = LocalR.current.mute)
    }
}

@Composable
fun SectionHeader(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = Sp.lg, bottom = Sp.xs, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = LocalR.current.txt, modifier = Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

/** مبلغ معزول الاتجاه (LTR) حتى لا تنقلب الإشارة السالبة ورمز العملة داخل السطر العربي. */
@Composable
fun AmountText(m: Money, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium, color: Color = LocalR.current.txt, signColor: Boolean = false) {
    val hide = LocalHideAmounts.current
    val c = LocalR.current
    val col = if (signColor) (if (m.minor < 0) c.danger else if (m.minor > 0) c.pri else c.mute) else color
    Text(
        if (hide) "•••• ${m.currency.code}" else Fmt.money(m, isolate = false),
        modifier = modifier.semantics { contentDescription = if (hide) "مبلغ مخفي" else spoken(m) },
        style = style.copy(textDirection = TextDirection.Ltr), color = col, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun UsdText(v: BigDecimal, modifier: Modifier = Modifier, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium, color: Color = LocalR.current.txt, prefix: String = "") {
    val hide = LocalHideAmounts.current
    Text(prefix + if (hide) "•••• USD" else Fmt.usd(v, isolate = false), modifier = modifier, style = style.copy(textDirection = TextDirection.Ltr), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

fun spoken(m: Money): String = (if (m.minor < 0) "سالب " else "") + Fmt.number(m.amount.abs(), m.currency.minorDigits) + " " + m.currency.nameAr

enum class Tone { OK, WARN, DANGER, INFO, GOLD }

/** حالة بنص وأيقونة مع اللون (لا يعتمد على اللون وحده). */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val c = LocalR.current
    val (fg, bg, icon) = when (tone) {
        Tone.OK -> Triple(c.pri, c.priSoft, Icons.Filled.CheckCircle)
        Tone.WARN -> Triple(c.warn, c.warnSoft, Icons.Filled.Warning)
        Tone.DANGER -> Triple(c.danger, c.dangerSoft, Icons.Filled.Error)
        Tone.INFO -> Triple(c.mute, c.soft, Icons.Filled.Info)
        Tone.GOLD -> Triple(c.gold, c.goldSoft, Icons.Filled.Star)
    }
    Row(modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}, sub: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalR.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = Sp.xs, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = c.txt) }
            Spacer(Modifier.weight(1f)); actions()
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Sp.md).padding(bottom = 120.dp).navigationBarsPadding()) {
            BigTitle(title, sub = sub)
            content()
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(18.dp)) {
        if (icon != null) { Icon(icon, null); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = LocalR.current
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.priSoft, contentColor = c.pri)) {
        if (icon != null) { Icon(icon, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** رابط «كيف انحسب؟» يفتح شرح القيمة. */
@Composable
fun HowLink(onClick: () -> Unit) {
    val c = LocalR.current
    Row(Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.HelpOutline, null, tint = c.pri, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("كيف انحسب؟", color = c.pri, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun ExplanationDialog(e: Explanation, onDismiss: () -> Unit, onOpenRef: ((String) -> Unit)? = null) {
    val c = LocalR.current
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } },
        title = { Text(e.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(e.result.replace("\u2066", "").replace("\u2069", ""), style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content), color = c.pri)
                e.asOf?.let { Text("بتاريخ $it", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                Spacer(Modifier.height(8.dp))
                Text("المعادلة", style = MaterialTheme.typography.titleSmall)
                Text(e.formula.replace("\u2066", "").replace("\u2069", ""), style = MaterialTheme.typography.bodySmall, color = c.txt)
                if (e.pending.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    StatusChip("يحتاج مراجعة (${e.pending.size})", Tone.WARN)
                    e.pending.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = c.warn) }
                }
                Spacer(Modifier.height(8.dp))
                Text("البنود", style = MaterialTheme.typography.titleSmall)
                e.items.forEach { it ->
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
                    Text(it.label, style = MaterialTheme.typography.bodyMedium, color = c.txt)
                    Text("القيمة الأصلية: ${it.original}", style = MaterialTheme.typography.bodySmall, color = c.mute)
                    it.rate?.let { r -> Text("السعر: $r — ${it.rateSource ?: ""} — ${it.rateDate ?: ""}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                    it.value?.let { v -> Text("= ${v.replace("\u2066", "").replace("\u2069", "")}", style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), color = c.txt) }
                    it.note?.let { n -> Text(n, style = MaterialTheme.typography.bodySmall, color = c.warn) }
                    if (onOpenRef != null) it.refIds.forEach { id -> TextButton(onClick = { onOpenRef(id) }) { Text("افتح السجل المرتبط") } }
                }
            }
        })
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalR.current
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, c.pri, 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.txt)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.mute, textAlign = TextAlign.Center)
        if (action != null && onAction != null) { Spacer(Modifier.height(12.dp)); SoftButton(action, onAction) }
    }
}

@Composable
fun <T> ChipRow(options: List<T>, selected: T?, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }, modifier = Modifier.heightIn(min = 40.dp),
                shape = RoundedCornerShape(50))
        }
    }
}

@Composable
fun KeyValue(k: String, v: String, modifier: Modifier = Modifier) {
    val c = LocalR.current
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(k, color = c.mute, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(v, color = c.txt, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content), textAlign = TextAlign.End)
    }
}

@Composable
fun LtrBox(content: @Composable () -> Unit) = CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
