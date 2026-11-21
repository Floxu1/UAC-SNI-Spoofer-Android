package com.uacspoofer.mobile.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineDirectIntroTest {
    @Test
    fun firstOpenAfterUpdateUsesDirect() {
        for (stored in EngineMode.entries) {
            assertEquals(EngineMode.UAC_DIRECT, EngineDirectIntro.resolve(stored, introApplied = false))
        }
    }

    @Test
    fun laterOpensKeepUserChoice() {
        for (stored in EngineMode.entries) {
            assertEquals(stored, EngineDirectIntro.resolve(stored, introApplied = true))
        }
    }
}
