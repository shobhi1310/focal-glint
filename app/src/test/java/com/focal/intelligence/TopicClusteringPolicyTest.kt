package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopicClusteringPolicyTest {

    @Test
    fun `requires rebuild when stored clustering version is older`() {
        assertTrue(TopicClusteringPolicy.needsFullRebuild(storedVersion = 0))
    }

    @Test
    fun `does not require rebuild when stored clustering version is current`() {
        assertFalse(TopicClusteringPolicy.needsFullRebuild(storedVersion = TopicClusteringPolicy.CONFIG_VERSION))
    }

    @Test
    fun `assign threshold is 0_93`() {
        assertEquals(0.93f, TopicClusteringPolicy.ASSIGN_THRESHOLD)
    }
}
