package com.tabpdf.doc

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.mozilla.universalchardet.UniversalDetector
import java.io.InputStream

/** At most [limit] bytes from the start of the stream (files parsed whole: Markdown, HTML). */
internal fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/**
 * Turns the bytes of a text file into a string, guessing the encoding (기획서 5.7): a byte order
 * mark, else UTF-8 if the bytes are valid UTF-8, else whatever juniversalchardet (Mozilla's
 * detector) says — which is what makes old Korean files in EUC-KR/CP949 readable.
 */
object TextDecoding {
    fun decode(bytes: ByteArray): String {
        bom(bytes)?.let { (charset, length) -> return String(bytes, length, bytes.size - length, charset) }
        strictUtf8(bytes)?.let { return it }
        return String(bytes, detect(bytes))
    }

    /**
     * The encoding of text whose non-ASCII part starts with [sample] (a file read in pieces, 기획서
     * 5.7): UTF-8 if the sample is valid UTF-8 (a character cut off at its end allowed), else the
     * detector's guess. Gives the same answer as [decode] when the sample runs to the end of the file.
     */
    fun charsetOf(sample: ByteArray): Charset = if (strictUtf8(sample) != null) Charsets.UTF_8 else detect(sample)

    /**
     * Whether the start of a file reads as text: no NUL bytes (unless it is UTF-16) and almost no
     * control characters other than whitespace.
     */
    fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return true
        if (bom(head) != null) return true
        var control = 0
        for (b in head) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != '\n'.code && c != '\r'.code && c != '\t'.code && c != 0x0C && c != 0x1B) control++
        }
        return control * 100 < head.size
    }

    /** The encoding a byte order mark at the start of [bytes] names, and the mark's length. */
    fun bom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        // A multi-byte character cut off by a size limit is still UTF-8; anything else is not.
        trimmedUtf8(bytes)
    }

    private fun trimmedUtf8(bytes: ByteArray): String? {
        for (cut in 1..3) {
            if (bytes.size <= cut) break
            try {
                return Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, bytes.size - cut))
                    .toString()
            } catch (_: CharacterCodingException) {
            }
        }
        return null
    }

    private fun detect(bytes: ByteArray): Charset {
        val name = try {
            UniversalDetector(null).run {
                handleData(bytes, 0, bytes.size)
                dataEnd()
                detectedCharset
            }
        } catch (_: Exception) {
            null
        }
        return when (name) {
            // Nothing recognised: most likely a Korean file too short to tell.
            null -> charsetOrNull("x-windows-949") ?: Charsets.ISO_8859_1
            // CP949 is the superset Korean Windows actually writes (it has every Hangul syllable).
            "EUC-KR" -> charsetOrNull("x-windows-949") ?: charsetOrNull("MS949") ?: Charset.forName("EUC-KR")
            else -> charsetOrNull(name) ?: Charsets.ISO_8859_1
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: Exception) {
        null
    }
}
