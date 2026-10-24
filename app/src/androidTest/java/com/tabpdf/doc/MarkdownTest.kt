package com.tabpdf.doc

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rendered markdown (기획서 5.7): laid out, paged, and links found where they are drawn. */
@RunWith(AndroidJUnit4::class)
class MarkdownTest {
    /** Every link found on page [page], probing a grid of points. */
    private fun linksOn(doc: Document, page: Int): Set<String> =
        (0..200).flatMap { i -> (0..100).mapNotNull { j -> doc.linkAt(page, j / 100f, i / 200f) } }.toSet()

    @Test
    fun webLinksAreFoundOtherLinksAreNot() = runBlocking {
        val doc = MarkdownDocument.render(
            "# Title\n\nSee [the site](https://example.com), [a file](other.md) and https://commonmark.org.\n"
        )
        assertEquals(setOf("https://example.com", "https://commonmark.org"), linksOn(doc, 0))
        doc.close()
    }

    @Test
    fun linksInTablesAndQuotesAreFound() = runBlocking {
        val doc = MarkdownDocument.render(
            "| a | b |\n|---|---|\n| [x](https://a.example) | y |\n\n> quote with [link](https://b.example)\n"
        )
        assertEquals(setOf("https://a.example", "https://b.example"), linksOn(doc, 0))
        doc.close()
    }

    @Test
    fun frontMatterIsNotShownAndEverythingRenders() = runBlocking {
        val long = (1..300).joinToString("\n\n") { "- item $it\n\n```\ncode $it\n```" }
        val doc = MarkdownDocument.render("---\ntitle: x\n---\n# Doc\n\n- [ ] task\n- [x] done\n\n$long")
        assertTrue(doc.pageCount > 5)
        assertTrue(!doc.pageGaps)
        for (page in listOf(0, doc.pageCount / 2, doc.pageCount - 1)) assertNotNull(doc.renderPage(page, 300))
        doc.close()
    }
}
