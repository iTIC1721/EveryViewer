package com.tabpdf.ink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.text.Layout
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.CharacterStyle
import android.text.style.MetricAffectingSpan
import android.text.style.UpdateAppearance
import android.util.LruCache
import com.tabpdf.R
import kotlin.math.ceil

/**
 * The one typeface of text boxes (기획서 5.8.6): Pretendard, as the app's own text. Bold is its
 * SemiBold; italic is drawn slanted (the font has none).
 */
object InkFonts {
    private lateinit var context: Context

    /** Loaded on first use, so the process that only renders files never loads them. */
    val regular: Typeface by lazy { context.resources.getFont(R.font.pretendard_regular) }
    val bold: Typeface by lazy { context.resources.getFont(R.font.pretendard_semibold) }

    /** Slant of italic text, as Android's own fake italic. */
    const val ITALIC_SKEW = -0.25f

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun face(bold: Boolean): Typeface = if (bold) this.bold else regular
}

/*
 * Spans for a text box, set both on the text being edited (an EditText) and on the text drawn on
 * the page, so both lay out alike. Sizes are in px of whatever the text is laid out at: [scale] px
 * per page unit.
 */

/** Typeface and slant. */
class InkFaceSpan(val bold: Boolean, val italic: Boolean) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    override fun updateDrawState(paint: TextPaint) = apply(paint)
    private fun apply(paint: TextPaint) {
        paint.typeface = InkFonts.face(bold)
        paint.textSkewX = if (italic) InkFonts.ITALIC_SKEW else 0f
        paint.isFakeBoldText = false
    }
}

/** Text size in px (a float: Android's own AbsoluteSizeSpan rounds to whole px). */
class InkSizeSpan(val px: Float) : MetricAffectingSpan() {
    override fun updateMeasureState(paint: TextPaint) {
        paint.textSize = px
    }

    override fun updateDrawState(paint: TextPaint) {
        paint.textSize = px
    }
}

class InkColorSpan(val color: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) {
        paint.color = color
    }
}

class InkLineSpan(val underline: Boolean, val strike: Boolean) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(paint: TextPaint) {
        if (underline) paint.isUnderlineText = true
        if (strike) paint.isStrikeThruText = true
    }
}

class InkAlignSpan(val align: TextAlign) : AlignmentSpan {
    override fun getAlignment(): Layout.Alignment = align.layout
}

val TextAlign.layout: Layout.Alignment
    get() = when (this) {
        TextAlign.Left -> Layout.Alignment.ALIGN_NORMAL
        TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
        TextAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
    }

object InkTextLayout {
    /** The narrowest a box is laid out (page units), so one at the page's right edge still holds a letter or two. */
    const val MIN_WIDTH = 40f

    private val ownSpans = arrayOf(
        InkFaceSpan::class.java, InkSizeSpan::class.java, InkColorSpan::class.java, InkLineSpan::class.java, InkAlignSpan::class.java,
    )

    /** Replaces the text box spans on [text] (which holds [rich]'s text) with [rich]'s styles at [scale] px per page unit. */
    fun applySpans(text: Spannable, rich: RichText, scale: Float) {
        for (type in ownSpans) for (span in text.getSpans(0, text.length, type)) text.removeSpan(span)
        val length = minOf(text.length, rich.length)
        for (run in rich.runs) {
            val start = run.start.coerceAtMost(length)
            val end = run.end.coerceAtMost(length)
            if (end <= start) continue
            val st = run.style
            val flags = Spannable.SPAN_EXCLUSIVE_INCLUSIVE
            text.setSpan(InkFaceSpan(st.bold, st.italic), start, end, flags)
            text.setSpan(InkSizeSpan(st.size * scale), start, end, flags)
            text.setSpan(InkColorSpan(st.color), start, end, flags)
            if (st.underline || st.strike) text.setSpan(InkLineSpan(st.underline, st.strike), start, end, flags)
        }
        for (p in rich.aligns.indices) {
            val align = rich.aligns[p]
            if (align == TextAlign.Left) continue
            val range = rich.paragraphRange(p)
            val start = range.first.coerceAtMost(length)
            // Over the paragraph's line break too.
            val end = (range.last + 1 + if (range.last + 1 < length) 1 else 0).coerceAtMost(length)
            if (end > start) text.setSpan(InkAlignSpan(align), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** The paint every text box starts from: [base]'s look at [scale]. Linear, so it measures alike at every size. */
    fun paint(base: CharStyle, scale: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = InkFonts.regular
        textSize = base.size * scale
        color = base.color
        isElegantTextHeight = false
    }

    private fun build(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(false) }
            .build()

    /** Where a box's text can go up to: its own width, or for one that grows, the page's right edge. */
    fun availableWidth(box: InkText) = if (box.width > 0f) box.width else (INK_PAGE_WIDTH - box.x).coerceAtLeast(MIN_WIDTH)

    /** [box] laid out in page units (scale 1). */
    fun layOut(box: InkText): BoxLayout {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) cache.get(box)?.let { return it }
        val text = SpannableString(box.content.text)
        applySpans(text, box.content, 1f)
        val paint = paint(box.content.base, 1f)
        var layout = build(text, paint, ceil(availableWidth(box)).toInt())
        var width = box.width
        if (width <= 0f) {
            var widest = 0f
            for (line in 0 until layout.lineCount) widest = maxOf(widest, layout.getLineWidth(line))
            width = maxOf(ceil(widest), 1f)
            // Centred and right-aligned lines line up within the box, not the rest of the page.
            if (box.content.aligns.any { it != TextAlign.Left }) layout = build(text, paint, width.toInt())
        }
        return BoxLayout(layout, width, layout.height.toFloat()).also { if (onMain) cache.put(box, it) }
    }

    // Main thread only: a layout's paint changes while it draws, so threads must not share one.
    private val cache = LruCache<InkText, BoxLayout>(256)

    /** Draws [boxes] onto [canvas], already scaled to page units. */
    fun draw(canvas: Canvas, boxes: List<InkText>, skip: String? = null) {
        for (box in boxes) {
            if (box.id == skip) continue
            val l = layOut(box)
            canvas.save()
            canvas.translate(box.x, box.y)
            l.layout.draw(canvas)
            canvas.restore()
        }
    }
}

/** A text box's text laid out: [width] × [height] page units. */
class BoxLayout(val layout: StaticLayout, val width: Float, val height: Float)
