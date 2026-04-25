package com.focal.intelligence

import org.junit.Assert.*
import org.junit.Test

class ExtractionToolSetTest {

    @Test
    fun `ExtractFinanceTool stores results`() {
        val tool = ExtractFinanceTool()
        tool.extractFinance(1, 425.0, "Swiggy", "food", "debit")
        tool.extractFinance(2, 3200.0, "ICICI", "bills", "debit")

        assertEquals(2, tool.results.size)
        assertEquals("finance", tool.results[0].category)
        assertEquals(1, tool.results[0].notificationIndex)
        assertTrue(tool.results[0].dataJson.contains("425"))
        assertTrue(tool.results[0].dataJson.contains("Swiggy"))
    }

    @Test
    fun `ExtractionToolFactory creates only requested categories`() {
        val tools = ExtractionToolFactory.createTools(listOf("finance", "logistics"))
        assertEquals(2, tools.size)
        assertTrue(tools.containsKey("finance"))
        assertTrue(tools.containsKey("logistics"))
        assertFalse(tools.containsKey("work"))
    }

    @Test
    fun `ExtractionToolFactory collectResults aggregates all tools`() {
        val tools = ExtractionToolFactory.createTools(listOf("finance", "work"))
        (tools["finance"] as ExtractFinanceTool).extractFinance(1, 100.0, "Test", "food", "debit")
        (tools["work"] as ExtractWorkTool).extractWork(2, "PR #1", "Alice", "review_requested", "repo")

        val results = ExtractionToolFactory.collectResults(tools)
        assertEquals(2, results.size)
        assertEquals("finance", results[0].category)
        assertEquals("work", results[1].category)
    }

    @Test
    fun `empty categories produces empty tools map`() {
        val tools = ExtractionToolFactory.createTools(emptyList())
        assertTrue(tools.isEmpty())
    }
}
