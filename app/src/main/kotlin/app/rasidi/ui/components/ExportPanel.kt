package app.rasidi.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rasidi.core.reports.ReportDoc
import app.rasidi.core.reports.display
import app.rasidi.reports.ExportFormat
import app.rasidi.reports.ExportService
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * لوحة التصدير: مراجعة عدد الصفوف والإجماليات، ثم الصيغة، ثم «حفظ الملف» أو «مشاركة».
 * [build] يبني النموذج المجمَّد مرة واحدة لكل اختيار؛ الصيغ الثلاث تقرأ النسخة نفسها.
 */
@Composable
fun ExportPanel(vm: MainViewModel, doc: ReportDoc?, showPrivacy: Boolean = true, hideNames: Boolean, hideNotes: Boolean, onPrivacy: (Boolean, Boolean) -> Unit) {
    val c = LocalR.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var format by remember { mutableStateOf(ExportFormat.PDF) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(format.mime)) { uri ->
        val bytes = pending; pending = null
        if (uri == null) { vm.message("أُلغي الحفظ؛ لم يُكتب أي ملف"); return@rememberLauncherForActivityResult }
        scope.launch {
            try { withContext(Dispatchers.IO) { ExportService.writeTo(ctx, uri, bytes!!) }; vm.message("حُفظ الملف") }
            catch (e: Exception) { vm.message("فشل الحفظ: ${e.message}") }
        }
    }
    RCard {
        if (doc == null) { Text("اختر التقرير أولاً", color = c.mute); return@RCard }
        Text("المراجعة قبل التصدير", style = MaterialTheme.typography.titleMedium, color = c.txt)
        KeyValue("الفترة", doc.period)
        KeyValue("عدد الصفوف", doc.rowCount.toString())
        doc.summary.take(6).forEach { (k, v) -> KeyValue(k, v.display()) }
        doc.warnings.forEach { Text("⚠ $it", color = c.warn, style = MaterialTheme.typography.bodySmall) }
        if (showPrivacy) {
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hideNames, { onPrivacy(it, hideNotes) }); Text("إخفاء الأسماء") }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(hideNotes, { onPrivacy(hideNames, it) }); Text("إخفاء الملاحظات") }
        }
        Spacer(Modifier.height(Sp.xs))
        ChipRow(ExportFormat.values().toList(), format, { it.label }, { format = it })
        Text("الاسم: ${ExportService.fileName(doc, format)}", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            PrimaryButton("حفظ الملف", enabled = !busy, icon = Icons.Filled.Save, modifier = Modifier.weight(1f), onClick = {
                busy = true
                scope.launch {
                    try { pending = withContext(Dispatchers.Default) { ExportService.render(doc, format) }; saver.launch(ExportService.fileName(doc, format)) }
                    catch (e: Exception) { vm.message("فشل التوليد: ${e.message}") } finally { busy = false }
                }
            })
            SoftButton("مشاركة", enabled = !busy, icon = Icons.Filled.Share, modifier = Modifier.weight(1f), onClick = {
                busy = true
                scope.launch {
                    try {
                        val bytes = withContext(Dispatchers.Default) { ExportService.render(doc, format) }
                        ctx.startActivity(ExportService.shareIntent(ctx, ExportService.fileName(doc, format), format.mime, bytes))
                    } catch (e: Exception) { vm.message("تعذّرت المشاركة: ${e.message}") } finally { busy = false }
                }
            })
        }
    }
}
