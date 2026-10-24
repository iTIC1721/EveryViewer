package com.tabpdf.tabs

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Rules of the Chrome-style tab structure (기획서 3장, 5.4). */
@RunWith(AndroidJUnit4::class)
class SessionEditsTest {
    private fun tab(id: String, groupId: String? = null) =
        DocTab(id, groupId, Uri.parse("content://test/$id"), "$id.pdf", isInternalCopy = false)

    private fun group(id: String, color: Int = 0) = TabGroup(id, "", color)

    /** Shorthand for asserting the top level: tab ids, and groups as "G:id[tab,tab]". */
    private fun SessionState.shape(): List<String> = top.map { item ->
        when (item) {
            is DocTab -> item.id
            is TabGroup -> "G:${item.id}${tabsOf(item.id).map { it.id }}"
        }
    }

    private val abc = SessionState(listOf(tab("a"), tab("b"), tab("c")), emptyMap(), "a")

    @Test
    fun droppingATabOnAnotherMakesAGroupWhereTheTargetWas() {
        val s = abc.groupTogether(draggedId = "c", targetId = "a", group("g"))!!
        assertEquals(listOf("G:g[a, c]", "b"), s.shape())
        assertEquals(listOf("g", "g"), s.tabsOf("g").map { it.groupId })
    }

    @Test
    fun groupingNeedsTwoDifferentUngroupedTabs() {
        assertNull(abc.groupTogether("a", "a", group("g")))
        val grouped = abc.groupTogether("b", "a", group("g"))!!
        assertNull(grouped.groupTogether("c", "a", group("h"))) // a is in a group now
    }

    @Test
    fun closingTheLastTabOfAGroupRemovesTheGroup() {
        val s = abc.groupTogether("b", "a", group("g"))!!
        val after = s.removeTab("a").removeTab("b")
        assertEquals(listOf("c"), after.shape())
        assertEquals(emptyMap<String, List<DocTab>>(), after.groupTabs)
    }

    @Test
    fun aTabTakenOutOfAGroupLandsRightAfterIt() {
        val s = SessionState(listOf(tab("x")), emptyMap(), null)
            .appendTab(tab("y"))
            .let { it.groupTogether("y", "x", group("g"))!! }
            .appendTab(tab("z"))
        assertEquals(listOf("G:g[x, y]", "z"), s.shape())
        val out = s.removeFromGroup("x")!!
        assertEquals(listOf("G:g[y]", "x", "z"), out.shape())
        assertNull(out.tab("x")!!.groupId)
        // The last tab out takes the group's place.
        assertEquals(listOf("y", "x", "z"), out.removeFromGroup("y")!!.shape())
    }

    @Test
    fun addingToAGroupMovesTheTabAndDropsAnEmptiedGroup() {
        val s = abc.groupTogether("b", "a", group("g"))!!
            .addToNewGroup("c", group("h"))!!
        assertEquals(listOf("G:g[a, b]", "G:h[c]"), s.shape())
        val moved = s.addToGroup("c", "g")!!
        assertEquals(listOf("G:g[a, b, c]"), moved.shape())
        assertNull(moved.addToGroup("c", "g")) // already there
        assertNull(moved.addToGroup("c", "nope"))
    }

    @Test
    fun aNewGroupFromAnUngroupedTabTakesItsPlace() {
        val s = abc.addToNewGroup("b", group("g"))!!
        assertEquals(listOf("a", "G:g[b]", "c"), s.shape())
    }

    @Test
    fun aNewGroupFromAGroupedTabGoesAfterTheOldGroup() {
        val s = abc.groupTogether("b", "a", group("g"))!!.addToNewGroup("a", group("h"))!!
        assertEquals(listOf("G:g[b]", "G:h[a]", "c"), s.shape())
    }

    @Test
    fun ungroupingPutsTheTabsInTheGroupsPlaceInOrder() {
        val s = abc.groupTogether("c", "b", group("g"))!!
        assertEquals(listOf("a", "G:g[b, c]"), s.shape())
        assertEquals(listOf("a", "b", "c"), s.ungroup("g")!!.shape())
    }

    @Test
    fun reorderingNeverLosesItemsMissingFromTheGivenOrder() {
        assertEquals(listOf("c", "a", "b"), abc.reorderTop(listOf("c", "a")).shape())
        assertEquals(listOf("c", "a", "b"), abc.reorderTop(listOf("c", "unknown", "a")).shape())
    }

    @Test
    fun swipingStaysInsideTheCurrentTabsPlace() {
        val s = abc.groupTogether("c", "b", group("g"))!!
        assertEquals(listOf("a"), s.context(null).map { it.id })
        assertEquals(listOf("b", "c"), s.context("g").map { it.id })
    }

    @Test
    fun aClosedCurrentTabIsReplacedByItsNeighbour() {
        val closedB = abc.copy(currentTabId = "b").removeTab("b")
        assertEquals("c", closedB.withValidCurrent(null, 1).currentTabId) // the one now in its slot
        val closedC = abc.copy(currentTabId = "c").removeTab("c")
        assertEquals("b", closedC.withValidCurrent(null, 2).currentTabId) // last one: previous neighbour
        val closedAll = SessionState(listOf(tab("only")), emptyMap(), "only").removeTab("only")
        assertNull(closedAll.withValidCurrent(null, 0).currentTabId)
    }

    @Test
    fun newGroupsGetTheFirstUnusedColour() {
        val s = abc.groupTogether("b", "a", group("g", color = 0))!!
        assertEquals(1, s.nextGroupColor(8))
        val full = SessionState((0 until 8).map { group("g$it", color = it) }, emptyMap(), null)
        assertEquals(0, full.nextGroupColor(8))
    }
}
