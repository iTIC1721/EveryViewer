package com.tabpdf.doc

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * Word documents end their pages where Word ends them (기획서 5.7). The files in assets/word-fit/
 * were made with Word (tools/word-fit.ps1): tables that fill a page in Korean and Latin fonts,
 * rows of set heights, a heading row repeated on every page. Each row starts with a marker
 * (R001, R002, …); word.txt holds where Word's own PDF puts each one. Every marker must be on the
 * same page as in Word and, measured from the first, within 1.5 pt of Word's place.
 */
@RunWith(AndroidJUnit4::class)
class WordFitTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class Expected(val name: String, val pages: Int, val marks: Map<String, Pair<Int, Float>>)

    private val expected: List<Expected> = instrumentation.context.assets.open("word-fit/word.txt").bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split(' ')
            Expected(parts[0], parts[1].toInt(), parts.drop(2).associate { m ->
                val (mark, page, top) = m.split(':')
                mark to (page.toInt() to top.toFloat())
            })
        }

    @Test
    fun rowsLandWhereWordPutsThem() = runBlocking {
        assertTrue(expected.size >= 10)
        val failures = mutableListOf<String>()
        for (e in expected) {
            val file = File(instrumentation.targetContext.cacheDir, "${e.name}.docx")
            instrumentation.context.assets.open("word-fit/${e.name}.docx").use { input -> file.outputStream().use { input.copyTo(it) } }
            val doc = Documents.open(instrumentation.targetContext, Uri.fromFile(file), file.name, FileFormat.Docx)
            try {
                if (doc.pageCount != e.pages) failures += "${e.name}: ${doc.pageCount} pages, Word ${e.pages}"
                // The marker's box starts a little differently in the two fonts: compare with the first.
                var offset: Float? = null
                for ((mark, place) in e.marks.toSortedMap()) {
                    val found = doc.search(mark).firstOrNull()
                    if (found == null) {
                        failures += "${e.name} $mark: not found"
                        continue
                    }
                    if (found.page != place.first) {
                        failures += "${e.name} $mark: page ${found.page + 1}, Word ${place.first + 1}"
                        continue
                    }
                    val shift = found.rects.first().top - place.second
                    val first = offset ?: shift.also { offset = it }
                    val points = (shift - first) * A4_HEIGHT
                    if (abs(points) > 1.5f) failures += "${e.name} $mark: %.1f pt off".format(points)
                }
            } finally {
                doc.close()
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    private companion object {
        /** All the files are A4, in points. */
        const val A4_HEIGHT = 841.9f
    }
}
