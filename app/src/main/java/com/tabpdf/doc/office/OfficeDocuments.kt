package com.tabpdf.doc.office

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Opens a docx or hwpx file: read it into the shared model, then lay it out on sheets. */
object OfficeDocuments {
    suspend fun open(context: Context, uri: Uri, format: FileFormat): Document {
        val parts = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { ZipParts.read(it) } ?: throw FileNotFoundException(uri.toString())
        }
        return withContext(Dispatchers.Default) {
            val document = when (format) {
                FileFormat.Hwpx -> HwpxReader.read(parts)
                else -> DocxReader.read(parts)
            }
            OfficeLayout.layOut(document)
        }
    }
}
