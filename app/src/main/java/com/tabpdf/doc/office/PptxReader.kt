package com.tabpdf.doc.office

import com.tabpdf.doc.DocumentOpenException
import com.tabpdf.doc.isWebLink
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a pptx into a [Presentation] (기획서 5.7): slides in show order, each with the shapes of
 * its master and layout under its own, placeholders taking position, look and text style from the
 * layout and master as PowerPoint does.
 */
object PptxReader {
    fun read(parts: ZipParts): Presentation = Pptx(parts).read()
}

private const val EMU_PER_PT = 12700f

private fun emu(value: String?): Float? = value?.toFloatOrNull()?.div(EMU_PER_PT)

private class Rel(val target: String, val type: String, val external: Boolean)

private class Part(val path: String, val root: Element, val rels: Map<String, Rel>) {
    fun related(type: String): String? = rels.values.firstOrNull { !it.external && it.type.endsWith("/$type") }?.target
}

/** Colour scheme, fonts and line widths of a theme. */
private class Theme(val colors: Map<String, Int>, val fonts: Map<String, String>, val lineWidths: List<Float>)

/** Maps a child's coordinates (points) into its parent's: a group's scale and offset. */
private data class Xf(val dx: Float = 0f, val dy: Float = 0f, val sx: Float = 1f, val sy: Float = 1f) {
    fun apply(f: Frame) = f.copy(x = dx + f.x * sx, y = dy + f.y * sy, width = f.width * sx, height = f.height * sy)
}

/** Elements consulted in order, the first that has a property giving it (DrawingML inheritance). */
private class Chain(val elements: List<Element>) {
    fun attr(name: String): String? = elements.firstNotNullOfOrNull { it.attr(name) }
    fun child(name: String): Element? = elements.firstNotNullOfOrNull { it.child(name) }
    /** The first element that sets any of [names], and which of them it is. */
    fun firstOf(vararg names: String): Element? = elements.firstNotNullOfOrNull { e -> names.firstNotNullOfOrNull { e.child(it) } }
}

private val FILL_NAMES = arrayOf("a:noFill", "a:solidFill", "a:gradFill", "a:blipFill", "a:pattFill", "a:grpFill")
private val COLOR_NAMES = setOf("a:srgbClr", "a:schemeClr", "a:sysClr", "a:prstClr", "a:scrgbClr", "a:hslClr")

private class Pptx(private val zip: ZipParts) {
    private val cache = mutableMapOf<String, Part?>()
    private lateinit var theme: Theme
    private var defaultTextStyle: Element? = null

    fun part(path: String): Part? = cache.getOrPut(path) { zip.xml(path)?.let { Part(path, it, rels(path)) } }

    private fun rels(path: String): Map<String, Rel> {
        val dir = path.substringBeforeLast('/', "")
        val relsPath = (if (dir.isEmpty()) "" else "$dir/") + "_rels/" + path.substringAfterLast('/') + ".rels"
        val root = zip.xml(relsPath) ?: return emptyMap()
        return root.all("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val target = r.attr("Target") ?: return@mapNotNull null
            val external = r.attr("TargetMode") == "External"
            id to Rel(if (external) target else resolve(dir, target), r.attr("Type") ?: "", external)
        }.toMap()
    }

    private fun resolve(dir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val out = ArrayDeque(if (dir.isEmpty()) emptyList() else dir.split('/'))
        for (piece in target.split('/')) when (piece) {
            ".." -> out.removeLastOrNull()
            ".", "" -> Unit
            else -> out.addLast(piece)
        }
        return out.joinToString("/")
    }

    fun read(): Presentation {
        val presPath = rels("").values.firstOrNull { it.type.endsWith("/officeDocument") }?.target
            ?: "ppt/presentation.xml"
        val pres = part(presPath) ?: throw IOException("No presentation part")
        val size = pres.root.child("p:sldSz")
        val width = emu(size?.attr("cx")) ?: 720f
        val height = emu(size?.attr("cy")) ?: 540f
        defaultTextStyle = pres.root.child("p:defaultTextStyle")
        val slidePaths = pres.root.child("p:sldIdLst")?.all("p:sldId").orEmpty().mapNotNull { pres.rels[it.attr("r:id")]?.target }
        val slides = slidePaths.mapIndexedNotNull { i, path -> part(path)?.let { slide(it, i + 1) } }
        if (slides.isEmpty()) throw DocumentOpenException(DocumentOpenException.Reason.NoPages)
        return Presentation(width, height, slides)
    }

    // ---- Slides ----

    /** What drawing a slide (or its layout or master) needs to know besides its own part. */
    private inner class Scope(
        val part: Part,
        /** Layout and master parts to find placeholders in, nearest first. */
        val inherited: List<Part>,
        val master: Part?,
        val colorMap: Map<String, String>,
        val slideNumber: Int,
    )

    private fun slide(slide: Part, number: Int): SlideContent {
        val layout = slide.related("slideLayout")?.let(::part)
        val master = layout?.related("slideMaster")?.let(::part)
        theme = master?.related("theme")?.let(::part)?.let(::readTheme) ?: Theme(emptyMap(), emptyMap(), emptyList())
        val colorMap = buildMap {
            master?.root?.child("p:clrMap")?.let { putAll(attrs(it)) }
            (slide.root.child("p:clrMapOvr")?.child("a:overrideClrMapping")
                ?: layout?.root?.child("p:clrMapOvr")?.child("a:overrideClrMapping"))?.let { putAll(attrs(it)) }
        }
        val shapes = mutableListOf<SlideShape>()
        val showMaster = slide.root.attr("showMasterSp") != "0"
        if (showMaster && master != null && layout?.root?.attr("showMasterSp") != "0") {
            tree(Scope(master, emptyList(), master, colorMap, number), master.root, Xf(), shapes, skipPlaceholders = true)
        }
        if (showMaster && layout != null) {
            tree(Scope(layout, listOfNotNull(master), master, colorMap, number), layout.root, Xf(), shapes, skipPlaceholders = true)
        }
        tree(Scope(slide, listOfNotNull(layout, master), master, colorMap, number), slide.root, Xf(), shapes, skipPlaceholders = false)

        val background = listOfNotNull(slide, layout, master).firstNotNullOfOrNull { p ->
            val bg = p.root.child("p:cSld")?.child("p:bg") ?: return@firstNotNullOfOrNull null
            val scope = Scope(p, emptyList(), master, colorMap, number)
            bg.child("p:bgPr")?.let { scope.fill(it) } ?: bg.child("p:bgRef")?.let { ref -> scope.color(ref)?.let { Fill.Solid(it) } }
        }
        val title = slide.root.findAll("p:sp").firstOrNull { sp ->
            sp.child("p:nvSpPr")?.child("p:nvPr")?.child("p:ph")?.attr("type") in TITLES
        }?.findAll("a:p")?.joinToString(" ") { p -> p.findAll("a:t").joinToString("") { it.text } }
            ?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        return SlideContent(background, shapes, title)
    }

    private fun attrs(e: Element): Map<String, String> =
        listOf("bg1", "tx1", "bg2", "tx2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
            .mapNotNull { k -> e.attr(k)?.let { k to it } }.toMap()

    private fun tree(scope: Scope, root: Element, xf: Xf, out: MutableList<SlideShape>, skipPlaceholders: Boolean) {
        val spTree = root.child("p:cSld")?.child("p:spTree") ?: return
        shapes(scope, spTree, xf, out, skipPlaceholders)
    }

    private fun shapes(scope: Scope, container: Element, xf: Xf, out: MutableList<SlideShape>, skipPlaceholders: Boolean) {
        for (e in container.children) {
            val element = if (e.name == "mc:AlternateContent") {
                val pick = e.child("mc:Fallback") ?: e.child("mc:Choice") ?: continue
                shapes(scope, pick, xf, out, skipPlaceholders)
                continue
            } else e
            val nv = element.children.firstOrNull { it.name.startsWith("p:nv") }
            if (nv?.child("p:cNvPr")?.attr("hidden").let { it == "1" || it == "true" }) continue
            val ph = nv?.child("p:nvPr")?.child("p:ph")
            if (skipPlaceholders && ph != null) continue
            try {
                when (element.name) {
                    "p:sp", "p:cxnSp" -> scope.shape(element, ph, xf)?.let(out::add)
                    "p:pic" -> scope.picture(element, ph, xf)?.let(out::add)
                    "p:grpSp" -> {
                        val gx = element.child("p:grpSpPr")?.child("a:xfrm")
                        val off = gx?.child("a:off")
                        val ext = gx?.child("a:ext")
                        val chOff = gx?.child("a:chOff")
                        val chExt = gx?.child("a:chExt")
                        val sx = ratio(emu(ext?.attr("cx")), emu(chExt?.attr("cx")))
                        val sy = ratio(emu(ext?.attr("cy")), emu(chExt?.attr("cy")))
                        val dx = (emu(off?.attr("x")) ?: 0f) - (emu(chOff?.attr("x")) ?: 0f) * sx
                        val dy = (emu(off?.attr("y")) ?: 0f) - (emu(chOff?.attr("y")) ?: 0f) * sy
                        shapes(scope, element, Xf(xf.dx + xf.sx * dx, xf.dy + xf.sy * dy, xf.sx * sx, xf.sy * sy), out, skipPlaceholders)
                    }
                    "p:graphicFrame" -> scope.graphicFrame(element, xf)?.let(out::add)
                }
            } catch (e: RuntimeException) {
                android.util.Log.w("EveryViewer", "Skipping a shape that could not be read", e)
            }
        }
    }

    private fun ratio(a: Float?, b: Float?): Float = if (a != null && b != null && b != 0f) a / b else 1f

    private fun frame(xfrm: Element?): Frame? {
        val off = xfrm?.child("a:off") ?: return null
        val ext = xfrm.child("a:ext") ?: return null
        return Frame(
            x = emu(off.attr("x")) ?: 0f,
            y = emu(off.attr("y")) ?: 0f,
            width = emu(ext.attr("cx")) ?: 0f,
            height = emu(ext.attr("cy")) ?: 0f,
            rotation = (xfrm.attr("rot")?.toFloatOrNull() ?: 0f) / 60000f,
            flipH = xfrm.attr("flipH") == "1",
            flipV = xfrm.attr("flipV") == "1",
        )
    }

    // What a Scope draws: member extensions, so they reach both the scope and the whole package.
    private fun Scope.placeholder(ph: Element?): List<Element> {
        if (ph == null) return emptyList()
        val type = ph.attr("type") ?: "obj"
        val idx = ph.attr("idx")
        return inherited.mapNotNull { part ->
            val candidates = part.root.child("p:cSld")?.child("p:spTree")?.children.orEmpty().filter { it.name == "p:sp" }
            fun phOf(sp: Element) = sp.child("p:nvSpPr")?.child("p:nvPr")?.child("p:ph")
            val isMaster = part === master
            if (!isMaster) {
                candidates.firstOrNull { sp -> idx != null && phOf(sp)?.attr("idx") == idx }
                    ?: candidates.firstOrNull { sp -> phOf(sp)?.let { sameType(it.attr("type") ?: "obj", type) } == true }
            } else {
                val wanted = masterType(type)
                candidates.firstOrNull { sp -> phOf(sp)?.let { masterType(it.attr("type") ?: "obj") == wanted } == true }
            }
        }
    }

    private fun sameType(a: String, b: String) = a == b || (a in TITLES && b in TITLES)
    private fun masterType(t: String) = when (t) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum" -> t
        else -> "body"
    }

    /** Which of the master's text styles a placeholder's text follows. */
    private fun Scope.textStyle(ph: Element?): Element? {
        val styles = master?.root?.child("p:txStyles") ?: return null
        if (ph == null) return styles.child("p:otherStyle")
        return when (masterType(ph.attr("type") ?: "obj")) {
            "title" -> styles.child("p:titleStyle")
            "body" -> styles.child("p:bodyStyle")
            else -> styles.child("p:otherStyle")
        }
    }

    private fun Scope.shape(sp: Element, ph: Element?, xf: Xf): SlideShape? {
        val inheritedSps = placeholder(ph)
        val spPr = sp.child("p:spPr")
        val spPrs = listOfNotNull(spPr) + inheritedSps.mapNotNull { it.child("p:spPr") }
        val frame = spPrs.firstNotNullOfOrNull { frame(it.child("a:xfrm")) } ?: return null
        val style = sp.child("p:style")

        val geometry = spPrs.firstNotNullOfOrNull { geometry(it) } ?: Geometry.Preset("rect")
        val fillElement = spPrs.firstNotNullOfOrNull { s -> FILL_NAMES.firstNotNullOfOrNull { s.child(it) }?.let { s } }
        val fill = fillElement?.let { fill(it) }
            ?: style?.child("a:fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { ref -> color(ref)?.let { Fill.Solid(it) } }
        val stroke = stroke(spPrs.firstNotNullOfOrNull { it.child("a:ln") }, style?.child("a:lnRef"))

        val txBody = sp.child("p:txBody")
        val text = txBody?.let {
            val ownStyle = it.child("a:lstStyle")
            val styles = buildList {
                add(ownStyle)
                inheritedSps.forEach { s -> add(s.child("p:txBody")?.child("a:lstStyle")) }
                if (ph == null) add(defaultTextStyle)
                add(textStyle(ph))
                if (ph != null) add(defaultTextStyle)
            }
            val fontRefColor = style?.child("a:fontRef")?.let { ref -> color(ref) }
            val bodyPrs = listOfNotNull(it.child("a:bodyPr")) + inheritedSps.mapNotNull { s -> s.child("p:txBody")?.child("a:bodyPr") }
            textFrame(it, bodyPrs, styles, TextFallback(fontRefColor, null))
        }
        val f = xf.apply(frame)
        if (text == null && fill == null && stroke == null) return null
        return ShapeOnSlide(f, geometry, fill, stroke, text)
    }

    private fun geometry(spPr: Element): Geometry? {
        spPr.child("a:prstGeom")?.let { g ->
            val adjust = g.child("a:avLst")?.all("a:gd").orEmpty().mapNotNull { gd ->
                val v = gd.attr("fmla")?.removePrefix("val ")?.trim()?.toFloatOrNull() ?: return@mapNotNull null
                (gd.attr("name") ?: return@mapNotNull null) to v
            }.toMap()
            return Geometry.Preset(g.attr("prst") ?: "rect", adjust)
        }
        val cust = spPr.child("a:custGeom") ?: return null
        val paths = cust.child("a:pathLst")?.all("a:path").orEmpty().map { p ->
            val commands = p.children.mapNotNull { c ->
                val pts = c.all("a:pt").map { (it.attr("x")?.toFloatOrNull() ?: 0f) to (it.attr("y")?.toFloatOrNull() ?: 0f) }
                when (c.name) {
                    "a:moveTo" -> pts.firstOrNull()?.let { PathCommand.MoveTo(it.first, it.second) }
                    "a:lnTo" -> pts.firstOrNull()?.let { PathCommand.LineTo(it.first, it.second) }
                    "a:cubicBezTo" -> if (pts.size >= 3) PathCommand.CubicTo(pts[0].first, pts[0].second, pts[1].first, pts[1].second, pts[2].first, pts[2].second) else null
                    "a:quadBezTo" -> if (pts.size >= 2) PathCommand.QuadTo(pts[0].first, pts[0].second, pts[1].first, pts[1].second) else null
                    "a:arcTo" -> PathCommand.ArcTo(
                        c.attr("wR")?.toFloatOrNull() ?: 0f,
                        c.attr("hR")?.toFloatOrNull() ?: 0f,
                        (c.attr("stAng")?.toFloatOrNull() ?: 0f) / 60000f,
                        (c.attr("swAng")?.toFloatOrNull() ?: 0f) / 60000f,
                    )
                    "a:close" -> PathCommand.Close
                    else -> null
                }
            }
            CustomPath(
                p.attr("w")?.toFloatOrNull() ?: 0f,
                p.attr("h")?.toFloatOrNull() ?: 0f,
                commands,
                filled = p.attr("fill") != "none",
                stroked = p.attr("stroke").let { it != "0" && it != "false" },
            )
        }
        return Geometry.Custom(paths)
    }

    /** The fill set in [holder] (spPr, bgPr, tcPr), or null if it sets none. No fill is a transparent solid. */
    private fun Scope.fill(holder: Element, phColor: Int? = null): Fill? {
        for (name in FILL_NAMES) {
            val f = holder.child(name) ?: continue
            return when (name) {
                "a:noFill" -> Fill.Solid(0)
                "a:solidFill" -> Fill.Solid(color(f, phColor) ?: 0xFF000000.toInt())
                "a:gradFill" -> {
                    val stops = f.child("a:gsLst")?.all("a:gs").orEmpty().mapNotNull { gs ->
                        val c = color(gs, phColor) ?: return@mapNotNull null
                        ((gs.attr("pos")?.toFloatOrNull() ?: 0f) / 100000f) to c
                    }.sortedBy { it.first }
                    when {
                        stops.isEmpty() -> null
                        stops.size == 1 -> Fill.Solid(stops[0].second)
                        else -> Fill.Gradient(stops, (f.child("a:lin")?.attr("ang")?.toFloatOrNull() ?: 5400000f) / 60000f)
                    }
                }
                "a:blipFill" -> picture(f)?.let { Fill.Picture(it) } ?: Fill.Solid(0)
                "a:pattFill" -> Fill.Solid(f.child("a:fgClr")?.let { color(it, phColor) } ?: 0xFF000000.toInt())
                else -> null
            }
        }
        return null
    }

    private fun Scope.picture(blipFill: Element): ByteArray? {
        val id = blipFill.child("a:blip")?.attr("r:embed") ?: return null
        val rel = part.rels[id]?.takeIf { !it.external } ?: return null
        return zip[rel.target]
    }

    private fun Scope.stroke(ln: Element?, ref: Element?): Stroke? {
        val refIdx = ref?.attr("idx")?.toIntOrNull() ?: 0
        if (ln?.child("a:noFill") != null) return null
        val color = ln?.child("a:solidFill")?.let { color(it) }
            ?: ln?.child("a:gradFill")?.child("a:gsLst")?.child("a:gs")?.let { color(it) }
            ?: ref?.takeIf { refIdx > 0 }?.let { color(it) }
            ?: return null
        val width = emu(ln?.attr("w")) ?: theme.lineWidths.getOrNull(refIdx - 1) ?: 0.75f
        val dash = when (ln?.child("a:prstDash")?.attr("val")) {
            "sysDot" -> floatArrayOf(1f, 1f)
            "dot" -> floatArrayOf(1f, 3f)
            "sysDash" -> floatArrayOf(3f, 1f)
            "dash" -> floatArrayOf(4f, 3f)
            "lgDash" -> floatArrayOf(8f, 3f)
            "dashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
            "sysDashDot" -> floatArrayOf(3f, 1f, 1f, 1f)
            "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
            "lgDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
            "sysDashDotDot" -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
            else -> if (ln?.child("a:custDash") != null) floatArrayOf(4f, 3f) else null
        }
        return Stroke(width, color, dash, arrow(ln?.child("a:headEnd")), arrow(ln?.child("a:tailEnd")))
    }

    private fun arrow(end: Element?): ArrowEnd? {
        val kind = when (end?.attr("type")) {
            "triangle" -> ArrowKind.Triangle
            "arrow" -> ArrowKind.Arrow
            "stealth" -> ArrowKind.Stealth
            "oval" -> ArrowKind.Oval
            "diamond" -> ArrowKind.Diamond
            else -> return null
        }
        fun size(v: String?) = when (v) {
            "sm" -> 2f
            "lg" -> 5f
            else -> 3f
        }
        return ArrowEnd(kind, size(end.attr("w")), size(end.attr("len")))
    }

    private fun Scope.picture(pic: Element, ph: Element?, xf: Xf): SlideShape? {
        val spPr = pic.child("p:spPr")
        val frame = frame(spPr?.child("a:xfrm"))
            ?: placeholder(ph).firstNotNullOfOrNull { frame(it.child("p:spPr")?.child("a:xfrm")) }
            ?: return null
        val blipFill = pic.child("p:blipFill")
        val srcRect = blipFill?.child("a:srcRect")
        val crop = FloatArray(4) { i -> (srcRect?.attr(CROP_SIDES[i])?.toFloatOrNull() ?: 0f) / 100000f }
        val stroke = stroke(spPr?.child("a:ln"), pic.child("p:style")?.child("a:lnRef"))
        val blip = blipFill?.child("a:blip")
        val lum = blip?.child("a:lum")
        val look = PictureLook(
            grayscale = blip?.child("a:grayscl") != null,
            brightness = (lum?.attr("bright")?.toFloatOrNull() ?: 0f) / 100000f,
            contrast = (lum?.attr("contrast")?.toFloatOrNull() ?: 0f) / 100000f,
            alpha = (blip?.child("a:alphaModFix")?.attr("amt")?.toFloatOrNull() ?: 100000f) / 100000f,
        )
        return PictureOnSlide(xf.apply(frame), blipFill?.let { picture(it) }, crop, stroke, look)
    }

    private fun Scope.graphicFrame(frameElement: Element, xf: Xf): SlideShape? {
        val frame = frame(frameElement.child("p:xfrm"))?.let(xf::apply) ?: return null
        val data = frameElement.child("a:graphic")?.child("a:graphicData") ?: return UnsupportedOnSlide(frame)
        data.child("a:tbl")?.let { return TableOnSlide(frame, table(it)) }
        // An embedded object usually carries a picture of itself.
        data.find("p:pic")?.let { pic ->
            val bytes = pic.child("p:blipFill")?.let { picture(it) }
            if (bytes != null) return PictureOnSlide(frame, bytes, FloatArray(4), null)
        }
        return UnsupportedOnSlide(frame)
    }

    // ---- Text ----

    /** Text colour and boldness to use where the text's own styles give none (a shape style, a table style). */
    private class TextFallback(val color: Int?, val bold: Boolean?)

    private fun Scope.textFrame(txBody: Element, bodyPrs: List<Element>, styles: List<Element?>, fallback: TextFallback): TextFrame? {
        val body = Chain(bodyPrs)
        val own = bodyPrs.firstOrNull()
        val autofit = own?.child("a:normAutofit")
        val fontScale = (autofit?.attr("fontScale")?.toFloatOrNull() ?: 100000f) / 100000f
        val reduction = (autofit?.attr("lnSpcReduction")?.toFloatOrNull() ?: 0f) / 100000f
        val paragraphs = paragraphs(txBody, styles, fallback, fontScale, reduction)
        if (paragraphs.isEmpty()) return null
        return TextFrame(
            paragraphs,
            Insets(
                emu(body.attr("lIns")) ?: 7.2f,
                emu(body.attr("tIns")) ?: 3.6f,
                emu(body.attr("rIns")) ?: 7.2f,
                emu(body.attr("bIns")) ?: 3.6f,
            ),
            when (body.attr("anchor")) {
                "ctr" -> VerticalAlign.Center
                "b" -> VerticalAlign.Bottom
                else -> VerticalAlign.Top
            },
            wrap = body.attr("wrap") != "none",
            direction = when (body.attr("vert")) {
                "vert", "eaVert", "wordArtVert", "mongolianVert" -> TextDirection.Down
                "vert270" -> TextDirection.Up
                else -> TextDirection.Horizontal
            },
        )
    }

    private fun Scope.paragraphs(txBody: Element, styles: List<Element?>, fallback: TextFallback, fontScale: Float, reduction: Float): List<Paragraph> {
        val ps = txBody.all("a:p")
        if (ps.all { p -> p.children.none { it.name == "a:r" || it.name == "a:fld" } }) return emptyList()
        val counters = IntArray(9)
        return ps.mapIndexed { index, p ->
            val pPr = p.child("a:pPr")
            val level = (pPr?.attr("lvl")?.toIntOrNull() ?: 0).coerceIn(0, 8)
            val levelName = "a:lvl${level + 1}pPr"
            val ownLevel = styles.firstOrNull()?.child(levelName)
            val para = Chain(listOfNotNull(pPr) + styles.mapNotNull { it?.child(levelName) })
            // The shape's own list style comes before a shape style's text colour, the inherited ones after.
            val firstStyles = listOfNotNull(pPr?.child("a:defRPr"), ownLevel?.child("a:defRPr"))
            val restStyles = styles.drop(1).mapNotNull { it?.child(levelName)?.child("a:defRPr") }

            fun style(rPr: Element?): TextStyle = runStyle(rPr, firstStyles, restStyles, fallback, fontScale)

            val runs = mutableListOf<Run>()
            for (c in p.children) {
                when (c.name) {
                    "a:r", "a:fld" -> {
                        val rPr = c.child("a:rPr")
                        var text = if (c.name == "a:fld" && c.attr("type") == "slidenum") slideNumber.toString() else c.child("a:t")?.text.orEmpty()
                        if (Chain(listOfNotNull(rPr) + firstStyles + restStyles).attr("cap") == "all") text = text.uppercase()
                        if (text.isEmpty()) continue
                        val link = rPr?.child("a:hlinkClick")?.attr("r:id")?.let { part.rels[it] }
                            ?.takeIf { it.external }?.target?.takeIf { isWebLink(it) }
                        var s = style(rPr)
                        if (link != null) s = s.copy(color = scheme("hlink") ?: s.color, underline = true)
                        runs += Run.Text(text, s, link)
                    }
                    "a:br" -> runs += Run.Text("\n", style(c.child("a:rPr")))
                }
            }
            val markStyle = style(p.child("a:endParaRPr"))
            val size = runs.filterIsInstance<Run.Text>().maxOfOrNull { it.style.size } ?: markStyle.size

            val lnSpc = para.child("a:lnSpc")
            val lineSpacing = lnSpc?.child("a:spcPts")?.attr("val")?.toFloatOrNull()?.let { LineSpacing.Exactly(it / 100f) }
                ?: run {
                    val pct = ((lnSpc?.child("a:spcPct")?.attr("val")?.toFloatOrNull() ?: 100000f) / 100000f - reduction).coerceAtLeast(0.1f)
                    val factor = SINGLE_LINE * pct
                    LineSpacing.Multiple(factor, baseline = factor - SINGLE_LINE + BASELINE, textHeight = factor)
                }
            fun spacing(name: String): Float {
                val s = para.child(name) ?: return 0f
                s.child("a:spcPts")?.attr("val")?.toFloatOrNull()?.let { return it / 100f }
                return (s.child("a:spcPct")?.attr("val")?.toFloatOrNull() ?: 0f) / 100000f * size * SINGLE_LINE
            }

            val firstText = runs.firstOrNull { it is Run.Text && it.text.isNotBlank() } as? Run.Text
            val label = firstText?.let { bullet(para, level, counters, it.style) }
            if (label == null) counters.fill(0, level, 9)

            Paragraph(
                runs = runs,
                align = when (para.attr("algn")) {
                    "ctr" -> Align.Center
                    "r" -> Align.Right
                    "just", "dist" -> Align.Justify
                    else -> Align.Left
                },
                indentLeft = emu(para.attr("marL")) ?: 0f,
                indentRight = emu(para.attr("marR")) ?: 0f,
                firstLine = emu(para.attr("indent")) ?: 0f,
                // PowerPoint leaves out the space before the first paragraph of a text box.
                spaceBefore = if (index == 0) 0f else spacing("a:spcBef"),
                spaceAfter = spacing("a:spcAft"),
                lineSpacing = lineSpacing,
                label = label,
                labelInHang = true,
                markStyle = markStyle,
            )
        }
    }

    private fun Scope.runStyle(rPr: Element?, first: List<Element>, rest: List<Element>, fallback: TextFallback, fontScale: Float): TextStyle {
        val chain = Chain(listOfNotNull(rPr) + first + rest)
        val color = Chain(listOfNotNull(rPr) + first).firstOf(*FILL_NAMES)?.let { textColor(it) }
            ?: fallback.color
            ?: Chain(rest).firstOf(*FILL_NAMES)?.let { textColor(it) }
            ?: scheme("tx1")
            ?: 0xFF000000.toInt()
        val typeface = (chain.child("a:latin")?.attr("typeface")?.takeIf { it.isNotEmpty() }
            ?: chain.child("a:ea")?.attr("typeface"))?.let(::themeFont).orEmpty()
        val heavy = listOf("black", "heavy", "헤드라인").any { it in typeface.lowercase() }
        val baseline = chain.attr("baseline")?.toIntOrNull() ?: 0
        val bold = chain.attr("b")?.let { it == "1" || it == "true" } ?: fallback.bold ?: heavy
        return TextStyle(
            size = (chain.attr("sz")?.toFloatOrNull()?.div(100f) ?: 18f) * fontScale,
            bold = bold,
            scaleX = fontWidth(typeface, bold),
            italic = chain.attr("i").let { it == "1" || it == "true" },
            underline = chain.attr("u").let { it != null && it != "none" },
            strike = chain.attr("strike").let { it != null && it != "noStrike" },
            color = color,
            background = chain.child("a:highlight")?.let { color(it) } ?: 0,
            font = fontKind(typeface),
            superscript = baseline > 0,
            subscript = baseline < 0,
        )
    }

    /**
     * How much wider than the system sans serif a common slide font sets text, so lines break near
     * where they do in PowerPoint (measured on PowerPoint's own rendering).
     */
    private fun fontWidth(typeface: String, bold: Boolean): Float = when (typeface.lowercase()) {
        "tahoma" -> if (bold) 1.12f else 1f
        "verdana" -> if (bold) 1.2f else 1.13f
        "arial black" -> 1.2f
        "calibri", "calibri light" -> 0.9f
        else -> 1f
    }

    private fun Scope.textColor(fill: Element): Int? = when (fill.name) {
        "a:solidFill" -> color(fill)
        "a:gradFill" -> fill.child("a:gsLst")?.child("a:gs")?.let { color(it) }
        "a:noFill" -> 0
        else -> null
    }

    private fun themeFont(name: String): String = when (name) {
        "+mj-lt" -> theme.fonts["majorLatin"]
        "+mn-lt" -> theme.fonts["minorLatin"]
        "+mj-ea" -> theme.fonts["majorEa"]
        "+mn-ea" -> theme.fonts["minorEa"]
        else -> name
    } ?: name

    /** The bullet or number of a paragraph, or null if it has none. */
    private fun Scope.bullet(para: Chain, level: Int, counters: IntArray, textStyle: TextStyle): Run? {
        val kind = para.firstOf("a:buNone", "a:buChar", "a:buAutoNum", "a:buBlip") ?: return null
        if (kind.name == "a:buNone" || kind.name == "a:buBlip") return null
        val size = para.firstOf("a:buSzPct", "a:buSzPts", "a:buSzTx").let { s ->
            when (s?.name) {
                "a:buSzPct" -> textStyle.size * (s.attr("val")?.toFloatOrNull() ?: 100000f) / 100000f
                "a:buSzPts" -> (s.attr("val")?.toFloatOrNull() ?: 1200f) / 100f
                else -> textStyle.size
            }
        }
        val color = para.firstOf("a:buClr", "a:buClrTx")?.takeIf { it.name == "a:buClr" }?.let { color(it) } ?: textStyle.color
        val font = para.firstOf("a:buFont", "a:buFontTx")?.takeIf { it.name == "a:buFont" }?.attr("typeface")
        val text = if (kind.name == "a:buChar") {
            symbol(kind.attr("char") ?: "•", font)
        } else {
            for (i in level + 1 until counters.size) counters[i] = 0
            val start = kind.attr("startAt")?.toIntOrNull() ?: 1
            counters[level] = if (counters[level] == 0) start else counters[level] + 1
            autoNumber(counters[level], kind.attr("type") ?: "arabicPeriod")
        }
        return Run.Text(text, TextStyle(size = size, color = color, font = if (kind.name == "a:buChar") FontKind.Sans else textStyle.font, bold = kind.name == "a:buAutoNum" && textStyle.bold))
    }

    /** Symbol fonts put their pictures at letter codes; show the Unicode look-alike. */
    private fun symbol(char: String, font: String?): String {
        val f = font?.lowercase().orEmpty()
        val c = char.firstOrNull() ?: return "•"
        if ("wingdings" in f) return when (c) {
            'l' -> "●"; 'n' -> "■"; 'q' -> "❑"; 'u' -> "◆"; 'v' -> "❖"; 'w' -> "⬥"; 'o' -> "❍"; 'p' -> "□"
            'ü' -> "✓"; 'û' -> "✗"; 'Ø' -> "➢"; '§' -> "▪"; 'à' -> "➔"; 'è' -> "➔"; 'ð' -> "⇨"; 'Ÿ' -> "•"; 'ª' -> "✦"
            else -> "•"
        }
        if ("symbol" in f) return when (c) {
            '·' -> "•"; 'Ø' -> "Ø"; '®' -> "→"
            else -> c.toString()
        }
        return c.toString()
    }

    private fun autoNumber(n: Int, type: String): String {
        val format = when {
            type.startsWith("alphaLc") -> "lowerLetter"
            type.startsWith("alphaUc") -> "upperLetter"
            type.startsWith("romanLc") -> "lowerRoman"
            type.startsWith("romanUc") -> "upperRoman"
            type.startsWith("circleNum") -> "decimalEnclosedCircle"
            else -> "decimal"
        }
        val number = formatNumber(n, format)
        return when {
            type.endsWith("ParenBoth") -> "($number)"
            type.endsWith("ParenR") -> "$number)"
            type.endsWith("Plain") || format == "decimalEnclosedCircle" -> number
            else -> "$number."
        }
    }

    // ---- Tables ----

    private fun Scope.table(tbl: Element): Table {
        val tblPr = tbl.child("a:tblPr")
        fun flag(name: String) = tblPr?.attr(name).let { it == "1" || it == "true" }
        val tableStyle = TableStyles.find(tblPr?.child("a:tableStyleId")?.text?.trim())
        val columns = tbl.child("a:tblGrid")?.all("a:gridCol").orEmpty().map { emu(it.attr("w")) ?: 0f }
        val trs = tbl.all("a:tr")
        val rows = trs.map { TableRow(minHeight = emu(it.attr("h")) ?: 0f) }
        val cells = mutableListOf<TableCell>()
        val firstRow = flag("firstRow")
        val lastRow = flag("lastRow")
        val bandRow = flag("bandRow")
        for ((r, tr) in trs.withIndex()) {
            for ((c, tc) in tr.all("a:tc").withIndex()) {
                if (tc.attr("hMerge") == "1" || tc.attr("vMerge") == "1") continue
                val tcPr = tc.child("a:tcPr")
                val part = when {
                    firstRow && r == 0 -> TableStyles.Part.FirstRow
                    lastRow && r == trs.lastIndex -> TableStyles.Part.LastRow
                    bandRow && (r - if (firstRow) 1 else 0) % 2 == 0 -> TableStyles.Part.Band1
                    else -> TableStyles.Part.Whole
                }
                val look = tableStyle?.invoke(part) { name -> scheme(name) }
                val styles = listOf(tc.child("a:txBody")?.child("a:lstStyle"), defaultTextStyle, master?.root?.child("p:txStyles")?.child("p:otherStyle"))
                val blocks = tc.child("a:txBody")?.let { paragraphs(it, styles, TextFallback(look?.textColor, look?.bold), 1f, 0f) }.orEmpty()
                fun border(name: String, default: Border?): Border? {
                    val ln = tcPr?.child(name) ?: return default
                    if (ln.child("a:noFill") != null) return null
                    val color = ln.child("a:solidFill")?.let { color(it) } ?: return default
                    return Border(emu(ln.attr("w")) ?: 1f, color)
                }
                val fill = tcPr?.let { fill(it) }
                cells += TableCell(
                    row = r,
                    column = c,
                    rowSpan = tc.attr("rowSpan")?.toIntOrNull() ?: 1,
                    columnSpan = tc.attr("gridSpan")?.toIntOrNull() ?: 1,
                    blocks = blocks,
                    fill = when (fill) {
                        is Fill.Solid -> fill.color
                        is Fill.Gradient -> fill.stops.first().second
                        else -> look?.fill ?: 0
                    },
                    borders = Borders(
                        border("a:lnL", look?.border),
                        border("a:lnT", if (part == TableStyles.Part.LastRow) look?.heavyBorder else look?.border),
                        border("a:lnR", look?.border),
                        border("a:lnB", if (part == TableStyles.Part.FirstRow) look?.heavyBorder else look?.border),
                    ),
                    padding = Insets(
                        emu(tcPr?.attr("marL")) ?: 7.2f,
                        emu(tcPr?.attr("marT")) ?: 3.6f,
                        emu(tcPr?.attr("marR")) ?: 7.2f,
                        emu(tcPr?.attr("marB")) ?: 3.6f,
                    ),
                    verticalAlign = when (tcPr?.attr("anchor")) {
                        "ctr" -> VerticalAlign.Center
                        "b" -> VerticalAlign.Bottom
                        else -> VerticalAlign.Top
                    },
                )
            }
        }
        return Table(columns, rows, cells)
    }

    // ---- Colours ----

    private fun Scope.scheme(name: String, phColor: Int? = null): Int? {
        if (name == "phClr") return phColor
        val mapped = colorMap[name] ?: when (name) {
            "bg1" -> "lt1"
            "tx1" -> "dk1"
            "bg2" -> "lt2"
            "tx2" -> "dk2"
            else -> name
        }
        return theme.colors[mapped]
    }

    /** The colour given by the first colour element inside [holder], with its modifiers applied. */
    private fun Scope.color(holder: Element, phColor: Int? = null): Int? {
        val c = holder.children.firstOrNull { it.name in COLOR_NAMES } ?: return null
        val base = when (c.name) {
            "a:srgbClr" -> parseColor(c.attr("val"))
            "a:schemeClr" -> scheme(c.attr("val") ?: return null, phColor)
            "a:sysClr" -> parseColor(c.attr("lastClr")) ?: if (c.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            "a:prstClr" -> presetColor(c.attr("val"))
            "a:scrgbClr" -> {
                fun ch(n: String) = ((c.attr(n)?.toFloatOrNull() ?: 0f) / 100000f * 255f).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (ch("r") shl 16) or (ch("g") shl 8) or ch("b")
            }
            else -> null
        } ?: return null
        return Colors.modify(base, c.children)
    }

    private fun presetColor(name: String?): Int? = when (name) {
        "black" -> 0xFF000000
        "white" -> 0xFFFFFFFF
        "red" -> 0xFFFF0000
        "green" -> 0xFF008000
        "lime" -> 0xFF00FF00
        "blue" -> 0xFF0000FF
        "yellow" -> 0xFFFFFF00
        "gray", "grey" -> 0xFF808080
        "orange" -> 0xFFFFA500
        "darkBlue" -> 0xFF00008B
        "darkRed" -> 0xFF8B0000
        else -> null
    }?.toInt()

    private fun readTheme(part: Part): Theme {
        val elements = part.root.child("a:themeElements")
        val colors = elements?.child("a:clrScheme")?.children.orEmpty().mapNotNull { e ->
            val c = e.child("a:srgbClr")?.attr("val")?.let(::parseColor)
                ?: e.child("a:sysClr")?.let { parseColor(it.attr("lastClr")) ?: if (it.attr("val") == "window") 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
                ?: return@mapNotNull null
            e.localName to c
        }.toMap()
        val fontScheme = elements?.child("a:fontScheme")
        val fonts = buildMap {
            fontScheme?.child("a:majorFont")?.let { m ->
                m.child("a:latin")?.attr("typeface")?.let { put("majorLatin", it) }
                m.child("a:ea")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { put("majorEa", it) }
            }
            fontScheme?.child("a:minorFont")?.let { m ->
                m.child("a:latin")?.attr("typeface")?.let { put("minorLatin", it) }
                m.child("a:ea")?.attr("typeface")?.takeIf { it.isNotEmpty() }?.let { put("minorEa", it) }
            }
        }
        val lineWidths = elements?.child("a:fmtScheme")?.child("a:lnStyleLst")?.all("a:ln").orEmpty().map { emu(it.attr("w")) ?: 0.75f }
        return Theme(colors, fonts, lineWidths)
    }

    private companion object {
        val TITLES = setOf("title", "ctrTitle")
        val CROP_SIDES = arrayOf("l", "t", "r", "b")

        /** PowerPoint's single line pitch, in font sizes, and where the baseline sits in it. */
        const val SINGLE_LINE = 1.2f
        const val BASELINE = 1.0f
    }
}

/** Colour modifiers of DrawingML (tint, shade, luminance, alpha). */
internal object Colors {
    fun modify(color: Int, modifiers: List<Element>): Int {
        if (modifiers.isEmpty()) return color
        var r = ((color shr 16) and 0xFF) / 255f
        var g = ((color shr 8) and 0xFF) / 255f
        var b = (color and 0xFF) / 255f
        var a = ((color ushr 24) and 0xFF) / 255f
        for (m in modifiers) {
            val v = (m.attr("val")?.toFloatOrNull() ?: continue) / 100000f
            when (m.name) {
                "a:tint" -> { r = 1 - (1 - r) * v; g = 1 - (1 - g) * v; b = 1 - (1 - b) * v }
                "a:shade" -> { r *= v; g *= v; b *= v }
                "a:alpha" -> a = v
                "a:lumMod", "a:lumOff", "a:satMod" -> {
                    val hsl = toHsl(r, g, b)
                    when (m.name) {
                        "a:lumMod" -> hsl[2] *= v
                        "a:lumOff" -> hsl[2] += v
                        else -> hsl[1] *= v
                    }
                    hsl[1] = hsl[1].coerceIn(0f, 1f)
                    hsl[2] = hsl[2].coerceIn(0f, 1f)
                    val rgb = fromHsl(hsl[0], hsl[1], hsl[2])
                    r = rgb[0]; g = rgb[1]; b = rgb[2]
                }
            }
        }
        fun ch(x: Float) = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun toHsl(r: Float, g: Float, b: Float): FloatArray {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return floatArrayOf(0f, 0f, l)
        val d = mx - mn
        val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } / 6
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1
            if (t > 1) t -= 1
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return floatArrayOf(hue(h + 1f / 3), hue(h), hue(h - 1f / 3))
    }
}
