package com.tabpdf.doc

import android.graphics.PointF
import android.graphics.RectF
import android.text.Layout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Selected text and the rectangles it covers on its page (fractions of the page), one per line. */
class TextSelection(val text: String, val rects: List<RectF>)

/** Receives a block's characters in reading order, each with its box (block or page coordinates). */
interface TextSink {
    fun char(c: Char, box: RectF)

    /** The end of a paragraph, cell or text box: text after it starts on a new line. */
    fun endBlock()
}

/**
 * The text of one page in reading order, each character with its box in page fractions (기획서 5.5).
 * Selections run between two caret positions, found from points.
 */
class PageText(private val text: String, private val boxes: List<RectF>) {

    /** The text between [from] and [to] (either order), or null if none. */
    fun select(from: PointF, to: PointF): TextSelection? {
        if (text.isEmpty()) return null
        val a = caretAt(from)
        val b = caretAt(to)
        return range(min(a, b), max(a, b))
    }

    /** The word (or the single character, if not a letter) at [at], or null if no text is there. */
    fun word(at: PointF): TextSelection? {
        val i = charAt(at) ?: return null
        if (!text[i].isLetterOrDigit()) return if (text[i].isWhitespace()) null else range(i, i + 1)
        var start = i
        var end = i + 1
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        return range(start, end)
    }

    private fun range(start: Int, end: Int): TextSelection? {
        if (start >= end) return null
        val rects = mutableListOf<RectF>()
        for (i in start until end) {
            val box = boxes[i]
            if (text[i] == '\n') continue
            val last = rects.lastOrNull()
            // Characters on one line join into one rectangle.
            if (last != null && abs(last.centerY() - box.centerY()) < last.height() / 2) last.union(box) else rects += RectF(box)
        }
        return TextSelection(text.substring(start, end), rects)
    }

    /** The character whose box holds [p], or the nearest on the line [p] is on; null if [p] is off every line. */
    private fun charAt(p: PointF): Int? {
        var best: Int? = null
        var bestDx = Float.MAX_VALUE
        for (i in boxes.indices) {
            val b = boxes[i]
            if (text[i] == '\n' || p.y < b.top || p.y > b.bottom) continue
            val dx = if (p.x < b.left) b.left - p.x else if (p.x > b.right) p.x - b.right else 0f
            if (dx < bestDx) {
                bestDx = dx
                best = i
            }
        }
        return best?.takeIf { bestDx < 0.05f }
    }

    /** Where a selection end at [p] falls between characters: on [p]'s line, or the nearest line. */
    private fun caretAt(p: PointF): Int {
        var best = 0
        var bestScore = Float.MAX_VALUE
        for (i in boxes.indices) {
            val b = boxes[i]
            val dy = if (p.y < b.top) b.top - p.y else if (p.y > b.bottom) p.y - b.bottom else 0f
            val dx = if (p.x < b.left) b.left - p.x else if (p.x > b.right) p.x - b.right else 0f
            // Being on the line matters far more than how far along it.
            val score = dy * 100f + dx
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        val b = boxes[best]
        val after = p.y > b.bottom || (p.y >= b.top && p.x > b.centerX())
        return if (after) best + 1 else best
    }

    /** Collects a page's characters; [toPage] turns a box into page fractions. */
    class Builder(private val toPage: (RectF) -> RectF) : TextSink {
        private val text = StringBuilder()
        private val boxes = mutableListOf<RectF>()

        override fun char(c: Char, box: RectF) {
            text.append(c)
            boxes += toPage(box)
        }

        override fun endBlock() {
            if (text.isEmpty() || text.last() == '\n') return
            val last = boxes.last()
            text.append('\n')
            boxes += RectF(last.right, last.top, last.right, last.bottom)
        }

        fun build() = PageText(text.toString(), boxes)
    }
}

/** Every character of [layout] with its box, offset by ([dx], [dy]); the invisible ones layout adds are left out. */
fun collectLayoutText(layout: Layout, sink: TextSink, dx: Float, dy: Float) {
    val text = layout.text
    for (line in 0 until layout.lineCount) {
        val (top, bottom) = TextSearch.lineBand(layout, line)
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        var x1 = layout.getPrimaryHorizontal(start)
        for (i in start until end) {
            val x2 = if (i + 1 < end) layout.getPrimaryHorizontal(i + 1) else layout.getLineRight(line)
            val c = when (val raw = text[i]) {
                '⁠', '­', '￼' -> null
                ' ', ' ' -> ' '
                else -> raw
            }
            if (c != null) sink.char(c, RectF(min(x1, x2) + dx, top + dy, max(x1, x2) + dx, bottom + dy))
            x1 = x2
        }
    }
    sink.endBlock()
}

/** [sink] seen through a move: characters given in a block's coordinates arrive in its parent's. */
fun TextSink.offset(dx: Float, dy: Float): TextSink = object : TextSink {
    override fun char(c: Char, box: RectF) = this@offset.char(c, RectF(box).apply { offset(dx, dy) })
    override fun endBlock() = this@offset.endBlock()
}

/** [sink] taking only characters whose middle is between [top] and [bottom] (one page of a flow). */
fun TextSink.clipped(top: Float, bottom: Float): TextSink = object : TextSink {
    override fun char(c: Char, box: RectF) {
        if (box.centerY() >= top && box.centerY() < bottom) this@clipped.char(c, box)
    }
    override fun endBlock() = this@clipped.endBlock()
}
