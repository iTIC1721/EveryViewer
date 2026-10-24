package com.tabpdf

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.tabpdf.tabs.FileAccess
import com.tabpdf.tabs.OpenedFile
import com.tabpdf.tabs.TabsViewModel
import com.tabpdf.ui.TabsScreen
import kotlinx.coroutines.launch

private const val PDF_MIME = "application/pdf"

class MainActivity : ComponentActivity() {
    private val tabsViewModel: TabsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openDebugTestFiles()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val openFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris -> prepare(uris) { tabsViewModel.open(it) } }

                // Tab waiting for a replacement file from "다시 찾기"; saved across the picker round trip.
                var relocatingTabId by rememberSaveable { mutableStateOf<String?>(null) }
                val relocate = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val tabId = relocatingTabId
                    relocatingTabId = null
                    if (uri != null && tabId != null) {
                        prepare(listOf(uri)) { files -> files.firstOrNull()?.let { tabsViewModel.replaceFile(tabId, it) } }
                    }
                }

                TabsScreen(
                    viewModel = tabsViewModel,
                    onOpenFiles = { openFiles.launch(arrayOf(PDF_MIME)) },
                    onRelocate = { tab ->
                        relocatingTabId = tab.id
                        relocate.launch(arrayOf(PDF_MIME))
                    },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Last chance to save reading positions before the process may be killed (기획서 5.3).
        tabsViewModel.flush()
    }

    /** Makes picked files readable after a restart (may copy them), then hands them to [onReady]. */
    private fun prepare(uris: List<Uri>, onReady: (List<OpenedFile>) -> Unit) {
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val files = uris.mapNotNull { uri ->
                try {
                    FileAccess.prepare(applicationContext, uri)
                } catch (e: Exception) {
                    android.util.Log.w("TabPDF", "Could not open $uri", e)
                    null
                }
            }
            onReady(files)
        }
    }

    /**
     * Debug builds only: `adb shell am start -n com.tabpdf/.MainActivity --ez debug_open_test_files true`
     * opens every PDF in the app's internal files dir (copy them in with `adb shell run-as com.tabpdf`).
     * Lets gestures be tested on an emulator without driving the system file picker.
     */
    private fun openDebugTestFiles() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || !intent.getBooleanExtra("debug_open_test_files", false)) return
        val files = filesDir.listFiles { f -> f.extension.equals("pdf", true) }.orEmpty()
        tabsViewModel.open(files.sortedBy { it.name }.map { OpenedFile(Uri.fromFile(it), it.name, isInternalCopy = false) })
    }
}
