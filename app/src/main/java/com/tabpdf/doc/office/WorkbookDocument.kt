package com.tabpdf.doc.office

import com.tabpdf.doc.Pictures
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import com.tabpdf.doc.Document
import com.tabpdf.doc.PageSize
import com.tabpdf.doc.TocEntry
import com.tabpdf.doc.PageText
import com.tabpdf.doc.SearchMatch
import com.tabpdf.doc.TextSearch
import com.tabpdf.doc.TextSelection
import com.tabpdf.doc.collectLayoutText
import com.tabpdf.doc.isWebLink
import com.tabpdf.doc.offset
import com.tabpdf.doc.paginate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An Excel workbook shown as pages (기획서 5.7: xlsx, 11장 25번): each sheet's used range at its
 * own column widths and row heights, as wide as the sheet (at least a phone-page width), cut
 * between rows into pages short enough to stay sharp when zoomed. Each sheet starts on a new page
 * under a band with its name. Pages follow on without gaps, like a sheet scrolled in Excel.
 */
class WorkbookDocument private constructor(private val book: Workbook) : Document {

    /** A sheet placed on pages: column and row edges from the sheet's top-left cell, in points. */
    private class Placed(val sheet: SheetModel, val index: Int) {
        val columnLefts = FloatArray(sheet.columnWidths.size + 1).also { for (i in sheet.columnWidths.indices) it[i + 1] = it[i] + sheet.columnWidths[i] }
        val rowTops = FloatArray(sheet.rowHeights.size + 1).also { for (i in sheet.rowHeights.indices) it[i + 1] = it[i] + sheet.rowHeights[i] }
        /** As wide as the used columns, and any picture that reaches past them. */
        val width = max(max(columnLefts.last() + 2 * MARGIN, MIN_WIDTH), sheet.drawings.maxOfOrNull { drawingRect(it).right + MARGIN } ?: 0f)
        val height = HEADER + rowTops.last() + MARGIN

        /** Merged ranges by their top-left cell, and every other cell they cover. */
        val mergeAt = HashMap<Long, CellRange>()
        val covered = HashSet<Long>()

        init {
            // For all merges together: a file can repeat a merge of 100 000 cells without end.
            var budget = MAX_COVERED_CELLS
            for (m in sheet.merges) {
                mergeAt[XlsxReader.key(m.firstRow, m.firstColumn)] = m
                // Huge merges (whole rows) only matter where cells are.
                val lastRow = min(m.lastRow, sheet.rowHeights.size - 1)
                val lastColumn = min(m.lastColumn, sheet.columnWidths.size - 1)
                val cells = (lastRow - m.firstRow + 1).toLong() * (lastColumn - m.firstColumn + 1)
                if (cells > 100_000 || cells > budget) continue
                budget -= cells
                for (r in m.firstRow..lastRow) for (c in m.firstColumn..lastColumn) {
                    if (r != m.firstRow || c != m.firstColumn) covered += XlsxReader.key(r, c)
                }
            }
        }

        fun x(column: Int) = MARGIN + columnLefts[column.coerceIn(0, columnLefts.size - 1)]
        fun y(row: Int) = HEADER + rowTops[row.coerceIn(0, rowTops.size - 1)]

        /** The row at [y] (sheet coordinates), or -1 above the first. */
        fun rowAt(y: Float): Int = indexAt(rowTops, y - HEADER)
        fun columnAt(x: Float): Int = indexAt(columnLefts, x - MARGIN)

        private fun indexAt(edges: FloatArray, v: Float): Int {
            if (edges.size < 2 || v < 0f || v >= edges.last()) return -1
            var i = Arrays.binarySearch(edges, v)
            if (i < 0) i = -i - 2
            // Zero-size (hidden) rows and columns share an edge with the next: take the visible one.
            while (i + 1 < edges.size - 1 && edges[i + 1] <= v) i++
            return i.coerceIn(0, edges.size - 2)
        }

        fun rect(r: CellRange) = RectF(x(r.firstColumn), y(r.firstRow), x(r.lastColumn + 1), y(r.lastRow + 1))

        fun drawingRect(d: Drawing): RectF {
            val a = d.anchor
            fun xAt(column: Int, offset: Float) = x(column) + offset
            fun yAt(row: Int, offset: Float) = y(row) + offset
            val left = xAt(a.fromColumn, a.fromColumnOffset)
            val top = yAt(a.fromRow, a.fromRowOffset)
            return if (a.width != null && a.height != null) RectF(left, top, left + a.width, top + a.height)
            else RectF(left, top, xAt(a.toColumn, a.toColumnOffset), yAt(a.toRow, a.toRowOffset))
        }
    }

    /** One page: a slice [top, bottom) of a sheet. */
    private class Page(val sheet: Placed, val top: Float, val bottom: Float)

    private val placed = book.sheets.mapIndexed { i, s -> Placed(s, i) }
    private val pages: List<Page> = placed.flatMap { p ->
        val breaks = FloatArray(p.rowTops.size) { HEADER + p.rowTops[it] }
        val pageHeight = (p.width * 1.414f).coerceIn(MIN_PAGE_HEIGHT, MAX_PAGE_HEIGHT)
        val bounds = paginate(p.height, breaks, pageHeight)
        (0 until bounds.size - 1).map { Page(p, bounds[it], bounds[it + 1]) }
    }

    override val pageSizes: List<PageSize> = pages.map { PageSize(it.sheet.width, it.bottom - it.top) }

    override val pageGaps: Boolean get() = false

    override val notice: String? = if (book.truncated) "행이 많아 앞부분만 표시합니다." else null

    private val mutex = Mutex()
    private var closed = false

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock null
            val page = pages[index]
            val scale = widthPx / page.sheet.width
            val heightPx = ((page.bottom - page.top) * scale).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for sheet page $index at ${widthPx}x$heightPx", e)
                return@withLock null
            }
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            drawSlice(canvas, page, scale)
            bitmap
        }
    }

    override suspend fun drawPage(index: Int, canvas: Canvas): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock false
            // Hairlines as thin as at 200 dpi.
            drawSlice(canvas, pages[index], 200f / 72f)
            true
        }
    }

    /** Pages are in points already. */
    override fun paperWidth(index: Int): Float = pageSizes[index].width

    /** The sheets, each at its first page. */
    override suspend fun tableOfContents(): List<TocEntry> =
        pages.withIndex().distinctBy { it.value.sheet.index }.map { (page, p) -> TocEntry(p.sheet.sheet.name, page, null) }

    private fun drawSlice(canvas: Canvas, page: Page, scale: Float) {
        canvas.save()
        canvas.translate(0f, -page.top)
        canvas.clipRect(0f, page.top, page.sheet.width, page.bottom)
        draw(canvas, page, scale)
        canvas.restore()
    }

    // ---- Drawing ----

    private val fill = Paint()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = 11f
        color = HEADER_TEXT
    }

    private fun draw(canvas: Canvas, page: Page, scale: Float) {
        val p = page.sheet
        val sheet = p.sheet
        if (page.top < HEADER) header(canvas, p)
        val firstRow = max(0, p.rowAt(page.top).let { if (it < 0) 0 else it })
        val lastRow = p.rowAt(page.bottom - 0.01f).let { if (it < 0) sheet.rowHeights.size - 1 else it }
        if (sheet.rowHeights.isEmpty() || sheet.columnWidths.isEmpty()) return drawings(canvas, page)
        val left = p.x(0)
        val right = p.x(sheet.columnWidths.size)

        // Gridlines, as Excel shows them on screen.
        if (sheet.showGrid) {
            line.color = GRID
            line.strokeWidth = 1f / scale
            line.pathEffect = null
            val top = p.y(firstRow)
            val bottom = p.y(lastRow + 1)
            for (c in 0..sheet.columnWidths.size) canvas.drawLine(p.x(c), top, p.x(c), bottom, line)
            for (r in firstRow..lastRow + 1) canvas.drawLine(left, p.y(r), right, p.y(r), line)
        }

        // Merged cells hide the gridlines inside them.
        for (m in sheet.merges) {
            if (m.lastRow < firstRow || m.firstRow > lastRow) continue
            val style = styleOf(sheet, m.firstRow, m.firstColumn)
            fill.color = style?.fill?.takeIf { it != 0 } ?: Color.WHITE
            canvas.drawRect(p.rect(m), fill)
        }
        for (r in firstRow..lastRow) {
            val row = sheet.rows[r] ?: continue
            for (i in row.columns.indices) {
                val color = book.styles[row.styles[i]].fill
                val c = row.columns[i]
                if (color == 0 || XlsxReader.key(r, c) in p.covered || XlsxReader.key(r, c) in p.mergeAt) continue
                fill.color = color
                canvas.drawRect(p.x(c), p.y(r), p.x(c + 1), p.y(r + 1), fill)
            }
        }
        // Text, including merged cells whose top-left is above this page.
        for (m in sheet.merges) {
            if (m.firstRow < firstRow && m.lastRow >= firstRow) placeText(p, m.firstRow, m.firstColumn)?.draw(canvas)
        }
        for (r in firstRow..lastRow) {
            val row = sheet.rows[r] ?: continue
            for (i in row.columns.indices) {
                if (row.values[i] == null) continue
                placeText(p, r, row.columns[i])?.draw(canvas)
            }
        }
        for (r in firstRow..lastRow) {
            val row = sheet.rows[r] ?: continue
            for (i in row.columns.indices) {
                val style = book.styles[row.styles[i]]
                if (!style.hasBorder) continue
                val c = row.columns[i]
                val area = p.mergeAt[XlsxReader.key(r, c)]?.let { p.rect(it) } ?: RectF(p.x(c), p.y(r), p.x(c + 1), p.y(r + 1))
                style.top?.let { border(canvas, it, area.left, area.top, area.right, area.top, scale) }
                style.bottom?.let { border(canvas, it, area.left, area.bottom, area.right, area.bottom, scale) }
                style.left?.let { border(canvas, it, area.left, area.top, area.left, area.bottom, scale) }
                style.right?.let { border(canvas, it, area.right, area.top, area.right, area.bottom, scale) }
            }
        }
        drawings(canvas, page)
    }

    /** The band above a sheet: its tab colour and name. */
    private fun header(canvas: Canvas, p: Placed) {
        fill.color = HEADER_FILL
        canvas.drawRect(0f, 0f, p.width, HEADER - 4f, fill)
        p.sheet.tabColor?.let {
            fill.color = it
            canvas.drawRect(0f, 0f, 4f, HEADER - 4f, fill)
        }
        nameLayout(p).let {
            canvas.save()
            canvas.translate(MARGIN + 4f, (HEADER - 4f - it.height) / 2)
            it.draw(canvas)
            canvas.restore()
        }
    }

    /** The sheet's name as drawn in its band (also found by search and selection). */
    private fun nameLayout(p: Placed): StaticLayout =
        StaticLayout.Builder.obtain(p.sheet.name, 0, p.sheet.name.length, headerPaint, (p.width - 2 * MARGIN).roundToInt().coerceAtLeast(1))
            .setIncludePad(false).setMaxLines(1).build()

    private fun border(canvas: Canvas, b: CellBorder, x0: Float, y0: Float, x1: Float, y1: Float, scale: Float) {
        line.color = b.color
        line.strokeWidth = when (b.style) {
            "medium", "mediumDashed", "mediumDashDot", "mediumDashDotDot", "slantDashDot" -> 1.5f
            "thick" -> 2.25f
            "hair" -> 0.5f
            else -> 0.75f
        }.coerceAtLeast(1f / scale)
        line.pathEffect = when (b.style) {
            "dashed", "mediumDashed" -> DashPathEffect(floatArrayOf(3f, 1.5f), 0f)
            "dotted", "hair" -> DashPathEffect(floatArrayOf(1f, 1f), 0f)
            "dashDot", "mediumDashDot", "slantDashDot" -> DashPathEffect(floatArrayOf(3f, 1f, 1f, 1f), 0f)
            "dashDotDot", "mediumDashDotDot" -> DashPathEffect(floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f), 0f)
            else -> null
        }
        if (b.style == "double") {
            line.strokeWidth = max(0.5f, 1f / scale)
            val dx = if (y0 == y1) 0f else 0.75f
            val dy = if (y0 == y1) 0.75f else 0f
            canvas.drawLine(x0 - dx, y0 - dy, x1 - dx, y1 - dy, line)
            canvas.drawLine(x0 + dx, y0 + dy, x1 + dx, y1 + dy, line)
        } else {
            canvas.drawLine(x0, y0, x1, y1, line)
        }
        line.pathEffect = null
    }

    private val pictures = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun drawings(canvas: Canvas, page: Page) {
        val p = page.sheet
        for ((i, d) in p.sheet.drawings.withIndex()) {
            val r = p.drawingRect(d)
            if (r.bottom < page.top || r.top > page.bottom || r.width() <= 0f || r.height() <= 0f) continue
            val bitmap = d.bytes?.let { bytes -> picture("${p.index}/$i", bytes, r) }
            if (bitmap != null) canvas.drawBitmap(bitmap, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            else EmptyBoxSpan.drawBox(canvas, r)
        }
    }


    /** A picture decoded once, no larger than needed for a 3× zoom (and never more than 2048 pixels a side). */
    private fun picture(key: String, bytes: ByteArray, r: RectF): Bitmap? {
        pictures.get(key)?.let { return it }
        return try {
            val size = Pictures.size(bytes) ?: return null
            val want = min(2048f, r.width() * 4)
            var sample = 1
            while (size.width / (sample * 2) >= want || size.height / sample > 2048) sample *= 2
            Pictures.decode(bytes, size, sample)?.also { pictures.put(key, it) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    // ---- Cell text ----

    private fun styleOf(sheet: SheetModel, r: Int, c: Int): CellStyle? {
        val row = sheet.rows.getOrNull(r) ?: return null
        val i = row.indexOf(c)
        return if (i < 0) null else book.styles[row.styles[i]]
    }

    /** What a cell shows: its text as formatted, its colour, and whether it is a number (numbers don't spill over). */
    private class Shown(val text: CharSequence, val color: Int, val numeric: Boolean, val boolOrError: Boolean)

    private fun shown(value: Any?, style: CellStyle): Shown? = when (value) {
        null -> null
        is Double -> NumberFormat.format(value, style.format, book.date1904).let { Shown(it.text, it.color ?: style.font.color, true, false) }
        is Boolean -> Shown(if (value) "TRUE" else "FALSE", style.font.color, false, true)
        is CellError -> Shown(value.text, style.font.color, false, true)
        is RichText -> Shown(rich(value), style.font.color, false, false)
        else -> NumberFormat.formatText(value.toString(), style.format).let { Shown(it.text, it.color ?: style.font.color, false, false) }
    }

    private fun rich(value: RichText): CharSequence {
        val sb = SpannableStringBuilder()
        for ((text, font) in value.runs) {
            val start = sb.length
            sb.append(text)
            val f = font ?: continue
            sb.setSpan(FontSpan(f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(f.color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.underline) sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (f.strike) sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    private class FontSpan(val font: CellFont) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) = apply(paint)
        override fun updateDrawState(paint: TextPaint) = apply(paint)
        private fun apply(paint: TextPaint) = configure(paint, font)
    }

    /** Laid-out text of a cell placed on its sheet: the layout's top-left at ([x], [y]), clipped to [clip]. */
    private class CellText(val layout: StaticLayout, val x: Float, val y: Float, val clip: RectF) {
        fun draw(canvas: Canvas) {
            canvas.save()
            canvas.clipRect(clip)
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /**
     * Places the text of cell ([r], [c]) as Excel does: aligned in the cell (numbers right, text
     * left), at the bottom unless the style says otherwise; text that doesn't wrap spills into
     * empty cells beside it. Null if the cell shows nothing or is covered by a merge.
     */
    private fun placeText(p: Placed, r: Int, c: Int): CellText? {
        val sheet = p.sheet
        val row = sheet.rows.getOrNull(r) ?: return null
        val i = row.indexOf(c)
        if (i < 0) return null
        val key = XlsxReader.key(r, c)
        if (key in p.covered) return null
        val style = book.styles[row.styles[i]]
        var shown = shown(row.values[i], style) ?: return null
        if (shown.text.isEmpty()) return null
        val merge = p.mergeAt[key]
        val cell = merge?.let { p.rect(it) } ?: RectF(p.x(c), p.y(r), p.x(c + 1), p.y(r + 1))
        if (cell.width() <= 0f || cell.height() <= 0f) return null

        val paint = TextPaint(textPaint).also { configure(it, style.font); it.color = shown.color }
        val pad = PADDING + style.indent * INDENT
        val align = when (style.hAlign) {
            HAlign.General -> when {
                shown.numeric -> Layout.Alignment.ALIGN_OPPOSITE
                shown.boolOrError -> Layout.Alignment.ALIGN_CENTER
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            HAlign.Right -> Layout.Alignment.ALIGN_OPPOSITE
            HAlign.Center, HAlign.CenterContinuous -> Layout.Alignment.ALIGN_CENTER
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val inner = (cell.width() - 2 * pad).coerceAtLeast(1f)
        val multiline = style.wrap || shown.text.contains('\n')
        var natural = if (multiline) inner else Layout.getDesiredWidth(shown.text, paint)
        // A formatted number that doesn't fit shows as ### (Excel never shows part of a number).
        if (shown.numeric && !multiline && !style.shrink && natural > inner && !style.format.equals("General", true)) {
            val hash = paint.measureText("#").coerceAtLeast(0.1f)
            shown = Shown("#".repeat((inner / hash).toInt().coerceIn(1, 255)), shown.color, true, false)
            natural = Layout.getDesiredWidth(shown.text, paint)
        }
        if (style.shrink && !multiline && natural > inner) paint.textSize *= inner / natural

        var area = RectF(cell)
        val width: Float
        if (multiline) {
            width = inner
        } else {
            width = ceil(min(natural, if (style.shrink) inner else natural)).coerceAtLeast(1f)
            // Text wider than its cell spills into empty neighbours (never numbers, wrapped or merged cells).
            if (width > inner && !shown.numeric && merge == null) area = spill(p, r, c, row, align, width + 2 * pad, cell)
        }
        val layout = StaticLayout.Builder.obtain(shown.text, 0, shown.text.length, paint, width.roundToInt().coerceAtLeast(1))
            .setAlignment(if (multiline) align else Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
        val x = when {
            multiline -> area.left + pad
            align == Layout.Alignment.ALIGN_OPPOSITE -> area.right - pad - width
            align == Layout.Alignment.ALIGN_CENTER -> cell.centerX() - width / 2
            else -> area.left + pad
        }
        val h = layout.height.toFloat()
        val y = when (style.vAlign) {
            VerticalAlign.Top -> cell.top + 1f
            VerticalAlign.Center -> cell.centerY() - h / 2
            VerticalAlign.Bottom -> cell.bottom - h - 1f
        }
        return CellText(layout, x, y, area)
    }

    /** The area text [needed] wide may use: the cell and the empty cells on the side(s) it spills to. */
    private fun spill(p: Placed, r: Int, c: Int, row: RowCells, align: Layout.Alignment, needed: Float, cell: RectF): RectF {
        val sheet = p.sheet
        val lastColumn = sheet.columnWidths.size - 1
        fun empty(col: Int): Boolean {
            if (XlsxReader.key(r, col) in p.covered || XlsxReader.key(r, col) in p.mergeAt) return false
            val j = row.indexOf(col)
            return j < 0 || row.values[j] == null
        }
        var left = cell.left
        var right = cell.right
        val toRight = align != Layout.Alignment.ALIGN_OPPOSITE
        val toLeft = align != Layout.Alignment.ALIGN_NORMAL
        var cr = c
        var cl = c
        while (right - left < needed) {
            var grew = false
            if (toRight && cr < lastColumn && empty(cr + 1)) { cr++; right = p.x(cr + 1); grew = true }
            if (toLeft && cl > 0 && empty(cl - 1)) { cl--; left = p.x(cl); grew = true }
            if (!grew) break
        }
        // Past the last used column, text runs on to the page edge.
        if (toRight && cr == lastColumn && right - left < needed) right = p.width
        return RectF(left, cell.top, right, cell.bottom)
    }

    // ---- Search, selection, links ----

    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.Default) {
        val q = TextSearch.normalize(query)
        mutex.withLock {
            val result = mutableListOf<SearchMatch>()
            if (closed || q.isEmpty()) return@withLock result
            for (p in placed) {
                val sheet = p.sheet
                if (TextSearch.contains(sheet.name, q)) {
                    val name = nameLayout(p)
                    val y = (HEADER - 4f - name.height) / 2
                    val page = pageIndexOf.getValue(p)
                    for (found in TextSearch.find(name, q)) result += SearchMatch(page, found.offset(MARGIN + 4f, y).map { toPage(it, pages[page]) })
                }
                for (r in sheet.rows.indices) {
                    val row = sheet.rows[r] ?: continue
                    ensureActive()
                    for (i in row.columns.indices) {
                        val value = row.values[i] ?: continue
                        val shown = shown(value, book.styles[row.styles[i]]) ?: continue
                        if (!TextSearch.contains(shown.text, q)) continue
                        val text = placeText(p, r, row.columns[i]) ?: continue
                        for (found in TextSearch.find(text.layout, q)) {
                            val rects = found.offset(text.x, text.y)
                            val page = pageAt(p, rects.first().centerY())
                            result += SearchMatch(page, rects.map { toPage(it, pages[page]) })
                            if (result.size >= TextSearch.MAX_MATCHES) return@withLock result
                        }
                    }
                }
            }
            result
        }
    }

    private val pageIndexOf: Map<Placed, Int> = placed.associateWith { s -> pages.indexOfFirst { it.sheet === s } }

    private fun pageAt(p: Placed, y: Float): Int {
        var i = pageIndexOf.getValue(p)
        while (i + 1 < pages.size && pages[i + 1].sheet === p && pages[i + 1].top <= y) i++
        return i
    }

    private fun toPage(r: RectF, page: Page): RectF {
        val w = page.sheet.width
        val h = page.bottom - page.top
        return RectF(r.left / w, (r.top - page.top) / h, r.right / w, (r.bottom - page.top) / h)
    }

    private val pageTexts = LruCache<Int, PageText>(4)

    /** A page's text, cell by cell in reading order. */
    private fun pageText(index: Int): PageText = pageTexts[index] ?: run {
        val page = pages[index]
        val p = page.sheet
        val builder = PageText.Builder { toPage(it, page) }
        if (page.top < HEADER) {
            val name = nameLayout(p)
            collectLayoutText(name, builder, MARGIN + 4f, (HEADER - 4f - name.height) / 2)
        }
        val firstRow = max(0, p.rowAt(page.top))
        val lastRow = p.rowAt(page.bottom - 0.01f).let { if (it < 0) p.sheet.rowHeights.size - 1 else it }
        for (r in firstRow..lastRow) {
            val row = p.sheet.rows.getOrNull(r) ?: continue
            for (i in row.columns.indices) {
                if (row.values[i] == null) continue
                val text = placeText(p, r, row.columns[i]) ?: continue
                collectLayoutText(text.layout, builder.offset(text.x, text.y), 0f, 0f)
            }
        }
        builder.build().also { pageTexts.put(index, it) }
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).select(from, to) }
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).word(at) }
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val page = pages[index]
        val p = page.sheet
        val sx = x * p.width
        val sy = page.top + y * (page.bottom - page.top)
        var r = p.rowAt(sy)
        var c = p.columnAt(sx)
        if (r < 0 || c < 0) return null
        // A merged cell's link is its top-left cell's.
        p.sheet.merges.firstOrNull { r in it.firstRow..it.lastRow && c in it.firstColumn..it.lastColumn }?.let {
            r = it.firstRow
            c = it.firstColumn
        }
        return p.sheet.links[XlsxReader.key(r, c)]?.takeIf { isWebLink(it) }
    }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            pictures.evictAll()
        }
    }

    companion object {
        /** Narrow sheets still get a page this wide, so their text isn't blown up on a phone. */
        private const val MIN_WIDTH = 360f
        /** Pages short enough that a zoomed render (capped in pixels) stays sharp on wide sheets. */
        private const val MIN_PAGE_HEIGHT = 480f
        private const val MAX_PAGE_HEIGHT = 900f
        /** Cells hidden under merges, per sheet (Excel files with real merges cover far fewer). */
        private const val MAX_COVERED_CELLS = 1_000_000L
        private const val MARGIN = 6f
        private const val HEADER = 26f
        private const val PADDING = 2f
        private const val INDENT = 9f
        private const val GRID = 0xFFDADADA.toInt()
        private const val HEADER_FILL = 0xFFF1F3F4.toInt()
        private const val HEADER_TEXT = 0xFF3C4043.toInt()

        fun of(book: Workbook): WorkbookDocument = WorkbookDocument(book)

        private fun configure(paint: TextPaint, font: CellFont) {
            val family = when (font.kind) {
                FontKind.Serif -> Typeface.SERIF
                FontKind.Mono -> Typeface.MONOSPACE
                FontKind.Sans -> Typeface.SANS_SERIF
            }
            paint.typeface = Typeface.create(family, (if (font.bold) Typeface.BOLD else 0) or (if (font.italic) Typeface.ITALIC else 0))
            paint.textSize = font.size
            paint.textScaleX = font.scaleX
            paint.isUnderlineText = font.underline
            paint.isStrikeThruText = font.strike
            paint.color = font.color
        }
    }
}
