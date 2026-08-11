package com.uacspoofer.mobile.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test fun comparesSemanticReleaseVersions() {
        assertTrue(isVersionNewer("v1.0.8", "1.0.7"))
        assertTrue(isVersionNewer("2.0", "1.99.99"))
        assertFalse(isVersionNewer("v1.0.7", "1.0.7"))
        assertFalse(isVersionNewer("v1.0.5", "1.0.7"))
    }
}
