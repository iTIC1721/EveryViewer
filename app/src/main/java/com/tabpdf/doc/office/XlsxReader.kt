package com.tabpdf.doc.office

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException

/*
 * Excel workbooks (기획서 5.7: xlsx) as this viewer draws them: each sheet's used range with its
 * column widths and row heights, cell values (formulas show the result Excel saved), cell styles,
 * merged cells, links, pictures and where charts sit. Lengths are in points.
 */

class Workbook(val sheets: List<SheetModel>, val styles: List<CellStyle>, val date1904: Boolean, val truncated: Boolean)

class SheetModel(
    val name: String,
    val tabColor: Int?,
    val showGrid: Boolean,
    /** Width of each column up to the last used one (0 for hidden ones). */
    val columnWidths: FloatArray,
    /** Height of each row up to the last used one (0 for hidden ones). */
    val rowHeights: FloatArray,
    /** Cells by row; each row's cells sorted by column. */
    val rows: Array<RowCells?>,
    val merges: List<CellRange>,
    val links: Map<Long, String>,
    val drawings: List<Drawing>,
)

/** The cells of one row: parallel arrays, sorted by column. */
class RowCells(val columns: IntArray, val styles: IntArray, val values: Array<Any?>) {
    fun indexOf(column: Int): Int = java.util.Arrays.binarySearch(columns, column)
}

/** A cell value of rich text: runs with their own font. */
class RichText(val runs: List<Pair<String, CellFont?>>) {
    val text: String get() = runs.joinToString("") { it.first }
}

/** An error value (#DIV/0! …) as Excel saved it. */
class CellError(val text: String)

data class CellRange(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int)

/** Where a drawing sits: from a cell plus an offset to another cell plus an offset (points). */
class Anchor(val fromColumn: Int, val fromColumnOffset: Float, val fromRow: Int, val fromRowOffset: Float,
             val toColumn: Int, val toColumnOffset: Float, val toRow: Int, val toRowOffset: Float,
             /** Fixed size (one-cell anchors), used instead of the "to" cell. */
             val width: Float? = null, val height: Float? = null)

/** A picture ([bytes]), or with null bytes something that can't be drawn (a chart): an empty box. */
class Drawing(val anchor: Anchor, val bytes: ByteArray?)

data class CellFont(
    val size: Float = 11f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val kind: FontKind = FontKind.Sans,
    /** Width correction for fonts narrower than the system's (Calibri). */
    val scaleX: Float = 1f,
)

enum class HAlign { General, Left, Center, Right, Fill, Justify, CenterContinuous, Distributed }

data class CellBorder(val style: String, val color: Int)

class CellStyle(
    val format: String,
    val font: CellFont,
    val fill: Int,
    val left: CellBorder?, val right: CellBorder?, val top: CellBorder?, val bottom: CellBorder?,
    val hAlign: HAlign,
    val vAlign: VerticalAlign,
    val wrap: Boolean,
    val indent: Int,
    val shrink: Boolean,
) {
    val isDate: Boolean by lazy { NumberFormat.isDate(format) }
    val hasBorder: Boolean get() = left != null || right != null || top != null || bottom != null
}

object XlsxReader {
    /** A row or column number past Excel's own limits is a broken or crafted file. */
    private const val MAX_ROWS = 1_048_576
    private const val MAX_COLUMNS = 16_384

    /** Columns past this many are not shown (a style applied to whole rows reaches column XFD). */
    private const val SHOWN_COLUMNS = 1_000

    /** Cells kept in memory before the rest are left out ([Workbook.truncated]): about 110 bytes each with their values, kept to under half the heap. */
    private val maxCells = (Runtime.getRuntime().maxMemory() / 256).coerceIn(100_000L, 4_000_000L)

    /**
     * Cells a sheet's links are spread over, all links together. Each link is cut to the used range,
     * but a file can repeat links over a million cells each without end (opening would never finish).
     */
    private const val MAX_LINK_CELLS = 1_000_000

    fun read(parts: ZipParts): Workbook {
        val workbook = parts.xml("xl/workbook.xml") ?: throw IOException("No workbook")
        val rels = relationships(parts, "xl/workbook.xml")
        val theme = parts.xml("xl/theme/theme1.xml")?.let { themeColors(it) } ?: DEFAULT_THEME
        val stylesXml = parts.xml("xl/styles.xml")
        val palette = indexedPalette(stylesXml)
        val colors = Colors(theme, palette)
        val styles = stylesXml?.let { readStyles(it, colors) } ?: listOf(DEFAULT_STYLE)
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it, colors) } ?: emptyList()
        val date1904 = workbook.child("workbookPr")?.attr("date1904").let { it == "1" || it == "true" }
        var cellsLeft = maxCells
        var truncated = false
        val sheets = workbook.child("sheets")?.all("sheet").orEmpty().mapNotNull { sheet ->
            if (sheet.attr("state") == "veryHidden") return@mapNotNull null
            val target = rels[sheet.attr("r:id")] ?: return@mapNotNull null
            val path = resolve("xl/workbook.xml", target)
            val bytes = parts[path] ?: return@mapNotNull null
            val reader = SheetReader(styles, shared, colors, cellsLeft)
            val model = reader.read(bytes, sheet.attr("name") ?: "", parts, path)
            cellsLeft -= reader.cellCount
            if (reader.truncated) truncated = true
            model
        }
        if (sheets.isEmpty()) throw IOException("No sheets")
        return Workbook(sheets, styles, date1904, truncated)
    }

    // ---- Parts and relationships ----

    /** Relationship id → target, for the part at [path]. */
    private fun relationships(parts: ZipParts, path: String): Map<String, String> {
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val rels = parts.xml(if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels") ?: return emptyMap()
        return rels.all("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            id to (if (r.attr("TargetMode") == "External") "external:$target" else target)
        }.toMap()
    }

    /** [target] relative to the part at [from], as a path in the zip. */
    private fun resolve(from: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = from.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        for (piece in target.split('/')) {
            when (piece) {
                "..", -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                ".", "" -> Unit
                else -> parts += piece
            }
        }
        return parts.joinToString("/")
    }

    // ---- Colours ----

    private class Colors(val theme: List<Int>, val indexed: List<Int>) {
        /** A colour element (`<color rgb|theme|indexed tint>`); null for automatic. */
        fun of(e: Element?): Int? {
            e ?: return null
            if (e.attr("auto") == "1" || e.attr("auto") == "true") return null
            val base = e.attr("rgb")?.let { argb(it) }
                ?: e.attr("theme")?.toIntOrNull()?.let { theme.getOrNull(it) }
                ?: e.attr("indexed")?.toIntOrNull()?.let { if (it == 64 || it == 65) null else indexed.getOrNull(it) }
                ?: return null
            val tint = e.attr("tint")?.toFloatOrNull() ?: 0f
            return if (tint == 0f) base else tinted(base, tint)
        }

        private fun argb(hex: String): Int? {
            val v = hex.toLongOrNull(16) ?: return null
            // Excel writes alpha 00 for opaque colours more often than not; colours here are always opaque.
            return (v.toInt() and 0xFFFFFF) or 0xFF000000.toInt()
        }

        /** Excel's tint: lighter (positive) or darker (negative), on the colour's lightness. */
        private fun tinted(color: Int, tint: Float): Int {
            val hsl = FloatArray(3)
            androidx.core.graphics.ColorUtils.colorToHSL(color, hsl)
            hsl[2] = if (tint < 0) hsl[2] * (1 + tint) else hsl[2] * (1 - tint) + tint
            return androidx.core.graphics.ColorUtils.HSLToColor(hsl)
        }
    }

    /** Theme colours in Excel's index order: light 1, dark 1, light 2, dark 2, accents 1–6, links. */
    private fun themeColors(theme: Element): List<Int> {
        val scheme = theme.find("a:clrScheme") ?: return DEFAULT_THEME
        fun color(name: String): Int? = scheme.child(name)?.children?.firstOrNull()?.let { c ->
            (c.attr("val")?.takeIf { c.name == "a:srgbClr" } ?: c.attr("lastClr"))?.toIntOrNull(16)?.or(0xFF000000.toInt())
        }
        val order = listOf("a:lt1", "a:dk1", "a:lt2", "a:dk2", "a:accent1", "a:accent2", "a:accent3", "a:accent4", "a:accent5", "a:accent6", "a:hlink", "a:folHlink")
        return order.mapIndexed { i, name -> color(name) ?: DEFAULT_THEME[i] }
    }

    private val DEFAULT_THEME = listOf(
        0xFFFFFFFF, 0xFF000000, 0xFFE7E6E6, 0xFF44546A, 0xFF4472C4, 0xFFED7D31,
        0xFFA5A5A5, 0xFFFFC000, 0xFF5B9BD5, 0xFF70AD47, 0xFF0563C1, 0xFF954F72,
    ).map { it.toInt() }

    private fun indexedPalette(styles: Element?): List<Int> {
        val custom = styles?.child("colors")?.child("indexedColors")?.all("rgbColor")?.mapNotNull { c ->
            c.attr("rgb")?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
        }
        return if (!custom.isNullOrEmpty()) custom else DEFAULT_PALETTE
    }

    /** Excel's default indexed colours (0–63). */
    private val DEFAULT_PALETTE: List<Int> = listOf(
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x000000, 0xFFFFFF, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0xFF00FF, 0x00FFFF,
        0x800000, 0x008000, 0x000080, 0x808000, 0x800080, 0x008080, 0xC0C0C0, 0x808080,
        0x9999FF, 0x993366, 0xFFFFCC, 0xCCFFFF, 0x660066, 0xFF8080, 0x0066CC, 0xCCCCFF,
        0x000080, 0xFF00FF, 0xFFFF00, 0x00FFFF, 0x800080, 0x800000, 0x008080, 0x0000FF,
        0x00CCFF, 0xCCFFFF, 0xCCFFCC, 0xFFFF99, 0x99CCFF, 0xFF99CC, 0xCC99FF, 0xFFCC99,
        0x3366FF, 0x33CCCC, 0x99CC00, 0xFFCC00, 0xFF9900, 0xFF6600, 0x666699, 0x969696,
        0x003366, 0x339966, 0x003300, 0x333300, 0x993300, 0x993366, 0x333399, 0x333333,
    ).map { it or 0xFF000000.toInt() }

    // ---- Styles ----

    private val DEFAULT_STYLE = CellStyle(
        "General", CellFont(), 0, null, null, null, null, HAlign.General, VerticalAlign.Bottom, false, 0, false,
    )

    private fun font(e: Element?, colors: Colors): CellFont {
        e ?: return CellFont()
        fun flag(name: String) = e.child(name)?.let { it.attr("val") != "0" && it.attr("val") != "false" } ?: false
        val name = (e.child("name") ?: e.child("rFont"))?.attr("val") ?: ""
        val lower = name.lowercase()
        val kind = when {
            listOf("courier", "consolas", "mono", "d2coding", "굴림체", "돋움체", "바탕체", "궁서체").any { it in lower } -> FontKind.Mono
            listOf("times", "georgia", "cambria", "batang", "바탕", "명조", "궁서", "garamond").any { it in lower } -> FontKind.Serif
            else -> FontKind.Sans
        }
        return CellFont(
            size = e.child("sz")?.attr("val")?.toFloatOrNull()?.coerceIn(1f, 409f) ?: 11f,
            bold = flag("b"),
            italic = flag("i"),
            underline = e.child("u")?.let { it.attr("val") != "none" } ?: false,
            strike = flag("strike"),
            color = colors.of(e.child("color")) ?: 0xFF000000.toInt(),
            kind = kind,
            scaleX = if (lower.startsWith("calibri")) 0.9f else 1f,
        )
    }

    private fun readStyles(styles: Element, colors: Colors): List<CellStyle> {
        val formats = styles.child("numFmts")?.all("numFmt").orEmpty().associate {
            (it.attr("numFmtId")?.toIntOrNull() ?: -1) to (it.attr("formatCode") ?: "General")
        }
        val fonts = styles.child("fonts")?.all("font").orEmpty().map { font(it, colors) }
        val fills = styles.child("fills")?.all("fill").orEmpty().map { fill ->
            val pattern = fill.child("patternFill")
            val gradient = fill.child("gradientFill")
            when {
                pattern != null -> when (pattern.attr("patternType")) {
                    null, "none" -> 0
                    "solid" -> colors.of(pattern.child("fgColor")) ?: colors.of(pattern.child("bgColor")) ?: 0
                    // Patterns as an even mix of their colour and the background.
                    else -> (colors.of(pattern.child("fgColor")) ?: 0xFF000000.toInt()).let { blend(it, colors.of(pattern.child("bgColor")) ?: -1) }
                }
                gradient != null -> gradient.all("stop").firstOrNull()?.let { colors.of(it.child("color")) } ?: 0
                else -> 0
            }
        }
        val borders = styles.child("borders")?.all("border").orEmpty().map { b ->
            fun side(name: String): CellBorder? {
                val s = b.child(name) ?: return null
                val style = s.attr("style") ?: return null
                if (style == "none") return null
                return CellBorder(style, colors.of(s.child("color")) ?: 0xFF000000.toInt())
            }
            listOf(side("left") ?: side("start"), side("right") ?: side("end"), side("top"), side("bottom"))
        }
        val xfs = styles.child("cellXfs")?.all("xf").orEmpty()
        if (xfs.isEmpty()) return listOf(DEFAULT_STYLE)
        return xfs.map { xf ->
            val formatId = xf.attr("numFmtId")?.toIntOrNull() ?: 0
            val border = borders.getOrNull(xf.attr("borderId")?.toIntOrNull() ?: 0)
            val alignment = xf.child("alignment")
            CellStyle(
                format = formats[formatId] ?: NumberFormat.builtIn(formatId),
                font = fonts.getOrNull(xf.attr("fontId")?.toIntOrNull() ?: 0) ?: CellFont(),
                fill = fills.getOrNull(xf.attr("fillId")?.toIntOrNull() ?: 0) ?: 0,
                left = border?.get(0), right = border?.get(1), top = border?.get(2), bottom = border?.get(3),
                hAlign = when (alignment?.attr("horizontal")) {
                    "left" -> HAlign.Left
                    "center" -> HAlign.Center
                    "right" -> HAlign.Right
                    "fill" -> HAlign.Fill
                    "justify" -> HAlign.Justify
                    "centerContinuous" -> HAlign.CenterContinuous
                    "distributed" -> HAlign.Distributed
                    else -> HAlign.General
                },
                vAlign = when (alignment?.attr("vertical")) {
                    "top" -> VerticalAlign.Top
                    "center", "justify", "distributed" -> VerticalAlign.Center
                    else -> VerticalAlign.Bottom
                },
                wrap = alignment?.attr("wrapText").let { it == "1" || it == "true" },
                indent = alignment?.attr("indent")?.toIntOrNull()?.coerceIn(0, 250) ?: 0,
                shrink = alignment?.attr("shrinkToFit").let { it == "1" || it == "true" },
            )
        }
    }

    private fun blend(color: Int, background: Int): Int {
        fun ch(c: Int, shift: Int) = (((c shr shift) and 0xFF) + ((background shr shift) and 0xFF)) / 2
        return 0xFF000000.toInt() or (ch(color, 16) shl 16) or (ch(color, 8) shl 8) or ch(color, 0)
    }

    // ---- Shared strings ----

    private fun sharedStrings(bytes: ByteArray, colors: Colors): List<Any> {
        val result = mutableListOf<Any>()
        val root = Element.parse(bytes)
        for (si in root.all("si")) result += stringItem(si, colors)
        return result
    }

    /** A string item (`<si>` or `<is>`): plain text, or rich text if it has runs. Phonetic hints are left out. */
    private fun stringItem(si: Element, colors: Colors): Any {
        val runs = si.all("r")
        if (runs.isEmpty()) return si.child("t")?.text?.let(::unescape) ?: ""
        return RichText(runs.map { r -> unescape(r.child("t")?.text ?: "") to r.child("rPr")?.let { font(it, colors) } })
    }

    /** `_x000D_` and the like: characters Excel escapes in strings. */
    private fun unescape(s: String): String =
        if (!s.contains("_x")) s else ESCAPE.replace(s) { m -> m.groupValues[1].toInt(16).toChar().toString() }

    private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

    /** "AB12" → (row 11, column 27), zero-based. */
    fun cellRef(ref: String): Pair<Int, Int>? {
        var i = 0
        var column = 0
        while (i < ref.length && ref[i].isLetter()) {
            column = column * 26 + (ref[i].uppercaseChar() - 'A' + 1)
            if (column > MAX_COLUMNS) return null
            i++
        }
        val row = ref.substring(i).toIntOrNull() ?: return null
        if (i == 0 || row < 1 || row > MAX_ROWS) return null
        return (row - 1) to (column - 1)
    }

    private fun range(ref: String): CellRange? {
        val (a, b) = if (ref.contains(':')) ref.substringBefore(':') to ref.substringAfter(':') else ref to ref
        val (r1, c1) = cellRef(a.replace("$", "")) ?: return null
        val (r2, c2) = cellRef(b.replace("$", "")) ?: return null
        return CellRange(minOf(r1, r2), minOf(c1, c2), maxOf(r1, r2), maxOf(c1, c2))
    }

    // ---- Sheets ----

    private class SheetReader(
        private val styles: List<CellStyle>,
        private val shared: List<Any>,
        private val colors: Colors,
        private val cellLimit: Long,
    ) {
        var cellCount = 0L
        var truncated = false

        fun read(bytes: ByteArray, name: String, parts: ZipParts, path: String): SheetModel {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(bytes), null)

            var tabColor: Int? = null
            var showGrid = true
            var defaultRowHeight = 15f
            var defaultColumnWidth: Float? = null
            var baseColumnWidth = 8f
            val columnSpecs = mutableListOf<Triple<IntRange, Float?, Boolean>>()
            val rows = HashMap<Int, RowCells>()
            val rowHeights = HashMap<Int, Float>()
            val hiddenRows = HashSet<Int>()
            val merges = mutableListOf<CellRange>()
            val linkRefs = mutableListOf<Pair<CellRange, String>>()
            var drawingId: String? = null
            var maxRow = -1
            var maxColumn = -1
            var depth = 0

            // The row being read.
            var rowIndex = -1
            val rowColumns = ArrayList<Int>()
            val rowStyles = ArrayList<Int>()
            val rowValues = ArrayList<Any?>()
            // The cell being read.
            var cellColumn = -1
            var cellStyle = 0
            var cellType: String? = null
            var cellValue: String? = null
            var inlineRuns: MutableList<Pair<String, CellFont?>>? = null
            var runFont: CellFont? = null
            var text: StringBuilder? = null
            var inRun = false

            // Past the cell limit, rows are skipped but the rest (merges, links, drawings) is still read.
            var skipping = false

            fun finishRow() {
                if (rowIndex < 0 || rowColumns.isEmpty()) return
                val order = rowColumns.indices.sortedBy { rowColumns[it] }
                rows[rowIndex] = RowCells(
                    IntArray(order.size) { rowColumns[order[it]] },
                    IntArray(order.size) { rowStyles[order[it]] },
                    Array(order.size) { rowValues[order[it]] },
                )
                rowColumns.clear(); rowStyles.clear(); rowValues.clear()
            }

            loop@ while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth > Element.MAX_DEPTH) throw IOException("XML nested too deeply")
                        when (parser.name) {
                            "tabColor" -> tabColor = colorAttrs(parser)
                            "sheetView" -> showGrid = parser.getAttributeValue(null, "showGridLines") != "0"
                            "sheetFormatPr" -> {
                                parser.getAttributeValue(null, "defaultRowHeight")?.toFloatOrNull()?.let { defaultRowHeight = it.coerceIn(0f, 409f) }
                                defaultColumnWidth = parser.getAttributeValue(null, "defaultColWidth")?.toFloatOrNull()
                                parser.getAttributeValue(null, "baseColWidth")?.toFloatOrNull()?.let { baseColumnWidth = it }
                            }
                            "col" -> {
                                val min = parser.getAttributeValue(null, "min")?.toIntOrNull() ?: continue@loop
                                val max = parser.getAttributeValue(null, "max")?.toIntOrNull() ?: min
                                if (min < 1 || max < min) continue@loop
                                val width = parser.getAttributeValue(null, "width")?.toFloatOrNull()
                                val hidden = parser.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }
                                columnSpecs += Triple((min - 1)..(minOf(max, MAX_COLUMNS) - 1), width, hidden)
                            }
                            "row" -> {
                                finishRow()
                                rowIndex = parser.getAttributeValue(null, "r")?.toIntOrNull()?.minus(1) ?: (rowIndex + 1)
                                if (rowIndex !in 0 until MAX_ROWS) throw IOException("Bad row")
                                if (cellCount >= cellLimit) {
                                    truncated = true
                                    skipping = true
                                }
                                cellColumn = -1
                                parser.getAttributeValue(null, "ht")?.toFloatOrNull()?.let { rowHeights[rowIndex] = it.coerceIn(0f, 409f) }
                                if (parser.getAttributeValue(null, "hidden").let { it == "1" || it == "true" }) hiddenRows += rowIndex
                            }
                            "c" -> {
                                val ref = parser.getAttributeValue(null, "r")
                                cellColumn = ref?.let { cellRef(it)?.second } ?: (cellColumn + 1)
                                cellStyle = parser.getAttributeValue(null, "s")?.toIntOrNull()?.takeIf { it in styles.indices } ?: 0
                                cellType = parser.getAttributeValue(null, "t")
                                cellValue = null
                                inlineRuns = null
                            }
                            "v" -> text = StringBuilder()
                            "is" -> inlineRuns = mutableListOf()
                            "r" -> if (inlineRuns != null) { inRun = true; runFont = null }
                            "rPr" -> if (inRun) runFont = font(Element.parse(rPrXml(parser)), colors).also { depth-- }
                            "t" -> if (inlineRuns != null) text = StringBuilder()
                            "mergeCell" -> parser.getAttributeValue(null, "ref")?.let { range(it) }?.let { merges += it }
                            "hyperlink" -> {
                                val ref = parser.getAttributeValue(null, "ref")?.let { range(it) }
                                val id = parser.getAttributeValue(null, "r:id")
                                if (ref != null && id != null) linkRefs += ref to id
                            }
                            "drawing" -> drawingId = parser.getAttributeValue(null, "r:id")
                        }
                    }
                    XmlPullParser.TEXT -> text?.append(parser.text)
                    XmlPullParser.END_TAG -> {
                        depth--
                        when (parser.name) {
                            "v" -> { cellValue = text?.toString(); text = null }
                            "t" -> if (inlineRuns != null) {
                                inlineRuns!! += unescape(text?.toString() ?: "") to (if (inRun) runFont else null)
                                text = null
                            }
                            "r" -> inRun = false
                            "c" -> {
                                if (cellColumn !in 0 until MAX_COLUMNS) throw IOException("Bad column")
                                val value = value(cellType, cellValue, inlineRuns)
                                val style = styles.getOrNull(cellStyle)
                                // Empty cells matter only if they are drawn: a fill or a border.
                                val shown = value != null || (style != null && (style.fill != 0 || style.hasBorder))
                                if (shown && cellColumn < SHOWN_COLUMNS && !skipping) {
                                    rowColumns += cellColumn
                                    rowStyles += cellStyle
                                    rowValues += value
                                    cellCount++
                                    maxRow = maxOf(maxRow, rowIndex)
                                    maxColumn = maxOf(maxColumn, cellColumn)
                                }
                            }
                            "row" -> finishRow()
                        }
                    }
                    XmlPullParser.END_DOCUMENT -> break@loop
                }
            }
            finishRow()

            val sheetRels = relationships(parts, path)
            val links = HashMap<Long, String>()
            var linkCells = MAX_LINK_CELLS
            links@ for ((r, id) in linkRefs) {
                val target = sheetRels[id]?.takeIf { it.startsWith("external:") }?.removePrefix("external:") ?: continue
                // Links to many cells (a whole column) are kept for the cells that are used.
                for (row in r.firstRow..minOf(r.lastRow, maxRow)) for (col in r.firstColumn..minOf(r.lastColumn, maxColumn)) {
                    if (--linkCells < 0) break@links
                    links[key(row, col)] = target
                }
            }
            val drawings = drawingId?.let { sheetRels[it] }?.let { readDrawings(parts, resolve(path, it)) } ?: emptyList()
            for (m in merges) if (m.firstRow <= maxRow + 1 && m.firstColumn <= maxColumn + 1) {
                maxRow = maxOf(maxRow, minOf(m.lastRow, maxRow + 50))
                maxColumn = maxOf(maxColumn, minOf(m.lastColumn, SHOWN_COLUMNS - 1))
            }
            for (d in drawings) {
                maxRow = maxOf(maxRow, minOf(d.anchor.toRow, MAX_ROWS - 1))
                maxColumn = maxOf(maxColumn, minOf(d.anchor.toColumn, SHOWN_COLUMNS - 1))
            }

            // Column width in characters → points: characters of the default font's digit (7 px) plus padding.
            fun columnPoints(chars: Float): Float = (chars * 7f + 5f).coerceAtLeast(0f) * 0.75f
            val defaultWidth = defaultColumnWidth?.let { it * 7f * 0.75f } ?: columnPoints(baseColumnWidth)
            val columnWidths = FloatArray(maxColumn + 1) { defaultWidth }
            for ((range, width, hidden) in columnSpecs) {
                for (c in range) {
                    if (c > maxColumn) break
                    columnWidths[c] = if (hidden) 0f else width?.let { (it * 7f).coerceAtLeast(0f) * 0.75f } ?: defaultWidth
                }
            }
            val heights = FloatArray(maxRow + 1) { r ->
                if (r in hiddenRows) 0f else rowHeights[r] ?: defaultRowHeight
            }
            val rowArray = arrayOfNulls<RowCells>(maxRow + 1)
            for ((r, cells) in rows) if (r <= maxRow) rowArray[r] = cells
            return SheetModel(name, tabColor, showGrid, columnWidths, heights, rowArray, merges.filter { it.firstRow <= maxRow && it.firstColumn <= maxColumn }, links, drawings)
        }

        /** The run properties element under the parser, read whole into a small XML string. */
        private fun rPrXml(parser: XmlPullParser): ByteArray {
            val out = StringBuilder("<rPr>")
            var level = 1
            while (level > 0) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> {
                        level++
                        out.append('<').append(parser.name)
                        for (i in 0 until parser.attributeCount) {
                            out.append(' ').append(parser.getAttributeName(i)).append("=\"")
                                .append(parser.getAttributeValue(i).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")).append('"')
                        }
                        out.append('>')
                    }
                    XmlPullParser.END_TAG -> {
                        level--
                        out.append("</").append(parser.name).append('>')
                    }
                    XmlPullParser.END_DOCUMENT -> break
                }
                if (level > 32) throw IOException("XML nested too deeply")
            }
            return out.toString().toByteArray()
        }

        private fun colorAttrs(parser: XmlPullParser): Int? {
            val attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
            return colors.of(Element("color", attrs, emptyList(), emptyList()))
        }

        private fun value(type: String?, raw: String?, inline: List<Pair<String, CellFont?>>?): Any? = when (type) {
            "s" -> raw?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }
            "inlineStr" -> inline?.let { runs -> if (runs.any { it.second != null }) RichText(runs) else runs.joinToString("") { it.first } }
            "str" -> raw?.let(::unescape)
            "b" -> raw?.trim() == "1"
            "e" -> raw?.let { CellError(it) }
            "d" -> raw
            // Kotlin reads "NaN" and "Infinity" as numbers; Excel never writes them (nor a number past Double's range).
            else -> raw?.trim()?.toDoubleOrNull()?.let { if (it.isFinite()) it else CellError("#NUM!") }
        }

        /** Pictures and charts of a sheet's drawing part. Shapes without a picture are left out. */
        private fun readDrawings(parts: ZipParts, path: String): List<Drawing> {
            val root = parts.xml(path) ?: return emptyList()
            val rels = relationships(parts, path)
            val result = mutableListOf<Drawing>()
            fun point(e: Element?, name: String) = (e?.child(name)?.text?.trim()?.toLongOrNull() ?: 0L) / 12700f
            fun index(e: Element?, name: String) = (e?.child(name)?.text?.trim()?.toIntOrNull() ?: 0).coerceIn(0, MAX_ROWS - 1)
            for (anchor in root.children) {
                val from = anchor.child("xdr:from")
                val to = anchor.child("xdr:to")
                val ext = anchor.child("xdr:ext")
                val a = when (anchor.name) {
                    "xdr:twoCellAnchor" -> if (from == null || to == null) null else Anchor(
                        index(from, "xdr:col"), point(from, "xdr:colOff"), index(from, "xdr:row"), point(from, "xdr:rowOff"),
                        index(to, "xdr:col"), point(to, "xdr:colOff"), index(to, "xdr:row"), point(to, "xdr:rowOff"),
                    )
                    "xdr:oneCellAnchor" -> if (from == null || ext == null) null else {
                        val col = index(from, "xdr:col")
                        val row = index(from, "xdr:row")
                        Anchor(col, point(from, "xdr:colOff"), row, point(from, "xdr:rowOff"), col, 0f, row, 0f,
                            width = (ext.attr("cx")?.toLongOrNull() ?: 0L) / 12700f, height = (ext.attr("cy")?.toLongOrNull() ?: 0L) / 12700f)
                    }
                    else -> null
                } ?: continue
                val content = anchor.children.firstOrNull { it.name in setOf("xdr:pic", "xdr:graphicFrame", "xdr:sp", "xdr:grpSp") } ?: continue
                when (content.name) {
                    "xdr:pic" -> {
                        val embed = content.find("a:blip")?.attr("r:embed")
                        val bytes = embed?.let { rels[it] }?.let { parts[resolve(path, it)] }
                        result += Drawing(a, bytes)
                    }
                    // Charts and other objects: their place, empty.
                    "xdr:graphicFrame" -> result += Drawing(a, null)
                    // Text boxes and groups: their place too; plain shapes (lines, arrows) are left out.
                    "xdr:sp" -> if (content.find("a:t") != null) result += Drawing(a, null)
                    "xdr:grpSp" -> result += Drawing(a, null)
                }
            }
            return result
        }
    }

    fun key(row: Int, column: Int): Long = (row.toLong() shl 20) or column.toLong()
}
