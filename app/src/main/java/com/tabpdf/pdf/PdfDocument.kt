package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.PageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.roundToInt

/**
 * One open PDF file. [PdfRenderer] is not thread-safe and allows only one open page at a time,
 * so every access goes through [mutex] on the IO dispatcher.
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    override val pageSizes: List<PageSize>,
) : Document {
    private val mutex = Mutex()
    private var closed = false

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val heightPx = (widthPx * pageSizes[index].aspect).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for page $index at ${widthPx}x$heightPx", e)
                return@withLock null
            }
            // PDF pages are transparent by default; paper should be white.
            bitmap.eraseColor(Color.WHITE)
            renderer.openPage(index).use { page ->
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
            bitmap
        }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
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
                val renderer = try {
                    PdfRenderer(fd)
                } catch (e: SecurityException) {
                    // PdfRenderer's way of saying the file is encrypted.
                    throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
                }
                if (renderer.pageCount == 0) {
                    renderer.close()
                    throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                }
                // Reading every page size costs ~0.4 ms a page (1000 pages ≈ 0.4 s on an emulator).
                val sizes = (0 until renderer.pageCount).map { i ->
                    renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
                }
                PdfDocument(fd, renderer, sizes)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
