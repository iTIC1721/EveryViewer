package com.tabpdf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.tabpdf.AppSettings
import com.tabpdf.FingerDrawing
import com.tabpdf.ThemeMode

/**
 * App settings from the ⋮ menu (기획서 5.6), laid out like Android's own settings: sections of
 * rows, a choice of several as radio buttons, on/off as a switch.
 */
@Composable
fun SettingsScreen(settings: AppSettings, onDismiss: () -> Unit) {
    val theme by settings.theme.collectAsState()
    val keepScreenOn by settings.keepScreenOn.collectAsState()
    val fingerDrawing by settings.fingerDrawing.collectAsState()
    val stylusSeen by settings.stylusSeen.collectAsState()
    val sideButtonErases by settings.sideButtonErases.collectAsState()
    var showAbout by remember { mutableStateOf(false) }
    BackHandler(onBack = onDismiss)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                }
                Text("설정", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
                Section("화면")
                Label("테마")
                Choice(theme == ThemeMode.System, "시스템 설정에 따름") { settings.setTheme(ThemeMode.System) }
                Choice(theme == ThemeMode.Light, "밝게") { settings.setTheme(ThemeMode.Light) }
                Choice(theme == ThemeMode.Dark, "어둡게") { settings.setTheme(ThemeMode.Dark) }
                Toggle("화면 켜짐 유지", "보는 동안 화면이 꺼지지 않음", keepScreenOn, settings::setKeepScreenOn)

                Divider()
                Section("필기")
                Label("필기 모드에서 손가락으로 그리기")
                Choice(fingerDrawing == FingerDrawing.Auto, "자동 — 펜을 쓰면 펜만 그림" + if (stylusSeen) " (펜 사용 중)" else "") {
                    settings.setFingerDrawing(FingerDrawing.Auto)
                }
                Choice(fingerDrawing == FingerDrawing.Always, "항상 — 펜이 있어도 손가락도 그림") {
                    settings.setFingerDrawing(FingerDrawing.Always)
                }
                Choice(fingerDrawing == FingerDrawing.Never, "안 함 — 손가락은 넘기기만") {
                    settings.setFingerDrawing(FingerDrawing.Never)
                }
                Toggle("펜 측면 버튼으로 지우기", "버튼을 누른 채 그리면 지우개", sideButtonErases, settings::setSideButtonErases)

                Divider()
                Section("정보")
                Row(
                    Modifier.fillMaxWidth().clickable { showAbout = true }.padding(horizontal = 24.dp, vertical = 16.dp),
                ) {
                    Text("앱 정보", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Choice(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(Modifier.padding(top = 12.dp))
}
