package com.focal.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestedActionParserTest {

    @Test
    fun `parses complete topic with actions`() {
        val raw = """
            TITLE: Mom wants Sunday lunch
            SUMMARY: Two missed calls and a WhatsApp asking if you're bringing Maya.
            ACTIONS:
            - Call Mom back — she tried twice | call | Phone
            - Reply about Sunday plans | reply | WhatsApp
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("Mom wants Sunday lunch", result.title)
        assertEquals("Two missed calls and a WhatsApp asking if you're bringing Maya.", result.summary)
        assertEquals(2, result.actions.size)
        assertEquals("Call Mom back — she tried twice", result.actions[0].label)
        assertEquals("call", result.actions[0].type)
        assertEquals("Phone", result.actions[0].app)
        assertEquals("reply", result.actions[1].type)
    }

    @Test
    fun `parses topic without actions section`() {
        val raw = """
            TITLE: ICICI card charge
            SUMMARY: Rs 44,000 spent at Amazon.
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("ICICI card charge", result.title)
        assertTrue(result.actions.isEmpty())
    }

    @Test
    fun `invalid action type defaults to open_app`() {
        val raw = """
            TITLE: Test
            SUMMARY: Test summary.
            ACTIONS:
            - Do something | unknown_type | SomeApp
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals("open_app", result.actions[0].type)
    }

    @Test
    fun `caps at 3 actions`() {
        val raw = """
            TITLE: Test
            SUMMARY: Test summary.
            ACTIONS:
            - Action one | call | Phone
            - Action two | reply | WhatsApp
            - Action three | open_app | Gmail
            - Action four | view | Chrome
        """.trimIndent()

        val result = LlmResponseParser.parseTopicContent(raw)!!
        assertEquals(3, result.actions.size)
    }

    @Test
    fun `SuggestedAction JSON round-trip`() {
        val actions = listOf(
            SuggestedAction("Call Mom", "call", "Phone", "com.android.phone"),
            SuggestedAction("Reply", "reply", "WhatsApp", "com.whatsapp")
        )
        val json = SuggestedAction.listToJson(actions)
        val restored = SuggestedAction.listFromJson(json)
        assertEquals(2, restored.size)
        assertEquals("Call Mom", restored[0].label)
        assertEquals("com.android.phone", restored[0].packageName)
    }

    @Test
    fun `listFromJson handles null and empty`() {
        assertTrue(SuggestedAction.listFromJson(null).isEmpty())
        assertTrue(SuggestedAction.listFromJson("").isEmpty())
        assertTrue(SuggestedAction.listFromJson("invalid").isEmpty())
    }
}
