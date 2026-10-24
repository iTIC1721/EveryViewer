package com.tabpdf.data

import com.tabpdf.doc.FileFormat
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.SessionState
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.ViewerState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Saving and loading the tab structure (기획서 5.3), on an in-memory database. */
@RunWith(AndroidJUnit4::class)
class SessionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = SessionRepository(db.sessionDao())
    }

    @After
    fun tearDown() = db.close()

    private fun tab(id: String, groupId: String? = null) =
        DocTab(id, groupId, Uri.parse("content://test/$id"), "$id.pdf", isInternalCopy = false, FileFormat.Pdf)

    /** a, [g: b, c], d — a group between two ungrouped tabs. */
    private val mixed = SessionState(
        top = listOf(tab("a"), TabGroup("g", "Study", 3), tab("d")),
        groupTabs = mapOf("g" to listOf(tab("b", "g"), tab("c", "g"))),
        currentTabId = "c",
    )

    @Test
    fun structureSurvivesASaveAndLoad() = runBlocking {
        repository.saveStructure(mixed, emptyMap())
        val (loaded, _) = repository.load()
        assertEquals(mixed, loaded)
    }

    @Test
    fun newTabsGetTheGivenReadingPositionAndExistingOnesKeepTheirs() = runBlocking {
        repository.saveStructure(mixed, mapOf("a" to ViewerState(page = 4, zoom = 2f)))
        repository.saveViewerStates(mapOf("b" to ViewerState(page = 7)))
        // Saving the structure again with other positions must not overwrite stored ones.
        repository.saveStructure(mixed, mapOf("a" to ViewerState(page = 99), "b" to ViewerState(page = 99)))
        val (_, states) = repository.load()
        assertEquals(ViewerState(page = 4, zoom = 2f), states["a"])
        assertEquals(ViewerState(page = 7), states["b"])
    }

    /** Regression: deleting a group cascades to its tabs, so they must be moved out first. */
    @Test
    fun ungroupedTabsSurviveTheirGroupBeingDeleted() = runBlocking {
        repository.saveStructure(mixed, emptyMap())
        val ungrouped = mixed.copy(
            top = listOf(tab("a"), tab("b"), tab("c"), tab("d")),
            groupTabs = emptyMap(),
        )
        repository.saveStructure(ungrouped, emptyMap())
        val (loaded, _) = repository.load()
        assertEquals(ungrouped, loaded)
    }

    @Test
    fun closedTabsAndGroupsAreDeleted() = runBlocking {
        repository.saveStructure(mixed, emptyMap())
        val closed = SessionState(listOf(tab("d")), emptyMap(), "d")
        repository.saveStructure(closed, emptyMap())
        val (loaded, states) = repository.load()
        assertEquals(closed, loaded)
        assertEquals(setOf("d"), states.keys)
        assertEquals(false, repository.isUriInUse(Uri.parse("content://test/b")))
    }
}
