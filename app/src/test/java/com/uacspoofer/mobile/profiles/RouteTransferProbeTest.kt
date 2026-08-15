package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTransferProbeTest {
    @Test
    fun medianIsStableForOddAndEvenSamples() {
        assertEquals(30L, RouteTransferProbe.medianMs(listOf(50L, 10L, 30L)))
        assertEquals(25L, RouteTransferProbe.medianMs(listOf(40L, 10L, 30L, 20L)))
        assertNull(RouteTransferProbe.medianMs(emptyList()))
    }

    @Test
    fun jitterUsesMeanAbsoluteDifferenceBetweenConsecutiveSamples() {
        assertEquals(20L, RouteTransferProbe.jitterMs(listOf(10L, 30L, 20L, 50L)))
        assertEquals(0L, RouteTransferProbe.jitterMs(listOf(20L, 20L)))
        assertNull(RouteTransferProbe.jitterMs(listOf(20L)))
    }

    @Test
    fun throughputUsesDecimalKilobitsPerSecond() {
        assertEquals(8_000L, RouteTransferProbe.throughputKbps(bytes = 1_000_000, durationMs = 1_000L))
        assertEquals(1_024L, RouteTransferProbe.throughputKbps(bytes = 64 * 1_024, durationMs = 512L))
        assertEquals(0L, RouteTransferProbe.throughputKbps(bytes = 0, durationMs = 10L))
        assertEquals(0L, RouteTransferProbe.throughputKbps(bytes = 1_000, durationMs = 0L))
    }

    @Test
    fun byteValidationRequiresAnExactTransfer() {
        assertTrue(RouteTransferProbe.transferredExactly(65_536, 65_536))
        assertFalse(RouteTransferProbe.transferredExactly(65_536, 65_535))
        assertFalse(RouteTransferProbe.transferredExactly(65_536, 65_537))
        assertFalse(RouteTransferProbe.transferredExactly(-1, -1))
    }

    @Test
    fun endpointRateLimitAndServerErrorsAreClassifiedSeparately() {
        assertEquals(RouteEndpointFailureKind.RATE_LIMITED, RouteTransferProbe.endpointFailureKind(429))
        assertEquals(RouteEndpointFailureKind.SERVER_ERROR, RouteTransferProbe.endpointFailureKind(503))
        assertEquals(RouteEndpointFailureKind.HTTP_ERROR, RouteTransferProbe.endpointFailureKind(403))
        assertTrue(RouteTransferProbe.isEndpointTemporarilyUnavailable(RouteEndpointFailureKind.RATE_LIMITED))
        assertTrue(RouteTransferProbe.isEndpointTemporarilyUnavailable(RouteEndpointFailureKind.SERVER_ERROR))
        assertFalse(RouteTransferProbe.isEndpointTemporarilyUnavailable(RouteEndpointFailureKind.HTTP_ERROR))
    }

    @Test(expected = IllegalArgumentException::class)
    fun configRejectsUnboundedInMemoryPayloads() {
        RouteTransferProbeConfig(uploadBytes = RouteTransferProbeConfig.MAX_TRANSFER_BYTES + 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun socksProxyRejectsInvalidPort() {
        RouteSocksProxy("127.0.0.1", 0)
    }
}
