package com.focal.intelligence

import com.focal.data.db.entity.RuleEntity
import com.focal.intelligence.ClassificationResult.Companion.MATTERS
import com.focal.intelligence.ClassificationResult.Companion.NOISE

object DefaultRules {

    fun get(): List<RuleEntity> = listOf(
        // Noise — ride-hailing / cab apps
        appRule("com.rapido.passenger", NOISE),
        appRule("com.ubercab", NOISE),
        appRule("com.olacabs.customer", NOISE),

        // Noise — shopping / e-commerce
        appRule("com.flipkart.android", NOISE),
        appRule("com.amazon.mShop.android.shopping", NOISE),
        appRule("com.myntra.android", NOISE),
        appRule("com.snapdeal.main", NOISE),

        // Noise — payments / wallets (promos, cashback, rewards)
        appRule("net.one97.paytm", NOISE),
        appRule("com.phonepe.app", NOISE),
        appRule("com.google.android.apps.nbu.paisa", NOISE),
        appRule("in.org.npci.upiapp", NOISE),  // BHIM

        // Noise — gaming
        appRule("com.dream11.fantasy.cricket", NOISE),

        // Noise — news / media (no personal narrative value)
        appRule("com.google.android.apps.magazines", NOISE),  // Google News
        appRule("com.google.android.youtube", NOISE),
        appRule("com.google.android.apps.photos", NOISE),

        // Noise — caller ID (duplicates SMS as its own notification)
        appRule("com.truecaller", NOISE),

        // Noise — system / bloatware
        appRule("com.miui.msa.global", NOISE),  // MIUI ad services

        // Matters — personal communication
        appRule("com.whatsapp", MATTERS),
        appRule("org.telegram.messenger", MATTERS),
        appRule("com.Slack", MATTERS),
        appRule("com.discord", MATTERS),
        appRule("com.google.android.apps.messaging", MATTERS),

        // Matters — financial (transactions, not promos)
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
