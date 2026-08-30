package com.uacspoofer.mobile.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideShellTest {
    @Test
    fun phonePortraitIsNotWide() {
        assertFalse(WideShell.isWide(360.dp, 800.dp))
        assertFalse(WideShell.isWide(411.dp, 890.dp))
    }

    @Test
    fun landscapeTvIsWide() {
        assertTrue(WideShell.isWide(960.dp, 540.dp))
        assertTrue(WideShell.isWide(1280.dp, 720.dp))
    }

    @Test
    fun squareTabletIsNotTreatedAsTvShell() {
        assertFalse(WideShell.isWide(800.dp, 800.dp))
        assertFalse(WideShell.isWide(600.dp, 580.dp))
    }
}
