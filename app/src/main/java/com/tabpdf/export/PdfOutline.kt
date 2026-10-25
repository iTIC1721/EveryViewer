package com.tabpdf.export

import com.tabpdf.doc.TocEntry

/**
 * A PDF's bookmarks (ISO 32000-1, 12.3.3 document outline) as table of contents entries: each
 * item's title and where its destination is — the page, and how far down it where the destination
 * says (12.3.2.2), by name or directly, or through a GoTo action.
 */
object PdfOutline {
    private const val MAX_ITEMS = 20_000
    private const val MAX_DEPTH = 32

    fun read(pdf: PdfFile, pages: List<PdfFile.Page>): List<TocEntry.Flat> {
        val catalog = pdf.resolve(pdf.trailer["Root"]) as? Map<*, *> ?: return emptyList()
        val outlines = pdf.resolve(catalog["Outlines"]) as? Map<*, *> ?: return emptyList()
        val pageIndex = HashMap<Int, Int>().apply { pages.forEachIndexed { i, p -> put(p.ref.num, i) } }
        val names = NamedDestinations(pdf, catalog)
        val result = mutableListOf<TocEntry.Flat>()
        val visited = HashSet<Int>()

        fun walk(first: Any?, level: Int) {
            if (level > MAX_DEPTH) return
            var ref = first as? PdfRef
            while (ref != null && visited.add(ref.num) && result.size < MAX_ITEMS) {
                val item = pdf.get(ref) as? Map<*, *> ?: break
                val title = (pdf.resolve(item["Title"]) as? PdfString)?.let { text(it.bytes) }.orEmpty()
                val dest = item["Dest"] ?: (pdf.resolve(item["A"]) as? Map<*, *>)?.takeIf { (it["S"] as? PdfName)?.text == "GoTo" }?.get("D")
                val place = dest?.let { place(pdf, names.resolve(dest), pages, pageIndex) }
                val at = result.size
                walk(item["First"], level + 1)
                if (title.isNotEmpty()) {
                    // An item without a place of its own goes to its first child's.
                    val target = place ?: result.getOrNull(at)?.let { it.page to it.y }
                    if (target != null) result.add(at, TocEntry.Flat(level, title, target.first, target.second))
                }
                ref = item["Next"] as? PdfRef
            }
        }
        walk(outlines["First"], 0)
        return result
    }

    /** Page and fraction down it of an explicit destination ([page /XYZ left top zoom], [page /FitH top] …). */
    private fun place(pdf: PdfFile, dest: Any?, pages: List<PdfFile.Page>, pageIndex: Map<Int, Int>): Pair<Int, Float?>? {
        val array = pdf.resolve(dest).let { if (it is Map<*, *>) pdf.resolve(it["D"]) else it } as? List<*> ?: return null
        val index = when (val p = array.firstOrNull()) {
            is PdfRef -> pageIndex[p.num]
            is PdfNumber -> p.value.toInt()
            else -> null
        }?.takeIf { it in pages.indices } ?: return null
        val top = when ((array.getOrNull(1) as? PdfName)?.text) {
            "XYZ" -> array.getOrNull(3)
            "FitH", "FitBH" -> array.getOrNull(2)
            "FitR" -> array.getOrNull(5)
            else -> null
        }.let { (pdf.resolve(it) as? PdfNumber)?.value?.toFloat() }
        return index to top?.let { fraction(pages[index], it) }
    }

    /** How far down the page as shown a point [top] high in its own space is; null on turned pages. */
    private fun fraction(page: PdfFile.Page, top: Float): Float? {
        val m = page.mediaBox
        val box = page.cropBox?.let { c -> floatArrayOf(maxOf(c[0], m[0]), maxOf(c[1], m[1]), minOf(c[2], m[2]), minOf(c[3], m[3])) }
            ?.takeIf { it[3] > it[1] } ?: m
        val height = box[3] - box[1]
        if (height <= 0f) return null
        return when (page.rotate) {
            0 -> (box[3] - top) / height
            180 -> (top - box[1]) / height
            else -> null
        }?.coerceIn(0f, 1f)
    }

    /** Text strings (7.9.2.2): UTF-16BE or UTF-8 with a byte order mark, else PDFDocEncoding (≈ Latin-1). */
    fun text(bytes: ByteArray): String {
        val s = when {
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            else -> String(bytes, Charsets.ISO_8859_1)
        }
        // Language and other escape sequences in UTF-16 text (ESC … ESC), and control characters.
        return s.replace(Regex("\u001B[^\u001B]*\u001B"), "").replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim()
    }

    /** Named destinations: the catalog's /Dests dictionary (PDF 1.1) and the /Names tree. */
    private class NamedDestinations(private val pdf: PdfFile, catalog: Map<*, *>) {
        private val old = pdf.resolve(catalog["Dests"]) as? Map<*, *>
        private val tree = (pdf.resolve(catalog["Names"]) as? Map<*, *>)?.let { pdf.resolve(it["Dests"]) as? Map<*, *> }
        private var all: Map<String, Any>? = null

        /** [dest] itself, or what the name it is points to. */
        fun resolve(dest: Any): Any? = when (val d = pdf.resolve(dest)) {
            is PdfName -> old?.get(d.text) ?: lookup(d.text)
            is PdfString -> lookup(String(d.bytes, Charsets.ISO_8859_1))
            else -> d
        }

        private fun lookup(name: String): Any? {
            val map = all ?: HashMap<String, Any>().also { map ->
                val visited = HashSet<Int>()
                fun node(n: Map<*, *>, depth: Int) {
                    if (depth > 32 || map.size > 200_000) return
                    (pdf.resolve(n["Names"]) as? List<*>)?.chunked(2)?.forEach { pair ->
                        val key = (pdf.resolve(pair.getOrNull(0)) as? PdfString)?.bytes ?: return@forEach
                        pair.getOrNull(1)?.let { map[String(key, Charsets.ISO_8859_1)] = it }
                    }
                    (pdf.resolve(n["Kids"]) as? List<*>)?.forEach { kid ->
                        if (kid is PdfRef && !visited.add(kid.num)) return@forEach
                        (pdf.resolve(kid) as? Map<*, *>)?.let { node(it, depth + 1) }
                    }
                }
                tree?.let { node(it, 0) }
                all = map
            }
            return map[name]
        }
    }
}
