package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LlmResponseParserTest {

    @Test
    fun `parseMattersClassification parses matters true`() {
        val raw = """{"matters": true, "reason": "Personal message from mom"}"""
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.MATTERS, result!!.category)
        assertEquals("llm", result.classifiedBy)
        assertEquals("Personal message from mom", result.reason)
    }

    @Test
    fun `parseMattersClassification parses matters false`() {
        val raw = """{"matters": false, "reason": "Promotional content"}"""
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.NOISE, result!!.category)
        assertEquals("Promotional content", result.reason)
    }

    @Test
    fun `parseMattersClassification parses JSON with surrounding text`() {
        val raw = """Here is my analysis:
{"matters": true, "reason": "OTP for login"}
Done."""
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.MATTERS, result!!.category)
    }

    @Test
    fun `parseMattersClassification parses JSON in markdown code block`() {
        val raw = "```json\n{\"matters\": false, \"reason\": \"Shopping promo\"}\n```"
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.NOISE, result!!.category)
    }

    @Test
    fun `parseMattersClassification falls back to matters true keyword`() {
        val raw = """The answer is: "matters": true"""
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.MATTERS, result!!.category)
    }

    @Test
    fun `parseMattersClassification falls back to noise keyword`() {
        val raw = "This looks like promotional content, clearly noise."
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.NOISE, result!!.category)
    }

    @Test
    fun `parseMattersClassification returns null for unparseable response`() {
        val raw = "I don't know what to say here."
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNull(result)
    }

    @Test
    fun `parseMattersClassification returns null for empty response`() {
        val result = LlmResponseParser.parseMattersClassification("")
        assertNull(result)
    }

    @Test
    fun `parseMattersClassification defaults matters to false when missing`() {
        val raw = """{"reason": "unclear"}"""
        val result = LlmResponseParser.parseMattersClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.NOISE, result!!.category)
    }

    @Test
    fun `parseNarrative returns trimmed first line`() {
        val raw = "The family is planning a weekend trip to Manali, with Dad booking the hotel."
        val result = LlmResponseParser.parseNarrative(raw)
        assertEquals("The family is planning a weekend trip to Manali, with Dad booking the hotel.", result)
    }

    @Test
    fun `parseNarrative strips Summary preamble`() {
        val raw = "Summary: Planning weekend trip to Manali."
        val result = LlmResponseParser.parseNarrative(raw)
        assertEquals("Planning weekend trip to Manali.", result)
    }

    @Test
    fun `parseNarrative strips Here's a summary preamble`() {
        val raw = "Here's a summary: Mom wants dinner at 8."
        val result = LlmResponseParser.parseNarrative(raw)
        assertEquals("Mom wants dinner at 8.", result)
    }

    @Test
    fun `parseNarrative returns null for blank`() {
        val result = LlmResponseParser.parseNarrative("   ")
        assertNull(result)
    }

    @Test
    fun `parseClassification still works for backward compat binary only`() {
        val raw = """{"category": "matters", "reason": "Personal", "confidence": 0.9}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNotNull(result)
        assertEquals(ClassificationResult.MATTERS, result!!.category)
    }

    @Test
    fun `parseClassification rejects old 4-class categories`() {
        val raw = """{"category": "urgent", "reason": "OTP", "confidence": 0.9}"""
        val result = LlmResponseParser.parseClassification(raw)
        assertNull(result)
    }

    @Test
    fun `parseSummary still returns first non-blank line`() {
        val raw = "The family is planning a weekend trip to Manali."
        val result = LlmResponseParser.parseSummary(raw)
        assertEquals("The family is planning a weekend trip to Manali.", result)
    }
}
