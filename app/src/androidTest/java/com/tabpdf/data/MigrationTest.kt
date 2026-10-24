package com.tabpdf.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabpdf.tabs.DocTab
import com.tabpdf.tabs.TabGroup
import com.tabpdf.tabs.ViewerState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1 → v2 (MIGRATION_1_2) on a database written with the v1 schema exactly as Room exported it
 * (app/schemas/.../1.json).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    private fun writeV1(fill: SQLiteDatabase.() -> Unit) {
        context.deleteDatabase(name)
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tab_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`color` INTEGER NOT NULL, `position` INTEGER NOT NULL, `activeTabId` TEXT, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tabs` (`id` TEXT NOT NULL, `groupId` TEXT NOT NULL, `position` INTEGER NOT NULL, " +
                "`uri` TEXT NOT NULL, `isInternalCopy` INTEGER NOT NULL, `displayName` TEXT NOT NULL, `page` INTEGER NOT NULL, " +
                "`pageOffset` REAL NOT NULL, `zoom` REAL NOT NULL, `panX` REAL NOT NULL, `lastOpenedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`groupId`) REFERENCES `tab_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tabs_groupId` ON `tabs` (`groupId`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `app_state` (`id` INTEGER NOT NULL, `activeGroupId` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.fill()
        db.version = 1
        db.close()
    }

    private fun SQLiteDatabase.group(id: String, name: String, color: Int, position: Int, activeTabId: String?) =
        execSQL("INSERT INTO tab_groups VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(id, name, color, position, activeTabId))

    private fun SQLiteDatabase.tab(id: String, groupId: String, position: Int, page: Int = 0) =
        execSQL(
            "INSERT INTO tabs VALUES (?, ?, ?, ?, 0, ?, ?, 0.25, 1.5, 0.1, 0)",
            arrayOf<Any>(id, groupId, position, "content://test/$id", "$id.pdf", page),
        )

    private fun loadV2() = runBlocking {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            SessionRepository(db.sessionDao()).load()
        } finally {
            db.close()
        }
    }

    private fun tab(id: String, groupId: String? = null) =
        DocTab(id, groupId, Uri.parse("content://test/$id"), "$id.pdf", isInternalCopy = false)

    @Test
    fun defaultGroupBecomesUngroupedTabsAndOtherGroupsStay() {
        writeV1 {
            group("d", "기본", color = 0, position = 0, activeTabId = "t2")
            group("s", "Study", color = 2, position = 1, activeTabId = "t3")
            tab("t2", "d", position = 1, page = 9)
            tab("t1", "d", position = 0)
            tab("t3", "s", position = 0)
            execSQL("INSERT INTO app_state VALUES (0, 'd')")
        }
        val (state, states) = loadV2()
        assertEquals(listOf(tab("t1"), tab("t2"), TabGroup("s", "Study", 2)), state.top)
        assertEquals(mapOf("s" to listOf(tab("t3", "s"))), state.groupTabs)
        // The active group's last tab becomes the current tab.
        assertEquals("t2", state.currentTabId)
        // Reading positions come through untouched.
        assertEquals(ViewerState(page = 9, pageOffset = 0.25f, zoom = 1.5f, panX = 0.1f), states["t2"])
    }

    @Test
    fun defaultGroupTabsKeepThatGroupsPlaceInTheOrder() {
        writeV1 {
            group("s", "Study", color = 2, position = 0, activeTabId = null)
            group("d", "기본", color = 0, position = 1, activeTabId = null)
            group("e", "Exam", color = 4, position = 2, activeTabId = null)
            tab("t3", "s", position = 0)
            tab("t1", "d", position = 0)
            tab("t4", "e", position = 0)
            execSQL("INSERT INTO app_state VALUES (0, 's')")
        }
        val (state, _) = loadV2()
        assertEquals(listOf(TabGroup("s", "Study", 2), tab("t1"), TabGroup("e", "Exam", 4)), state.top)
        assertEquals(null, state.currentTabId) // Study had no remembered tab
    }
}
