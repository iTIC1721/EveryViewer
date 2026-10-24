package com.tabpdf.doc.office

/*
 * What a pptx is read into (기획서 5.7): each slide a fixed-size page of shapes placed where they
 * are on the slide. Text inside shapes uses the word-processor model (Paragraph, Table) so the same
 * layout draws it. Lengths are in points, angles in degrees.
 */

class Presentation(val width: Float, val height: Float, val slides: List<SlideContent>)

class SlideContent(val background: Fill?, val shapes: List<SlideShape>)

/** Where a shape sits on the slide; [rotation] is clockwise around its centre. */
data class Frame(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val rotation: Float = 0f,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
)

sealed interface Fill {
    data class Solid(val color: Int) : Fill
    /** Colour stops at 0..1 along a line at [angle] (0 = left to right, clockwise). */
    class Gradient(val stops: List<Pair<Float, Int>>, val angle: Float) : Fill
    class Picture(val bytes: ByteArray) : Fill
}

enum class ArrowKind { Triangle, Arrow, Stealth, Oval, Diamond }

/** An arrowhead [width] × [length] line widths in size. */
class ArrowEnd(val kind: ArrowKind, val width: Float, val length: Float)

class Stroke(
    val width: Float,
    val color: Int,
    /** Dash and gap lengths in line widths, or null for a solid line. */
    val dash: FloatArray? = null,
    /** At the start of the line. */
    val head: ArrowEnd? = null,
    /** At the end of the line. */
    val tail: ArrowEnd? = null,
)

sealed interface Geometry {
    /** One of DrawingML's preset shapes by name, with its adjust values (in 1/100000s). */
    class Preset(val name: String, val adjust: Map<String, Float> = emptyMap()) : Geometry
    class Custom(val paths: List<CustomPath>) : Geometry
}

/** A drawn path in its own [width] × [height] coordinate space, stretched to the shape. */
class CustomPath(val width: Float, val height: Float, val commands: List<PathCommand>, val filled: Boolean, val stroked: Boolean)

sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCommand
    data class QuadTo(val x1: Float, val y1: Float, val x: Float, val y: Float) : PathCommand
    /** An elliptic arc from the current point: radii, start angle and sweep in degrees. */
    data class ArcTo(val wR: Float, val hR: Float, val start: Float, val sweep: Float) : PathCommand
    data object Close : PathCommand
}

enum class TextDirection { Horizontal, Down, Up }

class TextFrame(
    val paragraphs: List<Paragraph>,
    val insets: Insets,
    val anchor: VerticalAlign,
    val wrap: Boolean,
    val direction: TextDirection = TextDirection.Horizontal,
)

sealed interface SlideShape {
    val frame: Frame
}

class ShapeOnSlide(
    override val frame: Frame,
    val geometry: Geometry,
    val fill: Fill?,
    val stroke: Stroke?,
    val text: TextFrame?,
) : SlideShape

/** Colour changes PowerPoint applies to a picture: grey, brightness and contrast (-1..1), opacity. */
data class PictureLook(val grayscale: Boolean = false, val brightness: Float = 0f, val contrast: Float = 0f, val alpha: Float = 1f)

/** A picture; [crop] cuts fractions off its left, top, right and bottom. */
class PictureOnSlide(
    override val frame: Frame,
    val bytes: ByteArray?,
    val crop: FloatArray,
    val stroke: Stroke?,
    val look: PictureLook = PictureLook(),
) : SlideShape

class TableOnSlide(override val frame: Frame, val table: Table) : SlideShape

/** A chart, diagram or other object this viewer doesn't draw: an empty box in its place. */
class UnsupportedOnSlide(override val frame: Frame) : SlideShape
