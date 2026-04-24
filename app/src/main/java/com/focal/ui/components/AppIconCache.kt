package com.focal.ui.components

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.LinkedHashMap

internal object AppIconCache {
    private const val MAX_ENTRIES = 128

    private val bitmapCache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(packageName: String): ImageBitmap? = bitmapCache[packageName]

    fun load(context: Context, packageName: String): ImageBitmap? {
        get(packageName)?.let { return it }
        val bitmap = try {
            context.packageManager.getApplicationIcon(packageName).toBitmap().asImageBitmap()
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) {
            bitmapCache[packageName] = bitmap
        }
        return bitmap
    }
}
