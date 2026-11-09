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
data class OpenedFile(
    val uri: Uri,
    val name: String,
    val isInternalCopy: Boolean,
    val format: FileFormat,
    /** The address it was opened from (kept on the tab to know the file again, 기획서 5.1). */
    val source: Uri? = null,
)

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
        if (uri.scheme == "file") return@withContext OpenedFile(uri, name, isInternalCopy = false, format, source = uri)
        // Android keeps a limited number of grants per app and silently drops the oldest past that:
        // the files of tabs opened long ago. Another app can send hundreds of files at once to make
        // that happen, so past our own share of the limit, files are copied instead.
        val grants = context.contentResolver.persistedUriPermissions
        if (grants.size >= maxGrants(context) && grants.none { it.uri == uri }) {
            return@withContext OpenedFile(copyToAppStorage(context, uri, name), name, isInternalCopy = true, format, uri)
        }
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            OpenedFile(uri, name, isInternalCopy = false, format, uri)
        } catch (_: SecurityException) {
            OpenedFile(copyToAppStorage(context, uri, name), name, isInternalCopy = true, format, uri)
        }
    }

    /**
     * The first of [tabs] that holds the same file as [uri] (기획서 5.1: a file opened again shows
     * its tab), or null. A tab is a candidate if it was opened from that address, or if its file is
     * as large as this one (the same file reached another way, or a tab from before addresses were
     * kept); either way the two are compared byte for byte, so a file changed since is a new one.
     * Only a tab that reads the file where it lies (not a copy) and has the same address needs no
     * comparing: it always shows the file as it is now.
     */
    suspend fun findSame(context: Context, uri: Uri, tabs: List<DocTab>): DocTab? = withContext(Dispatchers.IO) {
        try {
            checkNotOwn(context, uri)
        } catch (_: SecurityException) {
            return@withContext null
        }
        val size by lazy { size(context, uri) }
        tabs.firstOrNull { tab ->
            try {
                val sameAddress = tab.source == uri || tab.uri == uri
                if (sameAddress && !tab.isInternalCopy) return@firstOrNull true
                val tabSize = size(context, tab.uri) ?: return@firstOrNull false
                if (size == null && !sameAddress || size != null && size != tabSize) return@firstOrNull false
                sameContent(context, tab.uri, tabSize, uri)
            } catch (e: Exception) {
                // A file gone or not readable any more is not the same one.
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
        }
    }

    /** The size of file [uri] in bytes, if its provider says. */
    private fun size(context: Context, uri: Uri): Long? = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> File(requireNotNull(uri.path)).takeIf { it.isFile }?.length()
        else -> context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { n -> n >= 0 } else null
        }
    }

    /**
     * Whether [other] holds exactly the [size] bytes of [file]. Stops at the first difference, and
     * reads no more of [other] than that: another app's provider can send an endless stream.
     */
    private fun sameContent(context: Context, file: Uri, size: Long, other: Uri): Boolean {
        val resolver = context.contentResolver
        val a = resolver.openInputStream(file) ?: return false
        a.use {
            val b = resolver.openInputStream(other) ?: return false
            b.use {
                val bufA = ByteArray(64 * 1024)
                val bufB = ByteArray(bufA.size)
                var total = 0L
                while (true) {
                    val n = a.fill(bufA)
                    if (n == 0) break
                    if (b.fill(bufB, n) != n) return false
                    for (i in 0 until n) if (bufA[i] != bufB[i]) return false
                    total += n
                    if (total > size) return false
                }
                return total == size && b.read() == -1
            }
        }
    }

    /** Reads until [buf] is full ([count] bytes) or the stream ends; returns how many were read. */
    private fun java.io.InputStream.fill(buf: ByteArray, count: Int = buf.size): Int {
        var n = 0
        while (n < count) {
            val r = read(buf, n, count - n)
            if (r < 0) break
            n += r
        }
        return n
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
        val title = subject?.trim()?.takeIf { it.isNotEmpty() }?.take(40) ?: "공유된 텍스트"
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
