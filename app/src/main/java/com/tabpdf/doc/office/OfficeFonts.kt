package com.tabpdf.doc.office

import java.io.DataInputStream
import kotlin.math.roundToInt

/**
 * A font as Word lays it out (기획서 5.7): a single line [ascent] above the baseline and [descent]
 * below it, and the advance of each letter, all in thousandths of an em. Word adds 30% to the
 * line of a Korean font (measured: Malgun Gothic 1.73, Gulim 1.30 of the font size), mostly
 * below the text. The numbers come from the fonts themselves (tools/office-font-metrics.py),
 * those installed on the PC that made the table: Korean fonts and common Latin ones.
 */
internal class OfficeFont(
    val names: List<String>,
    val ascent: Int,
    val descent: Int,
    /** A Hangul syllable's advance, 0 if the font has no Hangul. */
    val hangul: Int,
    /** A Han character's advance, 0 if unknown. */
    val han: Int,
    /** Advances of ' ' to '~'. */
    val ascii: ShortArray,
    val boldAscii: ShortArray?,
    /** Advances of other letters the font has (dashes, quotes, symbols). */
    private val others: Map<Char, Int>,
    /** How much pairs of ASCII letters are kerned, by (first shl 16 or second). */
    private val kerning: Map<Int, Int>,
) {
    /**
     * [letters]' width at [size] points, kerned or not, in ems of that size; null if a letter's
     * width is unknown. Word sets East Asian letters at a size of whole 1/600 inches (measured:
     * a 10 pt Malgun Gothic syllable advances 9.96 pt, an 11 pt one 11.04), which adds up along a line.
     */
    fun width(letters: String, bold: Boolean, kerning: Boolean, size: Float): Float? {
        var ems = 0f
        for ((k, c) in letters.withIndex()) {
            val a = advance(c, bold) ?: return null
            ems += if (OfficeFonts.isEastAsian(c)) eastAsianEms(a, size) else a
            if (kerning && k > 0) ems += kern(letters[k - 1], c)
        }
        return ems
    }

    /** What kerning adds to [a]'s advance before [b] (negative: closer), in ems. */
    fun kern(a: Char, b: Char): Float = (kerning[a.code shl 16 or b.code] ?: 0) / 1000f

    /** [c]'s advance in ems, or null when unknown (the drawing font's own is kept). */
    fun advance(c: Char, bold: Boolean): Float? = when {
        c in ' '..'~' -> ((if (bold) boldAscii else null) ?: ascii)[c - ' '] / 1000f
        isHangulSyllable(c) && hangul > 0 -> hangul / 1000f
        c.code in 0x4E00..0x9FFF && han > 0 -> han / 1000f
        else -> others[c]?.let { it / 1000f }
    }
}

internal object OfficeFonts {
    /** The fonts by every name they go by, lower case; read on first use (tools/office-font-metrics.py). */
    private val byName: Map<String, OfficeFont> by lazy {
        val fonts = OfficeFonts::class.java.getResourceAsStream("office-fonts.bin")?.use { read(DataInputStream(it.buffered())) }.orEmpty()
        buildMap { for (f in fonts) for (n in f.names) putIfAbsent(n.lowercase(), f) }
    }

    private fun read(input: DataInputStream): List<OfficeFont> {
        val magic = ByteArray(4).also(input::readFully)
        if (String(magic, Charsets.US_ASCII) != "OFM1") return emptyList()
        fun ascii() = ShortArray(95) { input.readShort() }
        return List(input.readUnsignedShort()) {
            val names = List(input.readUnsignedByte()) { String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8) }
            val ascent = input.readShort().toInt()
            val descent = input.readShort().toInt()
            val hangul = input.readShort().toInt()
            val han = input.readShort().toInt()
            val regular = ascii()
            val bold = if (input.readUnsignedByte() != 0) ascii() else null
            val others = buildMap { repeat(input.readUnsignedShort()) { put(input.readChar(), input.readShort().toInt()) } }
            val kerning = buildMap {
                repeat(input.readUnsignedShort()) { put(input.readUnsignedByte() shl 16 or input.readUnsignedByte(), input.readShort().toInt()) }
            }
            OfficeFont(names, ascent, descent, hangul, han, regular, bold, others, kerning)
        }
    }

    private val MALGUN by lazy { byName["malgun gothic"] }

    /**
     * The font named [name]. A Korean font the table lacks counts as Malgun Gothic: few readers
     * have it, and Word puts Malgun Gothic in its place. Other unknown fonts are null.
     */
    fun find(name: String?): OfficeFont? {
        val n = name?.trim()?.lowercase() ?: return null
        return byName[n] ?: MALGUN?.takeIf { n.any { it.code >= 0x2E80 } }
    }

    /**
     * The font Word draws [c] in with [style]'s typefaces: Hangul and Han in the East Asian one,
     * Latin letters in the Latin one, and the letters both share by the hint.
     */
    fun fontFor(c: Char, style: TextStyle): String? {
        val eastAsian = isEastAsian(c) || (style.eastAsiaHint && c.code >= 0x80)
        return if (eastAsian) style.eastAsiaFont ?: style.latinFont ?: KOREAN_DEFAULT
        else style.latinFont ?: style.eastAsiaFont
    }

    /**
     * Whether a space between [before] and [after] is as wide as half a Hangul syllable of the
     * East Asian font ([halfSpace]): Word makes it so beside Hangul, whatever the space's own font
     * (measured; between other letters it keeps the font's width).
     */
    fun isHalfSpace(before: Char?, after: Char?): Boolean =
        before?.let(::isEastAsian) == true || after?.let(::isEastAsian) == true

    /** That space's width in ems of [style]'s size, or null if its East Asian font is unknown. */
    fun halfSpace(style: TextStyle): Float? {
        val font = find(style.eastAsiaFont ?: KOREAN_DEFAULT)?.takeIf { it.hangul > 0 } ?: return null
        return eastAsianEms(font.hangul / 2000f, style.textSize)
    }

    /** Hangul, Han, kana and full-width forms. */
    fun isEastAsian(c: Char): Boolean = c.code in 0x1100..0x11FF || c.code in 0x2E80..0xA4CF ||
        c.code in 0xAC00..0xD7AF || c.code in 0xF900..0xFAFF || c.code in 0xFE30..0xFE4F || c.code in 0xFF00..0xFFEF

    /** What Word falls back to for Hangul when no East Asian typeface is given. */
    private const val KOREAN_DEFAULT = "맑은 고딕"
}

/**
 * An East Asian letter [ems] wide at [size] points as Word advances it, in ems of [size]: Word sets
 * such letters at the size rounded to a whole 1/600 inch (Latin letters keep theirs).
 */
internal fun eastAsianEms(ems: Float, size: Float): Float =
    if (size > 0f) ems * (size * WORD_UNITS_PER_POINT).roundToInt() / (size * WORD_UNITS_PER_POINT) else ems

private const val WORD_UNITS_PER_POINT = 600f / 72f

private fun isHangulSyllable(c: Char) = c.code in 0xAC00..0xD7A3
