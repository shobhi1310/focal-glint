package com.focal.data.repository

import com.focal.data.db.dao.TopicDao
import com.focal.data.db.entity.TopicEntity
import kotlinx.coroutines.flow.Flow

class TopicRepository(
    private val topicDao: TopicDao
) {
    private val twentyFourHoursMs = 24 * 60 * 60 * 1000L

    fun getRecentTopics(): Flow<List<TopicEntity>> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.getTopicsSince(since)
    }

    fun getByCategory(category: String): Flow<List<TopicEntity>> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.getByCategory(category, since)
    }

    suspend fun getById(topicId: String): TopicEntity? {
        return topicDao.getById(topicId)
    }

    fun totalCount(): Flow<Int> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.countSince(since)
    }

    fun countByCategory(category: String): Flow<Int> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.countByCategory(category, since)
    }

    fun getBriefing(): Flow<TopicEntity?> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.getBriefing(since)
    }

    fun getStoryTopics(): Flow<List<TopicEntity>> {
        val since = System.currentTimeMillis() - twentyFourHoursMs
        return topicDao.getStoryTopics(since)
    }

    suspend fun saveTopics(topics: List<TopicEntity>) {
        topicDao.insertAll(topics)
    }

    suspend fun clearAndSaveTopics(topics: List<TopicEntity>) {
        topicDao.replaceAll(topics)
    }

    suspend fun purgeOld() {
        val cutoff = System.currentTimeMillis() - twentyFourHoursMs
        topicDao.deleteOlderThan(cutoff)
    }

    suspend fun getActiveTopicsInWindow(since: Long, until: Long): List<TopicEntity> {
        return topicDao.getActiveTopicsInWindow(since, until)
    }

    suspend fun getBriefingInWindow(since: Long, until: Long): TopicEntity? {
        return topicDao.getBriefingInWindow(since, until)
    }

    suspend fun saveTopic(topic: TopicEntity) {
        topicDao.insert(topic)
    }

    suspend fun updateTopicMembers(topicId: String, notificationIds: String, sourceApps: String, channelCount: Int) {
        val topic = topicDao.getById(topicId) ?: return
        topicDao.update(topic.copy(
            notificationIds = notificationIds,
            sourceApps = sourceApps,
            channelCount = channelCount,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun updateTopicHeadline(topicId: String, headline: String, summary: String, briefingContribution: String?) {
        val topic = topicDao.getById(topicId) ?: return
        topicDao.update(topic.copy(
            headline = headline,
            summary = summary,
            briefingContribution = briefingContribution,
            updatedAt = System.currentTimeMillis()
        ))
    }

    suspend fun markDirty(topicId: String) {
        topicDao.markDirty(topicId, System.currentTimeMillis())
    }

    suspend fun markClean(topicId: String) {
        topicDao.markClean(topicId)
    }

    suspend fun updateTopicActions(topicId: String, actionsJson: String) {
        topicDao.updateActions(topicId, actionsJson, System.currentTimeMillis())
    }
}
