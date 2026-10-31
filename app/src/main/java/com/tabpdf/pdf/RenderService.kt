package com.tabpdf.pdf

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Point
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.tabpdf.pdf.RenderProcess.writeBoxes

/**
 * Reads PDFs with Android's PDF code (PDFium, [PdfEngine]) in a process of its own with no
 * permissions and no access to the app's files (`isolatedProcess` in the manifest), so a file that
 * takes over that code finds nothing there: not the tabs, the ink, the copies of files or the grants
 * to other apps' files. A crash or running out of memory ends only this process; the app sees the
 * file fail to open.
 *
 * The app passes the file's descriptor and gets each page's pixels back in memory it allocated
 * itself ([RenderProcess]). The protocol is plain [Binder] transactions, codes in [RenderProcess];
 * every reply starts with a status.
 */
@RequiresApi(27)
class RenderService : Service() {
    private class Opened(val fd: ParcelFileDescriptor, val engine: PdfEngine) {
        /** Reused while pages come at the same size (the common case: one width per tab). */
        var bitmap: Bitmap? = null
    }

    private val documents = HashMap<Int, Opened>()
    private var nextId = 1

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Only this app can bind (the service isn't exported); the check costs nothing.
            if (getCallingUid() != applicationInfo.uid || reply == null) return false
            try {
                when (code) {
                    RenderProcess.OPEN -> open(data, reply)
                    RenderProcess.COUNT_PAGES -> countPages(data, reply)
                    RenderProcess.PICTURE_SIZE -> pictureSize(data, reply)
                    RenderProcess.PICTURE -> picture(data, reply)
                    in RenderProcess.RENDER..RenderProcess.WRITE -> {
                        val opened = synchronized(documents) { documents[data.readInt()] }
                        if (opened == null) {
                            reply.writeInt(RenderProcess.FAILED)
                        } else if (code == RenderProcess.CLOSE) {
                            close(opened)
                            reply.writeInt(RenderProcess.OK)
                        } else {
                            // PdfRenderer is not thread-safe: one call at a time per document.
                            synchronized(opened) { onDocument(code, opened, data, reply) }
                        }
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
            } catch (e: Exception) {
                reply.setDataSize(0)
                reply.writeInt(RenderProcess.FAILED)
            }
            return true
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun open(data: Parcel, reply: Parcel) {
        val fd = data.readFileDescriptor() ?: return reply.writeInt(RenderProcess.FAILED)
        val password = data.readString()
        val engine = try {
            PdfEngine.open(fd, password)
        } catch (e: SecurityException) {
            fd.close()
            return reply.writeInt(if (password == null) RenderProcess.NEEDS_PASSWORD else RenderProcess.WRONG_PASSWORD)
        } catch (e: Exception) {
            fd.close()
            throw e
        }
        val sizes = engine.pageSizes
        val id = synchronized(documents) { nextId++.also { documents[it] = Opened(fd, engine) } }
        reply.writeInt(RenderProcess.OK)
        reply.writeInt(id)
        reply.writeFloatArray(FloatArray(sizes.size * 2) { if (it % 2 == 0) sizes[it / 2].width else sizes[it / 2].height })
    }

    private fun countPages(data: Parcel, reply: Parcel) {
        val fd = data.readFileDescriptor() ?: return reply.writeInt(RenderProcess.FAILED)
        val pages = fd.use { PdfEngine.countPages(it) }
        reply.writeInt(RenderProcess.OK)
        reply.writeInt(pages)
    }

    /** A picture's bytes from [RenderProcess.writeSource], decoded with [options]. */
    private fun decode(data: Parcel, options: BitmapFactory.Options): Bitmap? = when (data.readInt()) {
        RenderProcess.FROM_MEMORY -> {
            val length = data.readInt()
            SharedMemory.CREATOR.createFromParcel(data).use { memory ->
                if (length !in 0..memory.size) return null
                val buffer = memory.mapReadOnly()
                val bytes = try {
                    ByteArray(length).also { buffer.get(it) }
                } finally {
                    SharedMemory.unmap(buffer)
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }
        RenderProcess.FROM_FILE -> data.readFileDescriptor()?.use { BitmapFactory.decodeFileDescriptor(it.fileDescriptor, null, options) }
        else -> null
    }

    private fun pictureSize(data: Parcel, reply: Parcel) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(data, options)
        reply.writeInt(RenderProcess.OK)
        reply.writeInt(options.outWidth)
        reply.writeInt(options.outHeight)
    }

    /** Decoded into the app's memory, which is only as large as the app expects the picture to be. */
    private fun picture(data: Parcel, reply: Parcel) {
        val options = BitmapFactory.Options().apply {
            inSampleSize = data.readInt()
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val mostWidth = data.readInt()
        val mostHeight = data.readInt()
        SharedMemory.CREATOR.createFromParcel(data).use { memory ->
            var bitmap = decode(data, options) ?: return reply.writeInt(RenderProcess.FAILED)
            try {
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
                        ?: return reply.writeInt(RenderProcess.FAILED)
                }
                val bytes = bitmap.width.toLong() * bitmap.height * 4
                if (bitmap.width > mostWidth || bitmap.height > mostHeight || bytes > memory.size) return reply.writeInt(RenderProcess.FAILED)
                val buffer = memory.map(OsConstants.PROT_READ or OsConstants.PROT_WRITE, 0, bytes.toInt())
                try {
                    bitmap.copyPixelsToBuffer(buffer)
                } finally {
                    SharedMemory.unmap(buffer)
                }
                reply.writeInt(RenderProcess.OK)
                reply.writeInt(bitmap.width)
                reply.writeInt(bitmap.height)
                reply.writeInt(if (bitmap.hasAlpha()) 1 else 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    private fun onDocument(code: Int, opened: Opened, data: Parcel, reply: Parcel) {
        when (code) {
            RenderProcess.RENDER -> render(opened, data.readInt(), data, reply)
            RenderProcess.SEARCH -> {
                val index = data.readInt()
                val matches = opened.engine.search(index, data.readString().orEmpty()).take(RenderProcess.MAX_ITEMS)
                reply.writeInt(RenderProcess.OK)
                reply.writeInt(matches.size)
                for (boxes in matches) reply.writeBoxes(boxes)
            }
            RenderProcess.SELECT -> {
                val index = data.readInt()
                val start = Point(data.readInt(), data.readInt())
                val stop = Point(data.readInt(), data.readInt())
                val pieces = opened.engine.select(index, start, stop)?.take(RenderProcess.MAX_ITEMS)
                reply.writeInt(RenderProcess.OK)
                reply.writeInt(if (pieces == null) 0 else 1)
                if (pieces != null) {
                    reply.writeInt(pieces.size)
                    for ((text, boxes) in pieces) {
                        reply.writeString(text)
                        reply.writeBoxes(boxes)
                    }
                }
            }
            RenderProcess.LINKS -> {
                val (web, goto) = opened.engine.links(data.readInt())
                reply.writeInt(RenderProcess.OK)
                reply.writeInt(web.size.coerceAtMost(RenderProcess.MAX_ITEMS))
                for ((boxes, url) in web.take(RenderProcess.MAX_ITEMS)) {
                    reply.writeBoxes(boxes)
                    reply.writeString(url)
                }
                reply.writeInt(goto.size.coerceAtMost(RenderProcess.MAX_ITEMS))
                for (link in goto.take(RenderProcess.MAX_ITEMS)) {
                    reply.writeBoxes(link.bounds)
                    reply.writeInt(link.page)
                    reply.writeFloat(link.y)
                }
            }
            RenderProcess.WRITE -> {
                val target = data.readFileDescriptor() ?: return reply.writeInt(RenderProcess.FAILED)
                val written = target.use { opened.engine.write(it) }
                reply.writeInt(RenderProcess.OK)
                reply.writeInt(if (written) 1 else 0)
            }
            else -> reply.writeInt(RenderProcess.FAILED)
        }
    }

    private fun render(opened: Opened, index: Int, data: Parcel, reply: Parcel) {
        val width = data.readInt()
        val height = data.readInt()
        SharedMemory.CREATOR.createFromParcel(data).use { memory ->
            if (width <= 0 || height <= 0 || width.toLong() * height * 4 > memory.size) return reply.writeInt(RenderProcess.FAILED)
            val bitmap = opened.bitmap?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                    opened.bitmap?.recycle()
                    opened.bitmap = it
                }
            // PDF pages are transparent by default; paper should be white.
            bitmap.eraseColor(Color.WHITE)
            opened.engine.render(index, bitmap)
            val buffer = memory.map(OsConstants.PROT_READ or OsConstants.PROT_WRITE, 0, width * height * 4)
            try {
                bitmap.copyPixelsToBuffer(buffer)
            } finally {
                SharedMemory.unmap(buffer)
            }
        }
        reply.writeInt(RenderProcess.OK)
    }

    private fun close(opened: Opened) {
        synchronized(documents) { documents.values.remove(opened) }
        synchronized(opened) {
            opened.engine.close()
            opened.fd.close()
            opened.bitmap?.recycle()
            opened.bitmap = null
        }
    }
}
