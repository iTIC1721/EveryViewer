package com.tabpdf.doc

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.office.OfficeDocuments
import com.tabpdf.pdf.PdfDocument

/** Opens a tab's file as a [Document], by the format it was detected as. */
object Documents {
    /** Throws if the file is missing, unreadable or can't be shown. */
    suspend fun open(context: Context, uri: Uri, name: String, format: FileFormat): Document =
        when (format) {
            FileFormat.Pdf -> PdfDocument.open(context, uri)
            FileFormat.Text -> TextDocument.open(context, uri, name)
            FileFormat.Image -> ImageDocument.open(context, uri)
            FileFormat.Markdown -> try {
                MarkdownDocument.open(context, uri)
            } catch (e: DocumentOpenException) {
                if (e.reason != DocumentOpenException.Reason.TooLarge) throw e
                TextDocument.open(context, uri, name, notice = "파일이 커서 마크다운 미리보기를 지원하지 않습니다. 원문으로 표시합니다.")
            }
            FileFormat.Docx, FileFormat.Hwpx, FileFormat.Pptx -> OfficeDocuments.open(context, uri, format)
            // Detected again: this version may read what the one that opened the tab could not.
            FileFormat.Unsupported -> FileFormats.detect(context, uri, name).let {
                if (it == FileFormat.Unsupported) throw DocumentOpenException(DocumentOpenException.Reason.Unsupported)
                open(context, uri, name, it)
            }
        }
}
