package com.tabpdf.doc.office

/*
 * What docx and hwpx are read into (기획서 5.7): a word-processor document reduced to what this
 * viewer draws — paragraphs of styled text, tables, pictures — on sheets of a given size. Both
 * readers produce it and one layout (OfficeLayout) draws it. Lengths are in points.
 */

class OfficeDocument(
    val page: PageSetup,
    val body: List<OfficeBlock>,
    val header: List<OfficeBlock> = emptyList(),
    val footer: List<OfficeBlock> = emptyList(),
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
    /** Style of the paragraph mark: sets the height of an empty paragraph. */
    val markStyle: TextStyle? = null,
) : OfficeBlock

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
)

sealed interface Run {
    data class Text(val text: String, val style: TextStyle, val link: String? = null) : Run
    /** A picture in the text, [width] × [height] points; null bytes if it couldn't be read. */
    class Picture(val bytes: ByteArray?, val width: Float, val height: Float) : Run
    /** Something this viewer doesn't draw (chart, equation, drawing): an empty box of its size. */
    class Unsupported(val width: Float, val height: Float) : Run
}

class Table(
    /** Width of each grid column. */
    val columns: List<Float>,
    val rows: List<TableRow>,
    val cells: List<TableCell>,
    val indent: Float = 0f,
    val align: Align = Align.Left,
    /** Starts a new page. */
    val pageBreakBefore: Boolean = false,
) : OfficeBlock

class TableRow(val minHeight: Float = 0f, val exactHeight: Boolean = false)

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

/** A line: [width] in points and colour; null sides are not drawn. */
data class Border(val width: Float, val color: Int)

data class Borders(val left: Border?, val top: Border?, val right: Border?, val bottom: Border?) {
    companion object {
        val None = Borders(null, null, null, null)
    }
}

data class Insets(val left: Float, val top: Float, val right: Float, val bottom: Float)
