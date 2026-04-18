package com.focal.intelligence

import com.focal.data.db.entity.RuleEntity

object DefaultRules {

    fun get(): List<RuleEntity> = listOf(
        appNoise("com.rapido.passenger"),
        appNoise("com.ubercab"),
        appNoise("com.olacabs.customer"),
        appNoise("in.swiggy.android", category = "actionable"),
        appNoise("com.application.zomato", category = "actionable"),
        appNoise("com.flipkart.android"),
        appNoise("com.amazon.mShop.android.shopping"),
        appNoise("net.one97.paytm"),
        appNoise("com.phonepe.app"),
        appNoise("com.google.android.apps.nbu.paisa"),
        appNoise("com.myntra.android"),
        appNoise("com.snapdeal.main"),
        appNoise("com.dream11.fantasy.cricket"),
        appNoise("com.cred.android", category = "actionable"),

        appRule("com.whatsapp", "digest"),
        appRule("org.telegram.messenger", "digest"),
        appRule("com.Slack", "digest"),
        appRule("com.discord", "digest"),
        appRule("com.google.android.apps.messaging", "digest"),

        keywordUrgent("otp"),
        keywordUrgent("urgent"),
        keywordUrgent("asap"),
        keywordUrgent("emergency"),
        keywordUrgent("call me"),
        keywordUrgent("immediately"),
    )

    private fun appNoise(packageName: String, category: String = "noise") = RuleEntity(
        type = "app_match",
        app = packageName,
        category = category,
        confidence = 0.7f,
        source = "system_default"
    )

    private fun appRule(packageName: String, category: String) = RuleEntity(
        type = "app_match",
        app = packageName,
        category = category,
        confidence = 0.5f,
        source = "system_default"
    )

    private fun keywordUrgent(keyword: String) = RuleEntity(
        type = "keyword_match",
        pattern = keyword,
        category = "urgent",
        confidence = 0.8f,
        source = "system_default"
    )
}
