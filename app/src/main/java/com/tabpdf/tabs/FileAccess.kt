package com.tabpdf.tabs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A file ready to become a tab: readable now and after an app restart. */
data class OpenedFile(val uri: Uri, val name: String, val isInternalCopy: Boolean)

/**
 * Keeps tab files readable across restarts (기획서 5.3): takes a persistable read grant, or — when
 * the provider won't give one — copies the file into app storage.
 */
object FileAccess {
    private const val COPY_DIR = "tab-copies"

    suspend fun prepare(context: Context, uri: Uri): OpenedFile = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        if (uri.scheme == "file") return@withContext OpenedFile(uri, name, isInternalCopy = false)
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            OpenedFile(uri, name, isInternalCopy = false)
        } catch (_: SecurityException) {
            OpenedFile(copyToAppStorage(context, uri), name, isInternalCopy = true)
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
     */
    suspend fun releaseUnused(context: Context, inUse: Set<Uri>) = withContext(Dispatchers.IO) {
        for (permission in context.contentResolver.persistedUriPermissions) {
            if (permission.uri !in inUse) {
                context.contentResolver.releasePersistableUriPermission(
                    permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        File(context.filesDir, COPY_DIR).listFiles()?.forEach { file ->
            if (Uri.fromFile(file) !in inUse) file.delete()
        }
    }

    private fun copyToAppStorage(context: Context, uri: Uri): Uri {
        val dir = File(context.filesDir, COPY_DIR).apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.pdf")
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
        return queried ?: uri.lastPathSegment ?: "PDF"
    }
}
