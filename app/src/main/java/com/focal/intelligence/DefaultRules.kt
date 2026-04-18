package com.focal.intelligence

import com.focal.data.db.entity.RuleEntity
import com.focal.intelligence.ClassificationResult.Companion.MATTERS
import com.focal.intelligence.ClassificationResult.Companion.NOISE

object DefaultRules {

    fun get(): List<RuleEntity> = listOf(
        // Noise — promotional/marketing apps
        appRule("com.rapido.passenger", NOISE),
        appRule("com.ubercab", NOISE),
        appRule("com.olacabs.customer", NOISE),
        appRule("com.flipkart.android", NOISE),
        appRule("com.amazon.mShop.android.shopping", NOISE),
        appRule("net.one97.paytm", NOISE),
        appRule("com.phonepe.app", NOISE),
        appRule("com.google.android.apps.nbu.paisa", NOISE),
        appRule("com.myntra.android", NOISE),
        appRule("com.snapdeal.main", NOISE),
        appRule("com.dream11.fantasy.cricket", NOISE),

        // Matters — personal communication
        appRule("com.whatsapp", MATTERS),
        appRule("org.telegram.messenger", MATTERS),
        appRule("com.Slack", MATTERS),
        appRule("com.discord", MATTERS),
        appRule("com.google.android.apps.messaging", MATTERS),

        // Matters — financial
        appRule("com.cred.android", MATTERS),

        // Matters — logistics
        appRule("in.swiggy.android", MATTERS),
        appRule("com.application.zomato", MATTERS),
    )

    private fun appRule(packageName: String, category: String) = RuleEntity(
        type = "app_match",
        app = packageName,
        category = category,
        confidence = 0.8f,
        source = "system_default"
    )
}
