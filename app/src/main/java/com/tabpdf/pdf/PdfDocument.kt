package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.content.PdfPageGotoLinkContent
import android.graphics.pdf.content.PdfPageLinkContent
import android.graphics.pdf.models.selection.SelectionBoundary
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.PdfRendererPreV
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.ext.SdkExtensions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresExtension
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.Link
import com.tabpdf.doc.isWebLink
import com.tabpdf.doc.PageSize
import com.tabpdf.doc.SearchMatch
import com.tabpdf.doc.TextSearch
import com.tabpdf.doc.TextSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import com.tabpdf.doc.TocEntry
import com.tabpdf.export.PdfCrypt
import com.tabpdf.export.PdfFile
import com.tabpdf.export.PdfOutline
import kotlin.math.roundToInt

/**
 * One open PDF file. [PdfRenderer] is not thread-safe and allows only one open page at a time,
 * so every access goes through [mutex] on the IO dispatcher.
 */
class PdfDocument private constructor(
    private val fd: ParcelFileDescriptor,
    /**
     * Android's [PdfRenderer]; or, for a file opened with a password on Android 12–14, the newer
     * [PdfRendererPreV] a system update brings there (the older one can't take passwords).
     */
    private val renderer: AutoCloseable,
    override val pageSizes: List<PageSize>,
    /** Opens the file again (for the search-capable renderer of Android 12–14). */
    private val reopen: () -> ParcelFileDescriptor?,
    /** The password it was opened with, if it needed one (기획서 5.5 암호 PDF). */
    private val password: String?,
) : Document {
    override val locked: Boolean get() = password != null

    /** Pages are in points already. */
    override fun paperWidth(index: Int): Float = pageSizes[index].width

    private var contents: List<TocEntry>? = null

    /**
     * The bookmarks (기획서 5.5 목차). Android's PDF support doesn't give them, so the file's
     * structure is read here ([PdfOutline]); a file that can't be followed has none.
     */
    override suspend fun tableOfContents(): List<TocEntry> = withContext(Dispatchers.IO) {
        contents?.let { return@withContext it }
        val result = try {
            openForReading().use { (raf, _, _) ->
                val pdf = PdfFile(raf)
                if (pdf.trailer.containsKey("Encrypt")) {
                    pdf.crypt = PdfCrypt.forFile(pdf, password ?: "") ?: return@use emptyList()
                }
                val pages = pdf.pages()
                if (pages.size != pageCount) emptyList() else TocEntry.tree(PdfOutline.read(pdf, pages))
            }
        } catch (e: Exception) {
            android.util.Log.w("EveryViewer", "No bookmarks", e)
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        }
        contents = result
        result
    }

    /**
     * The file for random access: opened again and read through /proc (the renderer owns the first
     * descriptor), or, where that can't seek, a copy that is deleted after.
     */
    private fun openForReading(): Readable {
        val pfd = reopen() ?: throw FileNotFoundException()
        try {
            return Readable(RandomAccessFile("/proc/self/fd/${pfd.fd}", "r"), pfd, null)
        } catch (_: IOException) {
        }
        val copy = File.createTempFile("outline", ".pdf")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> copy.outputStream().use { input.copyTo(it) } }
            return Readable(RandomAccessFile(copy, "r"), null, copy)
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
    }

    private data class Readable(val file: RandomAccessFile, val descriptor: ParcelFileDescriptor?, val copy: File?) : java.io.Closeable {
        override fun close() {
            file.close()
            descriptor?.close()
            copy?.delete()
        }
    }

    private val mutex = Mutex()
    private var closed = false

    /** Android 12–14's renderer that can search, opened on the first search. */
    private var searcher: AutoCloseable? = null

    /** Each page's links once read (a page is read on its first tap), in page fractions. */
    private val links = HashMap<Int, List<Pair<List<RectF>, Link>>>()

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
            if (renderer is PdfRenderer) {
                renderer.openPage(index).use { page ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } else if (preVSearch) {
                renderPreV(index, bitmap)
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

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun renderPreV(index: Int, bitmap: Bitmap) {
        (renderer as PdfRendererPreV).openPage(index).use { page ->
            page.render(bitmap, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
        }
    }

    /** On Android 15 the renderer is always [PdfRenderer] (it takes passwords there). */
    @get:RequiresApi(35)
    private val platform: PdfRenderer get() = renderer as PdfRenderer

    @RequiresApi(35)
    private fun searchPage(index: Int, query: String): List<List<RectF>> =
        platform.openPage(index).use { page -> page.searchText(query).map { it.bounds } }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun searchPagePreV(index: Int, query: String): List<List<RectF>> {
        val r = preV() ?: return emptyList()
        return r.openPage(index).use { page -> page.searchText(query).map { it.bounds } }
    }

    /** Selection with the platform's PDF text support, where it has it (as [search]). */
    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val size = pageSizes[index]
            val start = Point((from.x * size.width).roundToInt(), (from.y * size.height).roundToInt())
            val stop = Point((to.x * size.width).roundToInt(), (to.y * size.height).roundToInt())
            val contents = when {
                Build.VERSION.SDK_INT >= 35 -> selectOnPage(index, start, stop)
                preVSearch -> selectOnPagePreV(index, start, stop)
                else -> null
            } ?: return@withLock null
            val text = contents.joinToString("") { it.first }
            if (text.isBlank()) return@withLock null
            TextSelection(text, contents.flatMap { it.second }.map { RectF(it.left / size.width, it.top / size.height, it.right / size.width, it.bottom / size.height) })
        }
    }

    /** The same point at both ends selects the word there. */
    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = selectText(index, at, at)

    @RequiresApi(35)
    private fun selectOnPage(index: Int, start: Point, stop: Point): List<Pair<String, List<RectF>>>? =
        platform.openPage(index).use { page ->
            page.selectContent(SelectionBoundary(start), SelectionBoundary(stop))?.selectedTextContents?.map { it.text to it.bounds }
        }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun selectOnPagePreV(index: Int, start: Point, stop: Point): List<Pair<String, List<RectF>>>? {
        val r = preV() ?: return null
        return r.openPage(index).use { page ->
            page.selectContent(SelectionBoundary(start), SelectionBoundary(stop))?.selectedTextContents?.map { it.text to it.bounds }
        }
    }

    /**
     * Links with the platform's PDF support, where it has it (as [search]): web links, and links to
     * other places in the document (a table of contents).
     */
    override suspend fun link(index: Int, x: Float, y: Float): Link? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock null
            val size = pageSizes[index]
            // A finger is less precise than a cursor: a little around each link counts.
            val dx = LINK_SLOP / size.width
            val dy = LINK_SLOP / size.height
            links.getOrPut(index) { readLinks(index) }.firstOrNull { (rects, _) ->
                rects.any { x >= it.left - dx && x <= it.right + dx && y >= it.top - dy && y <= it.bottom + dy }
            }?.second
        }
    }

    private fun readLinks(index: Int): List<Pair<List<RectF>, Link>> = when {
        Build.VERSION.SDK_INT >= 35 -> platform.openPage(index).use { page -> linksOf(index, page.linkContents, page.gotoLinks) }
        preVSearch -> preV()?.openPage(index)?.use { page -> linksOf(index, page.linkContents, page.gotoLinks) }.orEmpty()
        else -> emptyList()
    }

    /** Links as page fractions; web links only to web pages and mail (see [isWebLink]). */
    private fun linksOf(index: Int, web: List<PdfPageLinkContent>, goto: List<PdfPageGotoLinkContent>): List<Pair<List<RectF>, Link>> {
        val size = pageSizes[index]
        fun fractions(rects: List<RectF>) = rects.map { RectF(it.left / size.width, it.top / size.height, it.right / size.width, it.bottom / size.height) }
        val result = mutableListOf<Pair<List<RectF>, Link>>()
        for (link in web) {
            val url = link.uri.toString()
            if (isWebLink(url)) result += fractions(link.bounds) to Link.Web(url)
        }
        for (link in goto) {
            val page = link.destination.pageNumber
            if (page !in pageSizes.indices) continue
            val y = link.destination.yCoordinate.takeIf { it > 0f }?.let { (it / pageSizes[page].height).coerceIn(0f, 1f) }
            result += fractions(link.bounds) to Link.Page(page, y)
        }
        return result
    }

    /**
     * Writes the file to [target] without its encryption, with Android's PDF support where it can
     * (Android 15, and 12–14 with the system update), for export. False where it can't.
     */
    suspend fun writeUnlocked(target: ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        if (!preVSearch) return@withContext false
        mutex.withLock {
            if (closed) return@withLock false
            writePreV(target)
            true
        }
    }

    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun writePreV(target: ParcelFileDescriptor) {
        val own = renderer as? PdfRendererPreV
        if (own != null) {
            own.write(target, true)
            return
        }
        // Encryption is only taken off a file opened with a password; one locked only against
        // printing or copying opens with the empty one.
        val file = reopen() ?: throw FileNotFoundException()
        PdfRendererPreV(file, loadParams(password ?: "")).use { it.write(target, true) }
    }

    /** Android 12–14's renderer that can search: the one in use if the file needed a password, else opened once. */
    @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
    private fun preV(): PdfRendererPreV? = renderer as? PdfRendererPreV ?: searcher as? PdfRendererPreV
        ?: reopen()?.let { file -> PdfRendererPreV(file).also { searcher = it } }

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
        /** How far around a link a tap still hits it, in PDF points (1/72 inch). */
        private const val LINK_SLOP = 4f

        /** Whether this Android 12–14 device has the system update that brings PDF text search. */
        @get:ChecksSdkIntAtLeast(api = 31)
        private val preVSearch: Boolean by lazy {
            Build.VERSION.SDK_INT >= 31 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
        }

        /**
         * Whether this device opens PDFs that need a password: Android's PDF support takes one on
         * Android 15, and on 12–14 with the system update that brings it (as for text search).
         */
        val takesPasswords: Boolean get() = Build.VERSION.SDK_INT >= 35 || preVSearch

        /**
         * Opens [uri] for reading, with the password typed for it in this run if any ([PdfPasswords]).
         * Throws if the file is missing, unreadable or not a valid PDF; [DocumentOpenException.Reason.PasswordProtected]
         * if it needs a password, [DocumentOpenException.Reason.WrongPassword] if the one typed doesn't open it.
         */
        suspend fun open(context: Context, uri: Uri): PdfDocument = withContext(Dispatchers.IO) {
            val password = PdfPasswords[uri]?.takeIf { takesPasswords }
            val fd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw FileNotFoundException(uri.toString())
            try {
                val renderer = try {
                    when {
                        password == null -> PdfRenderer(fd)
                        Build.VERSION.SDK_INT >= 35 -> PdfRenderer(fd, loadParams(password))
                        else -> openPreV(fd, password)
                    }
                } catch (e: SecurityException) {
                    // The renderer's way of saying the file is encrypted, or the password is wrong.
                    if (password == null) throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
                    PdfPasswords.remove(uri)
                    throw DocumentOpenException(DocumentOpenException.Reason.WrongPassword)
                }
                val sizes = try {
                    pageSizesOf(renderer)
                } catch (e: Exception) {
                    renderer.close()
                    throw e
                }
                if (sizes.isEmpty()) {
                    renderer.close()
                    throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                }
                val app = context.applicationContext
                PdfDocument(fd, renderer, sizes, { app.contentResolver.openFileDescriptor(uri, "r") }, password)
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }

        // Reading every page size costs ~0.4 ms a page (1000 pages ≈ 0.4 s on an emulator).
        private fun pageSizesOf(renderer: AutoCloseable): List<PageSize> = when {
            renderer is PdfRenderer -> (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
            }
            preVSearch -> pageSizesPreV(renderer as PdfRendererPreV)
            else -> emptyList()
        }

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        private fun pageSizesPreV(renderer: PdfRendererPreV): List<PageSize> = (0 until renderer.pageCount).map { i ->
            renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
        }

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        private fun loadParams(password: String): LoadParams = LoadParams.Builder().setPassword(password).build()

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        private fun openPreV(fd: ParcelFileDescriptor, password: String): PdfRendererPreV = PdfRendererPreV(fd, loadParams(password))
    }
}
