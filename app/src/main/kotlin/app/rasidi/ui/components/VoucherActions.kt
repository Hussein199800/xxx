package app.rasidi.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rasidi.core.reports.PdfWriter
import app.rasidi.core.reports.ReportBuilders
import app.rasidi.core.voucher.VoucherBuilder
import app.rasidi.reports.ExportService
import app.rasidi.reports.PrintService
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * أزرار السند في تفاصيل الحركة وكشف الحساب: طباعة، مشاركة PDF، مشاركة نصية (واتساب)،
 * و«سند / إشعار» لحركات الديون. القالب والمقاس من «إعدادات السندات» و«إعدادات الطباعة».
 */
@Composable
fun VoucherActions(vm: MainViewModel, txnId: String, compact: Boolean = false) {
    val c = LocalR.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val v = vm.view.collectAsStateWithLifecycle().value ?: return
    val s by vm.settings.collectAsStateWithLifecycle()
    if (v.txnById[txnId] == null) return
    val hasNotice = remember(v, txnId) { VoucherBuilder.hasNotice(v, txnId) }
    var notice by remember { mutableStateOf(false) }
    val opt = s.voucherOptions()
    fun stamp() = LocalDateTime.now().toString().take(16).replace('T', ' ')
    fun doc() = ReportBuilders.voucher(v, txnId, stamp(), opt = opt, asNotice = notice)
    fun run(block: suspend (ByteArray, String) -> Unit) = scope.launch {
        try {
            val d = doc()
            val bytes = withContext(Dispatchers.Default) { PdfWriter.withBundledFonts().write(d) }
            block(bytes, d.fileBase)
        } catch (e: Exception) { vm.message("تعذّر تجهيز السند: ${e.message}") }
    }
    Column {
        if (hasNotice) ChipRow(listOf(false, true), notice, { if (it) "إشعار" else "سند" }, { notice = it })
        if (!compact) Text("${VoucherBuilder.build(v, txnId, opt, "", notice).kind.title} · ورق ${opt.paper.ar}", style = MaterialTheme.typography.bodySmall, color = c.mute, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
            SoftButton("طباعة", { run { b, name -> PrintService.print(ctx, name, b, PrintService.mediaFor(opt.paper)) } }, Modifier.weight(1f), icon = Icons.Filled.Print)
            SoftButton("PDF", { run { b, name -> ctx.startActivity(ExportService.shareIntent(ctx, "$name.pdf", "application/pdf", b)) } }, Modifier.weight(1f), icon = Icons.Filled.PictureAsPdf)
            SoftButton("نص", {
                val text = VoucherBuilder.build(v, txnId, opt, stamp(), notice).shareText()
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "مشاركة السند"))
            }, Modifier.weight(1f), icon = Icons.Filled.Share)
        }
    }
}
