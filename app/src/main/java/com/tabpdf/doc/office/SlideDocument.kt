package com.tabpdf.doc.office

import com.tabpdf.doc.Pictures
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import com.tabpdf.doc.Document
import com.tabpdf.doc.FLOW_WIDTH
import com.tabpdf.doc.FlowBlock
import com.tabpdf.doc.Found
import com.tabpdf.doc.PageText
import com.tabpdf.doc.TextSelection
import com.tabpdf.doc.TextSink
import com.tabpdf.doc.PageSize
import com.tabpdf.doc.SearchMatch
import com.tabpdf.doc.TextSearch
import com.tabpdf.doc.TocEntry
import com.tabpdf.doc.offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A slide deck as pages (기획서 5.7): one slide per page, FLOW_WIDTH units wide, shapes drawn
 * where they sit on the slide. Text is laid out once when the deck opens; pictures are decoded when
 * a page is drawn, at the size it needs.
 */
class SlideDocument private constructor(
    private val height: Float,
    private val pages: List<SlidePage>,
    /** The slide's width on paper, in points. */
    private val paperWidth: Float,
) : Document {
    private val mutex = Mutex()
    private var closed = false

    /** Decoded pictures, by their bytes and sample size; shared by all pages. */
    private val pictures = object : LruCache<Pair<ByteArray, Int>, Bitmap>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: Pair<ByteArray, Int>, value: Bitmap) = value.allocationByteCount
    }

    override val pageSizes: List<PageSize> = List(pages.size) { PageSize(FLOW_WIDTH, height) }

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock null
            val scale = widthPx / FLOW_WIDTH
            val heightPx = (height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for slide $index at ${widthPx}x$heightPx", e)
                return@withLock null
            }
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            draw(index, canvas, scale)
            bitmap
        }
    }

    override suspend fun drawPage(index: Int, canvas: Canvas): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withLock false
            // Pictures at print resolution (200 dpi).
            draw(index, canvas, paperWidth / FLOW_WIDTH * 200f / 72f)
            true
        }
    }

    override fun paperWidth(index: Int): Float = paperWidth

    /** Every slide, by its title where it has one. */
    override suspend fun tableOfContents(): List<TocEntry> =
        pages.mapIndexed { i, p -> TocEntry("${i + 1}. ${p.title ?: "슬라이드 ${i + 1}"}", i, null) }

    /** [scale] is pixels per unit, for decoding pictures at the size they show. */
    private fun draw(index: Int, canvas: Canvas, scale: Float) {
        val page = pages[index]
        val context = DrawContext(scale, ::picture)
        page.background?.let { drawFill(canvas, it, RectF(0f, 0f, FLOW_WIDTH, height), null, context) }
        for (item in page.items) item.draw(canvas, context)
    }

    /** [bytes] decoded at least [widthPx] wide (or as large as it is), or null if it can't be. */
    private fun picture(bytes: ByteArray, widthPx: Float): Bitmap? {
        val size = Pictures.size(bytes) ?: return null
        var sample = 1
        while (size.width / (sample * 2) >= widthPx && sample < 64) sample *= 2
        // A small file can claim a huge (or very tall) picture: never more than 16 million pixels.
        while (size.width.toLong() * size.height / (sample.toLong() * sample) > MAX_PICTURE_PIXELS) sample *= 2
        pictures[bytes to sample]?.let { return it }
        return try {
            Pictures.decode(bytes, size, sample)?.also { pictures.put(bytes to sample, it) }
        } catch (e: OutOfMemoryError) {
            android.util.Log.w("EveryViewer", "No memory for a picture", e)
            null
        }
    }

    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.Default) {
        val q = TextSearch.normalize(query)
        mutex.withLock {
            val result = mutableListOf<SearchMatch>()
            if (closed || q.isEmpty()) return@withLock result
            for ((index, page) in pages.withIndex()) {
                ensureActive()
                for (item in page.items) for (found in item.find(q)) {
                    result += SearchMatch(index, found.map { RectF(it.left / FLOW_WIDTH, it.top / height, it.right / FLOW_WIDTH, it.bottom / height) })
                    if (result.size >= TextSearch.MAX_MATCHES) return@withLock result
                }
            }
            result
        }
    }

    private val pageTexts = LruCache<Int, PageText>(6)

    /** A slide's text, shape by shape in drawing order (as PowerPoint reads them out). */
    private fun pageText(index: Int): PageText = pageTexts[index] ?: run {
        val builder = PageText.Builder { RectF(it.left / FLOW_WIDTH, it.top / height, it.right / FLOW_WIDTH, it.bottom / height) }
        for (item in pages[index].items) item.collectText(builder)
        builder.build().also { pageTexts.put(index, it) }
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).select(from, to) }
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed) null else pageText(index).word(at) }
    }

    override fun linkAt(index: Int, x: Float, y: Float): String? {
        val px = x * FLOW_WIDTH
        val py = y * height
        return pages[index].items.asReversed().firstNotNullOfOrNull { it.linkAt(px, py) }
    }

    override suspend fun close() = withContext(NonCancellable) {
        mutex.withLock {
            closed = true
            pictures.evictAll()
        }
    }

    companion object {
        private const val PICTURE_CACHE_BYTES = 32 * 1024 * 1024
        private const val MAX_PICTURE_PIXELS = 16_000_000L

        fun layOut(presentation: Presentation): SlideDocument {
            checkPaper(presentation.width, presentation.height)
            val unit = FLOW_WIDTH / presentation.width
            val pages = presentation.slides.map { slide ->
                SlidePage(slide.background, slide.shapes.mapNotNull { item(it, unit) }, slide.title)
            }
            return SlideDocument(presentation.height * unit, pages, presentation.width)
        }

        private fun item(shape: SlideShape, unit: Float): SlideItem? {
            val f = shape.frame.let { RectF(it.x * unit, it.y * unit, (it.x + it.width) * unit, (it.y + it.height) * unit) }
            return when (shape) {
                is ShapeOnSlide -> ShapeItem(shape, f, unit)
                is PictureOnSlide -> PictureItem(shape, f, unit)
                is TableOnSlide -> {
                    val width = max(shape.table.columns.sum(), shape.frame.width)
                    BlockItem(OfficeLayout.box(listOf(shape.table), width, unit).first, f.left, f.top)
                }
                is UnsupportedOnSlide -> if (f.width() > 0f && f.height() > 0f) UnsupportedItem(f) else null
            }
        }
    }
}

private class SlidePage(val background: Fill?, val items: List<SlideItem>, val title: String?)

private class DrawContext(
    /** Pixels per flow unit. */
    val scale: Float,
    val picture: (ByteArray, Float) -> Bitmap?,
)

private interface SlideItem {
    fun draw(canvas: Canvas, context: DrawContext)
    fun linkAt(x: Float, y: Float): String? = null
    /** Where the item's text has [query], in slide coordinates. */
    fun find(query: String): List<Found> = emptyList()
    /** The item's characters with boxes in slide coordinates. */
    fun collectText(sink: TextSink) = Unit
}

private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

/** Fills [bounds] (clipped to [path] if given). */
private fun drawFill(canvas: Canvas, fill: Fill, bounds: RectF, path: Path?, context: DrawContext) {
    when (fill) {
        is Fill.Solid -> {
            if (Color.alpha(fill.color) == 0) return
            fillPaint.shader = null
            fillPaint.color = fill.color
            if (path != null) canvas.drawPath(path, fillPaint) else canvas.drawRect(bounds, fillPaint)
        }
        is Fill.Gradient -> {
            val angle = Math.toRadians(fill.angle.toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            // The gradient line spans the box's projection onto its direction.
            val half = (abs(dx) * bounds.width() + abs(dy) * bounds.height()) / 2
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            fillPaint.shader = LinearGradient(
                cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                fill.stops.map { it.second }.toIntArray(), fill.stops.map { it.first }.toFloatArray(), Shader.TileMode.CLAMP,
            )
            fillPaint.color = Color.BLACK
            if (path != null) canvas.drawPath(path, fillPaint) else canvas.drawRect(bounds, fillPaint)
            fillPaint.shader = null
        }
        is Fill.Picture -> {
            val bitmap = context.picture(fill.bytes, bounds.width() * context.scale) ?: return
            canvas.save()
            if (path != null) canvas.clipPath(path)
            canvas.drawBitmap(bitmap, null, bounds, bitmapPaint)
            canvas.restore()
        }
    }
}

private fun setUpStroke(stroke: Stroke, unit: Float) {
    strokePaint.color = stroke.color
    // Hairlines would vanish at normal zoom; keep every line visible.
    strokePaint.strokeWidth = max(stroke.width * unit, 0.9f)
    strokePaint.pathEffect = stroke.dash?.let { dash -> DashPathEffect(FloatArray(dash.size) { dash[it] * strokePaint.strokeWidth }, 0f) }
    strokePaint.strokeCap = Paint.Cap.BUTT
    strokePaint.strokeJoin = Paint.Join.ROUND
}

/** Rotates (and moves) the canvas into a frame's own coordinates. */
private inline fun Canvas.inFrame(f: RectF, rotation: Float, block: Canvas.() -> Unit) {
    save()
    if (rotation != 0f) rotate(rotation, f.centerX(), f.centerY())
    translate(f.left, f.top)
    block()
    restore()
}

/** A shape: outline, fill, line with arrowheads, and its text. */
private class ShapeItem(private val shape: ShapeOnSlide, private val f: RectF, private val unit: Float) : SlideItem {
    private val outline = Outline.of(shape.geometry, f.width(), f.height(), shape.frame.flipH, shape.frame.flipV)
    private val text: FlowBlock?
    private val textX: Float
    private val textY: Float

    init {
        val frame = shape.text
        if (frame == null) {
            text = null
            textX = 0f
            textY = 0f
        } else {
            // In the text's own direction: lines run "along", stack up "across". Vertical text
            // runs down (or up) the shape, and the shape's insets turn with it.
            val i = frame.insets
            val (alongStart, alongEnd, acrossStart, acrossEnd) = when (frame.direction) {
                TextDirection.Horizontal -> listOf(i.left, i.right, i.top, i.bottom)
                TextDirection.Down -> listOf(i.top, i.bottom, i.right, i.left)
                TextDirection.Up -> listOf(i.bottom, i.top, i.left, i.right)
            }
            val vertical = frame.direction != TextDirection.Horizontal
            val boxW = (if (vertical) f.height() else f.width()) - (alongStart + alongEnd) * unit
            val boxH = (if (vertical) f.width() else f.height()) - (acrossStart + acrossEnd) * unit
            val (block, width) = OfficeLayout.box(frame.paragraphs, boxW.coerceAtLeast(unit) / unit, unit, frame.wrap)
            text = block
            val align = frame.paragraphs.firstOrNull()?.align
            textX = alongStart * unit + when {
                width <= boxW -> 0f
                align == Align.Center -> (boxW - width) / 2
                align == Align.Right -> boxW - width
                else -> 0f
            }
            textY = acrossStart * unit + when (frame.anchor) {
                VerticalAlign.Top -> 0f
                VerticalAlign.Center -> (boxH - block.height) / 2
                VerticalAlign.Bottom -> boxH - block.height
            }
        }
    }

    override fun draw(canvas: Canvas, context: DrawContext) = canvas.inFrame(f, shape.frame.rotation) {
        val bounds = RectF(0f, 0f, f.width(), f.height())
        shape.fill?.let { fill -> outline.filled.forEach { drawFill(this, fill, bounds, it, context) } }
        shape.stroke?.let { stroke ->
            setUpStroke(stroke, unit)
            for (path in outline.stroked) drawPath(path, strokePaint)
            outline.ends?.let { (start, end) -> drawArrows(this, stroke, start, end) }
        }
        text?.let { block ->
            save()
            when (shape.text?.direction) {
                TextDirection.Down -> { translate(f.width(), 0f); rotate(90f) }
                TextDirection.Up -> { translate(0f, f.height()); rotate(-90f) }
                else -> Unit
            }
            translate(textX, textY)
            block.draw(this)
            restore()
        }
    }

    private fun drawArrows(canvas: Canvas, stroke: Stroke, start: Pair<PointF, PointF>, end: Pair<PointF, PointF>) {
        val w = strokePaint.strokeWidth
        stroke.head?.let { arrow(canvas, it, start.first, start.second, w) }
        stroke.tail?.let { arrow(canvas, it, end.first, end.second, w) }
    }

    /** An arrowhead with its tip at [tip], pointing away from [from]. */
    private fun arrow(canvas: Canvas, end: ArrowEnd, tip: PointF, from: PointF, lineWidth: Float) {
        val len = hypot(tip.x - from.x, tip.y - from.y)
        if (len == 0f) return
        val ux = (tip.x - from.x) / len
        val uy = (tip.y - from.y) / len
        val length = end.length * lineWidth
        val half = end.width * lineWidth / 2
        val bx = tip.x - ux * length
        val by = tip.y - uy * length
        val path = Path()
        fillPaint.shader = null
        fillPaint.color = strokePaint.color
        when (end.kind) {
            ArrowKind.Triangle, ArrowKind.Stealth -> {
                path.moveTo(tip.x, tip.y)
                path.lineTo(bx - uy * half, by + ux * half)
                if (end.kind == ArrowKind.Stealth) path.lineTo(tip.x - ux * length * 0.6f, tip.y - uy * length * 0.6f)
                path.lineTo(bx + uy * half, by - ux * half)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            ArrowKind.Arrow -> {
                path.moveTo(bx - uy * half, by + ux * half)
                path.lineTo(tip.x, tip.y)
                path.lineTo(bx + uy * half, by - ux * half)
                val p = Paint(strokePaint).apply { pathEffect = null; strokeCap = Paint.Cap.ROUND }
                canvas.drawPath(path, p)
            }
            ArrowKind.Oval -> canvas.drawOval(RectF(tip.x - half, tip.y - length / 2, tip.x + half, tip.y + length / 2), fillPaint)
            ArrowKind.Diamond -> {
                val mx = tip.x - ux * length / 2
                val my = tip.y - uy * length / 2
                path.moveTo(tip.x + ux * length / 2, tip.y + uy * length / 2)
                path.lineTo(mx - uy * half, my + ux * half)
                path.lineTo(tip.x - ux * length / 2, tip.y - uy * length / 2)
                path.lineTo(mx + uy * half, my - ux * half)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
        }
    }

    override fun find(query: String): List<Found> {
        val block = text ?: return emptyList()
        val m = toSlide()
        return block.find(query).map { found -> found.map { RectF(it).also(m::mapRect) } }
    }

    override fun collectText(sink: TextSink) {
        val block = text ?: return
        val m = toSlide()
        block.collectText(object : TextSink {
            override fun char(c: Char, box: RectF) = sink.char(c, RectF(box).also(m::mapRect))
            override fun endBlock() = sink.endBlock()
        })
    }

    /** The same moves as drawing: into the text box, turned for vertical text, then the frame's rotation. */
    private fun toSlide(): Matrix {
        return Matrix().apply {
            setTranslate(textX, textY)
            when (shape.text?.direction) {
                TextDirection.Down -> { postRotate(90f); postTranslate(f.width(), 0f) }
                TextDirection.Up -> { postRotate(-90f); postTranslate(0f, f.height()) }
                else -> Unit
            }
            postTranslate(f.left, f.top)
            if (shape.frame.rotation != 0f) postRotate(shape.frame.rotation, f.centerX(), f.centerY())
        }
    }

    override fun linkAt(x: Float, y: Float): String? {
        val block = text ?: return null
        if (shape.frame.rotation != 0f || shape.text?.direction != TextDirection.Horizontal || !f.contains(x, y)) return null
        return block.linkAt(x - f.left - textX, y - f.top - textY)
    }
}

private class PictureItem(private val picture: PictureOnSlide, private val f: RectF, private val unit: Float) : SlideItem {
    override fun draw(canvas: Canvas, context: DrawContext) = canvas.inFrame(f, picture.frame.rotation) {
        val dst = RectF(0f, 0f, f.width(), f.height())
        val (l, t, r, b) = picture.crop.toList()
        val shown = (1f - l - r).coerceAtLeast(0.01f)
        val bitmap = picture.bytes?.let { context.picture(it, f.width() * context.scale / shown) }
        val paint = lookPaint(picture.look)
        if (bitmap == null) {
            EmptyBoxSpan.drawBox(this, dst)
        } else {
            val src = Rect(
                (bitmap.width * l.coerceIn(0f, 1f)).roundToInt(),
                (bitmap.height * t.coerceIn(0f, 1f)).roundToInt(),
                (bitmap.width * (1f - r.coerceIn(0f, 1f))).roundToInt(),
                (bitmap.height * (1f - b.coerceIn(0f, 1f))).roundToInt(),
            )
            if (picture.frame.flipH || picture.frame.flipV) {
                scale(if (picture.frame.flipH) -1f else 1f, if (picture.frame.flipV) -1f else 1f, dst.centerX(), dst.centerY())
            }
            if (!src.isEmpty) drawBitmap(bitmap, src, dst, paint)
        }
        picture.stroke?.let {
            setUpStroke(it, unit)
            drawRect(dst, strokePaint)
        }
    }
}

/** Paint that applies a picture's colour changes. */
private fun lookPaint(look: PictureLook): Paint {
    if (look == PictureLook()) return bitmapPaint
    val m = ColorMatrix()
    if (look.grayscale) m.setSaturation(0f)
    // Contrast scales around middle grey and brightness shifts, as Office's picture corrections do.
    val c = if (look.contrast >= 0f) 1f / (1f - look.contrast.coerceAtMost(0.99f)) else 1f + look.contrast
    val t = (0.5f * (1f - c) + look.brightness) * 255f
    m.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, look.alpha, 0f)))
    return Paint(bitmapPaint).apply { colorFilter = ColorMatrixColorFilter(m) }
}

private class BlockItem(private val block: FlowBlock, private val x: Float, private val y: Float) : SlideItem {
    override fun draw(canvas: Canvas, context: DrawContext) {
        canvas.save()
        canvas.translate(x, y)
        block.draw(canvas)
        canvas.restore()
    }

    override fun find(query: String): List<Found> = block.find(query).map { it.offset(x, y) }

    override fun collectText(sink: TextSink) = block.collectText(sink.offset(x, y))

    override fun linkAt(x: Float, y: Float): String? =
        if (x >= this.x && y >= this.y && y < this.y + block.height) block.linkAt(x - this.x, y - this.y) else null
}

private class UnsupportedItem(private val f: RectF) : SlideItem {
    override fun draw(canvas: Canvas, context: DrawContext) = EmptyBoxSpan.drawBox(canvas, f)
}

/**
 * A shape's paths in its own coordinates (0..width, 0..height): what is filled, what is stroked,
 * and for lines the two ends with the point each end comes from (for arrowheads).
 */
private class Outline(
    val filled: List<Path>,
    val stroked: List<Path>,
    val ends: Pair<Pair<PointF, PointF>, Pair<PointF, PointF>>?,
) {
    companion object {
        fun of(geometry: Geometry, w: Float, h: Float, flipH: Boolean, flipV: Boolean): Outline {
            val outline = when (geometry) {
                is Geometry.Preset -> preset(geometry, w, h)
                is Geometry.Custom -> custom(geometry, w, h)
            }
            if (!flipH && !flipV) return outline
            val m = Matrix().apply { setScale(if (flipH) -1f else 1f, if (flipV) -1f else 1f, w / 2, h / 2) }
            fun Path.flipped() = Path(this).apply { transform(m) }
            fun PointF.flipped() = floatArrayOf(x, y).also(m::mapPoints).let { PointF(it[0], it[1]) }
            return Outline(
                outline.filled.map { it.flipped() },
                outline.stroked.map { it.flipped() },
                outline.ends?.let { (a, b) -> (a.first.flipped() to a.second.flipped()) to (b.first.flipped() to b.second.flipped()) },
            )
        }

        private fun closed(path: Path) = Outline(listOf(path), listOf(path), null)

        /** An open line through [points]; arrowheads go at its ends. */
        private fun polyline(vararg points: PointF): Outline {
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (p in points.drop(1)) lineTo(p.x, p.y)
            }
            return Outline(emptyList(), listOf(path), (points[0] to points[1]) to (points.last() to points[points.size - 2]))
        }

        private fun polygon(vararg xy: Float) = closed(Path().apply {
            moveTo(xy[0], xy[1])
            var i = 2
            while (i < xy.size) {
                lineTo(xy[i], xy[i + 1])
                i += 2
            }
            close()
        })

        private fun preset(g: Geometry.Preset, w: Float, h: Float): Outline {
            val ss = min(w, h)
            fun adj(name: String, default: Float) = (g.adjust[name] ?: default) / 100000f
            return when (g.name) {
                "line", "straightConnector1" -> polyline(PointF(0f, 0f), PointF(w, h))
                "bentConnector2" -> polyline(PointF(0f, 0f), PointF(w, 0f), PointF(w, h))
                "bentConnector3" -> {
                    val x = w * adj("adj1", 50000f)
                    polyline(PointF(0f, 0f), PointF(x, 0f), PointF(x, h), PointF(w, h))
                }
                "bentConnector4" -> {
                    val x = w * adj("adj1", 50000f)
                    val y = h * adj("adj2", 50000f)
                    polyline(PointF(0f, 0f), PointF(x, 0f), PointF(x, y), PointF(w, y), PointF(w, h))
                }
                "curvedConnector2", "curvedConnector3", "curvedConnector4" -> {
                    val path = Path().apply {
                        moveTo(0f, 0f)
                        cubicTo(w / 2, 0f, w / 2, h, w, h)
                    }
                    Outline(emptyList(), listOf(path), (PointF(0f, 0f) to PointF(w / 2, 0f)) to (PointF(w, h) to PointF(w / 2, h)))
                }
                "ellipse", "flowChartConnector" -> closed(Path().apply { addOval(RectF(0f, 0f, w, h), Path.Direction.CW) })
                "roundRect", "flowChartAlternateProcess" -> {
                    val r = ss * adj("adj", 16667f)
                    closed(Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, Path.Direction.CW) })
                }
                "flowChartTerminator" -> closed(Path().apply { addRoundRect(RectF(0f, 0f, w, h), min(h / 2, w / 2), h / 2, Path.Direction.CW) })
                "triangle", "flowChartExtract" -> polygon(w * adj("adj", 50000f), 0f, w, h, 0f, h)
                "flowChartMerge" -> polygon(0f, 0f, w, 0f, w / 2, h)
                "rtTriangle" -> polygon(0f, 0f, w, h, 0f, h)
                "diamond", "flowChartDecision" -> polygon(w / 2, 0f, w, h / 2, w / 2, h, 0f, h / 2)
                "parallelogram", "flowChartInputOutput" -> {
                    val x = ss * adj("adj", 25000f)
                    polygon(x, 0f, w, 0f, w - x, h, 0f, h)
                }
                "trapezoid" -> {
                    val x = ss * adj("adj", 25000f)
                    polygon(x, 0f, w - x, 0f, w, h, 0f, h)
                }
                "flowChartManualOperation" -> polygon(0f, 0f, w, 0f, w * 0.8f, h, w * 0.2f, h)
                "hexagon" -> {
                    val x = ss * adj("adj", 25000f)
                    polygon(x, 0f, w - x, 0f, w, h / 2, w - x, h, x, h, 0f, h / 2)
                }
                "octagon" -> {
                    val x = ss * adj("adj", 29289f)
                    polygon(x, 0f, w - x, 0f, w, x, w, h - x, w - x, h, x, h, 0f, h - x, 0f, x)
                }
                "pentagon", "homePlate" -> {
                    val x = w - ss * adj("adj", 50000f)
                    polygon(0f, 0f, x, 0f, w, h / 2, x, h, 0f, h)
                }
                "chevron" -> {
                    val d = ss * adj("adj", 50000f)
                    polygon(0f, 0f, w - d, 0f, w, h / 2, w - d, h, 0f, h, d, h / 2)
                }
                "rightArrow", "leftArrow" -> {
                    val shaft = h * adj("adj1", 50000f) / 2
                    val head = min(ss * adj("adj2", 50000f), w)
                    val o = if (g.name == "rightArrow") {
                        polygon(0f, h / 2 - shaft, w - head, h / 2 - shaft, w - head, 0f, w, h / 2, w - head, h, w - head, h / 2 + shaft, 0f, h / 2 + shaft)
                    } else {
                        polygon(w, h / 2 - shaft, head, h / 2 - shaft, head, 0f, 0f, h / 2, head, h, head, h / 2 + shaft, w, h / 2 + shaft)
                    }
                    o
                }
                "upArrow", "downArrow" -> {
                    val shaft = w * adj("adj1", 50000f) / 2
                    val head = min(ss * adj("adj2", 50000f), h)
                    if (g.name == "downArrow") {
                        polygon(w / 2 - shaft, 0f, w / 2 + shaft, 0f, w / 2 + shaft, h - head, w, h - head, w / 2, h, 0f, h - head, w / 2 - shaft, h - head)
                    } else {
                        polygon(w / 2 - shaft, h, w / 2 + shaft, h, w / 2 + shaft, head, w, head, w / 2, 0f, 0f, head, w / 2 - shaft, head)
                    }
                }
                "leftRightArrow" -> {
                    val shaft = h * adj("adj1", 50000f) / 2
                    val head = min(ss * adj("adj2", 50000f), w / 2)
                    polygon(
                        0f, h / 2, head, 0f, head, h / 2 - shaft, w - head, h / 2 - shaft, w - head, 0f, w, h / 2,
                        w - head, h, w - head, h / 2 + shaft, head, h / 2 + shaft, head, h,
                    )
                }
                "upDownArrow" -> {
                    val shaft = w * adj("adj1", 50000f) / 2
                    val head = min(ss * adj("adj2", 50000f), h / 2)
                    polygon(
                        w / 2, 0f, w, head, w / 2 + shaft, head, w / 2 + shaft, h - head, w, h - head, w / 2, h,
                        0f, h - head, w / 2 - shaft, h - head, w / 2 - shaft, head, 0f, head,
                    )
                }
                "leftBrace", "rightBrace" -> {
                    val curl = min(ss * adj("adj1", 8333f), h / 4)
                    val mid = h * adj("adj2", 50000f)
                    val path = Path().apply {
                        // Drawn as a left brace "{"; mirrored for a right one.
                        moveTo(w, 0f)
                        quadTo(w / 2, 0f, w / 2, curl)
                        lineTo(w / 2, mid - curl)
                        quadTo(w / 2, mid, 0f, mid)
                        quadTo(w / 2, mid, w / 2, mid + curl)
                        lineTo(w / 2, h - curl)
                        quadTo(w / 2, h, w, h)
                        if (g.name == "rightBrace") transform(Matrix().apply { setScale(-1f, 1f, w / 2, h / 2) })
                    }
                    Outline(emptyList(), listOf(path), null)
                }
                "plus", "mathPlus" -> {
                    val d = ss * adj("adj", 25000f)
                    polygon(d, 0f, w - d, 0f, w - d, d, w, d, w, h - d, w - d, h - d, w - d, h, d, h, d, h - d, 0f, h - d, 0f, d, d, d)
                }
                // Text-only frames and anything not drawn here: a plain rectangle.
                else -> closed(Path().apply { addRect(RectF(0f, 0f, w, h), Path.Direction.CW) })
            }
        }

        private fun custom(g: Geometry.Custom, w: Float, h: Float): Outline {
            val filled = mutableListOf<Path>()
            val stroked = mutableListOf<Path>()
            var ends: Pair<Pair<PointF, PointF>, Pair<PointF, PointF>>? = null
            for (p in g.paths) {
                val sx = if (p.width > 0f) w / p.width else 1f
                val sy = if (p.height > 0f) h / p.height else 1f
                val path = Path()
                val points = mutableListOf<PointF>()
                var cx = 0f
                var cy = 0f
                var open = true
                for (c in p.commands) {
                    when (c) {
                        is PathCommand.MoveTo -> { cx = c.x * sx; cy = c.y * sy; path.moveTo(cx, cy); points += PointF(cx, cy) }
                        is PathCommand.LineTo -> { cx = c.x * sx; cy = c.y * sy; path.lineTo(cx, cy); points += PointF(cx, cy) }
                        is PathCommand.CubicTo -> {
                            path.cubicTo(c.x1 * sx, c.y1 * sy, c.x2 * sx, c.y2 * sy, c.x * sx, c.y * sy)
                            points += PointF(c.x1 * sx, c.y1 * sy); points += PointF(c.x2 * sx, c.y2 * sy)
                            cx = c.x * sx; cy = c.y * sy; points += PointF(cx, cy)
                        }
                        is PathCommand.QuadTo -> {
                            path.quadTo(c.x1 * sx, c.y1 * sy, c.x * sx, c.y * sy)
                            points += PointF(c.x1 * sx, c.y1 * sy)
                            cx = c.x * sx; cy = c.y * sy; points += PointF(cx, cy)
                        }
                        is PathCommand.ArcTo -> {
                            val rx = c.wR * sx
                            val ry = c.hR * sy
                            val start = Math.toRadians(c.start.toDouble())
                            // The arc's ellipse, placed so it passes through the current point at the start angle.
                            val ox = cx - rx * cos(start).toFloat()
                            val oy = cy - ry * sin(start).toFloat()
                            path.arcTo(RectF(ox - rx, oy - ry, ox + rx, oy + ry), c.start, c.sweep)
                            val end = Math.toRadians((c.start + c.sweep).toDouble())
                            cx = ox + rx * cos(end).toFloat()
                            cy = oy + ry * sin(end).toFloat()
                            points += PointF(cx, cy)
                        }
                        PathCommand.Close -> { path.close(); open = false }
                    }
                }
                if (p.filled) filled += path
                if (p.stroked) stroked += path
                if (open && points.size >= 2 && ends == null) ends = (points[0] to points[1]) to (points.last() to points[points.size - 2])
            }
            return Outline(filled, stroked, ends)
        }
    }
}
