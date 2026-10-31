package com.tabpdf.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi

/**
 * Draws PDF pages in a process of its own with no permissions and no access to the app's files
 * (`isolatedProcess` in the manifest), so a file that takes over Android's PDF code (PDFium) finds
 * nothing there: not the tabs, the ink, the copies of files or the grants to other apps' files.
 * A crash or running out of memory ends only this process; the app sees the file fail to open.
 *
 * The app passes the file's descriptor and gets each page's pixels back in memory it allocated
 * itself ([RenderProcess]). The protocol is plain [Binder] transactions, codes in [RenderProcess].
 */
@RequiresApi(27)
class RenderService : Service() {
    private class Opened(val fd: ParcelFileDescriptor, val renderer: AutoCloseable) {
        /** Reused while pages come at the same size (the common case: one width per tab). */
        var bitmap: Bitmap? = null
    }

    private val documents = HashMap<Int, Opened>()
    private var nextId = 1

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Only this app can bind (the service isn't exported); the check costs nothing.
            if (getCallingUid() != applicationInfo.uid) return false
            when (code) {
                RenderProcess.OPEN -> open(data, reply ?: return false)
                RenderProcess.RENDER -> render(data, reply ?: return false)
                RenderProcess.CLOSE -> close(data.readInt())
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun open(data: Parcel, reply: Parcel) {
        val fd = data.readFileDescriptor() ?: return reply.writeInt(RenderProcess.FAILED)
        val password = data.readString()
        val renderer = try {
            PdfDocument.openRenderer(fd, password)
        } catch (e: SecurityException) {
            fd.close()
            return reply.writeInt(if (password == null) RenderProcess.NEEDS_PASSWORD else RenderProcess.WRONG_PASSWORD)
        } catch (e: Exception) {
            fd.close()
            return reply.writeInt(RenderProcess.FAILED)
        }
        val sizes = try {
            PdfDocument.pageSizesOf(renderer)
        } catch (e: Exception) {
            renderer.close()
            fd.close()
            return reply.writeInt(RenderProcess.FAILED)
        }
        val id = synchronized(documents) { nextId++.also { documents[it] = Opened(fd, renderer) } }
        reply.writeInt(RenderProcess.OK)
        reply.writeInt(id)
        reply.writeFloatArray(FloatArray(sizes.size * 2) { if (it % 2 == 0) sizes[it / 2].width else sizes[it / 2].height })
    }

    private fun render(data: Parcel, reply: Parcel) {
        val id = data.readInt()
        val index = data.readInt()
        val width = data.readInt()
        val height = data.readInt()
        val memory = SharedMemory.CREATOR.createFromParcel(data)
        try {
            val opened = synchronized(documents) { documents[id] } ?: return reply.writeInt(RenderProcess.FAILED)
            if (width <= 0 || height <= 0 || width.toLong() * height * 4 > memory.size) return reply.writeInt(RenderProcess.FAILED)
            // PdfRenderer is not thread-safe; each document's pages are drawn one at a time.
            synchronized(opened) {
                val bitmap = opened.bitmap?.takeIf { it.width == width && it.height == height }
                    ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        opened.bitmap?.recycle()
                        opened.bitmap = it
                    }
                // PDF pages are transparent by default; paper should be white.
                bitmap.eraseColor(Color.WHITE)
                PdfDocument.renderInto(opened.renderer, index, bitmap)
                val buffer = memory.map(OsConstants.PROT_READ or OsConstants.PROT_WRITE, 0, width * height * 4)
                try {
                    bitmap.copyPixelsToBuffer(buffer)
                } finally {
                    SharedMemory.unmap(buffer)
                }
            }
            reply.writeInt(RenderProcess.OK)
        } catch (e: Exception) {
            reply.writeInt(RenderProcess.FAILED)
        } finally {
            memory.close()
        }
    }

    private fun close(id: Int) {
        val opened = synchronized(documents) { documents.remove(id) } ?: return
        synchronized(opened) {
            opened.renderer.close()
            opened.fd.close()
            opened.bitmap?.recycle()
            opened.bitmap = null
        }
    }
}
