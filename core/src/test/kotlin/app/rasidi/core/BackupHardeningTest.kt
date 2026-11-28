package app.rasidi.core

import app.rasidi.core.backup.*
import app.rasidi.core.demo.DemoData
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** R13/R14: تقوية فحص النسخ والرمز. */
class BackupHardeningTest {
    private val snap = DemoData.snapshot()
    private fun problems(s: Snapshot) = SnapshotValidator.validate(s)

    @Test fun demoIsValid() = assertTrue(problems(snap).toString(), problems(snap).isEmpty())

    @Test fun invalidEnumsAndLinksDetected() {
        val t = snap.txns.first()
        assertFalse(problems(snap.copy(txns = snap.txns.map { if (it.id == t.id) it.copy(type = "HACK") else it })).isEmpty())
        assertFalse(problems(snap.copy(entries = snap.entries.map { if (it.id == snap.entries.first().id) it.copy(kind = "X") else it })).isEmpty())
        assertFalse(problems(snap.copy(accounts = snap.accounts.map { it.copy(type = "Z") })).isEmpty())
        assertFalse(problems(snap.copy(debts = snap.debts.map { it.copy(direction = "SIDEWAYS") })).isEmpty())
        assertFalse("عكس يشير لحركة مفقودة", problems(snap.copy(txns = snap.txns.map { if (it.id == t.id) it.copy(reversesId = "missing") else it })).isEmpty())
        assertFalse("عكس غير متبادل", problems(snap.copy(txns = snap.txns.map { if (it.id == t.id) it.copy(reversedById = snap.txns.last().id) else it })).isEmpty())
        assertFalse("وحدة سعر مجهولة", problems(snap.copy(rates = snap.rates.map { it.copy(unit = "ZZZ") })).isEmpty())
        assertFalse("نص طويل جداً", problems(snap.copy(txns = snap.txns.map { if (it.id == t.id) it.copy(note = "س".repeat(5000)) else it })).isEmpty())
        assertFalse("أقساط مكررة", problems(snap.copy(installments = snap.installments + snap.installments.first())).isEmpty())
        assertFalse("وزن غير صالح", problems(snap.copy(goldMoves = snap.goldMoves.map { it.copy(grams = "1e999999") })).isEmpty())
    }

    @Test fun badHeaderParamsRejectedSafely() {
        val c = RecoveryCode.generate()
        val file = BackupFormat.encrypt(snap, c, "i", "MANUAL", 1, "t").second
        fun withHeader(edit: (JsonObject) -> JsonObject): ByteArray {
            val len = java.nio.ByteBuffer.wrap(file, 8, 4).int
            val h = Json.parseToJsonElement(String(file, 12, len, Charsets.UTF_8)).jsonObject
            val nh = edit(h).toString().toByteArray()
            return BackupFormat.MAGIC + java.nio.ByteBuffer.allocate(4).putInt(nh.size).array() + nh + file.copyOfRange(12 + len, file.size)
        }
        fun reason(b: ByteArray) = (runCatching { BackupFormat.decrypt(b, c) }.exceptionOrNull() as? BackupFormat.BackupException)?.reason
        assertEquals(BackupFormat.Reason.CORRUPT_HEADER, reason(withHeader { JsonObject(it + ("nonce" to JsonPrimitive("AAAA"))) }))
        assertEquals(BackupFormat.Reason.CORRUPT_HEADER, reason(withHeader { JsonObject(it + ("salt" to JsonPrimitive("%%%"))) }))
        assertEquals(BackupFormat.Reason.CORRUPT_HEADER, reason(withHeader { JsonObject(it + ("cipher" to JsonPrimitive("AES-128-CBC"))) }))
        assertEquals(BackupFormat.Reason.CORRUPT_HEADER, reason(withHeader { JsonObject(it + ("kind" to JsonPrimitive("EVIL"))) }))
    }

    @Test fun parserErrorDoesNotEchoContent() {
        val c = RecoveryCode.generate()
        val secret = "سرّ-مالي-123456"
        val raw = BackupFormat.openRaw(BackupFormat.encrypt(snap, c, "i", "MANUAL", 1, "t").second, c)
        val badSnap = JsonObject(raw["snapshot"]!!.jsonObject + ("accounts" to JsonPrimitive(secret)))
        val bad = JsonObject(raw + ("snapshot" to badSnap) + ("fingerprint" to JsonPrimitive(sha(badSnap.toString()))))
        val e = runCatching { BackupFormat.decrypt(BackupFormat.sealRaw(bad.toString(), BackupFormat.DATA_VERSION, c, "i", "MANUAL", 1, "t"), c) }.exceptionOrNull()
        assertNotNull(e); assertFalse(e!!.message!!.contains(secret))
    }

    @Test fun recoveryCodeBytesAreDefensiveCopies() {
        val c = RecoveryCode.generate()
        val d = c.display()
        c.bytes.fill(0)
        assertEquals(d, c.display())
    }

    private fun sha(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
