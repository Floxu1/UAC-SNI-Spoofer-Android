package com.uacspoofer.mobile.mci

import org.junit.Assert.assertEquals
import org.junit.Test

class MciRouteSelectorTest {
    @Test
    fun primaryThenFallbackAndCooldownReordering() {
        var now = 1_000L
        val selector = MciRouteSelector { now }

        assertEquals(listOf("primary", "irancell", "fallback"), selector.orderedEdges().map(MciEdge::role))
        selector.recordFailure(MciConfig.PRIMARY_EDGE)
        assertEquals(listOf("fallback", "primary", "irancell"), selector.orderedEdges().map(MciEdge::role))

        now += MciConfig.EDGE_FAILURE_COOLDOWN_MS + 1L
        assertEquals(listOf("primary", "irancell", "fallback"), selector.orderedEdges().map(MciEdge::role))
    }
}
