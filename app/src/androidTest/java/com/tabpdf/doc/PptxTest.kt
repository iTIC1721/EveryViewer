package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabpdf.doc.office.Align
import com.tabpdf.doc.office.ArrowKind
import com.tabpdf.doc.office.Fill
import com.tabpdf.doc.office.PictureOnSlide
import com.tabpdf.doc.office.PptxReader
import com.tabpdf.doc.office.Run
import com.tabpdf.doc.office.ShapeOnSlide
import com.tabpdf.doc.office.SlideDocument
import com.tabpdf.doc.office.TableOnSlide
import com.tabpdf.doc.office.ZipParts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** pptx reading (기획서 5.7): slide order, inheritance from layout and master, shapes, on a small deck built here. */
@RunWith(AndroidJUnit4::class)
class PptxTest {
    private fun zip(parts: Map<String, String>): ZipParts {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in parts) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return ZipParts.read(ByteArrayInputStream(out.toByteArray()))
    }

    private val ns = """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships""""
    private val relNs = """xmlns="http://schemas.openxmlformats.org/package/2006/relationships""""
    private val type = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    private fun rels(vararg rels: Triple<String, String, String>) =
        "<Relationships $relNs>" + rels.joinToString("") { (id, t, target) -> """<Relationship Id="$id" Type="$type/$t" Target="$target"/>""" } + "</Relationships>"

    private fun sp(ph: String, xfrm: String = "", body: String = "", fill: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="1" name="s"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr>$xfrm$fill</p:spPr>$body</p:sp>"""

    private fun xfrm(x: Int, y: Int, w: Int, h: Int) = """<a:xfrm><a:off x="$x" y="$y"/><a:ext cx="$w" cy="$h"/></a:xfrm>"""

    private fun deck(slide1: String, slide2: String) = zip(
        mapOf(
            "_rels/.rels" to rels(Triple("rId1", "officeDocument", "ppt/presentation.xml")),
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId3"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst>
                <p:sldSz cx="9144000" cy="6858000"/><p:defaultTextStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:defaultTextStyle></p:presentation>""",
            // Slide order comes from the list, not the file names: slide2.xml is shown first.
            "ppt/_rels/presentation.xml.rels" to rels(Triple("rId2", "slide", "slides/slide1.xml"), Triple("rId3", "slide", "slides/slide2.xml")),
            "ppt/slides/slide1.xml" to "<p:sld $ns><p:cSld><p:spTree>$slide1</p:spTree></p:cSld></p:sld>",
            "ppt/slides/slide2.xml" to "<p:sld $ns><p:cSld><p:spTree>$slide2</p:spTree></p:cSld></p:sld>",
            "ppt/slides/_rels/slide1.xml.rels" to rels(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml"), Triple("rId2", "image", "../media/image1.png")),
            "ppt/slides/_rels/slide2.xml.rels" to rels(Triple("rId1", "slideLayout", "../slideLayouts/slideLayout1.xml")),
            "ppt/slideLayouts/slideLayout1.xml" to """<p:sldLayout $ns><p:cSld><p:spTree>
                ${sp("""<p:ph type="title"/>""")}
                ${sp("""<p:ph idx="1"/>""", xfrm(1270000, 2540000, 6350000, 2540000))}
                </p:spTree></p:cSld></p:sldLayout>""",
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to rels(Triple("rId1", "slideMaster", "../slideMasters/slideMaster1.xml")),
            "ppt/slideMasters/slideMaster1.xml" to """<p:sldMaster $ns><p:cSld><p:spTree>
                ${sp("""<p:ph type="title"/>""", xfrm(635000, 254000, 7620000, 1270000))}
                ${sp("""<p:ph type="body" idx="1"/>""", xfrm(635000, 1905000, 7620000, 3810000))}
                ${sp("", xfrm(0, 6604000, 9144000, 254000), fill = """<a:solidFill><a:schemeClr val="accent1"/></a:solidFill>""")}
                </p:spTree></p:cSld>
                <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>
                <p:txStyles>
                  <p:titleStyle><a:lvl1pPr algn="ctr"><a:defRPr sz="4400"/></a:lvl1pPr></p:titleStyle>
                  <p:bodyStyle><a:lvl1pPr marL="342900" indent="-342900"><a:buChar char="•"/><a:defRPr sz="3200"/></a:lvl1pPr>
                    <a:lvl2pPr marL="742950" indent="-285750"><a:buChar char="–"/><a:defRPr sz="2800"/></a:lvl2pPr></p:bodyStyle>
                  <p:otherStyle><a:lvl1pPr><a:defRPr sz="1800"/></a:lvl1pPr></p:otherStyle>
                </p:txStyles></p:sldMaster>""",
            "ppt/slideMasters/_rels/slideMaster1.xml.rels" to rels(Triple("rId1", "theme", "../theme/theme1.xml")),
            "ppt/theme/theme1.xml" to """<a:theme $ns><a:themeElements><a:clrScheme name="t">
                <a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1>
                <a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="4F81BD"/></a:accent1>
                </a:clrScheme><a:fontScheme name="f"><a:majorFont><a:latin typeface="Calibri"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme>
                </a:themeElements></a:theme>""",
            "ppt/media/image1.png" to "not really a png",
        )
    )

    private fun text(vararg paragraphs: String) =
        "<p:txBody><a:bodyPr/><a:lstStyle/>" + paragraphs.joinToString("") + "</p:txBody>"

    @Test
    fun slidesInheritFromLayoutAndMaster() {
        val presentation = PptxReader.read(
            deck(
                slide1 = sp("""<p:ph type="title"/>""", body = text("<a:p><a:r><a:t>Title</a:t></a:r></a:p>")) +
                    sp("""<p:ph idx="1"/>""", body = text("<a:p><a:r><a:t>Point</a:t></a:r></a:p>", """<a:p><a:pPr lvl="1"/><a:r><a:t>Detail</a:t></a:r></a:p>""")) +
                    """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="9" name="g"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
                       <p:grpSpPr><a:xfrm><a:off x="1270000" y="1270000"/><a:ext cx="2540000" cy="2540000"/><a:chOff x="0" y="0"/><a:chExt cx="5080000" cy="5080000"/></a:xfrm></p:grpSpPr>
                       ${sp("", xfrm(2540000, 2540000, 1270000, 1270000), fill = """<a:prstGeom prst="ellipse"/><a:solidFill><a:srgbClr val="00FF00"/></a:solidFill>""")}
                       </p:grpSp>""" +
                    """<p:pic><p:nvPicPr><p:cNvPr id="5" name="p"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                       <p:blipFill><a:blip r:embed="rId2"><a:grayscl/></a:blip><a:srcRect l="10000"/><a:stretch/></p:blipFill>
                       <p:spPr>${xfrm(0, 0, 1270000, 1270000)}</p:spPr></p:pic>""" +
                    """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="6" name="t"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
                       <p:xfrm><a:off x="0" y="5080000"/><a:ext cx="2540000" cy="762000"/></p:xfrm>
                       <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl>
                       <a:tblGrid><a:gridCol w="1270000"/><a:gridCol w="1270000"/></a:tblGrid>
                       <a:tr h="381000"><a:tc gridSpan="2"><a:txBody><a:bodyPr/><a:p><a:r><a:t>wide</a:t></a:r></a:p></a:txBody><a:tcPr/></a:tc><a:tc hMerge="1"><a:txBody><a:bodyPr/><a:p/></a:txBody><a:tcPr/></a:tc></a:tr>
                       </a:tbl></a:graphicData></a:graphic></p:graphicFrame>""",
                slide2 = "",
            )
        )
        assertEquals(720f, presentation.width, 0.01f)
        assertEquals(540f, presentation.height, 0.01f)
        assertEquals(2, presentation.slides.size)
        assertTrue("the first slide in the list is slide2.xml", presentation.slides[0].shapes.none { it is TableOnSlide })

        val shapes = presentation.slides[1].shapes
        // The master's own (non-placeholder) shape is drawn under the slide's, in the theme's accent colour.
        val bar = shapes[0] as ShapeOnSlide
        assertEquals(Fill.Solid(0xFF4F81BD.toInt()), bar.fill)

        // The title takes its place from the master and its look from the master's title style.
        val title = shapes[1] as ShapeOnSlide
        assertEquals(50f, title.frame.x, 0.01f)
        val titleText = title.text!!.paragraphs.single()
        assertEquals(Align.Center, titleText.align)
        assertEquals(44f, (titleText.runs.single() as Run.Text).style.size, 0.01f)

        // The body takes its place from the layout, bullets and sizes per level from the master.
        val body = shapes[2] as ShapeOnSlide
        assertEquals(100f, body.frame.x, 0.01f)
        val (point, detail) = body.text!!.paragraphs
        assertEquals("•", (point.label as Run.Text).text)
        assertEquals(27f, point.indentLeft, 0.01f)
        assertEquals(32f, (point.runs.single() as Run.Text).style.size, 0.01f)
        assertEquals("–", (detail.label as Run.Text).text)
        assertEquals(28f, (detail.runs.single() as Run.Text).style.size, 0.01f)

        // A group scales its children from its own coordinates onto the slide.
        val dot = shapes[3] as ShapeOnSlide
        assertEquals(200f, dot.frame.x, 0.01f)
        assertEquals(50f, dot.frame.width, 0.01f)

        val picture = shapes[4] as PictureOnSlide
        assertEquals(0.1f, picture.crop[0], 0.0001f)
        assertTrue(picture.look.grayscale)

        val table = (shapes[5] as TableOnSlide).table
        assertEquals(listOf(100f, 100f), table.columns)
        assertEquals(2, table.cells.single().columnSpan)
    }

    @Test
    fun backgroundsLinesAndColourChanges() = runBlocking {
        val parts = deck(
            slide1 = "",
            slide2 = """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="2" name="l"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr>
                <p:spPr>${xfrm(0, 0, 2540000, 0)}<a:prstGeom prst="straightConnector1"/><a:ln w="25400"><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill><a:prstDash val="sysDot"/><a:tailEnd type="triangle"/></a:ln></p:spPr></p:cxnSp>""" +
                sp("", xfrm(0, 1270000, 1270000, 1270000), fill = """<a:prstGeom prst="ellipse"/><a:solidFill><a:schemeClr val="accent1"><a:lumMod val="50000"/></a:schemeClr></a:solidFill>"""),
        ).let { z ->
            // Give the first slide (slide2.xml) a red background.
            val bytes = z["ppt/slides/slide2.xml"]!!.decodeToString().replace("<p:cSld>", """<p:cSld><p:bg><p:bgPr><a:solidFill><a:srgbClr val="FF0000"/></a:solidFill></p:bgPr></p:bg>""")
            val names = z.names.associateWith { if (it == "ppt/slides/slide2.xml") bytes else z[it]!!.decodeToString() }
            zip(names)
        }
        val presentation = PptxReader.read(parts)
        val slide = presentation.slides[0]
        assertEquals(Fill.Solid(0xFFFF0000.toInt()), slide.background)
        val line = slide.shapes[1] as ShapeOnSlide
        assertEquals(2f, line.stroke!!.width, 0.01f)
        assertEquals(ArrowKind.Triangle, line.stroke!!.tail!!.kind)
        assertNotNull(line.stroke!!.dash)
        // accent1 4F81BD at half its luminance.
        val dark = ((slide.shapes[2] as ShapeOnSlide).fill as Fill.Solid).color
        assertTrue("darker than the accent", (dark shr 16 and 0xFF) < 0x4F && (dark and 0xFF) < 0xBD)

        val doc = SlideDocument.layOut(presentation)
        assertEquals(2, doc.pageCount)
        assertEquals(0.75f, doc.pageSizes[0].aspect, 0.001f)
        for (page in 0 until doc.pageCount) assertNotNull(doc.renderPage(page, 400))
        doc.close()
    }
}
