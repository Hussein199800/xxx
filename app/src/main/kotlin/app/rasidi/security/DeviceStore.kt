package app.rasidi.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.rasidi.core.backup.RecoveryCode
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * إعدادات خاصة بهذا الجهاز وهذا التثبيت (لا تدخل في النسخة الاحتياطية):
 * معرّف التثبيت، رمز الاستعادة مغلّفاً بمفتاح Android Keystore، ربط Drive، القفل، وضع العرض.
 */
class DeviceStore(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("rasidi.device", Context.MODE_PRIVATE)

    val installId: String get() = p.getString("installId", null) ?: UUID.randomUUID().toString().also { p.edit().putString("installId", it).apply() }

    var demoMode: Boolean get() = p.getBoolean("demoMode", false); set(v) = p.edit().putBoolean("demoMode", v).apply()
    var onboarded: Boolean get() = p.getBoolean("onboarded", false); set(v) = p.edit().putBoolean("onboarded", v).apply()
    var appLock: Boolean get() = p.getBoolean("appLock", false); set(v) = p.edit().putBoolean("appLock", v).apply()
    var autoBackup: Boolean get() = p.getBoolean("autoBackup", false); set(v) = p.edit().putBoolean("autoBackup", v).apply()
    var codeConfirmed: Boolean get() = p.getBoolean("codeConfirmed", false); set(v) = p.edit().putBoolean("codeConfirmed", v).apply()

    // ربط Google Drive: الحساب المفوَّض مثبّت بـ permissionId
    var drivePermissionId: String? get() = p.getString("drive.permissionId", null); set(v) = p.edit().putString("drive.permissionId", v).apply()
    var driveEmail: String? get() = p.getString("drive.email", null); set(v) = p.edit().putString("drive.email", v).apply()
    var driveRelinkNeeded: Boolean get() = p.getBoolean("drive.relink", false); set(v) = p.edit().putBoolean("drive.relink", v).apply()
    var lastVerifiedAt: Long get() = p.getLong("drive.lastVerifiedAt", 0); set(v) = p.edit().putLong("drive.lastVerifiedAt", v).apply()
    var lastVerifiedVersion: Long get() = p.getLong("drive.lastVerifiedVersion", -1); set(v) = p.edit().putLong("drive.lastVerifiedVersion", v).apply()
    var lastLocalBackupAt: Long get() = p.getLong("local.lastBackupAt", 0); set(v) = p.edit().putLong("local.lastBackupAt", v).apply()
    /** عدّاد كتابة الدفتر المحلي (إصدار السجل) لربط مهام النسخ بإصدار محدد. */
    var ledgerVersion: Long get() = p.getLong("ledger.version", 0); set(v) = p.edit().putLong("ledger.version", v).apply()
    fun bumpLedgerVersion() { ledgerVersion = ledgerVersion + 1 }

    fun clearDriveLink() { p.edit().remove("drive.permissionId").remove("drive.email").remove("drive.relink").putBoolean("autoBackup", false).apply() }

    // ---------- رمز الاستعادة مغلّفاً بـ Keystore ----------
    fun hasRecoveryCode() = p.contains("recovery.wrapped")

    fun storeRecoveryCode(code: RecoveryCode) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(code.bytes)
        p.edit().putString("recovery.wrapped", Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)).apply()
    }

    fun recoveryCode(): RecoveryCode? {
        val s = p.getString("recovery.wrapped", null) ?: return null
        return try {
            val b = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12))
            RecoveryCode.fromBytes(c.doFinal(b, 12, b.size - 12))
        } catch (e: Exception) { null }
    }

    fun forgetRecoveryCode() = p.edit().remove("recovery.wrapped").putBoolean("codeConfirmed", false).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }

    companion object { private const val ALIAS = "rasidi_recovery_wrap_v1" }
}
