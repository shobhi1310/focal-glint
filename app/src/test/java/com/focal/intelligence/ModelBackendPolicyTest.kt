package com.focal.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelBackendPolicyTest {

    @Test
    fun `llm backend uses cpu even when preference requests gpu`() {
        assertFalse(ModelBackendPolicy.useGpuForLlm(preferGpu = true))
    }

    @Test
    fun `llm backend uses cpu when preference requests cpu`() {
        assertFalse(ModelBackendPolicy.useGpuForLlm(preferGpu = false))
    }

    @Test
    fun `embedding backend uses cpu when llm prefers gpu`() {
        assertFalse(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = true))
    }

    @Test
    fun `embedding backend uses cpu when llm prefers cpu`() {
        assertFalse(ModelBackendPolicy.useGpuForEmbeddings(llmUseGpu = false))
    }
}
