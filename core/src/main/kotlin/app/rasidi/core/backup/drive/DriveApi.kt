package app.rasidi.core.backup.drive

import java.io.IOException

/** واجهة Drive API v3 المحصورة في appDataFolder. التنفيذ الحقيقي [DriveRestClient] والوهمي في الاختبارات. */
interface DriveApi {
    data class About(val permissionId: String, val email: String, val displayName: String)
    data class FileMeta(
        val id: String,
        val name: String,
        val size: Long,
        val createdTime: String,
        val md5: String?,
        val appProperties: Map<String, String>,
    )

    fun about(): About
    fun generateId(): String
    /** يرفع الملف الثنائي بالمعرّف المجهّز مسبقاً. 409 إن وُجد ملف بنفس المعرّف. */
    fun upload(id: String, name: String, appProperties: Map<String, String>, bytes: ByteArray): FileMeta
    /** null إذا لم يوجد. */
    fun get(id: String): FileMeta?
    fun download(id: String): ByteArray
    fun list(): List<FileMeta>
    fun delete(id: String)
}

open class DriveHttpException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")
/** الرمز منتهٍ أو مسحوب: يلزم تفاعل المستخدم («أعد ربط Google»). */
class DriveAuthException(message: String) : IOException(message)
