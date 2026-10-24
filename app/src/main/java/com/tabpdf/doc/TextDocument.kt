package com.tabpdf.doc

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Plain text files (기획서 5.7), shown as one continuous sheet. Files of any size are read and laid
 * out a chunk of lines at a time ([StreamedTextDocument]); this object holds how the text is cut
 * into chunks and laid out, and [layOut], which does the same for a whole string at once.
 */
object TextDocument {
    const val MARGIN = 50f
    const val MAX_PAGE_HEIGHT = 1400f
    private const val PROSE_SIZE = 34f
    private const val CODE_SIZE = 30f

    /** Lines laid out per block: keeps each layout small and lets blocks be laid out in parallel. */
    const val CHUNK_CHARS = 8_000

    /** Prose reads better in the normal typeface; everything else (code, logs, data) is monospaced. */
    private val proseExtensions = setOf("txt", "text", "")

    /** Opens a text file; [notice] is shown with it (see [Document.notice]). */
    suspend fun open(context: Context, uri: Uri, name: String, notice: String? = null): Document =
        StreamedTextDocument.open(context, uri, name.substringAfterLast('.', "").lowercase() in proseExtensions, notice)

    /** Paints are not thread-safe: one per thread laying out. */
    fun paint(prose: Boolean): TextPaint = if (prose) flowPaint(PROSE_SIZE) else flowPaint(CODE_SIZE, Typeface.MONOSPACE)

    /** One chunk of text (from [chunkEnd]) laid out to the page width inside the margins. */
    fun chunkLayout(text: String, prose: Boolean, paint: TextPaint): StaticLayout =
        staticLayout(text, paint, FLOW_WIDTH - 2 * MARGIN, lineSpacing = if (prose) 1.4f else 1.25f)

    /**
     * [text] laid out whole: the pages [StreamedTextDocument] makes of a file with this text, which
     * keep handwriting where it was written (pages must never move for the same text).
     */
    suspend fun layOut(text: String, prose: Boolean): FlowDocument = coroutineScope {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = chunks(normalized).map { chunk ->
            async(Dispatchers.Default) { TextBlock(chunkLayout(chunk, prose, paint(prose))) }
        }.awaitAll()
        FlowBuilder().apply {
            space(MARGIN)
            blocks.forEach { add(it, MARGIN) }
            space(MARGIN)
        }.build(MAX_PAGE_HEIGHT, pageGaps = false)
    }

    /** [text] cut into chunks by [chunkEnd]. */
    internal fun chunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (true) {
            val end = chunkEnd(text, start)
            if (end == null) {
                result += text.substring(start)
                return result
            }
            result += text.substring(start, end)
            start = if (text[end] == '\n') end + 1 else end
        }
    }

    /**
     * Where the chunk starting at [start] ends: runs of whole lines about [CHUNK_CHARS] long, a
     * single huge line split too. The next chunk starts after the newline at the end, if there is
     * one (each block starts on a new line anyway). Null if the rest of [text] is the last chunk.
     * [text] must hold at least 3 × [CHUNK_CHARS] characters after [start], unless it ends there.
     */
    fun chunkEnd(text: CharSequence, start: Int): Int? {
        if (text.length - start <= CHUNK_CHARS) return null
        val lastNewline = text.lastIndexOf('\n', start + CHUNK_CHARS)
        if (lastNewline > start) return lastNewline
        val nextNewline = text.indexOf('\n', start + CHUNK_CHARS)
        val cut = start + CHUNK_CHARS
        return when {
            nextNewline in 0 until start + CHUNK_CHARS * 3 -> nextNewline
            // Never between the two halves of a surrogate pair (emoji and the like).
            Character.isHighSurrogate(text[cut - 1]) -> cut - 1
            else -> cut
        }
    }
}
