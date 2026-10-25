package com.tabpdf.export

import java.io.OutputStream

/**
 * A PDF of pictures: each page is one JPEG covering it, with handwriting drawn over it as shapes.
 * For pages that only come as pictures (PDFs whose structure can't be followed, photos, bundles).
 * Android's own PDF writer stores pictures uncompressed (a photo page ≈ 5 MB); JPEG keeps it small.
 */
class JpegPdfWriter(out: OutputStream) {
    private val out = Counting(out)
    private val offsets = mutableListOf<Long>() // by object number - 1
    private val pages = mutableListOf<Int>()

    init {
        this.out.ascii("%PDF-1.4\n")
        // Binary bytes in a comment mark the file as binary for transfer programs (7.5.2).
        this.out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        // 1 = catalog, 2 = page tree; written at the end, once the pages are known.
        offsets += 0L
        offsets += 0L
    }

    private fun begin(): Int {
        offsets += out.count
        val num = offsets.size
        out.ascii("$num 0 obj\n")
        return num
    }

    private fun end() = out.ascii("\nendobj\n")

    /** A page [width] × [height] points showing [jpeg] ([pixelWidth] × [pixelHeight]) with [ink] over it. */
    fun addPage(width: Float, height: Float, jpeg: ByteArray, pixelWidth: Int, pixelHeight: Int, ink: PdfInk?) {
        val image = begin()
        out.ascii("<</Type/XObject/Subtype/Image/Width $pixelWidth/Height $pixelHeight/ColorSpace/DeviceRGB/BitsPerComponent 8/Filter/DCTDecode/Length ${jpeg.size}>>\nstream\n")
        out.write(jpeg)
        out.ascii("\nendstream")
        end()

        val w = PdfWriter.number(width)
        val h = PdfWriter.number(height)
        val content = StringBuilder("q $w 0 0 $h 0 0 cm /Im0 Do Q\n")
        if (ink != null && !ink.isEmpty) {
            val s = PdfWriter.number(width / com.tabpdf.ink.INK_PAGE_WIDTH)
            content.append("q 1 0 0 -1 0 $h cm $s 0 0 $s 0 0 cm\n").append(ink.operators).append("Q\n")
        }
        val bytes = content.toString().toByteArray(Charsets.ISO_8859_1)
        val contents = begin()
        out.ascii("<</Length ${bytes.size}>>\nstream\n")
        out.write(bytes)
        out.ascii("\nendstream")
        end()

        val states = ink?.alphas.orEmpty().joinToString("") { "/${PdfInk.stateName(it)} ${PdfInk.state(it)}" }
        pages += begin()
        out.ascii("<</Type/Page/Parent 2 0 R/MediaBox[0 0 $w $h]/Contents $contents 0 R")
        out.ascii("/Resources<</XObject<</Im0 $image 0 R>>" + (if (states.isEmpty()) "" else "/ExtGState<<$states>>") + ">>>>")
        end()
    }

    fun finish() {
        offsets[0] = out.count
        out.ascii("1 0 obj\n<</Type/Catalog/Pages 2 0 R>>")
        end()
        offsets[1] = out.count
        out.ascii("2 0 obj\n<</Type/Pages/Count ${pages.size}/Kids[${pages.joinToString(" ") { "$it 0 R" }}]>>")
        end()
        val start = out.count
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f\r\n")
        for (o in offsets) table.append(String.format(java.util.Locale.ROOT, "%010d 00000 n\r\n", o))
        out.ascii(table.toString())
        out.ascii("trailer\n<</Size ${offsets.size + 1}/Root 1 0 R>>\nstartxref\n$start\n%%EOF\n")
        out.flush()
    }

    private class Counting(private val out: OutputStream) {
        var count = 0L
            private set

        fun write(b: ByteArray) {
            out.write(b)
            count += b.size
        }

        fun ascii(text: String) = write(text.toByteArray(Charsets.ISO_8859_1))

        fun flush() = out.flush()
    }
}
