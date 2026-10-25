package com.tabpdf.ink

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.FingerDrawing
import com.tabpdf.MainActivity
import com.tabpdf.tabs.TabsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The handwriting input rules (기획서 5.8.3, 5.8.4) with real pen and finger events injected into
 * the app: a pen draws, the held side button erases, a finger scrolls once a pen has been used,
 * and draws when set to. Starts from an empty app: tabs, ink, files and settings are wiped.
 */
@RunWith(AndroidJUnit4::class)
class InkInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as com.tabpdf.TabPdfApp
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var vm: TabsViewModel
    private var width = 0
    private var height = 0

    @Before
    fun setUp() {
        // The app's database stays open across tests in this process, so it is emptied, not deleted.
        kotlinx.coroutines.runBlocking {
            Thread.sleep(300) // let the previous test's queued writes land first
            app.repository.saveStructure(com.tabpdf.tabs.SessionState.Empty, emptyMap())
            app.inkDao.deleteOrphans()
        }
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        // Settings outlive the activity too (loaded once per process): set what these tests rely on.
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        instrumentation.runOnMainSync {
            app.settings.setFingerDrawing(FingerDrawing.Auto)
            app.settings.setInkTool(InkTool.Pen)
            app.settings.setSideButtonErases(true)
        }
        File(context.filesDir, "ink.txt").writeText((1..400).joinToString("\n") { "line $it of a text to write on" })

        scenario = ActivityScenario.launch(
            Intent(context, MainActivity::class.java).putExtra("debug_open_test_files", true)
        )
        scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TabsViewModel::class.java]
            width = activity.window.decorView.width
            height = activity.window.decorView.height
        }
        waitUntil("the text file is open") { onMain { vm.state.value?.currentTab?.let { vm.pageCount(it.id) } != null } }
        onMain { vm.setInkMode(true) }
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

    /** Main thread only. */
    private val tabId get() = vm.state.value!!.currentTabId!!
    private fun strokeCount() = onMain { vm.ink.strokes(tabId).value.size }

    /** Lets the viewer pick up a changed setting (it reaches the input handler on the next frame). */
    private fun settle() {
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        instrumentation.waitForIdleSync()
    }

    /** The tab has [expected] strokes, given a moment: a busy device may take longer than [drag] waits. */
    private fun assertStrokes(message: String, expected: Int) {
        val end = SystemClock.uptimeMillis() + 3_000
        while (strokeCount() != expected && SystemClock.uptimeMillis() < end) Thread.sleep(50)
        assertEquals(message, expected, strokeCount())
    }

    /** Drags one pointer from ([x0], [y0]) to ([x1], [y1]) (fractions of the window). */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, toolType: Int, buttons: Int = 0) {
        val source = if (toolType == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN
        else InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_TOUCHSCREEN
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, f: Float) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; this.toolType = toolType }
            val coords = MotionEvent.PointerCoords().apply {
                x = (x0 + (x1 - x0) * f) * width
                y = (y0 + (y1 - y0) * f) * height
                pressure = 0.6f
                size = 0.05f
            }
            val event = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, 1, arrayOf(properties), arrayOf(coords),
                0, buttons, 1f, 1f, 0, 0, source, 0,
            )
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        for (i in 1..20) {
            Thread.sleep(12)
            send(MotionEvent.ACTION_MOVE, i / 20f)
        }
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
        Thread.sleep(300) // finished strokes reach the page a frame or two later
        instrumentation.waitForIdleSync()
    }

    @Test
    fun penDrawsSideButtonErasesFingerScrolls() {
        drag(0.2f, 0.5f, 0.8f, 0.5f, MotionEvent.TOOL_TYPE_STYLUS)
        assertStrokes("a pen stroke is kept", 1)
        assertTrue("the pen was noticed", onMain { vm.settings.stylusSeen.value })

        drag(0.5f, 0.45f, 0.5f, 0.55f, MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertStrokes("the side button erased it", 0)
        onMain { vm.undoInk() }
        assertStrokes("undo brings it back", 1)

        val before = onMain { vm.viewerState(tabId) }
        drag(0.5f, 0.75f, 0.5f, 0.35f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("a finger doesn't draw once a pen is used", 1)
        assertNotEquals("the finger scrolled instead", before, onMain { vm.viewerState(tabId) })
    }

    /** One finger moves, then a second lands and both move up together. */
    private fun twoFingerScroll() {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, pointers: Int, f: Float) {
            val properties = Array(pointers) { i ->
                MotionEvent.PointerProperties().apply { id = i; toolType = MotionEvent.TOOL_TYPE_FINGER }
            }
            val coords = Array(pointers) { i ->
                MotionEvent.PointerCoords().apply {
                    x = (0.35f + 0.3f * i) * width
                    y = (0.7f - 0.3f * f) * height
                    pressure = 0.6f
                    size = 0.05f
                }
            }
            val event = MotionEvent.obtain(
                down, SystemClock.uptimeMillis(), action, pointers, properties, coords,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 1, 0f)
        for (i in 1..3) { Thread.sleep(12); send(MotionEvent.ACTION_MOVE, 1, i / 40f) }
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 3 / 40f)
        for (i in 4..20) { Thread.sleep(12); send(MotionEvent.ACTION_MOVE, 2, i / 20f) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 1f)
        send(MotionEvent.ACTION_UP, 1, 1f)
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
    }

    @Test
    fun secondFingerTurnsAStrokeIntoScrolling() {
        val before = onMain { vm.viewerState(tabId) }
        twoFingerScroll()
        assertStrokes("the started finger stroke was dropped", 0)
        assertNotEquals("two fingers scrolled", before, onMain { vm.viewerState(tabId) })
    }

    @Test
    fun closingATabKeepsItsInkUntilUndoIsGone() {
        drag(0.2f, 0.5f, 0.8f, 0.5f, MotionEvent.TOOL_TYPE_STYLUS)
        val id = onMain { tabId }
        val dao = app.inkDao
        fun saved() = kotlinx.coroutines.runBlocking { dao.strokesOf(id).size }
        waitUntil("the stroke is saved") { saved() == 1 }

        onMain { vm.close(id) }
        onMain { vm.undo() }
        assertEquals("undo brings the tab back with its ink", 1, onMain { vm.ink.strokes(id).value.size })

        onMain { vm.close(id) }
        onMain { vm.commitUndo() }
        waitUntil("the closed tab's ink is deleted") { saved() == 0 }
    }

    @Test
    fun fingerDrawsOnlyWhenItShould() {
        onMain { vm.settings.setFingerDrawing(FingerDrawing.Always) }
        settle()
        drag(0.2f, 0.5f, 0.8f, 0.5f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("set to always: the finger draws", 1)

        onMain {
            vm.settings.setFingerDrawing(FingerDrawing.Auto)
            vm.settings.markStylusSeen()
        }
        settle()
        drag(0.2f, 0.6f, 0.8f, 0.6f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("automatic, pen in use: the finger swipe draws nothing", 1)

        onMain { vm.settings.setFingerDrawing(FingerDrawing.Never) }
        settle()
        drag(0.2f, 0.4f, 0.8f, 0.4f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("set to never: the finger draws nothing", 1)
        drag(0.2f, 0.4f, 0.8f, 0.4f, MotionEvent.TOOL_TYPE_STYLUS)
        assertStrokes("…but the pen still does", 2)

        onMain { vm.setInkMode(false) }
        settle()
        drag(0.2f, 0.3f, 0.8f, 0.3f, MotionEvent.TOOL_TYPE_STYLUS)
        assertStrokes("handwriting off: even a pen doesn't draw", 2)
    }

    @Test
    fun fingerButtonLetsFingersDrawUntilThePenIsBack() {
        drag(0.2f, 0.5f, 0.8f, 0.5f, MotionEvent.TOOL_TYPE_STYLUS)
        drag(0.2f, 0.6f, 0.8f, 0.6f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("pen in use: the finger doesn't draw", 1)

        // The pen put away: the toolbar's finger button.
        pressFingerButton()
        drag(0.2f, 0.6f, 0.8f, 0.6f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("the finger draws again", 2)

        drag(0.2f, 0.4f, 0.8f, 0.4f, MotionEvent.TOOL_TYPE_STYLUS)
        assertTrue("the pen back ends it", !onMain { vm.settings.fingersToo.value })
        drag(0.2f, 0.3f, 0.8f, 0.3f, MotionEvent.TOOL_TYPE_FINGER)
        assertStrokes("…and the finger stops drawing", 3)
    }

    /** Finds the button as a screen reader would, by its description, and presses it. */
    private fun pressFingerButton() {
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.contentDescription == "손가락으로 그리기") return n
            return (0 until n.childCount).firstNotNullOfOrNull { find(n.getChild(it)) }
        }
        var button: AccessibilityNodeInfo? = null
        waitUntil("the finger button shows once a pen is used") {
            button = find(instrumentation.uiAutomation.rootInActiveWindow)
            button != null
        }
        var clickable = button
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        assertTrue(clickable!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil("fingers draw") { onMain { vm.settings.fingersToo.value } }
        settle()
    }
}
