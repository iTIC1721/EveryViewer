package com.tabpdf.ui

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.MainActivity
import com.tabpdf.TabPdfApp
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Zoom (기획서 5.2) with real two-finger and tap events: pinching in shrinks the page down to half
 * the screen's width and no further; a double tap brings it back to the width of the screen.
 * Starts from an empty app (tabs and files are wiped).
 */
@RunWith(AndroidJUnit4::class)
class ZoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TabPdfApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: TabsViewModel
    private var width = 0
    private var height = 0

    @Before
    fun setUp() {
        kotlinx.coroutines.runBlocking {
            Thread.sleep(300) // let a previous test's queued writes land first
            app.repository.saveStructure(SessionState.Empty, emptyMap())
        }
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        File(context.filesDir, "zoom.txt").writeText((1..400).joinToString("\n") { "line $it of a text to zoom" })
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("debug_open_test_files", true))
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TabsViewModel::class.java]
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        waitUntil("the file is open") { onMain { vm.state.value?.currentTab?.let { vm.pageCount(it.id) } != null } }
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() = scenario.close()

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("Timed out waiting until $what")
            Thread.sleep(50)
        }
    }

    private fun zoom() = onMain { vm.viewerState(vm.state.value!!.currentTabId!!).zoom }

    private fun send(down: Long, action: Int, xs: FloatArray, y: Float) {
        val properties = Array(xs.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(xs.size) { i ->
            MotionEvent.PointerCoords().apply { x = xs[i] * width; this.y = y * height; pressure = 0.6f; size = 0.05f }
        }
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, xs.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /** Two fingers from [from] apart to [to] apart (fractions of the width), around the middle. */
    private fun pinch(from: Float, to: Float) {
        val down = SystemClock.uptimeMillis()
        fun xs(gap: Float) = floatArrayOf(0.5f - gap / 2, 0.5f + gap / 2)
        send(down, MotionEvent.ACTION_DOWN, floatArrayOf(xs(from)[0]), 0.5f)
        send(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs(from), 0.5f)
        for (i in 1..20) {
            Thread.sleep(12)
            send(down, MotionEvent.ACTION_MOVE, xs(from + (to - from) * i / 20f), 0.5f)
        }
        send(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), xs(to), 0.5f)
        send(down, MotionEvent.ACTION_UP, floatArrayOf(xs(to)[0]), 0.5f)
        instrumentation.waitForIdleSync()
        Thread.sleep(700) // the saved position follows a moment later
    }

    private fun doubleTap() {
        repeat(2) {
            val down = SystemClock.uptimeMillis()
            send(down, MotionEvent.ACTION_DOWN, floatArrayOf(0.5f), 0.6f)
            send(down, MotionEvent.ACTION_UP, floatArrayOf(0.5f), 0.6f)
            Thread.sleep(60)
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(900) // the zoom animates, then the position is saved
    }

    @Test
    fun pinchShrinksToHalfAndDoubleTapRestores() {
        assertEquals(1f, zoom(), 0.01f)
        // Pinched to a third: stops at half.
        pinch(0.6f, 0.2f)
        assertEquals(0.5f, zoom(), 0.01f)
        // Shrunk pages sit in the middle.
        val panX = onMain { vm.viewerState(vm.state.value!!.currentTabId!!).panX }
        assertEquals(-0.25f, panX, 0.01f)
        doubleTap()
        assertEquals("a double tap brings it back to the screen's width", 1f, zoom(), 0.01f)
        doubleTap()
        assertEquals("and from there zooms in", true, zoom() > 1.5f)
    }
}
