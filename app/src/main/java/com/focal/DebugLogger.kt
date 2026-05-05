package com.focal

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.focal.data.db.entity.NotificationEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private var ctx: Context? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private const val NOTIF_FILE = "focal_notifications_debug.txt"

    fun init(context: Context) {
        ctx = context.applicationContext
        Log.d("DebugLogger", "Logging to Downloads/$NOTIF_FILE")
    }

    fun logNotification(n: NotificationEntity) {
        append(NOTIF_FILE, buildString {
            append("[${fmt.format(Date())}] id=${n.id}\n")
            append("  app=${n.appName}\n")
            append("  title=${n.title}\n")
            append("  bigText=${n.bigText}\n")
            append("  content=${n.content}\n")
            append("  conversation=${n.conversation}\n")
            append("  extrasJson=${n.extrasJson}\n")
            append("  pkg=${n.packageName}\n")
            append("  category=${n.category}  classifiedBy=${n.classifiedBy}  ruleId=${n.ruleId}\n")
            append("  isSummary=${n.isSummary}  summaryText=${n.summaryText}\n")
            append("  notificationKey=${n.notificationKey}\n")
            append("  postedAt=${n.postedAt}  capturedAt=${n.capturedAt}\n")
            append("---\n")
        })
    }

    private fun append(filename: String, text: String) {
        val resolver = ctx?.contentResolver ?: return
        try {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI

            val existingUri = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                arrayOf(filename),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                else null
            }

            val uri = existingUri ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                resolver.insert(collection, values)
            } ?: return

            resolver.openOutputStream(uri, "wa")?.use { it.write(text.toByteArray()) }
        } catch (e: Exception) {
            Log.w("DebugLogger", "Failed to write $filename", e)
        }
    }
}
