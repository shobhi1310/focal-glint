package com.focal.data.repository

import com.focal.data.db.dao.CorrectionDao
import com.focal.data.db.dao.RuleDao
import com.focal.data.db.entity.CorrectionEntity
import com.focal.data.db.entity.RuleEntity
import kotlinx.coroutines.flow.Flow

class RuleRepository(
    private val ruleDao: RuleDao,
    private val correctionDao: CorrectionDao
) {
    suspend fun getAllRulesOrdered(): List<RuleEntity> {
        val userRules = ruleDao.getUserRules()
        val llmRules = ruleDao.getLlmRules()
        val systemRules = ruleDao.getSystemRules()
        return userRules + llmRules + systemRules
    }

    fun observeRules(): Flow<List<RuleEntity>> {
        return ruleDao.getAllRules()
    }

    suspend fun getRulesForApp(packageName: String): List<RuleEntity> {
        return ruleDao.getRulesForApp(packageName)
    }

    suspend fun addRule(rule: RuleEntity) {
        ruleDao.insert(rule)
    }

    suspend fun addRules(rules: List<RuleEntity>) {
        ruleDao.insertAll(rules)
    }

    suspend fun replaceSystemDefaults(rules: List<RuleEntity>) {
        ruleDao.deleteSystemDefaults()
        ruleDao.insertAll(rules)
    }

    suspend fun incrementHitCount(rule: RuleEntity) {
        ruleDao.update(rule.copy(hitCount = rule.hitCount + 1))
    }

    suspend fun deleteRule(ruleId: String) {
        ruleDao.delete(ruleId)
    }

    suspend fun recordCorrection(notificationId: String, oldCategory: String, newCategory: String) {
        correctionDao.insert(
            CorrectionEntity(
                notificationId = notificationId,
                oldCategory = oldCategory,
                newCategory = newCategory
            )
        )
    }

    suspend fun getRecentCorrections(limit: Int = 10): List<CorrectionEntity> {
        return correctionDao.getRecent(limit)
    }

    suspend fun getCorrectionCount(): Int {
        return correctionDao.count()
    }
}
