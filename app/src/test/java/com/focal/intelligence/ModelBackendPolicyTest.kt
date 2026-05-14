package com.focal.intelligence

import org.junit.Assert.assertFalse
import org.junit.Test

class ModelBackendPolicyTest {

    @Test
    fun `embedding backend uses cpu when llm prefers gpu`() {
        assertFalse(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = true))
    }

    @Test
    fun `embedding backend uses cpu when llm prefers cpu`() {
        assertFalse(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = false))
    }
}
