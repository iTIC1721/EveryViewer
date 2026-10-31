package com.tabpdf.doc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.StaticLayout
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A text file of any size (기획서 5.7), shown while it is still being laid out. The file is read
 * from the start and cut into chunks of lines ([TextDocument.chunkEnd]); each chunk is laid out and
 * the pages are cut as the chunks come in, so the first page shows at once. Of each chunk only its
 * height and where it starts in the file are kept: chunks are read and laid out again when drawn,
 * so the file is never held whole. The pages are the ones [TextDocument.layOut] makes of the whole
 * text, so handwriting stays where it was written.
 */
class StreamedTextDocument private constructor(
    private val file: RandomFile,
    private val prose: Boolean,
    override val notice: String?,
) : Document {
    /** Where a chunk starts: a place the file can be decoded from, and how many characters after it. */
    private class Chunk(val top: Double, val height: Int, val from: Checkpoint, val skip: Int, val length: Int)

    private val chunks = ArrayList<Chunk>()
    private val pages = Boundaries()
    private val _progress = MutableStateFlow(LayoutProgress(0, complete = false))
    override val progress: StateFlow<LayoutProgress> = _progress.asStateFlow()
    private var failure: Exception? = null

    // Laying out in the background leaves cores free for drawing the pages being looked at.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism((Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1))
    )
    private val mutex = Mutex()
    private var closed = false
    private val paint = TextDocument.paint(prose)
    private val layouts = LruCache<Int, StaticLayout>(LAYOUT_CACHE)
    private val pageTexts = LruCache<Int, PageText>(6)

    /** Null until the text turns out not to be plain ASCII (any ASCII-based encoding reads that alike). */
    private var charset: Charset? = null

    override val pageGaps: Boolean get() = false

    override val pageSizes: List<PageSize> = object : AbstractList<PageSize>() {
        override val size: Int get() = pages.size - 1
        override fun get(index: Int) = PageSize(FLOW_WIDTH, (pages[index + 1] - pages[index]).toFloat())
    }

    /**
     * The encoding to decode the file with at byte [position]. While it is still unknown, null for
     * ASCII; at the first non-ASCII byte ([nonAscii]) it is guessed from a sample from there on.
     */
    @Synchronized
    private fun charsetFor(position: Long, nonAscii: Boolean): Charset? {
        charset?.let { return it }
        if (!nonAscii) return null
        val sample = ByteArray(CHARSET_SAMPLE_BYTES)
        val n = file.read(position, sample, 0, sample.size)
        return TextDecoding.charsetOf(sample.copyOf(n)).also { charset = it }
    }

    // ---- Laying out in the background ----

    private fun start(textStart: Long) {
        scope.launch {
            try {
                index(textStart)
            } catch (e: Throwable) {
                // Errors too (out of memory on an endless file): nothing above this scope catches
                // them, and one would end the app — again on every start, as the tab is reopened.
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Only the kind: messages may hold the file's address (and so its name).
                android.util.Log.w("EveryViewer", "Text read stopped: ${e.javaClass.simpleName}")
                failure = e as? Exception ?: java.io.IOException(e.javaClass.simpleName)
                // What was laid out stays readable.
                _progress.value = LayoutProgress(pageCount, complete = true)
            }
        }
    }

    /** Reads the whole file once: chunks, their layouts' heights and line positions, and the pages. */
    private suspend fun index(textStart: Long) = coroutineScope {
        val reader = TextReader(file, Checkpoint(textStart, cr = false), ::charsetFor)
        val window = StringBuilder()
        var windowStart = 0L // characters before window[0]
        // Places decoding can restart from, oldest first; the first is never after windowStart.
        val checkpoints = ArrayDeque<Pair<Long, Checkpoint>>().apply { add(0L to reader.checkpoint) }
        var atEnd = false
        val paginator = Paginator(pages, TextDocument.MAX_PAGE_HEIGHT.toDouble())
        var y = TextDocument.MARGIN.toDouble()
        val batch = mutableListOf<Pair<String, Pair<Checkpoint, Int>>>()

        suspend fun layOutBatch() {
            val laidOut = batch.map { (text, _) ->
                async { TextDocument.chunkLayout(text, prose, TextDocument.paint(prose)) }
            }.awaitAll()
            for ((i, layout) in laidOut.withIndex()) {
                val (from, skip) = batch[i].second
                val chunk = Chunk(y, layout.height, from, skip, batch[i].first.length)
                val index = synchronized(chunks) { chunks += chunk; chunks.size - 1 }
                // The first pages are likely shown right away.
                if (index < LAYOUT_CACHE / 2) layouts.put(index, layout)
                paginator.add(y)
                for (line in 1 until layout.lineCount) paginator.add(y + layout.getLineTop(line))
                y += layout.height
                paginator.add(y)
                paginator.known(y)
            }
            batch.clear()
            _progress.value = LayoutProgress(pageCount, complete = false)
        }

        while (true) {
            ensureActive()
            while (!atEnd && window.length <= 3 * TextDocument.CHUNK_CHARS) {
                if (reader.readInto(window)) checkpoints.addLast(windowStart + window.length to reader.checkpoint) else atEnd = true
            }
            val end = TextDocument.chunkEnd(window, 0)
            while (checkpoints.size > 1 && checkpoints[1].first <= windowStart) checkpoints.removeFirst()
            val (at, from) = checkpoints.first()
            batch += (if (end == null) window.toString() else window.substring(0, end)) to (from to (windowStart - at).toInt())
            if (end == null) break
            val next = if (window[end] == '\n') end + 1 else end
            window.delete(0, next)
            windowStart += next
            // The first chunk alone, so the first page shows as soon as possible.
            if (batch.size >= if (chunks.isEmpty()) 1 else LAYOUT_BATCH) layOutBatch()
        }
        layOutBatch()
        paginator.finish(y + TextDocument.MARGIN)
        _progress.value = LayoutProgress(pageCount, complete = true)
    }

    // ---- Chunks, read again when needed ----

    private fun chunk(index: Int): Chunk = synchronized(chunks) { chunks[index] }

    private fun chunkCount(): Int = synchronized(chunks) { chunks.size }

    private fun chunkText(c: Chunk): String {
        val reader = TextReader(file, c.from, ::charsetFor)
        val text = StringBuilder()
        while (text.length < c.skip + c.length && reader.readInto(text)) Unit
        // A file changed since it was read may come up short.
        return text.substring(min(c.skip, text.length), min(c.skip + c.length, text.length))
    }

    private fun layout(index: Int): StaticLayout =
        layouts[index] ?: TextDocument.chunkLayout(chunkText(chunk(index)), prose, paint).also { layouts.put(index, it) }

    /** Chunks overlapping flow positions [top, bottom), as (index, chunk). */
    private fun chunksBetween(top: Double, bottom: Double): List<Pair<Int, Chunk>> = synchronized(chunks) {
        var lo = 0
        var hi = chunks.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (chunks[mid].top + chunks[mid].height <= top) lo = mid + 1 else hi = mid
        }
        val result = mutableListOf<Pair<Int, Chunk>>()
        var i = lo
        while (i < chunks.size && chunks[i].top < bottom) {
            result += i to chunks[i]
            i++
        }
        result
    }

    // ---- Document ----

    override suspend fun renderPage(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || index >= pageCount) return@withLock null
            val top = pages[index]
            val height = pages[index + 1] - top
            val scale = widthPx / FLOW_WIDTH
            val heightPx = (height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = try {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("EveryViewer", "No memory for page $index at ${widthPx}x$heightPx", e)
                return@withLock null
            }
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            draw(index, canvas)
            bitmap
        }
    }

    override suspend fun drawPage(index: Int, canvas: Canvas): Boolean = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed || index >= pageCount) return@withLock false
            draw(index, canvas)
            true
        }
    }

    override fun paperWidth(index: Int): Float = PHONE_PAPER_WIDTH

    private fun draw(index: Int, canvas: Canvas) {
        val top = pages[index]
        val height = pages[index + 1] - top
        canvas.save()
        canvas.clipRect(0f, 0f, FLOW_WIDTH, height.toFloat())
        for ((i, c) in chunksBetween(top, top + height)) {
            canvas.save()
            // Relative to the page, so far down a long file nothing is lost to float precision.
            canvas.translate(TextDocument.MARGIN, (c.top - top).toFloat())
            layout(i).draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    override suspend fun search(query: String): List<SearchMatch> = withContext(Dispatchers.Default) {
        val q = TextSearch.normalize(query)
        val result = mutableListOf<SearchMatch>()
        if (q.isEmpty()) return@withContext result
        // Matches need pages, which need the file laid out up to them.
        awaitComplete()
        for (i in 0 until chunkCount()) {
            ensureActive()
            // A chunk at a time, so pages can be drawn in between.
            val found = mutex.withLock {
                if (closed) return@withContext result
                val layout = layouts[i] ?: chunkText(chunk(i)).takeIf { TextSearch.contains(it, q) }?.let {
                    TextDocument.chunkLayout(it, prose, paint).also { layout -> layouts.put(i, layout) }
                }
                layout?.let { TextSearch.find(it, q) }.orEmpty()
            }
            val c = chunk(i)
            for (rects in found) {
                val page = pageOf(c.top + rects.first().centerY())
                val top = pages[page]
                val height = (pages[page + 1] - top).toFloat()
                val dy = (c.top - top).toFloat()
                result += SearchMatch(page, rects.map {
                    RectF((it.left + TextDocument.MARGIN) / FLOW_WIDTH, (it.top + dy) / height, (it.right + TextDocument.MARGIN) / FLOW_WIDTH, (it.bottom + dy) / height)
                })
                if (result.size >= TextSearch.MAX_MATCHES) return@withContext result
            }
        }
        result
    }

    private fun pageText(index: Int): PageText = pageTexts[index] ?: run {
        val top = pages[index]
        val height = (pages[index + 1] - top).toFloat()
        val builder = PageText.Builder { RectF(it.left / FLOW_WIDTH, it.top / height, it.right / FLOW_WIDTH, it.bottom / height) }
        val page = builder.clipped(0f, height)
        for ((i, c) in chunksBetween(top, top + height)) {
            collectLayoutText(layout(i), page, TextDocument.MARGIN, (c.top - top).toFloat())
        }
        builder.build().also { pageTexts.put(index, it) }
    }

    override suspend fun selectText(index: Int, from: PointF, to: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed || index >= pageCount) null else pageText(index).select(from, to) }
    }

    override suspend fun selectWord(index: Int, at: PointF): TextSelection? = withContext(Dispatchers.Default) {
        mutex.withLock { if (closed || index >= pageCount) null else pageText(index).word(at) }
    }

    /** The page flow position [y] falls on. */
    private fun pageOf(y: Double): Int {
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (pages[mid] <= y) lo = mid else hi = mid
        }
        return lo.coerceIn(0, (pages.size - 2).coerceAtLeast(0))
    }

    override suspend fun close() = withContext(NonCancellable) {
        scope.cancel()
        mutex.withLock {
            closed = true
            file.close()
        }
    }

    companion object {
        /** Chunks laid out at once in the background (in parallel). */
        private const val LAYOUT_BATCH = 8

        /** Chunk layouts kept for drawing; a page spans one or two chunks. */
        private const val LAYOUT_CACHE = 32

        /** How much of the text after its first non-ASCII byte the encoding is guessed from. */
        private const val CHARSET_SAMPLE_BYTES = 4 * 1024 * 1024

        /** Opens [uri] and returns once its first page is laid out; the rest follows in the background. */
        suspend fun open(context: Context, uri: Uri, prose: Boolean, notice: String?): Document = withContext(Dispatchers.IO) {
            val file = RandomFile.open(context, uri)
            val document = StreamedTextDocument(file, prose, notice)
            try {
                val head = ByteArray(4)
                val bom = TextDecoding.bom(head.copyOf(file.read(0, head, 0, head.size)))
                document.charset = bom?.first
                document.start(bom?.second?.toLong() ?: 0L)
                document.awaitPages(1)
                if (document.pageCount == 0) throw document.failure ?: IOException("No text read")
                document
            } catch (e: Throwable) {
                document.close()
                throw e
            }
        }
    }
}

/** Where decoding can start: a byte offset at a character boundary, and whether a CR came just before. */
class Checkpoint(val position: Long, val cr: Boolean)

/**
 * Decodes a file from a [Checkpoint] into text with "\n" line ends (as [TextDocument.layOut] has
 * them), a batch of bytes at a time. [charsetFor] gives the encoding at a byte offset (see
 * [StreamedTextDocument]); while it gives null the bytes are ASCII and read as such.
 */
class TextReader(
    private val file: RandomFile,
    from: Checkpoint,
    private val charsetFor: (position: Long, nonAscii: Boolean) -> Charset?,
) {
    private var filePosition = from.position
    private var cr = from.cr
    private var atEnd = false
    private val input: ByteBuffer = ByteBuffer.allocate(BATCH_BYTES).apply { flip() }
    private val output: CharBuffer = CharBuffer.allocate(BATCH_BYTES)
    private var decoder: CharsetDecoder? = null

    /** Where the text after what has been read so far starts. */
    val checkpoint: Checkpoint get() = Checkpoint(filePosition - input.remaining(), cr)

    /** Appends the next piece of text to [out]; false once the file has no more. */
    fun readInto(out: StringBuilder): Boolean {
        while (true) {
            if (!atEnd && input.remaining() < MIN_INPUT) {
                input.compact()
                val n = file.read(filePosition, input.array(), input.position(), input.remaining())
                if (n <= 0) atEnd = true else {
                    filePosition += n
                    input.position(input.position() + n)
                }
                input.flip()
            }
            if (!input.hasRemaining()) return false
            val position = filePosition - input.remaining()
            val charset = charsetFor(position, (input.get(input.position()).toInt() and 0xFF) >= 0x80)
            if (charset == null) {
                // ASCII up to the first byte that isn't (then the encoding is settled).
                while (input.hasRemaining() && (input.get(input.position()).toInt() and 0xFF) < 0x80) {
                    append(out, input.get().toInt().toChar())
                }
                return true
            }
            val current = decoder?.takeIf { it.charset() == charset } ?: charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .also { decoder = it }
            // Decode only up to a sure character boundary: some decoders (ICU's) keep half a
            // character to themselves, and the checkpoint after this must be a place to start from.
            val limit = input.limit()
            if (!atEnd) input.limit(boundary(charset, position))
            output.clear()
            current.decode(input, output, false)
            input.limit(limit)
            output.flip()
            if (!output.hasRemaining()) {
                // Bytes at the end that make no whole character are dropped.
                if (atEnd) return false
                continue
            }
            while (output.hasRemaining()) append(out, output.get())
            return true
        }
    }

    /**
     * Where to stop decoding for now (an index into [input], whose first byte is at file offset
     * [position]): right after the last place in it that is surely between two characters, or
     * the whole input if there is none.
     */
    private fun boundary(charset: Charset, position: Long): Int {
        val start = input.position()
        val end = input.limit()
        if (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) {
            // Code units start at even offsets (after the 2-byte mark); never stop after the first
            // half of a surrogate pair.
            var at = if ((position + (end - start)) % 2 == 0L) end else end - 1
            while (at - 2 >= start) {
                val high = input.get(if (charset == Charsets.UTF_16LE) at - 1 else at - 2).toInt() and 0xFC
                if (high != 0xD8) return at
                at -= 2
            }
            return end
        }
        // A byte below 0x30 is a whole character in every encoding read here (other than UTF-16):
        // the later bytes of Korean, Chinese and Japanese characters are all higher.
        for (i in end - 1 downTo start) {
            if ((input.get(i).toInt() and 0xFF) < 0x30) return i + 1
        }
        return end
    }

    /** Line ends as "\n": CR LF and a lone CR both become one. */
    private fun append(out: StringBuilder, c: Char) {
        when {
            c == '\r' -> { out.append('\n'); cr = true }
            c == '\n' && cr -> cr = false
            else -> { out.append(c); cr = false }
        }
    }

    private companion object {
        const val BATCH_BYTES = 16 * 1024

        /** Refill before fewer bytes than the longest character are left. */
        const val MIN_INPUT = 8
    }
}

/**
 * A file read at any offset, kept open while the document is. Falls back to reopening and skipping
 * for providers whose files can't seek (a pipe).
 */
class RandomFile private constructor(
    private val context: Context,
    private val uri: Uri,
    private val descriptor: ParcelFileDescriptor?,
) : Closeable {
    private val stream = descriptor?.let { FileInputStream(it.fileDescriptor) }

    @Volatile
    private var seekable = stream != null

    @Volatile
    private var closed = false

    /** Reads up to [length] bytes at [position] into [into] from [offset]; fewer only at the end. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Closed")
        if (seekable) {
            try {
                val channel = stream!!.channel
                var done = 0
                while (done < length) {
                    val n = channel.read(ByteBuffer.wrap(into, offset + done, length - done), position + done)
                    if (n < 0) break
                    done += n
                }
                return done
            } catch (_: IOException) {
                seekable = false
            }
        }
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        return input.use {
            var skipped = 0L
            while (skipped < position) {
                val n = it.skip(position - skipped)
                if (n <= 0) {
                    if (it.read() < 0) return 0
                    skipped++
                } else {
                    skipped += n
                }
            }
            var done = 0
            while (done < length) {
                val n = it.read(into, offset + done, length - done)
                if (n < 0) break
                done += n
            }
            done
        }
    }

    override fun close() {
        closed = true
        try {
            descriptor?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): RandomFile {
            val descriptor = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: FileNotFoundException) {
                // Missing, or a provider that only streams: tell which.
                context.contentResolver.openInputStream(uri)?.close() ?: throw e
                null
            }
            return RandomFile(context, uri, descriptor)
        }
    }
}

/** Page boundaries in flow units, appended while other threads read them. */
private class Boundaries {
    @Volatile
    private var values = DoubleArray(1024)

    @Volatile
    var size = 1 // starts at 0.0
        private set

    operator fun get(index: Int): Double = values[index]

    fun add(value: Double) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size] = value
        size++
    }
}

/**
 * Cuts pages as content comes in, the same way [paginate] cuts it all at once: a page ends at the
 * last break that keeps it at least half full, else at the last break within reach, else at the
 * page height. A page's end is settled once content reaches a full page below its top.
 */
private class Paginator(private val pages: Boundaries, private val maxPage: Double) {
    /** Breaks after the current page's top, ascending. */
    private var breaks = DoubleArray(4096)
    private var count = 0
    private var top = 0.0

    fun add(y: Double) {
        if (count == breaks.size) breaks = breaks.copyOf(count * 2)
        breaks[count++] = y
    }

    /** Content (and every break in it) is known down to [y], and more follows. */
    fun known(y: Double) {
        while (y >= top + maxPage) cut()
    }

    /** All content is in, [total] tall. */
    fun finish(total: Double) {
        while (total - top > maxPage) cut()
        pages.add(maxOf(total, top + 1.0)) // even empty content is one (short) page
    }

    private fun cut() {
        val limit = top + maxPage
        val end = lastBreakIn(top + maxPage / 2, limit) ?: lastBreakIn(top, limit) ?: limit
        pages.add(end)
        top = end
        // Breaks at or above the new top can't end a page any more.
        var drop = 0
        while (drop < count && breaks[drop] <= top) drop++
        if (drop > 0) {
            breaks.copyInto(breaks, 0, drop, count)
            count -= drop
        }
    }

    /** The last break in (from, to], or null. */
    private fun lastBreakIn(from: Double, to: Double): Double? {
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (breaks[mid] > to) hi = mid else lo = mid + 1
        }
        val i = lo - 1
        return if (i >= 0 && breaks[i] > from) breaks[i] else null
    }
}
