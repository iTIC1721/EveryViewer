package com.tabpdf.tabs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Tabs whose file was being opened or first drawn when the app last ended (기획서 10장). Some ends
 * can't be caught: a crash in the system's own code (PDF and picture decoders), the system ending
 * the app when memory runs out, a file whose reading never ends (and the reader ends the app).
 * Opened again at the same page on the next start, such a tab would end the app again on every
 * start — until its data is cleared, with every tab and all handwriting. So a tab is marked while
 * it opens, and one still marked at the next start waits until the reader opens it ([held]).
 */
object OpenGuard {
    /** In no_backup: what one run left is no use on another device (nor a file to open, as in files/). */
    private const val FILE = "opening"

    private var marks: OpenMarks? = null

    private val _held = MutableStateFlow<Set<String>>(emptySet())

    /** Tabs not opened by themselves: their file was being opened when the app last ended. */
    val held: StateFlow<Set<String>> = _held.asStateFlow()

    /** Reads what the last run left, before any tab is opened. */
    fun load(context: Context) {
        marks(context)
    }

    /** Lets tab [id] open again: the reader asked for it. */
    fun release(id: String) = _held.update { it - id }

    /** Marks tab [id] before its file is opened or drawn: on the disk before this returns. */
    suspend fun begin(context: Context, id: String) = withContext(Dispatchers.IO) { marks(context).change(id, 1) }

    /** Takes the mark off: the file is open and drawn, or failed in a way that didn't end the app. */
    suspend fun end(context: Context, id: String) = withContext(Dispatchers.IO) { marks(context).change(id, -1) }

    @Synchronized
    private fun marks(context: Context): OpenMarks = marks ?: OpenMarks(File(context.noBackupFilesDir, FILE)).also {
        marks = it
        _held.value = it.previous
    }
}

/** The marks of one run of the app, kept in [file] ([OpenGuard]). */
class OpenMarks(private val file: File) {
    /** Openings under way, by tab: the viewer and a thumbnail can open the same tab at once. */
    private val opening = HashMap<String, Int>()

    /** What the run before left: the tabs being opened when it ended. */
    val previous: Set<String> = try {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.toSet() else emptySet()
    } catch (_: IOException) {
        emptySet()
    }.also { file.delete() }

    @Synchronized
    fun change(id: String, by: Int) {
        val count = (opening[id] ?: 0) + by
        if (count > 0) opening[id] = count else opening.remove(id)
        if (opening.isEmpty()) file.delete() else file.writeText(opening.keys.joinToString("\n"))
    }
}
