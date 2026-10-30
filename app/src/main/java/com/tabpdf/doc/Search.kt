package com.tabpdf.doc

import android.graphics.RectF
import android.text.Layout
import kotlin.math.max
import kotlin.math.min

/**
 * One place [Document.search] found the text: its page, and the rectangles it covers there (one
 * per line it runs over) as fractions of the page's width and height.
 */
class SearchMatch(val page: Int, val rects: List<RectF>)

/** A match in laid-out content, in that content's own coordinates: one rectangle per line. */
typealias Found = List<RectF>

fun Found.offset(dx: Float, dy: Float): Found = map { RectF(it).apply { offset(dx, dy) } }

/**
 * How text is searched (기획서 5.5): ignoring case, and the invisible characters layout puts in
 * (word joiners that keep Korean words whole, object placeholders for bullets and pictures).
 */
object TextSearch {
    /** More matches than this are not collected: the count shows as "N+". */
    const val MAX_MATCHES = 1000

    fun normalize(query: String): String = query.lowercase()

    /** Whether [query] (already [normalize]d) occurs in [text], as [find] would find it once laid out. */
    fun contains(text: CharSequence, query: String): Boolean =
        query.isNotEmpty() && searchable(text, null).indexOf(query) >= 0

    /** Every match of [query] (already [normalize]d) in [layout]'s text. */
    fun find(layout: Layout, query: String): List<Found> {
        if (query.isEmpty()) return emptyList()
        val text = layout.text
        // Where each searchable character sits in the layout's text.
        val at = IntArray(text.length)
        val plain = searchable(text, at)
        val result = mutableListOf<Found>()
        var from = 0
        while (true) {
            val start = plain.indexOf(query, from)
            if (start < 0) break
            val end = start + query.length
            result += rects(layout, at[start], at[end - 1] + 1)
            from = end
        }
        return result
    }

    /**
     * [text] as searched: lowercase, without the invisible characters layout adds. [at], if given,
     * receives where each character of the result came from in [text].
     */
    private fun searchable(text: CharSequence, at: IntArray?): StringBuilder {
        val plain = StringBuilder(text.length)
        for (i in text.indices) {
            val c = when (val raw = text[i]) {
                '⁠', '­', '​' -> continue
                '￼' -> '\u0000'
                ' ', ' ' -> ' '
                else -> raw.lowercaseChar()
            }
            at?.set(plain.length, i)
            plain.append(c)
        }
        return plain
    }

    /** The text from [start] to [end] in [layout], one rectangle per line, around the letters. */
    private fun rects(layout: Layout, start: Int, end: Int): Found {
        val first = layout.getLineForOffset(start)
        val last = layout.getLineForOffset(max(start, end - 1))
        return (first..last).map { line ->
            val lineStart = layout.getLineStart(line)
            val lineEnd = layout.getLineEnd(line)
            val a = max(start, lineStart)
            val b = min(end, lineEnd)
            val x1 = layout.getPrimaryHorizontal(a)
            val x2 = if (b >= lineEnd) layout.getLineRight(line) else layout.getPrimaryHorizontal(b)
            val (top, bottom) = lineBand(layout, line)
            RectF(min(x1, x2), top, max(x1, x2), bottom)
        }
    }

    /**
     * Top and bottom of the letters of [line]. Lines may carry spacing below the text (line pitch
     * over 1); marks and selections stay on the letters.
     */
    fun lineBand(layout: Layout, line: Int): Pair<Float, Float> {
        val top = layout.getLineTop(line).toFloat()
        val baseline = layout.getLineBaseline(line).toFloat()
        return top to min(layout.getLineBottom(line).toFloat(), baseline + (baseline - top) * 0.3f)
    }
}
