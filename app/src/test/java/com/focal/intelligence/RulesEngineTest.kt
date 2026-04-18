package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.RuleRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RulesEngineTest {

    private lateinit var ruleRepository: RuleRepository
    private lateinit var engine: RulesEngine

    @Before
    fun setup() {
        ruleRepository = mockk(relaxed = true)
        engine = RulesEngine(ruleRepository)
    }

    private fun notification(
        packageName: String = "com.test",
        title: String = "Test",
        content: String = "test content"
    ) = NotificationEntity(
        packageName = packageName,
        appName = "Test App",
        title = title,
        content = content,
        postedAt = System.currentTimeMillis()
    )

    @Test
    fun `app_match rule matches by package name`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r1", type = "app_match", app = "com.rapido.passenger", category = "noise", source = "system_default")
        )
        val result = engine.classify(notification(packageName = "com.rapido.passenger"))
        assertNotNull(result)
        assertEquals("noise", result!!.category)
        assertEquals("rule", result.classifiedBy)
        assertEquals("r1", result.ruleId)
    }

    @Test
    fun `app_match rule does not match different package`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r1", type = "app_match", app = "com.rapido.passenger", category = "noise", source = "system_default")
        )
        val result = engine.classify(notification(packageName = "com.whatsapp"))
        assertNull(result)
    }

    @Test
    fun `keyword_match rule matches in content`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r2", type = "keyword_match", pattern = "otp", category = "urgent", source = "system_default")
        )
        val result = engine.classify(notification(content = "Your OTP is 483921"))
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
    }

    @Test
    fun `keyword_match is case insensitive`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r2", type = "keyword_match", pattern = "urgent", category = "urgent", source = "system_default")
        )
        val result = engine.classify(notification(content = "This is URGENT please respond"))
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
    }

    @Test
    fun `sender_match rule matches by title within app`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r3", type = "sender_match", app = "com.whatsapp", pattern = "Boss", category = "urgent", source = "user_explicit")
        )
        val result = engine.classify(notification(packageName = "com.whatsapp", title = "Boss"))
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
    }

    @Test
    fun `user_explicit rules take priority over system_default`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r-user", type = "app_match", app = "com.whatsapp", category = "urgent", source = "user_explicit"),
            RuleEntity(id = "r-system", type = "app_match", app = "com.whatsapp", category = "digest", source = "system_default")
        )
        val result = engine.classify(notification(packageName = "com.whatsapp"))
        assertNotNull(result)
        assertEquals("urgent", result!!.category)
        assertEquals("r-user", result.ruleId)
    }

    @Test
    fun `hit count incremented on match`() = runTest {
        val rule = RuleEntity(id = "r1", type = "app_match", app = "com.rapido.passenger", category = "noise", source = "system_default")
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(rule)
        engine.classify(notification(packageName = "com.rapido.passenger"))
        coVerify { ruleRepository.incrementHitCount(rule) }
    }

    @Test
    fun `returns null when no rules match`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r1", type = "app_match", app = "com.rapido.passenger", category = "noise", source = "system_default")
        )
        val result = engine.classify(notification(packageName = "com.unknown.app"))
        assertNull(result)
    }

    @Test
    fun `keyword_match takes priority over app_match`() = runTest {
        coEvery { ruleRepository.getAllRulesOrdered() } returns listOf(
            RuleEntity(id = "r-app", type = "app_match", app = "com.whatsapp", category = "digest", source = "system_default"),
            RuleEntity(id = "r-keyword", type = "keyword_match", pattern = "bill", category = "actionable", source = "system_default")
        )
        val result = engine.classify(notification(packageName = "com.whatsapp", content = "Your bill is due"))
        assertNotNull(result)
        assertEquals("actionable", result!!.category)
        assertEquals("r-keyword", result.ruleId)
    }
}
