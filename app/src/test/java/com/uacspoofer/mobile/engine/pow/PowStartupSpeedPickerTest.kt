package com.uacspoofer.mobile.engine.pow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PowStartupSpeedPickerTest {
    private val first = PowStartupSpeedPicker.Route("162.159.198.1:443", "F")
    private val second = PowStartupSpeedPicker.Route("162.159.198.2:443", "F")
    private fun sample(rate: Long) = PowStartupSpeedPicker.Sample(rate, 100)

    @Test fun candidatesReuseOnlyValidKnownPeersAndNeverDuplicateTheInitialRoute() {
        val routes = PowStartupSpeedPicker.candidates(first,
            listOf(first.peer, second.peer, "example.com:443", "[2606:4700::1]:443"), listOf("F", "A"), null)
        assertEquals(listOf(second, first.copy(strategy = "A")), routes)
        assertFalse(PowStartupSpeedPicker.validPeer("999.1.1.1:443"))
        assertFalse(PowStartupSpeedPicker.validPeer("162.159.198.1:0"))
    }

    @Test fun unavailableDownloadKeepsTheExistingConnectionUntouched() = runBlocking {
        var activations = 0
        val result = PowStartupSpeedPicker.select(first, listOf(second), { 0L },
            { _, _ -> activations++; true }, { null }, {})
        assertEquals(first, result.route)
        assertNull(result.sample)
        assertEquals(0, activations)
    }

    @Test fun fasterLiveCandidateIsRetainedWithoutReconnectingIt() = runBlocking {
        var active = first
        var attempts = 0
        val result = PowStartupSpeedPicker.select(first, listOf(second), { 0L },
            { route, _ -> active = route; attempts++; true },
            { sample(if (active == first) 12_000_000 else 25_000_000) }, {})
        assertEquals(second, result.route)
        assertEquals(25_000_000, result.sample!!.bitsPerSecond)
        assertEquals(1, attempts)
    }

    @Test fun smallMeasurementNoiseDoesNotReplaceTheFirstPathAndTheFinalPathIsMeasuredAgain() = runBlocking {
        var active = first
        val attempts = mutableListOf<PowStartupSpeedPicker.Route>()
        var measurements = 0
        val result = PowStartupSpeedPicker.select(first, listOf(second), { 0L },
            { route, _ -> active = route; attempts += route; true },
            { measurements++; sample(if (active == first) 12_000_000 else 12_500_000) }, {})
        assertEquals(first, result.route)
        assertEquals(listOf(second, first), attempts)
        assertEquals(3, measurements)
    }

    @Test fun failedTrialRestoresThePreviouslyWorkingPath() = runBlocking {
        val attempts = mutableListOf<PowStartupSpeedPicker.Route>()
        val result = PowStartupSpeedPicker.select(first, listOf(second), { 0L },
            { route, _ -> attempts += route; route == first }, { sample(12_000_000) }, {})
        assertEquals(first, result.route)
        assertEquals(listOf(second, first), attempts)
    }

    @Test fun thrownTrialFailureAlsoRestoresTheWorkingPath() = runBlocking {
        val result = PowStartupSpeedPicker.select(first, listOf(second), { 0L },
            { route, _ -> if (route == second) error("bind failed") else true }, { sample(12_000_000) }, {})
        assertEquals(first, result.route)
    }

    @Test fun cancellationDoesNotStartAnotherConnection() = runBlocking {
        var attempts = 0
        try {
            PowStartupSpeedPicker.select(first, listOf(second), { 0L },
                { _, _ -> attempts++; throw CancellationException("stop") }, { sample(12_000_000) }, {})
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(1, attempts)
        }
    }

    @Test fun startupBudgetStopsFurtherTrials() = runBlocking {
        var clock = 0L
        var attempts = 0
        val result = PowStartupSpeedPicker.select(first, listOf(second, first.copy(strategy = "A")), { clock },
            { _, _ -> attempts++; clock += 60_000; true }, { sample(if (attempts == 0) 12_000_000 else 25_000_000) }, {})
        assertEquals(second, result.route)
        assertEquals(1, attempts)
    }
}
