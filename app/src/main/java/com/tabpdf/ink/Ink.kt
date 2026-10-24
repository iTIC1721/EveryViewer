package com.tabpdf.ink

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import com.tabpdf.data.InkStrokeEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/*
 * Handwriting on tabs (기획서 5.8). Strokes live in page space: every page is INK_PAGE_WIDTH wide
 * (and as tall as its aspect ratio makes it), whatever the format, zoom or screen. Pages never
 * reflow (기획서 5.7), so a stroke stays on the words it was written over.
 */

const val INK_PAGE_WIDTH = 1000f

enum class InkTool(val key: String) {
    Pen("pen"),
    Highlighter("highlighter"),
    Eraser("eraser");

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key } ?: Pen
    }
}

/** A tool's colour (ARGB) and size in page units. */
data class ToolStyle(val color: Int, val size: Float)

/** A finished stroke on page [page] of a tab. */
class InkStroke(
    val id: String,
    val page: Int,
    val seq: Long,
    val tool: InkTool,
    val stroke: Stroke,
)

object Brushes {
    private const val EPSILON = 0.1f

    fun forTool(tool: InkTool, style: ToolStyle): Brush = Brush.createWithColorIntArgb(
        family = if (tool == InkTool.Highlighter) StockBrushes.highlighter() else StockBrushes.pressurePen(),
        colorIntArgb = style.color,
        size = style.size,
        epsilon = EPSILON,
    )

    /** Colour and size presets for each drawing tool. */
    val penColors = listOf(
        0xFF000000.toInt(), 0xFF5F6368.toInt(), 0xFFD93025.toInt(), 0xFF1A73E8.toInt(),
        0xFF188038.toInt(), 0xFFE37400.toInt(), 0xFF9334E6.toInt(), 0xFFFFFFFF.toInt(),
    )
    val highlighterColors = listOf(
        0x66FFEB3B, 0x6676FF03, 0x66FF4081, 0x6640C4FF, 0x66FF9100,
    )
    val penSizes = listOf(2.5f, 5f, 10f)
    val highlighterSizes = listOf(16f, 26f, 40f)
    val eraserSizes = listOf(12f, 30f, 60f)

    /** How far each size slider goes. */
    fun sizeRange(tool: InkTool) = when (tool) {
        InkTool.Pen -> 1f..30f
        InkTool.Highlighter -> 8f..80f
        InkTool.Eraser -> 6f..100f
    }

    /** Highlighter colours are see-through; picking a solid colour for it keeps that see-through look. */
    fun highlighterAlpha(color: Int): Int = (color and 0x00FFFFFF) or 0x66000000
}

/** Draws [strokes] (all on one page) onto [canvas], where the page is [pageWidthPx] wide at the origin. */
object InkPainter {
    private val renderer by lazy { CanvasStrokeRenderer.create() }

    fun draw(canvas: Canvas, strokes: List<InkStroke>, pageWidthPx: Float) {
        if (strokes.isEmpty()) return
        val toCanvas = Matrix().apply {
            val scale = pageWidthPx / INK_PAGE_WIDTH
            setScale(scale, scale)
        }
        // The renderer only uses the transform to pick its precision; the canvas must carry it.
        canvas.save()
        canvas.concat(toCanvas)
        // Highlighter under the pen, as on paper: the ink is written over the marked text.
        for (s in strokes) if (s.tool == InkTool.Highlighter) renderer.draw(canvas, s.stroke, toCanvas)
        for (s in strokes) if (s.tool != InkTool.Highlighter) renderer.draw(canvas, s.stroke, toCanvas)
        canvas.restore()
    }
}

fun InkStroke.toEntity(tabId: String): InkStrokeEntity {
    val bytes = ByteArrayOutputStream().also { stroke.inputs.encode(it) }.toByteArray()
    return InkStrokeEntity(id, tabId, page, seq, tool.key, stroke.brush.colorIntArgb, stroke.brush.size, bytes)
}

fun InkStrokeEntity.toInkStroke(): InkStroke {
    val tool = InkTool.fromKey(tool)
    val inputs = StrokeInputBatch.decode(ByteArrayInputStream(inputs))
    return InkStroke(id, page, seq, tool, Stroke(Brushes.forTool(tool, ToolStyle(color, size)), inputs))
}
