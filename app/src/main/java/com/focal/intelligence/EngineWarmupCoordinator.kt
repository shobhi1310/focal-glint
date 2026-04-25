package com.focal.intelligence

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineWarmupCoordinator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val inferenceProvider: InferenceProvider,
    private val embeddingProvider: SwitchableEmbeddingProvider,
    private val modelManager: ModelManager
) {
    private val mutex = Mutex()

    suspend fun warmUp(): Boolean = mutex.withLock {
        val selected = modelManager.getSelectedVariant()
        val variant = selected
            ?.takeIf { modelManager.isModelAvailable(it) }
            ?: modelManager.activeVariant()
        if (variant == null) {
            Log.w(TAG, "Cannot warm engine: no selected or active model")
            return@withLock false
        }

        if (!inferenceProvider.isReady()) {
            val modelFile = modelManager.modelFileFor(variant)
            val useGpu = modelManager.getBackendPreference()
            try {
                inferenceProvider.initialize(modelFile.absolutePath, useGpu, variant.maxContextTokens)
                Log.d(TAG, "LLM warmed: ${variant.displayName} gpu=$useGpu")
            } catch (e: Exception) {
                Log.e(TAG, "LLM warm-up failed (${e.javaClass.simpleName}): ${e.message}")
                if (useGpu) {
                    inferenceProvider.initialize(modelFile.absolutePath, false, variant.maxContextTokens)
                    Log.d(TAG, "LLM warmed with CPU fallback")
                } else {
                    throw e
                }
            }
        }

        warmEmbeddings()
        true
    }

    private suspend fun warmEmbeddings() {
        if (!modelManager.isGemmaEmbeddingAvailable || embeddingProvider.isReady()) return
        embeddingProvider.inner = GemmaEmbeddingProvider(context)
        val useGpu = ModelBackendPolicy.useGpuForEmbeddings(modelManager.getBackendPreference())
        embeddingProvider.initialize(
            modelManager.gemmaEmbeddingModelFile.absolutePath,
            modelManager.tokenizerFile.absolutePath,
            useGpu
        )
        Log.d(TAG, "EmbeddingGemma warmed gpu=$useGpu")
    }

    companion object {
        private const val TAG = "EngineWarmupCoordinator"
    }
}
