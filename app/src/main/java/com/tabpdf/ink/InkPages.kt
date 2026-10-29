package com.tabpdf.ink

import androidx.compose.ui.geometry.Offset
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * How a document's pages stack in the viewer, in page units (every page [INK_PAGE_WIDTH] wide):
 * [heights] of the pages, and the [gap] between two of them. Lets a line drawn from one page go on
 * to the next ones (기획서 5.8.3).
 */
class PageLayout(val heights: FloatArray, val gap: Float) {
    /** Where each page's top is, from the first page's top. */
    private val tops = FloatArray(heights.size).also { tops ->
        var y = 0f
        for (i in heights.indices) {
            tops[i] = y
            y += heights[i] + gap
        }
    }

    fun top(page: Int) = tops[page]

    /** The page that [y] (from the first page's top) falls on; a point between pages or past either end goes to the nearest. */
    fun pageAt(y: Float): Int {
        if (heights.isEmpty()) return 0
        var lo = 0
        var hi = heights.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        // In the gap below page lo: the nearer of it and the next.
        val bottom = tops[lo] + heights[lo]
        return if (y > bottom && lo < heights.lastIndex && y - bottom > tops[lo + 1] - y) lo + 1 else lo
    }

    /** Pages that the band from [y1] to [y2] (from the first page's top) touches. */
    fun pagesBetween(y1: Float, y2: Float): IntRange = pageAt(minOf(y1, y2))..pageAt(maxOf(y1, y2))
}

/**
 * [stroke], drawn in page [start]'s coordinates, as the parts of it on each page it crossed, each
 * in its own page's coordinates. A part reaches one input past its page's edge, so the line runs
 * right up to the edge; the page cuts off the rest. A stroke that stays on its page comes back as is.
 */
fun splitAcrossPages(stroke: Stroke, start: Int, layout: PageLayout): List<Pair<Int, Stroke>> {
    val inputs = stroke.inputs
    if (inputs.size == 0 || start !in layout.heights.indices) return listOf(start to stroke)
    val origin = layout.top(start)
    val pages = IntArray(inputs.size) { layout.pageAt(origin + inputs.get(it).y) }
    if (pages.all { it == start }) return listOf(start to stroke)

    val parts = mutableListOf<Pair<Int, Stroke>>()
    var from = 0
    while (from < pages.size) {
        var to = from
        while (to + 1 < pages.size && pages[to + 1] == pages[from]) to++
        val page = pages[from]
        // One input beyond each end of the run, where the line comes in from or goes on to another page.
        val first = (from - 1).coerceAtLeast(0)
        val last = (to + 1).coerceAtMost(pages.lastIndex)
        val shift = layout.top(page) - origin
        val batch = MutableStrokeInputBatch()
        val input = StrokeInput()
        val t0 = inputs.get(first).elapsedTimeMillis
        for (i in first..last) {
            inputs.populate(i, input)
            input.update(
                input.x, input.y - shift, input.elapsedTimeMillis - t0, input.toolType,
                input.strokeUnitLengthCm, input.pressure, input.tiltRadians, input.orientationRadians,
            )
            batch.add(input)
        }
        parts += page to Stroke(stroke.brush, batch)
        from = to + 1
    }
    return parts
}

/** [p] in page [start]'s coordinates, moved into page [page]'s. */
fun PageLayout.move(p: Offset, start: Int, page: Int) = Offset(p.x, p.y - (top(page) - top(start)))
