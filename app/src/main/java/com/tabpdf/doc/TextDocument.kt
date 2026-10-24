package com.tabpdf.doc

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/** Plain text files (기획서 5.7), shown as one continuous sheet. */
object TextDocument {
    /** Bigger files are refused rather than laid out for a minute. */
    const val MAX_BYTES = 16 * 1024 * 1024

    const val MARGIN = 50f
    const val MAX_PAGE_HEIGHT = 1400f
    private const val PROSE_SIZE = 34f
    private const val CODE_SIZE = 30f

    /** Lines laid out per block: keeps each layout small and lets blocks be laid out in parallel. */
    private const val CHUNK_CHARS = 8_000

    /** Prose reads better in the normal typeface; everything else (code, logs, data) is monospaced. */
    private val proseExtensions = setOf("txt", "text", "")

    suspend fun open(context: Context, uri: Uri, name: String): Document = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(MAX_BYTES) }
            ?: throw FileNotFoundException(uri.toString())
        val text = TextDecoding.decode(bytes)
        val prose = name.substringAfterLast('.', "").lowercase() in proseExtensions
        layOut(text, prose)
    }

    suspend fun layOut(text: String, prose: Boolean): FlowDocument = coroutineScope {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val width = FLOW_WIDTH - 2 * MARGIN
        val blocks = chunks(normalized).map { chunk ->
            // Paints are not thread-safe: one per chunk.
            async(Dispatchers.Default) {
                val paint = if (prose) flowPaint(PROSE_SIZE) else flowPaint(CODE_SIZE, Typeface.MONOSPACE)
                TextBlock(staticLayout(chunk, paint, width, lineSpacing = if (prose) 1.4f else 1.25f))
            }
        }.awaitAll()
        FlowBuilder().apply {
            space(MARGIN)
            blocks.forEach { add(it, MARGIN) }
            space(MARGIN)
        }.build(MAX_PAGE_HEIGHT, pageGaps = false)
    }

    /**
     * Splits [text] into runs of whole lines about [CHUNK_CHARS] long (the newline between two
     * chunks is dropped: each block starts on a new line anyway). A single huge line is split too.
     */
    internal fun chunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (true) {
            if (text.length - start <= CHUNK_CHARS) {
                result += text.substring(start)
                return result
            }
            val lastNewline = text.lastIndexOf('\n', start + CHUNK_CHARS)
            val end = if (lastNewline > start) {
                lastNewline
            } else {
                val nextNewline = text.indexOf('\n', start + CHUNK_CHARS)
                val cut = start + CHUNK_CHARS
                when {
                    nextNewline in 0 until start + CHUNK_CHARS * 3 -> nextNewline
                    // Never between the two halves of a surrogate pair (emoji and the like).
                    Character.isHighSurrogate(text[cut - 1]) -> cut - 1
                    else -> cut
                }
            }
            result += text.substring(start, end)
            start = if (text[end] == '\n') end + 1 else end
        }
    }

    /** Reads at most [limit] bytes; a longer stream is [DocumentOpenException.Reason.TooLarge]. */
    private fun InputStream.readLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > limit) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
        }
    }
}
