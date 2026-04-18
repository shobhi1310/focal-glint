package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LlmResponseParserTest {

    @Test
    fun `parses clean JSON response`() {
        val raw = """{"category": "urgent", "reason": "OTP message", "confidence": 0.95}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
        assertEquals("llm", result.classifiedBy)
        assertEquals("OTP message", result.reason)
        assertEquals(0.95f, result.confidence, 0.01f)
    }

    @Test
    fun `parses JSON with surrounding text`() {
        val raw = """Based on my analysis, here is the result:
{"category": "noise", "reason": "Promotional content", "confidence": 0.8}
That's my classification."""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("noise", result!!.category)
    }

    @Test
    fun `parses JSON in markdown code block`() {
        val raw = "```json\n{\"category\": \"actionable\", \"reason\": \"Bill payment due\", \"confidence\": 0.7}\n```"
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("actionable", result!!.category)
    }

    @Test
    fun `parses actionable category`() {
        val raw = """{"category": "actionable", "reason": "Requires user action", "confidence": 0.85}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("actionable", result!!.category)
        assertEquals("Requires user action", result.reason)
    }

    @Test
    fun `parses digest category`() {
        val raw = """{"category": "digest", "reason": "Chat message to catch up on", "confidence": 0.75}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("digest", result!!.category)
        assertEquals("Chat message to catch up on", result.reason)
    }

    @Test
    fun `informational is now invalid category`() {
        val raw = """{"category": "informational", "reason": "Regular message", "confidence": 0.7}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNull(result)
    }

    @Test
    fun `handles uppercase category`() {
        val raw = """{"category": "URGENT", "reason": "test", "confidence": 0.9}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
    }

    @Test
    fun `returns null for invalid category`() {
        val raw = """{"category": "important", "reason": "test", "confidence": 0.9}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNull(result)
    }

    @Test
    fun `returns null for unparseable response`() {
        val raw = "I think this is an urgent notification."
        val result = LlmResponseParser.parseClassification(raw)
        assertNull(result)
    }

    @Test
    fun `returns null for empty response`() {
        val result = LlmResponseParser.parseClassification("")
        assertNull(result)
    }

    @Test
    fun `clamps confidence to valid range`() {
        val raw = """{"category": "urgent", "reason": "test", "confidence": 1.5}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals(1.0f, result!!.confidence, 0.01f)
    }

    @Test
    fun `defaults confidence when missing`() {
        val raw = """{"category": "noise", "reason": "promo"}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals(0.5f, result!!.confidence, 0.01f)
    }

    @Test
    fun `parseSummary returns first non-blank line`() {
        val raw = "The family is planning a weekend trip to Manali, with Dad booking the hotel."
        val result = LlmResponseParser.parseSummary(raw)
        assertEquals("The family is planning a weekend trip to Manali, with Dad booking the hotel.", result)
    }

    @Test
    fun `parseSummary skips Summary prefix`() {
        val raw = "Summary:\nPlanning weekend trip to Manali."
        val result = LlmResponseParser.parseSummary(raw)
        assertEquals("Planning weekend trip to Manali.", result)
    }

    @Test
    fun `parseSummary returns null for blank`() {
        val result = LlmResponseParser.parseSummary("   ")
        assertNull(result)
    }
}
