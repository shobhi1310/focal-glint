package com.focal.ui.components

import android.content.Context
import android.os.UserManager

object UserPreference {
    private const val PREFS_NAME = "focal_prefs"
    private const val KEY_USER_NAME = "user_display_name"

    fun getUserName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_USER_NAME, null)
        if (!stored.isNullOrBlank()) return stored

        // Fallback: device owner name
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
            val systemName = userManager?.userName
            if (!systemName.isNullOrBlank() && systemName != "Owner") systemName
            else ""
        } catch (_: Exception) { "" }
    }

    fun setUserName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER_NAME, name.trim()).apply()
    }

    fun hasUserName(context: Context): Boolean {
        return getUserName(context).isNotBlank()
    }
}
