package com.focal.intelligence

import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import java.io.Closeable

/**
 * Runs EmbeddingGemma inference via LiteRT CompiledModel + SentencePiece tokenization.
 *
 * VERIFY REQUIRED before shipping:
 *   - MAX_SEQ_LEN: derived from model filename (seq1024); confirm via logcat on first run.
 *   - BOS_TOKEN_ID: Gemma uses 2; verify against model card.
 *   - PREPEND_BOS: set to true by default; check if model expects it.
 *   - Input ordering: index 0 assumed input_ids, index 1 assumed attention_mask.
 */
class EmbeddingGemmaLiteRtEmbedder(
    private val modelPath: String,
    private val useGpu: Boolean
) : Closeable {

    // ── VERIFY REQUIRED ──────────────────────────────────────────────────────
    private val MAX_SEQ_LEN   = 1024  // from filename: seq1024
    private val PAD_TOKEN_ID  = 0     // SentencePiece padding token
    private val BOS_TOKEN_ID  = 2     // Gemma BOS token
    private val PREPEND_BOS   = true  // whether model expects BOS prepended
    // ─────────────────────────────────────────────────────────────────────────

    @Volatile private var model: CompiledModel? = null
    @Volatile private var tokenizer: SentencePieceTokenizer? = null
    // Pre-allocated once at initialize() and reused across all embed() calls.
    // Avoids 200 GPU buffer alloc/free cycles per 100-notification rebuild.
    private var cachedInputs: List<TensorBuffer>? = null
    private var cachedOutputs: List<TensorBuffer>? = null

    fun initialize(tokenizerPath: String) {
        val m = CompiledModel.create(modelPath, buildModelOptions(useGpu))
        cachedInputs = m.createInputBuffers()
        cachedOutputs = m.createOutputBuffers()
        logModelInfo()
        model = m
        tokenizer = SentencePieceTokenizer(tokenizerPath).also { it.initialize() }
    }

    fun embed(text: String): FloatArray {
        val m      = model         ?: error("EmbeddingGemmaLiteRtEmbedder not initialized")
        val tok    = tokenizer     ?: error("EmbeddingGemmaLiteRtEmbedder not initialized")
        val inputs = cachedInputs  ?: error("EmbeddingGemmaLiteRtEmbedder not initialized")
        val outputs = cachedOutputs ?: error("EmbeddingGemmaLiteRtEmbedder not initialized")

        val ids  = buildInputIds(tok.encode(text))
        val mask = IntArray(MAX_SEQ_LEN) { i -> if (ids[i] != PAD_TOKEN_ID) 1 else 0 }

        inputs[0].writeInt(ids)
        if (inputs.size > 1) inputs[1].writeInt(mask)

        m.run(inputs, outputs)

        return VectorMath.l2Normalize(outputs[0].readFloat())
    }

    internal fun buildInputIds(rawIds: IntArray): IntArray {
        val withBos = if (PREPEND_BOS) IntArray(rawIds.size + 1).also {
            it[0] = BOS_TOKEN_ID
            rawIds.copyInto(it, 1)
        } else rawIds

        return IntArray(MAX_SEQ_LEN) { i ->
            if (i < withBos.size) withBos[i] else PAD_TOKEN_ID
        }
    }

    internal fun buildModelOptions(useGpu: Boolean): CompiledModel.Options {
        return if (useGpu) {
            CompiledModel.Options(Accelerator.GPU).also { options ->
                // EmbeddingGemma doesn't support float16 — force FP32 to prevent NaN outputs.
                // infiniteFloatCapping guards against any residual overflow propagating as NaN.
                options.gpuOptions = CompiledModel.GpuOptions(
                    precision = CompiledModel.GpuOptions.Precision.FP32,
                    infiniteFloatCapping = true
                )
            }
        } else {
            CompiledModel.Options(Accelerator.CPU).also { options ->
                options.cpuOptions = CompiledModel.CpuOptions()
            }
        }
    }

    private fun logModelInfo() {
        try {
            val ins  = cachedInputs  ?: return
            val outs = cachedOutputs ?: return
            Log.d(TAG, "Model loaded — inputs: ${ins.size}, outputs: ${outs.size}")
            Log.d(TAG, "Output[0] size: ${outs[0].readFloat().size}")
            Log.d(TAG, "Embedding backend requested: ${if (useGpu) "GPU" else "CPU"}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not log model info", e)
        }
    }

    override fun close() {
        tokenizer?.close()
        tokenizer = null
        cachedInputs = null
        cachedOutputs = null
        model?.close()
        model = null
    }

    fun isReady(): Boolean = model != null && tokenizer != null

    companion object {
        private const val TAG = "EmbeddingGemmaLiteRt"
    }
}
