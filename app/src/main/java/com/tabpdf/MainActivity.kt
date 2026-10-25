package com.tabpdf

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import com.tabpdf.ink.InkSurface
import com.tabpdf.ink.LocalInkSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.tabpdf.tabs.FileAccess
import com.tabpdf.tabs.OpenedFile
import com.tabpdf.tabs.TabsViewModel
import com.tabpdf.ui.TabsScreen
import kotlinx.coroutines.launch

/** Any file: what can be shown is decided after it is picked (기획서 5.1, 5.7). */
private const val ANY_MIME = "*/*"

class MainActivity : ComponentActivity() {
    private val tabsViewModel: TabsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After a recreation (rotation, process restore) the intent was already handled.
        if (savedInstanceState == null) {
            openIncomingFiles(intent)
            openDebugTestFiles()
        }
        // Strokes being drawn are shown by Ink's low-latency view over the whole window (기획서 5.8.5).
        val inkSurface = InkSurface(this)
        setContent {
            CompositionLocalProvider(LocalInkSurface provides inkSurface) {
            val theme by tabsViewModel.settings.theme.collectAsState()
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Status and navigation bar icons follow the app's colours, not only the system's.
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            // Gives text and icons outside any other Surface the theme's colour instead of black.
            Surface(color = MaterialTheme.colorScheme.background) {
                // Group the picked files go into (null: ungrouped); saved across the picker round trip.
                var openingIntoGroup by rememberSaveable { mutableStateOf<String?>(null) }
                val openFiles = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments()
                ) { uris ->
                    val groupId = openingIntoGroup
                    // Several at once: a tab each, or one bundle (기획서 5.1).
                    prepare(uris) { tabsViewModel.open(it, groupId, askToBundle = true) }
                }

                // Files added to a bundle from its editor.
                val addToBundle = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    prepare(uris) { tabsViewModel.addBundleItems(it) }
                }

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
                    onOpenFiles = { groupId ->
                        openingIntoGroup = groupId
                        openFiles.launch(arrayOf(ANY_MIME))
                    },
                    onRelocate = { tab ->
                        relocatingTabId = tab.id
                        relocate.launch(arrayOf(ANY_MIME))
                    },
                    onAddToBundle = { addToBundle.launch(arrayOf(ANY_MIME)) },
                )
            }
            }
            }
        }
        // Above the Compose content; it takes no touches, so they all reach the viewer.
        addContentView(inkSurface.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openIncomingFiles(intent)
    }

    /**
     * Files handed over by another app ("열기"/"공유") open as ungrouped tabs (기획서 5.1). Their
     * read grants are temporary, so FileAccess usually copies them into app storage. Only content
     * URIs are taken: a file: URI from another app could point into this app's own private files.
     */
    private fun openIncomingFiles(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }.filter { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uris.isEmpty() && intent.action == Intent.ACTION_SEND) {
            // Shared text with no file behind it (a link, a selection): kept as a text file of its own.
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            lifecycleScope.launch {
                val file = FileAccess.saveSharedText(applicationContext, text, subject)
                tabsViewModel.open(listOf(file), groupId = null)
            }
            return
        }
        prepare(uris) { tabsViewModel.open(it, groupId = null, askToBundle = true) }
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
                    // Not the URI: file names in the log would tell what the user reads.
                    android.util.Log.w("EveryViewer", "Could not open a file", e)
                    null
                }
            }
            if (files.size < uris.size) {
                val failed = uris.size - files.size
                Toast.makeText(
                    this@MainActivity,
                    if (uris.size == 1) "파일을 열 수 없습니다" else "파일 ${failed}개를 열 수 없습니다",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            onReady(files)
        }
    }

    /**
     * Debug builds only: `adb shell am start -n com.tabpdf/.MainActivity --ez debug_open_test_files true`
     * opens every file in the app's internal files dir (copy them in with `adb shell run-as com.tabpdf`).
     * Lets gestures be tested on an emulator without driving the system file picker.
     */
    private fun openDebugTestFiles() {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable || !intent.getBooleanExtra("debug_open_test_files", false)) return
        val files = filesDir.listFiles { f -> f.isFile }.orEmpty().sortedBy { it.name }
        // --ez debug_ask_to_bundle true: as if picked together, asking whether to bundle them.
        val ask = intent.getBooleanExtra("debug_ask_to_bundle", false)
        prepare(files.map { Uri.fromFile(it) }) { tabsViewModel.open(it, groupId = null, askToBundle = ask) }
    }
}
