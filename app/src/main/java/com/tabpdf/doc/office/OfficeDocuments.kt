package com.tabpdf.doc.office

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Opens a docx, hwpx, hwp, pptx or xlsx file: read it into its model, then lay it out on sheets or slides. */
object OfficeDocuments {
    suspend fun open(context: Context, uri: Uri, format: FileFormat): Document {
        // Binary Hangul files are not zips; hwplib reads the stream itself.
        if (format == FileFormat.Hwp) {
            val doc = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { HwpReader.read(it) } ?: throw FileNotFoundException(uri.toString())
            }
            return withContext(Dispatchers.Default) { OfficeLayout.layOut(doc) }
        }
        val parts = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { ZipParts.read(it) } ?: throw FileNotFoundException(uri.toString())
        }
        return withContext(Dispatchers.Default) {
            when (format) {
                FileFormat.Pptx -> SlideDocument.layOut(PptxReader.read(parts))
                FileFormat.Hwpx -> OfficeLayout.layOut(HwpxReader.read(parts))
                FileFormat.Xlsx -> WorkbookDocument.of(XlsxReader.read(parts))
                else -> OfficeLayout.layOut(DocxReader.read(parts))
            }
        }
    }
}
