package com.tabpdf.pdf

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Passwords typed for locked PDFs, by file (기획서 5.5). Kept in memory only while the app runs:
 * swiping away and back doesn't ask again, starting the app afresh does. Never written anywhere.
 */
object PdfPasswords {
    private val passwords = ConcurrentHashMap<String, String>()

    operator fun get(uri: Uri): String? = passwords[uri.toString()]

    operator fun set(uri: Uri, password: String) {
        passwords[uri.toString()] = password
    }

    fun remove(uri: Uri) {
        passwords.remove(uri.toString())
    }
}
