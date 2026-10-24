package com.tabpdf

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences (기획서 5.6). Kept apart from the tab session; small enough for SharedPreferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEEP_SCREEN_ON, false))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    fun setKeepScreenOn(on: Boolean) {
        _keepScreenOn.value = on
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).apply()
    }

    private companion object {
        const val KEEP_SCREEN_ON = "keep_screen_on"
    }
}
