package com.tabpdf.tabs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A tab whose file was being opened when the app ended — a crash in the system's code, which
 * nothing in the app can catch — is not opened by itself at the next start (기획서 10장).
 */
@RunWith(AndroidJUnit4::class)
class OpenGuardTest {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "open-guard-test")

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun aTabBeingOpenedWhenTheAppEndedIsHeldBackOnce() {
        file.delete()
        val first = OpenMarks(file)
        assertTrue(first.previous.isEmpty())
        first.change("crashed", 1)
        first.change("opened", 1)
        first.change("opened", -1)
        // Opened twice at once (the viewer and a thumbnail): marked until both are done.
        first.change("both", 1)
        first.change("both", 1)
        first.change("both", -1)
        // The app ends here, with no chance to clean up: the next start finds what was under way.
        assertEquals(setOf("crashed", "both"), OpenMarks(file).previous)
        // Only once: a start after that holds nothing back.
        assertTrue(OpenMarks(file).previous.isEmpty())
    }

    @Test
    fun nothingIsLeftWhenEveryOpeningEnds() {
        file.delete()
        val run = OpenMarks(file)
        run.change("a", 1)
        run.change("a", -1)
        assertTrue(!file.exists())
        assertTrue(OpenMarks(file).previous.isEmpty())
    }
}
