package com.tabpdf.doc

import android.content.Context
import android.net.Uri
import com.tabpdf.pdf.PdfDocument

/** Opens a tab's file as a [Document]. */
object Documents {
    /** Throws if the file is missing, unreadable or can't be shown. */
    suspend fun open(context: Context, uri: Uri): Document = PdfDocument.open(context, uri)
}
