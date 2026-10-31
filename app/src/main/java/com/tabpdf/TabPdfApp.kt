package com.tabpdf

import android.app.Application
import com.tabpdf.data.AppDatabase
import com.tabpdf.data.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

class TabPdfApp : Application() {
    private val database by lazy { AppDatabase.create(this) }
    val repository by lazy { SessionRepository(database.sessionDao()) }
    val inkDao by lazy { database.inkDao() }
    val settings by lazy { AppSettings(this) }

    /** When this process started. Files and grants from before it belong to earlier sessions. */
    val startedAt: Long = System.currentTimeMillis()

    /** Outlives screens, so saves started as the app goes to the background still finish. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        com.tabpdf.pdf.RenderProcess.app = this
        appScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: Exception) {
                    android.util.Log.e("EveryViewer", "Session write failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Runs [write] after every previously enqueued write, so a newer reading position can never be
     * overwritten by an older one and a tab's row always exists before it is updated.
     */
    fun enqueueWrite(write: suspend () -> Unit) {
        writes.trySend(write)
    }
}
