package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.roundToInt

/** Page size in PDF points (1/72 inch). */
data class PageSize(val width: Int, val height: Int) {
    val aspect: Float get() = height.toFloat() / width
}

/**
 * One open PDF file. [PdfRenderer] is not thread-safe and allows only one open page at a time,
 * so every access goes through [mutex] on the IO dispatcher.
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val pageSizes: List<PageSize>,
) {
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = pageSizes.size

    /** Renders page [index] at [widthPx] wide, or returns null if the document was closed meanwhile. */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val heightPx = (widthPx * pageSizes[index].aspect).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            // PDF pages are transparent by default; paper should be white.
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        }
    }

    suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            renderer.close()
            fd.close()
        }
    }

    companion object {
        /** Opens [uri] for reading. Throws if the file is missing, unreadable or not a valid PDF. */
        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException(uri.toString())
            try {
                val renderer = PdfRenderer(fd)
                val sizes = (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { PageSize(it.width, it.height) }
                }
                PdfDocument(fd, renderer, sizes)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
