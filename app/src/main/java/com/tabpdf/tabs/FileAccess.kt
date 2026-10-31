package com.tabpdf.tabs

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.provider.OpenableColumns
import com.tabpdf.doc.Bundles
import com.tabpdf.doc.FileFormat
import com.tabpdf.doc.FileFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** A file ready to become a tab: readable now and after an app restart. */
data class OpenedFile(val uri: Uri, val name: String, val isInternalCopy: Boolean, val format: FileFormat)

/**
 * Keeps tab files readable across restarts (기획서 5.3): takes a persistable read grant, or — when
 * the provider won't give one — copies the file into app storage.
 */
object FileAccess {
    private const val COPY_DIR = "tab-copies"

    /**
     * Room always left on the device after a copy: the file comes from another app, whose provider
     * can send an endless stream. Any size that fits is still taken (text files have no size limit).
     */
    private const val KEEP_FREE_BYTES = 256L * 1024 * 1024

    suspend fun prepare(context: Context, uri: Uri): OpenedFile = withContext(Dispatchers.IO) {
        checkNotOwn(context, uri)
        val name = displayName(context, uri)
        val format = FileFormats.detect(context, uri, name)
        if (uri.scheme == "file") return@withContext OpenedFile(uri, name, isInternalCopy = false, format)
        // Android keeps a limited number of grants per app and silently drops the oldest past that:
        // the files of tabs opened long ago. Another app can send hundreds of files at once to make
        // that happen, so past our own share of the limit, files are copied instead.
        val grants = context.contentResolver.persistedUriPermissions
        if (grants.size >= maxGrants(context) && grants.none { it.uri == uri }) {
            return@withContext OpenedFile(copyToAppStorage(context, uri, name), name, isInternalCopy = true, format)
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            OpenedFile(uri, name, isInternalCopy = false, format)
        } catch (_: SecurityException) {
            OpenedFile(copyToAppStorage(context, uri, name), name, isInternalCopy = true, format)
        }
    }

    /**
     * Throws unless [uri] is another app's file. One of this app's own providers (its export folder,
     * where a locked PDF's unencrypted copy is made while it is exported) would be read with this
     * app's rights and kept as a copy; a file: address only the debug hook's own files may use.
     */
    private fun checkNotOwn(context: Context, uri: Uri) {
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                val authority = uri.authority ?: throw SecurityException("No provider")
                if (context.packageManager.resolveContentProvider(authority, 0)?.packageName == context.packageName) {
                    throw SecurityException("This app's own file")
                }
            }
            ContentResolver.SCHEME_FILE -> {
                val file = File(requireNotNull(uri.path)).canonicalFile
                if (file.parentFile != context.filesDir.canonicalFile) throw SecurityException("Not a test file")
            }
            else -> throw SecurityException("Not a file")
        }
    }

    /**
     * The file [uri] points at if it is one the app made itself (a copy, a bundle's list), else null:
     * the address comes from the saved session, and only those may be deleted.
     */
    fun ownFile(context: Context, uri: Uri): File? {
        if (uri.scheme != ContentResolver.SCHEME_FILE) return null
        val file = File(uri.path ?: return null).canonicalFile
        val dirs = listOf(File(context.filesDir, COPY_DIR), Bundles.dir(context)).map { it.canonicalFile }
        return file.takeIf { f -> f.parentFile?.let { it in dirs } == true }
    }

    /** Frees what [tab] held, once no tab uses its file any more. */
    suspend fun release(context: Context, tab: DocTab) = release(context, tab.uri, tab.isInternalCopy)

    /** Frees file [uri] (a copy is deleted, a grant given back), once nothing uses it any more. */
    suspend fun release(context: Context, uri: Uri, isInternalCopy: Boolean) = withContext(Dispatchers.IO) {
        if (isInternalCopy) {
            ownFile(context, uri)?.delete()
        } else if (uri.scheme == "content") {
            try {
                context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Never had a persistable grant.
            }
        }
    }

    /**
     * Every file the given tabs use: their own, and for a bundle (기획서 5.1), the files it lists.
     * A file in a bundle and in a tab of its own (or in two bundles) is freed only when none is left.
     */
    fun filesInUse(tabs: List<Pair<Uri, FileFormat>>): Set<Uri> = buildSet {
        for ((uri, format) in tabs) {
            add(uri)
            if (format == FileFormat.Bundle) {
                try {
                    Bundles.read(uri).forEach { add(it.uri) }
                } catch (_: Exception) {
                    // A broken list holds nothing.
                }
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
        Bundles.dir(context).listFiles()?.forEach { file ->
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
        // Keep the extension: the format is told partly by it (FileFormats.detect). The name comes
        // from the other app, so only plain letters and digits go into the file name.
        val extension = name.substringAfterLast('.', "").takeIf { e ->
            e.isNotEmpty() && e.length <= 10 && e.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }
        val target = File(dir, UUID.randomUUID().toString() + (extension?.let { ".$it" } ?: ""))
        copy(context, uri, target)
        return Uri.fromFile(target)
    }

    /** Copies [uri] into [target], always leaving [KEEP_FREE_BYTES] free on the device. */
    fun copy(context: Context, uri: Uri, target: File) {
        val limit = StatFs(requireNotNull(target.parentFile).path).availableBytes - KEEP_FREE_BYTES
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > limit) throw IOException("Too large to copy")
                        output.write(buffer, 0, n)
                    }
                }
            } ?: throw java.io.FileNotFoundException(uri.toString())
        } catch (e: Throwable) {
            // Half a copy is no use, and would hold the space until the next start (releaseUnused).
            target.delete()
            throw e
        }
    }

    /**
     * Persisted grants this app takes for itself: below the system's limit (128 on older Android
     * versions, more on newer ones), with room left for files picked in other ways.
     */
    private fun maxGrants(context: Context): Int {
        val lowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || lowRam) 100 else 400
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
