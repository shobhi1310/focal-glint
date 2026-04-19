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
    val sizeLabel: String,
    val maxContextTokens: Int
) {
    GEMMA3_1B(
        fileName = "gemma3-1b-it-int4.litertlm",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        displayName = "Gemma 3 1B",
        sizeLabel = "~500 MB",
        maxContextTokens = 8192
    ),
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        sizeLabel = "2.58 GB",
        maxContextTokens = 32768
    )
}

class ModelManager(private val context: Context) {

    // Primary dir for new downloads — same as Companion app
    val modelDir: File
        get() = context.getExternalFilesDir("models") ?: File(context.filesDir, "models")

    // Legacy internal path used before external storage migration
    private val legacyModelDir: File
        get() = File(context.filesDir, "models")

    val embeddingModelDir: File
        get() = File(modelDir, "embeddings")

    val geckoModelFile: File
        get() = File(embeddingModelDir, GECKO_MODEL_FILENAME)

    val geckoTokenizerFile: File
        get() = File(embeddingModelDir, GECKO_TOKENIZER_FILENAME)

    val isEmbeddingModelAvailable: Boolean
        get() = geckoModelFile.exists() && geckoModelFile.length() > 1_000_000L &&
                geckoTokenizerFile.exists()

    fun ensureEmbeddingModelDir() {
        if (!embeddingModelDir.exists()) embeddingModelDir.mkdirs()
    }

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

    // Variant-aware API — checks legacy internal path first for backward compat
    fun modelFileFor(variant: ModelVariant): File {
        val legacyFile = File(legacyModelDir, variant.fileName)
        if (legacyFile.exists() && legacyFile.length() > MIN_MODEL_SIZE) return legacyFile
        return File(modelDir, variant.fileName)
    }

    fun isModelAvailable(variant: ModelVariant): Boolean {
        val file = modelFileFor(variant)
        return file.exists() && file.length() > MIN_MODEL_SIZE
    }

    fun activeVariant(): ModelVariant? =
        ModelVariant.entries.firstOrNull { isModelAvailable(it) }

    fun deleteModel(variant: ModelVariant) {
        modelFileFor(variant).delete()
    }

    fun getBackendPreference(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("backend_preference", "gpu") == "gpu"
    }

    fun saveBackendPreference(useGpu: Boolean) {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("backend_preference", if (useGpu) "gpu" else "cpu").apply()
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

    suspend fun downloadEmbeddingModel(onProgress: (Int) -> Unit) {
        ensureEmbeddingModelDir()

        val dm = context.getSystemService(DownloadManager::class.java)

        // Download model file
        val modelRequest = DownloadManager.Request(Uri.parse(GECKO_MODEL_URL))
            .setDestinationUri(Uri.fromFile(geckoModelFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Downloading Gecko embedding model...")
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
        val modelDownloadId = dm.enqueue(modelRequest)

        withContext(Dispatchers.IO) {
            while (true) {
                delay(1000)
                val cursor = dm.query(DownloadManager.Query().setFilterById(modelDownloadId))
                if (!cursor.moveToFirst()) { cursor.close(); continue }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { onProgress(90); break }
                    DownloadManager.STATUS_FAILED -> throw IOException("Embedding model download failed")
                    else -> {
                        val percent = if (total > 0) ((downloaded * 80) / total).toInt() else 0
                        onProgress(percent)
                    }
                }
            }
        }

        // Download tokenizer
        val tokenizerRequest = DownloadManager.Request(Uri.parse(GECKO_TOKENIZER_URL))
            .setDestinationUri(Uri.fromFile(geckoTokenizerFile))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setTitle("Downloading tokenizer...")
            .setAllowedNetworkTypes(
                DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
            )
        val tokenizerDownloadId = dm.enqueue(tokenizerRequest)

        withContext(Dispatchers.IO) {
            while (true) {
                delay(500)
                val cursor = dm.query(DownloadManager.Query().setFilterById(tokenizerDownloadId))
                if (!cursor.moveToFirst()) { cursor.close(); continue }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                cursor.close()
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> { onProgress(100); return@withContext }
                    DownloadManager.STATUS_FAILED -> throw IOException("Tokenizer download failed")
                    else -> onProgress(95)
                }
            }
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
        const val MIN_MODEL_SIZE = 100_000_000L
        const val GECKO_MODEL_FILENAME = "Gecko_256_f32.tflite"
        const val GECKO_TOKENIZER_FILENAME = "sentencepiece.model"
        const val GECKO_MODEL_URL = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/Gecko_256_f32.tflite"
        const val GECKO_TOKENIZER_URL = "https://huggingface.co/litert-community/Gecko-110m-en/resolve/main/sentencepiece.model"
    }
}
