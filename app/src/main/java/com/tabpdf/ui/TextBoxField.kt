package com.tabpdf.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.tabpdf.ink.CharStyle
import com.tabpdf.ink.InkFonts
import com.tabpdf.ink.InkTextLayout
import com.tabpdf.ink.RichText
import com.tabpdf.ink.TextAlign

/**
 * The text box being typed into (기획서 5.8.6): Android's own text field, so the keyboard, Hangul
 * composition and selection handles are the system's, set up to lay out exactly as the box is drawn
 * on the page ([InkTextLayout]) at [scale] px per page unit. It keeps [rich], the styled text, in
 * step with what is typed: new characters take the style of the one before them, or [pending] when a
 * style was chosen with nothing selected.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
class TextBoxField(context: Context, initial: RichText, scale: Float) : EditText(context) {
    var rich: RichText = initial
        private set

    var scale: Float = scale
        private set

    /** How the next typed characters look, when chosen with nothing selected. */
    private var pending: CharStyle? = null

    /** Told after each change of the text, its styles, or the selection. */
    var onChange: () -> Unit = {}

    private var loading = false
    private var caretAfterEdit = -1

    init {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        setLineSpacing(0f, 1f)
        letterSpacing = 0f
        typeface = InkFonts.regular
        paintFlags = paintFlags or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        isElegantTextHeight = false
        if (Build.VERSION.SDK_INT >= 28) isFallbackLineSpacing = false
        if (Build.VERSION.SDK_INT >= 35) setUseBoundsForWidth(false)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHorizontallyScrolling(false)
        maxLines = Int.MAX_VALUE
        applyBase()
        loading = true
        setText(initial.text)
        loading = false
        restyle()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                if (loading) return
                rich = rich.edit(start, start + before, s.subSequence(start, start + count).toString(), pending)
                caretAfterEdit = start + count
            }

            override fun afterTextChanged(s: Editable) {
                if (loading) return
                restyle()
                onChange()
            }
        })
    }

    /** Laid out at a new zoom. */
    fun rescale(scale: Float) {
        if (scale == this.scale) return
        this.scale = scale
        applyBase()
        restyle()
    }

    private fun applyBase() {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, rich.base.size * scale)
        setTextColor(rich.base.color)
    }

    private fun restyle() {
        (text as? Spannable)?.let { InkTextLayout.applySpans(it, rich, scale) }
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        // Moving the cursor elsewhere drops a style chosen for typing; typing on keeps it.
        if (!(selStart == selEnd && selStart == caretAfterEdit)) pending = null
        caretAfterEdit = -1
        @Suppress("SENSELESS_COMPARISON") // called by EditText's constructor, before ours runs
        if (rich != null) onChange()
    }

    // Pasted text comes in plain, then takes the box's styles.
    override fun onTextContextMenuItem(id: Int): Boolean =
        super.onTextContextMenuItem(if (id == android.R.id.paste) android.R.id.pasteAsPlainText else id)

    private val range get() = minOf(selectionStart, selectionEnd).coerceAtLeast(0) to maxOf(selectionStart, selectionEnd).coerceAtLeast(0)

    /** How the selected characters look, or the next typed ones. */
    fun selectedStyles(): List<CharStyle> {
        val (start, end) = range
        return if (start == end) listOf(pending ?: rich.styleAt(start)) else rich.stylesIn(start, end)
    }

    fun selectedAlign(): TextAlign = rich.aligns.getOrElse(rich.paragraphAt(range.first)) { TextAlign.Left }

    /** Changes the selected characters, or what is typed next when nothing is selected. */
    fun style(change: (CharStyle) -> CharStyle) {
        val (start, end) = range
        when {
            start != end -> rich = rich.restyle(start, end, change)
            rich.length == 0 -> {
                rich = rich.withBase(change(rich.base))
                pending = null
                applyBase()
            }
            else -> {
                pending = change(pending ?: rich.styleAt(start))
                caretAfterEdit = start
            }
        }
        restyle()
        onChange()
    }

    fun align(align: TextAlign) {
        val (start, end) = range
        rich = rich.align(start, end, align)
        restyle()
        onChange()
    }

    /** Takes the keyboard again (after a menu took it). */
    fun showKeyboard() {
        post {
            requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, 0)
        }
    }

    /** Puts the cursor where [offset] is in the text. */
    fun placeCursor(offset: Int) = setSelection(offset.coerceIn(0, length()))
}
