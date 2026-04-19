package com.focal.ui.components

fun getAppCategory(packageName: String): String {
    return when (packageName) {
        "com.whatsapp",
        "org.telegram.messenger",
        "com.discord",
        "com.google.android.apps.messaging",
        "com.android.phone" -> "PERSONAL"

        "com.Slack",
        "com.github.android",
        "com.microsoft.teams",
        "com.microsoft.office.outlook",
        "com.linkedin.android" -> "WORK"

        "com.cred.android",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa",
        "in.org.npci.upiapp" -> "FINANCE"

        "in.swiggy.android",
        "com.application.zomato",
        "com.ubercab",
        "com.rapido.passenger" -> "LOGISTICS"

        else -> "GENERAL"
    }
}
