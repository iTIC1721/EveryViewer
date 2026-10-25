package com.tabpdf.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/*
 * Just enough of PDF's object syntax (ISO 32000-1, 7.2–7.5) to find a file's pages and write an
 * update after it: numbers, names, strings, arrays, dictionaries, references and streams. Values
 * are written back as they were read (numbers and names as their text, strings as bytes), so a
 * page rewritten in an update keeps everything it had.
 */

class PdfName(val text: String) {
    override fun equals(other: Any?) = other is PdfName && other.text == text
    override fun hashCode() = text.hashCode()
    override fun toString() = "/$text"
}

class PdfNumber(val text: String) {
    val value: Double get() = text.toDoubleOrNull() ?: 0.0
    override fun toString() = text
}

class PdfString(val bytes: ByteArray)

data class PdfRef(val num: Int, val gen: Int)

object PdfNull

typealias PdfArray = MutableList<Any>

/** Keys are names without the slash, as written (with any #xx escapes). */
typealias PdfDict = LinkedHashMap<String, Any>

/** A stream's dictionary and where its data starts in the file; [ref] once known (for decryption). */
class PdfStream(val dict: PdfDict, val dataStart: Long) {
    var ref: PdfRef? = null
}

class PdfSyntaxException(message: String) : IOException(message)

/** Random access to the file with a block cache, as a byte source for [PdfLexer]. */
class PdfBytes(private val file: RandomAccessFile) {
    val length: Long = file.length()
    private val block = ByteArray(BLOCK)
    private var blockStart = -1L
    private var blockLength = 0

    /** The byte at [pos], or -1 past the end. */
    operator fun get(pos: Long): Int {
        if (pos < 0 || pos >= length) return -1
        if (pos < blockStart || pos >= blockStart + blockLength) {
            blockStart = pos - pos % BLOCK
            file.seek(blockStart)
            blockLength = file.read(block, 0, minOf(BLOCK.toLong(), length - blockStart).toInt()).coerceAtLeast(0)
        }
        return block[(pos - blockStart).toInt()].toInt() and 0xFF
    }

    fun read(pos: Long, count: Int): ByteArray {
        if (pos < 0 || count < 0 || pos + count > length) throw PdfSyntaxException("Read past the end")
        val out = ByteArray(count)
        file.seek(pos)
        file.readFully(out)
        return out
    }

    private companion object {
        const val BLOCK = 64 * 1024
    }
}

/** Reads objects from [at]; [pos] moves past what was read. */
class PdfLexer(private val at: (Long) -> Int, var pos: Long) {
    constructor(bytes: ByteArray, pos: Long = 0) : this({ i -> if (i >= 0 && i < bytes.size) bytes[i.toInt()].toInt() and 0xFF else -1 }, pos)

    private fun peek() = at(pos)

    fun skipSpace() {
        while (true) {
            val c = peek()
            when {
                c == '%'.code -> while (peek().let { it != -1 && it != '\n'.code && it != '\r'.code }) pos++
                isSpace(c) -> pos++
                else -> return
            }
        }
    }

    /** A bare word (keyword or number) at [pos], without moving. */
    fun peekWord(): String {
        skipSpace()
        val start = pos
        val word = word()
        pos = start
        return word
    }

    private fun word(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c == -1 || isSpace(c) || isDelimiter(c)) break
            sb.append(c.toChar())
            pos++
            if (sb.length > MAX_TOKEN) throw PdfSyntaxException("Token too long")
        }
        return sb.toString()
    }

    /** The next bare word (keyword or number). */
    fun nextWord(): String {
        skipSpace()
        return word()
    }

    fun expectWord(word: String) {
        skipSpace()
        val got = word()
        if (got != word) throw PdfSyntaxException("Expected $word, found $got")
    }

    /** One object; "n g R" becomes a [PdfRef]. A dictionary followed by "stream" becomes a [PdfStream]. */
    fun readObject(depth: Int = 0): Any {
        if (depth > MAX_DEPTH) throw PdfSyntaxException("Nested too deeply")
        skipSpace()
        val c = peek()
        return when {
            c == -1 -> throw PdfSyntaxException("Unexpected end")
            c == '/'.code -> { pos++; PdfName(word()) }
            c == '('.code -> literalString()
            c == '<'.code && at(pos + 1) == '<'.code -> {
                pos += 2
                val dict = PdfDict()
                while (true) {
                    skipSpace()
                    if (peek() == '>'.code && at(pos + 1) == '>'.code) { pos += 2; break }
                    val key = readObject(depth + 1) as? PdfName ?: throw PdfSyntaxException("Dictionary key is not a name")
                    dict[key.text] = readObject(depth + 1)
                }
                val after = pos
                skipSpace()
                if (word() == "stream") {
                    // The data starts after the end-of-line that follows the keyword.
                    if (peek() == '\r'.code) pos++
                    if (peek() == '\n'.code) pos++
                    PdfStream(dict, pos)
                } else {
                    pos = after
                    dict
                }
            }
            c == '<'.code -> hexString()
            c == '['.code -> {
                pos++
                val array: PdfArray = mutableListOf()
                while (true) {
                    skipSpace()
                    if (peek() == ']'.code) { pos++; break }
                    array += readObject(depth + 1)
                }
                array
            }
            else -> {
                val w = word()
                if (w.isEmpty()) throw PdfSyntaxException("Unexpected '${c.toChar()}'")
                when {
                    w == "true" -> true
                    w == "false" -> false
                    w == "null" -> PdfNull
                    isInteger(w) -> {
                        // An integer may start a reference: "12 0 R".
                        val save = pos
                        skipSpace()
                        val gen = word()
                        if (isInteger(gen)) {
                            skipSpace()
                            if (word() == "R") return PdfRef(w.toInt(), gen.toInt())
                        }
                        pos = save
                        PdfNumber(w)
                    }
                    w.toDoubleOrNull() != null || w.startsWith(".") || w.startsWith("-.") -> PdfNumber(w)
                    else -> throw PdfSyntaxException("Unexpected $w")
                }
            }
        }
    }

    private fun literalString(): PdfString {
        pos++ // (
        val out = ByteArrayOutputStream()
        var nesting = 1
        while (true) {
            val c = peek()
            if (c == -1) throw PdfSyntaxException("Unterminated string")
            pos++
            when (c) {
                '('.code -> { nesting++; out.write(c) }
                ')'.code -> { if (--nesting == 0) break; out.write(c) }
                '\\'.code -> {
                    val e = peek()
                    pos++
                    when (e) {
                        'n'.code -> out.write('\n'.code)
                        'r'.code -> out.write('\r'.code)
                        't'.code -> out.write('\t'.code)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '\r'.code -> if (peek() == '\n'.code) pos++
                        '\n'.code -> Unit
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++ }
                            }
                            out.write(v and 0xFF)
                        }
                        -1 -> throw PdfSyntaxException("Unterminated string")
                        else -> out.write(e)
                    }
                }
                else -> out.write(c)
            }
            if (out.size() > MAX_STRING) throw PdfSyntaxException("String too long")
        }
        return PdfString(out.toByteArray())
    }

    private fun hexString(): PdfString {
        pos++ // <
        val out = ByteArrayOutputStream()
        var high = -1
        while (true) {
            val c = peek()
            pos++
            if (c == -1) throw PdfSyntaxException("Unterminated hex string")
            if (c == '>'.code) break
            val v = Character.digit(c, 16)
            if (v < 0) continue // spaces
            if (high < 0) high = v else { out.write(high * 16 + v); high = -1 }
            if (out.size() > MAX_STRING) throw PdfSyntaxException("String too long")
        }
        if (high >= 0) out.write(high * 16)
        return PdfString(out.toByteArray())
    }

    companion object {
        private const val MAX_TOKEN = 4096
        private const val MAX_STRING = 1 shl 20
        private const val MAX_DEPTH = 64

        fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelimiter(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        private fun isInteger(w: String) = w.isNotEmpty() && w.length < 11 && w.all { it in '0'..'9' }
    }
}

/** Writes objects back as PDF text. */
object PdfWriter {
    fun write(value: Any, out: StringBuilder) {
        when (value) {
            is PdfName -> out.append('/').append(value.text)
            is PdfNumber -> out.append(value.text)
            is PdfRef -> out.append(value.num).append(' ').append(value.gen).append(" R")
            is PdfString -> {
                out.append('<')
                for (b in value.bytes) out.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
                out.append('>')
            }
            is Boolean -> out.append(value)
            is PdfNull -> out.append("null")
            is Map<*, *> -> {
                out.append("<<")
                for ((k, v) in value) {
                    out.append('/').append(k as String).append(' ')
                    write(v!!, out)
                    out.append(' ')
                }
                out.append(">>")
            }
            is List<*> -> {
                out.append('[')
                for ((i, v) in value.withIndex()) {
                    if (i > 0) out.append(' ')
                    write(v!!, out)
                }
                out.append(']')
            }
            is Int -> out.append(value)
            is Float -> out.append(number(value))
            is String -> out.append(value) // already PDF text
            else -> throw IllegalArgumentException("Can't write ${value::class.java}")
        }
    }

    fun toText(value: Any) = StringBuilder().also { write(value, it) }.toString()

    /** A number as PDF writes it: no exponent, at most 3 decimals. */
    fun number(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val r = Math.round(v * 1000.0)
        if (r % 1000 == 0L) return (r / 1000).toString()
        return String.format(java.util.Locale.ROOT, "%.3f", r / 1000.0).trimEnd('0')
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** FlateDecode, with the PNG predictors cross-reference and object streams use (7.4.4). */
object Flate {
    fun decode(data: ByteArray, parms: PdfDict?, limit: Int): ByteArray {
        val inflater = Inflater()
        val out = ByteArrayOutputStream()
        try {
            inflater.setInput(data)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
                if (out.size() > limit) throw PdfSyntaxException("Stream too large")
            }
        } catch (e: DataFormatException) {
            throw PdfSyntaxException("Bad compressed data")
        } finally {
            inflater.end()
        }
        val predictor = (parms?.get("Predictor") as? PdfNumber)?.value?.toInt() ?: 1
        if (predictor < 10) {
            if (predictor != 1) throw PdfSyntaxException("Unsupported predictor $predictor")
            return out.toByteArray()
        }
        val colors = (parms?.get("Colors") as? PdfNumber)?.value?.toInt() ?: 1
        val bpc = (parms?.get("BitsPerComponent") as? PdfNumber)?.value?.toInt() ?: 8
        val columns = (parms?.get("Columns") as? PdfNumber)?.value?.toInt() ?: 1
        return unpredict(out.toByteArray(), colors, bpc, columns)
    }

    private fun unpredict(data: ByteArray, colors: Int, bpc: Int, columns: Int): ByteArray {
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLength = (colors * bpc * columns + 7) / 8
        if (rowLength <= 0) throw PdfSyntaxException("Bad predictor parameters")
        val rows = data.size / (rowLength + 1)
        val out = ByteArray(rows * rowLength)
        val prev = ByteArray(rowLength)
        for (r in 0 until rows) {
            val type = data[r * (rowLength + 1)].toInt()
            val rowStart = r * (rowLength + 1) + 1
            val row = ByteArray(rowLength)
            for (i in 0 until rowLength) {
                val raw = data[rowStart + i].toInt() and 0xFF
                val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val v = when (type) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> raw + paeth(left, up, upLeft)
                    else -> throw PdfSyntaxException("Bad PNG filter $type")
                }
                row[i] = v.toByte()
            }
            System.arraycopy(row, 0, out, r * rowLength, rowLength)
            System.arraycopy(row, 0, prev, 0, rowLength)
        }
        return out
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = Math.abs(p - a)
        val pb = Math.abs(p - b)
        val pc = Math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
