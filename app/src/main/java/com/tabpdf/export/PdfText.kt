package com.tabpdf.export

import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.text.TextPaint
import com.tabpdf.ink.CharStyle
import com.tabpdf.ink.InkFonts
import com.tabpdf.ink.InkText
import com.tabpdf.ink.InkTextLayout
import com.tabpdf.ink.StyleRun
import java.util.Locale

/**
 * Text boxes as PDF text (기획서 5.9): found and copied in other apps, and drawn exactly as on
 * screen. Pretendard isn't embedded; instead each glyph typed is made into a Type 3 font glyph
 * (its outline, filled in the text's colour) with a ToUnicode map back to the characters. A font
 * holds 256 glyphs; more make more fonts. One instance serves a whole export: [page] gives each
 * page's operators, then the fonts are written once ([objects]).
 */
class PdfText {
    private class Glyph(val font: Int, val code: Int, val text: String, val advance: Float, val outline: String)

    private val glyphs = LinkedHashMap<Pair<Boolean, String>, Glyph>()
    private val byFont = mutableListOf<MutableList<Glyph>>()

    val fontCount get() = byFont.size

    /** Operators drawing [boxes] in page units (y down), and the fonts they use. */
    class Page(val operators: String, val fonts: Set<Int>)

    fun page(boxes: List<InkText>): Page {
        val out = StringBuilder()
        val fonts = sortedSetOf<Int>()
        for (box in boxes) draw(box, out, fonts)
        return Page(out.toString(), fonts)
    }

    private fun draw(box: InkText, out: StringBuilder, fonts: MutableSet<Int>) {
        val layout = InkTextLayout.layOut(box).layout
        val text = box.content.text
        val runs = box.content.runs
        if (text.isEmpty()) return
        out.append("q\n")
        var color = 0
        var colorSet = false
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line)
            val baseline = box.y + layout.getLineBaseline(line)
            out.append("BT\n")
            var i = start
            while (i < end) {
                val cp = text.codePointAt(i)
                val count = Character.charCount(cp)
                val ch = text.substring(i, i + count)
                val style = styleAt(runs, i, box.content.base)
                if (ch != "\n" && ch != "\r") {
                    if (!colorSet || style.color != color) {
                        out.append(rgb(style.color)).append(" rg\n")
                        color = style.color
                        colorSet = true
                    }
                    val g = glyph(style.bold, ch)
                    fonts += g.font
                    val x = box.x + layout.getPrimaryHorizontal(i)
                    val s = style.size
                    val shear = if (style.italic) -InkFonts.ITALIC_SKEW * s else 0f
                    out.append('/').append(fontName(g.font)).append(" 1 Tf ")
                    out.append(n(s)).append(" 0 ").append(n(shear)).append(' ').append(n(-s)).append(' ')
                        .append(n(x)).append(' ').append(n(baseline)).append(" Tm ")
                    out.append('<').append(String.format(Locale.ROOT, "%02X", g.code)).append("> Tj\n")
                }
                i += count
            }
            out.append("ET\n")
            // Underline and strike-through: bars under and through each run's part of the line.
            for (run in runs) {
                val a = maxOf(run.start, start)
                val b = minOf(run.end, end).let { if (it > a && text[it - 1] == '\n') it - 1 else it }
                if (b <= a || !(run.style.underline || run.style.strike)) continue
                val x0 = box.x + layout.getPrimaryHorizontal(a)
                // The end of a wrapped line is where the next one starts: take the line's right edge there.
                val x1 = box.x + if (b < end || line == layout.lineCount - 1) layout.getPrimaryHorizontal(b) else layout.getLineRight(line)
                val metrics = lineMetrics(run.style)
                out.append(rgb(run.style.color)).append(" rg\n")
                colorSet = false
                if (run.style.underline) rect(out, x0, baseline + metrics[0], x1, metrics[1])
                if (run.style.strike) rect(out, x0, baseline + metrics[2], x1, metrics[3])
            }
        }
        out.append("Q\n")
    }

    private fun rect(out: StringBuilder, x0: Float, y: Float, x1: Float, thickness: Float) {
        out.append(n(minOf(x0, x1))).append(' ').append(n(y)).append(' ')
            .append(n(kotlin.math.abs(x1 - x0))).append(' ').append(n(thickness)).append(" re f\n")
    }

    /**
     * Underline position and thickness, strike-through position and thickness (page units, from the
     * baseline to the bar's top, y down), as Android draws them: the font's, or where a font gives
     * none (thickness 0), Skia's defaults.
     */
    private fun lineMetrics(style: CharStyle): FloatArray {
        val s = style.size
        val defaults = floatArrayOf(s / 9f, s / 18f, -s * 6f / 21f, s / 18f)
        if (Build.VERSION.SDK_INT < 29) return defaults
        val paint = TextPaint().apply {
            typeface = InkFonts.face(style.bold)
            textSize = s
        }
        val font = floatArrayOf(paint.underlinePosition, paint.underlineThickness, paint.strikeThruPosition, paint.strikeThruThickness)
        if (font[1] <= 0f) { font[0] = defaults[0]; font[1] = defaults[1] }
        if (font[3] <= 0f) { font[2] = defaults[2]; font[3] = defaults[3] }
        return font
    }

    private fun styleAt(runs: List<StyleRun>, i: Int, base: CharStyle) = runs.firstOrNull { i >= it.start && i < it.end }?.style ?: base

    private fun glyph(bold: Boolean, ch: String): Glyph = glyphs.getOrPut(bold to ch) {
        if (byFont.isEmpty() || byFont.last().size >= 256) byFont.add(mutableListOf())
        val font = byFont.lastIndex
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            typeface = InkFonts.face(bold)
            textSize = EM
        }
        val path = Path()
        paint.getTextPath(ch, 0, ch.length, 0f, 0f, path)
        Glyph(font, byFont[font].size, ch, paint.measureText(ch), outline(path)).also { byFont[font].add(it) }
    }

    /** [path] (y down, baseline at 0) as glyph-space operators (y up), its curves as fine lines. */
    private fun outline(path: Path): String {
        val points = path.approximate(0.5f)
        val out = StringBuilder()
        var open = false
        var i = 0
        while (i + 2 < points.size) {
            val fraction = points[i]
            val x = points[i + 1]
            val y = -points[i + 2]
            // A jump to a new contour shows as two points with the same fraction.
            val jump = i == 0 || (fraction == points[i - 3] && (x != points[i - 2] || y != -points[i - 1]))
            if (jump) {
                if (open) out.append("h\n")
                out.append(n(x)).append(' ').append(n(y)).append(" m\n")
                open = true
            } else {
                out.append(n(x)).append(' ').append(n(y)).append(" l\n")
            }
            i += 3
        }
        if (open) out.append("h\nf\n")
        return out.toString()
    }

    /**
     * The fonts' objects: [first] onward are numbered in order (as many as [objectCount]). Returns
     * each as its dictionary and, for streams, the data (not yet compressed). Font [i]'s dictionary
     * is object [fontObject] ([first], i).
     */
    fun objects(first: Int): List<Pair<String, ByteArray?>> {
        val list = mutableListOf<Pair<String, ByteArray?>>()
        var num = first
        for (glyphs in byFont) {
            val fontNum = num
            val toUnicode = num + 1
            val procs = num + 2
            num += 2 + glyphs.size
            val widths = glyphs.joinToString(" ") { n(it.advance) }
            val names = glyphs.joinToString("") { "/g${it.code}" }
            val charProcs = glyphs.joinToString("") { "/g${it.code} ${procs + it.code} 0 R" }
            list += "<</Type/Font/Subtype/Type3/FontBBox[0 0 0 0]/FontMatrix[${n(1 / EM)} 0 0 ${n(1 / EM)} 0 0]" +
                "/CharProcs<<$charProcs>>/Encoding<</Type/Encoding/Differences[0$names]>>" +
                "/FirstChar 0/LastChar ${glyphs.size - 1}/Widths[$widths]/Resources<<>>/ToUnicode $toUnicode 0 R>>" to null
            list += "" to cmap(glyphs).toByteArray(Charsets.ISO_8859_1)
            for (g in glyphs) list += "" to "${n(g.advance)} 0 d0\n${g.outline}".toByteArray(Charsets.ISO_8859_1)
        }
        return list
    }

    val objectCount get() = byFont.sumOf { 2 + it.size }

    /** Object number of font [font]'s dictionary when the objects start at [first]. */
    fun fontObject(first: Int, font: Int): Int {
        var num = first
        for (i in 0 until font) num += 2 + byFont[i].size
        return num
    }

    private fun cmap(glyphs: List<Glyph>): String {
        val out = StringBuilder()
        out.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
        out.append("/CIDSystemInfo <</Registry (Adobe)/Ordering (UCS)/Supplement 0>> def\n")
        out.append("/CMapName /EveryViewer-UCS def\n/CMapType 2 def\n")
        out.append("1 begincodespacerange\n<00> <FF>\nendcodespacerange\n")
        for (chunk in glyphs.chunked(100)) {
            out.append(chunk.size).append(" beginbfchar\n")
            for (g in chunk) {
                out.append('<').append(String.format(Locale.ROOT, "%02X", g.code)).append("> <")
                for (c in g.text) out.append(String.format(Locale.ROOT, "%04X", c.code))
                out.append(">\n")
            }
            out.append("endbfchar\n")
        }
        out.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
        return out.toString()
    }

    companion object {
        /** Glyph space: units per em. */
        private const val EM = 1000f

        fun fontName(font: Int) = "EVf$font"

        private fun n(v: Float) = PdfWriter.number(v)

        private fun rgb(color: Int): String {
            fun c(v: Int) = PdfWriter.number((v and 0xFF) / 255f)
            return "${c(color shr 16)} ${c(color shr 8)} ${c(color)}"
        }
    }
}
