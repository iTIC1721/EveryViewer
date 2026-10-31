package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A file that was read fine but can't be shown. */
class DocumentOpenException(val reason: Reason) : IOException(reason.name) {
    enum class Reason {
        PasswordProtected, NoPages, Unsupported, TooLarge,
        /** The password typed for it doesn't open it. */
        WrongPassword,
        /** Locked by DRM or the like (Hangul's certificate encryption, protected e-books). */
        Protected,
        /** A page too tall for its width to be shown (Documents.MAX_PAGE_ASPECT). */
        PageTooTall,
        /** The app ended while opening it last time ([com.tabpdf.tabs.OpenGuard]): opened again only when asked. */
        Interrupted,
    }
}

/**
 * Largest bitmap put on screen. Android won't draw one over 100 MB (RecordingCanvas) and throws
 * while drawing, which nothing can catch: the app would crash, again on every start.
 */
const val MAX_DRAWN_BYTES = 90 * 1024 * 1024

/** A4's width in points: the paper width of pages that have none of their own. */
const val A4_WIDTH = 595f

/** Phone-wide pages (text, HTML, e-books) on paper: as wide as the 480 CSS pixels they are laid out at. */
const val PHONE_PAPER_WIDTH = 360f

/** Page size in the document's own units (PDF points for PDFs); the viewer only uses the ratio. */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = height / width
}

/**
 * An entry of a document's table of contents (기획서 5.5 목차): [title] at [page], [y] down it (a
 * fraction of its height) where known, with the entries under it.
 */
class TocEntry(val title: String, val page: Int, val y: Float?, val children: List<TocEntry> = emptyList()) {
    /** An entry before nesting: [level] 0 is the top. */
    class Flat(val level: Int, val title: String, val page: Int, val y: Float?)

    companion object {
        /** Nests [flat] entries by level: each goes under the last one above it with a lower level. */
        fun tree(flat: List<Flat>): List<TocEntry> {
            class Node(val flat: Flat, val children: MutableList<Node> = mutableListOf())
            val roots = mutableListOf<Node>()
            val stack = ArrayDeque<Node>()
            for (f in flat) {
                while (stack.isNotEmpty() && stack.last().flat.level >= f.level) stack.removeLast()
                val node = Node(f)
                (stack.lastOrNull()?.children ?: roots) += node
                stack.addLast(node)
            }
            fun build(n: Node): TocEntry = TocEntry(n.flat.title, n.flat.page, n.flat.y, n.children.map(::build))
            return roots.map(::build)
        }

        /** [entries] moved down by [pages] (a part of a bundle). */
        fun shift(entries: List<TocEntry>, pages: Int): List<TocEntry> =
            entries.map { TocEntry(it.title, it.page + pages, it.y, shift(it.children, pages)) }
    }
}

/** Where a link in a document leads. */
sealed interface Link {
    /** A web page or mail address (see [isWebLink]). */
    data class Web(val url: String) : Link

    /** A place in the same document: [page], and [y] down it (a fraction of its height) if the link says. */
    data class Page(val page: Int, val y: Float?) : Link
}

/** How far a document laid out while it is shown has got: [pages] so far, and whether that is all. */
data class LayoutProgress(val pages: Int, val complete: Boolean)

/**
 * Any opened file, as the viewer sees it (기획서 5.7): pages of fixed size stacked vertically.
 * Every format is turned into this, so the viewer, thumbnails and reading positions never deal
 * with formats.
 */
interface Document {
    val pageSizes: List<PageSize>

    val pageCount: Int get() = pageSizes.size

    /**
     * For documents laid out in the background while shown (text files, 기획서 5.7): [pageSizes]
     * holds the pages laid out so far and grows until complete; earlier pages never change. Null
     * for documents laid out in full when opened.
     */
    val progress: StateFlow<LayoutProgress>? get() = null

    /** Opened with a password: nothing of it is kept on disk (thumbnails stay in memory). */
    val locked: Boolean get() = false

    /** Something the reader should be told when the document is shown (it is shown other than usual). */
    val notice: String? get() = null

    /**
     * Whether pages are shown apart, like sheets of paper. Text that just flows on is cut into
     * pages only for rendering and is shown without gaps.
     */
    val pageGaps: Boolean get() = true

    /**
     * Renders page [index] at [widthPx] wide (height follows the page's aspect ratio). Returns null
     * if the document was closed meanwhile or there wasn't memory for the bitmap.
     */
    suspend fun renderPage(index: Int, widthPx: Int): Bitmap?

    /**
     * Draws page [index] onto [canvas] as text and shapes rather than a picture, in the page's own
     * units ([pageSizes]), for PDF export (기획서 5.9). False for pages that only come as pictures.
     */
    suspend fun drawPage(index: Int, canvas: Canvas): Boolean = false

    /**
     * Page [index]'s width on paper, in points (1/72 inch), for PDF export: the page's own size
     * where it has one (PDF, Word, slides), else A4's width.
     */
    fun paperWidth(index: Int): Float = A4_WIDTH

    /**
     * The table of contents (기획서 5.5): the file's own (PDF bookmarks, an e-book's), else its
     * headings, slide titles or sheets. Empty where there is none. May take a while (reads the file).
     */
    suspend fun tableOfContents(): List<TocEntry> = emptyList()

    /**
     * Web link at ([x], [y]) on page [index], both as fractions of the page's width and height,
     * or null if nothing there is a link.
     */
    fun linkAt(index: Int, x: Float, y: Float): String? = null

    /** The link at ([x], [y]) on page [index] (as [linkAt]), web or within the document, for a tap. */
    suspend fun link(index: Int, x: Float, y: Float): Link? = linkAt(index, x, y)?.let { Link.Web(it) }

    /**
     * Where [query] occurs, in page order (기획서 5.5), at most [TextSearch.MAX_MATCHES].
     * Documents without text (images) find nothing.
     */
    suspend fun search(query: String): List<SearchMatch> = emptyList()

    /**
     * The text on page [index] between points [from] and [to] (fractions of the page, either
     * order), for selecting and copying (기획서 5.5); null where the page has no text there.
     */
    suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = null

    /** The word at [at] on page [index] (a long press), or null if there is no text there. */
    suspend fun selectWord(index: Int, at: PointF): TextSelection? = null

    suspend fun close()
}

/** Suspends until the document has at least [count] pages, or all it will have. */
suspend fun Document.awaitPages(count: Int) {
    progress?.first { it.complete || it.pages >= count }
}

/** Suspends until the document is laid out in full. */
suspend fun Document.awaitComplete() {
    progress?.first { it.complete }
}

/**
 * [block] on an open document, or [fallback] if it fails — even out of memory or stack. As for
 * [Documents.open]: a crafted file can make drawing, search or selection fail too, and a crash there
 * would come back on every start, as the tab is reopened at the same page.
 */
suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    // Only the kind: messages may hold the file's address (and so its name).
    android.util.Log.w("EveryViewer", "Document failed: ${e.javaClass.simpleName}")
    fallback
} catch (_: OutOfMemoryError) {
    fallback
} catch (_: StackOverflowError) {
    fallback
}
