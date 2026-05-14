package com.focal.intelligence

import android.content.Context
import java.io.File

object ModelAssetManager {

    private const val TOKENIZER_ASSET = "sentencepiece.model"

    /**
     * Copies sentencepiece.model from APK assets into filesDir on first use (skipped if already
     * present with the same byte length). Returns the absolute path for native code.
     *
     * Developer setup: place sentencepiece.model in app/src/main/assets/ before building.
     * Obtain it from the same source as the EmbeddingGemma .tflite (e.g. the model card on
     * HuggingFace). The file is ~800 KB and is shipped inside the APK.
     */
    fun ensureTokenizerCopied(context: Context): String {
        val dest = File(context.filesDir, TOKENIZER_ASSET)
        context.assets.open(TOKENIZER_ASSET).use { input ->
            val assetSize = input.available().toLong()
            if (dest.exists() && dest.length() == assetSize) return dest.absolutePath
            dest.outputStream().use { input.copyTo(it) }
        }
        return dest.absolutePath
    }
}
