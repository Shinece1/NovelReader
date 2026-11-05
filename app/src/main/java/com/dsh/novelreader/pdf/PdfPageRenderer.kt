package com.dsh.novelreader.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File

/**
 * Renders single PDF pages with the platform [PdfRenderer].
 *
 * Exactly one page is open and one bitmap exists at a time: the caller recycles
 * the returned bitmap right after OCR, so memory stays flat no matter how large
 * the document is. Nothing here ever loads a whole PDF into memory.
 */
class PdfPageRenderer(file: File) : Closeable {

    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

    /**
     * Renders [index] scaled to [targetWidth] px. Returns null for an out-of-range
     * page; throws only when the platform renderer itself fails.
     */
    fun render(index: Int, targetWidth: Int = 1600): Bitmap? {
        if (index < 0 || index >= renderer.pageCount) return null
        val page = renderer.openPage(index)
        try {
            val scale = (targetWidth.toFloat() / page.width.toFloat()).coerceAtLeast(0.2f)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            val matrix = Matrix().apply { setScale(scale, scale) }
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } finally {
            page.close()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
