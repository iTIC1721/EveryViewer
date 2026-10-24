package com.tabpdf.doc.office

/**
 * Hangul documents (.hwpx, OWPML / KS X 6101) into an [OfficeDocument] (기획서 5.7). Text and
 * paragraph shapes come from the header's property tables (charPr, paraPr, borderFill), referred
 * to by id from the sections. Lengths are in HWPUNIT: 1/7200 inch, so 100 per point.
 */
object HwpxReader {
    fun read(parts: ZipParts): OfficeDocument = Hwpx(parts).read()
}

private const val HWPUNIT = 1f / 100f // points per HWPUNIT

private class CharShape(val style: TextStyle)

private class ParaShape(
    val align: Align,
    val intent: Float,
    val left: Float,
    val right: Float,
    val prev: Float,
    val next: Float,
    val lineSpacing: LineSpacing,
    val keepWords: Boolean,
    val pageBreakBefore: Boolean,
)

private class Hwpx(private val parts: ZipParts) {
    private val header = parts.xml("Contents/header.xml") ?: throw java.io.IOException("No Contents/header.xml")

    private val hangulFonts: Map<String, Element> = header.findAll("hh:fontface")
        .firstOrNull { it.attr("lang") == "HANGUL" }?.all("hh:font")?.associateBy { it.attr("id").orEmpty() }.orEmpty()

    private val charShapes: Map<String, CharShape> = header.findAll("hh:charPr").associate { it.attr("id").orEmpty() to charShape(it) }
    private val paraShapes: Map<String, ParaShape> = header.findAll("hh:paraPr").associate { it.attr("id").orEmpty() to paraShape(it) }
    private val borderFills: Map<String, Element> = header.findAll("hh:borderFill").associateBy { it.attr("id").orEmpty() }

    /** Binary item ids (pictures) to their paths in the package. */
    private val binItems: Map<String, String> = parts.xml("Contents/content.hpf")?.findAll("opf:item")
        ?.associate { it.attr("id").orEmpty() to it.attr("href").orEmpty() }.orEmpty()

    private var headerBlocks: List<OfficeBlock> = emptyList()
    private var footerBlocks: List<OfficeBlock> = emptyList()
    private var pageSetup: PageSetup? = null

    fun read(): OfficeDocument {
        val sections = parts.names
            .mapNotNull { name -> Regex("Contents/section(\\d+)\\.xml", RegexOption.IGNORE_CASE).matchEntire(name)?.let { it.groupValues[1].toInt() to name } }
            .sortedBy { it.first }
            .map { it.second }
        if (sections.isEmpty()) throw java.io.IOException("No sections")
        val body = mutableListOf<OfficeBlock>()
        for ((i, path) in sections.withIndex()) {
            val sec = parts.xml(path) ?: continue
            val blocks = blocks(sec)
            // A new section starts on a new page.
            if (i > 0) (blocks.firstOrNull() as? Paragraph)?.let { first ->
                body += Paragraph(emptyList(), pageBreakBefore = true, markStyle = first.markStyle)
            }
            body += blocks
        }
        return OfficeDocument(pageSetup ?: a4(), body, headerBlocks, footerBlocks)
    }

    private fun a4() = PageSetup(595f, 842f, 85f, 85f, 85f, 842f - 85f, 42f, 842f - 42f)

    // ---- Blocks ----

    private fun blocks(container: Element): List<OfficeBlock> = buildList {
        for (p in container.all("hp:p")) addAll(paragraph(p))
    }

    /**
     * A paragraph's text, with any tables in it put after the text before them, as Hangul
     * places a table anchored in a paragraph on its own lines.
     */
    private fun paragraph(p: Element): List<OfficeBlock> {
        val shape = paraShapes[p.attr("paraPrIDRef")]
        val result = mutableListOf<OfficeBlock>()
        var runs = mutableListOf<Run>()
        var markStyle: TextStyle? = null
        var pageBreak = p.attr("pageBreak") == "1" || shape?.pageBreakBefore == true

        fun flushText(force: Boolean) {
            val hasContent = runs.any { it !is Run.Text || it.text.isNotEmpty() }
            if (hasContent || force) {
                result += Paragraph(
                    runs = runs,
                    align = shape?.align ?: Align.Left,
                    // Hangul's hanging indent (negative "intent") keeps the first line at the margin.
                    indentLeft = (shape?.left ?: 0f) + ((shape?.intent ?: 0f).coerceAtMost(0f) * -1f),
                    indentRight = shape?.right ?: 0f,
                    firstLine = shape?.intent ?: 0f,
                    spaceBefore = shape?.prev ?: 0f,
                    spaceAfter = shape?.next ?: 0f,
                    lineSpacing = shape?.lineSpacing ?: LineSpacing.Multiple(1.6f),
                    pageBreakBefore = pageBreak,
                    keepWords = shape?.keepWords ?: true,
                    markStyle = markStyle,
                )
                pageBreak = false
            }
            runs = mutableListOf()
        }

        var hadObject = false
        for (run in p.all("hp:run")) {
            val style = charShapes[run.attr("charPrIDRef")]?.style ?: TextStyle()
            if (markStyle == null) markStyle = style
            for (c in run.children) when (c.name) {
                "hp:t" -> runs += textRun(c, style)
                "hp:tbl" -> {
                    flushText(force = false)
                    result += table(c, pageBreak).also { pageBreak = false }
                    hadObject = true
                }
                "hp:pic" -> runs += picture(c)
                "hp:rect", "hp:ellipse", "hp:arc", "hp:polygon", "hp:curve", "hp:line", "hp:container",
                "hp:equation", "hp:ole", "hp:chart", "hp:textart", "hp:video" -> runs += unsupported(c)
                "hp:secPr" -> pageSetup = pageSetup ?: page(c)
                "hp:ctrl" -> control(c)
            }
        }
        // An empty paragraph is an empty line, unless it only held a table.
        flushText(force = result.isEmpty() && !hadObject)
        return result
    }

    private fun textRun(t: Element, style: TextStyle): List<Run> {
        val sb = StringBuilder()
        for (node in t.nodes) when (node) {
            is String -> sb.append(node)
            is Element -> when (node.name) {
                "hp:tab" -> sb.append('\t')
                "hp:lineBreak" -> sb.append('\n')
                "hp:nbSpace" -> sb.append(' ')
                "hp:fwSpace" -> sb.append('　')
                "hp:hyphen" -> sb.append('-')
                else -> sb.append(node.text)
            }
        }
        return listOf(Run.Text(sb.toString(), style))
    }

    private fun size(e: Element): Pair<Float, Float> {
        val sz = e.child("hp:sz")
        return ((sz?.attr("width")?.toFloatOrNull() ?: 4000f) * HWPUNIT) to ((sz?.attr("height")?.toFloatOrNull() ?: 2000f) * HWPUNIT)
    }

    private fun picture(pic: Element): Run {
        val (w, h) = size(pic)
        val ref = pic.find("hc:img")?.attr("binaryItemIDRef")
        val path = ref?.let { binItems[it] }?.let { if (it.startsWith("Contents/") || it.startsWith("BinData/")) it else "Contents/$it" }
        val bytes = path?.let { parts[it] ?: parts["BinData/" + it.substringAfterLast('/')] }
        return Run.Picture(bytes, w, h)
    }

    private fun unsupported(e: Element): Run {
        val (w, h) = size(e)
        return Run.Unsupported(w, h)
    }

    private fun control(ctrl: Element) {
        for (c in ctrl.children) when (c.name) {
            "hp:header" -> if (headerBlocks.isEmpty() && c.attr("applyPageType") != "EVEN") {
                headerBlocks = c.child("hp:subList")?.let(::blocks).orEmpty()
            }
            "hp:footer" -> if (footerBlocks.isEmpty() && c.attr("applyPageType") != "EVEN") {
                footerBlocks = c.child("hp:subList")?.let(::blocks).orEmpty()
            }
        }
    }

    private fun page(secPr: Element): PageSetup? {
        val pagePr = secPr.child("hp:pagePr") ?: return null
        var width = (pagePr.attr("width")?.toFloatOrNull() ?: 59528f) * HWPUNIT
        var height = (pagePr.attr("height")?.toFloatOrNull() ?: 84188f) * HWPUNIT
        if (pagePr.attr("landscape") == "NARROWLY" && width < height) width = height.also { height = width }
        val m = pagePr.child("hp:margin")
        fun v(name: String, default: Float) = m?.attr(name)?.toFloatOrNull()?.times(HWPUNIT) ?: default
        val top = v("top", 20f)
        val bottom = v("bottom", 15f)
        val headerHeight = v("header", 15f)
        val footerHeight = v("footer", 15f)
        // Hangul's top margin is above the header area; the body starts below the header.
        return PageSetup(
            width = width,
            height = height,
            left = v("left", 85f) + v("gutter", 0f),
            right = v("right", 85f),
            bodyTop = top + headerHeight,
            bodyBottom = height - bottom - footerHeight,
            headerTop = top,
            footerBottom = height - bottom,
        )
    }

    // ---- Tables ----

    private fun table(tbl: Element, pageBreak: Boolean): OfficeBlock {
        val inMargin = tbl.child("hp:inMargin")
        fun insets(e: Element?, default: Insets) = if (e == null) default else Insets(
            (e.attr("left")?.toFloatOrNull() ?: 0f) * HWPUNIT, (e.attr("top")?.toFloatOrNull() ?: 0f) * HWPUNIT,
            (e.attr("right")?.toFloatOrNull() ?: 0f) * HWPUNIT, (e.attr("bottom")?.toFloatOrNull() ?: 0f) * HWPUNIT,
        )
        val tableMargin = insets(inMargin, Insets(5.1f, 1.4f, 5.1f, 1.4f))

        class Raw(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int, val width: Float, val height: Float, val tc: Element)
        val raw = tbl.all("hp:tr").flatMap { it.all("hp:tc") }.map { tc ->
            val addr = tc.child("hp:cellAddr")
            val span = tc.child("hp:cellSpan")
            val sz = tc.child("hp:cellSz")
            Raw(
                row = addr?.attr("rowAddr")?.toIntOrNull() ?: 0,
                col = addr?.attr("colAddr")?.toIntOrNull() ?: 0,
                rowSpan = span?.attr("rowSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                colSpan = span?.attr("colSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
                width = (sz?.attr("width")?.toFloatOrNull() ?: 0f) * HWPUNIT,
                height = (sz?.attr("height")?.toFloatOrNull() ?: 0f) * HWPUNIT,
                tc = tc,
            )
        }
        val columnCount = maxOf(tbl.attr("colCnt")?.toIntOrNull() ?: 0, raw.maxOfOrNull { it.col + it.colSpan } ?: 0)
        val rowCount = maxOf(tbl.attr("rowCnt")?.toIntOrNull() ?: 0, raw.maxOfOrNull { it.row + it.rowSpan } ?: 0)

        // Column edges from the cell widths: each cell fixes the distance between two edges.
        val edges = arrayOfNulls<Float>(columnCount + 1).also { it[0] = 0f }
        repeat(columnCount) {
            for (c in raw) {
                val from = edges[c.col]
                val to = edges[(c.col + c.colSpan).coerceAtMost(columnCount)]
                if (from != null && to == null) edges[(c.col + c.colSpan).coerceAtMost(columnCount)] = from + c.width
                if (from == null && to != null) edges[c.col] = to - c.width
            }
        }
        val known = edges.indices.filter { edges[it] != null }
        for (i in edges.indices) if (edges[i] == null) {
            // Unconstrained edge: spread evenly between its known neighbours.
            val before = known.lastOrNull { it < i } ?: 0
            val after = known.firstOrNull { it > i }
            edges[i] = if (after == null) (edges[before] ?: 0f) + 30f * (i - before)
            else (edges[before] ?: 0f) + ((edges[after] ?: 0f) - (edges[before] ?: 0f)) * (i - before) / (after - before)
        }
        val columns = (0 until columnCount).map { ((edges[it + 1] ?: 0f) - (edges[it] ?: 0f)).coerceAtLeast(1f) }

        val rowHeights = FloatArray(rowCount)
        for (c in raw) if (c.rowSpan == 1 && c.row < rowCount) rowHeights[c.row] = maxOf(rowHeights[c.row], c.height)

        val tableFill = tbl.attr("borderFillIDRef")
        val cells = raw.map { c ->
            val bf = borderFills[c.tc.attr("borderFillIDRef") ?: tableFill]
            val subList = c.tc.child("hp:subList")
            TableCell(
                row = c.row,
                column = c.col,
                rowSpan = c.rowSpan,
                columnSpan = c.colSpan,
                blocks = subList?.let(::blocks).orEmpty(),
                fill = bf?.let(::fill) ?: 0,
                borders = bf?.let(::borders) ?: Borders.None,
                padding = fitPadding(
                    if (c.tc.attr("hasMargin") == "1") insets(c.tc.child("hp:cellMargin"), tableMargin) else tableMargin,
                    columns.subList(c.col.coerceAtMost(columns.size), (c.col + c.colSpan).coerceAtMost(columns.size)).sum(),
                    subList,
                ),
                verticalAlign = when (subList?.attr("vertAlign")) {
                    "CENTER" -> VerticalAlign.Center
                    "BOTTOM" -> VerticalAlign.Bottom
                    else -> VerticalAlign.Top
                },
            )
        }
        val pos = tbl.child("hp:pos")
        return Table(
            columns = columns,
            rows = rowHeights.map { TableRow(minHeight = it) },
            cells = cells,
            align = when (pos?.attr("horzAlign")) {
                "CENTER" -> Align.Center
                "RIGHT" -> Align.Right
                else -> Align.Left
            },
            pageBreakBefore = pageBreak,
        )
    }

    /**
     * Hangul shrinks a cell's side margins when the cell is too narrow for them. It records the
     * text width it actually used on each line (lineseg horzsize), so take the margins from that.
     */
    private fun fitPadding(padding: Insets, cellWidth: Float, subList: Element?): Insets {
        val p = subList?.child("hp:p") ?: return padding
        val used = p.find("hp:lineseg")?.attr("horzsize")?.toFloatOrNull()?.times(HWPUNIT) ?: return padding
        val shape = paraShapes[p.attr("paraPrIDRef")]
        val inner = used + (shape?.left ?: 0f) + (shape?.right ?: 0f)
        val sides = padding.left + padding.right
        if (sides <= 0f || inner <= 0f || cellWidth - sides >= inner - 0.5f) return padding
        val total = (cellWidth - inner).coerceAtLeast(0f)
        return padding.copy(left = total * padding.left / sides, right = total * padding.right / sides)
    }

    private fun borders(bf: Element): Borders {
        fun side(name: String): Border? {
            val e = bf.child(name) ?: return null
            val type = e.attr("type") ?: "NONE"
            if (type == "NONE") return null
            val mm = e.attr("width")?.substringBefore(' ')?.toFloatOrNull() ?: 0.12f
            val width = mm * 72f / 25.4f
            return Border(if (type.startsWith("DOUBLE")) width * 2.5f else width, parseColor(e.attr("color"), bgr = true) ?: 0xFF000000.toInt())
        }
        return Borders(side("hh:leftBorder"), side("hh:topBorder"), side("hh:rightBorder"), side("hh:bottomBorder"))
    }

    private fun fill(bf: Element): Int? {
        val brush = bf.find("hc:winBrush") ?: return null
        val color = parseColor(brush.attr("faceColor"), bgr = true) ?: return null
        return if (color == 0xFFFFFFFF.toInt()) null else color
    }

    // ---- Shapes ----

    private fun charShape(e: Element): CharShape {
        val fontId = e.child("hh:fontRef")?.attr("hangul")
        val font = hangulFonts[fontId]
        val kind = when {
            font?.child("hh:typeInfo")?.attr("familyType") == "FCAT_MYUNGJO" -> FontKind.Serif
            else -> font?.attr("face")?.let(::fontKind) ?: FontKind.Sans
        }
        val ratio = e.child("hh:ratio")?.attr("hangul")?.toFloatOrNull() ?: 100f
        val spacing = e.child("hh:spacing")?.attr("hangul")?.toFloatOrNull() ?: 0f
        val underline = e.child("hh:underline")?.attr("type")?.let { it != "NONE" } ?: false
        val strike = e.child("hh:strikeout")?.attr("shape")?.let { it != "NONE" && it != "3D" } ?: false
        return CharShape(
            TextStyle(
                size = (e.attr("height")?.toFloatOrNull() ?: 1000f) / 100f,
                bold = e.child("hh:bold") != null,
                italic = e.child("hh:italic") != null,
                underline = underline,
                strike = strike,
                color = parseColor(e.attr("textColor"), bgr = true) ?: 0xFF000000.toInt(),
                background = parseColor(e.attr("shadeColor"), bgr = true)?.takeIf { it != 0xFFFFFFFF.toInt() } ?: 0,
                font = kind,
                scaleX = ratio / 100f,
                letterSpacing = spacing / 100f,
                superscript = e.child("hh:supscript") != null,
                subscript = e.child("hh:subscript") != null,
            )
        )
    }

    /** A paragraph property, directly or (for newer files) in a compatibility switch's default branch. */
    private fun property(paraPr: Element, name: String): Element? =
        paraPr.child(name)
            ?: paraPr.all("hp:switch").firstNotNullOfOrNull { it.child("hp:default")?.child(name) }
            ?: paraPr.all("hp:switch").firstNotNullOfOrNull { it.child("hp:case")?.child(name) }

    private fun paraShape(e: Element): ParaShape {
        val margin = property(e, "hh:margin")
        fun m(name: String) = (margin?.child(name)?.attr("value")?.toFloatOrNull() ?: 0f) * HWPUNIT
        val ls = property(e, "hh:lineSpacing")
        val value = ls?.attr("value")?.toFloatOrNull() ?: 160f
        val spacing = when (ls?.attr("type")) {
            "FIXED" -> LineSpacing.Exactly(value * HWPUNIT)
            "AT_LEAST" -> LineSpacing.AtLeast(value * HWPUNIT)
            "BETWEEN_LINES" -> LineSpacing.Multiple(1f + value * HWPUNIT / 10f)
            else -> LineSpacing.Multiple(value / 100f)
        }
        val breakSetting = e.child("hh:breakSetting")
        return ParaShape(
            align = when (e.child("hh:align")?.attr("horizontal")) {
                "CENTER" -> Align.Center
                "RIGHT" -> Align.Right
                "JUSTIFY", "DISTRIBUTE", "DISTRIBUTE_SPACE" -> Align.Justify
                else -> Align.Left
            },
            intent = m("hc:intent"),
            left = m("hc:left"),
            right = m("hc:right"),
            prev = m("hc:prev"),
            next = m("hc:next"),
            lineSpacing = spacing,
            keepWords = breakSetting?.attr("breakNonLatinWord") != "BREAK_WORD",
            pageBreakBefore = breakSetting?.attr("pageBreakBefore") == "1",
        )
    }
}
