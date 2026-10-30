package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.util.LruCache
import android.graphics.RectF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** A stack of blocks shown as one (a table cell, a header). */
class StackBlock(private val placed: List<Placed>, override val height: Float) : FlowBlock() {
    /** Between its blocks, and wherever its blocks allow. */
    override val breaks: FloatArray = buildList {
        for (p in placed) {
            add(p.y)
            p.block.breaks.forEach { add(p.y + it) }
            add(p.bottom)
        }
    }.filter { it > 0f && it < height }.distinct().sorted().toFloatArray()

    override val pageEnds: FloatArray = placed.flatMap { p -> p.block.pageEnds.map { p.y + it } }.distinct().sorted().toFloatArray()

    override fun draw(canvas: Canvas) {
        // Only what shows: a stack can hold a whole document (an HTML page on a background).
        val clip = canvas.clipBounds
        for (p in placed) {
            if (p.bottom < clip.top || p.y > clip.bottom) continue
            canvas.save()
            canvas.translate(p.x, p.y)
            p.block.draw(canvas)
            canvas.restore()
        }
    }

    override fun collectText(sink: TextSink) = placed.forEach { it.block.collectText(sink.offset(it.x, it.y)) }

    override fun find(query: String): List<Found> = placed.flatMap { p -> p.block.find(query).map { it.offset(p.x, p.y) } }

    override fun linkAt(x: Float, y: Float): String? = placed
        .firstOrNull { y >= it.y && y < it.bottom && x >= it.x }
        ?.let { it.block.linkAt(x - it.x, y - it.y) }
}

/**
 * Where the body of a sheet goes, in flow units (a sheet is [FLOW_WIDTH] wide): the body is
 * [bodyHeight] tall starting [bodyTop] from the top; [header] and [footer] repeat on every sheet,
 * placed in sheet coordinates.
 */
class SheetFrame(
    val height: Float,
    val bodyTop: Float,
    val bodyHeight: Float,
    val header: List<Placed>,
    val footer: List<Placed>,
)

/**
 * A document of real sheets of paper (docx, hwpx; 기획서 5.7): the body flow is cut into
 * page-sized slices, each drawn on its own sheet between the repeating header and footer.
 */
class SheetDocument(
    private val placed: List<Placed>,
    private val boundaries: FloatArray,
    private val frame: SheetFrame,
    /** The sheet's width on paper, in points. */
    private val paperWidth: Float,
    /** Outline headings, in body flow positions. */
    private val headings: List<FlowHeading> = emptyList(),
    /** For each sheet, the table whose heading rows are drawn again at its top, if any. */
    private val repeated: List<Placed?> = emptyList(),
) : Document {
    /** How far down the sheet's body its slice of the flow starts, below repeated heading rows. */
    private fun lead(index: Int): Float = repeated.getOrNull(index)?.block?.repeatedTop ?: 0f

    override suspend fun tableOfContents(): List<TocEntry> = TocEntry.tree(headings.map { h ->
        val (page, down) = pageAt(boundaries, h.y)
        // The body slice sits below the header on the sheet.
        val bodyY = down * (boundaries[page + 1] - boundaries[page])
        TocEntry.Flat(h.level, h.title, page, ((frame.bodyTop + lead(page) + bodyY) / frame.height).coerceIn(0f, 1f))
    })

    private val mutex = Mutex()
    private var closed = false

    override val pageSizes: List<PageSize> = List(boundaries.size - 1) { PageSize(FLOW_WIDTH, frame.height) }

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock null
            val scale = widthPx / FLOW_WIDTH
            val heightPx = (frame.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for sheet $index at ${widthPx}x$heightPx", e)
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

    override fun paperWidth(index: Int): Float = paperWidth

    private fun draw(index: Int, canvas: Canvas) {
        drawAll(canvas, frame.header)
        drawAll(canvas, frame.footer)
        val top = boundaries[index]
        val bottom = boundaries[index + 1]
        val lead = lead(index)
        repeated.getOrNull(index)?.let { table ->
            canvas.save()
            canvas.clipRect(0f, frame.bodyTop, FLOW_WIDTH, frame.bodyTop + lead)
            canvas.translate(0f, frame.bodyTop - table.y)
            drawAll(canvas, listOf(table))
            canvas.restore()
        }
        canvas.save()
        canvas.clipRect(0f, frame.bodyTop + lead, FLOW_WIDTH, frame.bodyTop + lead + (bottom - top))
        canvas.translate(0f, frame.bodyTop + lead - top)
        drawAll(canvas, placed.filter { it.bottom > top && it.y < bottom })
        canvas.restore()
    }

    private fun drawAll(canvas: Canvas, blocks: List<Placed>) {
        for (p in blocks) {
            canvas.save()
            canvas.translate(p.x, p.y)
            p.block.draw(canvas)
            canvas.restore()
        }
    }

    /** Searches the body; headers and footers repeat on every sheet and are left out. */
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
                    val shift = frame.bodyTop + lead(page) - boundaries[page]
                    result += SearchMatch(page, rects.map {
                        RectF(it.left / FLOW_WIDTH, (it.top + shift) / frame.height, it.right / FLOW_WIDTH, (it.bottom + shift) / frame.height)
                    })
                    if (result.size >= TextSearch.MAX_MATCHES) return@withLock result
                }
            }
            result
        }
    }

    private val pageTexts = LruCache<Int, PageText>(6)

    /** A sheet's text: header, the body's slice, footer. */
    private fun pageText(index: Int): PageText = pageTexts[index] ?: run {
        val builder = PageText.Builder { RectF(it.left / FLOW_WIDTH, it.top / frame.height, it.right / FLOW_WIDTH, it.bottom / frame.height) }
        for (p in frame.header) p.block.collectText(builder.offset(p.x, p.y))
        val top = boundaries[index]
        val bottom = boundaries[index + 1]
        repeated.getOrNull(index)?.let { table ->
            val heading = builder.offset(0f, frame.bodyTop - table.y).clipped(table.y, table.y + lead(index))
            table.block.collectText(heading.offset(table.x, table.y))
        }
        // Body characters in flow coordinates, kept to this sheet's slice, then moved onto the sheet.
        val body = builder.offset(0f, frame.bodyTop + lead(index) - top).clipped(top, bottom)
        for (p in placed.filter { it.bottom > top && it.y < bottom }) p.block.collectText(body.offset(p.x, p.y))
        for (p in frame.footer) p.block.collectText(builder.offset(p.x, p.y))
        builder.build().also { pageTexts.put(index, it) }
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).select(from, to) }
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).word(at) }
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val sheetY = y * frame.height
        val flowX = x * FLOW_WIDTH
        if (sheetY < frame.bodyTop + lead(index)) return null
        val flowY = boundaries[index] + sheetY - frame.bodyTop - lead(index)
        if (flowY >= boundaries[index + 1]) return null
        return placed.firstOrNull { flowY >= it.y && flowY < it.bottom && flowX >= it.x }
            ?.let { it.block.linkAt(flowX - it.x, flowY - it.y) }
    }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closed = true }
    }
}
