package app.rasidi.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import app.rasidi.backup.BackupService
import app.rasidi.backup.DriveAuth
import app.rasidi.backup.DriveBackupWorker
import app.rasidi.core.backup.BackupFormat
import app.rasidi.core.backup.RecoveryCode
import app.rasidi.core.backup.drive.DriveApi
import app.rasidi.core.backup.drive.DriveBackupEngine
import app.rasidi.core.backup.drive.DriveRestClient
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.Routes
import app.rasidi.ui.components.*
import app.rasidi.ui.theme.LocalR
import app.rasidi.ui.theme.Sp
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

private val countLabels = mapOf("accounts" to "الحسابات", "txns" to "الحركات", "entries" to "القيود", "debts" to "الديون", "debtPayments" to "دفعات الديون",
    "goldHoldings" to "قطع الذهب", "rates" to "الأسعار", "inventories" to "الجرود", "zakatEstimates" to "تقديرات الزكاة", "zakatPayments" to "دفعات الزكاة")

private fun fmtTime(ms: Long) = if (ms <= 0) "—" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime().toString().take(16).replace('T', ' ')

@Composable
fun BackupScreen(vm: MainViewModel, nav: NavController) {
    val c = LocalR.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dev = vm.device
    val container = vm.container
    val demo by vm.demo.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    var newCode by remember { mutableStateOf<RecoveryCode?>(null) }
    var confirmText by remember { mutableStateOf("") }
    var restoreBytes by remember { mutableStateOf<ByteArray?>(null) }
    var restoreSource by remember { mutableStateOf("") }
    var driveList by remember { mutableStateOf<List<DriveApi.FileMeta>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var switchConfirm by remember { mutableStateOf<DriveApi.About?>(null) }
    var unlinkConfirm by remember { mutableStateOf(false) }
    val jobs by container.db.jobs().recent().collectAsStateWithLifecycle(emptyList())
    key(refresh) {}

    // ---- ملف محلي ----
    var pendingFile by remember { mutableStateOf<ByteArray?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val b = pendingFile; pendingFile = null
        if (uri == null) { vm.message("أُلغي الحفظ"); return@rememberLauncherForActivityResult }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(b!!) } }
                // تحقق: أعد قراءة الملف من الوجهة وافتح ترويسته
                val back = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                BackupFormat.readHeader(back); check(back.contentEquals(b))
                dev.lastLocalBackupAt = System.currentTimeMillis(); refresh++
                vm.message("حُفظت النسخة وتحقّقت قراءتها")
            } catch (e: Exception) { vm.message("فشل حفظ النسخة: ${e.message}") }
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val b = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { s -> s.readNBytes((BackupFormat.MAX_FILE_BYTES + 1).toInt()) }
                }
                BackupFormat.readHeader(b)
                restoreBytes = b; restoreSource = "ملف"
            } catch (e: BackupFormat.BackupException) { vm.message(e.message ?: "ملف غير صالح") } catch (e: Exception) { vm.message("تعذّرت قراءة الملف: ${e.message}") }
        }
    }

    // ---- ربط Google ----
    suspend fun finishLink(token: String) {
        try {
            val about = withContext(Dispatchers.IO) { DriveRestClient({ token }).about() }
            val prev = dev.drivePermissionId
            if (prev != null && prev != about.permissionId) { switchConfirm = about; return }
            dev.drivePermissionId = about.permissionId; dev.driveEmail = about.email; dev.driveRelinkNeeded = false
            refresh++; vm.message("رُبط ${about.email}")
        } catch (e: Exception) { vm.message("تعذّر التحقق من الحساب: ${e.message}") }
    }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) { vm.message("أُلغيت الموافقة؛ لم يُربط أي حساب"); return@rememberLauncherForActivityResult }
        try {
            val res = Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(r.data)
            res.accessToken?.let { t -> scope.launch { finishLink(t) } } ?: vm.message("لم يُمنح التفويض")
        } catch (e: Exception) { vm.message("فشل التفويض: ${e.message}") }
    }
    fun link() {
        if (!DriveAuth.playServicesAvailable(ctx)) { vm.message("خدمات Google Play غير متوفرة؛ استخدم النسخ إلى ملف"); return }
        Identity.getAuthorizationClient(ctx).authorize(DriveAuth.request())
            .addOnSuccessListener { res ->
                if (res.hasResolution()) authLauncher.launch(IntentSenderRequest.Builder(res.pendingIntent!!.intentSender).build())
                else res.accessToken?.let { t -> scope.launch { finishLink(t) } }
            }
            .addOnFailureListener { e -> vm.message("تعذّر بدء الربط: ${e.message}") }
    }
    suspend fun <T> withDrive(block: (DriveRestClient) -> T): T? {
        val t = withContext(Dispatchers.IO) { DriveAuth.silentToken(ctx) }
        return when (t) {
            is DriveAuth.Silent.Token -> withContext(Dispatchers.IO) {
                val client = DriveRestClient({ t.value })
                val about = client.about()
                if (about.permissionId != dev.drivePermissionId) { withContext(Dispatchers.Main) { vm.message("الحساب المفوَّض ${about.email} ليس الحساب المربوط") }; null } else block(client)
            }
            DriveAuth.Silent.NeedsUser -> { dev.driveRelinkNeeded = true; refresh++; vm.message("أعد ربط Google"); null }
            is DriveAuth.Silent.Unavailable -> { vm.message(t.reason); null }
        }
    }

    ScreenScaffold("النسخ الاحتياطي", { nav.popBackStack() }, sub = "نسخة مشفّرة كاملة. الاستعادة تحتاج رمز الاستعادة دائماً.") {
        if (demo) { RCard(tint = c.warnSoft) { Text("أنت في الدفتر التجريبي. النسخ يعمل على الدفتر الفعلي فقط.", color = c.warn) }; Spacer(Modifier.height(Sp.sm)) }
        // ---------- رمز الاستعادة ----------
        RCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Key, c.gold); Spacer(Modifier.width(Sp.sm))
                Column(Modifier.weight(1f)) {
                    Text("رمز الاستعادة", style = MaterialTheme.typography.titleMedium)
                    Text(if (dev.hasRecoveryCode() && dev.codeConfirmed) "محفوظ على هذا الجهاز ومؤكَّد" else "مطلوب قبل أول نسخة", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
                StatusChip(if (dev.hasRecoveryCode() && dev.codeConfirmed) "جاهز" else "غير مُعدّ", if (dev.hasRecoveryCode() && dev.codeConfirmed) Tone.OK else Tone.WARN)
            }
            if (newCode == null && !(dev.hasRecoveryCode() && dev.codeConfirmed)) SoftButton("أنشئ رمز استعادة", { newCode = RecoveryCode.generate(); confirmText = "" }, Modifier.fillMaxWidth())
            newCode?.let { code ->
                Text("اكتب هذا الرمز واحفظه خارج الهاتف. بدونه لا يمكن فتح أي نسخة، حتى من حسابك في Google.", color = c.warn, style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(code.display(), fontFamily = FontFamily.Monospace, fontSize = 17.sp, style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr), color = c.txt, modifier = Modifier.padding(vertical = 8.dp)) }
                val clip = LocalClipboardManager.current
                TextButton(onClick = { clip.setText(AnnotatedString(code.display())) }) { Text("انسخ الرمز") }
                TextIn(confirmText, { confirmText = it }, "للتأكيد: اكتب الرمز كاملاً")
                PrimaryButton("أكّدت أني حفظته", enabled = RecoveryCode.parse(confirmText) == code, modifier = Modifier.fillMaxWidth(), onClick = {
                    dev.storeRecoveryCode(code); dev.codeConfirmed = true; newCode = null; refresh++; vm.message("حُفظ رمز الاستعادة مغلّفاً بمفتاح الجهاز")
                })
            }
        }
        // ---------- ملف ----------
        SectionHeader("نسخة ملف")
        RCard {
            KeyValue("آخر نسخة ملف", fmtTime(dev.lastLocalBackupAt))
            Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                PrimaryButton("احفظ نسخة إلى ملف", enabled = !busy && dev.hasRecoveryCode() && dev.codeConfirmed && !demo, icon = Icons.Filled.Save, modifier = Modifier.weight(1f), onClick = {
                    busy = true
                    scope.launch {
                        try { val cr = container.backupService.create(dev.recoveryCode()!!, "MANUAL"); pendingFile = cr.bytes; saveFile.launch(container.backupService.fileName("manual")) }
                        catch (e: Exception) { vm.message("تعذّر إنشاء النسخة: ${e.message}") } finally { busy = false }
                    }
                })
            }
            SoftButton("استعد من ملف", { openFile.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().padding(top = 6.dp), icon = Icons.Filled.FileOpen, enabled = !demo)
        }
        // ---------- Google Drive ----------
        SectionHeader("Google Drive")
        RCard {
            val linked = dev.drivePermissionId != null
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Cloud, c.pri); Spacer(Modifier.width(Sp.sm))
                Column(Modifier.weight(1f)) {
                    Text(if (linked) dev.driveEmail ?: "حساب مربوط" else "غير مربوط", style = MaterialTheme.typography.titleMedium)
                    Text("خزنة التطبيق الخاصة (appDataFolder) فقط", style = MaterialTheme.typography.bodySmall, color = c.mute)
                }
                if (dev.driveRelinkNeeded) StatusChip("أعد الربط", Tone.WARN) else if (linked) StatusChip("مربوط", Tone.OK)
            }
            if (!linked || dev.driveRelinkNeeded) PrimaryButton(if (linked) "أعد ربط Google" else "ربط حساب Google", ::link, Modifier.fillMaxWidth(), icon = Icons.Filled.Link, enabled = !demo)
            if (linked) {
                KeyValue("آخر نسخة متحقّق منها", fmtTime(dev.lastVerifiedAt))
                Row(horizontalArrangement = Arrangement.spacedBy(Sp.xs)) {
                    PrimaryButton("انسخ الآن", { DriveBackupWorker.backupNow(ctx); vm.message("بدأ النسخ؛ يكتمل عند توفر الشبكة") }, Modifier.weight(1f), enabled = dev.hasRecoveryCode() && dev.codeConfirmed && !demo, icon = Icons.Filled.CloudUpload)
                    SoftButton("النسخ في Drive", { scope.launch { busy = true; try { driveList = withDrive { DriveBackupEngine(it, dev.installId).listBackups() } } catch (e: Exception) { vm.message("تعذّر جلب القائمة: ${e.message}") } finally { busy = false } } }, Modifier.weight(1f))
                }
                var auto by remember { mutableStateOf(dev.autoBackup) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(auto, { auto = it; dev.autoBackup = it; DriveBackupWorker.scheduleAuto(ctx, it) }, enabled = dev.hasRecoveryCode() && dev.codeConfirmed)
                    Spacer(Modifier.width(8.dp)); Column { Text("نسخ تلقائي يومي تقريباً"); Text("عند توفر الشبكة وبعد تغيّر البيانات؛ يُبقي آخر 7 نسخ تلقائية", style = MaterialTheme.typography.bodySmall, color = c.mute) }
                }
                TextButton(onClick = { unlinkConfirm = true }) { Text("فصل الحساب", color = c.danger) }
            }
            jobs.take(3).forEach { j -> Text("• ${fmtTime(j.updatedAtUtc)} — ${j.kind} — ${when (j.status) { "VERIFIED" -> "متحقّق"; "QUEUED" -> "بانتظار الشبكة"; "RUNNING" -> "جارٍ"; "FAILED" -> "فشل"; "ACCOUNT_MISMATCH" -> "حساب مختلف"; else -> j.status }}${if (j.lastError.isNotBlank()) " (${j.lastError})" else ""}", style = MaterialTheme.typography.bodySmall, color = c.mute) }
        }
        driveList?.let { list ->
            SectionHeader("النسخ في Drive (${list.size})")
            RCard {
                if (list.isEmpty()) Text("لا نسخ في هذا الحساب", color = c.mute)
                list.forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.createdTime.take(16).replace('T', ' '), style = MaterialTheme.typography.titleSmall)
                            Text("${if (f.appProperties["kind"] == "AUTO") "تلقائية" else "يدوية"} · ${if (f.appProperties["installId"] == dev.installId) "هذا الجهاز" else "جهاز آخر"} · ${f.size / 1024} ك.ب", style = MaterialTheme.typography.bodySmall, color = c.mute)
                        }
                        TextButton(onClick = { scope.launch { busy = true; try { withDrive { it.download(f.id) }?.let { b -> BackupFormat.readHeader(b); restoreBytes = b; restoreSource = "Drive" } } catch (e: Exception) { vm.message("تعذّر التنزيل: ${e.message}") } finally { busy = false } } }) { Text("استعد") }
                    }
                }
            }
        }
        val safety = container.backupService.safetyFiles()
        if (safety.isNotEmpty()) {
            SectionHeader("نسخ الأمان قبل الاستعادة")
            RCard { safety.take(5).forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) { Text(fmtTime(f.lastModified()), Modifier.weight(1f)); TextButton(onClick = { restoreBytes = f.readBytes(); restoreSource = "نسخة أمان" }) { Text("استعد") } } } }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Sp.sm))
    }

    restoreBytes?.let { bytes -> RestoreDialog(vm, bytes, restoreSource) { restoreBytes = null; refresh++ } }
    switchConfirm?.let { a ->
        AlertDialog(onDismissRequest = { switchConfirm = null }, title = { Text("تبديل حساب Google؟") },
            text = { Text("الحساب الجديد ${a.email} يختلف عن المربوط ${dev.driveEmail}. ستتوقف المهام القديمة، وستذهب النسخ القادمة إلى الحساب الجديد. النسخ في الحساب القديم تبقى كما هي.") },
            confirmButton = { TextButton(onClick = { scope.launch { DriveBackupWorker.cancelAll(ctx, container.db.jobs()); dev.drivePermissionId = a.permissionId; dev.driveEmail = a.email; dev.driveRelinkNeeded = false; switchConfirm = null; refresh++ } }) { Text("بدّل") } },
            dismissButton = { TextButton(onClick = { switchConfirm = null }) { Text("إلغاء") } })
    }
    if (unlinkConfirm) AlertDialog(onDismissRequest = { unlinkConfirm = false }, title = { Text("فصل حساب Google؟") },
        text = { Text("ستتوقف مهام النسخ ويُمسح الربط المحلي. يبقى دفترك على الجهاز، وتبقى النسخ الموجودة في Drive حتى تختار حذفها.") },
        confirmButton = { TextButton(onClick = { scope.launch { container.backupLock.withLock { DriveBackupWorker.cancelAll(ctx, container.db.jobs()) }; dev.clearDriveLink(); unlinkConfirm = false; refresh++; vm.message("فُصل الحساب") } }) { Text("افصل") } },
        dismissButton = { TextButton(onClick = { unlinkConfirm = false }) { Text("إلغاء") } })
}

@Composable
private fun RestoreDialog(vm: MainViewModel, bytes: ByteArray, source: String, onDone: () -> Unit) {
    val c = LocalR.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val container = vm.container
    var codeText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<BackupService.Preview?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val header = remember(bytes) { runCatching { BackupFormat.readHeader(bytes) }.getOrNull() }
    AlertDialog(onDismissRequest = { if (!working) onDone() },
        title = { Text(if (preview == null) "استعادة من $source" else "معاينة النسخة") },
        text = {
            Column {
                header?.let { Text("أُنشئت ${fmtTime(it.createdAtUtc)} · ${if (it.kind == "AUTO") "تلقائية" else if (it.kind == "SAFETY") "نسخة أمان" else "يدوية"}", color = c.mute, style = MaterialTheme.typography.bodySmall) }
                if (preview == null) {
                    TextIn(codeText, { codeText = it; error = null }, "رمز الاستعادة")
                    if (codeText.isNotBlank() && RecoveryCode.parse(codeText) == null) Text("الرمز غير مكتمل أو فيه خطأ إدخال", color = c.warn, style = MaterialTheme.typography.bodySmall)
                } else {
                    val p = preview!!
                    Text(if (p.sameAsCurrent) "النسخة مطابقة لسجلك الحالي" else if (p.currentEmpty) "سجلك الحالي فارغ" else "سيُستبدل سجلك الحالي بعد إنشاء نسخة أمان محلية", color = if (p.currentEmpty || p.sameAsCurrent) c.pri else c.warn)
                    Row { Text("", Modifier.weight(1f)); Text("النسخة", Modifier.width(64.dp), color = c.mute); Text("الحالي", Modifier.width(64.dp), color = c.mute) }
                    countLabels.forEach { (k, l) -> Row { Text(l, Modifier.weight(1f)); Text("${p.backupCounts[k] ?: 0}", Modifier.width(64.dp)); Text("${p.currentCounts[k] ?: 0}", Modifier.width(64.dp), color = c.mute) } }
                }
                error?.let { Text(it, color = c.danger) }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (preview == null) TextButton(enabled = !working && RecoveryCode.parse(codeText) != null, onClick = {
                working = true
                scope.launch {
                    try { preview = container.backupService.preview(bytes, RecoveryCode.parse(codeText)!!) }
                    catch (e: BackupFormat.BackupException) { error = e.message } catch (e: Exception) { error = "تعذّر الفتح: ${e.message}" } finally { working = false }
                }
            }) { Text("افتح وتحقّق") }
            else TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    try {
                        val r = container.backupLock.withLock {
                            DriveBackupWorker.cancelAll(ctx, container.db.jobs())
                            container.backupService.restore(preview!!, vm.device.recoveryCode() ?: RecoveryCode.parse(codeText))
                        }
                        when (r) {
                            is BackupService.RestoreResult.Done -> { vm.device.onboarded = true; vm.message("اكتملت الاستعادة وتطابقت البصمة"); onDone() }
                            BackupService.RestoreResult.Stale -> { error = "تغيّر السجل بعد المعاينة؛ أعدنا المعاينة"; preview = container.backupService.preview(bytes, RecoveryCode.parse(codeText)!!) }
                        }
                    } catch (e: Exception) { error = "توقفت الاستعادة دون تغيير السجل: ${e.message}" } finally { working = false }
                }
            }) { Text("استبدل واستعد") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDone) { Text("إلغاء") } })
}
