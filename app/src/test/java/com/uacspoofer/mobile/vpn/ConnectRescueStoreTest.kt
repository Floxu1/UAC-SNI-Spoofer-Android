package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectRescueStoreTest {
    @Test
    fun stepNumbersFollowTheRescuePipeline() {
        assertEquals(1, snapshot(ConnectRescuePhase.COLLECTING).stepNumber)
        assertEquals(2, snapshot(ConnectRescuePhase.PREFLIGHT).stepNumber)
        assertEquals(3, snapshot(ConnectRescuePhase.SCREENING).stepNumber)
        assertEquals(4, snapshot(ConnectRescuePhase.SELECTING).stepNumber)
        assertEquals(5, snapshot(ConnectRescuePhase.RETRYING).stepNumber)
        assertEquals(5, snapshot(ConnectRescuePhase.SUCCEEDED).stepNumber)
        assertEquals(0, ConnectRescueSnapshot.Hidden.stepNumber)
    }

    @Test
    fun successfulRescueFillsTheProgressBar() {
        assertEquals(1f, snapshot(ConnectRescuePhase.SUCCEEDED).overallProgress, 0.001f)
        assertFalse(ConnectRescueSnapshot.Hidden.visible)
        assertTrue(snapshot(ConnectRescuePhase.COLLECTING).visible)
    }

    @Test
    fun screeningProgressSitsInsideStepThree() {
        val snapshot = snapshot(ConnectRescuePhase.SCREENING, completed = 6, total = 12)
        assertEquals(0.5f, snapshot.overallProgress, 0.02f)
    }

    private fun snapshot(
        phase: ConnectRescuePhase,
        completed: Int = 0,
        total: Int = 0,
    ) = ConnectRescueSnapshot(
        phase = phase,
        generation = 1L,
        completed = completed,
        total = total,
    )
}
