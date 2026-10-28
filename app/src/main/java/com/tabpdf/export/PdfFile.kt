package com.tabpdf.export

import java.io.RandomAccessFile

/**
 * A PDF file's structure, read as far as adding to its pages needs (ISO 32000-1, 7.5): the
 * cross-reference sections from the newest back (tables and streams, 7.5.4 and 7.5.8), objects in
 * object streams (7.5.7), and the page tree with inherited attributes (7.7.3). Anything it doesn't
 * understand is a [PdfSyntaxException]; the caller then exports the pages as pictures instead.
 */
class PdfFile(file: RandomAccessFile) {
    private sealed interface Entry
    private class InFile(val offset: Long, val gen: Int) : Entry
    private class InStream(val stream: Int, val index: Int) : Entry
    private object Free : Entry

    val bytes = PdfBytes(file)
    private val entries = HashMap<Int, Entry>()

    /** The newest trailer (for an xref stream, its dictionary). */
    val trailer: PdfDict

    /** Where the newest cross-reference section starts: the next update's /Prev. */
    val lastXref: Long

    /** Whether the newest section is a stream; an update then writes one too. */
    val usesXrefStream: Boolean

    /** One more than the highest object number in use. */
    val size: Int

    /**
     * Set for an encrypted file, before reading anything but the trailer: objects' strings and
     * streams are then decrypted as they are read.
     */
    var crypt: PdfCrypt? = null

    private val cache = HashMap<Int, Any>()
    private val objectStreams = HashMap<Int, Pair<ByteArray, IntArray>>()
    private val opening = HashSet<Int>()

    /** Cross-reference stream data read so far (MAX_XREF_BYTES). */
    private var xrefBytes = 0L

    init {
        lastXref = findStartXref()
        var newest: PdfDict? = null
        var newestIsStream = false
        var maxSize = 0
        var offset: Long? = lastXref
        val seen = HashSet<Long>()
        while (offset != null) {
            if (!seen.add(offset) || seen.size > MAX_SECTIONS) break
            val lexer = PdfLexer(bytes::get, offset)
            val dict: PdfDict
            val isStream: Boolean
            if (lexer.peekWord() == "xref") {
                lexer.nextWord()
                readTable(lexer)
                lexer.expectWord("trailer")
                dict = lexer.readObject() as? PdfDict ?: throw PdfSyntaxException("Trailer is not a dictionary")
                // A hybrid file's stream lists what older readers don't see; the table wins.
                (dict["XRefStm"] as? PdfNumber)?.let { readXrefStream(it.value.toLong()) }
                isStream = false
            } else {
                dict = readXrefStream(offset)
                isStream = true
            }
            if (newest == null) {
                newest = dict
                newestIsStream = isStream
            }
            maxSize = maxOf(maxSize, (dict["Size"] as? PdfNumber)?.value?.toInt() ?: 0)
            offset = (dict["Prev"] as? PdfNumber)?.value?.toLong()
        }
        trailer = newest ?: throw PdfSyntaxException("No trailer")
        usesXrefStream = newestIsStream
        size = maxOf(maxSize, (entries.keys.maxOrNull() ?: 0) + 1)
    }

    private fun findStartXref(): Long {
        val tailStart = maxOf(0L, bytes.length - 2048)
        val tail = String(bytes.read(tailStart, (bytes.length - tailStart).toInt()), Charsets.ISO_8859_1)
        val at = tail.lastIndexOf("startxref")
        if (at < 0) throw PdfSyntaxException("No startxref")
        val lexer = PdfLexer(bytes::get, tailStart + at + "startxref".length)
        return lexer.nextWord().toLongOrNull()?.takeIf { it in 0 until bytes.length }
            ?: throw PdfSyntaxException("Bad startxref")
    }

    private fun readTable(lexer: PdfLexer) {
        while (lexer.peekWord() != "trailer") {
            val start = lexer.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Bad xref subsection")
            val count = lexer.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Bad xref subsection")
            if (start < 0 || count < 0 || count > MAX_OBJECTS) throw PdfSyntaxException("Bad xref subsection")
            for (i in 0 until count) {
                val offset = lexer.nextWord().toLongOrNull() ?: throw PdfSyntaxException("Bad xref entry")
                val gen = lexer.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Bad xref entry")
                val entry = when (lexer.nextWord()) {
                    "n" -> InFile(offset, gen)
                    "f" -> Free
                    else -> throw PdfSyntaxException("Bad xref entry")
                }
                entries.putIfAbsent(start + i, entry)
            }
        }
    }

    private fun readXrefStream(offset: Long): PdfDict {
        val (_, stream) = readIndirect(offset)
        if (stream !is PdfStream) throw PdfSyntaxException("Cross-reference stream expected")
        val dict = stream.dict
        val w = (dict["W"] as? List<*>)?.map { (it as? PdfNumber)?.value?.toInt() ?: -1 }
        if (w == null || w.size != 3 || w.any { it !in 0..8 }) throw PdfSyntaxException("Bad /W")
        val sizeAll = (dict["Size"] as? PdfNumber)?.value?.toInt() ?: throw PdfSyntaxException("No /Size")
        val index = (dict["Index"] as? List<*>)?.map { (it as? PdfNumber)?.value?.toInt() ?: -1 } ?: listOf(0, sizeAll)
        // An entry with no offset field says nothing; with all fields empty, rows take no bytes and
        // nothing below would bound the loop.
        if (w[1] == 0) throw PdfSyntaxException("Bad /W")
        val pairs = index.chunked(2)
        for (pair in pairs) {
            if (pair.size != 2 || pair[0] < 0 || pair[1] !in 0..MAX_OBJECTS) throw PdfSyntaxException("Bad /Index")
        }
        val data = streamData(stream)
        // Up to MAX_SECTIONS of them, each decoding up to MAX_STREAM: real ones are a few kilobytes.
        xrefBytes += data.size
        if (xrefBytes > MAX_XREF_BYTES) throw PdfSyntaxException("Cross-reference streams too large")
        val rowLength = w.sum()
        // Every row listed must be in the data: the work is bounded by its size.
        if (pairs.sumOf { it[1].toLong() } * rowLength > data.size) throw PdfSyntaxException("Cross-reference stream too short")
        var row = 0
        for (pair in pairs) {
            for (i in 0 until pair[1]) {
                val at = row++ * rowLength
                if (at + rowLength > data.size) throw PdfSyntaxException("Cross-reference stream too short")
                fun field(k: Int, default: Long): Long {
                    if (w[k] == 0) return default
                    var v = 0L
                    val start = at + w.take(k).sum()
                    for (b in 0 until w[k]) v = (v shl 8) or (data[start + b].toLong() and 0xFF)
                    return v
                }
                val entry = when (field(0, 1)) {
                    0L -> Free
                    1L -> InFile(field(1, 0), field(2, 0).toInt())
                    2L -> InStream(field(1, 0).toInt(), field(2, 0).toInt())
                    else -> null // unknown types are as if null (7.5.8.3)
                }
                if (entry != null) entries.putIfAbsent(pair[0] + i, entry)
            }
        }
        return dict
    }

    /** "n g obj" at [offset], and the object after it. */
    private fun readIndirect(offset: Long): Pair<PdfRef, Any> {
        val lexer = PdfLexer(bytes::get, offset)
        val num = lexer.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Object expected at $offset")
        val gen = lexer.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Object expected at $offset")
        lexer.expectWord("obj")
        val ref = PdfRef(num, gen)
        val value = lexer.readObject()
        if (value is PdfStream) value.ref = ref
        return ref to value
    }

    /** The object [ref] points to, or [PdfNull] for a free or missing one. */
    fun get(ref: PdfRef): Any {
        cache[ref.num]?.let { return it }
        val value = when (val entry = entries[ref.num]) {
            is InFile -> {
                val (found, value) = readIndirect(entry.offset)
                if (found.num != ref.num) throw PdfSyntaxException("Object ${ref.num} is not where the table says")
                // Strings in an object stream were decrypted with the stream.
                crypt?.let { decryptStrings(value, it, found) } ?: value
            }
            is InStream -> fromObjectStream(entry.stream, entry.index)
            else -> PdfNull
        }
        if (cache.size > MAX_CACHE) cache.clear()
        cache[ref.num] = value
        return value
    }

    private fun decryptStrings(value: Any, crypt: PdfCrypt, ref: PdfRef): Any = when (value) {
        is PdfString -> PdfString(crypt.decryptString(ref.num, ref.gen, value.bytes))
        is PdfStream -> value.also { s -> for ((k, v) in s.dict) s.dict[k] = decryptStrings(v, crypt, ref) }
        is MutableMap<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            val map = value as MutableMap<String, Any>
            for ((k, v) in map) map[k] = decryptStrings(v, crypt, ref)
            map
        }
        is MutableList<*> -> {
            @Suppress("UNCHECKED_CAST")
            val list = value as MutableList<Any>
            for (i in list.indices) list[i] = decryptStrings(list[i], crypt, ref)
            list
        }
        else -> value
    }

    /** [value], or what it points to. */
    fun resolve(value: Any?): Any? {
        var v = value
        var hops = 0
        while (v is PdfRef) {
            if (++hops > 32) throw PdfSyntaxException("Reference loop")
            v = get(v)
        }
        return v
    }

    private fun fromObjectStream(streamNum: Int, index: Int): Any {
        val (data, offsets) = objectStreams.getOrPut(streamNum) {
            // An object stream is never inside another (7.5.7): a crafted file could make it its own.
            if (entries[streamNum] !is InFile) throw PdfSyntaxException("Object stream not in the file")
            // Its /Length may not be one of its own objects.
            if (!opening.add(streamNum)) throw PdfSyntaxException("Object stream needs itself")
            val stream = get(PdfRef(streamNum, 0)) as? PdfStream ?: throw PdfSyntaxException("Object stream expected")
            val n = (stream.dict["N"] as? PdfNumber)?.value?.toInt() ?: throw PdfSyntaxException("No /N")
            val first = (stream.dict["First"] as? PdfNumber)?.value?.toInt() ?: throw PdfSyntaxException("No /First")
            if (n !in 0..MAX_OBJECTS) throw PdfSyntaxException("Bad /N")
            val data = streamData(stream)
            val header = PdfLexer(data)
            val offsets = IntArray(n) {
                header.nextWord()
                first + (header.nextWord().toIntOrNull() ?: throw PdfSyntaxException("Bad object stream"))
            }
            opening -= streamNum
            if (objectStreams.size > 4) objectStreams.clear()
            data to offsets
        }
        if (index !in offsets.indices) throw PdfSyntaxException("Object not in its stream")
        return PdfLexer(data, offsets[index].toLong()).readObject()
    }

    /** A stream's data, decoded; only FlateDecode (or none) is needed for structure. */
    fun streamData(stream: PdfStream): ByteArray {
        val length = (resolve(stream.dict["Length"]) as? PdfNumber)?.value?.toLong()
            ?: throw PdfSyntaxException("Stream without /Length")
        if (length < 0 || stream.dataStart + length > bytes.length || length > MAX_STREAM) throw PdfSyntaxException("Bad stream length")
        var raw = bytes.read(stream.dataStart, length.toInt())
        val ref = stream.ref
        // Cross-reference streams are never encrypted (7.6.2).
        if (crypt != null && ref != null && (stream.dict["Type"] as? PdfName)?.text != "XRef") raw = crypt!!.decryptStream(ref.num, ref.gen, raw)
        val filter = resolve(stream.dict["Filter"])
        val filters = when (filter) {
            null, PdfNull -> emptyList()
            is PdfName -> listOf(filter.text)
            is List<*> -> filter.map { (it as? PdfName)?.text ?: throw PdfSyntaxException("Bad /Filter") }
            else -> throw PdfSyntaxException("Bad /Filter")
        }
        return when {
            filters.isEmpty() -> raw
            filters == listOf("FlateDecode") -> {
                val parms = resolve(stream.dict["DecodeParms"]).let { if (it is List<*>) resolve(it.firstOrNull()) else it } as? PdfDict
                Flate.decode(raw, parms, MAX_STREAM.toInt())
            }
            else -> throw PdfSyntaxException("Unsupported filter $filters")
        }
    }

    /** A page and what it inherits from the tree above it. */
    class Page(
        val ref: PdfRef,
        val dict: PdfDict,
        val mediaBox: FloatArray,
        val cropBox: FloatArray?,
        val rotate: Int,
        val resources: Any?,
    )

    /** The pages in order. */
    fun pages(): List<Page> {
        val catalog = resolve(trailer["Root"]) as? PdfDict ?: throw PdfSyntaxException("No catalog")
        val root = catalog["Pages"] as? PdfRef ?: throw PdfSyntaxException("No page tree")
        val result = mutableListOf<Page>()
        val visited = HashSet<Int>()
        fun walk(ref: PdfRef, mediaBox: FloatArray?, cropBox: FloatArray?, rotate: Int?, resources: Any?, depth: Int) {
            if (depth > 64 || !visited.add(ref.num) || result.size > MAX_OBJECTS) throw PdfSyntaxException("Bad page tree")
            val node = get(ref) as? PdfDict ?: throw PdfSyntaxException("Page tree node is not a dictionary")
            val media = box(node["MediaBox"]) ?: mediaBox
            val crop = box(node["CropBox"]) ?: cropBox
            val rot = (resolve(node["Rotate"]) as? PdfNumber)?.value?.toInt() ?: rotate
            val res = node["Resources"] ?: resources
            val kids = resolve(node["Kids"]) as? List<*>
            val type = (node["Type"] as? PdfName)?.text
            if (type == "Pages" || (type == null && kids != null)) {
                for (kid in kids.orEmpty()) walk(kid as? PdfRef ?: throw PdfSyntaxException("Kid is not a reference"), media, crop, rot, res, depth + 1)
            } else {
                result += Page(ref, node, media ?: floatArrayOf(0f, 0f, 612f, 792f), crop, (((rot ?: 0) % 360) + 360) % 360, res)
            }
        }
        walk(root, null, null, null, null, 0)
        return result
    }

    private fun box(value: Any?): FloatArray? {
        val list = resolve(value) as? List<*> ?: return null
        if (list.size != 4) return null
        val v = list.map { (resolve(it) as? PdfNumber)?.value?.toFloat() ?: return null }
        return floatArrayOf(minOf(v[0], v[2]), minOf(v[1], v[3]), maxOf(v[0], v[2]), maxOf(v[1], v[3]))
    }

    private companion object {
        const val MAX_SECTIONS = 1000
        const val MAX_OBJECTS = 10_000_000
        const val MAX_CACHE = 10_000
        /** Structure streams (cross-reference, object streams) are small; a larger one is a crafted file. */
        const val MAX_STREAM = 32L * 1024 * 1024
        const val MAX_XREF_BYTES = 64L * 1024 * 1024
    }
}
