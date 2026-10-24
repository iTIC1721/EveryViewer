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
    val repository by lazy { SessionRepository(AppDatabase.create(this).sessionDao()) }

    /** Outlives screens, so saves started as the app goes to the background still finish. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            for (write in writes) {
                try {
                    write()
                } catch (e: Exception) {
                    android.util.Log.e("TabPDF", "Session write failed", e)
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
