package com.tabpdf.doc.office

import android.content.Context
import android.net.Uri
import com.tabpdf.doc.Document
import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.readUpTo
import com.tabpdf.doc.FileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Opens a docx, hwpx, hwp, pptx or xlsx file: read it into its model, then lay it out on sheets or slides. */
object OfficeDocuments {
    /**
     * Largest .hwp read. Zip-based formats are limited part by part (ZipParts); hwplib takes the
     * whole file and holds it more than once while reading, so a quarter of what the app may use.
     */
    private val MAX_HWP_BYTES = minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4).toInt()

    suspend fun open(context: Context, uri: Uri, format: FileFormat): Document {
        // Binary Hangul files are not zips; hwplib reads the stream itself, all of it into memory.
        if (format == FileFormat.Hwp) {
            val doc = withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readUpTo(MAX_HWP_BYTES + 1) }
                    ?: throw FileNotFoundException(uri.toString())
                if (bytes.size > MAX_HWP_BYTES) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
                HwpReader.read(bytes.inputStream())
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
