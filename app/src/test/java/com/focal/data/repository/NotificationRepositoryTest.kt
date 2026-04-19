package com.focal.data.repository

import com.focal.data.db.dao.AppProfileDao
import com.focal.data.db.dao.NotificationDao
import com.focal.data.db.entity.NotificationEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Before
import org.junit.Test

class NotificationRepositoryTest {

    private lateinit var notificationDao: NotificationDao
    private lateinit var appProfileDao: AppProfileDao
    private lateinit var repository: NotificationRepository

    @Before
    fun setup() {
        notificationDao = mockk(relaxed = true)
        appProfileDao = mockk(relaxed = true)
        repository = NotificationRepository(notificationDao, appProfileDao)
    }

    private fun notification(
        id: String = "n1",
        title: String = "Shubankar Iittp",
        content: String = "Hey are we still going to Manali this weekend?",
        postedAt: Long = 1000L,
        capturedAt: Long = 1000L,
        notificationKey: String = "whatsapp:key:1"
    ) = NotificationEntity(
        id = id,
        packageName = "com.whatsapp",
        appName = "WhatsApp",
        title = title,
        content = content,
        postedAt = postedAt,
        capturedAt = capturedAt,
        notificationKey = notificationKey
    )

    @Test
    fun `upsert inserts new notification when key is unseen`() = kotlinx.coroutines.test.runTest {
        val incoming = notification()
        coEvery { notificationDao.getLatestByNotificationKey("whatsapp:key:1") } returns null

        repository.upsertNotification(incoming)

        coVerify { notificationDao.insert(incoming) }
        coVerify { appProfileDao.insertIfNew(any()) }
        coVerify { appProfileDao.incrementCount("com.whatsapp", 1000L) }
    }

    @Test
    fun `upsert ignores duplicate callback when notification content is unchanged`() = kotlinx.coroutines.test.runTest {
        val existing = notification(id = "existing")
        val duplicate = notification(id = "incoming")
        coEvery { notificationDao.getLatestByNotificationKey("whatsapp:key:1") } returns existing

        repository.upsertNotification(duplicate)

        coVerify(exactly = 0) { notificationDao.insert(any()) }
        coVerify(exactly = 0) { notificationDao.update(any()) }
        coVerify(exactly = 0) { appProfileDao.incrementCount(any(), any()) }
    }

    @Test
    fun `upsert inserts new row when notification content changes for same key`() = kotlinx.coroutines.test.runTest {
        val existing = notification(id = "existing")
        val updated = notification(
            id = "incoming",
            content = "I found a nice hotel near Old Manali for 3500 per night",
            postedAt = 2000L,
            capturedAt = 2000L
        )
        coEvery { notificationDao.getLatestByNotificationKey("whatsapp:key:1") } returns existing

        repository.upsertNotification(updated)

        coVerify { notificationDao.insert(updated) }
        coVerify(exactly = 0) { notificationDao.update(any()) }
        coVerify { appProfileDao.incrementCount("com.whatsapp", 2000L) }
    }
}
