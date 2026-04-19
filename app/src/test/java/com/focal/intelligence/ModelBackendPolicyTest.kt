package com.focal.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBackendPolicyTest {

    @Test
    fun `embedding backend uses gpu when llm prefers gpu`() {
        assertTrue(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = true))
    }

    @Test
    fun `embedding backend uses cpu when llm prefers cpu`() {
        assertFalse(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = false))
    }
}
