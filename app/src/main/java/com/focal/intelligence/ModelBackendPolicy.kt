package com.focal.intelligence

object ModelBackendPolicy {
    // CPU-only: 100+ back-to-back GPU embeds starve SurfaceFlinger's render queue.
    // Gemma Embedding 0.12B is fast enough on CPU. See docs/embedding_async_gpu_plan.md
    // for the RunAsync JNI approach if GPU is needed in future.
    fun useGpuForEmbeddings(llmUseGpu: Boolean): Boolean = false
}
