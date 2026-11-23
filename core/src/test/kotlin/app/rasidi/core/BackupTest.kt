package app.rasidi.core

import app.rasidi.core.backup.*
import app.rasidi.core.demo.DemoData
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val snap = DemoData.snapshot()

    @Test fun roundTripPreservesEverything() {
        val code = RecoveryCode.generate()
        val (h, file) = BackupFormat.encrypt(snap, code, "install-1", "MANUAL", 1_800_000_000_000, "1.0")
        assertEquals(BackupFormat.FORMAT_VERSION, h.format)
        val opened = BackupFormat.decrypt(file, RecoveryCode.parse(code.display())!!)
        assertEquals(snap.fingerprint(), opened.payload.snapshot.fingerprint())
        assertEquals(snap.counts(), opened.payload.snapshot.counts())
        assertEquals(snap.balances(), opened.payload.snapshot.balances())
        assertTrue(SnapshotValidator.validate(snap).isEmpty())
    }

    @Test fun uniqueNoncePerEncryption() {
        val code = RecoveryCode.generate()
        val a = BackupFormat.encrypt(snap, code, "i", "AUTO", 1, "1").first
        val b = BackupFormat.encrypt(snap, code, "i", "AUTO", 1, "1").first
        assertNotEquals(a.nonce, b.nonce); assertNotEquals(a.backupId, b.backupId)
    }

    @Test fun wrongCodeTamperAndCorruptionStopBeforeWrite() {
        val code = RecoveryCode.generate()
        val file = BackupFormat.encrypt(snap, code, "i", "MANUAL", 1, "1").second
        fun reason(block: () -> Unit) = try { block(); null } catch (e: BackupFormat.BackupException) { e.reason }
        assertEquals(BackupFormat.Reason.WRONG_CODE_OR_TAMPERED, reason { BackupFormat.decrypt(file, RecoveryCode.generate()) })
        val tampered = file.copyOf().also { it[it.size - 40] = (it[it.size - 40] + 1).toByte() }
        assertEquals(BackupFormat.Reason.WRONG_CODE_OR_TAMPERED, reason { BackupFormat.decrypt(tampered, code) })
        // تعديل الترويسة (AAD) يُكتشف
        val hdr = String(file, 12, 60, Charsets.UTF_8)
        val idx = hdr.indexOf("MANUAL")
        if (idx >= 0) {
            val t2 = file.copyOf(); "AUTO__".toByteArray().copyInto(t2, 12 + idx)
            assertNotNull(reason { BackupFormat.decrypt(t2, code) })
        }
        assertEquals(BackupFormat.Reason.NOT_RASIDI, reason { BackupFormat.decrypt("hello world, not a backup".toByteArray(), code) })
        assertEquals(BackupFormat.Reason.CORRUPT_HEADER, reason { BackupFormat.decrypt(file.copyOf(30), code) })
    }

    @Test fun newerFormatRejected() {
        val code = RecoveryCode.generate()
        val file = BackupFormat.encrypt(snap, code, "i", "MANUAL", 1, "1").second
        val s = String(file, Charsets.ISO_8859_1).replaceFirst("\"format\":1", "\"format\":9")
        val e = runCatching { BackupFormat.decrypt(s.toByteArray(Charsets.ISO_8859_1), code) }.exceptionOrNull() as BackupFormat.BackupException
        assertEquals(BackupFormat.Reason.NEWER_FORMAT, e.reason)
        val s2 = String(file, Charsets.ISO_8859_1).replaceFirst("\"dataVersion\":${BackupFormat.DATA_VERSION}", "\"dataVersion\":9")
        val e2 = runCatching { BackupFormat.readHeader(s2.toByteArray(Charsets.ISO_8859_1)) }.exceptionOrNull() as BackupFormat.BackupException
        assertEquals(BackupFormat.Reason.NEWER_DATA, e2.reason)
    }

    @Test fun recoveryCodeParsingAndChecksum() {
        val c = RecoveryCode.generate()
        val text = c.display()
        assertEquals(14, text.split("-").size)
        assertEquals(c, RecoveryCode.parse(text.lowercase().replace("-", " ")))
        val typo = text.toCharArray().also { it[0] = if (it[0] == 'A') 'B' else 'A' }.concatToString()
        assertNull(RecoveryCode.parse(typo))
        assertNull(RecoveryCode.parse("short"))
    }

    @Test fun validatorCatchesBrokenRelations() {
        val broken = snap.copy(entries = snap.entries + snap.entries.first().copy(id = "x", accountId = "missing"))
        assertTrue(SnapshotValidator.validate(broken).isNotEmpty())
        val overpaid = snap.copy(debtPayments = snap.debtPayments + snap.debtPayments.first().copy(id = "dp-x", debtAmountMinor = 99_999_999))
        assertTrue(SnapshotValidator.validate(overpaid).any { it.contains("تتجاوز") })
        val secret = snap.copy(settings = snap.settings + app.rasidi.core.data.SettingEntity("drive.token", "x"))
        assertTrue(SnapshotValidator.validate(secret).isNotEmpty())
    }

    @Test fun fingerprintIndependentOfRowOrder() {
        assertEquals(snap.fingerprint(), snap.copy(entries = snap.entries.reversed()).fingerprint())
    }

    @Test fun backupMigrationSameVersionIsIdentity() {
        assertEquals("{}", BackupMigrations.upgrade(BackupFormat.DATA_VERSION, "{}"))
    }
}
