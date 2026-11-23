package app.rasidi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp

/** حالة الدفتر في الشريط العلوي (مثل حالة الجهاز في طاقتي): اسم، وسطر حالة، ونقطة ملوّنة. */
data class LedgerStatus(val name: String, val sub: String, val tone: Tone)

@Composable
fun StatusDot(tone: Tone, size: Int = 10) {
    val c = LocalR.current
    val col = when (tone) { Tone.OK -> c.pri; Tone.WARN -> c.warn; Tone.DANGER -> c.danger; Tone.GOLD -> c.gold; Tone.INFO -> c.mute }
    Box(Modifier.size(size.dp).clip(CircleShape).background(col))
}

/** شريط التبويبات الرئيسية: الشعار والاسم، كتلة الحالة، وزر سريع. */
@Composable
fun TabTopBar(status: LedgerStatus, onStatus: () -> Unit, actionIcon: ImageVector, actionDesc: String, onAction: () -> Unit) {
    val c = LocalR.current
    Row(Modifier.fillMaxWidth().background(c.bg.copy(alpha = 0.92f)).statusBarsPadding().padding(horizontal = Sp.md, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.pri), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.AccountBalanceWallet, null, tint = c.priInk, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text("رصيدي", style = MaterialTheme.typography.titleMedium, color = c.pri)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onStatus).heightIn(min = 48.dp).padding(horizontal = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = "${status.name}، ${status.sub}" }, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(status.name, style = MaterialTheme.typography.labelLarge, color = c.txt, maxLines = 1)
                Text(status.sub, style = MaterialTheme.typography.labelSmall, color = c.mute, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 170.dp))
            }
            Spacer(Modifier.width(8.dp))
            StatusDot(status.tone)
        }
        IconButton(onClick = onAction, modifier = Modifier.size(48.dp).clip(CircleShape)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.card).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(actionIcon, actionDesc, tint = c.txt, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** شريط الصفحات الفرعية: رجوع، عنوان الصفحة في الوسط، نقطة الحالة. */
@Composable
fun PageTopBar(title: String, onBack: (() -> Unit)?, tone: Tone = Tone.OK, actions: @Composable RowScope.() -> Unit = {}) {
    val c = LocalR.current
    Row(Modifier.fillMaxWidth().background(c.bg.copy(alpha = 0.92f)).statusBarsPadding().padding(horizontal = Sp.xs, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(c.card).border(1.dp, c.line, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = c.txt, modifier = Modifier.size(20.dp))
            }
        } else Spacer(Modifier.width(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.txt, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { StatusDot(tone, 9) }
    }
}

/** ألوان البطاقات كما في طاقتي. */
object CardTint {
    val green = Color(0xFF1F9D63); val pink = Color(0xFFD6457A); val sky = Color(0xFF2B8FD6)
    val amber = Color(0xFFD99A1E); val violet = Color(0xFF7C5CD6); val slate = Color(0xFF64748B); val teal = Color(0xFF14A3A3)
}

/** بطاقة قائمة: أيقونة في مربع ملوّن + عنوان + سطر حالة + شارة اختيارية. */
@Composable
fun ListCard(title: String, sub: String, icon: ImageVector, tint: Color, badge: String? = null, badgeTone: Tone = Tone.GOLD, onClick: () -> Unit) {
    val c = LocalR.current
    RCard(onClick = onClick, contentDesc = "$title، $sub") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(15.dp)).background(tint), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(Sp.sm))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = c.txt)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = c.mute, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) StatusChip(badge, badgeTone)
            Icon(Icons.Filled.ChevronLeft, null, tint = c.mute)
        }
    }
}

/** اختصار ملوّن (أيقونة + كلمتان) لصف الاختصارات في «المزيد». */
@Composable
fun Shortcut(label: String, icon: ImageVector, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalR.current
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(if (c.dark) c.cardHi else c.card).border(1.dp, c.line, RoundedCornerShape(20.dp))
        .clickable(onClick = onClick).heightIn(min = 92.dp).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.txt, textAlign = TextAlign.Center, maxLines = 2)
    }
}
