package com.tabpdf

import android.content.Context
import com.tabpdf.ink.Brushes
import com.tabpdf.ink.InkTool
import com.tabpdf.ink.ToolStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** When a finger draws in handwriting mode (기획서 5.8.3). */
enum class FingerDrawing(val key: String) {
    /** Until a pen has been used on this device. */
    Auto("auto"),
    Always("always"),
    Never("never"),
}

/** App colours (기획서 5.6): the system's light/dark setting, or always one of them. */
enum class ThemeMode(val key: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
}

/** User preferences (기획서 5.6). Kept apart from the tab session; small enough for SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEEP_SCREEN_ON, false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).apply()
    }

    private val _theme = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.key == prefs.getString(THEME, null) } ?: ThemeMode.System
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(THEME, mode.key).apply()
    }

    // ---- Handwriting (기획서 5.8) ----

    private val _inkTool = MutableStateFlow(InkTool.fromKey(prefs.getString(INK_TOOL, null) ?: ""))
    val inkTool: StateFlow<InkTool> = _inkTool.asStateFlow()

    fun setInkTool(tool: InkTool) {
        _inkTool.value = tool
        prefs.edit().putString(INK_TOOL, tool.key).apply()
    }

    /** Each tool's last colour and size (app-wide, not per tab). */
    private val _toolStyles = MutableStateFlow(InkTool.entries.associateWith { loadStyle(it) })
    val toolStyles: StateFlow<Map<InkTool, ToolStyle>> = _toolStyles.asStateFlow()

    fun setToolStyle(tool: InkTool, style: ToolStyle) {
        _toolStyles.value = _toolStyles.value + (tool to style)
        prefs.edit().putInt("ink_${tool.key}_color", style.color).putFloat("ink_${tool.key}_size", style.size).apply()
    }

    private fun loadStyle(tool: InkTool): ToolStyle {
        val default = when (tool) {
            InkTool.Pen -> ToolStyle(Brushes.penColors[0], Brushes.penSizes[1])
            InkTool.Highlighter -> ToolStyle(Brushes.highlighterColors[0], Brushes.highlighterSizes[1])
            InkTool.Eraser -> ToolStyle(0, Brushes.eraserSizes[1])
        }
        return ToolStyle(
            prefs.getInt("ink_${tool.key}_color", default.color),
            prefs.getFloat("ink_${tool.key}_size", default.size),
        )
    }

    /** Colours picked by hand for each tool, most recent first, shown after the presets. */
    private val _recentColors = MutableStateFlow(InkTool.entries.associateWith { loadRecent(it) })
    val recentColors: StateFlow<Map<InkTool, List<Int>>> = _recentColors.asStateFlow()

    fun addRecentColor(tool: InkTool, color: Int) {
        val list = (listOf(color) + _recentColors.value[tool].orEmpty().filter { it != color }).take(MAX_RECENT_COLORS)
        _recentColors.value = _recentColors.value + (tool to list)
        prefs.edit().putString("ink_${tool.key}_recent", list.joinToString(",")).apply()
    }

    private fun loadRecent(tool: InkTool): List<Int> =
        prefs.getString("ink_${tool.key}_recent", null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()

    private val _fingerDrawing = MutableStateFlow(
        FingerDrawing.entries.firstOrNull { it.key == prefs.getString(FINGER_DRAWING, null) } ?: FingerDrawing.Auto
    )
    val fingerDrawing: StateFlow<FingerDrawing> = _fingerDrawing.asStateFlow()

    fun setFingerDrawing(mode: FingerDrawing) {
        _fingerDrawing.value = mode
        prefs.edit().putString(FINGER_DRAWING, mode.key).apply()
    }

    /** A pen has touched the screen in handwriting mode at least once (for [FingerDrawing.Auto]). */
    private val _stylusSeen = MutableStateFlow(prefs.getBoolean(STYLUS_SEEN, false))
    val stylusSeen: StateFlow<Boolean> = _stylusSeen.asStateFlow()

    fun markStylusSeen() {
        if (_stylusSeen.value) return
        _stylusSeen.value = true
        prefs.edit().putBoolean(STYLUS_SEEN, true).apply()
    }

    /** Holding the pen's side button erases (기획서 5.8.4). */
    private val _sideButtonErases = MutableStateFlow(prefs.getBoolean(SIDE_BUTTON_ERASES, true))
    val sideButtonErases: StateFlow<Boolean> = _sideButtonErases.asStateFlow()

    fun setSideButtonErases(on: Boolean) {
        _sideButtonErases.value = on
        prefs.edit().putBoolean(SIDE_BUTTON_ERASES, on).apply()
    }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val THEME = "theme"
        const val INK_TOOL = "ink_tool"
        const val FINGER_DRAWING = "ink_finger_drawing"
        const val STYLUS_SEEN = "ink_stylus_seen"
        const val SIDE_BUTTON_ERASES = "ink_side_button_erases"
        const val MAX_RECENT_COLORS = 4
    }
}
