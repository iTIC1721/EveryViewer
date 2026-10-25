package com.tabpdf.doc.office

import com.tabpdf.doc.DocumentOpenException
import kr.dogfoot.hwplib.`object`.HWPFile
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlFooter
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlHeader
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlSectionDefine
import kr.dogfoot.hwplib.`object`.bodytext.control.ControlTable
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.gso.RelativeArrange
import kr.dogfoot.hwplib.`object`.bodytext.control.ctrlheader.header.HeaderFooterApplyPage
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.ControlPicture
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.GsoControl
import kr.dogfoot.hwplib.`object`.bodytext.control.gso.textbox.TextVerticalAlignment
import kr.dogfoot.hwplib.`object`.bodytext.control.sectiondefine.PaperDirection
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.ParagraphList
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.text.HWPCharType
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.BorderThickness
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.BorderType
import kr.dogfoot.hwplib.`object`.docinfo.borderfill.EachBorder
import kr.dogfoot.hwplib.`object`.docinfo.charshape.UnderLineSort
import kr.dogfoot.hwplib.`object`.docinfo.parashape.Alignment
import kr.dogfoot.hwplib.`object`.docinfo.parashape.LineDivideForHangul
import kr.dogfoot.hwplib.`object`.docinfo.parashape.LineSpaceSort
import kr.dogfoot.hwplib.`object`.etc.Color4Byte
import kr.dogfoot.hwplib.reader.HWPReader
import java.io.IOException
import java.io.InputStream
import kr.dogfoot.hwplib.`object`.bodytext.paragraph.Paragraph as HwpParagraph
import kr.dogfoot.hwplib.`object`.docinfo.CharShape as HwpCharShape
import kr.dogfoot.hwplib.`object`.docinfo.ParaShape as HwpParaShape
import kr.dogfoot.hwplib.`object`.docinfo.parashape.ParaHeadShape

/**
 * Hangul's binary documents (.hwp, HWP 5.0) into an [OfficeDocument] (기획서 5.7, 11장 26번).
 * The file is read by hwplib; this turns its records into the same model as [HwpxReader] (hwpx
 * is the same document model written as XML), so both are laid out alike. Lengths are HWPUNIT.
 */
object HwpReader {
    fun read(input: InputStream): OfficeDocument {
        val file = try {
            HWPReader.fromInputStream(input)
        } catch (e: Exception) {
            if (e.message?.contains("password", ignoreCase = true) == true) {
                throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
            }
            throw IOException("Not a readable HWP file", e)
        }
        val header = file.fileHeader
        if (header.hasPassword()) throw DocumentOpenException(DocumentOpenException.Reason.PasswordProtected)
        if (header.isDRMDocument || header.isPublicCertificationDRMDocument || header.isEncryptPublicCertification) {
            throw DocumentOpenException(DocumentOpenException.Reason.Protected)
        }
        return Hwp(file).read()
    }
}

private const val HWPUNIT = 1f / 100f // points per HWPUNIT

private class Hwp(private val file: HWPFile) {
    private val info = file.docInfo
    private val charStyles: List<TextStyle> = info.charShapeList.map(::charStyle)
    private val paraShapes: List<HwpParaShape> = info.paraShapeList

    /** Pictures by bin item id (1-based, as the document refers to them). */
    private val pictures: Map<Int, ByteArray> = run {
        val stored = file.binData.embeddedBinaryDataList.associate { it.name.substringBefore('.').uppercase() to it.data }
        info.binDataList.mapIndexedNotNull { i, b ->
            // Embedded items are stored as BIN0001.png and so on, numbered by their storage id in hex.
            val name = "BIN%04X".format(b.binDataID)
            stored[name]?.let { (i + 1) to it }
        }.toMap()
    }

    private var headerBlocks: List<OfficeBlock> = emptyList()
    private var footerBlocks: List<OfficeBlock> = emptyList()
    private var pageSetup: PageSetup? = null

    fun read(): OfficeDocument {
        val sections = file.bodyText.sectionList
        if (sections.isEmpty()) throw IOException("No sections")
        val body = mutableListOf<OfficeBlock>()
        for ((i, section) in sections.withIndex()) {
            val blocks = blocks(section.paragraphs.toList())
            // A new section starts on a new page.
            if (i > 0) (blocks.firstOrNull() as? Paragraph)?.let { first ->
                body += Paragraph(emptyList(), pageBreakBefore = true, markStyle = first.markStyle)
            }
            body += blocks
        }
        return OfficeDocument(pageSetup ?: a4(), body, headerBlocks, footerBlocks)
    }

    private fun a4() = PageSetup(595f, 842f, 85f, 85f, 85f, 842f - 85f, 42f, 842f - 42f)

    private fun blocks(list: ParagraphList?): List<OfficeBlock> = blocks(list?.paragraphs?.toList().orEmpty())

    private fun blocks(paragraphs: List<HwpParagraph>): List<OfficeBlock> = buildList {
        for (p in paragraphs) addAll(paragraph(p))
    }

    // ---- Paragraphs ----

    /**
     * A paragraph's text, with any tables in it put after the text before them, as Hangul
     * places a table anchored in a paragraph on its own lines.
     */
    private fun paragraph(p: HwpParagraph): List<OfficeBlock> {
        val shape = paraShapes.getOrNull(p.header.paraShapeId)
        val result = mutableListOf<OfficeBlock>()
        var runs = mutableListOf<Run>()
        // Divide sort: 0x01 section, 0x04 page break.
        var pageBreak = (p.header.divideSort.value.toInt() and 0x05) != 0 || shape?.property1?.isSplitPageBeforePara == true
        val shapeAt = p.charShape?.positonShapeIdPairList.orEmpty().map { it.position.toInt() to it.shapeId.toInt() }
        var markStyle: TextStyle? = shapeAt.firstOrNull()?.let { charStyles.getOrNull(it.second) }

        fun flushText(force: Boolean) {
            val hasContent = runs.any { it !is Run.Text || it.text.isNotEmpty() }
            if (hasContent || force) {
                val intent = shape?.indent?.times(HWPUNIT) ?: 0f
                result += Paragraph(
                    runs = runs,
                    align = when (shape?.property1?.alignment) {
                        Alignment.Center -> Align.Center
                        Alignment.Right -> Align.Right
                        Alignment.Justify, Alignment.Distribute, Alignment.Divide -> Align.Justify
                        else -> Align.Left
                    },
                    // Hangul's hanging indent (negative indent) keeps the first line at the margin.
                    indentLeft = (shape?.leftMargin?.times(HWPUNIT) ?: 0f) + (intent.coerceAtMost(0f) * -1f),
                    indentRight = shape?.rightMargin?.times(HWPUNIT) ?: 0f,
                    firstLine = intent,
                    spaceBefore = shape?.topParaSpace?.times(HWPUNIT) ?: 0f,
                    spaceAfter = shape?.bottomParaSpace?.times(HWPUNIT) ?: 0f,
                    lineSpacing = shape?.let(::lineSpacing) ?: LineSpacing.Multiple(1.6f),
                    pageBreakBefore = pageBreak,
                    // Bit 7 set keeps Korean words whole (Hangul writes the same paragraph as KEEP_WORD in hwpx);
                    // hwplib names that value ByLetter.
                    keepWords = shape?.property1?.lineDivideForHangul == LineDivideForHangul.ByLetter,
                    markStyle = markStyle,
                    // 개요 paragraphs make the table of contents, as in hwpx.
                    outlineLevel = shape?.property1?.takeIf { it.paraHeadShape == ParaHeadShape.Outline }
                        ?.paraLevel?.toInt()?.takeIf { it in 0..9 && result.none { b -> b is Paragraph } },
                )
                pageBreak = false
            }
            runs = mutableListOf()
        }

        // Text by character shape: a run ends where the next shape starts (positions count UTF-16 units).
        val controls = p.controlList.orEmpty().iterator()
        val text = StringBuilder()
        var style = markStyle ?: TextStyle()
        var position = 0
        var nextShape = 0
        fun flushRun() {
            if (text.isNotEmpty()) runs += Run.Text(text.toString(), style)
            text.clear()
        }
        var hadObject = false
        for (ch in p.text?.charList.orEmpty()) {
            while (nextShape < shapeAt.size && shapeAt[nextShape].first <= position) {
                flushRun()
                style = charStyles.getOrNull(shapeAt[nextShape].second) ?: style
                if (markStyle == null) markStyle = style
                nextShape++
            }
            when (ch.type) {
                HWPCharType.Normal -> text.append(ch.code.toChar())
                HWPCharType.ControlChar -> when (ch.code) {
                    10 -> text.append('\n')
                    24 -> text.append('-')
                    30 -> text.append(' ')
                    31 -> text.append('　')
                }
                HWPCharType.ControlInline -> if (ch.code == 9) text.append('\t')
                HWPCharType.ControlExtend -> {
                    // Extended characters stand for the paragraph's controls, in order.
                    val control = if (controls.hasNext()) controls.next() else null
                    when (control) {
                        is ControlTable -> {
                            flushRun()
                            flushText(force = false)
                            result += table(control, pageBreak).also { pageBreak = false }
                            hadObject = true
                        }
                        is ControlPicture -> {
                            flushRun()
                            runs += picture(control)
                        }
                        is GsoControl -> {
                            flushRun()
                            runs += unsupported(control)
                        }
                        is ControlSectionDefine -> pageSetup = pageSetup ?: page(control)
                        is ControlHeader -> if (headerBlocks.isEmpty() && control.header.applyPage != HeaderFooterApplyPage.EvenPage) {
                            headerBlocks = blocks(control.paragraphList)
                        }
                        is ControlFooter -> if (footerBlocks.isEmpty() && control.header.applyPage != HeaderFooterApplyPage.EvenPage) {
                            footerBlocks = blocks(control.paragraphList)
                        }
                        else -> Unit
                    }
                }
                null -> Unit
            }
            position += ch.charSize
        }
        flushRun()
        // An empty paragraph is an empty line, unless it only held a table.
        flushText(force = result.isEmpty() && !hadObject)
        return result
    }

    private fun lineSpacing(shape: HwpParaShape): LineSpacing {
        // Newer files keep the line spacing in a second field; older ones only in the first.
        val newer = shape.lineSpace2 != 0L
        val sort = if (newer) shape.property3.lineSpaceSort else shape.property1.lineSpaceSort
        val value = if (newer) shape.lineSpace2.toFloat() else shape.lineSpace.toFloat()
        return when (sort) {
            LineSpaceSort.FixedValue -> LineSpacing.Exactly(value * HWPUNIT)
            LineSpaceSort.AtLeast -> LineSpacing.AtLeast(value * HWPUNIT)
            LineSpaceSort.OnlyMargin -> LineSpacing.Multiple(1f + value * HWPUNIT / 10f)
            else -> LineSpacing.Multiple(value / 100f)
        }
    }

    private fun picture(c: ControlPicture): Run {
        val h = c.header
        val id = c.shapeComponentPicture?.pictureInfo?.binItemID ?: 0
        return Run.Picture(pictures[id], h.width * HWPUNIT, h.height * HWPUNIT)
    }

    private fun unsupported(c: GsoControl): Run = Run.Unsupported(c.header.width * HWPUNIT, c.header.height * HWPUNIT)

    private fun page(c: ControlSectionDefine): PageSetup? {
        val d = c.pageDef ?: return null
        var width = d.paperWidth * HWPUNIT
        var height = d.paperHeight * HWPUNIT
        if (d.property?.paperDirection == PaperDirection.Landscape && width < height) width = height.also { height = width }
        val top = d.topMargin * HWPUNIT
        val bottom = d.bottomMargin * HWPUNIT
        // Hangul's top margin is above the header area; the body starts below the header.
        return PageSetup(
            width = width,
            height = height,
            left = (d.leftMargin + d.gutterMargin) * HWPUNIT,
            right = d.rightMargin * HWPUNIT,
            bodyTop = top + d.headerMargin * HWPUNIT,
            bodyBottom = height - bottom - d.footerMargin * HWPUNIT,
            headerTop = top,
            footerBottom = height - bottom,
        )
    }

    // ---- Tables ----

    private fun table(control: ControlTable, pageBreak: Boolean): OfficeBlock {
        val t = control.table
        val tableMargin = Insets(t.leftInnerMargin * HWPUNIT, t.topInnerMargin * HWPUNIT, t.rightInnerMargin * HWPUNIT, t.bottomInnerMargin * HWPUNIT)
        val raw = control.rowList.flatMap { it.cellList }
        val columnCount = maxOf(t.columnCount, raw.maxOfOrNull { it.listHeader.colIndex + it.listHeader.colSpan.coerceAtLeast(1) } ?: 0)
        val rowCount = maxOf(t.rowCount, raw.maxOfOrNull { it.listHeader.rowIndex + it.listHeader.rowSpan.coerceAtLeast(1) } ?: 0)
        if (columnCount > 10_000 || rowCount > 100_000) throw IOException("Table too large")

        // Column edges from the cell widths: each cell fixes the distance between two edges.
        val edges = arrayOfNulls<Float>(columnCount + 1).also { it[0] = 0f }
        repeat(columnCount) {
            for (c in raw) {
                val lh = c.listHeader
                val end = (lh.colIndex + lh.colSpan.coerceAtLeast(1)).coerceIn(0, columnCount)
                val start = lh.colIndex.coerceIn(0, columnCount)
                val from = edges[start]
                val to = edges[end]
                if (from != null && to == null) edges[end] = from + lh.width * HWPUNIT
                if (from == null && to != null) edges[start] = to - lh.width * HWPUNIT
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
        for (c in raw) {
            val lh = c.listHeader
            if (lh.rowSpan <= 1 && lh.rowIndex in 0 until rowCount) rowHeights[lh.rowIndex] = maxOf(rowHeights[lh.rowIndex], lh.height * HWPUNIT)
        }

        val cells = raw.map { c ->
            val lh = c.listHeader
            // Border fill ids count from 1.
            val bf = info.borderFillList.getOrNull((if (lh.borderFillId > 0) lh.borderFillId else t.borderFillId) - 1)
            val col = lh.colIndex.coerceIn(0, columnCount - 1)
            val span = lh.colSpan.coerceAtLeast(1)
            val padding = if (lh.property?.isApplyInnerMagin == true) {
                Insets(lh.leftMargin * HWPUNIT, lh.topMargin * HWPUNIT, lh.rightMargin * HWPUNIT, lh.bottomMargin * HWPUNIT)
            } else tableMargin
            TableCell(
                row = lh.rowIndex.coerceAtLeast(0),
                column = col,
                rowSpan = lh.rowSpan.coerceAtLeast(1),
                columnSpan = span,
                blocks = blocks(c.paragraphList),
                fill = bf?.let(::fill) ?: 0,
                borders = bf?.let { Borders(side(it.leftBorder), side(it.topBorder), side(it.rightBorder), side(it.bottomBorder)) } ?: Borders.None,
                padding = fitPadding(padding, columns.subList(col, (col + span).coerceAtMost(columns.size)).sum(), c.paragraphList),
                verticalAlign = when (lh.property?.textVerticalAlignment) {
                    TextVerticalAlignment.Center -> VerticalAlign.Center
                    TextVerticalAlignment.Bottom -> VerticalAlign.Bottom
                    else -> VerticalAlign.Top
                },
            )
        }
        return Table(
            columns = columns,
            rows = rowHeights.map { TableRow(minHeight = it) },
            cells = cells,
            align = when (control.header?.property?.horzRelativeArrange) {
                RelativeArrange.Center -> Align.Center
                RelativeArrange.BottomOrRight -> Align.Right
                else -> Align.Left
            },
            pageBreakBefore = pageBreak,
        )
    }

    /**
     * Hangul shrinks a cell's side margins when the cell is too narrow for them. It records the
     * text width it actually used on each line (line segment), so take the margins from that.
     */
    private fun fitPadding(padding: Insets, cellWidth: Float, list: ParagraphList?): Insets {
        val p = list?.paragraphs?.firstOrNull() ?: return padding
        val used = p.lineSeg?.lineSegItemList?.firstOrNull()?.segmentWidth?.times(HWPUNIT) ?: return padding
        val shape = paraShapes.getOrNull(p.header.paraShapeId)
        val inner = used + (shape?.leftMargin?.times(HWPUNIT) ?: 0f) + (shape?.rightMargin?.times(HWPUNIT) ?: 0f)
        val sides = padding.left + padding.right
        if (sides <= 0f || inner <= 0f || cellWidth - sides >= inner - 0.5f) return padding
        val total = (cellWidth - inner).coerceAtLeast(0f)
        return padding.copy(left = total * padding.left / sides, right = total * padding.right / sides)
    }

    private fun side(b: EachBorder?): Border? {
        if (b == null || b.type == null || b.type == BorderType.None) return null
        val mm = THICKNESS[b.thickness] ?: 0.12f
        val width = mm * 72f / 25.4f
        val double = b.type == BorderType.Double || b.type == BorderType.ThinThick || b.type == BorderType.ThickThin || b.type == BorderType.ThinThickThin
        return Border(if (double) width * 2.5f else width, rgb(b.color) ?: 0xFF000000.toInt())
    }

    private fun fill(bf: kr.dogfoot.hwplib.`object`.docinfo.BorderFill): Int? {
        val info = bf.fillInfo ?: return null
        if (info.type?.hasPatternFill() != true) return null
        val color = rgb(info.patternFill?.backColor) ?: return null
        return if (color == 0xFFFFFFFF.toInt()) null else color
    }

    // ---- Shapes ----

    private fun charStyle(c: HwpCharShape): TextStyle {
        val p = c.property
        val face = info.hangulFaceNameList.getOrNull(c.faceNameIds?.hangul ?: 0)?.name
        val ratio = c.ratios?.hangul?.toFloat() ?: 100f
        val spacing = c.charSpaces?.hangul?.toFloat() ?: 0f
        return TextStyle(
            size = c.baseSize / 100f,
            bold = p.isBold,
            italic = p.isItalic,
            underline = p.underLineSort != null && p.underLineSort != UnderLineSort.None,
            strike = p.isStrikeLine,
            color = rgb(c.charColor) ?: 0xFF000000.toInt(),
            background = rgb(c.shadeColor)?.takeIf { it != 0xFFFFFFFF.toInt() && (c.shadeColor.value and 0xFF000000L) == 0L } ?: 0,
            font = face?.let(::fontKind) ?: FontKind.Sans,
            scaleX = ratio / 100f,
            letterSpacing = spacing / 100f,
            superscript = p.isSuperScript,
            subscript = p.isSubScript,
        )
    }

    /** A COLORREF (0x00BBGGRR) as an opaque colour. */
    private fun rgb(c: Color4Byte?): Int? {
        c ?: return null
        return 0xFF000000.toInt() or (c.r.toInt() shl 16) or (c.g.toInt() shl 8) or c.b.toInt()
    }

    private companion object {
        val THICKNESS = mapOf(
            BorderThickness.MM0_1 to 0.1f, BorderThickness.MM0_12 to 0.12f, BorderThickness.MM0_15 to 0.15f,
            BorderThickness.MM0_2 to 0.2f, BorderThickness.MM0_25 to 0.25f, BorderThickness.MM0_3 to 0.3f,
            BorderThickness.MM0_4 to 0.4f, BorderThickness.MM0_5 to 0.5f, BorderThickness.MM0_6 to 0.6f,
            BorderThickness.MM0_7 to 0.7f, BorderThickness.MM1_0 to 1f, BorderThickness.MM1_5 to 1.5f,
            BorderThickness.MM2_0 to 2f, BorderThickness.MM3_0 to 3f, BorderThickness.MM4_0 to 4f, BorderThickness.MM5_0 to 5f,
        )
    }
}
