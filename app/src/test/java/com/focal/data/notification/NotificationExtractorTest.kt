package com.focal.data.notification

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationExtractorTest {

    @Test
    fun ignoredPackagesContainsSystemUi() {
        assertTrue(NotificationExtractor.IGNORED_PACKAGES.contains("com.android.systemui"))
    }

    @Test
    fun ignoredPackagesContainsAndroidCore() {
        assertTrue(NotificationExtractor.IGNORED_PACKAGES.contains("android"))
    }

    @Test
    fun ignoredPackagesDoesNotContainWhatsApp() {
        assertTrue(!NotificationExtractor.IGNORED_PACKAGES.contains("com.whatsapp"))
    }
}
