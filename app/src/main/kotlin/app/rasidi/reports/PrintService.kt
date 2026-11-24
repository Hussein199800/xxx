package app.rasidi.reports

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import java.io.FileOutputStream

/** طباعة ملف PDF جاهز عبر خدمة الطباعة في Android (طابعة شبكة، أو حرارية عبر تطبيقها، أو «حفظ كـ PDF»). */
object PrintService {
    fun print(ctx: Context, jobName: String, pdf: ByteArray, mediaSize: PrintAttributes.MediaSize? = null) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel.isCanceled) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder("$jobName.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>, dest: ParcelFileDescriptor, cancel: CancellationSignal, cb: WriteResultCallback) {
                try {
                    FileOutputStream(dest.fileDescriptor).use { it.write(pdf) }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }
        val attrs = PrintAttributes.Builder().apply { mediaSize?.let { setMediaSize(it) } }.build()
        pm.print(jobName, adapter, attrs)
    }

    fun mediaFor(paper: app.rasidi.core.voucher.Paper): PrintAttributes.MediaSize? = when (paper) {
        app.rasidi.core.voucher.Paper.A4 -> PrintAttributes.MediaSize.ISO_A4
        app.rasidi.core.voucher.Paper.A5 -> PrintAttributes.MediaSize.ISO_A5
        else -> null // البكرات الحرارية: تختار الطابعة المقاس
    }
}
