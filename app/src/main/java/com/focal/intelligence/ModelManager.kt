package com.focal.intelligence

import android.content.Context
import java.io.File

class ModelManager(private val context: Context) {

    val modelDir: File
        get() = File(context.filesDir, "models")

    val modelFile: File
        get() = File(modelDir, MODEL_FILENAME)

    val isModelAvailable: Boolean
        get() = modelFile.exists() && modelFile.length() > MIN_MODEL_SIZE

    val modelPath: String
        get() = modelFile.absolutePath

    fun ensureModelDir() {
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
    }

    companion object {
        const val MODEL_FILENAME = "gemma3-1b-it-int4.litertlm"
        const val MIN_MODEL_SIZE = 100_000_000L // 100 MB minimum
    }
}
