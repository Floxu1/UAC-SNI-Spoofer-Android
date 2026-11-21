package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.engine.direct.DirectConnectionCoordinator.ProbeResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectStartupVerificationTest {
    @Test
    fun healthyFirstAttemptKeepsItsRoute() = runBlocking {
        verifyDirectStartup(
            start = { skipped ->
                assertEquals(null, skipped)
                ProbeResult(true, "verified")
            },
            selectedCarrier = { error("A verified route should not be replaced") },
            prepareRetry = { _, _ -> error("A verified route should not be stopped") },
        )
    }

    @Test
    fun initialTimeoutRetriesWithoutTheFirstCarrier() = runBlocking {
        val failedIp = "192.0.2.1"
        val attempts = mutableListOf<String?>()
        verifyDirectStartup(
            start = { skipped ->
                attempts += skipped
                ProbeResult(ok = skipped == failedIp, detail = "DNS/HTTPS check")
            },
            selectedCarrier = { failedIp },
            prepareRetry = { ip, _ -> assertEquals(failedIp, ip) },
        )
        assertEquals(listOf(null, failedIp), attempts)
    }

    @Test
    fun twoFailedPathsDoNotClearTheStartupGate() = runBlocking {
        var attempts = 0
        val result = runCatching {
            verifyDirectStartup(
                start = { ProbeResult(false, "timed out").also { attempts++ } },
                selectedCarrier = { "192.0.2.1" },
                prepareRetry = { _, _ -> },
            )
        }
        assertEquals(2, attempts)
        assertEquals(
            "UAC Direct DNS or HTTPS check failed on the alternate route (timed out)",
            result.exceptionOrNull()?.message,
        )
    }
}
