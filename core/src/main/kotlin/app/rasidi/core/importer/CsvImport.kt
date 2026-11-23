package app.rasidi.core.importer

import app.rasidi.core.money.AmountParser
import app.rasidi.core.money.Currencies
import app.rasidi.core.money.CurrencyDef
import app.rasidi.core.money.Money
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * استيراد حركات من CSV (مسار منفصل عن استعادة دفتر كامل).
 * القالب: date,type,amount,currency,account,category,note,party,id
 * - type: income/expense أو دخل/مصروف.
 * - «SYP» أو «ل.س» ملتبسة بين القديمة والجديدة: يحددها المستخدم في المعاينة ([Options.sypMeansOld]).
 * - منع التكرار: بصمة الدفعة (SHA-256 للملف) ومعرّف كل صف؛ إعادة الدفعة نفسها لا تُكرّر شيئاً.
 */
object CsvImport {
    val TEMPLATE_HEADER = listOf("date", "type", "amount", "currency", "account", "category", "note", "party", "id")
    val TEMPLATE = TEMPLATE_HEADER.joinToString(",") + "\n2026-11-01,expense,125.50,TRY,بنك زراعات,أكل,سوق,,old-1\n2026-11-02,income,900,USD,نقد دولار,راتب,راتب نوفمبر,,old-2\n"

    data class Options(
        val sypMeansOld: Boolean = false,
        /** خريطة رؤوس الأعمدة: اسم المفتاح في القالب → اسم العمود في الملف. */
        val columns: Map<String, String> = TEMPLATE_HEADER.associateWith { it },
        val dateFormats: List<String> = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "yyyy/MM/dd"),
    )

    data class Row(
        val line: Int, val date: LocalDate, val income: Boolean, val amount: Money, val account: String,
        val category: String, val note: String, val party: String, val externalId: String?,
        /** معرّف الطلب الثابت لهذا الصف: يمنع تكراره عند إعادة الاستيراد. */
        val requestId: String,
    )

    data class Invalid(val line: Int, val reason: String, val raw: String)

    data class Preview(val batchHash: String, val rows: List<Row>, val invalid: List<Invalid>, val delimiter: Char, val headers: List<String>) {
        fun totals(): Map<String, Pair<Money, Money>> = rows.groupBy { it.amount.currency.code }.mapValues { (c, l) ->
            val cur = Currencies.of(c)
            l.filter { it.income }.fold(Money.zero(cur)) { a, r -> a + r.amount } to l.filter { !it.income }.fold(Money.zero(cur)) { a, r -> a + r.amount }
        }
    }

    fun batchHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun parse(bytes: ByteArray, opt: Options = Options()): Preview {
        var text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        val hash = batchHash(bytes)
        val lines = splitRecords(text)
        if (lines.isEmpty()) return Preview(hash, emptyList(), listOf(Invalid(0, "الملف فارغ", "")), ',', emptyList())
        val delim = listOf(',', ';', '\t').maxBy { d -> lines.first().count { it == d } }
        val header = fields(lines.first(), delim).map { it.trim().lowercase() }
        fun idx(k: String) = header.indexOf((opt.columns[k] ?: k).lowercase())
        val iDate = idx("date"); val iType = idx("type"); val iAmt = idx("amount"); val iCur = idx("currency"); val iAcc = idx("account")
        val iCat = idx("category"); val iNote = idx("note"); val iParty = idx("party"); val iId = idx("id")
        if (listOf(iDate, iType, iAmt, iCur, iAcc).any { it < 0 })
            return Preview(hash, emptyList(), listOf(Invalid(1, "أعمدة ناقصة: يلزم date,type,amount,currency,account", lines.first())), delim, header)
        val rows = mutableListOf<Row>(); val bad = mutableListOf<Invalid>()
        val seenIds = HashSet<String>()
        lines.drop(1).forEachIndexed { n, raw ->
            val ln = n + 2
            if (raw.isBlank()) return@forEachIndexed
            val f = fields(raw, delim)
            fun g(i: Int) = if (i in f.indices) f[i].trim() else ""
            val date = opt.dateFormats.firstNotNullOfOrNull { p -> runCatching { LocalDate.parse(g(iDate), DateTimeFormatter.ofPattern(p)) }.getOrNull() }
                ?: return@forEachIndexed run { bad += Invalid(ln, "تاريخ غير مفهوم: ${g(iDate)}", raw) }
            val type = g(iType).lowercase()
            val income = when (type) { "income", "in", "دخل", "قبض" -> true; "expense", "out", "مصروف", "صرف" -> false; else -> return@forEachIndexed run { bad += Invalid(ln, "نوع غير معروف: ${g(iType)}", raw) } }
            val curText = g(iCur).uppercase().replace(" ", "")
            val cur: CurrencyDef = when (curText) {
                "SYP", "ل.س", "ليرةسورية" -> if (opt.sypMeansOld) Currencies.SYP_OLD else Currencies.SYP
                "SYP_OLD", "ل.س.ق" -> Currencies.SYP_OLD
                "TL", "ل.ت" -> Currencies.TRY
                "$" -> Currencies.USD
                else -> Currencies.find(curText) ?: return@forEachIndexed run { bad += Invalid(ln, "عملة غير معروفة: ${g(iCur)}", raw) }
            }
            val amt = AmountParser.parseMoney(g(iAmt), cur)?.abs()
                ?: return@forEachIndexed run { bad += Invalid(ln, "مبلغ غير صالح: ${g(iAmt)}", raw) }
            if (amt.isZero) return@forEachIndexed run { bad += Invalid(ln, "مبلغ صفري", raw) }
            if (g(iAcc).isBlank()) return@forEachIndexed run { bad += Invalid(ln, "الحساب فارغ", raw) }
            val ext = g(iId).ifBlank { null }
            if (ext != null && !seenIds.add(ext)) return@forEachIndexed run { bad += Invalid(ln, "معرّف مكرر داخل الملف: $ext", raw) }
            // المعرّف الأصلي يمنع التكرار بين دفعات مختلفة؛ بدونه بصمة الدفعة + رقم السطر تمنع إعادة الدفعة نفسها
            val rid = if (ext != null) "import:id:$ext" else "import:$hash:$ln"
            rows += Row(ln, date, income, amt, g(iAcc), g(iCat), g(iNote), g(iParty), ext, rid)
        }
        return Preview(hash, rows, bad, delim, header)
    }

    /** يقسم السجلات مع احترام الاقتباس (أسطر داخل حقول مقتبسة). */
    private fun splitRecords(t: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (ch in t) { if (ch == '"') q = !q; if ((ch == '\n') && !q) { out += sb.toString().trimEnd('\r'); sb.clear() } else sb.append(ch) }
        if (sb.isNotBlank()) out += sb.toString().trimEnd('\r')
        return out.filter { it.isNotBlank() }
    }

    fun fields(line: String, d: Char): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                ch == '"' -> q = !q
                ch == d && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
