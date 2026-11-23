package app.rasidi.core

import app.rasidi.core.backup.drive.*
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * يختبر عميل REST الحقيقي أمام خادم HTTP محلي يحاكي Drive v3 (ليس Google الفعلي):
 * التفويض في الترويسة، appDataFolder، رفع multipart بالمعرّف المجهّز، 409، 401، والتنزيل.
 */
class DriveRestClientTest {
    private lateinit var server: HttpServer
    private val files = HashMap<String, Pair<String, ByteArray>>()
    private val seenAuth = mutableListOf<String>()
    private var expired = false

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            seenAuth += ex.requestHeaders.getFirst("Authorization") ?: ""
            val path = ex.requestURI.path; val q = ex.requestURI.rawQuery ?: ""
            fun send(code: Int, body: String) { val b = body.toByteArray(); ex.sendResponseHeaders(code, if (b.isEmpty()) -1 else b.size.toLong()); if (b.isNotEmpty()) ex.responseBody.use { it.write(b) }; ex.close() }
            if (expired) return@createContext send(401, "{}")
            when {
                path == "/drive/v3/about" -> send(200, """{"user":{"permissionId":"P1","emailAddress":"t@example.com","displayName":"T"}}""")
                path == "/drive/v3/files/generateIds" -> { assertTrue(q.contains("space=appDataFolder")); send(200, """{"ids":["GEN1"]}""") }
                path == "/upload/drive/v3/files" && ex.requestMethod == "POST" -> {
                    val body = ex.requestBody.readBytes()
                    val text = String(body, Charsets.ISO_8859_1)
                    assertTrue(ex.requestHeaders.getFirst("Content-Type").startsWith("multipart/related"))
                    assertTrue(text.contains("\"parents\":[\"appDataFolder\"]"))
                    val id = Regex("\"id\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                    if (files.containsKey(id)) return@createContext send(409, """{"error":"exists"}""")
                    val start = text.indexOf("application/octet-stream\r\n\r\n") + "application/octet-stream\r\n\r\n".length
                    val end = text.lastIndexOf("\r\n--")
                    val data = body.copyOfRange(start, end)
                    files[id] = text to data
                    send(200, """{"id":"$id","name":"n","size":"${data.size}","createdTime":"2026-11-23T00:00:00Z","md5Checksum":"${DriveBackupEngine.md5(data)}","appProperties":{"sha256":"${DriveBackupEngine.sha256(data)}","installId":"I","kind":"MANUAL","rasidi":"1"}}""")
                }
                path.startsWith("/drive/v3/files/") && q.contains("alt=media") -> { val id = path.removePrefix("/drive/v3/files/"); files[id]?.let { val b = it.second; ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { o -> o.write(b) }; ex.close() } ?: send(404, "{}") }
                path.startsWith("/drive/v3/files/") -> { val id = path.removePrefix("/drive/v3/files/"); if (files.containsKey(id)) send(200, """{"id":"$id","name":"n","size":"1","createdTime":"x","appProperties":{"sha256":"${DriveBackupEngine.sha256(files[id]!!.second)}"}}""") else send(404, "{}") }
                path == "/drive/v3/files" -> { assertTrue(q.contains("spaces=appDataFolder")); send(200, """{"files":[${files.keys.joinToString(",") { """{"id":"$it","name":"n","size":"1","createdTime":"x","appProperties":{"rasidi":"1"}}""" }}]}""") }
                else -> send(404, "{}")
            }
        }
        server.start()
    }
    @After fun stop() = server.stop(0)

    private fun client() = DriveRestClient({ "TOKEN" }, "http://127.0.0.1:${server.address.port}")

    @Test fun fullFlowAgainstLocalServer() {
        val c = client()
        assertEquals("P1", c.about().permissionId)
        val data = ByteArray(5000) { (it % 251).toByte() }
        val r = DriveBackupEngine(c, "I").run(DriveBackupEngine.Job("j", "P1", null, "b", "MANUAL", 1, 1), data) {}
        assertTrue(r.toString(), r is DriveBackupEngine.Result.Verified)
        assertArrayEquals(data, files["GEN1"]!!.second)
        assertTrue(seenAuth.all { it == "Bearer TOKEN" })
        // إعادة الرفع بالمعرّف نفسه: 409 ثم تحقق بالتنزيل → نجاح دون ملف ثانٍ
        val r2 = DriveBackupEngine(c, "I").run(DriveBackupEngine.Job("j", "P1", "GEN1", "b", "MANUAL", 1, 1), data) {}
        assertTrue(r2 is DriveBackupEngine.Result.Verified); assertEquals(1, files.size)
        assertEquals(1, c.list().size)
        assertNull(c.get("missing"))
    }

    @Test fun expiredTokenSurfacesAsAuthError() {
        expired = true
        assertTrue(runCatching { client().about() }.exceptionOrNull() is DriveAuthException)
        assertTrue(DriveBackupEngine(client(), "I").run(DriveBackupEngine.Job("j", "P1", null, "b", "AUTO", 1, 1), byteArrayOf(1)) {} is DriveBackupEngine.Result.NeedsAuth)
    }
}
