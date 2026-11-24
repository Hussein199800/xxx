package app.rasidi.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * صيغة ملف `.rasidi`:
 * ```
 * MAGIC(8) = "RASIDI" 0x00 0x01
 * u32 BE  طول الترويسة
 * ترويسة JSON UTF-8 (غير مشفّرة لكن مربوطة بالتشفير كـ AAD)
 * نص مشفّر AES-256-GCM (وسم 128 بت في آخره) لـ gzip(JSON اللقطة)
 * ```
 * المفتاح = HKDF-SHA256(رمز الاستعادة 256 بت، salt من الترويسة، info="rasidi-backup-v1").
 * nonce عشوائي 96 بت لكل تشفير، وsalt عشوائي لكل ملف.
 */
object BackupFormat {
    val MAGIC = byteArrayOf('R'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'I'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), 0, 1)
    const val FORMAT_VERSION = 1
    /** إصدار بيانات اللقطة (يتبع مخطط Room). */
    const val DATA_VERSION = 3
    const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_HEADER = 16 * 1024
    private val rnd = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Header(
        val format: Int,
        val dataVersion: Int,
        val backupId: String,
        val createdAtUtc: Long,
        val app: String,
        val installId: String,
        val kind: String, // MANUAL | AUTO | SAFETY
        val salt: String,
        val nonce: String,
        val cipher: String = "AES-256-GCM",
        val kdf: String = "HKDF-SHA256",
    )

    @Serializable
    data class Payload(
        val fingerprint: String,
        val counts: Map<String, Int>,
        val snapshot: Snapshot,
    )

    class BackupException(val reason: Reason, message: String) : Exception(message)
    enum class Reason { TOO_LARGE, NOT_RASIDI, CORRUPT_HEADER, NEWER_FORMAT, NEWER_DATA, WRONG_CODE_OR_TAMPERED, CORRUPT_PAYLOAD, INVALID_DATA }

    fun encrypt(snapshot: Snapshot, code: RecoveryCode, installId: String, kind: String, nowUtc: Long, appVersion: String): Pair<Header, ByteArray> {
        val salt = ByteArray(16).also(rnd::nextBytes)
        val nonce = ByteArray(12).also(rnd::nextBytes)
        val header = Header(FORMAT_VERSION, DATA_VERSION, UUID.randomUUID().toString(), nowUtc, "rasidi/$appVersion", installId, kind, b64(salt), b64(nonce))
        val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
        val canon = snapshot.canonical()
        val payload = Payload(canon.fingerprint(), canon.counts(), canon)
        val plain = gzip(BackupJson.encodeToString(Payload.serializer(), payload).toByteArray(Charsets.UTF_8))
        val aad = MAGIC + u32(headerBytes.size) + headerBytes
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deriveKey(code.bytes, salt), "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plain)
        return header to (aad + ct)
    }

    /** يقرأ الترويسة فقط (بلا رمز) للتحقق المبدئي وعرض التاريخ. */
    fun readHeader(file: ByteArray): Header {
        if (file.size > MAX_FILE_BYTES) throw BackupException(Reason.TOO_LARGE, "حجم الملف أكبر من الحد")
        if (file.size < MAGIC.size + 4 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw BackupException(Reason.NOT_RASIDI, "الملف ليس نسخة رصيدي")
        val len = ByteBuffer.wrap(file, MAGIC.size, 4).int
        if (len <= 0 || len > MAX_HEADER || MAGIC.size + 4 + len > file.size) throw BackupException(Reason.CORRUPT_HEADER, "ترويسة الملف تالفة")
        val header = try {
            json.decodeFromString(Header.serializer(), String(file, MAGIC.size + 4, len, Charsets.UTF_8))
        } catch (e: Exception) { throw BackupException(Reason.CORRUPT_HEADER, "ترويسة الملف تالفة") }
        if (header.format > FORMAT_VERSION) throw BackupException(Reason.NEWER_FORMAT, "النسخة من صيغة أحدث (${header.format}). حدّث التطبيق أولاً")
        if (header.dataVersion > DATA_VERSION) throw BackupException(Reason.NEWER_DATA, "بيانات النسخة من إصدار أحدث (${header.dataVersion}). حدّث التطبيق أولاً")
        return header
    }

    data class Opened(val header: Header, val payload: Payload)

    /** يفكّ التشفير ويتحقق من السلامة والبصمة والعلاقات قبل أي كتابة. */
    fun decrypt(file: ByteArray, code: RecoveryCode): Opened {
        val header = readHeader(file)
        val len = ByteBuffer.wrap(file, MAGIC.size, 4).int
        val aadEnd = MAGIC.size + 4 + len
        val aad = file.copyOfRange(0, aadEnd)
        val ct = file.copyOfRange(aadEnd, file.size)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(deriveKey(code.bytes, b64d(header.salt)), "AES"), GCMParameterSpec(128, b64d(header.nonce)))
            c.updateAAD(aad)
            c.doFinal(ct)
        } catch (e: GeneralSecurityException) {
            throw BackupException(Reason.WRONG_CODE_OR_TAMPERED, "رمز الاستعادة غير صحيح أو الملف معدَّل")
        } catch (e: IllegalArgumentException) {
            throw BackupException(Reason.CORRUPT_HEADER, "ترويسة الملف تالفة")
        }
        val jsonText = try { String(gunzip(plain), Charsets.UTF_8) } catch (e: Exception) { throw BackupException(Reason.CORRUPT_PAYLOAD, "محتوى النسخة تالف") }
        val upgraded = BackupMigrations.upgrade(header.dataVersion, jsonText)
        val payload = try { BackupJson.decodeFromString(Payload.serializer(), upgraded) } catch (e: Exception) {
            throw BackupException(Reason.CORRUPT_PAYLOAD, "محتوى النسخة غير مقروء: ${e.message?.take(80)}")
        }
        if (payload.snapshot.fingerprint() != payload.fingerprint && header.dataVersion == DATA_VERSION)
            throw BackupException(Reason.INVALID_DATA, "بصمة المحتوى لا تطابق")
        val problems = SnapshotValidator.validate(payload.snapshot)
        if (problems.isNotEmpty()) throw BackupException(Reason.INVALID_DATA, "بيانات غير متّسقة: " + problems.take(3).joinToString("؛ "))
        return Opened(header, payload)
    }

    internal fun deriveKey(ikm: ByteArray, salt: ByteArray): ByteArray {
        // HKDF-SHA256 (RFC 5869): extract ثم expand لكتلة واحدة 32 بايت
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update("rasidi-backup-v1".toByteArray()); mac.update(1)
        return mac.doFinal()
    }

    private fun u32(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    private fun gunzip(b: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(b)).use { inp ->
            val buf = ByteArray(16 * 1024); var total = 0L
            while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > 512L * 1024 * 1024) throw IllegalStateException("too big"); out.write(buf, 0, n) }
        }
        return out.toByteArray()
    }
    private fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String) = java.util.Base64.getDecoder().decode(s)
}

/** ترحيل لقطات الإصدارات القديمة إلى [BackupFormat.DATA_VERSION]. كل خطوة لها اختبار. */
object BackupMigrations {
    private val steps: Map<Int, (JsonObject) -> JsonObject> = mapOf(
        // 1 → 2: أفراد البيت والميزانيات والمتكررات (جداول جديدة فارغة) و memberId للحركات (null).
        1 to { o ->
            val snap = o["snapshot"]!!.jsonObject
            val txns = snap["txns"]!!.jsonArray.map { t -> JsonObject(t.jsonObject + ("memberId" to JsonNull)) }
            val newSnap = JsonObject(snap + mapOf("txns" to JsonArray(txns), "members" to JsonArray(emptyList()), "budgets" to JsonArray(emptyList()), "recurring" to JsonArray(emptyList())))
            JsonObject(o + ("snapshot" to newSnap))
        },
        // 2 → 3: جدول الحسابات (الأشخاص) يُشتق من أسماء الديون، بمعرّفات ثابتة من الاسم.
        2 to { o ->
            val snap = o["snapshot"]!!.jsonObject
            val names = snap["debts"]!!.jsonArray.map { it.jsonObject["party"]!!.jsonPrimitive.content.trim() }.distinct()
            val parties = names.map { n -> buildJsonObject { put("id", PartyIds.forName(n)); put("name", n); put("phone", ""); put("note", ""); put("archived", false); put("createdAtUtc", 0L) } }
            JsonObject(o + ("snapshot" to JsonObject(snap + ("parties" to JsonArray(parties)))))
        },
    )

    fun upgrade(fromVersion: Int, payloadJson: String): String {
        if (fromVersion == BackupFormat.DATA_VERSION) return payloadJson
        var obj = Json.parseToJsonElement(payloadJson).jsonObject
        var v = fromVersion
        while (v < BackupFormat.DATA_VERSION) {
            val step = steps[v] ?: throw BackupFormat.BackupException(BackupFormat.Reason.CORRUPT_PAYLOAD, "لا يوجد ترحيل من الإصدار $v")
            obj = step(obj); v++
        }
        return obj.toString()
    }
}

/** معرّف ثابت لحساب الشخص مشتق من اسمه (للترحيل: النتيجة نفسها في كل مرة). */
object PartyIds {
    fun forName(name: String): String = "party-" + java.util.UUID.nameUUIDFromBytes(name.trim().toByteArray(Charsets.UTF_8)).toString()
}
