package com.tabpdf.doc.office

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import com.tabpdf.doc.FLOW_WIDTH
import com.tabpdf.doc.FlowBlock
import com.tabpdf.doc.FlowBuilder
import com.tabpdf.doc.LinkSpan
import com.tabpdf.doc.Placed
import com.tabpdf.doc.SheetDocument
import com.tabpdf.doc.SheetFrame
import com.tabpdf.doc.StackBlock
import com.tabpdf.doc.TextBlock
import com.tabpdf.doc.flowPaint
import com.tabpdf.doc.paginate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lays an [OfficeDocument] out on sheets (기획서 5.7). The sheet is FLOW_WIDTH units wide, so one
 * point is FLOW_WIDTH / page width units; text keeps its size relative to the sheet, as in print.
 */
class OfficeLayout private constructor(private val unit: Float) {

    companion object {
        fun layOut(doc: OfficeDocument): SheetDocument {
            val page = doc.page
            val layout = OfficeLayout(FLOW_WIDTH / page.width)
            val u = layout.unit
            val left = page.left * u
            val width = (page.width - page.left - page.right) * u

            val body = FlowBuilder()
            layout.blocks(doc.body, width, left, body)

            val header = layout.stack(doc.header, width)
            val footer = layout.stack(doc.footer, width)
            val frame = SheetFrame(
                height = page.height * u,
                bodyTop = page.bodyTop * u,
                bodyHeight = (page.bodyBottom - page.bodyTop) * u,
                header = if (doc.header.isEmpty()) emptyList() else listOf(Placed(header, left, page.headerTop * u)),
                footer = if (doc.footer.isEmpty()) emptyList() else listOf(Placed(footer, left, page.footerBottom * u - footer.height)),
            )
            val boundaries = paginate(body.y, body.breaks(), frame.bodyHeight, body.pageBreaks())
            return SheetDocument(body.blocks.toList(), boundaries, frame)
        }

        /**
         * [blocks] as one block [width] points wide, at [unit] flow units per point (a slide's text
         * box or table). Without [wrap] lines are only broken where the text breaks them and the
         * block is as wide as its longest line. Returns the block and its width in flow units.
         */
        fun box(blocks: List<OfficeBlock>, width: Float, unit: Float, wrap: Boolean = true): Pair<StackBlock, Float> {
            val layout = OfficeLayout(unit)
            val w = if (wrap) width * unit else max(width * unit, layout.naturalWidth(blocks))
            return layout.stack(blocks, w) to w
        }

        /** No break is allowed between two of these (Korean words kept whole, like both editors). */
        private fun isCjk(c: Char): Boolean {
            val block = Character.UnicodeBlock.of(c)
            return block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                block == Character.UnicodeBlock.HANGUL_JAMO ||
                block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
                block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        }

        private const val WORD_JOINER = '⁠'
    }

    /** Lays [list] out top to bottom into [out], [width] wide starting at [x]. */
    private fun blocks(list: List<OfficeBlock>, width: Float, x: Float, out: FlowBuilder) {
        for (block in list) {
            when (block) {
                is Paragraph -> paragraph(block, width, x, out)
                is Table -> {
                    if (block.pageBreakBefore) out.pageBreak()
                    val table = TableBlock(block, width)
                    val offset = when (block.align) {
                        Align.Center -> (width - table.width) / 2
                        Align.Right -> width - table.width
                        else -> block.indent * unit
                    }
                    out.add(table, x + offset.coerceAtLeast(0f))
                }
            }
        }
    }

    /**
     * [list] as one block [width] wide (a cell's content, a header). Hangul doesn't count the
     * spacing under the last line of a cell, so neither do we.
     */
    private fun stack(list: List<OfficeBlock>, width: Float): StackBlock {
        val out = FlowBuilder()
        blocks(list, width, 0f, out)
        val last = out.blocks.lastOrNull()
        val trailing = (last?.block as? ParagraphBlock)?.takeIf { last.bottom == out.y }?.trailingSpace ?: 0f
        return StackBlock(out.blocks.toList(), out.y - trailing)
    }

    // ---- Paragraphs ----

    private fun paragraph(p: Paragraph, width: Float, x: Float, out: FlowBuilder) {
        if (p.pageBreakBefore) out.pageBreak()
        out.space(p.spaceBefore * unit)
        val hanging = (-p.firstLine).coerceAtLeast(0f) * unit
        val left = (p.indentLeft * unit - hanging).coerceAtLeast(0f)
        val textWidth = (width - left - p.indentRight * unit).coerceAtLeast(unit * 20)
        val layout = paragraphLayout(p, textWidth, hanging)
        val spacing = p.lineSpacing
        val trailing = if (spacing is LineSpacing.Multiple && spacing.factor > spacing.textHeight) {
            val lastSize = (layout.text as? Spanned)?.let { t ->
                val start = layout.getLineStart(layout.lineCount - 1)
                t.getSpans(start, t.length, RunStyleSpan::class.java).maxOfOrNull { it.size }
            } ?: 0f
            (spacing.factor - spacing.textHeight) * lastSize
        } else {
            0f
        }
        out.add(ParagraphBlock(layout, trailing), x + left)
        out.space(p.spaceAfter * unit)
    }

    /** Width the paragraphs of [list] take when no line is broken. */
    private fun naturalWidth(list: List<OfficeBlock>): Float = list.filterIsInstance<Paragraph>().maxOfOrNull { p ->
        val hanging = (-p.firstLine).coerceAtLeast(0f) * unit
        val layout = paragraphLayout(p, 1_000_000f, hanging, Align.Left)
        val lines = (0 until layout.lineCount).maxOfOrNull { layout.getLineWidth(it) } ?: 0f
        lines + (p.indentLeft + p.indentRight) * unit - hanging + 1f
    } ?: 0f

    private fun paragraphLayout(p: Paragraph, width: Float, hanging: Float, align: Align = p.align): StaticLayout {
        val text = SpannableStringBuilder()
        var hasObjects = false
        val sizes = mutableListOf<Float>()
        fun appendRun(run: Run) {
            val start = text.length
            when (run) {
                is Run.Text -> {
                    for (c in run.text) {
                        val last = text.lastOrNull()
                        if (p.keepWords && last != null && isCjk(last) && isCjk(c)) text.append(WORD_JOINER)
                        text.append(c)
                    }
                    if (text.length == start) return
                    val s = run.style
                    sizes += s.size
                    text.setSpan(RunStyleSpan(s, unit), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    text.setSpan(ForegroundColorSpan(s.color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (s.background != 0) text.setSpan(BackgroundColorSpan(s.background), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (s.underline) text.setSpan(UnderlineSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (s.strike) text.setSpan(StrikethroughSpan(), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    run.link?.let { text.setSpan(LinkSpan(it), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                is Run.Picture -> {
                    hasObjects = true
                    text.append('￼')
                    val scale = minOf(1f, width / (run.width * unit))
                    text.setSpan(
                        PictureSpan(run.bytes, run.width * unit * scale, run.height * unit * scale),
                        start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is Run.Unsupported -> {
                    hasObjects = true
                    text.append('￼')
                    val scale = minOf(1f, width / (run.width * unit))
                    text.setSpan(EmptyBoxSpan(run.width * unit * scale, run.height * unit * scale), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        p.label?.let {
            if (p.labelInHang && it is Run.Text) {
                val start = text.length
                text.append('￼')
                text.setSpan(LabelSpan(it, unit, hanging), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendRun(it)
                text.append(' ')
            }
        }
        p.runs.forEach(::appendRun)
        val baseSize = sizes.maxOrNull() ?: p.markStyle?.size ?: (p.label as? Run.Text)?.style?.size ?: 10f
        val paint = flowPaint(baseSize * unit)
        if (text.isEmpty()) {
            // An empty paragraph still takes a line, in the size of its paragraph mark.
            text.append(' ')
            text.setSpan(RunStyleSpan(p.markStyle ?: TextStyle(size = baseSize), unit), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (hanging > 0f) text.setSpan(LeadingMarginSpan.Standard(0, hanging.roundToInt()), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        else if (p.firstLine > 0f) text.setSpan(LeadingMarginSpan.Standard((p.firstLine * unit).roundToInt(), 0), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        text.setSpan(LineHeightRule(p.lineSpacing, unit, hasObjects), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.roundToInt().coerceAtLeast(1))
            .setAlignment(
                when (align) {
                    Align.Center -> Layout.Alignment.ALIGN_CENTER
                    Align.Right -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
            .setJustificationMode(if (align == Align.Justify) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
    }

    // ---- Tables ----

    /** A table; pages may end between rows that no merged cell spans. */
    private inner class TableBlock(table: Table, available: Float) : FlowBlock() {
        private val columnWidths: FloatArray
        private val columnLefts: FloatArray
        private val rowTops: FloatArray
        private val cells: List<Pair<TableCell, StackBlock>>
        val width: Float
        override val height: Float
        override val breaks: FloatArray

        init {
            val natural = table.columns.map { it * unit }
            val fit = if (natural.sum() > available) available / natural.sum() else 1f
            columnWidths = natural.map { it * fit }.toFloatArray()
            width = columnWidths.sum()
            columnLefts = FloatArray(columnWidths.size + 1).also { for (i in columnWidths.indices) it[i + 1] = it[i] + columnWidths[i] }

            fun spanWidth(c: TableCell): Float {
                val from = c.column.coerceIn(0, columnWidths.size)
                val to = (c.column + c.columnSpan).coerceIn(from, columnWidths.size)
                return columnLefts[to] - columnLefts[from]
            }
            cells = table.cells.map { c ->
                val inner = (spanWidth(c) - (c.padding.left + c.padding.right) * unit).coerceAtLeast(unit)
                c to stack(c.blocks, inner)
            }
            val rowCount = max(table.rows.size, (table.cells.maxOfOrNull { it.row + it.rowSpan } ?: 0))
            val rowHeights = FloatArray(rowCount) { table.rows.getOrNull(it)?.minHeight?.times(unit) ?: 0f }
            val exact = BooleanArray(rowCount) { table.rows.getOrNull(it)?.exactHeight == true }
            fun need(c: TableCell, content: StackBlock) = content.height + (c.padding.top + c.padding.bottom) * unit
            for ((c, content) in cells) {
                if (c.rowSpan == 1 && c.row < rowCount && !exact[c.row]) {
                    rowHeights[c.row] = max(rowHeights[c.row], need(c, content))
                }
            }
            // A merged cell taller than its rows makes its last row taller.
            for ((c, content) in cells) {
                if (c.rowSpan > 1) {
                    val last = (c.row + c.rowSpan - 1).coerceAtMost(rowCount - 1)
                    val have = (c.row..last).sumOf { rowHeights[it].toDouble() }.toFloat()
                    val lack = need(c, content) - have
                    if (lack > 0f) rowHeights[last] += lack
                }
            }
            rowTops = FloatArray(rowCount + 1).also { for (i in 0 until rowCount) it[i + 1] = it[i] + rowHeights[i] }
            height = rowTops[rowCount]
            breaks = splitPoints()
        }

        /** Where a cell's content starts (after padding and vertical alignment). */
        private fun contentTop(c: TableCell, content: StackBlock): Float {
            val r = rect(c)
            val innerHeight = r.height() - (c.padding.top + c.padding.bottom) * unit
            val offset = when (c.verticalAlign) {
                VerticalAlign.Top -> 0f
                VerticalAlign.Center -> (innerHeight - content.height) / 2
                VerticalAlign.Bottom -> innerHeight - content.height
            }.coerceAtLeast(0f)
            return r.top + c.padding.top * unit + offset
        }

        /**
         * Where a page may end: between rows, and inside a row (as both editors split a tall row
         * across pages) wherever no cell has a line of text cut in half.
         */
        private fun splitPoints(): FloatArray {
            val candidates = (rowTops.toList() + cells.flatMap { (c, content) ->
                val top = contentTop(c, content)
                content.breaks.map { top + it } + listOf(top, top + content.height)
            }).filter { it > 0f && it < height }.distinct().sorted()
            return candidates.filter { y ->
                val covering = cells.filter { (c, _) -> rect(c).let { y > it.top && y < it.bottom } }
                val noLineCut = covering.all { (c, content) ->
                    val top = contentTop(c, content)
                    y <= top + 0.5f || y >= top + content.height - 0.5f ||
                        content.breaks.any { kotlin.math.abs(top + it - y) < 0.5f }
                }
                // Splitting a row before any of its text would leave just an empty strip of it behind.
                val textAbove = covering.isEmpty() || covering.any { (c, content) -> content.height > 0f && y > contentTop(c, content) + 0.5f }
                noLineCut && textAbove
            }.toFloatArray()
        }

        private fun rect(c: TableCell): RectF {
            val from = c.column.coerceIn(0, columnWidths.size)
            val to = (c.column + c.columnSpan).coerceIn(from, columnWidths.size)
            val top = rowTops[c.row.coerceIn(0, rowTops.size - 1)]
            val bottom = rowTops[(c.row + c.rowSpan).coerceIn(0, rowTops.size - 1)]
            return RectF(columnLefts[from], top, columnLefts[to], bottom)
        }

        override fun draw(canvas: Canvas) {
            for ((c, _) in cells) if (c.fill != 0) canvas.drawRect(rect(c), fillPaint.apply { color = c.fill })
            for ((c, content) in cells) {
                val r = rect(c)
                val innerTop = r.top + c.padding.top * unit
                val innerHeight = r.height() - (c.padding.top + c.padding.bottom) * unit
                val offset = when (c.verticalAlign) {
                    VerticalAlign.Top -> 0f
                    VerticalAlign.Center -> (innerHeight - content.height) / 2
                    VerticalAlign.Bottom -> innerHeight - content.height
                }.coerceAtLeast(0f)
                canvas.save()
                canvas.clipRect(r)
                canvas.translate(r.left + c.padding.left * unit, innerTop + offset)
                content.draw(canvas)
                canvas.restore()
            }
            for ((c, _) in cells) {
                val r = rect(c)
                c.borders.left?.let { line(canvas, it, r.left, r.top, r.left, r.bottom) }
                c.borders.right?.let { line(canvas, it, r.right, r.top, r.right, r.bottom) }
                c.borders.top?.let { line(canvas, it, r.left, r.top, r.right, r.top) }
                c.borders.bottom?.let { line(canvas, it, r.left, r.bottom, r.right, r.bottom) }
            }
        }

        private fun line(canvas: Canvas, border: Border, x0: Float, y0: Float, x1: Float, y1: Float) {
            linePaint.color = border.color
            // Hairlines (0.1 mm) would vanish at normal zoom; keep every border visible.
            linePaint.strokeWidth = max(border.width * unit, 0.9f)
            canvas.drawLine(x0, y0, x1, y1, linePaint)
        }

        override fun linkAt(x: Float, y: Float): String? {
            for ((c, content) in cells) {
                val r = rect(c)
                if (r.contains(x, y)) return content.linkAt(x - r.left - c.padding.left * unit, y - r.top - c.padding.top * unit)
            }
            return null
        }
    }

    private val fillPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }
}

/** A paragraph; [trailingSpace] is the line spacing under its last line (Hangul puts it below the text). */
private class ParagraphBlock(layout: StaticLayout, val trailingSpace: Float) : TextBlock(layout)

/** Size, weight, slant, typeface, width and spacing of one run, in flow units. */
class RunStyleSpan(val style: TextStyle, private val unit: Float) : MetricAffectingSpan() {
    val size get() = style.size * unit

    override fun updateMeasureState(paint: TextPaint) = apply(paint)
    override fun updateDrawState(paint: TextPaint) = apply(paint)

    private fun apply(paint: TextPaint) {
        val family = when (style.font) {
            FontKind.Serif -> Typeface.SERIF
            FontKind.Mono -> Typeface.MONOSPACE
            FontKind.Sans -> Typeface.SANS_SERIF
        }
        val styleBits = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
        paint.typeface = Typeface.create(family, styleBits)
        val small = style.superscript || style.subscript
        paint.textSize = size * if (small) 0.7f else 1f
        if (style.superscript) paint.baselineShift -= (size * 0.35f).roundToInt()
        if (style.subscript) paint.baselineShift += (size * 0.15f).roundToInt()
        paint.textScaleX = style.scaleX
        paint.letterSpacing = style.letterSpacing
    }
}

/**
 * Line pitch as the document asks: a multiple of the line's largest text size, or a fixed height.
 * Lines holding a picture keep their natural height so the picture fits.
 */
private class LineHeightRule(private val spacing: LineSpacing, private val unit: Float, private val hasObjects: Boolean) : LineHeightSpan {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
        if (hasObjects && text is Spanned && text.getSpans(start, end, ReplacementSpan::class.java).isNotEmpty()) return
        val size = (text as? Spanned)?.getSpans(start, end, RunStyleSpan::class.java)?.maxOfOrNull { it.size } ?: return
        val natural = fm.descent - fm.ascent
        val target = when (spacing) {
            is LineSpacing.Multiple -> spacing.factor * size
            is LineSpacing.AtLeast -> max(spacing.points * unit, natural.toFloat())
            is LineSpacing.Exactly -> spacing.points * unit
        }
        val pitch = target.roundToInt().coerceAtLeast(1)
        // The text sits at the top of the line, the extra space below it.
        val baseline = when (spacing) {
            is LineSpacing.Multiple -> (spacing.baseline * size).roundToInt()
            else -> (pitch - size * 0.25f).roundToInt()
        }
        fm.ascent = -baseline.coerceIn(1, pitch)
        fm.descent = pitch + fm.ascent
        fm.top = fm.ascent
        fm.bottom = fm.descent
    }
}

/** An embedded picture, decoded no larger than it is drawn at a 3× zoom. */
private class PictureSpan(bytes: ByteArray?, private val width: Float, private val height: Float) : ReplacementSpan() {
    private val bitmap: Bitmap? = bytes?.let {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(it, 0, it.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width * 3) sample *= 2
            BitmapFactory.decodeByteArray(it, 0, it.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Throwable) {
            null
        }
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply {
            ascent = -height.roundToInt()
            descent = 0
            top = ascent
            bottom = 0
        }
        return width.roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val dst = RectF(x, y - height, x + width, y.toFloat())
        val b = bitmap
        if (b != null) canvas.drawBitmap(b, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        else EmptyBoxSpan.drawBox(canvas, dst)
    }
}

/** A bullet or number drawn in the hanging indent, so the text after it starts at the indent. */
private class LabelSpan(private val label: Run.Text, unit: Float, private val hanging: Float) : ReplacementSpan() {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).also {
        RunStyleSpan(label.style, unit).updateDrawState(it)
        it.color = label.style.color
    }

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
        max(hanging, this.paint.measureText(label.text) + this.paint.textSize * 0.3f).roundToInt()

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        // Bullet dots and squares as shapes: the system font's are much smaller than Office fonts' ones.
        val shape = SHAPES[label.text]
        if (shape == null) {
            canvas.drawText(label.text, x, y.toFloat(), this.paint)
            return
        }
        val size = this.paint.textSize
        val (round, extent) = shape
        val half = size * extent / 2
        val cx = x + half + size * 0.05f
        val cy = y - size * 0.33f
        if (round) canvas.drawCircle(cx, cy, half, this.paint) else canvas.drawRect(cx - half, cy - half, cx + half, cy + half, this.paint)
    }

    private companion object {
        /** Bullet characters drawn as shapes: round or square, and size in ems. */
        val SHAPES = mapOf("•" to (true to 0.36f), "●" to (true to 0.6f), "▪" to (false to 0.34f), "■" to (false to 0.55f))
    }
}

/** Where something we can't draw sits: an empty dashed box of its size (기획서 5.7). */
internal class EmptyBoxSpan(private val width: Float, private val height: Float) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.apply {
            ascent = -height.roundToInt()
            descent = 0
            top = ascent
            bottom = 0
        }
        return width.roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) =
        drawBox(canvas, RectF(x, y - height, x + width, y.toFloat()))

    companion object {
        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFB0B0B0.toInt()
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }

        fun drawBox(canvas: Canvas, r: RectF) {
            canvas.drawRect(r, Paint().apply { color = 0xFFF4F4F4.toInt() })
            canvas.drawRect(r, boxPaint)
        }
    }
}
