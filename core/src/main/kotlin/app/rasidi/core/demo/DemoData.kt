package app.rasidi.core.demo

import app.rasidi.core.backup.Snapshot
import app.rasidi.core.data.*
import java.time.LocalDate
import kotlin.random.Random

/**
 * بيانات اصطناعية بالكامل للعرض التجريبي والاختبارات وعينات التقارير.
 * لا تمثّل أي شخص حقيقي. تُستخدم في قاعدة العرض المنفصلة عن الدفتر الفعلي.
 */
object DemoData {
    val defaultCategories = listOf(
        CategoryEntity("cat-salary", "راتب", "INCOME", "work", system = true, sort = 1),
        CategoryEntity("cat-freelance", "عمل حر", "INCOME", "laptop", system = true, sort = 2),
        CategoryEntity("cat-gift", "هدية", "INCOME", "gift", system = true, sort = 3),
        CategoryEntity("cat-other-in", "دخل آخر", "INCOME", "plus", system = true, sort = 4),
        CategoryEntity("cat-home", "بيت", "EXPENSE", "home", system = true, sort = 1),
        CategoryEntity("cat-food", "أكل", "EXPENSE", "food", system = true, sort = 2),
        CategoryEntity("cat-transport", "نقل", "EXPENSE", "car", system = true, sort = 3),
        CategoryEntity("cat-health", "صحة", "EXPENSE", "health", system = true, sort = 4),
        CategoryEntity("cat-bills", "فواتير", "EXPENSE", "bill", system = true, sort = 5),
        CategoryEntity("cat-subs", "اشتراكات", "EXPENSE", "subs", system = true, sort = 6),
        CategoryEntity("cat-fees", "رسوم تحويل", "EXPENSE", "fee", system = true, sort = 7),
        CategoryEntity("cat-zakat", "زكاة", "EXPENSE", "zakat", system = true, sort = 8),
        CategoryEntity("cat-other-out", "مصروف آخر", "EXPENSE", "minus", system = true, sort = 9),
    )

    /** دفتر تجريبي: [extraTxns] حركة إضافية اصطناعية لاختبار الأداء. */
    fun snapshot(today: LocalDate = LocalDate.of(2026, 11, 20), extraTxns: Int = 0, seed: Int = 7): Snapshot {
        val accounts = listOf(
            AccountEntity("acc-usd", "نقد دولار", "CASH", "USD", sort = 1),
            AccountEntity("acc-try", "بنك زراعات", "BANK", "TRY", sort = 2),
            AccountEntity("acc-syp", "نقد سوري", "CASH", "SYP", sort = 3),
            AccountEntity("acc-eur", "محفظة يورو", "EWALLET", "EUR", sort = 4),
        )
        val txns = mutableListOf<TxnEntity>(); val entries = mutableListOf<EntryEntity>()
        var n = 0
        fun tx(type: String, date: LocalDate, note: String, vararg es: Triple<String, String, Long>, cat: String? = null, party: String = "", debtId: String? = null, fx: String? = null): String {
            val id = "tx-${++n}"
            val member = when { note.contains("سوق") -> "mem-2"; note.contains("مواصلات") -> "mem-3"; note.contains("راتب") -> "mem-1"; else -> null }
            txns += TxnEntity(id, type, date.toString(), note, party, "req-$id", debtId = debtId, fxRate = fx, createdAtUtc = 1_700_000_000_000 + n, memberId = member)
            es.forEachIndexed { i, (acc, kind, minor) ->
                entries += EntryEntity("$id-e$i", id, acc, kind, minor, accounts.first { it.id == acc }.currency, date.toString(), if (kind in setOf("INCOME", "EXPENSE", "FEE", "ZAKAT")) cat ?: if (kind == "FEE") "cat-fees" else null else null)
            }
            return id
        }
        val start = today.withDayOfYear(1)
        tx("OPENING", start, "رصيد افتتاحي", Triple("acc-usd", "OPENING", 250_000))
        tx("OPENING", start, "رصيد افتتاحي", Triple("acc-try", "OPENING", 2_000_000))
        tx("OPENING", start, "رصيد افتتاحي", Triple("acc-syp", "OPENING", 600_000))
        tx("OPENING", start, "رصيد افتتاحي", Triple("acc-eur", "OPENING", 30_000))
        for (m in 0 until today.monthValue) {
            val d = start.plusMonths(m.toLong())
            tx("INCOME", d.plusDays(0), "راتب شهر ${d.monthValue}", Triple("acc-usd", "INCOME", 90_000), cat = "cat-salary")
            tx("EXPENSE", d.plusDays(2), "إيجار البيت", Triple("acc-try", "EXPENSE", -1_200_000), cat = "cat-home")
            tx("EXPENSE", d.plusDays(5), "سوق الخضار والبقالة", Triple("acc-try", "EXPENSE", -350_000), cat = "cat-food")
            tx("EXPENSE", d.plusDays(9), "مواصلات", Triple("acc-syp", "EXPENSE", -15_000), cat = "cat-transport")
            tx("EXPENSE", d.plusDays(12), "فاتورة الإنترنت", Triple("acc-try", "EXPENSE", -60_000), cat = "cat-bills")
            tx("TRANSFER", d.plusDays(14), "صرف دولار إلى تركي", Triple("acc-usd", "TRANSFER_OUT", -40_000), Triple("acc-try", "TRANSFER_IN", 1_640_000), Triple("acc-usd", "FEE", -200), fx = "41")
            if (m % 3 == 1) tx("INCOME", d.plusDays(18), "مشروع تصميم", Triple("acc-eur", "INCOME", 25_000), cat = "cat-freelance")
        }
        val debts = listOf(
            DebtEntity("debt-1", "أبو سامر", "OWED_TO_ME", 40_000, "USD", start.plusDays(20).toString(), start.plusMonths(14).toString(), "سلفة"),
            DebtEntity("debt-2", "محل الأجهزة", "I_OWE", 3_000_000, "TRY", start.plusMonths(3).toString(), null, "تقسيط هاتف"),
            DebtEntity("debt-3", "خالد", "I_OWE", 20_000, "USD", start.minusYears(1).toString(), start.plusMonths(2).toString(), "دين قديم"),
        )
        val inst = (1..6).map { InstallmentEntity("inst-$it", "debt-2", start.plusMonths(3L + it).toString(), 500_000) }
        val pay1 = tx("DEBT_PAYMENT", start.plusMonths(4), "قسط الهاتف", Triple("acc-try", "DEBT_SETTLEMENT", -500_000), debtId = "debt-2", party = "محل الأجهزة")
        val pay2 = tx("DEBT_PAYMENT", start.plusMonths(5), "قسط الهاتف", Triple("acc-try", "DEBT_SETTLEMENT", -500_000), debtId = "debt-2", party = "محل الأجهزة")
        val pay3 = tx("DEBT_PAYMENT", start.plusMonths(6), "سداد جزئي لخالد بالتركي", Triple("acc-try", "DEBT_SETTLEMENT", -410_000), debtId = "debt-3", party = "خالد")
        val payments = listOf(
            DebtPaymentEntity("dp-1", "debt-2", pay1, start.plusMonths(4).toString(), 500_000, -500_000, "TRY", "acc-try"),
            DebtPaymentEntity("dp-2", "debt-2", pay2, start.plusMonths(5).toString(), 500_000, -500_000, "TRY", "acc-try"),
            DebtPaymentEntity("dp-3", "debt-3", pay3, start.plusMonths(6).toString(), 10_000, -410_000, "TRY", "acc-try"),
        )
        val gold = listOf(GoldHoldingEntity("gold-1", "ليرات ذهب ادخار", 21, "SAVINGS"), GoldHoldingEntity("gold-2", "طقم حلي", 18, "JEWELRY"))
        val goldMoves = listOf(
            GoldMoveEntity("gm-1", "gold-1", "OPENING", "40", start.toString()),
            GoldMoveEntity("gm-2", "gold-2", "OPENING", "25.5", start.toString()),
        )
        val rd = today.toString()
        val rates = listOf(
            RateEntity("rate-try", "TRY", "41", "UNITS_PER_USD", rd, "صرّاف محلي", "LOCAL"),
            RateEntity("rate-syp", "SYP", "118.5", "UNITS_PER_USD", rd, "صرّاف محلي", "LOCAL"),
            RateEntity("rate-eur", "EUR", "1.08", "USD_PER_UNIT", rd, "يدوي"),
            RateEntity("rate-g24", "XAU_G24", "102.4", "USD_PER_UNIT", rd, "صائغ الحي"),
            RateEntity("rate-try-old", "TRY", "38", "UNITS_PER_USD", start.toString(), "صرّاف محلي", "LOCAL"),
        )
        val rnd = Random(seed)
        val cats = listOf("cat-food", "cat-transport", "cat-bills", "cat-health", "cat-subs", "cat-other-out")
        repeat(extraTxns) { i ->
            val d = start.plusDays(rnd.nextLong(0, maxOf(1, java.time.temporal.ChronoUnit.DAYS.between(start, today))))
            if (i % 9 == 0) tx("INCOME", d, "دخل اصطناعي $i", Triple("acc-usd", "INCOME", rnd.nextLong(1_000, 50_000)), cat = "cat-other-in")
            else tx("EXPENSE", d, "مصروف اصطناعي $i", Triple("acc-try", "EXPENSE", -rnd.nextLong(500, 20_000)), cat = cats[i % cats.size])
        }
        return Snapshot(
            accounts = accounts, categories = defaultCategories, txns = txns, entries = entries,
            debts = debts, installments = inst, debtPayments = payments,
            goldHoldings = gold, goldMoves = goldMoves, rates = rates,
            settings = listOf(SettingEntity("ui.theme", "system"), SettingEntity("profile.name", "بيت أبو محمد"), SettingEntity("profile.city", "حلب، سوريا")),
            members = listOf(MemberEntity("mem-1", "أبو محمد", sort = 1), MemberEntity("mem-2", "أم محمد", sort = 2), MemberEntity("mem-3", "محمد", sort = 3)),
            budgets = listOf(
                BudgetEntity("bud-food", "cat-food", "TRY", 400_000),
                BudgetEntity("bud-bills", "cat-bills", "TRY", 100_000),
                BudgetEntity("bud-transport", "cat-transport", "SYP", 20_000),
            ),
            recurring = listOf(
                RecurringEntity("rec-rent", "إيجار البيت", "EXPENSE", "acc-try", "cat-home", 1_200_000, 3),
                RecurringEntity("rec-net", "الإنترنت", "EXPENSE", "acc-try", "cat-bills", 60_000, 13),
                RecurringEntity("rec-salary", "الراتب", "INCOME", "acc-usd", "cat-salary", 90_000, 1, "mem-1"),
            ),
        )
    }
}
