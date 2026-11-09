package com.tabpdf.ink

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Marked text becomes one straight highlighter stroke along each line (기획서 5.8.7). */
@RunWith(AndroidJUnit4::class)
class TextMarksTest {
    @Test
    fun oneStrokePerLineAsThickAsTheLine() {
        val yellow = Brushes.highlighterColors.first()
        val lines = listOf(
            0 to listOf(RectF(0.1f, 0.10f, 0.9f, 0.12f), RectF(0.1f, 0.12f, 0.4f, 0.14f)),
            1 to listOf(RectF(0.2f, 0.5f, 0.6f, 0.55f), RectF(0.5f, 0.5f, 0.5f, 0.55f)), // the last is empty
        )
        val aspects = mapOf(0 to 1.5f, 1 to 0.5f)
        val strokes = markStrokes(lines, { aspects.getValue(it) }, yellow)

        assertEquals(listOf(0, 0, 1), strokes.map { it.first })
        val first = strokes[0].second
        assertEquals(yellow, first.brush.colorIntArgb)
        assertEquals(0.02f * 1500f, first.brush.size, 0.01f)
        assertEquals(2, first.inputs.size)
        assertEquals(100f, first.inputs.get(0).x, 0.01f)
        assertEquals(900f, first.inputs.get(1).x, 0.01f)
        assertEquals(0.11f * 1500f, first.inputs.get(0).y, 0.01f)
        assertEquals(0.11f * 1500f, first.inputs.get(1).y, 0.01f)
        // Page 1 is half as high as wide: its fractions are of 500 units.
        assertEquals(0.05f * 500f, strokes[2].second.brush.size, 0.01f)
        assertEquals(0.525f * 500f, strokes[2].second.inputs.get(0).y, 0.01f)
    }
}
