package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.PageSize
import com.tabpdf.doc.SearchMatch
import com.tabpdf.doc.TextSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
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
    /** Opens the file again (for the search-capable renderer of Android 12–14). */
    private val reopen: () -> ParcelFileDescriptor?,
) : Document {
    private val mutex = Mutex()
    private var closed = false

    /** Android 12–14's renderer that can search, opened on the first search. */
    private var searcher: AutoCloseable? = null

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

    /**
     * Text search with the platform's PDF text support (기획서 5.5): Android 15's renderer, or the
     * same renderer brought to Android 12–14 by a system update. Older devices find nothing.
     */
    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SearchMatch>()
        if (query.isBlank()) return@withContext result
        for (index in pageSizes.indices) {
            ensureActive()
            val bounds = mutex.withLock {
                if (closed) return@withContext result
                when {
                    Build.VERSION.SDK_INT >= 35 -> searchPage(index, query)
                    preVSearch -> searchPagePreV(index, query)
                    else -> return@withContext result
                }
            }
            val size = pageSizes[index]
            for (rects in bounds) {
                result += SearchMatch(index, rects.map { RectF(it.left / size.width, it.top / size.height, it.right / size.width, it.bottom / size.height) })
                if (result.size >= TextSearch.MAX_MATCHES) return@withContext result
            }
        }
        result
    }

    @RequiresApi(35)
    private fun searchPage(index: Int, query: String): List<List<RectF>> =
        renderer.openPage(index).use { page -> page.searchText(query).map { it.bounds } }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun searchPagePreV(index: Int, query: String): List<List<RectF>> {
        val r = (searcher as? PdfRendererPreV) ?: run {
            val file = reopen() ?: return emptyList()
            PdfRendererPreV(file).also { searcher = it }
        }
        return r.openPage(index).use { page -> page.searchText(query).map { it.bounds } }
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            renderer.close()
            fd.close()
            searcher?.close()
        }
    }

    companion object {
        /** Whether this Android 12–14 device has the system update that brings PDF text search. */
        @get:ChecksSdkIntAtLeast(api = 31)
        private val preVSearch: Boolean by lazy {
            Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        }

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
                val app = context.applicationContext
                PdfDocument(fd, renderer, sizes) { app.contentResolver.openFileDescriptor(uri, "r") }
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }
    }
}
