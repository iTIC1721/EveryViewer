package com.tabpdf.pdf

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
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
 * like that. Documents opened in an ended process find out by [isRunning] and open again.
 *
 * The process is started when the app starts with PDF tabs ([prepare]) or a PDF is opened, and ended
 * [IDLE_MS] after the last one is closed, so it doesn't hold its memory while only other files are open.
 */
object RenderProcess {
    const val OPEN = IBinder.FIRST_CALL_TRANSACTION
    const val RENDER = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CLOSE = IBinder.FIRST_CALL_TRANSACTION + 2

    const val OK = 0
    const val FAILED = 1
    const val NEEDS_PASSWORD = 2
    const val WRONG_PASSWORD = 3

    /** Longer than any page of a real file takes (a few seconds for the heaviest). */
    private const val TIMEOUT_MS = 30_000L

    private const val IDLE_MS = 60_000L

    /** Pages a file may say it has; past it the reply is not believed. */
    private const val MAX_PAGES = 1_000_000

    /** Shared memory for pixels came in Android 8.1; on 8.0 PDFs are drawn in the app as before. */
    val available: Boolean get() = Build.VERSION.SDK_INT >= 27 && enabled

    /** Off only for comparing with drawing in the app (debug builds). */
    @Volatile var enabled = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bindLock = Mutex()
    private var bound: Bound? = null

    /** Counts processes started, so a document can tell its pages are no longer there. */
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
                connected.completeExceptionally(IOException("Render process ended"))
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

    /** Runs one transaction; past [TIMEOUT_MS] the process is ended and this throws. */
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
                read(reply)
            } catch (e: RemoteException) {
                throw IOException("Render process ended")
            } finally {
                watchdog.cancel()
                data.recycle()
                reply.recycle()
            }
        }

    /** The process [generation] still running, or null once it has ended. */
    private fun running(generation: Int): Bound? = bound?.takeIf { it.generation == generation && it.binder.isBinderAlive }

    /** Whether documents opened in process [generation] can still be drawn. */
    fun isRunning(generation: Int): Boolean = running(generation) != null

    suspend fun open(context: Context, fd: ParcelFileDescriptor, password: String?): Opened {
        val bound = bind(context)
        val opened = call(bound, OPEN, { data ->
            data.writeFileDescriptor(fd.fileDescriptor)
            data.writeString(password)
        }) { reply ->
            when (reply.readInt()) {
                OK -> {
                    val id = reply.readInt()
                    val numbers = reply.createFloatArray() ?: throw IOException("No page sizes")
                    if (numbers.size % 2 != 0 || numbers.size / 2 > MAX_PAGES) throw IOException("Bad page sizes")
                    val sizes = List(numbers.size / 2) { PageSize(numbers[it * 2], numbers[it * 2 + 1]) }
                    // A process that was taken over could send anything; sizes go into layout math.
                    if (sizes.any { !(it.width > 0f && it.height > 0f && it.width.isFinite() && it.height.isFinite()) }) {
                        throw IOException("Bad page sizes")
                    }
                    Triple(id, sizes, null)
                }
                NEEDS_PASSWORD -> Triple(0, null, Opened.NeedsPassword)
                WRONG_PASSWORD -> Triple(0, null, Opened.WrongPassword)
                else -> throw IOException("Not a PDF")
            }
        }
        opened.third?.let { return it }
        bindLock.withLock {
            bound.documents++
            idle?.cancel()
        }
        return Opened.Document(opened.first, bound.generation, opened.second!!)
    }

    /**
     * Draws page [index] of document [id] into [bitmap] (ARGB_8888, white where the page is
     * transparent). False if that process has ended since the document was opened.
     */
    @RequiresApi(27)
    suspend fun render(id: Int, documentGeneration: Int, index: Int, bitmap: Bitmap): Boolean {
        val bound = running(documentGeneration) ?: return false
        val bytes = bitmap.width * bitmap.height * 4
        val memory = SharedMemory.create("page", bytes)
        try {
            val ok = call(bound, RENDER, { data ->
                data.writeInt(id)
                data.writeInt(index)
                data.writeInt(bitmap.width)
                data.writeInt(bitmap.height)
                memory.writeToParcel(data, 0)
            }) { reply -> reply.readInt() == OK }
            if (!ok) throw IOException("Page not drawn")
            val buffer = memory.mapReadOnly()
            try {
                bitmap.copyPixelsFromBuffer(buffer)
            } finally {
                SharedMemory.unmap(buffer)
            }
            return true
        } finally {
            memory.close()
        }
    }

    suspend fun close(id: Int, documentGeneration: Int) {
        val bound = running(documentGeneration) ?: return
        try {
            call(bound, CLOSE, { it.writeInt(id) }) { }
        } catch (_: IOException) {
        }
        bindLock.withLock {
            if (--bound.documents == 0 && this.bound === bound) stopWhenIdle(bound)
        }
    }

    /** Ends the process (tests; and what a call that takes too long does). */
    suspend fun restart() = bindLock.withLock { bound?.let { stop(it) } }
}
