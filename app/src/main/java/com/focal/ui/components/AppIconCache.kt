package com.focal.ui.components

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

internal object AppIconCache {
    private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    fun get(packageName: String): ImageBitmap? = bitmapCache[packageName]

    fun load(context: Context, packageName: String): ImageBitmap? {
        bitmapCache[packageName]?.let { return it }
        val bitmap = try {
            context.packageManager
                .getApplicationIcon(packageName)
                .toBitmap()
                .asImageBitmap()
        } catch (_: Exception) {
            null
        }
        if (bitmap != null) {
            bitmapCache[packageName] = bitmap
        }
        return bitmap
    }

    fun prewarm(context: Context, packageNames: List<String>) {
        val appContext = context.applicationContext
        for (pkg in packageNames) {
            if (!bitmapCache.containsKey(pkg)) {
                load(appContext, pkg)
            }
        }
    }
}
