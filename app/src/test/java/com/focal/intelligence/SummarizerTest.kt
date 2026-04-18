package com.focal.intelligence

import com.focal.data.db.entity.AppProfileEntity
import com.focal.data.db.entity.NotificationEntity
import com.focal.data.repository.NotificationRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SummarizerTest {

    private lateinit var inferenceProvider: InferenceProvider
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var summarizer: Summarizer

    @Before
    fun setup() {
        inferenceProvider = mockk(relaxed = true)
        notificationRepository = mockk(relaxed = true)
        summarizer = Summarizer(inferenceProvider, notificationRepository)
    }

    private fun notification(
        packageName: String = "com.whatsapp",
        appName: String = "WhatsApp",
        title: String = "Mom",
        content: String = "Hello",
        conversation: String? = "Family Group"
    ) = NotificationEntity(
        packageName = packageName,
        appName = appName,
        title = title,
        content = content,
        conversation = conversation,
        postedAt = System.currentTimeMillis(),
        category = "informational",
        classifiedBy = "rule",
        processedAt = System.currentTimeMillis()
    )

    @Test
    fun `returns null when LLM not ready`() = runTest {
        every { inferenceProvider.isReady() } returns false
        val result = summarizer.summarizeForApp("com.whatsapp")
        assertNull(result)
    }

    @Test
    fun `returns null when fewer than 3 notifications`() = runTest {
        every { inferenceProvider.isReady() } returns true
        coEvery { notificationRepository.getAppProfile("com.whatsapp") } returns
            AppProfileEntity(packageName = "com.whatsapp", appName = "WhatsApp", appType = "messaging")
        coEvery { notificationRepository.getNotificationsByPackage("com.whatsapp") } returns
            listOf(notification(), notification())
        val result = summarizer.summarizeForApp("com.whatsapp")
        assertNull(result)
    }

    @Test
    fun `transactional summarization returns latest notification`() = runTest {
        every { inferenceProvider.isReady() } returns true
        val profile = AppProfileEntity(packageName = "in.swiggy.android", appName = "Swiggy", appType = "transactional")
        coEvery { notificationRepository.getAppProfile("in.swiggy.android") } returns profile
        val notifs = listOf(
            notification(packageName = "in.swiggy.android", appName = "Swiggy", title = "Order", content = "Preparing", conversation = null),
            notification(packageName = "in.swiggy.android", appName = "Swiggy", title = "Order", content = "Out for delivery", conversation = null),
            notification(packageName = "in.swiggy.android", appName = "Swiggy", title = "Order", content = "Delivered", conversation = null)
        )
        coEvery { notificationRepository.getNotificationsByPackage("in.swiggy.android") } returns notifs
        val result = summarizer.summarizeForApp("in.swiggy.android")
        assertNotNull(result)
        assertTrue(result!!.isSummary)
        assertEquals("Swiggy", result.appName)
    }

    @Test
    fun `promotional summarization counts and hides`() = runTest {
        every { inferenceProvider.isReady() } returns true
        val profile = AppProfileEntity(packageName = "com.rapido.passenger", appName = "Rapido", appType = "promotional")
        coEvery { notificationRepository.getAppProfile("com.rapido.passenger") } returns profile
        val notifs = (1..5).map {
            notification(packageName = "com.rapido.passenger", appName = "Rapido", title = "Offer", content = "Promo $it", conversation = null)
        }
        coEvery { notificationRepository.getNotificationsByPackage("com.rapido.passenger") } returns notifs
        val result = summarizer.summarizeForApp("com.rapido.passenger")
        assertNotNull(result)
        assertTrue(result!!.summaryText!!.contains("5 promotional"))
        assertEquals(ClassificationResult.NOISE, result.category)
    }
}
