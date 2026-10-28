package com.tabpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tabpdf.R

/** A library the app ships with, its license, and the license text (an asset, or a note). */
private class Library(val name: String, val license: String, val asset: String?, val note: String? = null)

private val LIBRARIES = listOf(
    Library("AndroidX (Jetpack Compose, Room, Ink 등)", "Apache License 2.0", "licenses/apache-2.0.txt"),
    Library("Kotlin, kotlinx.coroutines", "Apache License 2.0", "licenses/apache-2.0.txt"),
    Library("hwplib", "Apache License 2.0", "licenses/apache-2.0.txt"),
    Library("jsoup", "MIT License", "licenses/jsoup-mit.txt"),
    Library("commonmark-java", "BSD 2-Clause License", "licenses/commonmark-bsd-2.txt"),
    Library("Pretendard", "SIL Open Font License 1.1", "licenses/pretendard-ofl.txt"),
    Library(
        "juniversalchardet",
        "Mozilla Public License 1.1",
        null,
        note = "juniversalchardet은 Mozilla Public License 1.1에 따라 배포됩니다.\n\n" +
            "라이선스 원문: https://www.mozilla.org/MPL/1.1/\n" +
            "소스 코드: https://github.com/albfernandez/juniversalchardet",
    ),
)

/**
 * App information (기획서 5.6), laid out as apps commonly show it: the icon, name and version in the
 * middle, then rows for privacy and the open source licenses, each license opening its full text.
 */
@Composable
fun AboutScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var showLicenses by remember { mutableStateOf(false) }
    var showLicense by remember { mutableStateOf<Library?>(null) }
    BackHandler(onBack = onDismiss)

    Page("앱 정보", onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 28.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon()
            Spacer(Modifier.height(16.dp))
            Text("EveryViewer", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("버전 $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "어떤 파일이든 탭으로 열어 두고 넘겨 보는 문서 뷰어",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        HorizontalDivider()
        InfoRow("개인정보", "인터넷을 사용하지 않으며, 어떤 데이터도 외부로 보내지 않습니다.")
        InfoRow("오픈소스 라이선스", null, onClick = { showLicenses = true })
    }
    if (showLicenses) {
        BackHandler { showLicenses = false }
        Page("오픈소스 라이선스", onBack = { showLicenses = false }) {
            for (library in LIBRARIES) InfoRow(library.name, library.license, onClick = { showLicense = library })
        }
    }
    showLicense?.let { library ->
        BackHandler { showLicense = null }
        val text = remember(library) {
            library.note ?: runCatching { context.assets.open(library.asset!!).bufferedReader().use { reflow(it.readText()) } }.getOrDefault("")
        }
        Page(library.name, onBack = { showLicense = null }) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/**
 * License text as written for 80-column files, rejoined into paragraphs so it wraps to the screen:
 * lines within a paragraph are joined, blank lines kept.
 */
private fun reflow(text: String): String =
    text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
        .joinToString("\n\n") { paragraph -> paragraph.lines().joinToString(" ") { it.trim() }.trim() }
        .trim()

/** A full-screen page with a back arrow and [title], its [content] scrolling under it. */
@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로") }
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) { content() }
        }
    }
}

/** A row as in Android's settings: a title, an optional line under it, an arrow when it opens more. */
@Composable
private fun InfoRow(title: String, detail: String?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The launcher icon: its foreground on the background colour, cut to a rounded square. The
 * foreground layer is 108 dp with the art in the middle 72, so it is drawn at 1.5× and clipped.
 */
@Composable
private fun AppIcon() {
    Box(
        Modifier
            .size(88.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.5f, scaleY = 1.5f),
        )
    }
}
