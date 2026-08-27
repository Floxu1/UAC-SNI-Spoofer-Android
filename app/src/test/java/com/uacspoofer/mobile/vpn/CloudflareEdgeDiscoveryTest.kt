package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.profiles.RuntimeProxyIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareEdgeDiscoveryTest {
    @Test
    fun cidrMembershipAndSamplingAreDeterministic() {
        val cidr = requireNotNull(IpCidr.parse("104.16.0.0/13"))
        assertTrue(cidr.contains(requireNotNull(IpAddress.parse("104.18.1.1"))))
        assertFalse(cidr.contains(requireNotNull(IpAddress.parse("1.1.1.1"))))
        val first = cidr.sample("network-profile", 4)
        val repeated = cidr.sample("network-profile", 4)
        val next = cidr.sample("network-profile", 5)
        assertEquals(first, repeated)
        assertNotEquals(first, next)
        assertTrue(cidr.contains(first))
    }

    @Test
    fun bundledSnapshotContainsBothAddressFamilies() {
        val ranges = bundledCloudflareRanges().ranges
        assertTrue(ranges.any(IpCidr::isIpv4))
        assertTrue(ranges.any(IpCidr::isIpv6))
    }

    @Test
    fun shortlistKeepsEightPrimariesAndTwoSubnetDiverseBackups() {
        val candidates = (1..12).map { index ->
            val ip = requireNotNull(IpAddress.parse("104.18.$index.10"))
            CloudflareEdgeCandidate(
                key = ip.key,
                address = ip.canonical,
                port = 443,
                ip = ip,
                sources = setOf(CloudflareEdgeSource.OFFICIAL_CIDR),
                reserved = false,
                preflight = CloudflareEdgePreflightResult(
                    tcpSucceeded = true,
                    tlsAttempted = true,
                    tlsSucceeded = true,
                    tcpLatencyMs = index.toLong(),
                    detail = "ok",
                ),
                score = 1_000 - index,
            )
        }
        val (primary, backups) = selectSubnetDiverseEdges(candidates)
        assertEquals(8, primary.size)
        assertEquals(2, backups.size)
        assertEquals(2, backups.map(CloudflareEdgeCandidate::subnetKey).distinct().size)
        assertTrue(backups.none { it.subnetKey == primary.first().subnetKey })
    }

    @Test
    fun websocketTlsProfileWithOfficialRangeEvidenceIsEligible() {
        val identity = RuntimeProxyIdentity(
            protocol = ProxyProtocol.VLESS,
            credential = "id",
            network = "ws",
            security = "tls",
            sni = "example.com",
            host = "example.com",
            path = "/",
            alpn = "http/1.1",
            fingerprint = "chrome",
            allowInsecure = false,
            flow = "",
            encryption = "none",
            alterId = 0,
            serviceName = "",
            authority = "",
        )
        val ip = requireNotNull(IpAddress.parse("104.18.1.1"))
        val candidate = CloudflareEdgeCandidate(
            key = ip.key,
            address = ip.canonical,
            port = 443,
            ip = ip,
            sources = setOf(CloudflareEdgeSource.DNS_SNI),
            reserved = false,
        )
        val decision = evaluateCloudflareSuitability(
            identity,
            443,
            listOf(candidate),
            bundledCloudflareRanges().ranges,
        )
        assertEquals(CloudflareSuitability.ELIGIBLE, decision.status)
    }

    @Test
    fun xhttpTlsProfileWithOfficialRangeEvidenceIsEligible() {
        val identity = websocketIdentity().copy(network = "xhttp", alpn = "h2")
        val ip = requireNotNull(IpAddress.parse("104.18.1.1"))
        val candidate = CloudflareEdgeCandidate(
            key = ip.key,
            address = ip.canonical,
            port = 443,
            ip = ip,
            sources = setOf(CloudflareEdgeSource.DNS_SNI),
            reserved = false,
        )
        val decision = evaluateCloudflareSuitability(
            identity,
            443,
            listOf(candidate),
            bundledCloudflareRanges().ranges,
        )
        assertEquals(CloudflareSuitability.ELIGIBLE, decision.status)
    }

    @Test
    fun xhttpOnCloudflarePortWithoutDnsEvidenceStillSamplesOfficialRanges() {
        val identity = websocketIdentity().copy(network = "xhttp", alpn = "h2,http/1.1")
        val decision = evaluateCloudflareSuitability(
            identity = identity,
            port = 2053,
            candidates = emptyList(),
            ranges = bundledCloudflareRanges().ranges,
        )
        assertEquals(CloudflareSuitability.UNKNOWN, decision.status)
        assertTrue(shouldSampleOfficialCloudflareRanges(decision))
    }

    @Test
    fun xhttpOnNonCloudflarePortDoesNotSampleOfficialRanges() {
        val identity = websocketIdentity().copy(network = "xhttp", alpn = "h2")
        val decision = evaluateCloudflareSuitability(
            identity = identity,
            port = 1234,
            candidates = emptyList(),
            ranges = bundledCloudflareRanges().ranges,
        )
        assertEquals(CloudflareSuitability.INELIGIBLE, decision.status)
        assertFalse(shouldSampleOfficialCloudflareRanges(decision))
    }

    @Test
    fun trustedBuiltInProfileIsEligibleWithoutDnsEvidence() {
        val identity = websocketIdentity()
        val untrusted = evaluateCloudflareSuitability(
            identity = identity,
            port = 443,
            candidates = emptyList(),
            ranges = bundledCloudflareRanges().ranges,
        )
        val trusted = evaluateCloudflareSuitability(
            identity = identity,
            port = 443,
            candidates = emptyList(),
            ranges = bundledCloudflareRanges().ranges,
            trustedProfile = true,
        )

        assertEquals(CloudflareSuitability.UNKNOWN, untrusted.status)
        assertEquals(CloudflareSuitability.ELIGIBLE, trusted.status)
    }

    @Test
    fun discoveryIdDependsOnSortedCandidatePoolNotSelectedOrder() {
        val suitability = CloudflareSuitabilityDecision(CloudflareSuitability.ELIGIBLE, "test")
        val candidates = listOf("104.18.1.1", "104.18.2.1", "104.18.3.1").map { address ->
            val ip = requireNotNull(IpAddress.parse(address))
            CloudflareEdgeCandidate(
                key = canonicalEndpointKey(address, 443),
                address = address,
                port = 443,
                ip = ip,
                sources = setOf(CloudflareEdgeSource.OFFICIAL_CIDR),
                reserved = false,
            )
        }
        val first = CloudflareEdgeDiscoveryResult(
            suitability = suitability,
            rangeEtag = "etag",
            primary = candidates.take(2),
            backups = candidates.drop(2),
            candidates = candidates,
        )
        val reordered = CloudflareEdgeDiscoveryResult(
            suitability = suitability,
            rangeEtag = "etag",
            primary = candidates.reversed().take(2),
            backups = candidates.take(1),
            candidates = candidates.reversed(),
        )
        val changedPool = first.copy(candidates = candidates.dropLast(1))

        assertEquals(first.discoveryId, reordered.discoveryId)
        assertNotEquals(first.discoveryId, changedPool.discoveryId)
    }

    private fun websocketIdentity() = RuntimeProxyIdentity(
        protocol = ProxyProtocol.VLESS,
        credential = "id",
        network = "ws",
        security = "tls",
        sni = "example.com",
        host = "example.com",
        path = "/",
        alpn = "http/1.1",
        fingerprint = "chrome",
        allowInsecure = false,
        flow = "",
        encryption = "none",
        alterId = 0,
        serviceName = "",
        authority = "",
    )
}
