package com.tabpdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
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
 * One open PDF file. Android's PDF code ([PdfEngine]) reads it in the isolated render process
 * ([RenderProcess]), or in the app on Android 8.0 ([local]). It is not thread-safe and allows only
 * one open page at a time, so every access goes through [mutex] on the IO dispatcher.
 */
class PdfDocument private constructor(
    /** The file and its reading in this process, where there is no render process. */
    private val local: Local?,
    /** Where it is read otherwise; null once it can't be opened there again. */
    private var remote: RenderProcess.Opened.Document?,
    private val context: Context,
    override val pageSizes: List<PageSize>,
    /** Opens the file again (bookmarks; and the render process after it ended). */
    private val reopen: () -> ParcelFileDescriptor?,
    /** The password it was opened with, if it needed one (기획서 5.5 암호 PDF). */
    private val password: String?,
    /** Where a copy for reading the bookmarks goes, if the file can't be read in place ([TEMP_DIR]). */
    private val tempDir: File,
) : Document {
    private class Local(val fd: ParcelFileDescriptor, val engine: PdfEngine)

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

    /** Times in a row the render process has been started again for this file (see [remotely]). */
    private var restarts = 0

    /** Each page's links once read (a page is read on its first tap), in page fractions. */
    private val links = HashMap<Int, List<Pair<List<RectF>, Link>>>()

    /**
     * [here] with the engine in this process, or [there] in the render process; null if the file is
     * closed or can't be read there now.
     */
    private suspend fun <T> withEngine(here: (PdfEngine) -> T, there: suspend (RenderProcess.Opened.Document) -> T): T? =
        mutex.withLock {
            if (closed) return@withLock null
            local?.let { return@withLock here(it.engine) }
            remotely(there)
        }

    /**
     * If the render process ended meanwhile (another file, or this one, ended it), the file is
     * opened there again, a few times in a row at most: a file that ends it each time stops here.
     */
    private suspend fun <T> remotely(block: suspend (RenderProcess.Opened.Document) -> T): T? {
        while (true) {
            val opened = remote ?: return null
            try {
                return block(opened).also { restarts = 0 }
            } catch (e: RenderProcess.Ended) {
            } catch (e: IOException) {
                android.util.Log.w("EveryViewer", "PDF not read: ${e.javaClass.simpleName}")
                return null
            }
            if (++restarts > MAX_RESTARTS) return null
            remote = try {
                reopen()?.use { RenderProcess.open(context, it, password) } as? RenderProcess.Opened.Document
            } catch (e: IOException) {
                null
            }
        }
    }

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val heightPx = (widthPx * pageSizes[index].aspect).roundToInt().coerceAtLeast(1)
        val bitmap = try {
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("EveryViewer", "No memory for page $index at ${widthPx}x$heightPx", e)
            return@withContext null
        }
        val drawn = withEngine({ engine ->
            // PDF pages are transparent by default; paper should be white.
            bitmap.eraseColor(Color.WHITE)
            engine.render(index, bitmap)
        }) { if (Build.VERSION.SDK_INT >= 27) RenderProcess.render(it, index, bitmap) }
        if (drawn == null) {
            bitmap.recycle()
            null
        } else {
            bitmap
        }
    }

    private fun fractions(index: Int, rects: List<RectF>): List<RectF> {
        val size = pageSizes[index]
        return rects.map { RectF(it.left / size.width, it.top / size.height, it.right / size.width, it.bottom / size.height) }
    }

    /**
     * Text search with the platform's PDF text support (기획서 5.5): Android 15's renderer, or the
     * same renderer brought to Android 12–14 by a system update. Older devices find nothing.
     */
    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.IO) {
        val result = mutableListOf<SearchMatch>()
        if (query.isBlank() || !PdfEngine.textSupport) return@withContext result
        for (index in pageSizes.indices) {
            ensureActive()
            val bounds = withEngine({ it.search(index, query) }) { RenderProcess.search(it, index, query) }
                ?: return@withContext result
            for (rects in bounds) {
                result += SearchMatch(index, fractions(index, rects))
                if (result.size >= TextSearch.MAX_MATCHES) return@withContext result
            }
        }
        result
    }

    /** Selection with the platform's PDF text support, where it has it (as [search]). */
    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.IO) {
        if (!PdfEngine.textSupport) return@withContext null
        val size = pageSizes[index]
        val start = Point((from.x * size.width).roundToInt(), (from.y * size.height).roundToInt())
        val stop = Point((to.x * size.width).roundToInt(), (to.y * size.height).roundToInt())
        val contents = withEngine({ it.select(index, start, stop) }) { RenderProcess.select(it, index, start, stop) }
            ?: return@withContext null
        val text = contents.joinToString("") { it.first }
        if (text.isBlank()) return@withContext null
        TextSelection(text, fractions(index, contents.flatMap { it.second }))
    }

    /** The same point at both ends selects the word there. */
    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = selectText(index, at, at)

    /**
     * Links with the platform's PDF support, where it has it (as [search]): web links, and links to
     * other places in the document (a table of contents).
     */
    override suspend fun link(index: Int, x: Float, y: Float): Link? = withContext(Dispatchers.IO) {
        if (!PdfEngine.textSupport) return@withContext null
        val size = pageSizes[index]
        // A finger is less precise than a cursor: a little around each link counts.
        val dx = LINK_SLOP / size.width
        val dy = LINK_SLOP / size.height
        val onPage = synchronized(links) { links[index] }
            ?: (withEngine({ it.links(index) }) { RenderProcess.links(it, index) } ?: return@withContext null)
                .let { (web, goto) -> linksOf(index, web, goto) }
                .also { synchronized(links) { links[index] = it } }
        onPage.firstOrNull { (rects, _) ->
            rects.any { x >= it.left - dx && x <= it.right + dx && y >= it.top - dy && y <= it.bottom + dy }
        }?.second
    }

    /** Links as page fractions; web links only to web pages and mail (see [isWebLink]). */
    private fun linksOf(index: Int, web: List<Pair<List<RectF>, String>>, goto: List<PdfEngine.Goto>): List<Pair<List<RectF>, Link>> {
        val result = mutableListOf<Pair<List<RectF>, Link>>()
        for ((bounds, url) in web) {
            if (isWebLink(url)) result += fractions(index, bounds) to Link.Web(url)
        }
        for (link in goto) {
            val page = link.page
            if (page !in pageSizes.indices) continue
            val y = link.y.takeIf { it > 0f && it.isFinite() }?.let { (it / pageSizes[page].height).coerceIn(0f, 1f) }
            result += fractions(index, link.bounds) to Link.Page(page, y)
        }
        return result
    }

    /**
     * Writes the file to [target] without its encryption, with Android's PDF support where it can
     * (Android 15, and 12–14 with the system update), for export. False where it can't.
     */
    suspend fun writeUnlocked(target: ParcelFileDescriptor): Boolean = withContext(Dispatchers.IO) {
        if (!PdfEngine.textSupport) return@withContext false
        withEngine({ it.write(target) }) { RenderProcess.write(it, target) } ?: false
    }

    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            local?.let {
                it.engine.close()
                it.fd.close()
            }
            remote?.let { RenderProcess.close(it) }
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

        /**
         * Whether this device opens PDFs that need a password: Android's PDF support takes one on
         * Android 15, and on 12–14 with the system update that brings it (as for text search).
         */
        val takesPasswords: Boolean get() = PdfEngine.textSupport

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
                // The render process gets its own copy of the descriptor.
                val opened = fd.use { RenderProcess.open(app, it, password) }
                return@withContext when (opened) {
                    is RenderProcess.Opened.Document -> {
                        if (opened.sizes.isEmpty()) {
                            RenderProcess.close(opened)
                            throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                        }
                        PdfDocument(null, opened, app, opened.sizes, reopen, password, File(app.cacheDir, TEMP_DIR))
                    }
                    RenderProcess.Opened.NeedsPassword -> throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
                    RenderProcess.Opened.WrongPassword -> {
                        PdfPasswords.remove(uri)
                        throw DocumentOpenException(DocumentOpenException.Reason.WrongPassword)
                    }
                }
            }
            try {
                val engine = try {
                    PdfEngine.open(fd, password)
                } catch (e: SecurityException) {
                    // The renderer's way of saying the file is encrypted, or the password is wrong.
                    if (password == null) throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
                    PdfPasswords.remove(uri)
                    throw DocumentOpenException(DocumentOpenException.Reason.WrongPassword)
                }
                if (engine.pageSizes.isEmpty()) {
                    engine.close()
                    throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                }
                PdfDocument(Local(fd, engine), null, app, engine.pageSizes, reopen, password, File(app.cacheDir, TEMP_DIR))
            } catch (e: Exception) {
                fd.close()
                throw e
            }
        }

        /** Pages in [file] when Android reads it, each opened once (an export is checked like this). */
        suspend fun pageCount(context: Context, file: File): Int = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                if (RenderProcess.available) RenderProcess.countPages(context, fd) else PdfEngine.countPages(fd)
            }
        }
    }
}
