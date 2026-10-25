package com.tabpdf.doc.html

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.FlowDocument
import com.tabpdf.doc.TextDecoding
import com.tabpdf.doc.office.Box
import com.tabpdf.doc.office.OfficeLayout
import com.tabpdf.doc.readUpTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.FileNotFoundException
import java.nio.charset.Charset

/**
 * HTML files (기획서 5.7), read and laid out by this app at a fixed phone-wide width like
 * Markdown, so handwriting stays put and search, selection and links work as in other formats.
 * No browser engine: scripts never run and nothing is fetched.
 */
object HtmlDocument {
    /** Parsed whole; bigger files are [DocumentOpenException.Reason.TooLarge] (shown as source instead). */
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Laid-out flow per page, as for Markdown. */
    private const val MAX_PAGE_HEIGHT = 1400f

    suspend fun open(context: Context, uri: Uri): Document {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_BYTES + 1) } ?: throw FileNotFoundException(uri.toString())
        }
        if (bytes.size > MAX_BYTES) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
        return withContext(Dispatchers.Default) { render(decode(bytes)) }
    }

    /**
     * Reading follows the page's nesting by recursion, a few frames per element; a thread with a
     * large stack lets pages nest as deep as [com.tabpdf.doc.office.Element.MAX_DEPTH] allows.
     */
    fun render(html: String, resources: ((String) -> ByteArray?)? = null, margin: Float = 0f): FlowDocument {
        var result: Result<FlowDocument>? = null
        val thread = Thread(null, { result = runCatching { renderNow(html, resources, margin) } }, "html-layout", 16L * 1024 * 1024)
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    private fun renderNow(html: String, resources: ((String) -> ByteArray?)?, margin: Float): FlowDocument {
        val page = HtmlReader.read(Jsoup.parse(html), resources, margin)
        val blocks = if (page.background != 0) listOf(Box(page.blocks, fill = page.background, padding = com.tabpdf.doc.office.Insets(0f, 0f, 0f, page.bottom))) else page.blocks
        return OfficeLayout.flow(blocks, HtmlReader.PAGE_WIDTH, if (page.background != 0) 0f else page.bottom, MAX_PAGE_HEIGHT)
    }

    /**
     * The text of an HTML file: a byte order mark first, then the encoding the page declares
     * (`<meta charset>`), else as for text files (UTF-8, or the detector's guess).
     */
    fun decode(bytes: ByteArray): String {
        if (TextDecoding.bom(bytes) == null) declaredCharset(bytes)?.let { charset ->
            if (charset != Charsets.UTF_8) return String(bytes, charset)
        }
        return TextDecoding.decode(bytes)
    }

    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val name = META_CHARSET.find(head)?.groupValues?.get(1)?.lowercase() ?: return null
        // Browsers read "EUC-KR" pages as Windows-949, its superset (the Korean letters EUC-KR lacks).
        val candidates = if (name in setOf("euc-kr", "ks_c_5601-1987", "cp949", "windows-949", "x-windows-949")) {
            listOf("x-windows-949", "MS949", "EUC-KR")
        } else {
            listOf(name)
        }
        return candidates.firstNotNullOfOrNull {
            try {
                Charset.forName(it)
            } catch (_: Exception) {
                null
            }
        }
    }

    private val META_CHARSET = Regex("""<meta[^>]*?charset\s*=\s*["']?\s*([\w.:-]+)""", RegexOption.IGNORE_CASE)
}
