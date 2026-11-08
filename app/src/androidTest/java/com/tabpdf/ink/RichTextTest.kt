package com.tabpdf.ink

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Styled text in text boxes (기획서 5.8.6): typing, restyling, paragraphs, storage. */
@RunWith(AndroidJUnit4::class)
class RichTextTest {
    private val plain = CharStyle(24f, 0xFF000000.toInt())
    private val bold = plain.copy(bold = true)
    private val red = plain.copy(color = 0xFFD93025.toInt())

    private fun typed(text: String, style: CharStyle = plain) = RichText.empty(style).replace(0, 0, text, style)

    @Test
    fun typedTextTakesTheStyleOfTheCharacterBefore() {
        var t = typed("ab")
        t = t.restyle(1, 2, { bold })
        assertEquals(bold, t.styleAt(2))
        t = t.replace(2, 2, "c", t.styleAt(2))
        assertEquals(listOf(StyleRun(0, 1, plain), StyleRun(1, 3, bold)), t.runs)
        // At the start: the first character's style.
        assertEquals(plain, t.styleAt(0))
    }

    @Test
    fun keyboardRewritingAWordKeepsItsStyles() {
        // "Hello Bold" with "Bold" bold; the keyboard commits the word the cursor was put in again.
        var t = typed("Hello Bold").restyle(6, 10, { bold })
        val same = t.edit(6, 10, "Bold", null)
        assertEquals(t, same)
        // Corrected in the middle: the new letters take the style of the ones they replace.
        t = t.edit(6, 10, "Bald", null)
        assertEquals("Hello Bald", t.text)
        assertEquals(listOf(StyleRun(0, 6, plain), StyleRun(6, 10, bold)), t.runs)
        // A Hangul syllable being composed: each step replaces it, keeping the chosen style.
        var k = typed("가 ")
        k = k.edit(2, 2, "ㅎ", red).edit(2, 3, "하", null).edit(2, 3, "한", null)
        assertEquals("가 한", k.text)
        assertEquals(red, k.styleAt(3))
        // Typing on after a word: the style before.
        assertEquals(bold, t.edit(10, 10, "!", null).styleAt(11))
    }

    @Test
    fun fieldStylesTheSelectionOrWhatIsTypedNext() {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val f = com.tabpdf.ui.TextBoxField(instrumentation.targetContext, typed("Hello world"), 1.5f)
            f.setSelection(6, 11)
            f.style { it.copy(bold = true) }
            assertEquals(listOf(StyleRun(0, 6, plain), StyleRun(6, 11, bold)), f.rich.runs)
            assertEquals(listOf(bold), f.selectedStyles())
            // Nothing selected: the next characters typed there get it.
            f.setSelection(11)
            f.style { it.copy(italic = true) }
            f.text.insert(11, "!")
            assertEquals(bold.copy(italic = true), f.rich.styleAt(12))
            assertEquals("Hello world!", f.rich.text)
            // Moving away drops it.
            f.setSelection(5)
            f.text.insert(5, ",")
            assertEquals(plain, f.rich.styleAt(6))
            f.setSelection(0, 3)
            f.align(TextAlign.Right)
            assertEquals(listOf(TextAlign.Right), f.rich.aligns)
        }
    }

    @Test
    fun restyleSplitsAndMergesRuns() {
        var t = typed("hello")
        t = t.restyle(1, 3, { it.copy(bold = true) })
        assertEquals(listOf(StyleRun(0, 1, plain), StyleRun(1, 3, bold), StyleRun(3, 5, plain)), t.runs)
        t = t.restyle(1, 3, { it.copy(bold = false) })
        assertEquals(listOf(StyleRun(0, 5, plain)), t.runs)
    }

    @Test
    fun replacingKeepsRunsAroundIt() {
        var t = typed("abcdef").restyle(0, 3, { red })
        t = t.replace(2, 4, "XY", bold)
        assertEquals("abXYef", t.text)
        assertEquals(listOf(StyleRun(0, 2, red), StyleRun(2, 4, bold), StyleRun(4, 6, plain)), t.runs)
        t = t.replace(0, 6, "", plain)
        assertEquals("", t.text)
        assertTrue(t.runs.isEmpty())
    }

    @Test
    fun paragraphsKeepTheirAlignmentAsLinesComeAndGo() {
        var t = typed("one\ntwo")
        assertEquals(listOf(TextAlign.Left, TextAlign.Left), t.aligns)
        t = t.align(5, 5, TextAlign.Center)
        assertEquals(listOf(TextAlign.Left, TextAlign.Center), t.aligns)
        // A new line inside the centred paragraph: both halves centred.
        t = t.replace(6, 6, "\n", plain)
        assertEquals(listOf(TextAlign.Left, TextAlign.Center, TextAlign.Center), t.aligns)
        // Joining the first two: the first one's alignment stays.
        t = t.replace(3, 4, "", plain)
        assertEquals("onetw\no", t.text)
        assertEquals(listOf(TextAlign.Left, TextAlign.Center), t.aligns)
        assertEquals(6, t.paragraphRange(1).first)
    }

    @Test
    fun selectionStylesReportWhatIsThere() {
        val t = typed("abcd").restyle(0, 2, { bold })
        assertEquals(listOf(bold), t.stylesIn(0, 2))
        assertEquals(listOf(bold, plain), t.stylesIn(1, 3))
        assertEquals(listOf(bold), t.stylesIn(2, 2))
    }

    @Test
    fun storedStylesComeBackTheSame() {
        val t = typed("굵게 기울임\n밑줄").restyle(0, 2, { bold }).restyle(3, 6, { it.copy(italic = true, size = 40f) })
            .restyle(7, 9, { it.copy(underline = true, strike = true, color = red.color) }).align(8, 8, TextAlign.Right)
        val back = RichText.fromJson(t.text, t.styleJson(), plain)
        assertEquals(t, back)
        // Unreadable style: the text stays, in the fallback style.
        val broken = RichText.fromJson("abc", "{not json", red)
        assertEquals("abc", broken.text)
        assertEquals(listOf(StyleRun(0, 3, red)), broken.runs)
    }

    @Test
    fun layoutWrapsAtTheBoxWidthWhateverTheScale() {
        val text = typed("가나다라마바사아자차카타파하 abc def ghi jkl mno pqr")
        val box = InkText("t", 0, 0, 100f, 100f, 200f, text)
        val layout = InkTextLayout.layOut(box).layout
        assertTrue(layout.lineCount > 1)
        // The same text at another scale breaks at the same places (the editor lays out at the zoom's px).
        for (scale in listOf(0.37f, 1.7f, 3.1f)) {
            val spanned = android.text.SpannableString(text.text)
            InkTextLayout.applySpans(spanned, text, scale)
            val other = android.text.StaticLayout.Builder.obtain(spanned, 0, spanned.length, InkTextLayout.paint(text.base, scale), (200f * scale).toInt())
                .setIncludePad(false).setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE).build()
            assertEquals("scale $scale", (0 until layout.lineCount).map { layout.getLineEnd(it) }, (0 until other.lineCount).map { other.getLineEnd(it) })
        }
    }

    @Test
    fun growingBoxIsAsWideAsItsText() {
        val box = InkText("t", 0, 0, 100f, 100f, 0f, typed("short"))
        val l = InkTextLayout.layOut(box)
        assertTrue(l.width in 20f..200f)
        val long = InkText("u", 0, 0, 600f, 100f, 0f, typed("a fairly long line of typed text that runs past the edge"))
        val ll = InkTextLayout.layOut(long)
        assertTrue(ll.width <= INK_PAGE_WIDTH - 600f)
        assertTrue(ll.layout.lineCount > 1)
    }
}
