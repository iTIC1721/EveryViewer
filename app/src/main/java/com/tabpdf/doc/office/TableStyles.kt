package com.tabpdf.doc.office

/**
 * PowerPoint's built-in table styles that decks refer to by id without defining them. Only the
 * default look ("Medium Style 2" in each accent) and the two plain ones; other ids draw unstyled.
 */
internal object TableStyles {
    enum class Part { Whole, Band1, FirstRow, LastRow }

    /** A cell's look in a style; [heavyBorder] is the thick line under the header row (or over a total row). */
    class Look(val fill: Int, val textColor: Int?, val bold: Boolean?, val border: Border?, val heavyBorder: Border?)

    /** Looks for each part of a table, given how to resolve the deck's scheme colours. */
    fun find(id: String?): ((Part, (String) -> Int?) -> Look)? {
        val key = id?.uppercase() ?: return null
        MEDIUM_2[key]?.let { accent -> return { part, scheme -> medium2(part, accent, scheme) } }
        return when (key) {
            "{2D5ABB26-0587-4C30-8999-92F81FD0307C}" -> { _, _ -> Look(0, null, null, null, null) }
            "{5940675A-B579-460E-94D1-54222C63F5DA}" -> { _, scheme ->
                val line = Border(1f, scheme("tx1") ?: BLACK)
                Look(0, null, null, line, line)
            }
            else -> null
        }
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    private val MEDIUM_2 = mapOf(
        "{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}" to "dk1",
        "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" to "accent1",
        "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE8}" to "accent2",
        "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" to "accent3",
        "{00A15C55-8517-42AA-B614-E9B94910E393}" to "accent4",
        "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" to "accent5",
        "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" to "accent6",
    )

    /** Header row in the accent colour with light bold text, body rows in light tints of it, white lines between. */
    private fun medium2(part: Part, accentName: String, scheme: (String) -> Int?): Look {
        val accent = scheme(accentName) ?: BLACK
        val light = scheme("lt1") ?: WHITE
        val dark = scheme("dk1") ?: BLACK
        val line = Border(1f, light)
        val heavy = Border(3f, light)
        return when (part) {
            Part.FirstRow, Part.LastRow -> Look(accent, light, true, line, heavy)
            Part.Band1 -> Look(tint(accent, 0.4f), dark, null, line, heavy)
            Part.Whole -> Look(tint(accent, 0.2f), dark, null, line, heavy)
        }
    }

    /** [amount] of [color], the rest white (DrawingML's tint). */
    private fun tint(color: Int, amount: Float): Int {
        fun ch(shift: Int): Int {
            val c = ((color shr shift) and 0xFF) / 255f
            return ((1 - (1 - c) * amount) * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
}
