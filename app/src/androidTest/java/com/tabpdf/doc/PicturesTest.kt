package com.tabpdf.doc

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.pdf.RenderProcess
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pictures decoded in the isolated render process (Pictures): the same size and pixels as decoding in
 * the app, for every format the app shows, at a reduced size too, and nothing for other formats.
 */
@RunWith(AndroidJUnit4::class)
class PicturesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        RenderProcess.enabled = true
    }

    /** A picture with colours, a gradient and (for formats that have it) transparency. */
    private fun source(): Bitmap = Bitmap.createBitmap(301, 203, Bitmap.Config.ARGB_8888).apply {
        for (y in 0 until height) for (x in 0 until width) {
            setPixel(x, y, Color.argb(if (x < 20) 0 else 255, x % 256, y % 256, (x + y) % 256))
        }
    }

    private fun compressed(format: Bitmap.CompressFormat): ByteArray =
        ByteArrayOutputStream().also { source().compress(format, 90, it) }.toByteArray()

    /** 24-bit uncompressed BMP of a few pixels. */
    private fun bmp(): ByteArray {
        val w = 3
        val h = 2
        val row = (w * 3 + 3) / 4 * 4
        val size = 54 + row * h
        val b = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put('B'.code.toByte()).put('M'.code.toByte()).putInt(size).putInt(0).putInt(54)
        b.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(row * h).putInt(2835).putInt(2835).putInt(0).putInt(0)
        for (y in 0 until h) {
            for (x in 0 until w) b.put((x * 80).toByte()).put((y * 120).toByte()).put(200.toByte())
            repeat(row - w * 3) { b.put(0) }
        }
        return b.array()
    }

    /** A 2 × 2 GIF with four colours. */
    private val gif = byteArrayOf(
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x02, 0x00, 0x02, 0x00, 0x91.toByte(), 0x00, 0x00,
        0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        0x2C, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x00, 0x02, 0x03, 0x44, 0x34, 0x05, 0x00, 0x3B,
    )

    private fun formats(): Map<String, ByteArray> = mapOf(
        "png" to compressed(Bitmap.CompressFormat.PNG),
        "jpeg" to compressed(Bitmap.CompressFormat.JPEG),
        @Suppress("DEPRECATION") "webp" to compressed(Bitmap.CompressFormat.WEBP),
        "bmp" to bmp(),
        "gif" to gif,
    )

    @Test
    fun picturesDecodeAsInTheApp() {
        assumeTrue(Build.VERSION.SDK_INT >= 27)
        for ((name, bytes) in formats()) {
            for (sample in listOf(1, 2, 4)) {
                RenderProcess.enabled = false
                val localSize = Pictures.size(bytes)
                val local = Pictures.decode(bytes, localSize!!, sample)!!
                RenderProcess.enabled = true
                val size = Pictures.size(bytes)
                assertEquals(name, localSize, size)
                val remote = Pictures.decode(bytes, size!!, sample)
                assertNotNull("$name at 1/$sample", remote)
                assertEquals("$name at 1/$sample", local.width, remote!!.width)
                assertEquals("$name at 1/$sample", local.height, remote.height)
                assertEquals("$name alpha", local.hasAlpha(), remote.hasAlpha())
                assertTrue("$name at 1/$sample pixels", local.sameAs(remote))
            }
        }
    }

    /** What decoding there costs, logged as "RenderMeasure": 100 different 800 × 600 JPEGs, size and picture each. */
    @Test
    fun measure() {
        assumeTrue(Build.VERSION.SDK_INT >= 27)
        val pictures = List(100) { i ->
            val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(i * 2, 100, 200 - i)) }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        }
        fun run(): Long {
            val start = android.os.SystemClock.elapsedRealtime()
            for (bytes in pictures) Pictures.decode(bytes, Pictures.size(bytes)!!, 1)!!.recycle()
            return android.os.SystemClock.elapsedRealtime() - start
        }
        RenderProcess.enabled = false
        run()
        val local = run()
        RenderProcess.enabled = true
        run()
        val remote = run()
        val start = android.os.SystemClock.elapsedRealtime()
        for (bytes in pictures) Pictures.size(bytes)
        val sizes = android.os.SystemClock.elapsedRealtime() - start
        android.util.Log.i("RenderMeasure", "100 pictures 800x600: in app ${local}ms, render process ${remote}ms (sizes alone ${sizes}ms)")
    }

    @Test
    fun imageFilesDecodeAsInTheApp() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 27)
        val file = File(context.cacheDir, "pictures-test.jpg")
        file.writeBytes(compressed(Bitmap.CompressFormat.JPEG))
        RenderProcess.enabled = false
        val local = Pictures.decode(file, Pictures.size(file)!!, 2)!!
        RenderProcess.enabled = true
        val remote = Pictures.decode(file, Pictures.size(file)!!, 2)!!
        assertTrue(local.sameAs(remote))
        file.delete()
        Unit
    }

    /**
     * HEIF: Android decodes it through its media service, which an isolated process can't reach, so
     * it is asked for from the app (Pictures). The picture is made here with Android's HEVC encoder
     * (as androidx HeifWriter does); the Android 11 emulator has none.
     */
    @Test
    fun heifDecodesAsInTheApp() {
        assumeTrue(Build.VERSION.SDK_INT >= 28)
        val bytes = try {
            heic(256, 256)
        } catch (e: Exception) {
            android.util.Log.i("PicturesTest", "HEIC not made", e)
            null
        }
        assumeTrue("no HEVC encoder on this device", bytes != null)
        RenderProcess.enabled = false
        val localSize = Pictures.size(bytes!!)
        assumeTrue("this device can't decode HEIF at all", localSize != null)
        val local = Pictures.decode(bytes, localSize!!, 1)!!
        RenderProcess.enabled = true
        val size = Pictures.size(bytes)
        assertEquals(localSize, size)
        val remote = Pictures.decode(bytes, size!!, 1)
        assertNotNull(remote)
        assertTrue(local.sameAs(remote))
    }

    /** A [width] × [height] HEIC of two colours, or null if the device has no HEVC encoder. */
    private fun heic(width: Int, height: Int): ByteArray? {
        val format = android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC, width, height).apply {
            setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(android.media.MediaFormat.KEY_BIT_RATE, width * height * 8)
            setInteger(android.media.MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(android.media.MediaFormat.KEY_I_FRAME_INTERVAL, 0)
        }
        val codec = try {
            android.media.MediaCodec.createEncoderByType(android.media.MediaFormat.MIMETYPE_VIDEO_HEVC).apply {
                configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
        } catch (e: Exception) {
            return null
        }
        val file = File(context.cacheDir, "pictures-test.heic")
        val muxer = android.media.MediaMuxer(file.path, android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF)
        codec.start()
        val input = codec.dequeueInputBuffer(1_000_000)
        val image = codec.getInputImage(input)!!
        // Left half red-ish, right half blue-ish, in YUV.
        for ((plane, values) in image.planes.zip(listOf(intArrayOf(82, 41), intArrayOf(90, 240), intArrayOf(240, 110)))) {
            val sub = if (plane === image.planes[0]) 1 else 2
            for (y in 0 until height / sub) for (x in 0 until width / sub) {
                plane.buffer.put(y * plane.rowStride + x * plane.pixelStride, values[if (x < width / sub / 2) 0 else 1].toByte())
            }
        }
        codec.queueInputBuffer(input, 0, width * height * 3 / 2, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        val info = android.media.MediaCodec.BufferInfo()
        var track = -1
        while (true) {
            val output = codec.dequeueOutputBuffer(info, 1_000_000)
            if (output == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val heif = codec.outputFormat.apply { setString(android.media.MediaFormat.KEY_MIME, android.media.MediaFormat.MIMETYPE_IMAGE_ANDROID_HEIC) }
                track = muxer.addTrack(heif)
                muxer.start()
            } else if (output >= 0) {
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    muxer.writeSampleData(track, codec.getOutputBuffer(output)!!, info)
                }
                codec.releaseOutputBuffer(output, false)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            } else if (output == android.media.MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
        return file.readBytes().also { file.delete() }
    }

    @Test
    fun otherFormatsAreNotDecoded() {
        // TIFF (and DNG, which is TIFF inside) never reaches a decoder.
        val tiff = byteArrayOf(0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00) + ByteArray(64)
        assertNull(Pictures.size(tiff))
        assertNull(Pictures.size(ByteArray(64)))
        // AVIF in a HEIF box ("mif1" that also claims "avif") goes to another decoder: not shown.
        val avif = byteArrayOf(0, 0, 0, 0x1C) + "ftypmif1".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1avifmiaf".toByteArray() + ByteArray(64)
        assertTrue(!FileFormats.isImage(avif))
        val heif = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1heic".toByteArray() + ByteArray(64)
        assertTrue(FileFormats.isImage(heif))
    }
}
