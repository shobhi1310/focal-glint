package com.focal.ui.setup

import com.focal.intelligence.ModelVariant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupScreenLogicTest {

    @Test
    fun `stop button stays enabled while engine is running even when selected model differs`() {
        val state = SetupUiState(
            selectedModel = ModelVariant.GEMMA4_E2B,
            activeModel = ModelVariant.GEMMA3_1B,
            engineRunning = true,
            engineStopping = false
        )

        assertTrue(isStartStopButtonEnabled(state))
    }

    @Test
    fun `start button remains disabled when selected model is not available`() {
        val state = SetupUiState(
            selectedModel = ModelVariant.GEMMA4_E2B,
            activeModel = ModelVariant.GEMMA3_1B,
            engineRunning = false,
            engineStopping = false
        )

        assertFalse(isStartStopButtonEnabled(state))
    }
}
