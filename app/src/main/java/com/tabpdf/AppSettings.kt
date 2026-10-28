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
    /** Until a pen has been used on this device; then by the toolbar's finger button. */
    Auto("auto"),
    Always("always"),
    Never("never"),
}

/** How many colour-and-size presets each drawing tool keeps. */
const val MAX_INK_PRESETS = 6

/** What holding or pressing the pen's side button does in handwriting mode (기획서 5.8.4). */
enum class SideButton(val key: String) {
    Eraser("eraser"),
    /** Pressed while the pen hovers: undo once. */
    Undo("undo"),
    None("none"),
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

    /** Swiping past a group's last (or first) tab goes on to the next tab outside it (기획서 5.2). */
    private val _crossGroupSwipe = MutableStateFlow(prefs.getBoolean(CROSS_GROUP_SWIPE, false))
    val crossGroupSwipe: StateFlow<Boolean> = _crossGroupSwipe.asStateFlow()

    fun setCrossGroupSwipe(on: Boolean) {
        _crossGroupSwipe.value = on
        prefs.edit().putBoolean(CROSS_GROUP_SWIPE, on).apply()
    }

    /** Several files opened at once outside any group go into a new group of their own (기획서 5.1). */
    private val _openManyAsGroup = MutableStateFlow(prefs.getBoolean(OPEN_MANY_AS_GROUP, true))
    val openManyAsGroup: StateFlow<Boolean> = _openManyAsGroup.asStateFlow()

    fun setOpenManyAsGroup(on: Boolean) {
        _openManyAsGroup.value = on
        prefs.edit().putBoolean(OPEN_MANY_AS_GROUP, on).apply()
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

    /** Saved colour-and-size pairs for each drawing tool, in the order they were added (기획서 5.8.2). */
    private val _presets = MutableStateFlow(InkTool.entries.associateWith { loadPresets(it) })
    val presets: StateFlow<Map<InkTool, List<ToolStyle>>> = _presets.asStateFlow()

    fun addPreset(tool: InkTool, style: ToolStyle) {
        val list = _presets.value[tool].orEmpty()
        if (style in list || list.size >= MAX_INK_PRESETS) return
        savePresets(tool, list + style)
    }

    fun removePreset(tool: InkTool, index: Int) {
        val list = _presets.value[tool].orEmpty()
        if (index !in list.indices) return
        savePresets(tool, list.filterIndexed { i, _ -> i != index })
    }

    private fun savePresets(tool: InkTool, list: List<ToolStyle>) {
        _presets.value = _presets.value + (tool to list)
        prefs.edit().putString("ink_${tool.key}_presets", list.joinToString(",") { "${it.color}:${it.size}" }).apply()
    }

    private fun loadPresets(tool: InkTool): List<ToolStyle> =
        prefs.getString("ink_${tool.key}_presets", null)?.split(',')?.mapNotNull { item ->
            val color = item.substringBefore(':').toIntOrNull()
            val size = item.substringAfter(':', "").toFloatOrNull()
            if (color != null && size != null) ToolStyle(color, size) else null
        }.orEmpty().take(MAX_INK_PRESETS)

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
        if (_fingersToo.value) setFingersToo(false)
        if (_stylusSeen.value) return
        _stylusSeen.value = true
        prefs.edit().putBoolean(STYLUS_SEEN, true).apply()
    }

    /**
     * Fingers draw for now although a pen has been seen (the toolbar's finger button, with the pen
     * put away); the pen touching or hovering again ends it.
     */
    private val _fingersToo = MutableStateFlow(prefs.getBoolean(FINGERS_TOO, false))
    val fingersToo: StateFlow<Boolean> = _fingersToo.asStateFlow()

    fun setFingersToo(on: Boolean) {
        _fingersToo.value = on
        prefs.edit().putBoolean(FINGERS_TOO, on).apply()
    }

    /** The pen's side button (기획서 5.8.4); before it was a choice, an on/off "erases" switch. */
    private val _sideButton = MutableStateFlow(
        SideButton.entries.firstOrNull { it.key == prefs.getString(SIDE_BUTTON, null) }
            ?: if (prefs.getBoolean(SIDE_BUTTON_ERASES, true)) SideButton.Eraser else SideButton.None
    )
    val sideButton: StateFlow<SideButton> = _sideButton.asStateFlow()

    fun setSideButton(action: SideButton) {
        _sideButton.value = action
        prefs.edit().putString(SIDE_BUTTON, action.key).apply()
    }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
        const val THEME = "theme"
        const val CROSS_GROUP_SWIPE = "cross_group_swipe"
        const val INK_TOOL = "ink_tool"
        const val FINGER_DRAWING = "ink_finger_drawing"
        const val STYLUS_SEEN = "ink_stylus_seen"
        const val FINGERS_TOO = "ink_fingers_too"
        const val SIDE_BUTTON_ERASES = "ink_side_button_erases" // read once to carry over the old switch
        const val SIDE_BUTTON = "ink_side_button"
        const val OPEN_MANY_AS_GROUP = "open_many_as_group"
        const val MAX_RECENT_COLORS = 4
    }
}
