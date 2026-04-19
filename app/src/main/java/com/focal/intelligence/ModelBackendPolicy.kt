package com.focal.intelligence

object ModelBackendPolicy {
    fun useGpuForEmbeddings(llmUseGpu: Boolean): Boolean {
        return llmUseGpu
    }
}
