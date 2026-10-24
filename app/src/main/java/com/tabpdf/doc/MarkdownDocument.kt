package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.max

/**
 * Markdown files, always shown rendered (기획서 5.7, 11장 8번): no raw view, so handwriting has one
 * layout to stay on. Images are drawn only when embedded in the file (data: URIs): files next to
 * it can't be reached, and web images would need network access, which the app doesn't have.
 */
object MarkdownDocument {
    /** Markdown is parsed whole; bigger files are [DocumentOpenException.Reason.TooLarge] (shown as plain text instead). */
    private const val MAX_BYTES = 4 * 1024 * 1024

    suspend fun open(context: Context, uri: Uri): Document = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES + 1) }
            ?: throw FileNotFoundException(uri.toString())
        if (bytes.size > MAX_BYTES) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
        render(TextDecoding.decode(bytes))
    }

    /** At most [limit] bytes from the start of the stream. */
    private fun InputStream.readUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private val parser: Parser = Parser.builder()
        .extensions(
            listOf(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListItemsExtension.create(),
                AutolinkExtension.create(),
            )
        )
        .build()

    fun render(markdown: String): FlowDocument {
        val root = parser.parse(withoutFrontMatter(markdown))
        return MarkdownRenderer().render(root)
    }

    /** YAML front matter (`---` … `---` at the very top) is metadata, not content. */
    private fun withoutFrontMatter(text: String): String {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return normalized
        val end = normalized.indexOf("\n---\n", 4)
        return if (end in 0..8192) normalized.substring(end + 5) else normalized
    }
}

private object Md {
    const val MARGIN = 50f
    const val BODY = 34f
    const val CODE = 29f
    const val LINE_SPACING = 1.45f
    const val BLOCK_GAP = 26f
    const val TIGHT_GAP = 8f
    const val INDENT = 56f
    const val QUOTE_INDENT = 36f
    const val CELL_PAD = 14f
    const val MAX_PAGE_HEIGHT = 1400f

    const val TEXT = 0xFF1F2328.toInt()
    const val MUTED = 0xFF59636E.toInt()
    const val LINK = 0xFF0969DA.toInt()
    const val RULE = 0xFFD1D9E0.toInt()
    const val CODE_BG = 0xFFF2F4F7.toInt()
    const val HEADER_BG = 0xFFF6F8FA.toInt()

    val HEADING_SCALE = floatArrayOf(1.9f, 1.5f, 1.25f, 1.1f, 1f, 0.9f)
    val BULLETS = listOf("•", "◦", "▪")
}

/** Where blocks go: their left edge and width, and the quote bars beside them. */
private data class Ctx(val x: Float, val width: Float, val bars: List<Float>)

private class MarkdownRenderer {
    private val out = FlowBuilder()
    private var pendingMarker: Marker? = null

    fun render(root: Node): FlowDocument {
        out.space(Md.MARGIN)
        blocks(root, Ctx(Md.MARGIN, FLOW_WIDTH - 2 * Md.MARGIN, emptyList()), gap = Md.BLOCK_GAP)
        out.space(Md.MARGIN)
        return out.build(Md.MAX_PAGE_HEIGHT, pageGaps = false)
    }

    /** Lays out [parent]'s block children with [gap] between them. */
    private fun blocks(parent: Node, ctx: Ctx, gap: Float) {
        var first = true
        var node = parent.firstChild
        while (node != null) {
            if (node !is TaskListItemMarker) {
                // Headings start a new section: more room above them than between paragraphs.
                if (!first) space(if (node is Heading) gap + Md.BLOCK_GAP else gap, ctx)
                block(node, ctx)
                first = false
            }
            node = node.next
        }
    }

    private fun block(node: Node, ctx: Ctx) {
        when (node) {
            is Heading -> heading(node, ctx)
            is Paragraph -> paragraph(node, ctx)
            is BlockQuote -> blocks(
                node,
                ctx.copy(x = ctx.x + Md.QUOTE_INDENT, width = ctx.width - Md.QUOTE_INDENT, bars = ctx.bars + (ctx.x + 4f)),
                Md.BLOCK_GAP,
            )
            is BulletList, is OrderedList -> list(node as ListBlock, ctx)
            is FencedCodeBlock -> code(node.literal, ctx)
            is IndentedCodeBlock -> code(node.literal, ctx)
            is ThematicBreak -> emit(RuleBlock(ctx.width), ctx)
            is HtmlBlock -> html(node.literal, ctx)
            is TableBlock -> table(node, ctx)
            else -> blocks(node, ctx, Md.BLOCK_GAP)
        }
    }

    private fun heading(node: Heading, ctx: Ctx) {
        val level = node.level.coerceIn(1, 6)
        val paint = flowPaint(Md.BODY * Md.HEADING_SCALE[level - 1], Typeface.DEFAULT_BOLD, Md.TEXT)
        val layout = staticLayout(inlines(node), paint, ctx.width, lineSpacing = 1.25f)
        // Like GitHub: a line under the two top levels.
        emit(if (level <= 2) RuledTextBlock(layout, ctx.width) else TextBlock(layout), ctx)
    }

    private fun paragraph(node: Paragraph, ctx: Ctx) {
        embeddedImage(node)?.let { bitmap ->
            emit(ImageBlock(bitmap, ctx.width), ctx)
            return
        }
        emitText(inlines(node), ctx)
    }

    private fun emitText(text: CharSequence, ctx: Ctx, size: Float = Md.BODY, color: Int = Md.TEXT) {
        emit(TextBlock(staticLayout(text, flowPaint(size, color = color), ctx.width, Md.LINE_SPACING)), ctx)
    }

    private fun list(node: ListBlock, ctx: Ctx) {
        val depth = generateSequence(node.parent) { it.parent }.count { it is ListBlock }
        @Suppress("DEPRECATION")
        var number = (node as? OrderedList)?.startNumber ?: 1
        val itemCtx = ctx.copy(x = ctx.x + Md.INDENT, width = ctx.width - Md.INDENT)
        var item = node.firstChild
        var first = true
        while (item != null) {
            if (item is ListItem) {
                if (!first) space(if (node.isTight) Md.TIGHT_GAP else Md.BLOCK_GAP, ctx)
                val task = item.firstChild as? TaskListItemMarker
                pendingMarker = when {
                    task != null -> Marker.Check(task.isChecked)
                    node is OrderedList -> Marker.Label("$number.")
                    else -> Marker.Label(Md.BULLETS[depth % Md.BULLETS.size])
                }
                number++
                if (item.firstChild == null || item.firstChild is TaskListItemMarker && item.firstChild.next == null) {
                    emitText("", itemCtx)
                } else {
                    blocks(item, itemCtx, if (node.isTight) Md.TIGHT_GAP else Md.BLOCK_GAP)
                }
                first = false
            }
            item = item.next
        }
    }

    private fun code(literal: String, ctx: Ctx) {
        val text = literal.removeSuffix("\n")
        val paint = flowPaint(Md.CODE, Typeface.MONOSPACE, Md.TEXT)
        emit(CodeBlock(staticLayout(text, paint, ctx.width - 2 * Md.CELL_PAD, 1.3f), ctx.width), ctx)
    }

    /** Raw HTML: rendered with Android's basic HTML support; images in it are dropped. */
    private fun html(literal: String, ctx: Ctx) {
        val spanned = fromHtml(literal)
        if (spanned.isBlank()) return
        emitText(spanned, ctx)
    }

    private fun table(node: TableBlock, ctx: Ctx) {
        val rows = mutableListOf<TableRow>()
        fun collect(n: Node) {
            var c = n.firstChild
            while (c != null) {
                if (c is TableRow) rows += c else collect(c)
                c = c.next
            }
        }
        collect(node)
        if (rows.isEmpty()) return
        val cells = rows.map { row ->
            generateSequence(row.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
        }
        val columns = cells.maxOf { it.size }
        val texts = cells.map { row -> row.map { inlines(it) } }
        val paints = cells.map { row ->
            val header = row.firstOrNull()?.isHeader == true
            flowPaint(Md.BODY * 0.92f, if (header) Typeface.DEFAULT_BOLD else Typeface.DEFAULT, Md.TEXT)
        }
        val natural = FloatArray(columns)
        texts.forEachIndexed { r, row ->
            row.forEachIndexed { c, text ->
                val widest = text.split('\n').maxOf { Layout.getDesiredWidth(it, paints[r]) }
                natural[c] = max(natural[c], widest + 2 * Md.CELL_PAD + 2f)
            }
        }
        val widths = columnWidths(natural, ctx.width)
        val layouts = texts.mapIndexed { r, row ->
            (0 until columns).map { c ->
                val alignment = when (cells[r].getOrNull(c)?.alignment) {
                    TableCell.Alignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    TableCell.Alignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                staticLayout(row.getOrElse(c) { "" }, paints[r], widths[c] - 2 * Md.CELL_PAD, 1.3f, alignment)
            }
        }
        emit(TableGridBlock(layouts, widths, headerRows = cells.count { it.firstOrNull()?.isHeader == true }), ctx)
    }

    /**
     * Columns at their natural width if the table fits; otherwise every column first gets up to an
     * equal share, and what is left goes to the columns that want more, in proportion.
     */
    private fun columnWidths(natural: FloatArray, available: Float): FloatArray {
        if (natural.sum() <= available) return natural
        val share = available / natural.size
        val widths = FloatArray(natural.size) { minOf(natural[it], share) }
        val left = available - widths.sum()
        val wanting = natural.indices.sumOf { (natural[it] - widths[it]).toDouble() }.toFloat()
        if (wanting > 0f) for (i in widths.indices) widths[i] += left * (natural[i] - widths[i]) / wanting
        return widths
    }

    /** A paragraph that is just one embedded (data: URI) image, decoded. */
    private fun embeddedImage(node: Paragraph): Bitmap? {
        val image = node.firstChild as? Image ?: return null
        if (image.next != null) return null
        val url = image.destination
        if (!url.startsWith("data:image/") || !url.contains(";base64,")) return null
        return try {
            val bytes = Base64.decode(url.substringAfter(";base64,"), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    // ---- Inline content ----

    private fun inlines(parent: Node): SpannableStringBuilder =
        SpannableStringBuilder().also { appendInlines(parent, it) }

    private fun appendInlines(parent: Node, sb: SpannableStringBuilder) {
        var node = parent.firstChild
        while (node != null) {
            appendInline(node, sb)
            node = node.next
        }
    }

    private fun appendInline(node: Node, sb: SpannableStringBuilder) {
        val start = sb.length
        when (node) {
            is Text -> sb.append(node.literal)
            is SoftLineBreak -> sb.append(' ')
            is HardLineBreak -> sb.append('\n')
            is Code -> {
                sb.append(' ').append(node.literal).append(' ')
                sb.span(TypefaceSpan("monospace"), start)
                sb.span(RelativeSizeSpan(0.88f), start)
                sb.span(BackgroundColorSpan(Md.CODE_BG), start)
            }
            is Emphasis -> {
                appendInlines(node, sb)
                sb.span(StyleSpan(Typeface.ITALIC), start)
            }
            is StrongEmphasis -> {
                appendInlines(node, sb)
                sb.span(StyleSpan(Typeface.BOLD), start)
            }
            is Strikethrough -> {
                appendInlines(node, sb)
                sb.span(StrikethroughSpan(), start)
            }
            is Link -> {
                appendInlines(node, sb)
                sb.span(ForegroundColorSpan(Md.LINK), start)
                if (isWebLink(node.destination)) sb.span(LinkSpan(node.destination), start)
            }
            is Image -> {
                // Only an image alone in its paragraph is drawn (embeddedImage); inline ones are named.
                val alt = inlines(node).toString().ifBlank { node.destination.substringAfterLast('/').take(40) }
                sb.append("[이미지: ").append(alt).append(']')
                sb.span(ForegroundColorSpan(Md.MUTED), start)
            }
            is HtmlInline -> if (node.literal.matches(Regex("(?i)<br\\s*/?>"))) sb.append('\n')
            else -> appendInlines(node, sb)
        }
    }

    private fun SpannableStringBuilder.span(what: Any, start: Int) {
        if (length > start) setSpan(what, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun isWebLink(url: String) =
        url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("mailto:", true)

    private fun fromHtml(html: String): SpannableStringBuilder {
        val spanned = SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT))
        for (span in spanned.getSpans(0, spanned.length, URLSpan::class.java)) {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            spanned.removeSpan(span)
            spanned.setSpan(ForegroundColorSpan(Md.LINK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (isWebLink(span.url)) spanned.setSpan(LinkSpan(span.url), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Images become object replacement characters; there is nothing to show for them.
        for (i in spanned.length - 1 downTo 0) if (spanned[i] == '￼') spanned.delete(i, i + 1)
        return SpannableStringBuilder(spanned.trim())
    }

    // ---- Output ----

    private fun emit(block: FlowBlock, ctx: Ctx) {
        out.add(Decorated(block, ctx.x, ctx.bars, pendingMarker))
        pendingMarker = null
    }

    private fun space(height: Float, ctx: Ctx) {
        if (ctx.bars.isEmpty()) out.space(height) else out.add(Decorated(SpaceBlock(height), ctx.x, ctx.bars, null))
    }
}

/** What goes before a list item's first line. */
private sealed interface Marker {
    data class Label(val text: String) : Marker
    data class Check(val checked: Boolean) : Marker
}

/** Puts [inner] at [innerX] and draws what sits beside it: quote bars and a list marker. */
private class Decorated(
    private val inner: FlowBlock,
    private val innerX: Float,
    private val bars: List<Float>,
    private val marker: Marker?,
) : FlowBlock() {
    override val height get() = inner.height
    override val breaks get() = inner.breaks

    override fun draw(canvas: Canvas) {
        for (x in bars) canvas.drawRect(x, 0f, x + 6f, height, barPaint)
        canvas.save()
        canvas.translate(innerX, 0f)
        inner.draw(canvas)
        canvas.restore()
        when (marker) {
            is Marker.Label -> {
                val paint = markerPaint
                canvas.drawText(marker.text, innerX - 14f - paint.measureText(marker.text), firstBaseline(), paint)
            }
            is Marker.Check -> {
                val size = Md.BODY * 0.8f
                val bottom = firstBaseline() + 2f
                val box = RectF(innerX - 14f - size, bottom - size, innerX - 14f, bottom)
                canvas.drawRoundRect(box, 5f, 5f, if (marker.checked) checkFill else checkBox)
                if (marker.checked) {
                    canvas.drawLine(box.left + size * 0.22f, box.centerY(), box.left + size * 0.42f, box.bottom - size * 0.25f, checkMark)
                    canvas.drawLine(box.left + size * 0.42f, box.bottom - size * 0.25f, box.right - size * 0.2f, box.top + size * 0.25f, checkMark)
                }
            }
            null -> Unit
        }
    }

    private fun firstBaseline(): Float = when (inner) {
        is TextBlock -> inner.layout.getLineBaseline(0).toFloat()
        else -> Md.BODY
    }

    override fun linkAt(x: Float, y: Float) = inner.linkAt(x - innerX, y)

    override fun find(query: String) = inner.find(query).map { it.offset(innerX, 0f) }

    override fun collectText(sink: TextSink) = inner.collectText(sink.offset(innerX, 0f))

    private companion object {
        val barPaint = Paint().apply { color = Md.RULE }
        val markerPaint = flowPaint(Md.BODY, color = Md.TEXT)
        val checkBox = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Md.MUTED; style = Paint.Style.STROKE; strokeWidth = 3f }
        val checkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Md.LINK }
        val checkMark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
        }
    }
}

/** A heading with a rule under it. */
private class RuledTextBlock(layout: StaticLayout, private val width: Float) : TextBlock(layout) {
    override val height: Float = layout.height + 16f

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        canvas.drawRect(0f, height - 3f, width, height, rulePaint)
    }

    private companion object {
        val rulePaint = Paint().apply { color = Md.RULE }
    }
}

private class RuleBlock(private val width: Float) : FlowBlock() {
    override val height = 30f

    override fun draw(canvas: Canvas) = canvas.drawRect(0f, 13f, width, 17f, paint)

    private companion object {
        val paint = Paint().apply { color = Md.RULE }
    }
}

/** Code on a grey panel; pages may end between its lines. */
private class CodeBlock(private val layout: StaticLayout, private val width: Float) : FlowBlock() {
    override val height = layout.height + 2 * Md.CELL_PAD
    override val breaks = FloatArray((layout.lineCount - 1).coerceAtLeast(0)) { Md.CELL_PAD + layout.getLineTop(it + 1) }

    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(RectF(0f, 0f, width, height), 10f, 10f, background)
        canvas.save()
        canvas.translate(Md.CELL_PAD, Md.CELL_PAD)
        layout.draw(canvas)
        canvas.restore()
    }

    override fun find(query: String) = TextSearch.find(layout, query).map { it.offset(Md.CELL_PAD, Md.CELL_PAD) }

    override fun collectText(sink: TextSink) = collectLayoutText(layout, sink, Md.CELL_PAD, Md.CELL_PAD)

    private companion object {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Md.CODE_BG }
    }
}

/** An embedded image, as wide as the column at most. */
private class ImageBlock(private val bitmap: Bitmap, available: Float) : FlowBlock() {
    private val width = minOf(available, bitmap.width * 2f)
    override val height = width * bitmap.height / bitmap.width

    override fun draw(canvas: Canvas) =
        canvas.drawBitmap(bitmap, null, RectF(0f, 0f, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
}

/** A table; pages may end between rows. */
private class TableGridBlock(
    private val cells: List<List<StaticLayout>>,
    private val widths: FloatArray,
    private val headerRows: Int,
) : FlowBlock() {
    private val rowHeights = cells.map { row -> row.maxOf { it.height } + 2 * Md.CELL_PAD }
    private val rowTops = rowHeights.runningFold(0f) { acc, h -> acc + h }
    private val totalWidth = widths.sum()

    override val height = rowTops.last()
    override val breaks = rowTops.drop(1).dropLast(1).toFloatArray()

    override fun draw(canvas: Canvas) {
        for (r in cells.indices) {
            val top = rowTops[r]
            if (r < headerRows) canvas.drawRect(0f, top, totalWidth, top + rowHeights[r], headerFill)
            var x = 0f
            cells[r].forEachIndexed { c, layout ->
                canvas.save()
                canvas.translate(x + Md.CELL_PAD, top + Md.CELL_PAD)
                layout.draw(canvas)
                canvas.restore()
                x += widths[c]
            }
        }
        // Grid lines on top of the cell fills.
        for (top in rowTops) canvas.drawRect(0f, top - 1f, totalWidth, top + 1f, linePaint)
        var x = 0f
        for (w in listOf(0f) + widths.toList()) {
            x += w
            canvas.drawRect(x - 1f, 0f, x + 1f, height, linePaint)
        }
    }

    override fun collectText(sink: TextSink) {
        for ((r, row) in cells.withIndex()) {
            var left = 0f
            for ((c, layout) in row.withIndex()) {
                collectLayoutText(layout, sink, left + Md.CELL_PAD, rowTops[r] + Md.CELL_PAD)
                left += widths[c]
            }
        }
    }

    override fun find(query: String): List<Found> = cells.flatMapIndexed { r, row ->
        var left = 0f
        row.flatMapIndexed { c, layout ->
            val x = left
            left += widths[c]
            TextSearch.find(layout, query).map { it.offset(x + Md.CELL_PAD, rowTops[r] + Md.CELL_PAD) }
        }
    }

    override fun linkAt(x: Float, y: Float): String? {
        val r = rowTops.indexOfLast { it <= y }.takeIf { it in cells.indices } ?: return null
        var left = 0f
        for (c in widths.indices) {
            if (x < left + widths[c]) {
                val layout = cells[r].getOrNull(c) ?: return null
                return TextBlock(layout).linkAt(x - left - Md.CELL_PAD, y - rowTops[r] - Md.CELL_PAD)
            }
            left += widths[c]
        }
        return null
    }

    private companion object {
        val headerFill = Paint().apply { color = Md.HEADER_BG }
        val linePaint = Paint().apply { color = Md.RULE }
    }
}
