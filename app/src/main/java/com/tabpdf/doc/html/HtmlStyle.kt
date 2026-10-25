package com.tabpdf.doc.html

import com.tabpdf.doc.office.Align
import com.tabpdf.doc.office.FontKind
import org.jsoup.nodes.Element

/** A length that may depend on the width of what holds it: [px] plus [percent] of that width. */
class Len(val px: Float, val percent: Float = 0f, val auto: Boolean = false) {
    fun resolve(base: Float): Float = if (auto) 0f else px + percent * base / 100f

    companion object {
        val ZERO = Len(0f)
        val AUTO = Len(0f, auto = true)
    }
}

enum class WhiteSpace { Normal, Pre, PreLine }

/** A border side in CSS pixels. */
class Side(val width: Float, val color: Int)

/**
 * An element's computed style, the part of CSS this viewer uses. Lengths are CSS pixels. The
 * first group is inherited by child elements, the rest is not.
 */
class Style private constructor() {
    var color = 0xFF000000.toInt()
    var fontSize = 16f
    var bold = false
    var italic = false
    var font = FontKind.Sans
    var align = Align.Left
    /** Line height as a multiple of the font size, or [lineHeightPx] if that is set. */
    var lineHeight = NORMAL_LINE_HEIGHT
    var lineHeightPx: Float? = null
    var whiteSpace = WhiteSpace.Normal
    var listType = "disc"
    var keepAll = false
    var transform: String? = null
    var underline = false
    var strike = false
    var textIndent: Len = Len.ZERO
    var invisible = false
    var superscript = false
    var subscript = false
    /** Custom properties (`--name`), inherited like the rest of this group. */
    var vars: Map<String, String> = emptyMap()

    var display = "inline"
    var background = 0
    val margin = arrayOf(Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO)
    val padding = arrayOf(Len.ZERO, Len.ZERO, Len.ZERO, Len.ZERO)
    val borderWidth = floatArrayOf(3f, 3f, 3f, 3f)
    val borderStyle = arrayOf("none", "none", "none", "none")
    val borderColor = arrayOfNulls<Int>(4)
    var width: Len? = null
    var maxWidth: Len? = null
    var height: Len? = null
    /** [width] includes padding and borders. */
    var borderBox = false
    var verticalAlign = "baseline"

    /** Border side [i] (top, right, bottom, left), or null if none is drawn. */
    fun border(i: Int): Side? {
        if (borderStyle[i] == "none" || borderStyle[i] == "hidden" || borderWidth[i] <= 0f) return null
        return Side(borderWidth[i], borderColor[i] ?: color)
    }

    val hasBorder: Boolean get() = (0..3).any { border(it) != null }

    val isBlock: Boolean get() = display !in INLINE_DISPLAYS

    /** Line pitch in pixels. */
    val linePx: Float get() = lineHeightPx ?: (lineHeight * fontSize)

    fun inherit(): Style = Style().also {
        it.color = color
        it.fontSize = fontSize
        it.bold = bold
        it.italic = italic
        it.font = font
        it.align = align
        it.lineHeight = lineHeight
        it.lineHeightPx = lineHeightPx
        it.whiteSpace = whiteSpace
        it.listType = listType
        it.keepAll = keepAll
        it.transform = transform
        it.underline = underline
        it.strike = strike
        it.textIndent = textIndent
        it.invisible = invisible
        it.superscript = superscript
        it.subscript = subscript
        it.vars = vars
    }

    companion object {
        /** `line-height: normal` (browsers use about 1.2 for common fonts). */
        const val NORMAL_LINE_HEIGHT = 1.2f

        private val INLINE_DISPLAYS = setOf("inline", "inline-block", "inline-flex", "inline-grid", "inline-table", "contents", "ruby")

        fun root(): Style = Style().also { it.display = "block" }
    }
}

/** Works out each element's [Style] from the browser defaults, the page's CSS and HTML attributes. */
class StyleResolver(private val author: Stylesheet) {
    private var rootFontSize = 16f

    fun compute(e: Element, parent: Style): Style {
        val s = parent.inherit()
        val declarations = ArrayList<Declaration>()
        declarations += USER_AGENT.declarationsFor(e, emptyList())
        declarations += presentational(e)
        val inline = e.attr("style").takeIf { it.isNotBlank() }?.let { Stylesheet.parseDeclarations(it) } ?: emptyList()
        declarations += author.declarationsFor(e, inline)

        // Later declarations win; shorthands are spread into the properties they set.
        val values = LinkedHashMap<String, String>()
        val vars = LinkedHashMap<String, String>()
        for (d in declarations) {
            if (d.property.startsWith("--")) vars[d.property] = d.value else expand(d.property, d.value, values)
        }
        if (vars.isNotEmpty()) s.vars = s.vars + vars
        val resolved = values.mapValues { (_, v) -> substitute(v, s.vars, 0) }

        resolved["font-size"]?.let { v -> fontSize(v, parent.fontSize)?.let { s.fontSize = it.coerceIn(1f, 500f) } }
        if (e.parent() == null || e.normalName() == "html") rootFontSize = s.fontSize
        val em = s.fontSize
        fun len(v: String, percent: Boolean = true): Len? {
            val t = v.trim().lowercase()
            if (t == "auto") return Len.AUTO
            if (percent && t.endsWith("%")) return t.dropLast(1).toFloatOrNull()?.let { Len(0f, it) }
            return CssValues.length(t, em, rootFontSize, null)?.let { Len(it) }
        }
        for ((property, value) in resolved) {
            val v = value.trim()
            val lower = v.lowercase()
            if (lower == "inherit" || lower == "unset" || lower == "revert") continue
            when (property) {
                "color" -> CssValues.color(v, s.color)?.let { s.color = it }
                "font-weight" -> s.bold = when (lower) {
                    "bold", "bolder" -> true
                    "normal", "lighter", "initial" -> false
                    else -> (lower.toIntOrNull() ?: if (s.bold) 700 else 400) >= 600
                }
                "font-style" -> s.italic = lower == "italic" || lower.startsWith("oblique")
                "font-family" -> s.font = fontKind(lower)
                "text-align" -> s.align = when (lower) {
                    "center", "-webkit-center", "-moz-center" -> Align.Center
                    "right", "end", "-webkit-right" -> Align.Right
                    "justify" -> Align.Justify
                    else -> Align.Left
                }
                "line-height" -> when {
                    lower == "normal" || lower == "initial" -> { s.lineHeight = Style.NORMAL_LINE_HEIGHT; s.lineHeightPx = null }
                    lower.toFloatOrNull() != null -> { s.lineHeight = lower.toFloat().coerceIn(0.5f, 5f); s.lineHeightPx = null }
                    lower.endsWith("%") -> lower.dropLast(1).toFloatOrNull()?.let { s.lineHeightPx = it * em / 100f }
                    else -> CssValues.length(lower, em, rootFontSize, null)?.let { s.lineHeightPx = it }
                }
                "white-space" -> s.whiteSpace = when (lower) {
                    "pre", "pre-wrap", "break-spaces" -> WhiteSpace.Pre
                    "pre-line" -> WhiteSpace.PreLine
                    else -> WhiteSpace.Normal
                }
                "white-space-collapse" -> s.whiteSpace = if (lower == "preserve" || lower == "break-spaces") WhiteSpace.Pre else s.whiteSpace
                "list-style-type" -> s.listType = lower
                "word-break" -> s.keepAll = lower == "keep-all"
                "text-transform" -> s.transform = lower.takeIf { it != "none" }
                "text-decoration-line" -> {
                    s.underline = "underline" in lower
                    s.strike = "line-through" in lower
                }
                "text-indent" -> len(v)?.let { s.textIndent = it }
                "visibility" -> s.invisible = lower == "hidden" || lower == "collapse"
                "display" -> s.display = lower.substringBefore(' ').let { if (it == "flow-root") "block" else it }
                "background-color" -> CssValues.color(v, s.color)?.let { s.background = it }
                "width" -> s.width = len(v)
                "max-width" -> s.maxWidth = if (lower == "none") null else len(v)
                "height" -> s.height = len(v)
                "vertical-align" -> {
                    s.verticalAlign = lower
                    s.superscript = lower == "super"
                    s.subscript = lower == "sub"
                }
                "box-sizing" -> s.borderBox = lower == "border-box"
                else -> {
                    val side = SIDES.indexOf(property.substringAfterLast('-'))
                    when {
                        property.startsWith("margin-") && side >= 0 -> len(v)?.let { s.margin[side] = it }
                        property.startsWith("padding-") && side >= 0 -> len(v)?.let { if (!it.auto) s.padding[side] = it }
                        property.matches(BORDER_PART) -> {
                            val i = SIDES.indexOf(property.split('-')[1])
                            when (property.substringAfterLast('-')) {
                                "width" -> borderWidth(lower, em)?.let { s.borderWidth[i] = it }
                                "style" -> s.borderStyle[i] = lower
                                // currentColor: the element's own colour, which may be set after this.
                                "color" -> s.borderColor[i] = if (lower == "currentcolor") null else CssValues.color(v, s.color)
                            }
                        }
                    }
                }
            }
        }
        if (e.hasAttr("hidden")) s.display = "none"
        return s
    }

    private fun fontSize(value: String, parent: Float): Float? {
        val v = value.trim().lowercase()
        FONT_KEYWORDS[v]?.let { return it }
        return when (v) {
            "smaller" -> parent / 1.2f
            "larger" -> parent * 1.2f
            else -> if (v.endsWith("%")) v.dropLast(1).toFloatOrNull()?.let { it * parent / 100f }
            else CssValues.length(v, parent, rootFontSize, null)
        }
    }

    /** Old HTML attributes that set style, weaker than any CSS. */
    private fun presentational(e: Element): List<Declaration> {
        val result = mutableListOf<Declaration>()
        fun add(property: String, value: String) {
            if (value.isNotBlank()) result += Declaration(property, value.trim(), important = false)
        }
        fun pixels(v: String) = if (v.trim().all { it.isDigit() }) "${v.trim()}px" else v
        val tag = e.normalName()
        if (e.hasAttr("bgcolor")) add("background-color", e.attr("bgcolor"))
        if (e.hasAttr("align") && tag != "img" && tag != "table") add("text-align", e.attr("align"))
        if (tag == "table" && e.attr("align").equals("center", true)) {
            add("margin-left", "auto")
            add("margin-right", "auto")
        }
        if (e.hasAttr("valign")) add("vertical-align", e.attr("valign"))
        if (e.hasAttr("width") && tag in setOf("table", "td", "th", "col", "hr", "div")) add("width", pixels(e.attr("width")))
        if (e.hasAttr("height") && tag in setOf("td", "th", "tr")) add("height", pixels(e.attr("height")))
        if (tag == "body" && e.hasAttr("text")) add("color", e.attr("text"))
        if (tag == "font") {
            if (e.hasAttr("color")) add("color", e.attr("color"))
            if (e.hasAttr("face")) add("font-family", e.attr("face"))
            e.attr("size").trim().takeIf { it.isNotEmpty() }?.let { size ->
                val n = when {
                    size.startsWith("+") -> 3 + (size.drop(1).toIntOrNull() ?: 0)
                    size.startsWith("-") -> 3 - (size.drop(1).toIntOrNull() ?: 0)
                    else -> size.toIntOrNull() ?: 3
                }
                add("font-size", "${FONT_SIZES[n.coerceIn(1, 7) - 1]}px")
            }
        }
        return result
    }

    /** `var(--name, fallback)` replaced by the custom property's value. */
    private fun substitute(value: String, vars: Map<String, String>, depth: Int): String {
        val start = value.indexOf("var(")
        if (start < 0 || depth > 8) return value
        var i = start + 4
        var nesting = 1
        while (i < value.length && nesting > 0) {
            if (value[i] == '(') nesting++ else if (value[i] == ')') nesting--
            i++
        }
        val inside = value.substring(start + 4, (i - 1).coerceAtLeast(start + 4))
        val name = inside.substringBefore(',').trim()
        val fallback = if (inside.contains(',')) inside.substringAfter(',').trim() else ""
        val replacement = substitute(vars[name] ?: fallback, vars, depth + 1)
        return substitute(value.substring(0, start) + replacement + value.substring(i), vars, depth + 1)
    }

    companion object {
        private val SIDES = listOf("top", "right", "bottom", "left")
        private val BORDER_PART = Regex("^border-(top|right|bottom|left)-(width|style|color)$")
        private val BORDER_STYLES = setOf("none", "hidden", "solid", "dashed", "dotted", "double", "groove", "ridge", "inset", "outset")
        private val FONT_SIZES = intArrayOf(10, 13, 16, 18, 24, 32, 48)
        private val FONT_KEYWORDS = mapOf(
            "xx-small" to 9f, "x-small" to 10f, "small" to 13f, "medium" to 16f, "large" to 18f,
            "x-large" to 24f, "xx-large" to 32f, "xxx-large" to 48f,
        )
        private val LIST_TYPES = setOf(
            "disc", "circle", "square", "decimal", "decimal-leading-zero", "lower-alpha", "upper-alpha",
            "lower-latin", "upper-latin", "lower-roman", "upper-roman", "none", "lower-greek", "hangul", "korean-hangul-formal",
        )

        private fun fontKind(family: String): FontKind = when {
            listOf("mono", "courier", "consolas", "menlo", "monaco", "code", "d2coding", "fixed").any { it in family } -> FontKind.Mono
            Regex("""(^|[,\s"'])serif""").containsMatchIn(family) ||
                listOf("times", "georgia", "garamond", "batang", "바탕", "명조", "myeongjo", "gungsuh", "궁서", "cambria", "palatino").any { it in family } -> FontKind.Serif
            else -> FontKind.Sans
        }

        private fun borderWidth(v: String, em: Float): Float? = when (v) {
            "thin" -> 1f
            "medium" -> 3f
            "thick" -> 5f
            else -> CssValues.length(v, em, 16f, null)
        }

        /** [value] split into words, keeping function calls like rgb(1, 2, 3) whole. */
        private fun tokens(value: String): List<String> =
            Stylesheet.splitTopLevel(value.trim(), ' ').map { it.trim() }.filter { it.isNotEmpty() }

        private fun isColor(token: String) = CssValues.color(token, 0) != null

        /** Puts [property] into [out], spreading shorthands into their longhands. */
        private fun expand(property: String, value: String, out: MutableMap<String, String>) {
            fun sides(prefix: String, suffix: String, v: String) {
                val t = tokens(v)
                if (t.isEmpty() || t.size > 4) return
                val four = when (t.size) {
                    1 -> listOf(t[0], t[0], t[0], t[0])
                    2 -> listOf(t[0], t[1], t[0], t[1])
                    3 -> listOf(t[0], t[1], t[2], t[1])
                    else -> t
                }
                SIDES.forEachIndexed { i, side -> out["$prefix-$side$suffix"] = four[i] }
            }
            fun border(sidesOf: List<String>, v: String) {
                val t = tokens(v)
                var width = "medium"
                var style = "none"
                var color = "currentcolor"
                for (token in t) {
                    val lower = token.lowercase()
                    when {
                        lower in BORDER_STYLES -> style = lower
                        lower in setOf("thin", "medium", "thick") || CssValues.length(lower, 16f, 16f, null) != null -> width = lower
                        isColor(lower) -> color = token
                    }
                }
                for (side in sidesOf) {
                    out["border-$side-width"] = width
                    out["border-$side-style"] = style
                    out["border-$side-color"] = color
                }
            }
            when (property) {
                "margin" -> sides("margin", "", value)
                "padding" -> sides("padding", "", value)
                "border" -> border(SIDES, value)
                "border-top", "border-right", "border-bottom", "border-left" -> border(listOf(property.substringAfter('-')), value)
                "border-width" -> sides("border", "-width", value)
                "border-style" -> sides("border", "-style", value)
                "border-color" -> sides("border", "-color", value)
                "background" -> {
                    // The first colour named: a plain colour, or a gradient's first stop.
                    val color = Regex("""#[0-9a-fA-F]{3,8}\b|(?:rgba?|hsla?)\([^)]*\)|\b[a-zA-Z]+\b""").findAll(value)
                        .map { it.value }.firstOrNull { isColor(it) && !it.equals("transparent", true) }
                    out["background-color"] = color ?: "transparent"
                }
                "font" -> {
                    val t = tokens(value.replace("/", " / "))
                    val sizeAt = t.indexOfFirst { FONT_KEYWORDS.containsKey(it.lowercase()) || it.endsWith("%") || CssValues.length(it, 16f, 16f, null) != null }
                    if (sizeAt < 0) return
                    out["font-style"] = if (t.take(sizeAt).any { it.equals("italic", true) || it.equals("oblique", true) }) "italic" else "normal"
                    out["font-weight"] = t.take(sizeAt).firstOrNull { it.equals("bold", true) || it.equals("bolder", true) || it.toIntOrNull() != null } ?: "normal"
                    out["font-size"] = t[sizeAt]
                    var rest = sizeAt + 1
                    if (t.getOrNull(rest) == "/") {
                        t.getOrNull(rest + 1)?.let { out["line-height"] = it }
                        rest += 2
                    } else {
                        out["line-height"] = "normal"
                    }
                    if (rest < t.size) out["font-family"] = t.drop(rest).joinToString(" ")
                }
                "list-style" -> tokens(value).firstOrNull { it.lowercase() in LIST_TYPES }?.let { out["list-style-type"] = it }
                "text-decoration" -> out["text-decoration-line"] = value
                "word-wrap", "overflow-wrap" -> Unit
                else -> out[property] = value
            }
        }

        /** What a browser does with no CSS (the parts of its default style sheet that matter here). */
        private val USER_AGENT = Stylesheet().apply {
            add(
                """
                html, address, blockquote, body, center, dialog, div, figure, figcaption, footer, form, header, hr,
                legend, listing, main, p, plaintext, pre, search, xmp, article, aside, h1, h2, h3, h4, h5, h6, hgroup,
                nav, section, dir, dd, dl, dt, menu, ol, ul, details, summary, fieldset, table, caption, thead, tbody,
                tfoot, tr, td, th, optgroup { display: block }
                li { display: list-item }
                area, base, basefont, datalist, head, link, meta, noembed, noframes, param, rp, script, style,
                template, title, colgroup, col, select, option, input[type=hidden] { display: none }
                body { margin: 8px }
                p, blockquote, figure, listing, plaintext, pre, xmp, dl, ul, ol, menu, dir, fieldset, details { margin-top: 1em; margin-bottom: 1em }
                blockquote, figure { margin-left: 40px; margin-right: 40px }
                address, cite, dfn, em, i, var { font-style: italic }
                b, strong, th, h1, h2, h3, h4, h5, h6, summary { font-weight: bold }
                h1 { font-size: 2em; margin-top: .67em; margin-bottom: .67em }
                h2 { font-size: 1.5em; margin-top: .83em; margin-bottom: .83em }
                h3 { font-size: 1.17em; margin-top: 1em; margin-bottom: 1em }
                h4 { margin-top: 1.33em; margin-bottom: 1.33em }
                h5 { font-size: .83em; margin-top: 1.67em; margin-bottom: 1.67em }
                h6 { font-size: .67em; margin-top: 2.33em; margin-bottom: 2.33em }
                pre, code, kbd, samp, tt, listing, plaintext, xmp, textarea { font-family: monospace }
                pre, code, kbd, samp, tt { font-size: .8125em }
                pre code, pre kbd, pre samp, pre tt { font-size: 1em }
                pre, listing, plaintext, xmp, textarea { white-space: pre }
                ul, ol, menu, dir { padding-left: 40px }
                ul ul, ul ol, ol ul, ol ol, ul menu, menu ul, ol menu, menu ol { margin-top: 0; margin-bottom: 0 }
                ul, menu, dir { list-style-type: disc }
                ol { list-style-type: decimal }
                ul ul, ol ul, menu ul, ul menu { list-style-type: circle }
                ul ul ul, ol ul ul, ul ol ul, ol ol ul { list-style-type: square }
                dd { margin-left: 40px }
                hr { margin-top: .5em; margin-bottom: .5em; border-top: 1px solid #9a9a9a; border-bottom: 1px solid #eeeeee }
                u, ins { text-decoration: underline }
                s, strike, del { text-decoration: line-through }
                a:link { color: #0000ee; text-decoration: underline }
                mark { background-color: yellow; color: black }
                small { font-size: smaller }
                big { font-size: larger }
                sub { vertical-align: sub }
                sup { vertical-align: super }
                center, caption { text-align: center }
                th { text-align: center }
                td, th { padding: 1px; vertical-align: middle }
                fieldset { margin-left: 2px; margin-right: 2px; padding: .35em .75em .625em; border: 2px groove #c0c0c0 }
                """.trimIndent()
            )
        }
    }
}
