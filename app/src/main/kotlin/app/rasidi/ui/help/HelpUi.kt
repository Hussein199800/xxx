package app.rasidi.ui.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp

/** الشرح: مجموعات بعناوين، وكل بند بطاقة تُفتح بلمسة. */
@Composable
fun GuideList(openFirst: String? = null) {
    val c = LocalR.current
    var open by remember { mutableStateOf(openFirst) }
    GUIDE.forEach { (group, items) ->
        Text(group, style = MaterialTheme.typography.titleLarge, color = c.txt, modifier = Modifier.padding(top = Sp.lg, bottom = Sp.xs, start = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(Sp.xs)) {
            items.forEach { it ->
                val on = open == it.title
                RCard(onClick = { open = if (on) null else it.title }) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { stateDescription = if (on) "مفتوح" else "مغلق" }) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(it.tint), contentAlignment = Alignment.Center) { Icon(it.icon, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                        Spacer(Modifier.width(Sp.sm))
                        Text(it.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Icon(if (on) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = c.mute)
                    }
                    AnimatedVisibility(on) {
                        Column(Modifier.padding(top = Sp.sm)) {
                            it.steps.forEachIndexed { i, s ->
                                Row(Modifier.padding(vertical = 3.dp)) {
                                    Box(Modifier.size(22.dp).clip(CircleShape).background(c.priSoft), contentAlignment = Alignment.Center) { Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = c.pri) }
                                    Spacer(Modifier.width(8.dp)); Text(s, style = MaterialTheme.typography.bodyMedium, color = c.txt)
                                }
                            }
                            it.paras.forEach { p -> Text(p, style = MaterialTheme.typography.bodyMedium, color = c.txt, modifier = Modifier.padding(vertical = 3.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** الجولة التعريفية: خطوات بنقاط، «السابق / التالي / تخطي». */
@Composable
fun TourDialog(onClose: () -> Unit) {
    val c = LocalR.current
    var i by remember { mutableIntStateOf(0) }
    val s = TOUR[i]; val last = i == TOUR.lastIndex
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(28.dp)).background(c.surface).padding(Sp.lg).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!last) TextButton(onClick = onClose, modifier = Modifier.align(Alignment.Start)) { Text("تخطي", color = c.mute) } else Spacer(Modifier.height(40.dp))
            Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(s.tint), contentAlignment = Alignment.Center) { Icon(s.icon, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
            Spacer(Modifier.height(Sp.md))
            Text(s.title, style = MaterialTheme.typography.headlineSmall, color = c.txt, textAlign = TextAlign.Center)
            s.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = c.mute, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(Sp.md))
            Row(Modifier.semantics { contentDescription = "الخطوة ${i + 1} من ${TOUR.size}" }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TOUR.indices.forEach { k -> Box(Modifier.size(if (k == i) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (k == i) c.pri else c.line)) }
            }
            Spacer(Modifier.height(Sp.md))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                if (i > 0) OutlinedButton(onClick = { i-- }, modifier = Modifier.heightIn(min = 48.dp)) { Text("السابق") } else Spacer(Modifier.width(1.dp))
                PrimaryButton(if (last) "ابدأ" else "التالي", { if (last) onClose() else i++ })
            }
        }
    }
}

/** «ما الجديد» بعد التحديث، أو صفحة كل الإصدارات. */
@Composable
fun ReleaseCard(r: Release, highlight: Boolean = false) {
    val c = LocalR.current
    RCard(tint = if (highlight) c.priSoft else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("الإصدار ${r.version}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            StatusChip(r.date, Tone.INFO)
        }
        r.items.forEach { Row(Modifier.padding(top = 6.dp)) { Text("•", color = c.pri); Spacer(Modifier.width(6.dp)); Text(it, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@Composable
fun WhatsNewDialog(version: String, onClose: () -> Unit) {
    val r = CHANGELOG.firstOrNull { version.startsWith(it.version) } ?: CHANGELOG.first()
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("حسناً") } },
        title = { Text("ما الجديد في الإصدار ${r.version}") },
        text = { Column { r.items.forEach { Row(Modifier.padding(vertical = 3.dp)) { Text("•"); Spacer(Modifier.width(6.dp)); Text(it) } } } })
}
