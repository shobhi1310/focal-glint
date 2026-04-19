package com.focal.intelligence

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

enum class ModelVariant(
    val fileName: String,
    val url: String,
    val displayName: String,
    val sizeLabel: String
) {
    GEMMA3_1B(
        fileName = "gemma3-1b-it-int4.litertlm",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        displayName = "Gemma 3 1B",
        sizeLabel = "~500 MB"
    ),
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        sizeLabel = "2.58 GB"
    )
}

class ModelManager(private val context: Context) {

    val modelDir: File
        get() = File(context.filesDir, "models")

    // Legacy single-model API — kept for FocalApplication.initializeLlmIfModelExists()
    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    val isModelAvailable: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    val modelPath: String
        get() = modelFile.absolutePath

    fun ensureModelDir() {
        if (!modelDir.exists()) modelDir.mkdirs()
    }

    // Variant-aware API
    fun modelFileFor(variant: ModelVariant): File = File(modelDir, variant.fileName)

    fun isModelAvailable(variant: ModelVariant): Boolean {
        val file = modelFileFor(variant)
        return file.exists() && file.length() > MIN_MODEL_SIZE
    }

    fun activeVariant(): ModelVariant? =
        ModelVariant.entries.firstOrNull { isModelAvailable(it) }

    fun deleteModel(variant: ModelVariant) {
        modelFileFor(variant).delete()
    }

    suspend fun downloadModel(variant: ModelVariant, onProgress: (Int) -> Unit) {
        ensureModelDir()
        val destFile = modelFileFor(variant)

        val request = DownloadManager.Request(Uri.parse(variant.url))
            .setDestinationUri(Uri.fromFile(destFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Downloading ${variant.displayName}...")
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )

        val dm = context.getSystemService(DownloadManager::class.java)
        val downloadId = dm.enqueue(request)

        withContext(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
                if (!cursor.moveToFirst()) { cursor.close(); continue }

                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        onProgress(100)
                        return@withContext
                    }
                    DownloadManager.STATUS_FAILED -> {
                        throw IOException("Download failed for ${variant.displayName}")
                    }
                    else -> {
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
                        onProgress(percent)
                    }
                }
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 100_000_000L
    }
}
