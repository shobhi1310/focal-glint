package com.focal.intelligence

import android.util.Log
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.WidgetRepository

internal class ExtractionResultSaver(
    private val widgetRepository: WidgetRepository?
) {
    suspend fun save(
        extractionResults: List<ExtractionResult>,
        notifications: List<NotificationEntity>
    ) {
        if (extractionResults.isEmpty() || widgetRepository == null) return

        try {
            val entities = extractionResults.mapNotNull { result ->
                val notification = notifications.getOrNull(result.notificationIndex - 1) ?: return@mapNotNull null
                ExtractedDataEntity(
                    notificationId = notification.id,
                    category = result.category,
                    data = result.dataJson,
                    appPackage = notification.packageName
                )
            }
            if (entities.isEmpty()) {
                Log.w(TAG, "No valid extraction rows to save")
                return
            }
            widgetRepository.saveExtractedData(entities)
            Log.i(TAG, "Saved ${entities.size} extracted data rows")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save extraction results", e)
        }
    }

    private companion object {
        const val TAG = "ExtractionResultSaver"
    }
}
