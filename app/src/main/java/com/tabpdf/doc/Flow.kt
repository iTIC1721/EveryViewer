package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.LruCache
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Text that flows (txt, md, and later docx/hwpx) is laid out once at a fixed virtual width and cut
 * into pages (기획서 5.7). The width never depends on the screen, so a line always wraps at the
 * same word and handwriting stays where it was written, whatever the device or orientation.
 */

/** Width every flow is laid out at, in flow units. Large enough that text metrics are not tiny. */
const val FLOW_WIDTH = 1000f

/** A piece of laid-out content: drawn at its own origin, [height] tall. */
abstract class FlowBlock {
    abstract val height: Float

    /** Offsets inside the block where a page may end (between lines), ascending, in (0, height). */
    open val breaks: FloatArray get() = NO_BREAKS

    abstract fun draw(canvas: Canvas)

    /** Web link at ([x], [y]) in block coordinates. */
    open fun linkAt(x: Float, y: Float): String? = null

    /** Where [query] (normalized by [TextSearch]) occurs in the block's text, in block coordinates. */
    open fun find(query: String): List<Found> = emptyList()

    /** Gives [sink] the block's characters in reading order, with boxes in block coordinates. */
    open fun collectText(sink: TextSink) = Unit

    private companion object {
        val NO_BREAKS = FloatArray(0)
    }
}

/** Empty vertical space. */
class SpaceBlock(override val height: Float) : FlowBlock() {
    override fun draw(canvas: Canvas) = Unit
}

/** A [StaticLayout]; pages may end between any two of its lines. */
open class TextBlock(val layout: StaticLayout) : FlowBlock() {
    override val height: Float = layout.height.toFloat()

    override val breaks: FloatArray = FloatArray((layout.lineCount - 1).coerceAtLeast(0)) { layout.getLineTop(it + 1).toFloat() }

    override fun draw(canvas: Canvas) = layout.draw(canvas)

    override fun find(query: String): List<Found> = TextSearch.find(layout, query)

    override fun collectText(sink: TextSink) = collectLayoutText(layout, sink, 0f, 0f)

    override fun linkAt(x: Float, y: Float): String? {
        val text = layout.text as? Spanned ?: return null
        val line = layout.getLineForVertical(y.roundToInt())
        if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, x)
        return text.getSpans(offset, offset, LinkSpan::class.java).firstOrNull()?.url
    }
}

/** Marks a web link in laid-out text; drawn by whatever colour/underline spans go with it. */
class LinkSpan(val url: String)

/**
 * Whether a document's link may be opened: web pages and mail only. Others (file:, content:,
 * intent: …) could crash the app or hand a crafted address to another app.
 */
fun isWebLink(url: String) =
    url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("mailto:", true)

/** A block placed in the flow at ([x], [y]). */
class Placed(val block: FlowBlock, val x: Float, val y: Float) {
    val bottom: Float get() = y + block.height
}

/** A heading at [y] in the flow, for the table of contents; [level] 0 is the top. */
class FlowHeading(val level: Int, val title: String, val y: Float)

/** Stacks blocks top to bottom. */
class FlowBuilder {
    private val placed = mutableListOf<Placed>()
    private val forced = mutableListOf<Float>()
    var y = 0f
        private set

    /** What has been stacked so far. */
    val blocks: List<Placed> get() = placed

    /** Headings so far, in order (기획서 5.5 목차). */
    val headings = mutableListOf<FlowHeading>()

    /** Where each named place starts (an HTML id), for links into the flow. */
    val anchors = HashMap<String, Float>()

    /** The next block is a heading. */
    fun heading(level: Int, title: String) {
        val t = title.replace(SPACES, " ").trim()
        if (t.isNotEmpty()) headings += FlowHeading(level.coerceIn(0, 8), t, y)
    }

    /** The next block starts the place named [id] (the first of a name counts). */
    fun anchor(id: String) {
        anchors.putIfAbsent(id, y)
    }

    fun add(block: FlowBlock, x: Float = 0f) {
        placed += Placed(block, x, y)
        y += block.height
    }

    fun space(height: Float) {
        y += height
    }

    /** The next block starts a new page (a page break in the document). */
    fun pageBreak() {
        if (y > 0f && forced.lastOrNull() != y) forced += y
    }

    /** Every place a page may end: between blocks, or between lines or rows inside one. */
    fun breaks(): FloatArray = buildList {
        for (p in placed) {
            add(p.y)
            p.block.breaks.forEach { add(p.y + it) }
            add(p.bottom)
        }
    }.distinct().sorted().toFloatArray()

    fun pageBreaks(): FloatArray = forced.toFloatArray()

    /**
     * The finished document, cut into pages of at most [maxPageHeight] at the nearest place a
     * page may end: between blocks or between lines.
     */
    fun build(maxPageHeight: Float, pageGaps: Boolean): FlowDocument =
        FlowDocument(placed.toList(), paginate(y, breaks(), maxPageHeight, pageBreaks()), pageGaps, headings.toList(), anchors.toMap())

    private companion object {
        val SPACES = Regex("\\s+")
    }
}

/**
 * Page boundaries for content [total] tall: 0, then each page's end, ending at [total]. A page
 * ends at a [forced] break (page break) if one comes within [maxPage]; otherwise at the last
 * allowed break no lower than [maxPage] below its top, preferring one that keeps it at least half
 * full; content with no break in reach is cut at [maxPage].
 */
fun paginate(total: Float, breaks: FloatArray, maxPage: Float, forced: FloatArray = FloatArray(0)): FloatArray {
    val result = mutableListOf(0f)
    var top = 0f
    while (true) {
        val limit = top + maxPage
        val pageBreak = forced.firstOrNull { it > top + 0.5f && it <= limit && it < total }
        if (pageBreak == null && total - top <= maxPage) break
        val end = pageBreak
            ?: lastBreakIn(breaks, top + maxPage / 2, limit)
            ?: lastBreakIn(breaks, top, limit)
            ?: limit
        result += end
        top = end
    }
    result += total.coerceAtLeast(top + 1f) // even empty content is one (short) page
    return result.toFloatArray()
}

/** The page of [boundaries] holding flow position [y], and how far down it (a fraction). */
fun pageAt(boundaries: FloatArray, y: Float): Pair<Int, Float> {
    // The last page starting at or above y.
    var lo = 0
    var hi = boundaries.size - 2
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (boundaries[mid] <= y) lo = mid else hi = mid - 1
    }
    val i = lo
    val height = (boundaries[i + 1] - boundaries[i]).coerceAtLeast(1f)
    return i to ((y - boundaries[i]) / height).coerceIn(0f, 1f)
}

/** The last break in (from, to], or null. */
private fun lastBreakIn(breaks: FloatArray, from: Float, to: Float): Float? {
    var lo = 0
    var hi = breaks.size // first index with breaks[i] > to
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (breaks[mid] > to) hi = mid else lo = mid + 1
    }
    val i = lo - 1
    return if (i >= 0 && breaks[i] > from) breaks[i] else null
}

/**
 * Laid-out content shown as pages [FLOW_WIDTH] wide, each a slice of the flow between two
 * [boundaries]. Layouts and paints are not safe to draw from two threads, so renders take turns.
 */
class FlowDocument(
    private val placed: List<Placed>,
    private val boundaries: FloatArray,
    override val pageGaps: Boolean,
    private val headings: List<FlowHeading> = emptyList(),
    private val anchors: Map<String, Float> = emptyMap(),
) : Document {
    private val mutex = Mutex()
    private var closed = false

    override val pageSizes: List<PageSize> =
        (0 until boundaries.size - 1).map { PageSize(FLOW_WIDTH, boundaries[it + 1] - boundaries[it]) }

    override suspend fun tableOfContents(): List<TocEntry> =
        TocEntry.tree(headings.map { h -> place(h.y).let { (page, y) -> TocEntry.Flat(h.level, h.title, page, y) } })

    /** Where the place named [id] is: page, and how far down it (a fraction). */
    fun anchor(id: String): Pair<Int, Float>? = anchors[id]?.let(::place)

    /** The page holding flow position [y], and how far down it. */
    private fun place(y: Float): Pair<Int, Float> = pageAt(boundaries, y)

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock null
            val top = boundaries[index]
            val bottom = boundaries[index + 1]
            val scale = widthPx / FLOW_WIDTH
            val heightPx = ((bottom - top) * scale).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for page $index at ${widthPx}x$heightPx", e)
                return@withLock null
            }
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            draw(index, canvas)
            bitmap
        }
    }

    override suspend fun drawPage(index: Int, canvas: Canvas): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock false
            draw(index, canvas)
            true
        }
    }

    override fun paperWidth(index: Int): Float = PHONE_PAPER_WIDTH

    private fun draw(index: Int, canvas: Canvas) {
        val top = boundaries[index]
        val bottom = boundaries[index + 1]
        canvas.save()
        canvas.translate(0f, -top)
        canvas.clipRect(0f, top, FLOW_WIDTH, bottom)
        for (p in blocksBetween(top, bottom)) {
            canvas.save()
            canvas.translate(p.x, p.y)
            p.block.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.Default) {
        val q = TextSearch.normalize(query)
        mutex.withLock {
            val result = mutableListOf<SearchMatch>()
            if (closed || q.isEmpty()) return@withLock result
            for (p in placed) {
                ensureActive()
                for (found in p.block.find(q)) {
                    val rects = found.offset(p.x, p.y)
                    val page = pageOf(boundaries, rects.first().centerY())
                    val top = boundaries[page]
                    val height = boundaries[page + 1] - top
                    result += SearchMatch(page, rects.map { RectF(it.left / FLOW_WIDTH, (it.top - top) / height, it.right / FLOW_WIDTH, (it.bottom - top) / height) })
                    if (result.size >= TextSearch.MAX_MATCHES) return@withLock result
                }
            }
            result
        }
    }

    /** Page texts for selecting; a drag asks for the same pages again and again. */
    private val pageTexts = LruCache<Int, PageText>(6)

    private fun pageText(index: Int): PageText = pageTexts[index] ?: run {
        val top = boundaries[index]
        val bottom = boundaries[index + 1]
        val height = bottom - top
        val builder = PageText.Builder { RectF(it.left / FLOW_WIDTH, (it.top - top) / height, it.right / FLOW_WIDTH, (it.bottom - top) / height) }
        val page = builder.clipped(top, bottom)
        for (p in blocksBetween(top, bottom)) p.block.collectText(page.offset(p.x, p.y))
        builder.build().also { pageTexts.put(index, it) }
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).select(from, to) }
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).word(at) }
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val top = boundaries[index]
        val flowY = top + y * (boundaries[index + 1] - top)
        val flowX = x * FLOW_WIDTH
        return blocksBetween(flowY, flowY + 0.01f)
            .firstOrNull { flowX >= it.x && flowY < it.bottom }
            ?.let { it.block.linkAt(flowX - it.x, flowY - it.y) }
    }

    /** Blocks overlapping [top, bottom); [placed] is in flow order, so a binary search finds the first. */
    private fun blocksBetween(top: Float, bottom: Float): List<Placed> {
        var lo = 0
        var hi = placed.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (placed[mid].bottom <= top) lo = mid + 1 else hi = mid
        }
        val result = mutableListOf<Placed>()
        var i = lo
        while (i < placed.size && placed[i].y < bottom) {
            if (placed[i].bottom > top) result += placed[i]
            i++
        }
        return result
    }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closed = true }
    }
}

/** The page (between two of [boundaries]) that flow position [y] falls on. */
fun pageOf(boundaries: FloatArray, y: Float): Int {
    var lo = 0
    var hi = boundaries.size - 1 // pages
    while (lo < hi - 1) {
        val mid = (lo + hi) / 2
        if (boundaries[mid] <= y) lo = mid else hi = mid
    }
    return lo.coerceIn(0, (boundaries.size - 2).coerceAtLeast(0))
}

/** Paint for flow text. Linear (unhinted) metrics, since every page is drawn scaled. */
fun flowPaint(size: Float, typeface: Typeface = Typeface.DEFAULT, color: Int = Color.BLACK) =
    TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        textSize = size
        this.typeface = typeface
        this.color = color
    }

fun staticLayout(
    text: CharSequence,
    paint: TextPaint,
    width: Float,
    lineSpacing: Float = 1.25f,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
): StaticLayout =
    StaticLayout.Builder.obtain(text, 0, text.length, paint, width.roundToInt().coerceAtLeast(1))
        .setAlignment(alignment)
        .setLineSpacing(0f, lineSpacing)
        .setIncludePad(false)
        .build()
