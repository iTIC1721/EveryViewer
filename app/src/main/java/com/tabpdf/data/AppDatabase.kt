package com.tabpdf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GroupEntity::class, TabEntity::class, AppStateEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tabpdf.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}

/**
 * v1 → v2: from "every tab is in a group, one group shown at a time" to Chrome-style tabs and
 * groups (기획서 3장).
 * - Tabs of the auto-created default group ("기본") become ungrouped tabs, at that group's place
 *   in the order; every other group stays a group.
 * - Groups drop activeTabId; app state remembers the current tab instead of the active group.
 * - Groups and ungrouped tabs now share one position space. Positions are spread out here
 *   (group position × 1,000,000 + tab position) to keep the old order; the app rewrites them as
 *   0, 1, 2… on its next save.
 * Tables are rebuilt through constraint-free copies, so no foreign-key cascade can fire on the way.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE `_m_groups` AS SELECT id, name, color, position FROM tab_groups")
        db.execSQL(
            "CREATE TABLE `_m_default` AS SELECT id, position FROM tab_groups " +
                "WHERE name = '기본' ORDER BY position LIMIT 1"
        )
        db.execSQL("CREATE TABLE `_m_tabs` AS SELECT * FROM tabs")
        db.execSQL(
            "CREATE TABLE `_m_state` AS SELECT g.activeTabId AS currentTabId FROM app_state a " +
                "LEFT JOIN tab_groups g ON g.id = a.activeGroupId WHERE a.id = 0"
        )
        db.execSQL("DROP TABLE tabs")
        db.execSQL("DROP TABLE tab_groups")
        db.execSQL("DROP TABLE app_state")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tab_groups` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`color` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tabs` (`id` TEXT NOT NULL, `groupId` TEXT, `position` INTEGER NOT NULL, " +
                "`uri` TEXT NOT NULL, `isInternalCopy` INTEGER NOT NULL, `displayName` TEXT NOT NULL, " +
                "`page` INTEGER NOT NULL, `pageOffset` REAL NOT NULL, `zoom` REAL NOT NULL, `panX` REAL NOT NULL, " +
                "`lastOpenedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`groupId`) " +
                "REFERENCES `tab_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_tabs_groupId` ON `tabs` (`groupId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_state` (`id` INTEGER NOT NULL, `currentTabId` TEXT, PRIMARY KEY(`id`))"
        )

        db.execSQL(
            "INSERT INTO tab_groups (id, name, color, position) " +
                "SELECT id, name, color, position * 1000000 FROM _m_groups " +
                "WHERE id NOT IN (SELECT id FROM _m_default)"
        )
        db.execSQL(
            "INSERT INTO tabs (id, groupId, position, uri, isInternalCopy, displayName, page, pageOffset, zoom, panX, lastOpenedAt) " +
                "SELECT id, " +
                "CASE WHEN groupId IN (SELECT id FROM _m_default) THEN NULL ELSE groupId END, " +
                "CASE WHEN groupId IN (SELECT id FROM _m_default) " +
                "THEN (SELECT position FROM _m_default) * 1000000 + position ELSE position END, " +
                "uri, isInternalCopy, displayName, page, pageOffset, zoom, panX, lastOpenedAt FROM _m_tabs"
        )
        db.execSQL("INSERT INTO app_state (id, currentTabId) SELECT 0, currentTabId FROM _m_state")

        db.execSQL("DROP TABLE _m_groups")
        db.execSQL("DROP TABLE _m_default")
        db.execSQL("DROP TABLE _m_tabs")
        db.execSQL("DROP TABLE _m_state")
    }
}
