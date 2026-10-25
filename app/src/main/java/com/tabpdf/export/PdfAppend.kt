package com.tabpdf.export

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.Deflater

/**
 * Adds handwriting to a PDF without touching what is in it (ISO 32000-1, 7.5.6 incremental
 * updates): the original file is copied as it is, and after it come, for each page written on, a
 * content stream with the strokes and the page object again with that stream added; then a
 * cross-reference section for those objects pointing back to the original one. Text, links,
 * bookmarks and forms stay as they were.
 */
object PdfAppend {
    /** Where page [index] shows on screen: [width] × [height] points, as the viewer lays it out. */
    class Target(val index: Int, val ink: PdfInk, val width: Float)

    /**
     * Copies [source] to [out] with [targets] added. Throws [PdfSyntaxException] if the file's
     * structure can't be followed, is encrypted, or has another number of pages than [pageCount].
     */
    fun write(source: File, pageCount: Int, targets: List<Target>, out: OutputStream) {
        RandomAccessFile(source, "r").use { raf ->
            val pdf = PdfFile(raf)
            // Encrypted without a password (only limits on printing, copying…): additions are
            // encrypted the same way. With a password: the caller has Android write it without.
            val crypt = if (pdf.trailer.containsKey("Encrypt")) PdfCrypt.forFile(pdf) ?: throw PdfSyntaxException("Encrypted") else null
            pdf.crypt = crypt
            val root = pdf.trailer["Root"] as? PdfRef ?: throw PdfSyntaxException("No /Root reference")
            val pages = pdf.pages()
            if (pages.size != pageCount) throw PdfSyntaxException("${pages.size} pages, expected $pageCount")

            // The original, unchanged.
            val counter = CountingStream(out)
            source.inputStream().use { it.copyTo(counter) }
            if (targets.all { it.ink.isEmpty }) {
                counter.flush()
                return
            }
            if (pdf.bytes[pdf.bytes.length - 1].let { it != '\n'.code && it != '\r'.code }) counter.write('\n'.code)

            val offsets = sortedMapOf<Int, Pair<Long, Int>>() // object number → offset, generation
            var next = pdf.size
            fun begin(num: Int, gen: Int) {
                offsets[num] = counter.count to gen
                counter.ascii("$num $gen obj\n")
            }
            fun stream(num: Int, content: String) {
                val data = deflate(content.toByteArray(Charsets.ISO_8859_1)).let { crypt?.encryptStream(num, 0, it) ?: it }
                begin(num, 0)
                counter.ascii("<</Length ${data.size}/Filter/FlateDecode>>\nstream\n")
                counter.write(data)
                counter.ascii("\nendstream\nendobj\n")
            }

            for (t in targets) {
                if (t.ink.isEmpty) continue
                val page = pages[t.index]
                // Save the state before the page's own content and restore it after, so the strokes
                // start from the page's untouched coordinates whatever the content leaves behind.
                val before = next++
                val after = next++
                stream(before, "q\n")
                stream(after, "Q\nq\n${toPage(page, t.width)}${t.ink.operators}Q\n")

                val dict = PdfDict(page.dict)
                val contents = when (val c = page.dict["Contents"]) {
                    null -> emptyList()
                    is PdfRef -> when (val resolved = pdf.get(c)) {
                        is PdfStream -> listOf(c)
                        is List<*> -> resolved.filterIsInstance<PdfRef>()
                        else -> emptyList()
                    }
                    is List<*> -> c.filterIsInstance<PdfRef>()
                    else -> emptyList()
                }
                dict["Contents"] = mutableListOf<Any>(PdfRef(before, 0)).apply { addAll(contents); add(PdfRef(after, 0)) }
                if (t.ink.alphas.isNotEmpty()) {
                    // Resources may be inherited or shared: this page gets its own copy with the new states.
                    val resources = PdfDict((pdf.resolve(page.resources) as? Map<*, *>)?.let { copy(it) } ?: emptyMap())
                    val states = PdfDict((pdf.resolve(resources["ExtGState"]) as? Map<*, *>)?.let { copy(it) } ?: emptyMap())
                    for (a in t.ink.alphas) states[PdfInk.stateName(a)] = PdfInk.state(a)
                    resources["ExtGState"] = states
                    dict["Resources"] = resources
                } else if (page.resources != null && !page.dict.containsKey("Resources")) {
                    dict["Resources"] = page.resources
                }
                // Inherited boxes and rotation stay inherited: the page keeps its place in the tree.
                begin(page.ref.num, page.ref.gen)
                // Strings read from an object stream were decrypted with it; on their own now, they
                // are encrypted as this object's. Others are written back as read (still encrypted).
                val written = if (crypt != null && pdf.inObjectStream(page.ref.num)) {
                    encryptStrings(dict, crypt, page.ref)
                } else dict
                counter.ascii(PdfWriter.toText(written))
                counter.ascii("\nendobj\n")
            }

            val trailer = PdfDict()
            trailer["Root"] = root
            pdf.trailer["Info"]?.let { trailer["Info"] = it }
            pdf.trailer["ID"]?.let { trailer["ID"] = it }
            pdf.trailer["Encrypt"]?.let { trailer["Encrypt"] = it }
            trailer["Prev"] = PdfNumber(pdf.lastXref.toString())

            if (pdf.usesXrefStream) {
                // An update to a file with a cross-reference stream adds one too.
                val num = next++
                val start = counter.count
                offsets[num] = start to 0
                trailer["Type"] = PdfName("XRef")
                trailer["Size"] = PdfNumber(next.toString())
                trailer["W"] = mutableListOf<Any>(PdfNumber("1"), PdfNumber("4"), PdfNumber("2"))
                trailer["Index"] = runs(offsets.keys).flatMap { (s, n) -> listOf(PdfNumber(s.toString()), PdfNumber(n.toString())) }.toMutableList<Any>()
                val data = ByteArrayOutputStream()
                for ((_, entry) in offsets) {
                    val (offset, gen) = entry
                    data.write(1)
                    for (shift in intArrayOf(24, 16, 8, 0)) data.write(((offset shr shift) and 0xFF).toInt())
                    data.write((gen shr 8) and 0xFF)
                    data.write(gen and 0xFF)
                }
                trailer["Length"] = PdfNumber(data.size().toString())
                counter.ascii("$num 0 obj\n${PdfWriter.toText(trailer)}\nstream\n")
                counter.write(data.toByteArray())
                counter.ascii("\nendstream\nendobj\nstartxref\n$start\n%%EOF\n")
            } else {
                val start = counter.count
                val table = StringBuilder("xref\n")
                for ((first, n) in runs(offsets.keys)) {
                    table.append(first).append(' ').append(n).append('\n')
                    for (num in first until first + n) {
                        val (offset, gen) = offsets.getValue(num)
                        table.append(String.format(java.util.Locale.ROOT, "%010d %05d n\r\n", offset, gen))
                    }
                }
                trailer["Size"] = PdfNumber(next.toString())
                counter.ascii(table.toString())
                counter.ascii("trailer\n${PdfWriter.toText(trailer)}\nstartxref\n$start\n%%EOF\n")
            }
            counter.flush()
        }
    }

    /**
     * The matrix from the strokes' space (INK_PAGE_WIDTH across the page as shown, y down) to the
     * page's own space: the visible box (CropBox within MediaBox), turned by /Rotate as shown.
     */
    private fun toPage(page: PdfFile.Page, shownWidth: Float): String {
        val m = page.mediaBox
        val box = page.cropBox?.let { c ->
            floatArrayOf(maxOf(c[0], m[0]), maxOf(c[1], m[1]), minOf(c[2], m[2]), minOf(c[3], m[3]))
        }?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: m
        val (llx, lly, urx, ury) = box.toList()
        // Shown point (X, Y) from the top left → page space, for each turn (clockwise).
        val base = when (page.rotate) {
            90 -> floatArrayOf(0f, 1f, 1f, 0f, llx, lly)
            180 -> floatArrayOf(-1f, 0f, 0f, 1f, urx, lly)
            270 -> floatArrayOf(0f, -1f, -1f, 0f, urx, ury)
            else -> floatArrayOf(1f, 0f, 0f, -1f, llx, ury)
        }
        val s = shownWidth / com.tabpdf.ink.INK_PAGE_WIDTH
        return base.joinToString(" ") { PdfWriter.number(it) } + " cm\n" +
            "${PdfWriter.number(s)} 0 0 ${PdfWriter.number(s)} 0 0 cm\n"
    }

    private fun encryptStrings(value: Any, crypt: PdfCrypt, ref: PdfRef): Any = when (value) {
        is PdfString -> PdfString(crypt.encryptString(ref.num, ref.gen, value.bytes))
        is Map<*, *> -> PdfDict().also { d -> for ((k, v) in value) d[k as String] = encryptStrings(v!!, crypt, ref) }
        is List<*> -> value.mapTo(mutableListOf()) { encryptStrings(it!!, crypt, ref) }
        else -> value
    }

    /** Consecutive runs of [nums] as (first, count). */
    private fun runs(nums: Collection<Int>): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (n in nums.sorted()) {
            val last = result.lastOrNull()
            if (last != null && last.first + last.second == n) result[result.lastIndex] = last.first to last.second + 1
            else result += n to 1
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun copy(map: Map<*, *>): Map<String, Any> = map as Map<String, Any>

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(data)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()

        fun ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))
    }
}
