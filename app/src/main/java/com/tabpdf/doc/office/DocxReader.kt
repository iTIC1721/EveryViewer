package com.tabpdf.doc.office

/**
 * Word documents (.docx, Office Open XML) into an [OfficeDocument] (기획서 5.7). Styles are
 * resolved the way Word does — document defaults, then the paragraph style chain, then the
 * character style, then direct formatting — for the properties this viewer draws.
 */
object DocxReader {
    fun read(parts: ZipParts): OfficeDocument {
        val document = parts.xml("word/document.xml") ?: throw java.io.IOException("No word/document.xml")
        return Docx(parts).read(document)
    }
}

private const val TWIP = 1f / 20f // points per twip
private const val EMU = 1f / 12700f // points per EMU

/**
 * Word's "single" line pitch relative to the font size. It comes from the font's own metrics, so
 * it differs a lot between fonts: Malgun Gothic (Korean Word's default) is measured at 1.73.
 */
private fun singleLine(font: String?): Float {
    val n = font?.lowercase() ?: return 1.2f
    return when {
        "malgun" in n || "맑은" in n -> 1.73f
        listOf("굴림", "gulim", "돋움", "dotum", "바탕", "batang", "궁서", "gungsuh").any { it in n } -> 1.15f
        // Other Korean fonts are usually missing on the reader's PC and replaced with Malgun Gothic.
        n.any { it.code > 0x2E80 } -> 1.73f
        else -> 1.2f
    }
}

/** Paragraph properties; null means "not set here", so layers can be merged. */
private data class PProps(
    val align: Align? = null,
    val left: Float? = null,
    val right: Float? = null,
    val firstLine: Float? = null,
    val before: Float? = null,
    val after: Float? = null,
    val line: Float? = null,
    val lineRule: String? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
    val pageBreakBefore: Boolean? = null,
    val keepWords: Boolean? = null,
) {
    infix fun over(base: PProps) = PProps(
        align ?: base.align, left ?: base.left, right ?: base.right, firstLine ?: base.firstLine,
        before ?: base.before, after ?: base.after, line ?: base.line, lineRule ?: base.lineRule,
        numId ?: base.numId, ilvl ?: base.ilvl, pageBreakBefore ?: base.pageBreakBefore, keepWords ?: base.keepWords,
    )
}

/** Run properties, mergeable like [PProps]. */
private data class RProps(
    val size: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val color: Int? = null,
    val background: Int? = null,
    val font: FontKind? = null,
    val scaleX: Float? = null,
    val spacing: Float? = null,
    val vertAlign: String? = null,
    val hidden: Boolean? = null,
    val fontName: String? = null,
) {
    infix fun over(base: RProps) = RProps(
        size ?: base.size, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline,
        strike ?: base.strike, color ?: base.color, background ?: base.background, font ?: base.font,
        scaleX ?: base.scaleX, spacing ?: base.spacing, vertAlign ?: base.vertAlign, hidden ?: base.hidden,
        fontName ?: base.fontName,
    )

    fun toStyle(): TextStyle {
        val size = size ?: 10f
        return TextStyle(
            size = size,
            bold = bold == true,
            italic = italic == true,
            underline = underline == true,
            strike = strike == true,
            color = color ?: 0xFF000000.toInt(),
            background = background ?: 0,
            font = font ?: FontKind.Sans,
            scaleX = scaleX ?: 1f,
            letterSpacing = (spacing ?: 0f) / size,
            superscript = vertAlign == "superscript",
            subscript = vertAlign == "subscript",
        )
    }
}

private class Docx(private val parts: ZipParts) {
    /** Theme fonts ("minorEastAsia" and the like) to typeface names, from the theme part. */
    private val themeFonts: Map<String, String> = run {
        val theme = parts.xml("word/theme/theme1.xml") ?: return@run emptyMap()
        buildMap {
            for (kind in listOf("major", "minor")) {
                val f = theme.find("a:${kind}Font") ?: continue
                val latin = f.child("a:latin")?.attr("typeface").orEmpty()
                val ea = f.child("a:ea")?.attr("typeface").orEmpty()
                    .ifEmpty { f.all("a:font").firstOrNull { it.attr("script") == "Hang" }?.attr("typeface").orEmpty() }
                put("${kind}HAnsi", latin)
                put("${kind}Ascii", latin)
                put("${kind}EastAsia", ea.ifEmpty { latin })
                put("${kind}Bidi", latin)
            }
        }
    }
    private val styles: Map<String, Element> = parts.xml("word/styles.xml")
        ?.all("w:style")?.associateBy { it.attr("w:styleId").orEmpty() }.orEmpty()
    private val stylesRoot = parts.xml("word/styles.xml")
    private val defaultP: PProps = stylesRoot?.find("w:pPrDefault")?.child("w:pPr")?.let(::pProps) ?: PProps()
    private val defaultR: RProps = stylesRoot?.find("w:rPrDefault")?.child("w:rPr")?.let(::rProps) ?: RProps()
    private val defaultParagraphStyle = styles.values.firstOrNull { it.attr("w:type") == "paragraph" && it.attr("w:default") == "1" }?.attr("w:styleId")
    private val numbering = parts.xml("word/numbering.xml")
    private val counters = mutableMapOf<String, IntArray>()

    /** Relationship targets of the part being read (images and links resolve against it). */
    private var rels: Map<String, Pair<String, Boolean>> = emptyMap()

    private fun relsOf(partPath: String): Map<String, Pair<String, Boolean>> {
        val dir = partPath.substringBeforeLast('/')
        val name = partPath.substringAfterLast('/')
        val xml = parts.xml("$dir/_rels/$name.rels") ?: return emptyMap()
        return xml.all("Relationship").associate { r ->
            val external = r.attr("TargetMode") == "External"
            val target = r.attr("Target").orEmpty()
            r.attr("Id").orEmpty() to Pair(if (external) target else resolve(dir, target), external)
        }
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val segments = dir.split('/').toMutableList()
        for (s in target.split('/')) when (s) {
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            ".", "" -> Unit
            else -> segments += s
        }
        return segments.joinToString("/")
    }

    fun read(document: Element): OfficeDocument {
        val body = document.child("w:body") ?: throw java.io.IOException("No body")
        val section = body.child("w:sectPr") ?: body.findAll("w:sectPr").lastOrNull()
        rels = relsOf("word/document.xml")
        val docRels = rels
        val blocks = blocks(body)

        fun headerFooter(kind: String): List<OfficeBlock> {
            val refs = section?.all("w:${kind}Reference").orEmpty()
            val ref = refs.firstOrNull { it.attr("w:type") == "default" } ?: refs.firstOrNull() ?: return emptyList()
            val path = docRels[ref.attr("r:id")]?.first ?: return emptyList()
            val xml = parts.xml(path) ?: return emptyList()
            rels = relsOf(path)
            return blocks(xml).also { rels = docRels }
        }
        val header = headerFooter("header")
        val footer = headerFooter("footer")

        val size = section?.child("w:pgSz")
        val margin = section?.child("w:pgMar")
        fun twips(e: Element?, name: String, default: Float) = e?.attr(name)?.toFloatOrNull()?.times(TWIP) ?: default
        var width = twips(size, "w:w", 595.3f)
        var height = twips(size, "w:h", 841.9f)
        if (size?.attr("w:orient") == "landscape" && width < height) width = height.also { height = width }
        val top = twips(margin, "w:top", 72f)
        val bottom = twips(margin, "w:bottom", 72f)
        val page = PageSetup(
            width = width,
            height = height,
            left = twips(margin, "w:left", 72f),
            right = twips(margin, "w:right", 72f),
            bodyTop = kotlin.math.abs(top),
            bodyBottom = height - kotlin.math.abs(bottom),
            headerTop = twips(margin, "w:header", 36f),
            footerBottom = height - twips(margin, "w:footer", 36f),
        )
        return OfficeDocument(page, blocks, header, footer)
    }

    // ---- Blocks ----

    private fun blocks(container: Element): List<OfficeBlock> = buildList {
        for (e in container.children) when (e.name) {
            "w:p" -> addAll(paragraph(e))
            "w:tbl" -> add(table(e))
            "w:sdt" -> e.child("w:sdtContent")?.let { addAll(blocks(it)) }
            "w:customXml", "w:ins", "w:moveTo" -> addAll(blocks(e))
        }
    }

    private fun style(id: String?): Element? = id?.let { styles[it] }

    /** A style's properties with everything it is based on underneath. */
    private fun styleP(id: String?, depth: Int = 0): PProps {
        val s = style(id) ?: return PProps()
        val own = s.child("w:pPr")?.let(::pProps) ?: PProps()
        return if (depth > 20) own else own over styleP(s.child("w:basedOn")?.attr("w:val"), depth + 1)
    }

    private fun styleR(id: String?, depth: Int = 0): RProps {
        val s = style(id) ?: return RProps()
        val own = s.child("w:rPr")?.let(::rProps) ?: RProps()
        return if (depth > 20) own else own over styleR(s.child("w:basedOn")?.attr("w:val"), depth + 1)
    }

    /** One paragraph, or several when it contains page breaks. */
    private fun paragraph(p: Element): List<Paragraph> {
        val pPr = p.child("w:pPr")
        val styleId = pPr?.child("w:pStyle")?.attr("w:val") ?: defaultParagraphStyle
        val styleProps = styleP(styleId)
        val direct = pPr?.let(::pProps) ?: PProps()
        val numId = direct.numId ?: styleProps.numId
        val ilvl = direct.ilvl ?: styleProps.ilvl ?: 0
        val level = numId?.let { level(it, ilvl) }
        val levelP = level?.child("w:pPr")?.let(::pProps) ?: PProps()
        val props = direct over levelP over styleProps over defaultP
        val paragraphR = styleR(styleId) over defaultR
        val markR = (pPr?.child("w:rPr")?.let(::rProps) ?: RProps()) over paragraphR

        val segments = mutableListOf(mutableListOf<Run>())
        fun add(run: Run) = segments.last().add(run)
        collectRuns(p, paragraphR, null, ::add) { segments += mutableListOf<Run>() }

        val label = level?.let { label(numId, ilvl, it, (level.child("w:rPr")?.let(::rProps) ?: RProps()) over markR) }
        val line = props.line
        // The line pitch follows the font of the paragraph's first run.
        val firstRun = p.findAll("w:r").firstOrNull { it.child("w:t") != null }
        val font = (firstRun?.child("w:rPr")?.let { rPr -> rProps(rPr) over styleR(rPr.child("w:rStyle")?.attr("w:val")) } ?: RProps())
            .over(markR).fontName
        val spacing = when (props.lineRule) {
            "exact" -> LineSpacing.Exactly((line ?: 240f) * TWIP)
            "atLeast" -> LineSpacing.AtLeast((line ?: 240f) * TWIP)
            else -> singleLine(font).let { single ->
                // Measured on Word's own output: the baseline sits about 0.72 of a single line down,
                // and unlike Hangul, the space under the last line of a cell counts.
                val pitch = (line ?: 240f) / 240f * single
                LineSpacing.Multiple(pitch, baseline = 0.72f * single, textHeight = pitch)
            }
        }
        return segments.mapIndexed { i, runs ->
            Paragraph(
                runs = runs,
                align = props.align ?: Align.Left,
                indentLeft = props.left ?: 0f,
                indentRight = props.right ?: 0f,
                firstLine = props.firstLine ?: 0f,
                spaceBefore = if (i == 0) props.before ?: 0f else 0f,
                spaceAfter = if (i == segments.lastIndex) props.after ?: 0f else 0f,
                lineSpacing = spacing,
                pageBreakBefore = if (i == 0) props.pageBreakBefore == true else true,
                keepWords = props.keepWords ?: true,
                label = if (i == 0) label else null,
                markStyle = markR.toStyle(),
            )
        }
    }

    /** Runs of [e] in order; [pageBreak] is called where the text continues on a new page. */
    private fun collectRuns(e: Element, paragraphR: RProps, link: String?, add: (Run) -> Unit, pageBreak: () -> Unit) {
        for (c in e.children) when (c.name) {
            "w:r" -> run(c, paragraphR, link, add, pageBreak)
            "w:hyperlink" -> {
                val target = c.attr("r:id")?.let { rels[it] }?.takeIf { it.second }?.first
                collectRuns(c, paragraphR, target ?: link, add, pageBreak)
            }
            "w:fldSimple", "w:smartTag", "w:ins", "w:moveTo", "w:customXml", "w:bdo", "w:dir" ->
                collectRuns(c, paragraphR, link, add, pageBreak)
            "w:sdt" -> c.child("w:sdtContent")?.let { collectRuns(it, paragraphR, link, add, pageBreak) }
            "mc:AlternateContent" -> (c.child("mc:Choice") ?: c.child("mc:Fallback"))?.let { collectRuns(it, paragraphR, link, add, pageBreak) }
        }
    }

    private fun run(r: Element, paragraphR: RProps, link: String?, add: (Run) -> Unit, pageBreak: () -> Unit) {
        val rPr = r.child("w:rPr")
        val props = (rPr?.let(::rProps) ?: RProps()) over styleR(rPr?.child("w:rStyle")?.attr("w:val")) over paragraphR
        if (props.hidden == true) return
        val style = props.toStyle().let { if (link != null) it.copy(color = 0xFF0563C1.toInt(), underline = true) else it }
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) add(Run.Text(text.toString(), style, link))
            text.clear()
        }
        for (c in r.children) when (c.name) {
            "w:t" -> text.append(c.text)
            "w:tab", "w:ptab" -> text.append('\t')
            "w:br" -> if (c.attr("w:type") == "page") {
                flush()
                pageBreak()
            } else {
                text.append('\n')
            }
            "w:cr" -> text.append('\n')
            "w:noBreakHyphen" -> text.append('‑')
            "w:sym" -> text.append(symbol(c.attr("w:char")))
            "w:drawing" -> {
                flush()
                add(drawing(c))
            }
            "w:pict", "w:object" -> {
                flush()
                add(Run.Unsupported(120f, 60f))
            }
            "mc:AlternateContent" -> {
                flush()
                (c.child("mc:Choice") ?: c.child("mc:Fallback"))?.let { run(Element("w:r", emptyMap(), listOfNotNull(rPr) + it.children, emptyList()), paragraphR, link, add, pageBreak) }
            }
        }
        flush()
    }

    private fun drawing(d: Element): Run {
        val frame = d.child("wp:inline") ?: d.child("wp:anchor")
        val extent = frame?.child("wp:extent")
        val width = (extent?.attr("cx")?.toFloatOrNull() ?: 0f) * EMU
        val height = (extent?.attr("cy")?.toFloatOrNull() ?: 0f) * EMU
        val blip = d.find("a:blip")?.attr("r:embed")
        if (blip == null || width <= 0f || height <= 0f) return Run.Unsupported(width.coerceAtLeast(40f), height.coerceAtLeast(20f))
        val bytes = rels[blip]?.takeIf { !it.second }?.first?.let { parts[it] }
        return Run.Picture(bytes, width, height)
    }

    private fun symbol(code: String?): String {
        val c = code?.toIntOrNull(16) ?: return ""
        return if (c in 0xF000..0xF0FF) "•" else c.toChar().toString()
    }

    // ---- Lists ----

    private fun level(numId: String, ilvl: Int): Element? {
        val num = numbering?.all("w:num")?.firstOrNull { it.attr("w:numId") == numId } ?: return null
        val abstractId = num.child("w:abstractNumId")?.attr("w:val")
        val override = num.all("w:lvlOverride").firstOrNull { it.attr("w:ilvl") == ilvl.toString() }?.child("w:lvl")
        if (override != null) return override
        val abstract = numbering.all("w:abstractNum").firstOrNull { it.attr("w:abstractNumId") == abstractId } ?: return null
        return abstract.all("w:lvl").firstOrNull { it.attr("w:ilvl") == ilvl.toString() }
    }

    private fun label(numId: String, ilvl: Int, level: Element, props: RProps): Run? {
        val format = level.child("w:numFmt")?.attr("w:val") ?: "decimal"
        val template = level.child("w:lvlText")?.attr("w:val") ?: return null
        val counts = counters.getOrPut(numId) { IntArray(9) }
        if (ilvl !in 0..8) return null
        val start = level.child("w:start")?.attr("w:val")?.toIntOrNull() ?: 1
        counts[ilvl] = if (counts[ilvl] == 0) start else counts[ilvl] + 1
        for (deeper in ilvl + 1..8) counts[deeper] = 0
        val text = if (format == "bullet") {
            bullet(template)
        } else {
            Regex("%(\\d)").replace(template) { m ->
                val l = m.groupValues[1].toInt() - 1
                formatNumber(counts.getOrElse(l) { 1 }.coerceAtLeast(1), if (l == ilvl) format else levelFormat(numId, l))
            }
        }
        if (format == "none" || text.isBlank()) return null
        return Run.Text(text, props.toStyle())
    }

    private fun levelFormat(numId: String, ilvl: Int) =
        level(numId, ilvl)?.child("w:numFmt")?.attr("w:val") ?: "decimal"

    private fun bullet(template: String): String = when {
        template.isEmpty() -> "•"
        template[0].code in 0xF000..0xF0FF -> when (template[0].code) {
            0xF0A7, 0xF06E -> "▪"
            0xF0D8 -> "➢"
            0xF0FC -> "✓"
            else -> "•"
        }
        template == "o" -> "◦"
        else -> template
    }

    // ---- Tables ----

    private fun table(t: Element): Table {
        val tblPr = t.child("w:tblPr")
        val styleBorders = style(tblPr?.child("w:tblStyle")?.attr("w:val"))?.child("w:tblPr")?.child("w:tblBorders")
        val borders = tblPr?.child("w:tblBorders")
        fun tableBorder(vararg names: String): Border? {
            for (n in names) {
                borders?.child(n)?.let { return border(it) }
                styleBorders?.child(n)?.let { return border(it) }
            }
            return null
        }
        val outer = Borders(tableBorder("w:left", "w:start"), tableBorder("w:top"), tableBorder("w:right", "w:end"), tableBorder("w:bottom"))
        val insideH = tableBorder("w:insideH")
        val insideV = tableBorder("w:insideV")
        val cellMargin = tblPr?.child("w:tblCellMar")
        fun margin(e: Element?, vararg names: String, default: Float): Float {
            for (n in names) e?.child(n)?.attr("w:w")?.toFloatOrNull()?.let { return it * TWIP }
            return default
        }
        val defaultPadding = Insets(
            margin(cellMargin, "w:left", "w:start", default = 5.4f), margin(cellMargin, "w:top", default = 0f),
            margin(cellMargin, "w:right", "w:end", default = 5.4f), margin(cellMargin, "w:bottom", default = 0f),
        )
        val columns = t.child("w:tblGrid")?.all("w:gridCol")?.map { (it.attr("w:w")?.toFloatOrNull() ?: 0f) * TWIP }.orEmpty()

        val rows = mutableListOf<TableRow>()
        class Building(val row: Int, val column: Int, val span: Int, val tc: Element) {
            var rowSpan = 1
        }
        val built = mutableListOf<Building>()
        val trs = t.all("w:tr")
        trs.forEachIndexed { r, tr ->
            val trPr = tr.child("w:trPr")
            val h = trPr?.child("w:trHeight")
            rows += TableRow(
                minHeight = (h?.attr("w:val")?.toFloatOrNull() ?: 0f) * TWIP,
                exactHeight = h?.attr("w:hRule") == "exact",
            )
            var column = trPr?.child("w:gridBefore")?.attr("w:val")?.toIntOrNull() ?: 0
            for (tc in tr.all("w:tc") + tr.all("w:sdt").flatMap { it.child("w:sdtContent")?.all("w:tc").orEmpty() }) {
                val tcPr = tc.child("w:tcPr")
                val span = tcPr?.child("w:gridSpan")?.attr("w:val")?.toIntOrNull() ?: 1
                val vMerge = tcPr?.child("w:vMerge")
                if (vMerge != null && vMerge.attr("w:val") != "restart") {
                    built.lastOrNull { it.column == column && it.row + it.rowSpan == r }?.let { it.rowSpan++ }
                } else {
                    built += Building(r, column, span, tc)
                }
                column += span
            }
        }
        val columnCount = maxOf(columns.size, built.maxOfOrNull { it.column + it.span } ?: 0)
        val gridColumns = if (columns.size >= columnCount) columns else columns + List(columnCount - columns.size) { 40f }
        val cells = built.map { b ->
            val tcPr = b.tc.child("w:tcPr")
            val tcBorders = tcPr?.child("w:tcBorders")
            fun side(names: List<String>, fallback: Border?): Border? {
                for (n in names) tcBorders?.child(n)?.let { return border(it) }
                return fallback
            }
            val lastRow = b.row + b.rowSpan >= trs.size
            val tcMar = tcPr?.child("w:tcMar")
            TableCell(
                row = b.row,
                column = b.column,
                rowSpan = b.rowSpan,
                columnSpan = b.span,
                blocks = blocks(b.tc),
                fill = parseColor(tcPr?.child("w:shd")?.attr("w:fill")) ?: 0,
                borders = Borders(
                    left = side(listOf("w:left", "w:start"), if (b.column == 0) outer.left else insideV),
                    top = side(listOf("w:top"), if (b.row == 0) outer.top else insideH),
                    right = side(listOf("w:right", "w:end"), if (b.column + b.span >= columnCount) outer.right else insideV),
                    bottom = side(listOf("w:bottom"), if (lastRow) outer.bottom else insideH),
                ),
                padding = if (tcMar == null) defaultPadding else Insets(
                    margin(tcMar, "w:left", "w:start", default = defaultPadding.left),
                    margin(tcMar, "w:top", default = defaultPadding.top),
                    margin(tcMar, "w:right", "w:end", default = defaultPadding.right),
                    margin(tcMar, "w:bottom", default = defaultPadding.bottom),
                ),
                verticalAlign = when (tcPr?.child("w:vAlign")?.attr("w:val")) {
                    "center" -> VerticalAlign.Center
                    "bottom" -> VerticalAlign.Bottom
                    else -> VerticalAlign.Top
                },
            )
        }
        return Table(
            columns = gridColumns,
            rows = rows,
            cells = cells,
            indent = (tblPr?.child("w:tblInd")?.attr("w:w")?.toFloatOrNull() ?: 0f) * TWIP,
            align = when (tblPr?.child("w:jc")?.attr("w:val")) {
                "center" -> Align.Center
                "right", "end" -> Align.Right
                else -> Align.Left
            },
        )
    }

    private fun border(e: Element): Border? {
        val kind = e.attr("w:val") ?: return null
        if (kind == "nil" || kind == "none") return null
        val width = (e.attr("w:sz")?.toFloatOrNull() ?: 4f) / 8f
        return Border(if (kind == "double") width * 3 else width, parseColor(e.attr("w:color")) ?: 0xFF000000.toInt())
    }

    // ---- Properties ----

    private fun toggle(e: Element?): Boolean? = e?.let { it.attr("w:val")?.let { v -> v != "0" && v != "false" && v != "off" } ?: true }

    private fun pProps(pPr: Element): PProps {
        val ind = pPr.child("w:ind")
        val spacing = pPr.child("w:spacing")
        val num = pPr.child("w:numPr")
        fun twips(e: Element?, vararg names: String): Float? {
            for (n in names) e?.attr(n)?.toFloatOrNull()?.let { return it * TWIP }
            return null
        }
        val hanging = twips(ind, "w:hanging")
        return PProps(
            align = when (pPr.child("w:jc")?.attr("w:val")) {
                "center" -> Align.Center
                "right", "end" -> Align.Right
                "both", "distribute", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> Align.Justify
                "left", "start" -> Align.Left
                else -> null
            },
            left = twips(ind, "w:left", "w:start"),
            right = twips(ind, "w:right", "w:end"),
            firstLine = hanging?.let { -it } ?: twips(ind, "w:firstLine"),
            before = if (spacing?.attr("w:beforeAutospacing") == "1") 14f else twips(spacing, "w:before"),
            after = if (spacing?.attr("w:afterAutospacing") == "1") 14f else twips(spacing, "w:after"),
            line = spacing?.attr("w:line")?.toFloatOrNull(),
            lineRule = spacing?.attr("w:lineRule") ?: spacing?.attr("w:line")?.let { "auto" },
            numId = num?.child("w:numId")?.attr("w:val")?.takeIf { it != "0" },
            ilvl = num?.child("w:ilvl")?.attr("w:val")?.toIntOrNull(),
            pageBreakBefore = toggle(pPr.child("w:pageBreakBefore")),
            keepWords = pPr.child("w:wordWrap")?.let { toggle(it) },
        )
    }

    private fun rProps(rPr: Element): RProps {
        val fonts = rPr.child("w:rFonts")
        fun theme(attr: String) = fonts?.attr(attr)?.let { themeFonts[it] }?.takeIf { it.isNotEmpty() }
        val fontName = fonts?.attr("w:eastAsia") ?: theme("w:eastAsiaTheme")
            ?: fonts?.attr("w:ascii") ?: theme("w:asciiTheme") ?: fonts?.attr("w:hAnsi")
        val highlight = rPr.child("w:highlight")?.attr("w:val")?.let(::highlightColor)
        val shade = parseColor(rPr.child("w:shd")?.attr("w:fill"))?.takeIf { it != 0xFFFFFFFF.toInt() }
        return RProps(
            size = (rPr.child("w:sz") ?: rPr.child("w:szCs"))?.attr("w:val")?.toFloatOrNull()?.div(2f),
            bold = toggle(rPr.child("w:b")),
            italic = toggle(rPr.child("w:i")),
            underline = rPr.child("w:u")?.attr("w:val")?.let { it != "none" },
            strike = toggle(rPr.child("w:strike")) ?: toggle(rPr.child("w:dstrike")),
            color = rPr.child("w:color")?.attr("w:val")?.let { parseColor(it) ?: if (it == "auto") 0xFF000000.toInt() else null },
            background = highlight ?: shade,
            font = fontName?.let(::fontKind),
            scaleX = rPr.child("w:w")?.attr("w:val")?.toFloatOrNull()?.div(100f),
            spacing = rPr.child("w:spacing")?.attr("w:val")?.toFloatOrNull()?.times(TWIP),
            vertAlign = rPr.child("w:vertAlign")?.attr("w:val"),
            hidden = toggle(rPr.child("w:vanish")),
            fontName = fontName,
        )
    }

    private fun highlightColor(name: String): Int? = when (name) {
        "yellow" -> 0xFFFFFF00
        "green" -> 0xFF00FF00
        "cyan" -> 0xFF00FFFF
        "magenta" -> 0xFFFF00FF
        "blue" -> 0xFF0000FF
        "red" -> 0xFFFF0000
        "darkBlue" -> 0xFF000080
        "darkCyan" -> 0xFF008080
        "darkGreen" -> 0xFF008000
        "darkMagenta" -> 0xFF800080
        "darkRed" -> 0xFF800000
        "darkYellow" -> 0xFF808000
        "darkGray" -> 0xFF808080
        "lightGray" -> 0xFFC0C0C0
        "black" -> 0xFF000000
        else -> null
    }?.toInt()
}

/** Font names to the kind of system typeface that stands in for them. */
fun fontKind(name: String): FontKind {
    val n = name.lowercase()
    return when {
        listOf("courier", "consolas", "mono", "d2coding", "lucida console", "고정폭").any { it in n } -> FontKind.Mono
        listOf("바탕", "batang", "명조", "myeongjo", "궁서", "gungsuh", "times", "georgia", "serif", "cambria", "garamond", "신명").any { it in n } &&
            "sans" !in n -> FontKind.Serif
        else -> FontKind.Sans
    }
}

/** Numbers in the list formats both editors use. */
fun formatNumber(n: Int, format: String): String = when (format.lowercase()) {
    "lowerletter", "latin_lower", "lower_latin" -> letters(n, 'a')
    "upperletter", "latin_capital", "latin_upper", "upper_latin" -> letters(n, 'A')
    "lowerroman", "roman_small", "lower_roman" -> roman(n).lowercase()
    "upperroman", "roman_capital", "upper_roman" -> roman(n)
    "ganada", "hangul_syllable" -> "가나다라마바사아자차카타파하".let { it[(n - 1) % it.length].toString() }
    "chosung", "hangul_jamo" -> "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ".let { it[(n - 1) % it.length].toString() }
    "decimalenclosedcircle", "circled_digit" -> if (n in 1..20) ('①' + (n - 1)).toString() else n.toString()
    else -> n.toString()
}

private fun letters(n: Int, first: Char): String {
    var k = n
    val sb = StringBuilder()
    while (k > 0) {
        k--
        sb.insert(0, first + k % 26)
        k /= 26
    }
    return sb.toString()
}

private fun roman(n: Int): String {
    if (n <= 0 || n >= 4000) return n.toString()
    val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
    val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
    var k = n
    val sb = StringBuilder()
    for (i in values.indices) while (k >= values[i]) {
        sb.append(symbols[i])
        k -= values[i]
    }
    return sb.toString()
}
