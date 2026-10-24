package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

/** A block placed in the flow at ([x], [y]). */
class Placed(val block: FlowBlock, val x: Float, val y: Float) {
    val bottom: Float get() = y + block.height
}

/** Stacks blocks top to bottom. */
class FlowBuilder {
    private val placed = mutableListOf<Placed>()
    var y = 0f
        private set

    fun add(block: FlowBlock, x: Float = 0f) {
        placed += Placed(block, x, y)
        y += block.height
    }

    fun space(height: Float) {
        y += height
    }

    /**
     * The finished document, cut into pages of at most [maxPageHeight] at the nearest place a
     * page may end: between blocks or between lines.
     */
    fun build(maxPageHeight: Float, pageGaps: Boolean): FlowDocument {
        val breaks = buildList {
            for (p in placed) {
                add(p.y)
                p.block.breaks.forEach { add(p.y + it) }
                add(p.bottom)
            }
        }.distinct().sorted().toFloatArray()
        return FlowDocument(placed.toList(), paginate(y, breaks, maxPageHeight), pageGaps)
    }
}

/**
 * Page boundaries for content [total] tall: 0, then each page's end, ending at [total]. A page
 * ends at the last allowed break no lower than [maxPage] below its top, preferring one that keeps
 * it at least half full; content with no break in reach is cut at [maxPage].
 */
fun paginate(total: Float, breaks: FloatArray, maxPage: Float): FloatArray {
    val result = mutableListOf(0f)
    var top = 0f
    while (total - top > maxPage) {
        val limit = top + maxPage
        val end = lastBreakIn(breaks, top + maxPage / 2, limit)
            ?: lastBreakIn(breaks, top, limit)
            ?: limit
        result += end
        top = end
    }
    result += total.coerceAtLeast(top + 1f) // even empty content is one (short) page
    return result.toFloatArray()
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
) : Document {
    private val mutex = Mutex()
    private var closed = false

    override val pageSizes: List<PageSize> =
        (0 until boundaries.size - 1).map { PageSize(FLOW_WIDTH, boundaries[it + 1] - boundaries[it]) }

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
            canvas.translate(0f, -top)
            canvas.clipRect(0f, top, FLOW_WIDTH, bottom)
            for (p in blocksBetween(top, bottom)) {
                canvas.save()
                canvas.translate(p.x, p.y)
                p.block.draw(canvas)
                canvas.restore()
            }
            bitmap
        }
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
