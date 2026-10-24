package com.tabpdf.doc.office

import android.util.Xml
import com.tabpdf.doc.DocumentOpenException
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * The parts of a zip-based document (docx, hwpx), by path. Guarded against zip bombs (기획서
 * 5.7): limits on entry count, entry size and total size.
 */
class ZipParts private constructor(private val parts: Map<String, ByteArray>) {
    operator fun get(path: String): ByteArray? = parts[path] ?: parts.entries.firstOrNull { it.key.equals(path, true) }?.value

    fun xml(path: String): Element? = get(path)?.let { Element.parse(it) }

    val names: Set<String> get() = parts.keys

    companion object {
        private const val MAX_ENTRIES = 5_000
        private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

        fun read(input: InputStream): ZipParts {
            val parts = mutableMapOf<String, ByteArray>()
            var total = 0L
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (parts.size >= MAX_ENTRIES) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        total += n
                        if (out.size() > MAX_ENTRY_BYTES || total > MAX_TOTAL_BYTES) {
                            throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
                        }
                    }
                    parts[entry.name.removePrefix("/")] = out.toByteArray()
                }
            }
            if (parts.isEmpty()) throw IOException("Not a zip document")
            return ZipParts(parts)
        }
    }
}

/**
 * A parsed XML element. Names keep their prefixes as written ("w:p", "hp:run"): both formats use
 * fixed, conventional prefixes. No DTDs or external entities are processed.
 */
class Element(
    val name: String,
    private val attributes: Map<String, String>,
    val children: List<Element>,
    /** Text and child elements in document order (for mixed content like Hangul's text runs). */
    val nodes: List<Any>,
) {
    /** All text directly inside this element. */
    val text: String get() = nodes.filterIsInstance<String>().joinToString("")

    fun attr(name: String): String? = attributes[name]

    fun child(name: String): Element? = children.firstOrNull { it.name == name }

    fun all(name: String): List<Element> = children.filter { it.name == name }

    /** The first element named [name] anywhere below this one. */
    fun find(name: String): Element? {
        for (c in children) {
            if (c.name == name) return c
            c.find(name)?.let { return it }
        }
        return null
    }

    /** Every element named [name] below this one, in document order. */
    fun findAll(name: String): List<Element> = buildList {
        fun walk(e: Element) {
            for (c in e.children) {
                if (c.name == name) add(c)
                walk(c)
            }
        }
        walk(this@Element)
    }

    val localName: String get() = name.substringAfter(':')

    companion object {
        fun parse(bytes: ByteArray): Element {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(ByteArrayInputStream(bytes), null)
            class Building(val name: String, val attributes: Map<String, String>) {
                val children = mutableListOf<Element>()
                val nodes = mutableListOf<Any>()
            }
            val stack = ArrayDeque<Building>()
            var root: Element? = null
            while (true) {
                when (parser.next()) {
                    XmlPullParser.START_TAG -> stack.addLast(
                        Building(parser.name, (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) })
                    )
                    XmlPullParser.TEXT -> stack.lastOrNull()?.nodes?.add(parser.text)
                    XmlPullParser.END_TAG -> {
                        val b = stack.removeLast()
                        val element = Element(b.name, b.attributes, b.children, b.nodes)
                        if (stack.isEmpty()) root = element else stack.last().apply { children += element; nodes += element }
                    }
                    XmlPullParser.END_DOCUMENT -> return root ?: throw IOException("Empty XML")
                }
            }
        }
    }
}

/**
 * "#RRGGBB" or "RRGGBB" to an opaque colour; null for "auto", "none" or anything unreadable.
 * [bgr]: the digits are blue, green, red — as Hangul stores them (its own rendering of a hwpx shows
 * "#0000FF" as red).
 */
fun parseColor(value: String?, bgr: Boolean = false): Int? {
    val hex = value?.removePrefix("#") ?: return null
    if (hex.length == 8 && hex.startsWith("FF", true)) return null // hwpx writes "#FFFFFFFF" for "none"
    if (hex.length != 6) return null
    val v = hex.toIntOrNull(16) ?: return null
    val rgb = if (bgr) ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v shr 16) and 0xFF) else v
    return rgb or 0xFF000000.toInt()
}
