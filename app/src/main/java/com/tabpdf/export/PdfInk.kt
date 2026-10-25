package com.tabpdf.export

import androidx.ink.geometry.MutableVec
import com.tabpdf.ink.InkStroke
import com.tabpdf.ink.InkTool

/**
 * Handwriting as PDF drawing operators: each stroke filled along its outline, in its colour, the
 * see-through ones (highlighter) through a graphics state with that opacity. Coordinates are the
 * strokes' own page space (INK_PAGE_WIDTH wide, y down); the caller sets the matrix to the page.
 */
class PdfInk(strokes: List<InkStroke>) {
    /** The operators, highlighter under pen as on screen ([com.tabpdf.ink.InkPainter]). */
    val operators: String

    /** Opacities used (0–255, below 255): each needs a graphics state named [stateName]. */
    val alphas: Set<Int>

    init {
        val out = StringBuilder()
        val used = sortedSetOf<Int>()
        val point = MutableVec()
        val ordered = strokes.filter { it.tool == InkTool.Highlighter } + strokes.filter { it.tool != InkTool.Highlighter }
        for (s in ordered) {
            val color = s.stroke.brush.colorIntArgb
            val alpha = (color ushr 24) and 0xFF
            if (alpha == 0) continue
            val shape = s.stroke.shape
            out.append("q\n")
            if (alpha < 255) {
                used += alpha
                out.append('/').append(stateName(alpha)).append(" gs\n")
            }
            out.append(channel(color shr 16)).append(' ').append(channel(color shr 8)).append(' ').append(channel(color)).append(" rg\n")
            var any = false
            for (group in 0 until shape.getRenderGroupCount()) {
                for (outline in 0 until shape.getOutlineCount(group)) {
                    val count = shape.getOutlineVertexCount(group, outline)
                    if (count < 3) continue
                    for (v in 0 until count) {
                        shape.populateOutlinePosition(group, outline, v, point)
                        out.append(PdfWriter.number(point.x)).append(' ').append(PdfWriter.number(point.y))
                        out.append(if (v == 0) " m\n" else " l\n")
                    }
                    out.append("h\n")
                    any = true
                }
            }
            // Nonzero winding, as the outline may cross itself where a stroke loops.
            out.append(if (any) "f\n" else "n\n")
            out.append("Q\n")
        }
        operators = out.toString()
        alphas = used
    }

    val isEmpty: Boolean get() = operators.isEmpty()

    companion object {
        fun stateName(alpha: Int) = "EVa$alpha"

        /** The graphics state for [alpha]: fills (and strokes) that see-through. */
        fun state(alpha: Int): String {
            val a = PdfWriter.number(alpha / 255f)
            return "<</Type/ExtGState/ca $a/CA $a>>"
        }

        private fun channel(v: Int) = PdfWriter.number((v and 0xFF) / 255f)
    }
}
