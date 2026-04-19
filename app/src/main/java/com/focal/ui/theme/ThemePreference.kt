package com.focal.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

object ThemePreference {
    private const val PREFS_NAME = "focal_prefs"
    private const val KEY_THEME = "theme_mode"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode

    fun initialize(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_THEME, "SYSTEM") ?: "SYSTEM"
        _themeMode.value = try { ThemeMode.valueOf(stored) } catch (_: Exception) { ThemeMode.SYSTEM }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }
}
