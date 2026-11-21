package com.uacspoofer.mobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeEditorTapSequenceTest {
    @Test
    fun onlyFifthQuickTapUnlocksTheEditor() {
        val sequence = FiveTapSequence()
        for (time in 1000L..1300L step 100L) assertFalse(sequence.tap(time))
        assertTrue(sequence.tap(1400L))
        assertFalse(sequence.tap(1500L))
    }

    @Test
    fun pauseOrAnotherThemeBreaksTheSequence() {
        val sequence = FiveTapSequence()
        for (time in 1000L..1300L step 100L) assertFalse(sequence.tap(time))
        assertFalse(sequence.tap(1800L))
        sequence.reset()
        for (time in 1900L..2200L step 100L) assertFalse(sequence.tap(time))
        assertTrue(sequence.tap(2300L))
    }
}
