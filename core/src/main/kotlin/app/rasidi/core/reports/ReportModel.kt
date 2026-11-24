package app.rasidi.core.reports

import java.math.BigDecimal
import java.time.LocalDate

/**
 * نموذج تقرير واحد مجمَّد: الشاشة والصيغ الثلاث (XLSX/DOCX/PDF) تقرأ النسخة نفسها
 * بنفس الفلاتر وتاريخ التوليد، فتتطابق الأرقام وعدد البنود.
 */
data class ReportDoc(
    val kind: Kind,
    val title: String,
    /** وصف الفترة أو السجل، مثل «1 نوفمبر 2026 – 30 نوفمبر 2026». */
    val period: String,
    val generatedAt: String,
    /** الفلاتر المطبّقة. */
    val filters: List<Pair<String, String>> = emptyList(),
    /** معلومات إضافية: تاريخ التقييم، مصدر السعر، حالة التقرير، معرّف مراجعة الجرد… */
    val meta: List<Pair<String, String>> = emptyList(),
    val summary: List<Pair<String, Cell>> = emptyList(),
    val tables: List<ReportTable> = emptyList(),
    /** ملاحظات تحذيرية تظهر في كل صيغة (أسعار ناقصة، إجمالي جزئي…). */
    val warnings: List<String> = emptyList(),
    /** اسم الملف دون الامتداد، مثل «رصيدي-كشف-الحساب-2026-11». */
    val fileBase: String,
    /** للسند المفرد: بياناته الكاملة لقالب الطباعة الخاص (PDF) ومقاس الورق. */
    val voucher: app.rasidi.core.voucher.Voucher? = null,
    /** ملاحظة أسفل التقرير عند الطباعة (مثل ملاحظة كشف الحساب). */
    val footerNote: String = "",
    /** الجهة المُصدِرة في ترويسة التقرير (من «البيانات الشخصية»). */
    val issuerName: String = "",
    val issuerLine: String = "",
    /** كتلة «أُعدّ بواسطة / اعتماد» في آخر التقرير الرسمي. */
    val approvalBlock: Boolean = true,
) {
    /** رقم مرجعي ثابت للتقرير من محتواه (العنوان والفترة ووقت الإصدار). */
    val reportNo: String get() {
        val h = java.security.MessageDigest.getInstance("SHA-256").digest("$title|$period|$generatedAt".toByteArray())
        return "RPT-" + h.take(3).joinToString("") { "%02X".format(it) }
    }
    enum class Kind { REPORT, VOUCHER }

    /** نسخة ملخّصة: الجداول بلا صفوف تفاصيل (الإجماليات فقط). */
    fun summaryOnly(): ReportDoc = copy(tables = tables.filter { it.keepInSummary }.map { it })

    val rowCount: Int get() = tables.sumOf { it.rows.size }
}

data class ReportTable(
    val title: String,
    /** اسم الورقة في Excel (≤ 31 حرفاً). */
    val sheetName: String,
    val columns: List<Column>,
    val rows: List<List<Cell>>,
    val totals: List<List<Cell>> = emptyList(),
    val keepInSummary: Boolean = false,
)

data class Column(val header: String, val type: ColType, val weight: Float = 1f)

enum class ColType { TEXT, NUMBER, DATE, CODE }

sealed interface Cell {
    data class Text(val v: String) : Cell
    /** رقم دقيق مع عدد الخانات للعرض. */
    data class Num(val v: BigDecimal, val digits: Int = 2) : Cell
    data class Date(val v: LocalDate) : Cell
    data object Empty : Cell

    companion object {
        fun t(s: String?) = if (s.isNullOrEmpty()) Empty else Text(s)
        fun n(v: BigDecimal?, d: Int = 2) = if (v == null) Empty else Num(v, d)
        fun d(v: LocalDate?) = if (v == null) Empty else Date(v)
    }
}

/** نص عرض موحّد للخلية (للـ PDF وWord). */
fun Cell.display(): String = when (this) {
    is Cell.Text -> v
    is Cell.Num -> app.rasidi.core.money.Fmt.number(v, digits)
    is Cell.Date -> v.toString()
    Cell.Empty -> ""
}

object FileNames {
    /** اسم ملف آمن واضح: يستبدل المسافات بشرطة ويحذف المحارف غير المسموحة. */
    fun safe(base: String): String = base.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "").replace(Regex("\\s+"), "-")
}
