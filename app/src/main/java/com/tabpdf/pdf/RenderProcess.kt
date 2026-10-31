package com.tabpdf.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import androidx.annotation.RequiresApi
import com.tabpdf.doc.PageSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * The app's side of [RenderService]: binds it on first use and makes its calls. A call that takes
 * longer than [TIMEOUT_MS] unbinds, which ends the isolated process (Android ends one nothing is
 * bound to), so a file that never finishes drawing can't hold the app; Java threads can't be stopped
 * like that. Calls on a document opened in an ended process throw [Ended]; it is opened again.
 *
 * The process is started when the app starts with PDF tabs ([prepare]) or a PDF is opened, and ended
 * [IDLE_MS] after the last one is closed, so it doesn't hold its memory while only other files are open.
 *
 * What comes back is checked as from a stranger: a file that took over that process could send
 * anything (sizes, counts, numbers that aren't finite).
 */
object RenderProcess {
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val RENDER = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2
    const val SEARCH = IBinder.FIRST_CALL_TRANSACTION + 3
    const val SELECT = IBinder.FIRST_CALL_TRANSACTION + 4
    const val LINKS = IBinder.FIRST_CALL_TRANSACTION + 5
    const val WRITE = IBinder.FIRST_CALL_TRANSACTION + 6
    const val COUNT_PAGES = IBinder.FIRST_CALL_TRANSACTION + 7

    const val OK = 0
    const val FAILED = 1
    const val NEEDS_PASSWORD = 2
    const val WRONG_PASSWORD = 3

    /** Longer than any page of a real file takes (a few seconds for the heaviest). */
    private const val TIMEOUT_MS = 30_000L

    private const val IDLE_MS = 60_000L

    /** Pages a file may say it has; past it the reply is not believed. */
    private const val MAX_PAGES = 1_000_000

    /** Matches, links or pieces of text a page may have in a reply; and boxes in one of them. */
    internal const val MAX_ITEMS = 10_000
    private const val MAX_BOXES = 10_000

    /** Characters of one piece of selected text or one link. */
    private const val MAX_CHARS = 1_000_000

    /** Shared memory for pixels came in Android 8.1; on 8.0 PDFs are read in the app as before. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= 27 && enabled

    /** Off only for comparing with reading in the app (tests). */
    @Volatile var enabled = true

    /** The process a document was opened in has ended (or was ended); open it again. */
    class Ended : IOException("Render process ended")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindLock = Mutex()
    private var bound: Bound? = null

    /** Counts processes started, so a document can tell its process is no longer there. */
    private var generation = 0

    private class Bound(val context: Context, val connection: ServiceConnection, val binder: IBinder, val generation: Int) {
        /** Documents open there; under [bindLock]. */
        var documents = 0
    }

    private var idle: Job? = null

    /** What [open] gives back. */
    sealed interface Opened {
        class Document(val id: Int, val generation: Int, val sizes: List<PageSize>) : Opened
        data object NeedsPassword : Opened
        data object WrongPassword : Opened
    }

    private suspend fun bind(context: Context): Bound = bindLock.withLock {
        bound?.let { if (it.binder.isBinderAlive) return it }
        bound?.let { stop(it) }
        val app = context.applicationContext
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                connected.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName) {}

            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(Ended())
            }

            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(IOException("Render process gave nothing"))
            }
        }
        if (!app.bindService(Intent(app, RenderService::class.java), connection, Context.BIND_AUTO_CREATE)) {
            app.unbindService(connection)
            throw IOException("Render process can't start")
        }
        val binder = try {
            withTimeout(TIMEOUT_MS) { connected.await() }
        } catch (e: Throwable) {
            app.unbindService(connection)
            throw e
        }
        Bound(app, connection, binder, ++generation).also {
            bound = it
            stopWhenIdle(it)
        }
    }

    /** Under [bindLock]. */
    private fun stop(it: Bound) {
        if (bound === it) bound = null
        try {
            it.context.unbindService(it.connection)
        } catch (_: IllegalArgumentException) {
        }
    }

    /** Under [bindLock]: ends [it] after [IDLE_MS] unless a document is opened there meanwhile. */
    private fun stopWhenIdle(it: Bound) {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            bindLock.withLock { if (it.documents == 0) stop(it) }
        }
    }

    /** Starts the process ahead of the first PDF, so opening it doesn't wait for that. */
    fun prepare(context: Context) {
        if (!available) return
        scope.launch {
            try {
                bind(context)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Runs one transaction; past [TIMEOUT_MS] the process is ended and this throws [Ended]. A reply
     * other than [OK] throws [IOException].
     */
    private suspend fun <T> call(bound: Bound, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T =
        withContext(Dispatchers.IO) {
            val watchdog = scope.launch {
                delay(TIMEOUT_MS)
                android.util.Log.w("EveryViewer", "Render process stopped: took too long")
                bindLock.withLock { stop(bound) }
            }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                write(data)
                bound.binder.transact(code, data, reply, 0)
                when (reply.readInt()) {
                    OK -> read(reply)
                    NEEDS_PASSWORD -> throw PasswordReply(Opened.NeedsPassword)
                    WRONG_PASSWORD -> throw PasswordReply(Opened.WrongPassword)
                    else -> throw IOException("Render process failed")
                }
            } catch (e: RemoteException) {
                // A reply too large for Binder, say, from a process still running.
                if (bound.binder.isBinderAlive) throw IOException("Render process failed") else throw Ended()
            } finally {
                watchdog.cancel()
                data.recycle()
                reply.recycle()
            }
        }

    /** The process [generation] still running, or null once it has ended. */
    private fun running(generation: Int): Bound? = bound?.takeIf { it.generation == generation && it.binder.isBinderAlive }

    /** A transaction on [document], which starts with its id. */
    private suspend fun <T> call(document: Opened.Document, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val bound = running(document.generation) ?: throw Ended()
        return call(bound, code, { data ->
            data.writeInt(document.id)
            write(data)
        }, read)
    }

    /** Opens [fd] there (it gets its own copy of the descriptor). */
    suspend fun open(context: Context, fd: ParcelFileDescriptor, password: String?): Opened {
        val bound = bind(context)
        val opened = try {
            call(bound, OPEN, { data ->
                data.writeFileDescriptor(fd.fileDescriptor)
                data.writeString(password)
            }) { reply ->
                val id = reply.readInt()
                val numbers = reply.createFloatArray() ?: throw IOException("No page sizes")
                if (numbers.size % 2 != 0 || numbers.size / 2 > MAX_PAGES) throw IOException("Bad page sizes")
                val sizes = List(numbers.size / 2) { PageSize(numbers[it * 2], numbers[it * 2 + 1]) }
                // Sizes go into layout math.
                if (sizes.any { !(it.width > 0f && it.height > 0f && it.width.isFinite() && it.height.isFinite()) }) {
                    throw IOException("Bad page sizes")
                }
                Opened.Document(id, bound.generation, sizes)
            }
        } catch (e: PasswordReply) {
            return e.opened
        }
        bindLock.withLock {
            bound.documents++
            idle?.cancel()
        }
        return opened
    }

    /** [RenderService]'s password replies, as thrown by [call] and caught in [open]. */
    private class PasswordReply(val opened: Opened) : IOException()

    /**
     * Draws page [index] into [bitmap] (ARGB_8888, white where the page is transparent).
     * The pixels come back in memory made here, of exactly the bitmap's size.
     */
    @RequiresApi(27)
    suspend fun render(document: Opened.Document, index: Int, bitmap: Bitmap) {
        val memory = SharedMemory.create("page", bitmap.width * bitmap.height * 4)
        try {
            call(document, RENDER, { data ->
                data.writeInt(index)
                data.writeInt(bitmap.width)
                data.writeInt(bitmap.height)
                memory.writeToParcel(data, 0)
            }) { }
            val buffer = memory.mapReadOnly()
            try {
                bitmap.copyPixelsFromBuffer(buffer)
            } finally {
                SharedMemory.unmap(buffer)
            }
        } finally {
            memory.close()
        }
    }

    /** [PdfEngine.search] there. */
    suspend fun search(document: Opened.Document, index: Int, query: String): List<List<RectF>> =
        call(document, SEARCH, { data ->
            data.writeInt(index)
            data.writeString(query)
        }) { reply -> List(count(reply, MAX_ITEMS)) { reply.readBoxes() } }

    /** [PdfEngine.select] there. */
    suspend fun select(document: Opened.Document, index: Int, start: Point, stop: Point): List<Pair<String, List<RectF>>>? =
        call(document, SELECT, { data ->
            data.writeInt(index)
            data.writeInt(start.x)
            data.writeInt(start.y)
            data.writeInt(stop.x)
            data.writeInt(stop.y)
        }) { reply ->
            if (reply.readInt() == 0) null
            else List(count(reply, MAX_ITEMS)) { reply.readText() to reply.readBoxes() }
        }

    /** [PdfEngine.links] there. */
    suspend fun links(document: Opened.Document, index: Int): Pair<List<Pair<List<RectF>, String>>, List<PdfEngine.Goto>> =
        call(document, LINKS, { it.writeInt(index) }) { reply ->
            val web = List(count(reply, MAX_ITEMS)) { reply.readBoxes() to reply.readText() }
            val goto = List(count(reply, MAX_ITEMS)) { PdfEngine.Goto(reply.readBoxes(), reply.readInt(), reply.readFloat()) }
            web to goto
        }

    /** [PdfEngine.write] there, into [target]. */
    suspend fun write(document: Opened.Document, target: ParcelFileDescriptor): Boolean =
        call(document, WRITE, { it.writeFileDescriptor(target.fileDescriptor) }) { it.readInt() != 0 }

    /** [PdfEngine.countPages] there. */
    suspend fun countPages(context: Context, fd: ParcelFileDescriptor): Int {
        val bound = bind(context)
        return call(bound, COUNT_PAGES, { it.writeFileDescriptor(fd.fileDescriptor) }) { count(it, MAX_PAGES) }
    }

    suspend fun close(document: Opened.Document) {
        val bound = running(document.generation) ?: return
        try {
            call(document, CLOSE, { }) { }
        } catch (_: IOException) {
        }
        bindLock.withLock {
            if (--bound.documents == 0 && this.bound === bound) stopWhenIdle(bound)
        }
    }

    /** Ends the process (tests; and what a call that takes too long does). */
    suspend fun restart() = bindLock.withLock { bound?.let { stop(it) } }

    private fun count(reply: Parcel, max: Int): Int =
        reply.readInt().also { if (it !in 0..max) throw IOException("Bad count") }

    private fun Parcel.readBoxes(): List<RectF> = List(count(this, MAX_BOXES)) {
        RectF(readFloat(), readFloat(), readFloat(), readFloat()).also {
            if (!(it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite())) throw IOException("Bad box")
        }
    }

    private fun Parcel.readText(): String =
        (readString() ?: throw IOException("No text")).also { if (it.length > MAX_CHARS) throw IOException("Text too long") }

    /** [RenderService]'s side of [readBoxes]. */
    internal fun Parcel.writeBoxes(boxes: List<RectF>) {
        val kept = boxes.take(MAX_BOXES)
        writeInt(kept.size)
        for (box in kept) {
            writeFloat(box.left)
            writeFloat(box.top)
            writeFloat(box.right)
            writeFloat(box.bottom)
        }
    }
}
