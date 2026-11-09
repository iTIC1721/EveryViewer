package com.tabpdf.ink

import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke

/**
 * Marking text with the highlighter (기획서 5.8.7): held still on text, the highlighter stops
 * drawing and selects instead, from the word it is on to wherever it is dragged. Let go, the range
 * stays with handles to adjust it, and is marked when the next thing is done on the page. The
 * viewer does the selecting; handwriting input tells it what the pointer does.
 */
interface TextMark {
    /**
     * A line or the eraser starts at [position] (viewer coordinates). A range waiting to be marked
     * is marked first; then true, and a tap (no line drawn) leaves no dot. With the [highlighter],
     * the text there is looked up, in case it is held.
     */
    fun down(position: Offset, highlighter: Boolean): Boolean

    /** The highlighter was held still where it went [down]: true if marking text starts there. */
    fun held(): Boolean

    /** The marking pointer moved to [position]. */
    fun moved(position: Offset)
}

/** How long the highlighter is held still on text before it marks the text instead of drawing. */
const val TEXT_MARK_HOLD_MS = 400L

/**
 * Marked text as highlighter strokes, one straight stroke along each line: [lines] are pages with
 * the boxes of their marked lines in fractions of the page, [aspect] a page's height over its width.
 * Each stroke is as thick as its line is high and runs from its first letter to its last (the
 * highlighter's ends are square, ending where its points do).
 */
fun markStrokes(lines: List<Pair<Int, List<RectF>>>, aspect: (Int) -> Float, color: Int): List<Pair<Int, Stroke>> =
    lines.flatMap { (page, rects) ->
        val height = INK_PAGE_WIDTH * aspect(page)
        rects.mapNotNull { r ->
            val left = r.left * INK_PAGE_WIDTH
            val right = r.right * INK_PAGE_WIDTH
            val top = r.top * height
            val bottom = r.bottom * height
            val size = bottom - top
            if (size <= 0f || right <= left) return@mapNotNull null
            val y = (top + bottom) / 2
            val batch = MutableStrokeInputBatch()
            batch.add(InputToolType.STYLUS, left, y, 0L)
            batch.add(InputToolType.STYLUS, right, y, 100L)
            page to Stroke(Brushes.forTool(InkTool.Highlighter, ToolStyle(color, size)), batch)
        }
    }
