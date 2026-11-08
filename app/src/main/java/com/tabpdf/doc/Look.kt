package com.tabpdf.doc

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance

/*
 * A document's dark look (기획서 5.10). A file that defines one (HTML and e-books with dark-mode CSS)
 * or text whose colours the viewer picks (txt, md) is laid out once; every colour in the layout
 * carries its dark counterpart, and which one is used is decided while a page is drawn. So switching
 * looks is only drawing again: pages, lines, handwriting and reading position stay where they are.
 */
object DocLook {
    private val dark = ThreadLocal<Boolean>()

    /** Whether the page being drawn on this thread is drawn dark. */
    val isDark: Boolean get() = dark.get() == true

    /** Runs [block] (drawing a page) in the dark look or the usual one. */
    inline fun <T> drawing(dark: Boolean, block: () -> T): T {
        val before = isDark
        set(dark)
        try {
            return block()
        } finally {
            set(before)
        }
    }

    fun set(value: Boolean) = dark.set(value)

    /** [light], or [dark] when drawing dark and there is one. */
    fun pick(light: Int, dark: Int?): Int = if (dark != null && isDark) dark else light

    /** Colours of text documents and Markdown in the dark look: the app's dark greys, text at 4.5:1 or more. */
    const val PAPER = 0xFF1E1E21.toInt()
    const val TEXT = 0xFFE3E3E6.toInt()
    const val MUTED = 0xFFA8A8AE.toInt()
    const val LINK = 0xFF8AB4F8.toInt()

    /** A page with no colour of its own in a browser's dark look (CSS `Canvas`, `CanvasText`, `LinkText`). */
    const val CANVAS = 0xFF121212.toInt()
    const val CANVAS_TEXT = 0xFFE8E8E8.toInt()
    const val LINK_TEXT = 0xFF9E9EFF.toInt()
}

/** Two of something, one for each look; [now] is the one for the page being drawn. */
class Looked<T>(val light: T, val dark: T) {
    val now: T get() = if (DocLook.isDark) dark else light

    companion object {
        /** Paints of [light] and [dark] colour made by [make]. */
        fun <P : Paint> paints(light: Int, dark: Int, make: () -> P): Looked<P> =
            Looked(make().apply { color = light }, make().apply { color = dark })
    }
}

/** Text colour with a dark counterpart. */
class LookColorSpan(val light: Int, val dark: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.color = DocLook.pick(light, dark)
    }
}

/** Background behind text with a dark counterpart (0: none). */
class LookBackgroundSpan(val light: Int, val dark: Int) : CharacterStyle(), UpdateAppearance {
    override fun updateDrawState(tp: TextPaint) {
        tp.bgColor = DocLook.pick(light, dark)
    }
}

/**
 * The paint a layout's text without a colour span is drawn with, in each look. Its colour is set
 * just before drawing ([drawLooked]); a document draws one page at a time, so that is safe.
 */
class LookTextPaint(flags: Int, val light: Int, val dark: Int) : TextPaint(flags) {
    init {
        color = light
    }

    fun apply() {
        color = DocLook.pick(light, dark)
    }
}

/** Draws the layout, its text in the colour of the look being drawn. */
fun Layout.drawLooked(canvas: Canvas) {
    (paint as? LookTextPaint)?.apply()
    draw(canvas)
}
