package com.uacspoofer.mobile.ui

import org.junit.Assert.assertNull
import org.junit.Test

class HomeGuideTest {
    @Test
    fun guideNeverShows() {
        for (e in listOf(true, false)) for (c in listOf(true, false)) for (t in listOf(true, false)) for (d in listOf(true, false)) {
            assertNull(HomeGuide.step(engineSeen = e, countrySeen = c, torMode = t, drawerOpen = d))
        }
    }
}
