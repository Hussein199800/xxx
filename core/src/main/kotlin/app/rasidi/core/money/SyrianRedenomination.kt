package app.rasidi.core.money

import java.math.BigDecimal
import java.time.LocalDate

/**
 * قاعدة تحويل اسمية موثّقة: 100 ليرة سورية قديمة = 1 ليرة جديدة.
 * [effectiveDate] تاريخ النفاذ و[withdrawalEnd] نهاية التداول (إن عُرفت). كلاهما إعداد قابل للتعديل
 * لأن المستخدم يجب أن يطابقه مع إعلان المصرف المركزي.
 *
 * القاعدة تُطبَّق مرة واحدة فقط: المبلغ «القديم» يبقى مخزّناً بوحدته SYP_OLD ولا يُحوَّل عند الاستيراد.
 */
data class SyrianRedenomination(
    val effectiveDate: LocalDate,
    val withdrawalEnd: LocalDate?,
    val factor: BigDecimal = BigDecimal(100),
) {
    enum class OldCashStatus { CURRENT, CONVERTIBLE, SUBJECT_TO_WITHDRAWAL }

    fun status(on: LocalDate): OldCashStatus = when {
        on.isBefore(effectiveDate) -> OldCashStatus.CURRENT
        withdrawalEnd == null || !on.isAfter(withdrawalEnd) -> OldCashStatus.CONVERTIBLE
        else -> OldCashStatus.SUBJECT_TO_WITHDRAWAL
    }

    /** يحوّل مبلغاً قديماً إلى جديد (اسمياً). */
    fun oldToNew(old: Money): Money {
        require(old.currency.code == Currencies.SYP_OLD.code)
        return Money.of(old.amount.divide(factor), Currencies.SYP)
    }

    /**
     * سعر «سوري قديم» بتاريخ بعد النفاذ يُشتق من سعر الجديد: 1 USD = r جديد ⇒ 1 USD = r×100 قديم.
     */
    fun deriveOldRate(newRate: Rate): Rate {
        require(newRate.unit == Currencies.SYP.code)
        return when (newRate.direction) {
            RateDirection.UNITS_PER_USD -> newRate.copy(id = newRate.id + "-old", unit = Currencies.SYP_OLD.code, entered = newRate.entered.multiply(factor), source = newRate.source + " (مشتق ×100)")
            RateDirection.USD_PER_UNIT -> newRate.copy(id = newRate.id + "-old", unit = Currencies.SYP_OLD.code, entered = newRate.entered.divide(factor), source = newRate.source + " (مشتق ÷100)")
        }
    }

    companion object {
        /** القيمة الافتراضية؛ يجب على المستخدم مطابقتها مع المرسوم وإعلان المصرف (انظر docs/rules.md). */
        val DEFAULT = SyrianRedenomination(LocalDate.of(2026, 1, 1), null)
    }
}
