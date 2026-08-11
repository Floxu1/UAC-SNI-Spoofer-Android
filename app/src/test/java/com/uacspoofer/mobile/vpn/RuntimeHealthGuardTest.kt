package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeHealthGuardTest {
    @Test
    fun transientFailuresKeepTunnelAndSuccessResetsCounter() {
        val guard = RuntimeHealthGuard(3)
        assertEquals(RuntimeHealthAction.KEEP_CONNECTED, guard.recordFailure(coreRunning = true))
        assertEquals(RuntimeHealthAction.KEEP_CONNECTED, guard.recordFailure(coreRunning = true))
        guard.recordHealthy()
        assertEquals(0, guard.consecutiveFailures)
        assertEquals(RuntimeHealthAction.KEEP_CONNECTED, guard.recordFailure(coreRunning = true))
    }

    @Test
    fun sustainedFailuresRequestRecoveryAtThreshold() {
        val guard = RuntimeHealthGuard(3)
        guard.recordFailure(coreRunning = true)
        guard.recordFailure(coreRunning = true)
        assertEquals(RuntimeHealthAction.RECOVER, guard.recordFailure(coreRunning = true))
    }

    @Test
    fun stoppedCoreRequestsImmediateRecovery() {
        val guard = RuntimeHealthGuard(3)
        assertEquals(RuntimeHealthAction.RECOVER, guard.recordFailure(coreRunning = false))
        assertEquals(0, guard.consecutiveFailures)
    }
}
