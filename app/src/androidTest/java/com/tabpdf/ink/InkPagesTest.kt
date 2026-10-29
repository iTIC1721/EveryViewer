package com.tabpdf.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** A line drawn from one page on to the next ones is kept as its part on each page (기획서 5.8.3). */
@RunWith(AndroidJUnit4::class)
class InkPagesTest {
    private fun line(vararg ys: Float): Stroke {
        val batch = MutableStrokeInputBatch()
        ys.forEachIndexed { i, y -> batch.add(InputToolType.STYLUS, 500f, y, i * 10L) }
        return Stroke(Brushes.forTool(InkTool.Pen, ToolStyle(0xFF000000.toInt(), 3f)), batch)
    }

    private fun Stroke.ys() = (0 until inputs.size).map { inputs.get(it).y }

    @Test
    fun pagesAndGaps() {
        val layout = PageLayout(floatArrayOf(1000f, 500f, 1000f), gap = 20f)
        assertEquals(0, layout.pageAt(-50f))
        assertEquals(0, layout.pageAt(1005f)) // nearer the first page's bottom
        assertEquals(1, layout.pageAt(1015f))
        assertEquals(1, layout.pageAt(1200f))
        assertEquals(2, layout.pageAt(5000f))
        assertEquals(0..2, layout.pagesBetween(900f, 1600f))
    }

    @Test
    fun aLineOnOnePageStaysWhole() {
        val stroke = line(100f, 200f, 300f)
        val parts = splitAcrossPages(stroke, 0, PageLayout(floatArrayOf(1000f, 1000f), 0f))
        assertEquals(1, parts.size)
        assertSame(stroke, parts[0].second)
    }

    @Test
    fun aLineAcrossThreePagesIsCutAtTheirEdges() {
        // Starting on page 1 (top at 1020), going up into page 0 and down into page 2 (top at 1540).
        val layout = PageLayout(floatArrayOf(1000f, 500f, 1000f), gap = 20f)
        val parts = splitAcrossPages(line(400f, 100f, -100f, -400f), 1, layout)
        // Drawn upwards: page 1, then page 0. Each part reaches one input over the edge.
        assertEquals(listOf(1, 0), parts.map { it.first })
        assertEquals(listOf(400f, 100f, -100f), parts[0].second.ys())
        assertEquals(listOf(1120f, 920f, 620f), parts[1].second.ys())

        val down = splitAcrossPages(line(100f, 450f, 550f, 900f), 1, layout)
        assertEquals(listOf(1, 2), down.map { it.first })
        assertEquals(listOf(100f, 450f, 550f), down[0].second.ys())
        assertEquals(listOf(-70f, 30f, 380f), down[1].second.ys())
    }
}
