package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.RuleRepository

class RulesEngine(private val ruleRepository: RuleRepository) {

    suspend fun classify(notification: NotificationEntity): ClassificationResult? {
        val rules = ruleRepository.getAllRulesOrdered()

        for (rule in rules) {
            if (matches(rule, notification)) {
                ruleRepository.incrementHitCount(rule)
                return ClassificationResult(
                    category = rule.category,
                    classifiedBy = "rule",
                    ruleId = rule.id,
                    confidence = rule.confidence
                )
            }
        }
        return null
    }

    private fun matches(rule: RuleEntity, notification: NotificationEntity): Boolean {
        return when (rule.type) {
            "app_match" -> rule.app == notification.packageName

            "sender_match" -> {
                rule.app == notification.packageName &&
                    rule.pattern != null &&
                    notification.title.contains(rule.pattern, ignoreCase = true)
            }

            "keyword_match" -> {
                val text = "${notification.title} ${notification.content} ${notification.bigText ?: ""}"
                rule.pattern != null && text.contains(rule.pattern, ignoreCase = true)
            }

            else -> false
        }
    }
}
