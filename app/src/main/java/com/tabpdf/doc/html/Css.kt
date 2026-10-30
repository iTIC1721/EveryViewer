package com.tabpdf.doc.html

import com.tabpdf.doc.toFiniteFloatOrNull
import com.tabpdf.doc.DocumentOpenException
import org.jsoup.nodes.Element
import java.util.BitSet
import java.util.IdentityHashMap

/*
 * The CSS an HTML file brings (기획서 5.7: 간단한 CSS): style attributes and <style> sheets with
 * simple selectors — tags, classes, ids, attributes, descendant and child combinators, and the
 * structural :first-child/:last-child. Rules with anything else (hover states, sibling
 * combinators, generated content) can never match a static page here and are dropped. Media
 * queries are answered for a phone-wide screen ([VIEWPORT_PX]).
 */

/** Width of the page in CSS pixels: what media queries and viewport units see. */
const val VIEWPORT_PX = 480f

/** A declaration as written: `property: value [!important]`. */
class Declaration(val property: String, val value: String, val important: Boolean)

/** One simple selector of a compound (`div.note#a[href]:first-child`). */
private class Compound(
    val tag: String?,
    val id: String?,
    val classes: List<String>,
    val attributes: List<AttributeTest>,
    val firstChild: Boolean,
    val lastChild: Boolean,
    val root: Boolean,
) {
    fun matches(e: Element): Boolean {
        if (tag != null && tag != e.normalName()) return false
        if (id != null && id != e.id()) return false
        if (classes.isNotEmpty()) {
            val names = e.classNames()
            if (!classes.all { it in names }) return false
        }
        if (!attributes.all { it.matches(e) }) return false
        if (firstChild && e.previousElementSibling() != null) return false
        if (lastChild && e.nextElementSibling() != null) return false
        if (root && e.parent()?.parent() != null) return false
        return true
    }

    val specificity: Int
        get() = (if (id != null) 10_000 else 0) +
            (classes.size + attributes.size + (if (firstChild) 1 else 0) + (if (lastChild) 1 else 0) + (if (root) 1 else 0)) * 100 +
            (if (tag != null) 1 else 0)
}

private class AttributeTest(val name: String, val op: String?, val value: String) {
    fun matches(e: Element): Boolean {
        if (!e.hasAttr(name)) return false
        val actual = e.attr(name)
        return when (op) {
            null -> true
            "=" -> actual == value
            "~=" -> value in actual.split(' ', '\t', '\n')
            "|=" -> actual == value || actual.startsWith("$value-")
            "^=" -> value.isNotEmpty() && actual.startsWith(value)
            "$=" -> value.isNotEmpty() && actual.endsWith(value)
            "*=" -> value.isNotEmpty() && value in actual
            else -> false
        }
    }
}

/**
 * Matching work done for one page (all its style sheets and the default one): a crafted sheet must
 * not take forever. One per page, not per sheet: the default sheet is shared by every page.
 */
class MatchSteps {
    var count = 0L
}

/** Compounds from left to right, and between each two the combinator (' ' descendant, '>' child). */
private class Selector(val parts: List<Compound>, val combinators: List<Char>) {
    val specificity = parts.sumOf { it.specificity }
    val key: Compound get() = parts.last()

    fun matches(e: Element, steps: MatchSteps): Boolean = matchFrom(parts.size - 1, e, steps, IdentityHashMap())

    /**
     * [failed]: compounds already known not to match from an element. Without it a descendant
     * combinator tries every chain of ancestors — exponential in the selector's length.
     */
    private fun matchFrom(i: Int, e: Element, steps: MatchSteps, failed: IdentityHashMap<Element, BitSet>): Boolean {
        // Too much work for any real page: shown as source instead (DocumentOpenException.Reason.TooLarge).
        if (++steps.count > MAX_MATCH_STEPS) throw DocumentOpenException(DocumentOpenException.Reason.TooLarge)
        if (failed[e]?.get(i) == true) return false
        val result = parts[i].matches(e) && (i == 0 || when (combinators[i - 1]) {
            '>' -> e.parent()?.let { matchFrom(i - 1, it, steps, failed) } ?: false
            else -> {
                var ancestor = e.parent()
                var found = false
                while (ancestor != null && !found) {
                    found = matchFrom(i - 1, ancestor, steps, failed)
                    ancestor = ancestor.parent()
                }
                found
            }
        })
        if (!result) failed.getOrPut(e) { BitSet() }.set(i)
        return result
    }
}

/** Steps (compounds tried) one page's styles may take; real pages take a small fraction. */
private const val MAX_MATCH_STEPS = 200_000_000L

/** Longer selectors are dropped: real ones have a few compounds. */
private const val MAX_COMPOUNDS = 32

/** Deeper @media and @supports blocks are skipped: real style sheets nest one or two. */
private const val MAX_NESTING = 16

/** Longer lengths aren't read: "12.5px" is short, and a long run of digits made the pattern below backtrack for ever. */
private const val MAX_LENGTH_CHARS = 32

/** Rules without a tag, class or id to look them up by are tried on every element: their number is limited. */
private const val MAX_UNIVERSAL_RULES = 1_000

private class Rule(val selector: Selector, val declarations: List<Declaration>, val order: Int)

/** The rules of a page's style sheets, found for an element by the last compound of each selector. */
class Stylesheet {
    private val byId = HashMap<String, MutableList<Rule>>()
    private val byClass = HashMap<String, MutableList<Rule>>()
    private val byTag = HashMap<String, MutableList<Rule>>()
    private val universal = mutableListOf<Rule>()
    private var order = 0

    /** Reads [css]; what it can't understand is skipped. */
    fun add(css: String) = parseRules(stripComments(css))

    /**
     * The declarations that apply to [e], in increasing priority: normal ones by specificity and
     * order, then the [inline] style attribute, then !important ones the same way. [steps] counts
     * the work for the page [e] is in.
     */
    fun declarationsFor(e: Element, inline: List<Declaration>, steps: MatchSteps = MatchSteps()): List<Declaration> {
        val candidates = ArrayList<Rule>()
        byId[e.id()]?.let { candidates += it }
        for (name in e.classNames()) byClass[name]?.let { candidates += it }
        byTag[e.normalName()]?.let { candidates += it }
        candidates += universal
        val matched = candidates.filter { it.selector.matches(e, steps) }
            .distinct()
            .sortedWith(compareBy({ it.selector.specificity }, { it.order }))
        val normal = ArrayList<Declaration>()
        val important = ArrayList<Declaration>()
        for (rule in matched) for (d in rule.declarations) (if (d.important) important else normal) += d
        normal += inline.filter { !it.important }
        important += inline.filter { it.important }
        return normal + important
    }

    /**
     * [depth]: how many @media and @supports blocks this is inside. Each level copies its body, so
     * thousands of them nested in a large style sheet would never finish (or run out of stack).
     */
    private fun parseRules(css: String, depth: Int = 0) {
        var i = 0
        while (i < css.length) {
            val c = css[i]
            when {
                c.isWhitespace() -> i++
                c == '@' -> {
                    val end = css.indexOfAny(charArrayOf('{', ';'), i)
                    if (end < 0) return
                    val prelude = css.substring(i + 1, end).trim()
                    if (css[end] == ';') {
                        i = end + 1
                        continue
                    }
                    val close = matchingBrace(css, end)
                    val name = prelude.substringBefore(' ').substringBefore('(').lowercase()
                    val body = css.substring(end + 1, close)
                    when {
                        depth >= MAX_NESTING -> Unit
                        name == "media" && mediaMatches(prelude.substring(5)) -> parseRules(body, depth + 1)
                        name == "supports" -> parseRules(body, depth + 1)
                    }
                    i = close + 1
                }
                c == '}' -> i++ // stray
                else -> {
                    val open = css.indexOf('{', i)
                    if (open < 0) return
                    val close = matchingBrace(css, open)
                    val selectors = css.substring(i, open)
                    val declarations = parseDeclarations(css.substring(open + 1, close))
                    if (declarations.isNotEmpty()) {
                        for (text in splitTopLevel(selectors, ',')) {
                            val selector = parseSelector(text.trim()) ?: continue
                            val rule = Rule(selector, declarations, order++)
                            val key = selector.key
                            when {
                                key.id != null -> byId.getOrPut(key.id) { mutableListOf() } += rule
                                key.classes.isNotEmpty() -> byClass.getOrPut(key.classes.first()) { mutableListOf() } += rule
                                key.tag != null -> byTag.getOrPut(key.tag) { mutableListOf() } += rule
                                universal.size < MAX_UNIVERSAL_RULES -> universal += rule
                            }
                        }
                    }
                    i = close + 1
                }
            }
        }
    }

    companion object {
        fun stripComments(css: String): String {
            if (!css.contains("/*")) return css
            val out = StringBuilder(css.length)
            var i = 0
            while (i < css.length) {
                val start = css.indexOf("/*", i)
                if (start < 0) {
                    out.append(css, i, css.length)
                    break
                }
                out.append(css, i, start)
                val end = css.indexOf("*/", start + 2)
                i = if (end < 0) css.length else end + 2
            }
            return out.toString()
        }

        /** Index of the brace closing the one at [open] (or the end), skipping strings. */
        private fun matchingBrace(css: String, open: Int): Int {
            var depth = 0
            var i = open
            var quote = 0.toChar()
            while (i < css.length) {
                val c = css[i]
                if (quote != 0.toChar()) {
                    if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
                } else when (c) {
                    '"', '\'' -> quote = c
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
                i++
            }
            return css.length
        }

        /** A style attribute or rule body: `a: b; c: d !important`. */
        fun parseDeclarations(body: String): List<Declaration> = splitTopLevel(body, ';').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            val property = part.substring(0, colon).trim().lowercase()
            var value = part.substring(colon + 1).trim()
            val important = value.endsWith("!important", ignoreCase = true)
            if (important) value = value.dropLast("!important".length).trim()
            if (property.isEmpty() || value.isEmpty()) null else Declaration(property, value, important)
        }

        /** [text] split at [separator]s outside parentheses, brackets and quotes. */
        fun splitTopLevel(text: String, separator: Char): List<String> {
            val result = mutableListOf<String>()
            var depth = 0
            var quote = 0.toChar()
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quote != 0.toChar()) {
                    if (c == '\\') i++ else if (c == quote) quote = 0.toChar()
                } else when (c) {
                    '"', '\'' -> quote = c
                    '(', '[' -> depth++
                    ')', ']' -> depth = (depth - 1).coerceAtLeast(0)
                    separator -> if (depth == 0) {
                        result += text.substring(start, i)
                        start = i + 1
                    }
                }
                i++
            }
            result += text.substring(start)
            return result
        }

        /**
         * A media query list, for a phone-wide colour screen in portrait and light mode. Unknown
         * features don't match, so rules meant for other devices stay out.
         */
        fun mediaMatches(query: String): Boolean = splitTopLevel(query, ',').any { one ->
            var q = one.trim().lowercase()
            val negate = q.startsWith("not ")
            if (negate) q = q.removePrefix("not ").trim()
            q = q.removePrefix("only ").trim()
            val type = q.substringBefore('(').substringBefore(" and").trim()
            val typeOk = type.isEmpty() || type == "all" || type == "screen"
            val features = Regex("""\(([^)]*)\)""").findAll(q).map { it.groupValues[1].trim() }.toList()
            val ok = typeOk && features.all { featureMatches(it) }
            ok != negate
        }

        private fun featureMatches(feature: String): Boolean {
            val name = feature.substringBefore(':').trim()
            val value = feature.substringAfter(':', "").trim()
            fun px(): Float? = CssValues.length(value, 16f, 16f, null)
            return when (name) {
                "min-width" -> px()?.let { VIEWPORT_PX >= it } ?: false
                "max-width" -> px()?.let { VIEWPORT_PX <= it } ?: false
                "orientation" -> value == "portrait"
                "prefers-color-scheme" -> value == "light"
                "color", "hover", "pointer" -> name == "color" || value == "none" || value == "coarse"
                else -> false
            }
        }

        /** A selector this viewer can match, or null. */
        private fun parseSelector(text: String): Selector? {
            if (text.isEmpty()) return null
            val parts = mutableListOf<Compound>()
            val combinators = mutableListOf<Char>()
            var i = 0
            var pending: Char? = null
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace() -> {
                        if (parts.isNotEmpty() && pending == null) pending = ' '
                        i++
                    }
                    c == '>' -> {
                        if (parts.isEmpty()) return null
                        pending = '>'
                        i++
                    }
                    c == '+' || c == '~' -> return null
                    else -> {
                        val (compound, end) = parseCompound(text, i) ?: return null
                        if (parts.isNotEmpty()) combinators += pending ?: return null
                        parts += compound
                        pending = null
                        i = end
                    }
                }
            }
            if (parts.isEmpty() || parts.size > MAX_COMPOUNDS || combinators.size != parts.size - 1) return null
            return Selector(parts, combinators)
        }

        private fun parseCompound(text: String, from: Int): Pair<Compound, Int>? {
            var i = from
            var tag: String? = null
            var id: String? = null
            val classes = mutableListOf<String>()
            val attributes = mutableListOf<AttributeTest>()
            var first = false
            var last = false
            var root = false
            fun ident(): String {
                val start = i
                while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == '_' || text[i] == '\\' || text[i].code > 127)) {
                    if (text[i] == '\\') i++
                    i++
                }
                return text.substring(start, i.coerceAtMost(text.length)).replace("\\", "")
            }
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '*' -> i++
                    c == '.' -> { i++; classes += ident().ifEmpty { return null } }
                    c == '#' -> { i++; id = ident().ifEmpty { return null } }
                    c == '[' -> {
                        val close = text.indexOf(']', i)
                        if (close < 0) return null
                        val inside = text.substring(i + 1, close).trim()
                        val m = Regex("""^([\w-]+)\s*(?:([~|^$*]?=)\s*(?:"([^"]*)"|'([^']*)'|([^\s"']+))\s*[is]?)?$""").find(inside) ?: return null
                        val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.ifEmpty { m.groupValues[5] }
                        attributes += AttributeTest(m.groupValues[1].lowercase(), m.groupValues[2].ifEmpty { null }, value)
                        i = close + 1
                    }
                    c == ':' -> {
                        if (text.startsWith("::", i)) return null // generated content
                        i++
                        when (ident().lowercase()) {
                            "first-child" -> first = true
                            "last-child" -> last = true
                            "only-child" -> { first = true; last = true }
                            "root" -> root = true
                            "link", "any-link" -> attributes += AttributeTest("href", null, "")
                            else -> return null // states and functions: never matched statically
                        }
                    }
                    c.isLetter() || c == '-' || c == '_' || c.code > 127 -> tag = ident().lowercase()
                    else -> break
                }
            }
            if (i == from) return null
            return Compound(tag, id, classes, attributes, first, last, root) to i
        }
    }
}

/** Reading CSS values: lengths in CSS pixels, colours as ARGB. */
object CssValues {
    /**
     * A length in pixels: [em] and [rem] are the font sizes for em and rem, [percentOf] what 100%
     * is (null: percentages unknown here). Null for "auto" and anything unreadable.
     */
    fun length(value: String, em: Float, rem: Float, percentOf: Float?): Float? {
        val v = value.trim().lowercase()
        if (v == "0") return 0f
        if (v.length > MAX_LENGTH_CHARS) return null
        val m = Regex("""^([+-]?(?:\d+\.?\d*|\.\d+)(?:e[+-]?\d+)?)([a-z%]*)$""").find(v) ?: return null
        // "1e99" is a number too, but no length: it would be endless.
        val n = m.groupValues[1].toFiniteFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return when (m.groupValues[2]) {
            "px", "" -> if (m.groupValues[2].isEmpty() && n != 0f) null else n
            "pt" -> n * 4f / 3f
            "pc" -> n * 16f
            "in" -> n * 96f
            "cm" -> n * 96f / 2.54f
            "mm" -> n * 96f / 25.4f
            "q" -> n * 96f / 101.6f
            "em" -> n * em
            "rem" -> n * rem
            "ex", "ch" -> n * em * 0.5f
            "vw", "vmin" -> n * VIEWPORT_PX / 100f
            "vh", "vmax" -> n * VIEWPORT_PX * 1.6f / 100f
            "%" -> percentOf?.let { n * it / 100f }
            else -> null
        }
    }

    /** A colour (0 for transparent), or null if unreadable; [current] is `currentColor`. */
    fun color(value: String, current: Int): Int? {
        val v = value.trim().lowercase()
        if (v == "transparent") return 0
        if (v == "currentcolor") return current
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            if (!hex.all { it.isDigit() || it in 'a'..'f' }) return null
            fun d(i: Int) = hex[i].digitToInt(16)
            return when (hex.length) {
                3 -> argb(255, d(0) * 17, d(1) * 17, d(2) * 17)
                4 -> argb(d(3) * 17, d(0) * 17, d(1) * 17, d(2) * 17)
                6 -> (0xFF000000.toInt()) or hex.toInt(16)
                8 -> {
                    val rgb = hex.substring(0, 6).toInt(16)
                    (hex.substring(6).toInt(16) shl 24) or rgb
                }
                else -> null
            }
        }
        val fn = Regex("""^(rgba?|hsla?)\((.*)\)$""").find(v)
        if (fn != null) {
            val args = fn.groupValues[2].split(',', ' ', '/').map { it.trim() }.filter { it.isNotEmpty() }
            if (args.size < 3) return null
            val alpha = args.getOrNull(3)?.let { a -> if (a.endsWith('%')) a.dropLast(1).toFiniteFloatOrNull()?.div(100f) else a.toFiniteFloatOrNull() } ?: 1f
            val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
            return if (fn.groupValues[1].startsWith("rgb")) {
                fun ch(s: String) = (if (s.endsWith('%')) s.dropLast(1).toFiniteFloatOrNull()?.times(2.55f) else s.toFiniteFloatOrNull())?.toInt()?.coerceIn(0, 255)
                argb(a, ch(args[0]) ?: return null, ch(args[1]) ?: return null, ch(args[2]) ?: return null)
            } else {
                val h = args[0].removeSuffix("deg").toFiniteFloatOrNull() ?: return null
                val s = args[1].removeSuffix("%").toFiniteFloatOrNull()?.div(100f) ?: return null
                val l = args[2].removeSuffix("%").toFiniteFloatOrNull()?.div(100f) ?: return null
                val rgb = android.graphics.Color.HSVToColor(hslToHsv(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
                (a shl 24) or (rgb and 0xFFFFFF)
            }
        }
        return NAMED[v]?.let { (0xFF000000.toInt()) or it }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun hslToHsv(h: Float, s: Float, l: Float): FloatArray {
        val v = l + s * minOf(l, 1 - l)
        val sv = if (v == 0f) 0f else 2 * (1 - l / v)
        return floatArrayOf(((h % 360) + 360) % 360, sv, v)
    }

    private val NAMED = mapOf(
        "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
        "yellow" to 0xFFFF00, "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000,
        "purple" to 0x800080, "fuchsia" to 0xFF00FF, "magenta" to 0xFF00FF, "lime" to 0x00FF00, "olive" to 0x808000,
        "navy" to 0x000080, "teal" to 0x008080, "aqua" to 0x00FFFF, "cyan" to 0x00FFFF, "orange" to 0xFFA500,
        "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "gold" to 0xFFD700, "lightgray" to 0xD3D3D3, "lightgrey" to 0xD3D3D3,
        "darkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9, "dimgray" to 0x696969, "dimgrey" to 0x696969,
        "gainsboro" to 0xDCDCDC, "whitesmoke" to 0xF5F5F5, "beige" to 0xF5F5DC, "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C,
        "lavender" to 0xE6E6FA, "salmon" to 0xFA8072, "coral" to 0xFF7F50, "tomato" to 0xFF6347, "crimson" to 0xDC143C,
        "indigo" to 0x4B0082, "violet" to 0xEE82EE, "orchid" to 0xDA70D6, "plum" to 0xDDA0DD, "tan" to 0xD2B48C,
        "wheat" to 0xF5DEB3, "skyblue" to 0x87CEEB, "steelblue" to 0x4682B4, "royalblue" to 0x4169E1,
        "dodgerblue" to 0x1E90FF, "darkblue" to 0x00008B, "darkred" to 0x8B0000, "darkgreen" to 0x006400,
        "forestgreen" to 0x228B22, "seagreen" to 0x2E8B57, "lightblue" to 0xADD8E6, "lightgreen" to 0x90EE90,
        "lightyellow" to 0xFFFFE0, "lightpink" to 0xFFB6C1, "aliceblue" to 0xF0F8FF, "honeydew" to 0xF0FFF0,
        "mintcream" to 0xF5FFFA, "azure" to 0xF0FFFF, "ghostwhite" to 0xF8F8FF, "linen" to 0xFAF0E6, "snow" to 0xFFFAFA,
        "slategray" to 0x708090, "slategrey" to 0x708090, "lightslategray" to 0x778899, "darkslategray" to 0x2F4F4F,
        "darkorange" to 0xFF8C00, "orangered" to 0xFF4500, "firebrick" to 0xB22222, "chocolate" to 0xD2691E,
        "sienna" to 0xA0522D, "goldenrod" to 0xDAA520, "darkgoldenrod" to 0xB8860B, "olivedrab" to 0x6B8E23,
        "yellowgreen" to 0x9ACD32, "limegreen" to 0x32CD32, "mediumseagreen" to 0x3CB371, "darkcyan" to 0x008B8B,
        "cadetblue" to 0x5F9EA0, "cornflowerblue" to 0x6495ED, "deepskyblue" to 0x00BFFF, "lightskyblue" to 0x87CEFA,
        "midnightblue" to 0x191970, "mediumblue" to 0x0000CD, "slateblue" to 0x6A5ACD, "blueviolet" to 0x8A2BE2,
        "darkviolet" to 0x9400D3, "mediumpurple" to 0x9370DB, "rebeccapurple" to 0x663399, "hotpink" to 0xFF69B4,
        "deeppink" to 0xFF1493, "palevioletred" to 0xDB7093, "mistyrose" to 0xFFE4E1, "lemonchiffon" to 0xFFFACD,
        "papayawhip" to 0xFFEFD5, "oldlace" to 0xFDF5E6, "seashell" to 0xFFF5EE, "cornsilk" to 0xFFF8DC,
        "lightcyan" to 0xE0FFFF, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE, "turquoise" to 0x40E0D0,
        "lightsteelblue" to 0xB0C4DE, "powderblue" to 0xB0E0E6, "lightcoral" to 0xF08080, "rosybrown" to 0xBC8F8F,
        "indianred" to 0xCD5C5C, "peru" to 0xCD853F, "burlywood" to 0xDEB887, "sandybrown" to 0xF4A460,
    )
}
