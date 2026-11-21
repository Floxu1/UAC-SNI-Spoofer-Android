package com.uacspoofer.mobile.engine.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DirectH2StartupTest {
    @Test
    fun aSuccessfulPrimaryKeepsItsOriginalRoute() = runBlocking {
        val port = startDirectH2WithFallback(
            allowRetry = true,
            startPrimary = { 1234 },
            startWithoutFragments = { error("Fallback must never run after a successful startup") },
        )
        assertEquals(1234, port)
    }

    @Test
    fun masqueCandidateTimeoutTriesTheCompatibilityProfileOnce() = runBlocking {
        var fallbackCalls = 0
        val port = startDirectH2WithFallback(
            allowRetry = true,
            startPrimary = { error("masque: candidate startup timed out") },
            startWithoutFragments = { fallbackCalls++; 4321 },
        )
        assertEquals(4321, port)
        assertEquals(1, fallbackCalls)
    }

    @Test
    fun otherErrorsAndDisabledRetriesKeepTheOriginalFailure() = runBlocking {
        val cases = listOf(
            IllegalStateException("masque: candidate startup timed out") to false,
            IllegalStateException("UAC Direct native core failed to load") to true,
        )
        for ((failure, allowRetry) in cases) {
            val result = runCatching {
                startDirectH2WithFallback(
                    allowRetry,
                    startPrimary = { throw failure },
                    startWithoutFragments = { error("Fallback must not run") },
                )
            }
            assertSame(failure, result.exceptionOrNull())
        }
    }
}
