package com.tabpdf.doc.office

/*
 * What docx and hwpx are read into (기획서 5.7): a word-processor document reduced to what this
 * viewer draws — paragraphs of styled text, tables, pictures — on sheets of a given size. Both
 * readers produce it and one layout (OfficeLayout) draws it. Lengths are in points. HTML is read
 * into the same blocks and laid out as one flowing sheet (OfficeLayout.flow).
 */

/** Paper a file can state, in points (PDF's own limit is 14 400); outside this it is broken or crafted. */
private val PAPER_POINTS = 1f..14_400f

/**
 * Throws unless [width] × [height] (points) is paper that can be laid out: a zero width would make
 * every length endless (lengths are scaled by FLOW_WIDTH / width).
 */
fun checkPaper(width: Float, height: Float) {
    if (width !in PAPER_POINTS || height !in PAPER_POINTS) throw java.io.IOException("Bad paper size")
}

/**
 * Most columns and rows a table in a file may have; beyond it the file is crafted (Word makes at most
 * 63 columns). Laying a table out takes columns × cells work, so a few bytes claiming billions would
 * never finish.
 */
const val MAX_TABLE_COLUMNS = 10_000
const val MAX_TABLE_ROWS = 100_000

class OfficeDocument(
    val page: PageSetup,
    val body: List<OfficeBlock>,
    val header: List<OfficeBlock> = emptyList(),
    val footer: List<OfficeBlock> = emptyList(),
    /** The first page's own header and footer (Word's "different first page"), null if it has none. */
    val firstHeader: List<OfficeBlock>? = null,
    val firstFooter: List<OfficeBlock>? = null,
)

/** Sheet size and margins. The body runs from [bodyTop] to [bodyBottom] (from the sheet's top). */
class PageSetup(
    val width: Float,
    val height: Float,
    val left: Float,
    val right: Float,
    val bodyTop: Float,
    val bodyBottom: Float,
    /** Top of the header area. */
    val headerTop: Float,
    /** Bottom of the footer area. */
    val footerBottom: Float,
)

sealed interface OfficeBlock

enum class Align { Left, Center, Right, Justify }

class Paragraph(
    val runs: List<Run>,
    val align: Align = Align.Left,
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    /** Extra indent of the first line; negative for a hanging indent. */
    val firstLine: Float = 0f,
    val spaceBefore: Float = 0f,
    val spaceAfter: Float = 0f,
    val lineSpacing: LineSpacing = LineSpacing.Multiple(1f),
    /** Starts a new page. */
    val pageBreakBefore: Boolean = false,
    /** Korean words are kept whole at line ends (break only at spaces), as both editors do by default. */
    val keepWords: Boolean = true,
    /** Text put before the first line (a list's bullet or number). */
    val label: Run? = null,
    /**
     * The label fills the hanging indent, so the text starts at [indentLeft] (PowerPoint's
     * bullets); otherwise it is followed by a space.
     */
    val labelInHang: Boolean = false,
    /** Style of the paragraph mark: sets the height of an empty paragraph. */
    val markStyle: TextStyle? = null,
    /** A heading of the outline (table of contents), 0 the top level (Word's outline level, Hangul's 개요, HTML's h1). */
    val outlineLevel: Int? = null,
    /** Names of places that start here (HTML ids), for links into the document. */
    val anchors: List<String> = emptyList(),
    /** The paragraph's style, when [contextualSpacing] needs it. */
    val styleKey: String? = null,
    /** No space before or after it next to a paragraph of the same [styleKey] (Word's "contextual spacing"). */
    val contextualSpacing: Boolean = false,
) : OfficeBlock {
    /** The text, for the table of contents. */
    val plainText: String get() = runs.joinToString("") { (it as? Run.Text)?.text ?: "" }
}

sealed interface LineSpacing {
    /**
     * Line pitch [factor] × the line's font size (1.6 for Hangul's "160%"). Both editors put the
     * text at the top of the line, its baseline [baseline] font sizes down, and the rest of the
     * pitch below it; the text itself is [textHeight] font sizes tall (the extra under the last
     * line of a cell is not counted).
     */
    data class Multiple(val factor: Float, val baseline: Float = 0.85f, val textHeight: Float = 1f) : LineSpacing
    /** At least [points] per line (Word's "at least"). */
    data class AtLeast(val points: Float) : LineSpacing
    /** Exactly [points] per line. */
    data class Exactly(val points: Float) : LineSpacing
    /**
     * Word's "single" line times [factor]: as tall as the tallest font on the line makes a single
     * line ([OfficeFonts]), the text at its top and the rest of the pitch below it.
     */
    data class Single(val factor: Float = 1f) : LineSpacing
}

enum class FontKind { Sans, Serif, Mono }

data class TextStyle(
    val size: Float = 10f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    /** Highlight behind the text, or 0. */
    val background: Int = 0,
    val font: FontKind = FontKind.Sans,
    /** Horizontal glyph scale, 1 = normal (Hangul "장평"). */
    val scaleX: Float = 1f,
    /** Extra space between letters, in ems (Hangul "자간"). */
    val letterSpacing: Float = 0f,
    val superscript: Boolean = false,
    val subscript: Boolean = false,
    /**
     * Word's typefaces: Latin letters in [latinFont], Hangul and Han in [eastAsiaFont] (with
     * [eastAsiaHint], also the letters both share). Set, the text keeps those fonts' widths and
     * line heights ([OfficeFonts]) though drawn in the system's.
     */
    val latinFont: String? = null,
    val eastAsiaFont: String? = null,
    val eastAsiaHint: Boolean = false,
    /** Word kerns pairs of letters (w:kern, set for text this size). */
    val kerning: Boolean = false,
) {
    /** The size the letters are drawn at, smaller for super- and subscripts. */
    val textSize: Float get() = size * if (superscript || subscript) SMALL_SCRIPT else 1f
}

/** Super- and subscripts' size relative to the text. */
const val SMALL_SCRIPT = 0.7f

sealed interface Run {
    data class Text(val text: String, val style: TextStyle, val link: String? = null) : Run
    /** A picture in the text, [width] × [height] points; null bytes if it couldn't be read. */
    class Picture(val bytes: ByteArray?, val width: Float, val height: Float, val floating: Floating? = null) : Run
    /** Something this viewer doesn't draw (chart, equation, drawing): an empty box of its size. */
    class Unsupported(val width: Float, val height: Float) : Run
    /** Where Word last ended a page before this, when it saved the file (w:lastRenderedPageBreak). */
    data object PageEnd : Run
}

class Table(
    /** Width of each grid column; 0 for one sized to its content (HTML). */
    val columns: List<Float>,
    val rows: List<TableRow>,
    val cells: List<TableCell>,
    val indent: Float = 0f,
    val align: Align = Align.Left,
    /** Starts a new page. */
    val pageBreakBefore: Boolean = false,
    val spaceBefore: Float = 0f,
    /** Width wanted for the whole table, when columns are sized to their content. */
    val width: Float? = null,
    /** Room kept on the right of a content-sized table. */
    val indentRight: Float = 0f,
    /** A row is as much taller as the line above it is wide (Word; rows of exact height excepted). */
    val bordersInRows: Boolean = false,
) : OfficeBlock

/**
 * Blocks drawn on a background or inside borders (an HTML element with them). The box spans the
 * width left by [indentLeft] and [indentRight]; pages may end inside it, between its lines.
 */
class Box(
    val blocks: List<OfficeBlock>,
    val fill: Int = 0,
    val borders: Borders = Borders.None,
    val padding: Insets = Insets(0f, 0f, 0f, 0f),
    val indentLeft: Float = 0f,
    val indentRight: Float = 0f,
    val spaceBefore: Float = 0f,
) : OfficeBlock

/** [repeats]: a heading row, drawn again at the top of each page the table goes on to. */
class TableRow(val minHeight: Float = 0f, val exactHeight: Boolean = false, val repeats: Boolean = false)

class TableCell(
    val row: Int,
    val column: Int,
    val rowSpan: Int = 1,
    val columnSpan: Int = 1,
    val blocks: List<OfficeBlock>,
    val fill: Int = 0,
    val borders: Borders = Borders.None,
    val padding: Insets = Insets(5.4f, 0f, 5.4f, 0f),
    val verticalAlign: VerticalAlign = VerticalAlign.Top,
)

enum class VerticalAlign { Top, Center, Bottom }

/**
 * Where a picture floats over the text instead of sitting in a line (Word's "in front of text" or
 * "behind text"; it takes no room): [x] points across from the text column's left edge, or the
 * page's when [fromPage], unless [align] puts it at that frame's left, centre or right; [y] points
 * down from its paragraph's top.
 */
class Floating(val x: Float, val fromPage: Boolean, val align: Align?, val y: Float, val behind: Boolean)

/** A line: [width] in points and colour; null sides are not drawn. */
data class Border(val width: Float, val color: Int)

data class Borders(val left: Border?, val top: Border?, val right: Border?, val bottom: Border?) {
    companion object {
        val None = Borders(null, null, null, null)
    }
}

data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)
