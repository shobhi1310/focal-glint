package com.focal.intelligence

import com.google.ai.edge.localagents.rag.models.EmbedData
import org.junit.Assert.assertEquals
import org.junit.Test

class GeckoEmbeddingProviderTest {

    @Test
    fun `default task type is semantic similarity`() {
        val provider = GeckoEmbeddingProvider()
        val field = provider.javaClass.getDeclaredField("taskType").apply { isAccessible = true }

        assertEquals(EmbedData.TaskType.SEMANTIC_SIMILARITY, field.get(provider))
    }
}
