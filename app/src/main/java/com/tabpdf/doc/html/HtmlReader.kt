package com.tabpdf.doc.html

import android.graphics.BitmapFactory
import android.util.Base64
import com.tabpdf.doc.isWebLink
import com.tabpdf.doc.office.Align
import com.tabpdf.doc.office.Border
import com.tabpdf.doc.office.Borders
import com.tabpdf.doc.office.Box
import com.tabpdf.doc.office.Element as XmlElement
import com.tabpdf.doc.office.Insets
import com.tabpdf.doc.office.LineSpacing
import com.tabpdf.doc.office.OfficeBlock
import com.tabpdf.doc.office.Paragraph
import com.tabpdf.doc.office.Run
import com.tabpdf.doc.office.Table
import com.tabpdf.doc.office.TableCell
import com.tabpdf.doc.office.TableRow
import com.tabpdf.doc.office.TextStyle
import com.tabpdf.doc.office.VerticalAlign
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import kotlin.math.max

/** A page read from HTML: its blocks (lengths in points), the page's background, and room left at the bottom. */
class HtmlPage(val blocks: List<OfficeBlock>, val background: Int, val bottom: Float)

/**
 * Turns parsed HTML into the blocks the office layout draws (기획서 5.7): elements become
 * paragraphs, lists, tables and boxes (an element with a background or border), styled by the
 * page's CSS ([StyleResolver]). Layout CSS (flex, grid, float, position) is not followed: blocks
 * go one under another, as a browser shows a page without its style sheet's layout. Scripts are
 * never run, and nothing is fetched: only images inside the file (data: URIs) are drawn.
 */
class HtmlReader private constructor(
    private val resolver: StyleResolver,
    /** Files next to the page, by the address the page uses (an e-book's own files); null: none can be reached. */
    private val resources: ((String) -> ByteArray?)?,
) {

    companion object {
        /** Points per CSS pixel. */
        const val PT = 0.75f

        /** Width of the page in points: a phone-wide page (CSS pixels as on a phone). */
        const val PAGE_WIDTH = VIEWPORT_PX * PT

        /** Images inside the file larger than this are not read (a few MB each in a big saved page is normal). */
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024

        /**
         * [resources] gives the files the page refers to (style sheets, pictures) where they can be
         * reached; [margin] is room kept at the sides of the page, in pixels.
         */
        fun read(doc: Document, resources: ((String) -> ByteArray?)? = null, margin: Float = 0f): HtmlPage {
            val sheet = Stylesheet()
            // Style sheets in document order, inline or linked.
            for (style in doc.select("style, link")) {
                val media = style.attr("media")
                if (media.isNotBlank() && !Stylesheet.mediaMatches(media)) continue
                if (style.normalName() == "style") {
                    sheet.add(style.data())
                } else if (style.attr("rel").split(' ').any { it.equals("stylesheet", true) }) {
                    val css = style.attr("href").takeIf { it.isNotBlank() }?.let { resources?.invoke(it) } ?: continue
                    sheet.add(String(css, Charsets.UTF_8).removePrefix("\uFEFF"))
                }
            }
            val reader = HtmlReader(StyleResolver(sheet), resources)
            val root = doc.child(0)
            val htmlStyle = reader.resolver.compute(root, Style.root())
            val body = doc.body()
            val bodyStyle = reader.resolver.compute(body, htmlStyle)
            // A background on the page (html or body) is the whole page's, as in a browser.
            val background = htmlStyle.background.takeIf { it != 0 } ?: bodyStyle.background
            val out = Out()
            val ctx = Ctx(margin, margin, VIEWPORT_PX, htmlStyle, null, 0)
            reader.block(root, htmlStyle, ctx, out, pageRoot = true)
            return HtmlPage(out.blocks, background, out.take() * PT)
        }

        private val BULLETS = mapOf("disc" to "•", "circle" to "◦", "square" to "▪")
        private const val HANGUL = "가나다라마바사아자차카타파하"

        private fun roman(n: Int): String {
            if (n !in 1..3999) return n.toString()
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i")
            var rest = n
            return buildString {
                for (i in values.indices) while (rest >= values[i]) {
                    append(symbols[i])
                    rest -= values[i]
                }
            }
        }

        private fun alpha(n: Int): String {
            if (n < 1) return n.toString()
            var rest = n
            return buildString {
                while (rest > 0) {
                    rest--
                    insert(0, 'a' + rest % 26)
                    rest /= 26
                }
            }
        }

        /** A list item's marker for list style [type], or null for none. */
        private fun marker(type: String, n: Int): String? = BULLETS[type] ?: when (type) {
            "none" -> null
            "decimal-leading-zero" -> "%02d.".format(n)
            "lower-alpha", "lower-latin" -> alpha(n) + "."
            "upper-alpha", "upper-latin" -> alpha(n).uppercase() + "."
            "lower-roman" -> roman(n) + "."
            "upper-roman" -> roman(n).uppercase() + "."
            "lower-greek" -> if (n in 1..24) "${'α' + n - 1 + (if (n > 17) 1 else 0)}." else "$n."
            "hangul", "korean-hangul-formal" -> if (n in 1..HANGUL.length) "${HANGUL[n - 1]}." else "$n."
            else -> "$n."
        }

        private fun pixels(value: String): Float? = value.trim().removeSuffix("px").toFloatOrNull()?.takeIf { it > 0f }
    }

    /** Where blocks go: margins collapse between siblings, padding doesn't; lengths in pixels. */
    private class Out {
        val blocks = mutableListOf<OfficeBlock>()
        private var fixed = 0f
        private var collapsing = 0f
        /** Marker of the list item whose first paragraph is next. */
        var label: Run.Text? = null
        /** Outline level of the heading whose first paragraph is next (h1 = 0). */
        var heading: Int? = null
        /** Ids of the elements that start with the next paragraph. */
        val anchors = mutableListOf<String>()

        fun margin(px: Float) {
            collapsing = if (px < 0f || collapsing < 0f) collapsing + px else max(collapsing, px)
        }

        /** Room that doesn't collapse (padding). */
        fun solid(px: Float) {
            fixed += collapsing.coerceAtLeast(0f) + px
            collapsing = 0f
        }

        /** The space before the next block. */
        fun take(): Float = (fixed + collapsing).coerceAtLeast(0f).also {
            fixed = 0f
            collapsing = 0f
        }
    }

    /**
     * Where a block's content goes: [left] and [right] indents in its container, which is [width]
     * wide; [block] is the style of the block whose text this is (alignment, line height).
     */
    private data class Ctx(val left: Float, val right: Float, val width: Float, val block: Style, val list: ListState?, val depth: Int)

    private class ListState(var next: Int, val step: Int)

    /** Inline state passed down to text: the link it is in and the background behind it. */
    private data class InlineState(val link: String?, val background: Int)

    /** The runs of the paragraph being built. Whitespace is collapsed as it comes in. */
    private inner class Inline(private val dropFirstNewline: Boolean) {
        val runs = mutableListOf<Run>()
        private var spaceBefore = true
        private var started = false

        fun text(raw: String, s: Style, state: InlineState) {
            var text = when (s.whiteSpace) {
                WhiteSpace.Normal -> COLLAPSE.replace(raw, " ").let { if (spaceBefore && it.startsWith(' ')) it.substring(1) else it }
                WhiteSpace.PreLine -> PRE_LINE.replace(raw.replace("\r\n", "\n").replace(SPACES, " "), "\n").let {
                    if (spaceBefore && it.startsWith(' ')) it.substring(1) else it
                }
                WhiteSpace.Pre -> raw.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").let {
                    if (!started && dropFirstNewline && it.startsWith('\n')) it.substring(1) else it
                }
            }
            if (text.isEmpty()) return
            text = when (s.transform) {
                "uppercase" -> text.uppercase()
                "lowercase" -> text.lowercase()
                "capitalize" -> text.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
                else -> text
            }
            started = true
            spaceBefore = s.whiteSpace != WhiteSpace.Pre && (text.endsWith(' ') || text.endsWith('\n'))
            runs += Run.Text(text, textStyle(s, state.background), state.link)
        }

        fun lineBreak(s: Style) {
            runs += Run.Text("\n", textStyle(s, 0))
            spaceBefore = true
            started = true
        }

        fun add(run: Run) {
            runs += run
            spaceBefore = false
            started = true
        }

        /** The paragraph so far, into [out]; nothing if it holds only spaces. */
        fun flush(out: Out, ctx: Ctx) {
            // Trailing collapsible space, and the last line break before the end (as browsers show it).
            val last = runs.lastOrNull() as? Run.Text
            if (last != null) {
                val trimmed = if (ctx.block.whiteSpace == WhiteSpace.Pre) last.text.removeSuffix("\n") else last.text.trimEnd(' ')
                runs[runs.lastIndex] = last.copy(text = trimmed)
            }
            val visible = runs.any { it !is Run.Text || it.text.isNotBlank() }
            if (!visible) {
                runs.clear()
                spaceBefore = true
                return
            }
            val s = ctx.block
            val label = out.label
            out.label = null
            val hang = if (label != null) minOf(ctx.left, s.fontSize * 1.6f) else 0f
            val factor = (s.linePx / s.fontSize).coerceIn(0.6f, 5f)
            out.blocks += Paragraph(
                runs = runs.toList(),
                align = s.align,
                indentLeft = ctx.left * PT,
                indentRight = ctx.right * PT,
                firstLine = if (label != null) -hang * PT else s.textIndent.resolve(ctx.width) * PT,
                spaceBefore = out.take() * PT,
                lineSpacing = LineSpacing.Multiple(factor, baseline = (factor - 1.17f) / 2 + 0.93f, textHeight = factor),
                keepWords = s.keepAll,
                label = label,
                labelInHang = true,
                outlineLevel = out.heading,
                anchors = out.anchors.toList(),
            )
            out.heading = null
            out.anchors.clear()
            runs.clear()
            spaceBefore = true
            started = false
        }
    }

    private fun textStyle(s: Style, background: Int) = TextStyle(
        size = s.fontSize * PT,
        bold = s.bold,
        italic = s.italic,
        underline = s.underline,
        strike = s.strike,
        color = s.color,
        background = background,
        font = s.font,
        superscript = s.superscript,
        subscript = s.subscript,
    )

    /** A block element (or the page root) and everything in it. */
    private fun block(e: Element, s: Style, ctx: Ctx, out: Out, pageRoot: Boolean = false) {
        val tag = e.normalName()
        if (s.invisible && !pageRoot) return
        if (tag == "table") {
            table(e, s, ctx, out)
            return
        }
        // Percentages are of the width the block sits in.
        val base = ctx.width - ctx.left - ctx.right
        val padding = FloatArray(4) { s.padding[it].resolve(base) }
        val borders = Array(4) { s.border(it) }
        val borderPx = FloatArray(4) { borders[it]?.width ?: 0f }
        var left = s.margin[3].resolve(base)
        var right = s.margin[1].resolve(base)
        val available = base
        val wanted = listOfNotNull(s.width?.takeIf { !it.auto }, s.maxWidth).map { it.resolve(base) }.minOrNull()
        if (wanted != null) {
            val outer = wanted + if (s.borderBox) 0f else padding[1] + padding[3] + borderPx[1] + borderPx[3]
            val extra = available - left - right - outer
            if (extra > 0f) when {
                s.margin[3].auto && s.margin[1].auto -> { left += extra / 2; right += extra / 2 }
                s.margin[3].auto -> left += extra
                else -> right += extra
            }
        }
        out.margin(s.margin[0].resolve(base))
        val label = if (s.display == "list-item") listMarker(e, s, ctx) else null
        val list = if (tag in LISTS) ListState(start(e), if (e.hasAttr("reversed")) -1 else 1) else ctx.list
        // The page background is drawn under the whole page, not as a box.
        val fill = if (pageRoot || tag == "body") 0 else s.background
        val level = HEADINGS.indexOf(tag).takeIf { it >= 0 }
        val id = e.id().trim()
        if (fill != 0 || borders.any { it != null }) {
            val inner = Out()
            inner.label = label
            inner.heading = level
            // Places named before the box start with it.
            inner.anchors += out.anchors
            out.anchors.clear()
            if (id.isNotEmpty()) inner.anchors += id
            val innerWidth = (available - left - right - padding[1] - padding[3] - borderPx[1] - borderPx[3]).coerceAtLeast(1f)
            container(e, s, Ctx(0f, 0f, innerWidth, s, list, ctx.depth + 1), inner)
            val bottom = inner.take()
            out.blocks += Box(
                blocks = inner.blocks,
                fill = fill,
                borders = Borders(
                    borders[3]?.let { Border(it.width * PT, it.color) },
                    borders[0]?.let { Border(it.width * PT, it.color) },
                    borders[1]?.let { Border(it.width * PT, it.color) },
                    borders[2]?.let { Border(it.width * PT, it.color) },
                ),
                padding = Insets(padding[3] * PT, padding[0] * PT, padding[1] * PT, (padding[2] + bottom) * PT),
                indentLeft = (ctx.left + left) * PT,
                indentRight = (ctx.right + right) * PT,
                spaceBefore = out.take() * PT,
            )
        } else {
            if (padding[0] > 0f) out.solid(padding[0])
            // A list item's marker goes on its first paragraph, however deep; other blocks pass it on.
            if (label != null) out.label = label
            if (level != null) out.heading = level
            if (id.isNotEmpty()) out.anchors += id
            val inner = Ctx(ctx.left + left + padding[3], ctx.right + right + padding[1], ctx.width, s, list, ctx.depth + 1)
            container(e, s, inner, out)
            if (label != null) out.label = null
            // An empty heading makes none.
            if (level != null) out.heading = null
            if (padding[2] > 0f) out.solid(padding[2])
        }
        out.margin(s.margin[2].resolve(base))
    }

    /** [e]'s content: runs of inline content become paragraphs, block children are laid out in between. */
    private fun container(e: Element, s: Style, ctx: Ctx, out: Out) {
        val inline = Inline(dropFirstNewline = e.normalName() in setOf("pre", "listing", "textarea"))
        children(e, s, ctx, out, inline, InlineState(null, 0))
        inline.flush(out, ctx)
    }

    private fun children(parent: Element, parentStyle: Style, ctx: Ctx, out: Out, inline: Inline, state: InlineState) {
        for (node in parent.childNodes()) {
            when (node) {
                is TextNode -> if (!parentStyle.invisible) inline.text(node.wholeText, parentStyle, state)
                is Element -> element(node, parentStyle, ctx, out, inline, state)
            }
        }
    }

    private fun element(e: Element, parentStyle: Style, ctx: Ctx, out: Out, inline: Inline, state: InlineState) {
        if (ctx.depth >= XmlElement.MAX_DEPTH) {
            // Far deeper than real pages nest: kept as plain text, without walking further down.
            inline.text(e.text(), parentStyle, state)
            return
        }
        val s = resolver.compute(e, parentStyle)
        if (s.display == "none") return
        when (e.normalName()) {
            "br" -> return inline.lineBreak(s)
            "img" -> return image(e, s, inline, state)
            "svg", "video", "iframe", "canvas", "object", "embed", "math" -> return placeholder(e, s, inline)
            "input" -> return input(e, s, inline, state)
            "wbr" -> return
        }
        if (s.isBlock || e.normalName() == "table") {
            inline.flush(out, ctx)
            block(e, s, ctx, out)
        } else {
            // A named place inside a paragraph: that paragraph.
            e.id().trim().takeIf { it.isNotEmpty() }?.let { out.anchors += it }
            val href = e.attr("href").trim()
            val link = if (e.normalName() == "a" && isWebLink(href)) href else state.link
            val inner = InlineState(link, if (s.background != 0) s.background else state.background)
            children(e, s, ctx.copy(depth = ctx.depth + 1), out, inline, inner)
        }
    }

    private fun listMarker(e: Element, s: Style, ctx: Ctx): Run.Text? {
        val list = ctx.list ?: ListState(1, 1)
        e.attr("value").trim().toIntOrNull()?.let { list.next = it }
        val n = list.next
        list.next += list.step
        val text = marker(s.listType, n) ?: return null
        return Run.Text(text, textStyle(s, 0))
    }

    private fun start(e: Element): Int {
        e.attr("start").trim().toIntOrNull()?.let { return it }
        // A reversed list counts down from its number of items.
        return if (e.hasAttr("reversed")) e.children().count { it.normalName() == "li" } else 1
    }

    /** An image: drawn if it is inside the file, else its description (pages can't be fetched). */
    private fun image(e: Element, s: Style, inline: Inline, state: InlineState) {
        val src = e.attr("src").trim()
        val bytes = if (src.startsWith("data:image/", true) && src.contains(";base64,")) {
            val data = src.substringAfter(";base64,")
            if (data.length / 4 * 3 > MAX_IMAGE_BYTES) null
            else try {
                Base64.decode(data, Base64.DEFAULT)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else if (src.isNotEmpty() && !src.contains(':')) {
            resources?.invoke(src)?.takeIf { it.size <= MAX_IMAGE_BYTES }
        } else {
            null
        }
        val cssWidth = s.width?.takeIf { !it.auto && it.percent == 0f }?.px
        val cssHeight = s.height?.takeIf { !it.auto && it.percent == 0f }?.px
        var width = cssWidth ?: pixels(e.attr("width"))
        var height = cssHeight ?: pixels(e.attr("height"))
        if (bytes == null) {
            val alt = e.attr("alt").trim()
            when {
                alt.isNotEmpty() -> inline.text(alt, s, state)
                width != null && height != null -> inline.add(Run.Unsupported(width * PT, height * PT))
            }
            return
        }
        if (width == null || height == null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth.toFloat()
            val h = bounds.outHeight.toFloat()
            if (w > 0f && h > 0f) {
                when {
                    width == null && height == null -> { width = w; height = h }
                    width == null -> width = height!! * w / h
                    else -> height = width * h / w
                }
            }
        }
        inline.add(Run.Picture(bytes, (width ?: 100f) * PT, (height ?: 100f) * PT))
    }

    /** Embedded content this viewer can't draw: an empty box of its size, if it has one. */
    private fun placeholder(e: Element, s: Style, inline: Inline) {
        var width = s.width?.takeIf { !it.auto && it.percent == 0f }?.px ?: pixels(e.attr("width"))
        var height = s.height?.takeIf { !it.auto && it.percent == 0f }?.px ?: pixels(e.attr("height"))
        val tag = e.normalName()
        // An SVG holding just a picture (e-book covers): the picture, at the SVG's size.
        if (tag == "svg") {
            // The HTML parser may have renamed <image> to <img>.
            val image = e.select("image, img").firstOrNull()
            val href = image?.let { it.attr("xlink:href").ifBlank { it.attr("href") }.ifBlank { it.attr("src") } }?.trim()
            val bytes = href?.takeIf { it.isNotEmpty() && !it.contains(':') }?.let { resources?.invoke(it) }
            if (bytes != null) {
                val box = e.attr("viewBox").trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (box.size == 4 && (width == null || height == null)) {
                    width = width ?: pixels(image.attr("width")) ?: box[2]
                    height = height ?: (width * box[3] / box[2].coerceAtLeast(1f))
                }
                inline.add(Run.Picture(bytes, (width ?: 300f) * PT, (height ?: 400f) * PT))
                return
            }
        }
        when {
            width != null && height != null -> inline.add(Run.Unsupported(width * PT, height * PT))
            // Videos and frames have a default size; icons drawn as inline SVG without one are left out.
            tag != "svg" && tag != "math" -> inline.add(Run.Unsupported((width ?: 300f) * PT, (height ?: 150f) * PT))
        }
    }

    private fun input(e: Element, s: Style, inline: Inline, state: InlineState) {
        val checked = e.hasAttr("checked")
        when (e.attr("type").lowercase()) {
            "hidden" -> Unit
            "checkbox" -> inline.text(if (checked) "☑ " else "☐ ", s, state)
            "radio" -> inline.text(if (checked) "◉ " else "○ ", s, state)
            "image" -> Unit
            else -> e.attr("value").ifBlank { e.attr("placeholder") }.takeIf { it.isNotBlank() }?.let { inline.text(" [$it] ", s, state) }
        }
    }

    // ---- Tables ----

    private fun table(e: Element, s: Style, ctx: Ctx, out: Out) {
        val base = ctx.width - ctx.left - ctx.right
        out.margin(s.margin[0].resolve(base))
        e.children().firstOrNull { it.normalName() == "caption" }?.let { caption ->
            val cs = resolver.compute(caption, s)
            if (cs.display != "none") block(caption, cs.also { it.display = "block" }, ctx, out)
        }

        // Rows in display order: the header group first, then bodies and loose rows, the footer last.
        class RowSource(val tr: Element, val style: Style, val group: Style?)
        val head = mutableListOf<RowSource>()
        val bodies = mutableListOf<RowSource>()
        val foot = mutableListOf<RowSource>()
        for (child in e.children()) {
            val tag = child.normalName()
            if (tag == "tr") {
                val ts = resolver.compute(child, s)
                if (ts.display != "none") bodies += RowSource(child, ts, null)
            } else if (tag == "thead" || tag == "tbody" || tag == "tfoot") {
                val gs = resolver.compute(child, s)
                if (gs.display == "none") continue
                val target = when (tag) { "thead" -> head; "tfoot" -> foot; else -> bodies }
                for (tr in child.children()) if (tr.normalName() == "tr") {
                    val ts = resolver.compute(tr, gs)
                    if (ts.display != "none") target += RowSource(tr, ts, gs)
                }
            }
        }
        val rows = head + bodies + foot
        val borderAttr = e.attr("border").trim().let { if (e.hasAttr("border")) it.toFloatOrNull() ?: 1f else 0f }
        val attrBorder = if (borderAttr > 0f) Border(PT, 0xFF808080.toInt()) else null
        val cellPadding = e.attr("cellpadding").trim().toFloatOrNull()
        val tableBorders = Array(4) { s.border(it)?.let { b -> Border(b.width * PT, b.color) } ?: attrBorder }

        val taken = HashMap<Int, java.util.BitSet>()
        fun isTaken(r: Int, c: Int) = taken[r]?.get(c) == true
        val cells = mutableListOf<TableCell>()
        val columnWidths = mutableListOf<Float>()
        fun widen(column: Int, width: Float) {
            while (columnWidths.size <= column) columnWidths += 0f
            columnWidths[column] = max(columnWidths[column], width)
        }
        val tableWidth = s.width?.takeIf { !it.auto }?.resolve(base)
            ?.coerceAtMost(base - s.margin[1].resolve(base) - s.margin[3].resolve(base))
        var columns = 0
        rows.forEachIndexed { r, source ->
            var col = 0
            for (cellElement in source.tr.children()) {
                val tag = cellElement.normalName()
                if (tag != "td" && tag != "th") continue
                val cs = resolver.compute(cellElement, source.style)
                if (cs.display == "none") continue
                while (isTaken(r, col)) col++
                val colSpan = cellElement.attr("colspan").trim().toIntOrNull()?.coerceIn(1, 1000) ?: 1
                val rowSpanAttr = cellElement.attr("rowspan").trim().toIntOrNull() ?: 1
                val rowSpan = (if (rowSpanAttr == 0) rows.size - r else rowSpanAttr.coerceAtLeast(1)).coerceAtMost(rows.size - r)
                for (rr in r until r + rowSpan) taken.getOrPut(rr) { java.util.BitSet() }.set(col, col + colSpan)

                val content = Out()
                container(cellElement, cs, Ctx(0f, 0f, tableWidth ?: base, cs, null, ctx.depth + 1), content)
                val pad = FloatArray(4) { side ->
                    // cellpadding stands in for the browser's default padding, not for the page's CSS.
                    val p = cs.padding[side].resolve(base)
                    if (cellPadding != null && p == 1f) cellPadding else p
                }
                val bottom = content.take()
                val cellBorder = { side: Int ->
                    cs.border(side)?.let { Border(it.width * PT, it.color) } ?: attrBorder ?: when {
                        side == 0 && r == 0 -> tableBorders[0]
                        side == 2 && r + rowSpan == rows.size -> tableBorders[2]
                        side == 3 && col == 0 -> tableBorders[3]
                        else -> null
                    }
                }
                cells += TableCell(
                    row = r,
                    column = col,
                    rowSpan = rowSpan,
                    columnSpan = colSpan,
                    blocks = content.blocks,
                    fill = cs.background.takeIf { it != 0 } ?: source.style.background.takeIf { it != 0 }
                        ?: source.group?.background?.takeIf { it != 0 } ?: s.background,
                    borders = Borders(cellBorder(3), cellBorder(0), cellBorder(1), cellBorder(2)),
                    padding = Insets(pad[3] * PT, pad[0] * PT, pad[1] * PT, (pad[2] + bottom) * PT),
                    verticalAlign = when (cs.verticalAlign) {
                        "top", "baseline", "text-top" -> VerticalAlign.Top
                        "bottom", "text-bottom" -> VerticalAlign.Bottom
                        else -> VerticalAlign.Center
                    },
                )
                if (colSpan == 1) cs.width?.takeIf { !it.auto }?.resolve(tableWidth ?: base)?.let { w ->
                    widen(col, (w + if (cs.borderBox) 0f else pad[1] + pad[3]) * PT)
                }
                col += colSpan
            }
            columns = max(columns, col)
        }
        // A right table border goes on the last cell of each row.
        val lastColumn = columns - 1
        val withRight = cells.map { c ->
            if (c.column + c.columnSpan - 1 == lastColumn && c.borders.right == null && tableBorders[1] != null) {
                TableCell(c.row, c.column, c.rowSpan, c.columnSpan, c.blocks, c.fill, c.borders.copy(right = tableBorders[1]), c.padding, c.verticalAlign)
            } else c
        }
        if (columns > 0 && withRight.isNotEmpty()) {
            // Column widths the page gives with <col width>.
            var colIndex = 0
            val cols = e.children().flatMap { c ->
                when (c.normalName()) {
                    "col" -> listOf(c)
                    "colgroup" -> c.children().filter { it.normalName() == "col" }
                    else -> emptyList()
                }
            }
            for (col in cols) {
                val span = col.attr("span").trim().toIntOrNull()?.coerceIn(1, 1000) ?: 1
                val w = pixels(col.attr("width")) ?: col.attr("style").let { st ->
                    Stylesheet.parseDeclarations(st).lastOrNull { it.property == "width" }?.let { CssValues.length(it.value, s.fontSize, 16f, tableWidth ?: base) }
                }
                if (w != null) for (i in colIndex until colIndex + span) widen(i, w * PT)
                colIndex += span
            }
            val left = s.margin[3]
            val right = s.margin[1]
            out.blocks += Table(
                // Widths the page gives; the other columns are sized to their content.
                columns = List(columns) { columnWidths.getOrNull(it) ?: 0f },
                rows = rows.map { source -> TableRow(minHeight = (source.style.height?.takeIf { !it.auto }?.px ?: 0f) * PT) },
                cells = withRight,
                indent = (ctx.left + left.resolve(base)) * PT,
                indentRight = (ctx.right + right.resolve(base)) * PT,
                align = if (left.auto && right.auto) Align.Center else Align.Left,
                spaceBefore = out.take() * PT,
                width = tableWidth?.let { it * PT },
            )
        }
        out.margin(s.margin[2].resolve(base))
    }
}

private val COLLAPSE = Regex("[ \t\n\r\u000C]+")
private val SPACES = Regex("[ \t\u000C]+")
private val PRE_LINE = Regex(" *\n *")
private val LISTS = setOf("ul", "ol", "menu", "dir")

/** Heading elements by outline level. */
private val HEADINGS = listOf("h1", "h2", "h3", "h4", "h5", "h6")
