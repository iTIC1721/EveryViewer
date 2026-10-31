package com.tabpdf.doc

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How a tab's file is read (기획서 5.7). [key] is what the database stores. */
enum class FileFormat(val key: String) {
    Pdf("pdf"),
    Text("text"),
    Markdown("markdown"),
    Html("html"),
    Epub("epub"),
    Image("image"),
    Docx("docx"),
    Hwpx("hwpx"),
    Hwp("hwp"),
    Pptx("pptx"),
    Xlsx("xlsx"),
    /** Several files shown as one ([BundleDocument]); never detected, only made in the app. */
    Bundle("bundle"),
    /** Nothing we can show. Detected again on every open, so a later version can take it over. */
    Unsupported("unsupported");

    companion object {
        fun fromKey(key: String): FileFormat = entries.firstOrNull { it.key == key } ?: Unsupported
    }
}

object FileFormats {
    /** Bytes read from the start of a file to tell its format. */
    const val HEAD_SIZE = 8 * 1024

    private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd", "mkdn")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    /** Known to be something else than text, even if the bytes happen to look like it. */
    private val binaryExtensions = setOf(
        "doc", "ppt", "xls", "odt", "odp", "ods",
        "zip", "apk", "rar", "7z", "gz", "mp3", "mp4", "m4a", "mov", "avi", "mkv",
    )

    suspend fun detect(context: Context, uri: Uri, name: String): FileFormat = withContext(Dispatchers.IO) {
        val mime = context.contentResolver.getType(uri)
        val head = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(HEAD_SIZE)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        } ?: ByteArray(0)
        detect(name, mime, head)
    }

    /**
     * Tells the format from the file's first bytes, its [name] and the [mime] type its provider
     * reports, in that order of trust: signatures can't lie, while file managers and messengers
     * often report "application/octet-stream" or a wrong type.
     */
    fun detect(name: String, mime: String?, head: ByteArray): FileFormat {
        when {
            head.startsWith("%PDF-") -> return FileFormat.Pdf
            isImage(head) -> return FileFormat.Image
            // An EPUB's first zip entry is "mimetype" holding its type, stored uncompressed.
            head.size >= 58 && String(head, 30, 28, Charsets.ISO_8859_1) == "mimetypeapplication/epub+zip" -> return FileFormat.Epub
        }
        val extension = name.substringAfterLast('.', "").lowercase()
        when (extension) {
            "pdf" -> return FileFormat.Pdf
            in markdownExtensions -> return FileFormat.Markdown
            "html", "htm", "xhtml", "xht", "shtml" -> return FileFormat.Html
            "epub" -> return FileFormat.Epub
            in imageExtensions -> return FileFormat.Image
            "docx", "docm", "dotx" -> return FileFormat.Docx
            "hwpx" -> return FileFormat.Hwpx
            "hwp" -> return FileFormat.Hwp
            "pptx", "pptm", "ppsx", "potx" -> return FileFormat.Pptx
            "xlsx", "xlsm", "xltx", "xltm" -> return FileFormat.Xlsx
            in binaryExtensions -> return FileFormat.Unsupported
        }
        val type = mime?.lowercase()
        when {
            type == "application/pdf" -> return FileFormat.Pdf
            type == "text/markdown" || type == "text/x-markdown" -> return FileFormat.Markdown
            type == "text/html" || type == "application/xhtml+xml" -> return FileFormat.Html
            type == "application/epub+zip" -> return FileFormat.Epub
            type != null && type.startsWith("image/") -> return FileFormat.Image
            type == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> return FileFormat.Docx
            type == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> return FileFormat.Pptx
            type == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> return FileFormat.Xlsx
            type in setOf("application/hwp+zip", "application/haansofthwpx", "application/vnd.hancom.hwpx") -> return FileFormat.Hwpx
            type in setOf("application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp") -> return FileFormat.Hwp
        }
        if (!TextDecoding.looksLikeText(head)) return FileFormat.Unsupported
        // Only a file without an extension is taken for a page by its content: notes.txt stays text.
        return if (extension.isEmpty() && isHtml(head)) FileFormat.Html else FileFormat.Text
    }

    /** A page saved without its extension (downloads, messengers): starts like an HTML document. */
    private fun isHtml(head: ByteArray): Boolean {
        val start = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\t', '\r', '\n')
        return start.startsWith("<!doctype html", ignoreCase = true) || start.startsWith("<html", ignoreCase = true)
    }

    /**
     * A picture in a format the app shows (기획서 5.7), told by its first bytes: PNG, JPEG, GIF,
     * BMP, WebP or HEIF. Every picture is checked with this before Android decodes it: Android picks
     * the decoder by content whatever the name or type says, and reaches far more than these
     * (camera raw formats like DNG, makers' own codecs) — a way into phones with a single picture.
     */
    fun isImage(head: ByteArray): Boolean =
        head.startsWith(0x89, 'P'.code, 'N'.code, 'G'.code) ||
            head.startsWith(0xFF, 0xD8, 0xFF) ||
            head.startsWith("GIF8") ||
            // "BM" alone is too common a start for text; check the size of the header that follows.
            head.startsWith("BM") && head.size >= 18 && head.int32At(14) in setOf(12, 40, 52, 56, 64, 108, 124) ||
            head.startsWith("RIFF") && head.size >= 12 && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" ||
            isHeif(head)

    /**
     * HEIF: an ISO media "ftyp" box with a HEIF brand, and not AVIF (the same container, another
     * decoder) among the brands it also claims. Android decodes HEIF through its media service,
     * which an isolated process can't reach ([Pictures]).
     */
    fun isHeif(head: ByteArray): Boolean {
        if (head.size < 12 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        if (String(head, 8, 4, Charsets.US_ASCII) !in setOf("heic", "heix", "mif1", "msf1", "hevc", "heim", "heis")) return false
        val box = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
            ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
        // Brands after the major one and its version, as far as the box (and what was read) goes.
        val end = minOf(box, head.size)
        var i = 16
        while (i + 4 <= end) {
            if (String(head, i, 4, Charsets.US_ASCII) in setOf("avif", "avis")) return false
            i += 4
        }
        return true
    }

    private fun ByteArray.int32At(i: Int): Int =
        (this[i].toInt() and 0xFF) or ((this[i + 1].toInt() and 0xFF) shl 8) or
            ((this[i + 2].toInt() and 0xFF) shl 16) or ((this[i + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it].code.toByte() }

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
