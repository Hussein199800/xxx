package app.rasidi.core.ledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

enum class GoldKind(val ar: String) { SAVINGS("ادخار"), JEWELRY("حلي"), OTHER("غير ذلك") }

data class GoldHolding(
    val id: String,
    val name: String,
    val karat: Int,
    val kind: GoldKind,
    val archived: Boolean = false,
) { init { require(karat in 1..24) { "العيار بين 1 و24" } } }

enum class GoldMoveType(val ar: String) { OPENING("رصيد افتتاحي"), BUY("شراء"), SELL("بيع"), ADJUST("تسوية") }

/** حركة وزن: [grams] صافي من الأحجار، موجب للزيادة وسالب للنقص. */
data class GoldMove(
    val id: String,
    val holdingId: String,
    val type: GoldMoveType,
    val grams: BigDecimal,
    val date: LocalDate,
    val txnId: String? = null,
)

object GoldCalc {
    /** غرامات الخالص = غرامات القطعة × العيار ÷ 24. */
    fun pureGrams(grams: BigDecimal, karat: Int): BigDecimal =
        grams.multiply(BigDecimal(karat)).divide(BigDecimal(24), 18, RoundingMode.HALF_EVEN)

    fun grams(holding: GoldHolding, moves: List<GoldMove>, onOrBefore: LocalDate? = null): BigDecimal =
        moves.filter { it.holdingId == holding.id && (onOrBefore == null || !it.date.isAfter(onOrBefore)) }
            .fold(BigDecimal.ZERO) { a, m -> a + m.grams }
}
