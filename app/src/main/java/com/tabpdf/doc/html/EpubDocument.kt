package com.tabpdf.doc.html

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.FlowDocument
import com.tabpdf.doc.LayoutProgress
import com.tabpdf.doc.PageSize
import com.tabpdf.doc.SearchMatch
import com.tabpdf.doc.TextSearch
import com.tabpdf.doc.TextSelection
import com.tabpdf.doc.awaitComplete
import com.tabpdf.doc.office.Element
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * E-books (.epub, 기획서 5.7, 11장 27번): the chapters in reading order, each an XHTML page laid out
 * as HTML at the same phone-wide width, one after another without gaps; each chapter starts on a
 * new page. The book's own style sheets and pictures are used. The first chapter shows at once;
 * the rest are laid out behind it ([progress]), always the same way so handwriting stays put.
 * Books locked by DRM can't be read.
 */
class EpubDocument private constructor(
    private var parts: ZipParts?,
    private val spine: List<String>,
    first: FlowDocument,
) : Document {
    /** Chapters laid out so far, and where each one's pages start; replaced whole as it grows. */
    private class Laid(val chapters: List<FlowDocument>, val starts: IntArray, val pages: Int)

    @Volatile
    private var laid = laidOf(listOf(first))

    private val _progress = MutableStateFlow(LayoutProgress(laid.pages, complete = spine.size == 1))
    override val progress: StateFlow<LayoutProgress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        if (spine.size > 1) scope.launch {
            val done = mutableListOf(first)
            for (path in spine.drop(1)) {
                ensureActive()
                done += chapter(path)
                laid = laidOf(done.toList())
                _progress.value = LayoutProgress(laid.pages, complete = done.size == spine.size)
            }
            // Everything is laid out; the pictures in use are kept by their pages.
            parts = null
        } else {
            parts = null
        }
    }

    private fun laidOf(chapters: List<FlowDocument>): Laid {
        val starts = IntArray(chapters.size)
        var pages = 0
        for ((i, c) in chapters.withIndex()) {
            starts[i] = pages
            pages += c.pageCount
        }
        return Laid(chapters, starts, pages)
    }

    private fun chapter(path: String): FlowDocument = layOut(parts ?: throw IOException("Closed"), path)

    /** Page [index] of the book as (chapter, page in it). */
    private fun locate(index: Int): Pair<FlowDocument, Int> {
        val l = laid
        if (index !in 0 until l.pages) throw IndexOutOfBoundsException("Page $index")
        var i = java.util.Arrays.binarySearch(l.starts, index)
        if (i < 0) i = -i - 2
        // Empty chapters share a start with the next; take the one that has the page.
        while (i + 1 < l.starts.size && l.starts[i + 1] <= index) i++
        return l.chapters[i] to index - l.starts[i]
    }

    override val pageSizes: List<PageSize> = object : AbstractList<PageSize>() {
        override val size: Int get() = laid.pages
        override fun get(index: Int): PageSize = locate(index).let { (c, p) -> c.pageSizes[p] }
    }

    override val pageGaps: Boolean get() = false

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? {
        val (c, p) = locate(index)
        return c.renderPage(p, widthPx)
    }

    override suspend fun drawPage(index: Int, canvas: Canvas): Boolean {
        val (c, p) = locate(index)
        return c.drawPage(p, canvas)
    }

    override fun paperWidth(index: Int): Float = locate(index).let { (c, p) -> c.paperWidth(p) }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val (c, p) = locate(index)
        return c.linkAt(p, x, y)
    }

    override suspend fun search(query: String): List<SearchMatch> {
        // As for long text: results once the whole book is laid out.
        awaitComplete()
        val l = laid
        val result = mutableListOf<SearchMatch>()
        for ((i, c) in l.chapters.withIndex()) {
            currentCoroutineContext().ensureActive()
            for (m in c.search(query)) {
                result += SearchMatch(l.starts[i] + m.page, m.rects)
                if (result.size >= TextSearch.MAX_MATCHES) return result
            }
        }
        return result
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? {
        val (c, p) = locate(index)
        return c.selectText(p, from, to)
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? {
        val (c, p) = locate(index)
        return c.selectWord(p, at)
    }

    override suspend fun close() = withContext(NonCancellable) {
        scope.cancel()
        for (c in laid.chapters) c.close()
    }

    companion object {
        /** Room at the sides of a book's pages, in CSS pixels (books leave margins to the reader). */
        private const val MARGIN = 12f

        /** Font obfuscation (IDPF, Adobe) only hides embedded fonts; anything else encrypted is DRM. */
        private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

        suspend fun open(context: Context, uri: Uri): Document {
            val parts = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { ZipParts.read(it) } ?: throw FileNotFoundException(uri.toString())
            }
            return withContext(Dispatchers.Default) {
                val spine = spine(parts)
                if (spine.isEmpty()) throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
                EpubDocument(parts, spine, layOut(parts, spine.first()))
            }
        }

        /** The chapters' paths in reading order, from the package document the container points to. */
        fun spine(parts: ZipParts): List<String> {
            parts.xml("META-INF/encryption.xml")?.let { encryption ->
                val methods = encryption.findAll("EncryptionMethod") + encryption.findAll("enc:EncryptionMethod")
                if (methods.any { it.attr("Algorithm") !in FONT_OBFUSCATION }) throw DocumentOpenException(DocumentOpenException.Reason.Protected)
            }
            val container = parts.xml("META-INF/container.xml") ?: throw IOException("Not an EPUB: no container")
            val opfPath = all(container, "rootfile").firstNotNullOfOrNull { it.attr("full-path") } ?: throw IOException("No package document")
            val opf = parts.xml(opfPath) ?: throw IOException("Missing package document")
            val items = all(opf, "item").mapNotNull { item ->
                val id = item.attr("id") ?: return@mapNotNull null
                val href = item.attr("href") ?: return@mapNotNull null
                id to resolve(opfPath, href)
            }.toMap()
            return all(opf, "itemref").mapNotNull { ref -> items[ref.attr("idref")] }.filter { parts[it] != null }
        }

        /** Elements named [local] with any namespace prefix (package documents use them or not). */
        private fun all(root: Element, local: String): List<Element> =
            (root.findAll(local) + root.findAll("opf:$local") + root.findAll("ocf:$local")).distinct()

        /** [href] as written in the part at [from], as a path in the zip: relative, %-escaped, no fragment. */
        fun resolve(from: String, href: String): String {
            val clean = href.substringBefore('#').substringBefore('?')
            val decoded = try {
                URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                clean
            }
            if (decoded.startsWith("/")) return decoded.removePrefix("/")
            val path = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            for (piece in decoded.split('/')) {
                when (piece) {
                    ".." -> if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    ".", "" -> Unit
                    else -> path += piece
                }
            }
            return path.joinToString("/")
        }

        /** One chapter laid out; a chapter that can't be read is left empty rather than failing the book. */
        private fun layOut(parts: ZipParts, path: String): FlowDocument {
            return try {
                val bytes = parts[path] ?: return HtmlDocument.render("")
                HtmlDocument.render(HtmlDocument.decode(bytes), { href -> parts[resolve(path, href)] }, MARGIN)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("EveryViewer", "Chapter $path not laid out", e)
                HtmlDocument.render("")
            } catch (e: OutOfMemoryError) {
                HtmlDocument.render("")
            }
        }
    }
}
