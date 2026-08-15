package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveGatePolicyTest {
    @Test
    fun strongHttpKeepsTunnelWhenDnsProbeIsTemporarilyDegraded() {
        val decision = decideAdaptiveGate(
            http = http(successes = 3),
            dns = dns(success = false),
            tun = tun(payload = true, counters = false),
            score = 72,
        )

        assertTrue(decision.accepted)
        assertEquals("dual-http+tun-payload+dns-degraded", decision.mode)
    }

    @Test
    fun strongHttpWithoutTunPayloadIsRejected() {
        val decision = decideAdaptiveGate(
            http = http(successes = 3),
            dns = dns(success = false),
            tun = tun(payload = false, counters = false),
            score = 72,
        )

        assertFalse(decision.accepted)
        assertEquals("rejected", decision.mode)
    }

    @Test
    fun oneHttpTargetStillRequiresDns() {
        assertFalse(
            decideAdaptiveGate(
                http = http(successes = 1),
                dns = dns(success = false),
                tun = tun(payload = true, counters = false),
                score = 60,
            ).accepted,
        )
        assertTrue(
            decideAdaptiveGate(
                http = http(successes = 1),
                dns = dns(success = true),
                tun = tun(payload = true, counters = false),
                score = 60,
            ).accepted,
        )
    }

    @Test
    fun scoreThresholdStillProtectsWeakCandidates() {
        assertFalse(
            decideAdaptiveGate(
                http = http(successes = 2),
                dns = dns(success = true),
                tun = tun(payload = true, counters = true),
                score = 64,
            ).accepted,
        )
    }

    @Test
    fun runtimeTunnelSurvivesDiagnosticDnsTimeoutWithTwoWorkingPaths() {
        assertTrue(
            isRuntimeControlHealthy(
                proxyMode = false,
                http = http(successes = 1),
                dns = dns(success = false),
                tun = tun(payload = true, counters = false),
            ),
        )
    }

    @Test
    fun runtimeProxyStillRequiresDns() {
        assertFalse(
            isRuntimeControlHealthy(
                proxyMode = true,
                http = http(successes = 1),
                dns = dns(success = false),
                tun = null,
            ),
        )
    }

    @Test
    fun routeSettleDelayCoversEveryTransportClass() {
        assertEquals(1_500L, candidateRouteSettleDelayMs("cellular"))
        assertEquals(750L, candidateRouteSettleDelayMs("wifi"))
        assertEquals(500L, candidateRouteSettleDelayMs("ethernet"))
        assertEquals(1_000L, candidateRouteSettleDelayMs("other"))
    }

    @Test
    fun transientFailureDoesNotImmediatelyCoolDownCandidate() {
        val first = nextFailureCount(0L, 0, 10_000L, 600_000L)
        val second = nextFailureCount(10_000L, first, 20_000L, 600_000L)

        assertEquals(1, first)
        assertEquals(2, second)
        assertFalse(isFailureCoolingDown(10_000L, first, 20_000L, 120_000L, 2))
        assertTrue(isFailureCoolingDown(20_000L, second, 30_000L, 120_000L, 2))
        assertFalse(isFailureCoolingDown(20_000L, second, 150_000L, 120_000L, 2))
    }

    @Test
    fun learningCohortIsStableAcrossIpAndNetworkHandleChanges() {
        val first = fingerprint(key = "session-a", handle = 10L, asn = "44244", carrier = "irancell")
        val second = fingerprint(key = "session-b", handle = 20L, asn = "44244", carrier = "irancell")
        val otherCarrier = fingerprint(key = "session-c", handle = 30L, asn = "197207", carrier = "mci")

        assertEquals(first.learningKey(), second.learningKey())
        assertFalse(first.learningKey() == otherCarrier.learningKey())
        assertTrue(first.isSameUnderlyingNetwork(first.copy(key = "enriched")))
        assertFalse(first.isSameUnderlyingNetwork(second))
    }

    @Test
    fun exactStorageKeyUsesOnlyOfflineFingerprintIdentity() {
        val offline = fingerprint(key = "offline-network", handle = 10L, asn = "unknown", carrier = "fixed")
        val enriched = offline.copy(
            networkAsn = "44244",
            networkProvider = "Example Carrier",
            carrier = "Example Carrier",
            carrierClass = "irancell",
        )
        val differentOfflineNetwork = enriched.copy(key = "another-offline-network")

        assertEquals(offline.exactStorageKey(), enriched.exactStorageKey())
        assertFalse(enriched.exactStorageKey() == differentOfflineNetwork.exactStorageKey())
    }

    @Test
    fun resolverChainUsesOnlyPreferredAndOneControlledFallback() {
        val cloudflare = AdaptiveDnsResolvers.ordered(AdaptiveDnsResolvers.CLOUDFLARE.url)
        val google = AdaptiveDnsResolvers.ordered(AdaptiveDnsResolvers.GOOGLE.url)
        val quad9 = AdaptiveDnsResolvers.ordered(AdaptiveDnsResolvers.QUAD9.url)

        assertEquals(listOf("cloudflare", "google"), cloudflare.map { it.id })
        assertEquals(listOf("google", "cloudflare"), google.map { it.id })
        assertEquals(listOf("quad9", "google"), quad9.map { it.id })
        assertTrue(AdaptiveDnsResolvers.all.all { AdaptiveDnsResolvers.ordered(it.url).size <= 2 })
    }

    private fun http(successes: Int): ProbeResult = ProbeResult(
        success = successes > 0,
        totalBytes = successes * 16_384,
        detail = "test",
        succeededTargets = successes,
        attemptedTargets = 3,
    )

    private fun dns(success: Boolean): DnsProbeResult = DnsProbeResult(
        success = success,
        server = "1.1.1.1",
        answerCount = if (success) 1 else 0,
        detail = "test",
    )

    private fun tun(payload: Boolean, counters: Boolean): ProbeResult = ProbeResult(
        success = counters,
        totalBytes = if (payload) 1_024 else 0,
        detail = "test",
        succeededTargets = if (payload) 1 else 0,
        attemptedTargets = 1,
    )

    private fun fingerprint(
        key: String,
        handle: Long,
        asn: String,
        carrier: String,
    ): NetworkFingerprint = NetworkFingerprint(
        key = key,
        networkHandle = handle,
        transport = "cellular",
        carrier = carrier,
        carrierClass = carrier,
        networkAsn = asn,
        networkProvider = carrier,
        dataSubscriptionId = 1,
        metered = true,
        roaming = false,
        validated = true,
        captivePortal = false,
        mtu = 1_500,
        hasIpv4 = true,
        hasIpv6 = true,
        dnsCount = 2,
        downstreamKbps = 10_000,
        upstreamKbps = 5_000,
    )
}
