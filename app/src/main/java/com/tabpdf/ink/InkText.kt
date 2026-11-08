package com.tabpdf.ink

import com.tabpdf.data.InkTextEntity
import org.json.JSONArray
import org.json.JSONObject

/*
 * Typed text on a page (기획서 5.8.6): a box at a place on the page, in the same page units as the
 * strokes, holding text whose characters each carry their own style and whose paragraphs each have
 * an alignment. Laid out at a fixed width in page units, so it wraps the same at any zoom.
 */

/** How a run of characters looks. [size] is in page units (a page is [INK_PAGE_WIDTH] wide). */
data class CharStyle(
    val size: Float,
    val color: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
)

/** Characters [start] until [end] look like [style]. */
data class StyleRun(val start: Int, val end: Int, val style: CharStyle)

enum class TextAlign(val key: String) {
    Left("left"),
    Center("center"),
    Right("right");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: Left
    }
}

/**
 * Styled text: [runs] cover the text from start to end without gaps (empty for empty text),
 * [aligns] has one entry per paragraph (paragraphs are split by '\n'). [base] is how characters
 * typed into empty text look.
 */
class RichText private constructor(
    val text: String,
    val runs: List<StyleRun>,
    val aligns: List<TextAlign>,
    val base: CharStyle,
) {
    val length get() = text.length

    /** The style a character typed at [offset] gets: that of the character before it (the first one at the start). */
    fun styleAt(offset: Int): CharStyle {
        if (runs.isEmpty()) return base
        val at = if (offset <= 0) 0 else offset - 1
        return runs.firstOrNull { at >= it.start && at < it.end }?.style ?: runs.last().style
    }

    /** The styles of the characters from [start] to [end]; just the typing style when the range is empty. */
    fun stylesIn(start: Int, end: Int): List<CharStyle> {
        if (start >= end) return listOf(styleAt(start))
        return runs.filter { it.end > start && it.start < end }.map { it.style }.ifEmpty { listOf(styleAt(start)) }
    }

    /** Paragraph that the character at [offset] is in. */
    fun paragraphAt(offset: Int): Int {
        var count = 0
        for (i in 0 until offset.coerceAtMost(text.length)) if (text[i] == '\n') count++
        return count
    }

    /** Where paragraph [index] starts and ends (without its '\n'). */
    fun paragraphRange(index: Int): IntRange {
        var start = 0
        repeat(index) { start = text.indexOf('\n', start) + 1 }
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return start until end
    }

    /** Characters [start] until [end] replaced by [inserted], which looks like [style]. */
    fun replace(start: Int, end: Int, inserted: String, style: CharStyle): RichText {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val newText = text.substring(0, s) + inserted + text.substring(e)
        val shift = inserted.length - (e - s)
        val pieces = mutableListOf<StyleRun>()
        for (r in runs) {
            if (r.start < s) pieces += StyleRun(r.start, minOf(r.end, s), r.style)
        }
        if (inserted.isNotEmpty()) pieces += StyleRun(s, s + inserted.length, style)
        for (r in runs) {
            if (r.end > e) pieces += StyleRun(maxOf(r.start, e) + shift, r.end + shift, r.style)
        }
        // Paragraphs: those whose breaks were removed join the one they were in; new breaks copy it.
        val paragraph = paragraphAt(s)
        val removed = text.substring(s, e).count { it == '\n' }
        val added = inserted.count { it == '\n' }
        val aligns = this.aligns.toMutableList()
        val own = aligns.getOrElse(paragraph) { TextAlign.Left }
        repeat(removed) { if (paragraph + 1 < aligns.size) aligns.removeAt(paragraph + 1) }
        repeat(added) { aligns.add((paragraph + 1).coerceAtMost(aligns.size), own) }
        return of(newText, pieces, aligns, if (newText.isEmpty()) style else base)
    }

    /**
     * What the keyboard did: characters [start] until [end] became [inserted]. Keyboards rewrite
     * whole words (the word being composed, or one the cursor was put in), so only the characters
     * that really changed are replaced: the rest keep their styles. New characters look like
     * [chosen] if a style was chosen for typing, else like the first character they replace, else
     * like the one before them.
     */
    fun edit(start: Int, end: Int, inserted: String, chosen: CharStyle?): RichText {
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        val old = text.substring(s, e)
        var prefix = 0
        while (prefix < old.length && prefix < inserted.length && old[prefix] == inserted[prefix]) prefix++
        var suffix = 0
        while (suffix < old.length - prefix && suffix < inserted.length - prefix &&
            old[old.length - 1 - suffix] == inserted[inserted.length - 1 - suffix]
        ) suffix++
        val from = s + prefix
        val to = e - suffix
        val middle = inserted.substring(prefix, inserted.length - suffix)
        if (from == to && middle.isEmpty()) return this
        val style = chosen ?: if (to > from) stylesIn(from, from + 1).first() else styleAt(from)
        return replace(from, to, middle, style)
    }

    /** Characters [start] until [end] restyled by [change]. */
    fun restyle(start: Int, end: Int, change: (CharStyle) -> CharStyle): RichText {
        if (start >= end) return this
        val pieces = mutableListOf<StyleRun>()
        for (r in runs) {
            if (r.end <= start || r.start >= end) {
                pieces += r
                continue
            }
            if (r.start < start) pieces += StyleRun(r.start, start, r.style)
            pieces += StyleRun(maxOf(r.start, start), minOf(r.end, end), change(r.style))
            if (r.end > end) pieces += StyleRun(end, r.end, r.style)
        }
        return of(text, pieces, aligns, base)
    }

    /** The paragraphs that [start] to [end] touch aligned to [align]. */
    fun align(start: Int, end: Int, align: TextAlign): RichText {
        val first = paragraphAt(start)
        val last = paragraphAt(end)
        return of(text, runs, aligns.mapIndexed { i, a -> if (i in first..last) align else a }, base)
    }

    fun withBase(style: CharStyle) = of(text, runs, aligns, style)

    override fun equals(other: Any?) = other is RichText && other.text == text && other.runs == runs && other.aligns == aligns && other.base == base

    override fun hashCode() = text.hashCode() * 31 + runs.hashCode()

    /** As stored (InkTextEntity.style). */
    fun styleJson(): String {
        val o = JSONObject()
        o.put("base", base.toJson())
        o.put("runs", JSONArray().apply { runs.forEach { put(it.style.toJson().put("s", it.start).put("e", it.end)) } })
        o.put("align", JSONArray().apply { aligns.forEach { put(it.key) } })
        return o.toString()
    }

    companion object {
        fun empty(style: CharStyle) = RichText("", emptyList(), listOf(TextAlign.Left), style)

        /** [runs] tidied: clipped to the text, gaps filled, neighbours that look the same merged. */
        fun of(text: String, runs: List<StyleRun>, aligns: List<TextAlign>, base: CharStyle): RichText {
            val sorted = runs.filter { it.end > it.start }.sortedBy { it.start }
            val out = mutableListOf<StyleRun>()
            var at = 0
            for (r in sorted) {
                val start = maxOf(r.start, at)
                val end = minOf(r.end, text.length)
                if (end <= start) continue
                if (start > at) out.add(StyleRun(at, start, out.lastOrNull()?.style ?: r.style))
                out.add(StyleRun(start, end, r.style))
                at = end
            }
            if (at < text.length) out.add(StyleRun(at, text.length, out.lastOrNull()?.style ?: base))
            val merged = mutableListOf<StyleRun>()
            for (r in out) {
                val last = merged.lastOrNull()
                if (last != null && last.style == r.style && last.end == r.start) merged[merged.lastIndex] = StyleRun(last.start, r.end, r.style)
                else merged += r
            }
            val paragraphs = text.count { it == '\n' } + 1
            val fixed = List(paragraphs) { aligns.getOrElse(it) { aligns.lastOrNull() ?: TextAlign.Left } }
            return RichText(text, merged, fixed, base)
        }

        /** From storage; anything unreadable falls back to [fallback] for the whole text. */
        fun fromJson(text: String, json: String, fallback: CharStyle): RichText = try {
            val o = JSONObject(json)
            val base = o.optJSONObject("base")?.let { charStyle(it, fallback) } ?: fallback
            val runs = o.optJSONArray("runs")?.let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { StyleRun(it.getInt("s"), it.getInt("e"), charStyle(it, base)) } }
            }.orEmpty()
            val aligns = o.optJSONArray("align")?.let { a -> (0 until a.length()).map { TextAlign.fromKey(a.optString(it)) } }.orEmpty()
            of(text, runs, aligns, base)
        } catch (e: org.json.JSONException) {
            of(text, emptyList(), emptyList(), fallback)
        }

        private fun CharStyle.toJson() = JSONObject().apply {
            put("size", size.toDouble())
            put("color", color)
            if (bold) put("b", true)
            if (italic) put("i", true)
            if (underline) put("u", true)
            if (strike) put("x", true)
        }

        private fun charStyle(o: JSONObject, fallback: CharStyle) = CharStyle(
            size = o.optDouble("size", fallback.size.toDouble()).toFloat().coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE),
            color = o.optInt("color", fallback.color),
            bold = o.optBoolean("b"),
            italic = o.optBoolean("i"),
            underline = o.optBoolean("u"),
            strike = o.optBoolean("x"),
        )
    }
}

const val MIN_TEXT_SIZE = 8f
const val MAX_TEXT_SIZE = 200f

/**
 * A text box on page [page] of a tab: its top-left at ([x], [y]) in page units, [width] wide, or
 * as wide as its text up to the page's right edge when [width] is 0.
 */
class InkText(
    val id: String,
    val page: Int,
    val seq: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val content: RichText,
) {
    fun copy(page: Int = this.page, x: Float = this.x, y: Float = this.y, width: Float = this.width, content: RichText = this.content) =
        InkText(id, page, seq, x, y, width, content)

    override fun equals(other: Any?) = other is InkText && other.id == id && other.page == page && other.seq == seq &&
        other.x == x && other.y == y && other.width == width && other.content == content

    override fun hashCode() = id.hashCode()
}

/** Default size of typed text, in page units: about 12 pt on an A4 page. */
const val DEFAULT_TEXT_SIZE = 24f

fun InkText.toEntity(tabId: String) = InkTextEntity(id, tabId, page, seq, x, y, width, content.text, content.styleJson())

fun InkTextEntity.toInkText(fallback: CharStyle) = InkText(id, page, seq, x, y, width, RichText.fromJson(text, style, fallback))
