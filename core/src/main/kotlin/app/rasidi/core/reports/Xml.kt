package app.rasidi.core.reports

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun esc(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (ch in s) when {
        ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;")
        ch == '"' -> sb.append("&quot;")
        ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {} // محارف تحكم غير مسموحة في XML
        ch in '\u2066'..'\u2069' -> {} // عزل الاتجاه يُعالج بالتنسيق لا بالمحارف
        else -> sb.append(ch)
    }
    return sb.toString()
}

/** حاوية ZIP حتمية (تواريخ ثابتة) لملفات OOXML. */
internal class Zip {
    private val out = ByteArrayOutputStream()
    private val zip = ZipOutputStream(out)
    fun put(name: String, content: String) = put(name, content.toByteArray(Charsets.UTF_8))
    fun put(name: String, bytes: ByteArray) {
        val e = ZipEntry(name); e.time = 315532800000L // 1980-01-01
        zip.putNextEntry(e); zip.write(bytes); zip.closeEntry()
    }
    fun bytes(): ByteArray { zip.close(); return out.toByteArray() }
}

internal const val XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
