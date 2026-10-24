package com.tabpdf.doc

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a check: renders every page of the files in the app's files/render-in/ to
 * files/render-out/<name>-<page>.png, for comparing layouts with the original (기획서 5.7).
 * Skipped when there is nothing to render. Run on its own so the app (and its files) stay:
 *   adb shell am instrument -w -e class com.tabpdf.doc.RenderDump com.tabpdf.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class RenderDump {
    @Test
    fun renderAll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "render-in")
        val files = input.listFiles().orEmpty().filter { it.isFile }
        assumeTrue(files.isNotEmpty())
        val output = File(context.filesDir, "render-out").apply { deleteRecursively(); mkdirs() }
        for (file in files) {
            val uri = Uri.fromFile(file)
            val format = FileFormats.detect(context, uri, file.name)
            val doc = Documents.open(context, uri, file.name, format)
            for (page in 0 until doc.pageCount) {
                val bitmap = doc.renderPage(page, 1240) ?: continue
                File(output, "${file.nameWithoutExtension}-$page.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            doc.close()
        }
    }
}
