package app.rasidi.core.money

/**
 * عملة مدعومة. [minorDigits] عدد خانات الوحدة الصغرى المخزّنة كعدد صحيح.
 * الرمز [code] داخلي وثابت ولا يتغيّر بعد الحفظ. «سوري قديم» و«سوري جديد» وحدتان منفصلتان.
 */
data class CurrencyDef(
    val code: String,
    val nameAr: String,
    val symbol: String,
    val minorDigits: Int,
)

object Currencies {
    val USD = CurrencyDef("USD", "دولار أمريكي", "\$", 2)
    val TRY = CurrencyDef("TRY", "ليرة تركية", "TL", 2)
    val EUR = CurrencyDef("EUR", "يورو", "€", 2)
    /** الليرة السورية الجديدة (بعد حذف صفرين). */
    val SYP = CurrencyDef("SYP", "ليرة سورية جديدة", "ل.س", 2)
    /** الليرة السورية القديمة (قبل حذف الصفرين). وحدة داخلية منفصلة. */
    val SYP_OLD = CurrencyDef("SYP_OLD", "ليرة سورية قديمة", "ل.س.ق", 0)

    /** قائمة موثّقة يمكن للمستخدم إضافة عملة منها (ISO 4217). */
    val catalog: List<CurrencyDef> = listOf(
        USD, TRY, EUR, SYP, SYP_OLD,
        CurrencyDef("SAR", "ريال سعودي", "ر.س", 2),
        CurrencyDef("AED", "درهم إماراتي", "د.إ", 2),
        CurrencyDef("GBP", "جنيه إسترليني", "£", 2),
        CurrencyDef("JOD", "دينار أردني", "د.أ", 3),
        CurrencyDef("IQD", "دينار عراقي", "د.ع", 0),
        CurrencyDef("EGP", "جنيه مصري", "ج.م", 2),
        CurrencyDef("LBP", "ليرة لبنانية", "ل.ل", 0),
        CurrencyDef("KWD", "دينار كويتي", "د.ك", 3),
        CurrencyDef("QAR", "ريال قطري", "ر.ق", 2),
    )

    /** الوحدة الحسابية للجرد. */
    val BASE = USD

    fun of(code: String): CurrencyDef =
        catalog.firstOrNull { it.code == code } ?: throw IllegalArgumentException("عملة غير معروفة: $code")

    fun find(code: String): CurrencyDef? = catalog.firstOrNull { it.code == code }
}
