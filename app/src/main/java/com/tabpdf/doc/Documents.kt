package com.tabpdf.doc

import android.content.Context
import android.net.Uri
import com.tabpdf.pdf.PdfDocument

/** Opens a tab's file as a [Document], by the format it was detected as. */
object Documents {
    /** Throws if the file is missing, unreadable or can't be shown. */
    suspend fun open(context: Context, uri: Uri, name: String, format: FileFormat): Document =
        when (format) {
            FileFormat.Pdf -> PdfDocument.open(context, uri)
            FileFormat.Text -> TextDocument.open(context, uri, name)
            FileFormat.Markdown, FileFormat.Image ->
                throw DocumentOpenException(DocumentOpenException.Reason.Unsupported)
            // Detected again: this version may read what the one that opened the tab could not.
            FileFormat.Unsupported -> FileFormats.detect(context, uri, name).let {
                if (it == FileFormat.Unsupported) throw DocumentOpenException(DocumentOpenException.Reason.Unsupported)
                open(context, uri, name, it)
            }
        }
}
