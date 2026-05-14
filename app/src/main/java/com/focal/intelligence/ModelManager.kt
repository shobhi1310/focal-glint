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
    GEMMA4_E2B(
        fileName = "gemma-4-E2B-it.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        displayName = "Gemma 4 E2B",
        sizeLabel = "2.58 GB",
        maxContextTokens = 8 * 1024
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

    val gemmaEmbeddingModelFile: File
        get() = File(embeddingModelDir, GEMMA_MODEL_FILENAME)

    val tokenizerFile: File
        get() = File(embeddingModelDir, TOKENIZER_FILENAME)

    val isGemmaEmbeddingAvailable: Boolean
        get() = gemmaEmbeddingModelFile.exists() && gemmaEmbeddingModelFile.length() > 50_000_000L

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

    fun getSelectedVariant(): ModelVariant? {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("selected_model_variant", null) ?: return null
        return ModelVariant.entries.firstOrNull { it.name == raw }
    }

    fun saveSelectedVariant(variant: ModelVariant) {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_model_variant", variant.name).apply()
    }

    fun isEngineEnabled(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("llm_engine_enabled", false)
    }

    fun setEngineEnabled(enabled: Boolean) {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("llm_engine_enabled", enabled).commit()
    }

    fun deleteModel(variant: ModelVariant) {
        modelFileFor(variant).delete()
    }

    fun getContextWindowMultiplier(): Int {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getInt("context_window_multiplier", 8).coerceIn(4, 8)
    }

    fun saveContextWindowMultiplier(multiplier: Int) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putInt("context_window_multiplier", multiplier.coerceIn(4, 8)).apply()
    }

    fun getContextTokens(): Int = getContextWindowMultiplier() * 1024

    fun getBackendPreference(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("backend_preference", "gpu") == "gpu"
    }

    fun saveBackendPreference(useGpu: Boolean) {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("backend_preference", if (useGpu) "gpu" else "cpu").apply()
    }

    fun isCloudEnabled(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("cloud_inference_enabled", false)
    }

    fun setCloudEnabled(enabled: Boolean) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("cloud_inference_enabled", enabled).apply()
    }

    fun isDataConsentEnabled(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("cloud_data_consent", false)
    }

    fun setDataConsentEnabled(enabled: Boolean) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("cloud_data_consent", enabled).apply()
    }

    fun getCloudEndpoint(): String {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("cloud_endpoint", CloudClassifier.DEFAULT_BASE_URL) ?: CloudClassifier.DEFAULT_BASE_URL
    }

    fun setCloudEndpoint(url: String) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putString("cloud_endpoint", url.trimEnd('/')).apply()
    }

    fun getCloudApiKey(): String {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("cloud_api_key", "") ?: ""
    }

    fun setCloudApiKey(key: String) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putString("cloud_api_key", key.trim()).apply()
    }

    fun getCloudModelName(): String {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("cloud_model_name", CloudClassifier.DEFAULT_MODEL_NAME) ?: CloudClassifier.DEFAULT_MODEL_NAME
    }

    fun setCloudModelName(name: String) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putString("cloud_model_name", name.trim()).apply()
    }

    fun getUserFocus(): String {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getString("user_focus", "") ?: ""
    }

    fun setUserFocus(focus: String) {
        context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
            .edit().putString("user_focus", focus.trim()).apply()
    }

    fun setPendingRebuild(pending: Boolean) {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("pending_topic_rebuild", pending).apply()
    }

    fun getPendingRebuild(): Boolean {
        val prefs = context.getSharedPreferences("focal_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("pending_topic_rebuild", false)
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
        const val GEMMA_MODEL_FILENAME = "embeddinggemma-300M_seq1024_mixed-precision.tflite"
        const val TOKENIZER_FILENAME = "sentencepiece.model"
    }
}
