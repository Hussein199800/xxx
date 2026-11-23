package app.rasidi.core.backup

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * رمز استعادة 256 بت محمول، مكتوب بـ Base32 (Crockford) في مجموعات من 4 أحرف،
 * مع 4 أحرف تحقق من SHA-256 لاكتشاف أخطاء الإدخال قبل محاولة فك التشفير.
 * مثال: «7K2M-…-Q9XZ» (13 مجموعة بيانات + مجموعة تحقق).
 */
class RecoveryCode private constructor(val bytes: ByteArray) {
    init { require(bytes.size == 32) }

    fun display(): String {
        val body = encode(bytes)
        val check = encode(MessageDigest.getInstance("SHA-256").digest(bytes)).take(4)
        return (body + check).chunked(4).joinToString("-")
    }

    override fun equals(other: Any?) = other is RecoveryCode && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "RecoveryCode(****)"

    companion object {
        private const val ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(rnd: SecureRandom = SecureRandom()) = RecoveryCode(ByteArray(32).also(rnd::nextBytes))
        fun fromBytes(b: ByteArray) = RecoveryCode(b.copyOf())

        /** يقبل مسافات وشرطات وأحرفاً صغيرة، ويصحّح O→0 وI/L→1. يعيد null عند خطأ التحقق. */
        fun parse(text: String): RecoveryCode? {
            val clean = text.uppercase().filter { it.isLetterOrDigit() }
                .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; 'U' -> 'V'; else -> it } }.joinToString("")
            if (clean.length != 56 || clean.any { it !in ALPHA }) return null
            val bytes = decode(clean.take(52)) ?: return null
            if (bytes.size != 32) return null
            val check = encode(MessageDigest.getInstance("SHA-256").digest(bytes)).take(4)
            return if (check == clean.substring(52)) RecoveryCode(bytes) else null
        }

        private fun encode(b: ByteArray): String {
            val sb = StringBuilder(); var buf = 0; var bits = 0
            for (x in b) { buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8; while (bits >= 5) { sb.append(ALPHA[(buf shr (bits - 5)) and 31]); bits -= 5 } }
            if (bits > 0) sb.append(ALPHA[(buf shl (5 - bits)) and 31])
            return sb.toString()
        }

        private fun decode(s: String): ByteArray? {
            val out = java.io.ByteArrayOutputStream(); var buf = 0; var bits = 0
            for (ch in s) { val v = ALPHA.indexOf(ch); if (v < 0) return null; buf = (buf shl 5) or v; bits += 5; if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 } }
            return out.toByteArray()
        }
    }
}
