package com.focal.data.repository

import com.focal.data.db.dao.ExtractedDataDao
import com.focal.data.db.dao.WidgetConfigDao
import com.focal.data.db.dao.WidgetStateDao
import com.focal.data.db.entity.ExtractedDataEntity
import com.focal.data.db.entity.WidgetConfigEntity
import com.focal.data.db.entity.WidgetStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

class WidgetRepository(
    private val configDao: WidgetConfigDao,
    private val extractedDataDao: ExtractedDataDao,
    private val stateDao: WidgetStateDao
) {
    fun observeConfigs(): Flow<List<WidgetConfigEntity>> = configDao.observeAll()
    fun observeStates(): Flow<List<WidgetStateEntity>> = stateDao.observeAll()
    fun observeState(widgetId: String): Flow<WidgetStateEntity?> = stateDao.observeById(widgetId)
    suspend fun getActiveCategories(): List<String> = configDao.getActiveCategories()
    suspend fun getAllConfigs(): List<WidgetConfigEntity> = configDao.getAll()
    suspend fun getConfig(id: String): WidgetConfigEntity? = configDao.getById(id)

    suspend fun createWidget(config: WidgetConfigEntity) {
        val position = configDao.count()
        configDao.insert(config.copy(position = position))
    }

    suspend fun updateWidget(config: WidgetConfigEntity) {
        configDao.update(config.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteWidget(widgetId: String) {
        stateDao.deleteById(widgetId)
        configDao.deleteById(widgetId)
    }

    suspend fun wipeWidgetData(widgetId: String) {
        val config = configDao.getById(widgetId) ?: return
        val filterApps = config.filterApps?.let {
            Json.decodeFromString<List<String>>(it)
        }
        if (filterApps != null) {
            extractedDataDao.deleteByCategoryAndApps(config.category, filterApps)
        } else {
            extractedDataDao.deleteByCategory(config.category)
        }
        stateDao.deleteById(widgetId)
    }

    suspend fun deleteExtractedRow(id: Long) {
        extractedDataDao.deleteById(id)
    }

    suspend fun restoreExtractedRow(entity: ExtractedDataEntity) {
        extractedDataDao.insert(entity)
    }

    suspend fun saveExtractedData(data: List<ExtractedDataEntity>) {
        extractedDataDao.insertAll(data)
    }

    suspend fun getExtractedData(category: String): List<ExtractedDataEntity> {
        return extractedDataDao.getByCategory(category)
    }

    suspend fun getExtractedData(category: String, packages: List<String>): List<ExtractedDataEntity> {
        return extractedDataDao.getByCategoryAndApps(category, packages)
    }

    suspend fun saveWidgetStates(states: List<WidgetStateEntity>) {
        stateDao.upsertAll(states)
    }
}
