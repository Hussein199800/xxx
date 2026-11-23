package app.rasidi.reports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.rasidi.core.reports.*
import java.io.File

/** صيغ التصدير مع نوع MIME الصحيح لمنتقي Android. */
enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word"),
    PDF("pdf", "application/pdf", "PDF"),
    CSV("csv", "text/csv", "CSV"),
}

/** يولّد الملفات من نموذج التقرير المجمَّد نفسه، محلياً بالكامل وخارج مسار الواجهة. */
object ExportService {
    fun render(doc: ReportDoc, f: ExportFormat): ByteArray = when (f) {
        ExportFormat.XLSX -> XlsxWriter.write(doc)
        ExportFormat.DOCX -> DocxWriter.write(doc)
        ExportFormat.PDF -> PdfWriter.withBundledFonts().write(doc)
        ExportFormat.CSV -> ReportBuilders.csv(doc.tables.maxByOrNull { it.rows.size } ?: ReportTable("", "", emptyList(), emptyList()))
    }

    fun fileName(doc: ReportDoc, f: ExportFormat) = "${doc.fileBase}.${f.ext}"

    /** يكتب إلى Uri اختاره المستخدم عبر منتقي الملفات (CreateDocument). */
    fun writeTo(ctx: Context, uri: Uri, bytes: ByteArray) {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: throw IllegalStateException("تعذّر فتح الوجهة")
    }

    /** ملف مؤقت خاص للمشاركة بصلاحية قراءة مؤقتة. يُنظَّف الأقدم من ساعة. */
    fun shareIntent(ctx: Context, name: String, mime: String, bytes: ByteArray): Intent {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val f = File(dir, name).apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "مشاركة $name")
    }
}
