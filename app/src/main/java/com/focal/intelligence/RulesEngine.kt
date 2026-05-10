package com.focal.intelligence

import com.focal.data.db.entity.NotificationEntity
import com.focal.data.db.entity.RuleEntity
import com.focal.data.repository.RuleRepository

class RulesEngine(private val ruleRepository: RuleRepository) {

    suspend fun classify(notification: NotificationEntity): ClassificationResult? {
        val rules = ruleRepository.getAllRulesOrdered()

        // Check rules in priority order: keyword_match -> sender_match -> app_match
        // This ensures content-based actionable detection fires before broad app rules
        val orderedRules = rules.sortedBy { rule ->
            when (rule.type) {
                "keyword_match" -> 0
                "sender_match" -> 1
                "app_match" -> 2
                else -> 3
            }
        }

        for (rule in orderedRules) {
            if (matches(rule, notification)) {
                if (rule.category == "auto") return null
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
