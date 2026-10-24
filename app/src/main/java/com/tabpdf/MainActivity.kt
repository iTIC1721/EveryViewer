package com.tabpdf

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.tabpdf.tabs.TabsViewModel
import com.tabpdf.ui.TabsScreen

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
                ) { uris ->
                    uris.forEach(::takeReadPermission)
                    tabsViewModel.open(uris.map { it to displayName(it) })
                }
                TabsScreen(tabsViewModel, onOpenFiles = { openFiles.launch(arrayOf("application/pdf")) })
            }
        }
    }

    /** Persistable read access, so tabs can reopen the file after a restart (기획서 5.3). */
    private fun takeReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't offer persistable grants; the tab still works this session.
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
        tabsViewModel.open(files.sortedBy { it.name }.map { Uri.fromFile(it) to it.name })
    }

    private fun displayName(uri: Uri): String =
        queryDisplayName(this, uri) ?: uri.lastPathSegment ?: "PDF"
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
