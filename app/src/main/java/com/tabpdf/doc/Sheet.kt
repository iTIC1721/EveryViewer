package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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

    override fun draw(canvas: Canvas) {
        for (p in placed) {
            canvas.save()
            canvas.translate(p.x, p.y)
            p.block.draw(canvas)
            canvas.restore()
        }
    }

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
) : Document {
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
            drawAll(canvas, frame.header)
            drawAll(canvas, frame.footer)
            val top = boundaries[index]
            val bottom = boundaries[index + 1]
            canvas.save()
            canvas.clipRect(0f, frame.bodyTop, FLOW_WIDTH, frame.bodyTop + (bottom - top))
            canvas.translate(0f, frame.bodyTop - top)
            drawAll(canvas, placed.filter { it.bottom > top && it.y < bottom })
            canvas.restore()
            bitmap
        }
    }

    private fun drawAll(canvas: Canvas, blocks: List<Placed>) {
        for (p in blocks) {
            canvas.save()
            canvas.translate(p.x, p.y)
            p.block.draw(canvas)
            canvas.restore()
        }
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val sheetY = y * frame.height
        val flowX = x * FLOW_WIDTH
        if (sheetY < frame.bodyTop) return null
        val flowY = boundaries[index] + sheetY - frame.bodyTop
        if (flowY >= boundaries[index + 1]) return null
        return placed.firstOrNull { flowY >= it.y && flowY < it.bottom && flowX >= it.x }
            ?.let { it.block.linkAt(flowX - it.x, flowY - it.y) }
    }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock { closed = true }
    }
}
