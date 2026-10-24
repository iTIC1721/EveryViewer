package com.tabpdf.doc.office

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Opens a docx, hwpx or pptx file: read it into its model, then lay it out on sheets or slides. */
object OfficeDocuments {
    suspend fun open(context: Context, uri: Uri, format: FileFormat): Document {
        val parts = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { ZipParts.read(it) } ?: throw FileNotFoundException(uri.toString())
        }
        return withContext(Dispatchers.Default) {
            when (format) {
                FileFormat.Pptx -> SlideDocument.layOut(PptxReader.read(parts))
                FileFormat.Hwpx -> OfficeLayout.layOut(HwpxReader.read(parts))
                else -> OfficeLayout.layOut(DocxReader.read(parts))
            }
        }
    }
}
