package app.rasidi.core.backup.drive

import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * عميل Drive REST v3 بلا تبعيات (HttpURLConnection)، محصور في مساحة appDataFolder.
 * [token] يُستدعى قبل كل طلب ويعيد رمز وصول صالحاً لنطاق drive.appdata.
 */
class DriveRestClient(private val token: () -> String, private val base: String = "https://www.googleapis.com") : DriveApi {
    private val json = Json { ignoreUnknownKeys = true }

    override fun about(): DriveApi.About {
        val o = getJson("/drive/v3/about?fields=user(permissionId,emailAddress,displayName)").jsonObject["user"]!!.jsonObject
        return DriveApi.About(o.str("permissionId"), o.str("emailAddress"), o.str("displayName"))
    }

    override fun generateId(): String =
        getJson("/drive/v3/files/generateIds?count=1&space=appDataFolder&type=files").jsonObject["ids"]!!.jsonArray[0].jsonPrimitive.content

    override fun upload(id: String, name: String, appProperties: Map<String, String>, bytes: ByteArray): DriveApi.FileMeta {
        val boundary = "rasidi-" + UUID.randomUUID()
        val meta = buildJsonObject {
            put("id", id); put("name", name); put("mimeType", "application/octet-stream")
            putJsonArray("parents") { add("appDataFolder") }
            putJsonObject("appProperties") { appProperties.forEach { (k, v) -> put(k, v) } }
        }.toString()
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes); write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        val r = request("POST", "/upload/drive/v3/files?uploadType=multipart&fields=$FIELDS", body, "multipart/related; boundary=$boundary")
        return parseMeta(json.parseToJsonElement(String(r, Charsets.UTF_8)).jsonObject)
    }

    override fun get(id: String): DriveApi.FileMeta? = try {
        parseMeta(getJson("/drive/v3/files/${enc(id)}?fields=$FIELDS").jsonObject)
    } catch (e: DriveHttpException) { if (e.code == 404) null else throw e }

    override fun download(id: String): ByteArray = request("GET", "/drive/v3/files/${enc(id)}?alt=media", null, null)

    override fun list(): List<DriveApi.FileMeta> {
        val out = mutableListOf<DriveApi.FileMeta>(); var page: String? = null
        do {
            val q = "/drive/v3/files?spaces=appDataFolder&pageSize=100&orderBy=createdTime%20desc&fields=nextPageToken,files($FIELDS)" + (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val o = getJson(q).jsonObject
            o["files"]?.jsonArray?.forEach { out += parseMeta(it.jsonObject) }
            page = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return out
    }

    override fun delete(id: String) { try { request("DELETE", "/drive/v3/files/${enc(id)}", null, null) } catch (e: DriveHttpException) { if (e.code != 404) throw e } }

    private fun getJson(path: String) = json.parseToJsonElement(String(request("GET", path, null, null), Charsets.UTF_8))

    private fun request(method: String, path: String, body: ByteArray?, contentType: String?): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method; c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("Authorization", "Bearer ${token()}")
            if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType); c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            if (code == 401) throw DriveAuthException("انتهت صلاحية التفويض")
            if (code >= 400) {
                val err = c.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                if (code == 403 && err.contains("insufficient", true)) throw DriveAuthException("الصلاحية غير كافية")
                throw DriveHttpException(code, err.take(200))
            }
            return c.inputStream.use { it.readBytes() }
        } catch (e: IOException) { throw e } finally { c.disconnect() }
    }

    private fun parseMeta(o: JsonObject) = DriveApi.FileMeta(
        o.str("id"), o["name"]?.jsonPrimitive?.contentOrNull ?: "", o["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
        o["createdTime"]?.jsonPrimitive?.contentOrNull ?: "", o["md5Checksum"]?.jsonPrimitive?.contentOrNull,
        o["appProperties"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
    )

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        private const val FIELDS = "id,name,size,createdTime,md5Checksum,appProperties"
    }
}
