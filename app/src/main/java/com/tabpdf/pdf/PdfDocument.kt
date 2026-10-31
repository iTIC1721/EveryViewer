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
import android.os.StatFs
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
    private var fd: ParcelFileDescriptor?,
    /**
     * Android's [PdfRenderer]; or, for a file opened with a password on Android 12–14, the newer
     * [PdfRendererPreV] a system update brings there (the older one can't take passwords).
     * Null while pages are drawn in [RenderProcess]: opened here only for what that doesn't do
     * (text, links, export).
     */
    private var rendererOrNull: AutoCloseable?,
    /** Where pages are drawn, if not here. */
    private var remote: RenderProcess.Opened.Document?,
    private val context: Context,
    override val pageSizes: List<PageSize>,
    /** Opens the file again (for the search-capable renderer of Android 12–14). */
    private val reopen: () -> ParcelFileDescriptor?,
    /** The password it was opened with, if it needed one (기획서 5.5 암호 PDF). */
    private val password: String?,
    /** Where a copy for reading the bookmarks goes, if the file can't be read in place ([TEMP_DIR]). */
    private val tempDir: File,
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
            android.util.Log.w("EveryViewer", "No bookmarks: ${e.javaClass.simpleName}")
            emptyList()
        } catch (e: StackOverflowError) {
            emptyList()
        } catch (e: OutOfMemoryError) {
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
        val copy = File.createTempFile("outline", ".pdf", tempDir.apply { mkdirs() })
        try {
            // As for copies of tab files (FileAccess): the device keeps room for everything else.
            val limit = StatFs(tempDir.path).availableBytes - KEEP_FREE_BYTES
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                copy.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IOException("No room to copy")
                        output.write(buffer, 0, n)
                    }
                }
            }
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

    /** The renderer in this process, opened on first use if pages are drawn elsewhere. Under [mutex]. */
    private val renderer: AutoCloseable
        get() = rendererOrNull ?: run {
            val file = reopen() ?: throw FileNotFoundException()
            try {
                openRenderer(file, password).also { rendererOrNull = it; fd = file }
            } catch (e: Exception) {
                file.close()
                throw e
            }
        }

    /** Times in a row the render process has been started again for this file (see [renderRemotely]). */
    private var restarts = 0

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
            if (remote != null) return@withLock if (renderRemotely(index, bitmap)) bitmap else null
            // PDF pages are transparent by default; paper should be white.
            bitmap.eraseColor(Color.WHITE)
            renderInto(renderer, index, bitmap)
            bitmap
        }
    }

    /**
     * Draws in [RenderProcess]. If that process ended meanwhile (another file, or this one, ended it),
     * the file is opened there again, a few times at most: a file that ends it each time stops here.
     */
    private suspend fun renderRemotely(index: Int, bitmap: Bitmap): Boolean {
        while (true) {
            val opened = remote ?: return false
            try {
                if (RenderProcess.render(opened.id, opened.generation, index, bitmap)) {
                    restarts = 0
                    return true
                }
            } catch (e: IOException) {
                android.util.Log.w("EveryViewer", "Page $index not drawn: ${e.message}")
                // A page that can't be drawn, as opposed to a process that ended drawing it.
                if (RenderProcess.isRunning(opened.generation)) return false
            }
            if (++restarts > MAX_RESTARTS) return false
            val file = reopen() ?: return false
            remote = try {
                RenderProcess.open(context, file, password) as? RenderProcess.Opened.Document
            } catch (e: IOException) {
                null
            } finally {
                file.close()
            }
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
        val own = rendererOrNull as? PdfRendererPreV
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
    private fun preV(): PdfRendererPreV? = rendererOrNull as? PdfRendererPreV ?: searcher as? PdfRendererPreV
        ?: reopen()?.let { file -> PdfRendererPreV(file, loadParams(password ?: "")).also { searcher = it } }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            rendererOrNull?.close()
            fd?.close()
            searcher?.close()
            remote?.let { RenderProcess.close(it.id, it.generation) }
            Unit
        }
    }

    companion object {
        /**
         * Under the cache folder: copies made to read bookmarks. Each is deleted after reading; ones
         * left by an app that ended meanwhile are deleted at the next start (TabsViewModel).
         */
        const val TEMP_DIR = "outline"

        private const val KEEP_FREE_BYTES = 256L * 1024 * 1024

        /** How far around a link a tap still hits it, in PDF points (1/72 inch). */
        private const val LINK_SLOP = 4f

        private const val MAX_RESTARTS = 3

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
            val app = context.applicationContext
            val reopen = { app.contentResolver.openFileDescriptor(uri, "r") }
            if (RenderProcess.available) {
                val opened = try {
                    RenderProcess.open(app, fd, password)
                } finally {
                    // The render process has its own copy of the descriptor.
                    fd.close()
                }
                return@withContext when (opened) {
                    is RenderProcess.Opened.Document -> {
                        if (opened.sizes.isEmpty()) throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                        PdfDocument(null, null, opened, app, opened.sizes, reopen, password, File(app.cacheDir, TEMP_DIR))
                    }
                    RenderProcess.Opened.NeedsPassword -> throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
                    RenderProcess.Opened.WrongPassword -> {
                        PdfPasswords.remove(uri)
                        throw DocumentOpenException(DocumentOpenException.Reason.WrongPassword)
                    }
                }
            }
            try {
                val renderer = try {
                    openRenderer(fd, password)
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
                PdfDocument(fd, renderer, null, app, sizes, reopen, password, File(app.cacheDir, TEMP_DIR))
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }

        /**
         * Android's renderer for [fd] (see [renderer]), here or in [RenderService]. Throws
         * [SecurityException] if the file needs a password, or the one given doesn't open it.
         */
        internal fun openRenderer(fd: ParcelFileDescriptor, password: String?): AutoCloseable = when {
            password == null -> PdfRenderer(fd)
            Build.VERSION.SDK_INT >= 35 -> PdfRenderer(fd, loadParams(password))
            else -> openPreV(fd, password)
        }

        /** Draws page [index] over what is in [bitmap]. */
        internal fun renderInto(renderer: AutoCloseable, index: Int, bitmap: Bitmap) {
            if (renderer is PdfRenderer) {
                renderer.openPage(index).use { page ->
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } else if (preVSearch) {
                renderPreV(renderer as PdfRendererPreV, index, bitmap)
            }
        }

        @RequiresExtension(extension = Build.VERSION_CODES.S, version = 13)
        private fun renderPreV(renderer: PdfRendererPreV, index: Int, bitmap: Bitmap) {
            renderer.openPage(index).use { page ->
                page.render(bitmap, null, null, RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY).build())
            }
        }

        // Reading every page size costs ~0.4 ms a page (1000 pages ≈ 0.4 s on an emulator).
        internal fun pageSizesOf(renderer: AutoCloseable): List<PageSize> = when {
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
