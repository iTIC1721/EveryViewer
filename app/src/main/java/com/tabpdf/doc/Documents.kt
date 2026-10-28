package com.tabpdf.doc

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.html.EpubDocument
import com.tabpdf.doc.html.HtmlDocument
import com.tabpdf.doc.office.OfficeDocuments
import com.tabpdf.pdf.PdfDocument
import java.io.IOException

/** Opens a tab's file as a [Document], by the format it was detected as. */
object Documents {
    /**
     * Throws if the file is missing, unreadable or can't be shown — as an exception even when it
     * ran out of memory or stack, which a crafted file can cause: an error would crash the app,
     * and again on every start as the tab is reopened.
     */
    suspend fun open(context: Context, uri: Uri, name: String, format: FileFormat): Document = try {
        openAs(context, uri, name, format).also { it.checkPageSizes() }
    } catch (_: OutOfMemoryError) {
        throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
    } catch (e: StackOverflowError) {
        throw IOException("Too deeply nested", e)
    }

    /**
     * Pages taller than this (height / width) aren't shown: even at the smallest zoom the viewer
     * couldn't lay them out on a wide screen (DocumentViewer's MAX_PAGE_HEIGHT_PX).
     */
    const val MAX_PAGE_ASPECT = 100f

    /**
     * A page size from the file (an image, a PDF's MediaBox, a docx's paper) can be zero, endless or
     * absurdly tall: the viewer would crash on it. Documents laid out while shown are left out: they
     * are laid out at a fixed width, cut into pages of bounded height.
     */
    private suspend fun Document.checkPageSizes() {
        if (progress != null) return
        val problem = when {
            pageSizes.any { !(it.width > 0f && it.height > 0f && it.width.isFinite() && it.height.isFinite()) } ->
                IOException("Bad page size")
            pageSizes.any { it.aspect > MAX_PAGE_ASPECT } -> DocumentOpenException(DocumentOpenException.Reason.PageTooTall)
            else -> return
        }
        close()
        throw problem
    }

    private suspend fun openAs(context: Context, uri: Uri, name: String, format: FileFormat): Document =
        when (format) {
            FileFormat.Pdf -> PdfDocument.open(context, uri)
            FileFormat.Text -> TextDocument.open(context, uri, name)
            FileFormat.Image -> ImageDocument.open(context, uri)
            FileFormat.Bundle -> BundleDocument.open(context, uri)
            FileFormat.Markdown -> try {
                MarkdownDocument.open(context, uri)
            } catch (e: DocumentOpenException) {
                if (e.reason != DocumentOpenException.Reason.TooLarge) throw e
                TextDocument.open(context, uri, name, notice = "파일이 커서 마크다운 미리보기를 지원하지 않습니다. 원문으로 표시합니다.")
            }
            FileFormat.Html -> try {
                HtmlDocument.open(context, uri)
            } catch (e: DocumentOpenException) {
                if (e.reason != DocumentOpenException.Reason.TooLarge) throw e
                TextDocument.open(context, uri, name, notice = "파일이 커서 HTML 미리보기를 지원하지 않습니다. 원문으로 표시합니다.")
            }
            FileFormat.Epub -> EpubDocument.open(context, uri)
            FileFormat.Docx, FileFormat.Hwpx, FileFormat.Hwp, FileFormat.Pptx, FileFormat.Xlsx -> OfficeDocuments.open(context, uri, format)
            // Detected again: this version may read what the one that opened the tab could not.
            FileFormat.Unsupported -> FileFormats.detect(context, uri, name).let {
                if (it == FileFormat.Unsupported) throw DocumentOpenException(DocumentOpenException.Reason.Unsupported)
                openAs(context, uri, name, it)
            }
        }
}
