package com.tabpdf.tabs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.tabpdf.doc.FileFormat
import com.tabpdf.doc.FileFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A file ready to become a tab: readable now and after an app restart. */
data class OpenedFile(val uri: Uri, val name: String, val isInternalCopy: Boolean, val format: FileFormat)

/**
 * Keeps tab files readable across restarts (기획서 5.3): takes a persistable read grant, or — when
 * the provider won't give one — copies the file into app storage.
 */
object FileAccess {
    private const val COPY_DIR = "tab-copies"

    suspend fun prepare(context: Context, uri: Uri): OpenedFile = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val format = FileFormats.detect(context, uri, name)
        if (uri.scheme == "file") return@withContext OpenedFile(uri, name, isInternalCopy = false, format)
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            OpenedFile(uri, name, isInternalCopy = false, format)
        } catch (_: SecurityException) {
            OpenedFile(copyToAppStorage(context, uri, name), name, isInternalCopy = true, format)
        }
    }

    /** Frees what [tab] held, once no tab uses its file any more. */
    suspend fun release(context: Context, tab: DocTab) = withContext(Dispatchers.IO) {
        if (tab.isInternalCopy) {
            tab.uri.path?.let { File(it).delete() }
        } else if (tab.uri.scheme == "content") {
            try {
                context.contentResolver.releasePersistableUriPermission(tab.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Never had a persistable grant.
            }
        }
    }

    /**
     * Frees grants and copies no tab uses any more. Closing a tab only frees its file once undo is no
     * longer possible, so if the app died in between, this catches what was left behind.
     *
     * Only what is older than [before] is touched: a file opened while the saved tabs were still
     * loading (e.g. shared from another app at a cold start) is not in [inUse] yet, but is not left over.
     */
    suspend fun releaseUnused(context: Context, inUse: Set<Uri>, before: Long) = withContext(Dispatchers.IO) {
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (permission.uri !in inUse && permission.persistedTime < before) {
                context.contentResolver.releasePersistableUriPermission(
                    permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        File(context.filesDir, COPY_DIR).listFiles()?.forEach { file ->
            if (Uri.fromFile(file) !in inUse && file.lastModified() < before) file.delete()
        }
    }

    /** Stores text shared from another app (with no file behind it) as a tab file of its own. */
    suspend fun saveSharedText(context: Context, text: String, subject: String?): OpenedFile = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, COPY_DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.txt")
        target.writeText(text)
        val title = subject?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "공유한 텍스트"
        OpenedFile(Uri.fromFile(target), "$title.txt", isInternalCopy = true, FileFormat.Text)
    }

    private fun copyToAppStorage(context: Context, uri: Uri, name: String): Uri {
        val dir = File(context.filesDir, COPY_DIR).apply { mkdirs() }
        // Keep the extension: the format is told partly by it (FileFormats.detect).
        val extension = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 10 }
        val target = File(dir, UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: throw java.io.FileNotFoundException(uri.toString())
        return Uri.fromFile(target)
    }

    private fun displayName(context: Context, uri: Uri): String {
        val queried = if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } else null
        return queried ?: uri.lastPathSegment ?: "파일"
    }
}
