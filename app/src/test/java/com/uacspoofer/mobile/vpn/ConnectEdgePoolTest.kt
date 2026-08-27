package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.mci.MciEdge
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectEdgePoolTest {
    private val settings = AdvancedSettingsData.DEFAULT

    @Test
    fun currentOperatorPoolIsPreferredOverPreviousOperator() {
        val current = listOf(edge("1.1.1.1"), edge("1.0.0.1"))
        val previous = listOf(edge("8.8.8.8"))

        val selection = resolveConnectPool(
            thisPool = current,
            lastPool = previous,
            lastPoolKey = "mci-old",
            thisKey = "irancell-new",
        )

        assertEquals(ConnectPoolSelection.SOURCE_NETWORK, selection.source)
        assertEquals(current, selection.edges)
    }

    @Test
    fun previousOperatorPoolIsUsedWhenCurrentOperatorHasNone() {
        val previous = listOf(edge("104.18.9.83"), edge("172.66.0.1"))

        val selection = resolveConnectPool(
            thisPool = emptyList(),
            lastPool = previous,
            lastPoolKey = "mci",
            thisKey = "irancell",
        )

        assertEquals(ConnectPoolSelection.SOURCE_PREVIOUS, selection.source)
        assertEquals(previous, selection.edges)
    }

    @Test
    fun hardcodedDefaultIsUsedWhenNoOperatorPoolExists() {
        val selection = resolveConnectPool(
            thisPool = null,
            lastPool = null,
            lastPoolKey = null,
            thisKey = "mci",
        )

        assertEquals(ConnectPoolSelection.SOURCE_DEFAULT, selection.source)
        assertTrue(selection.edges.isEmpty())
    }

    @Test
    fun previousPoolIsIgnoredWhenItBelongsToTheSameOperator() {
        val selection = resolveConnectPool(
            thisPool = emptyList(),
            lastPool = listOf(edge("1.1.1.1")),
            lastPoolKey = "mci",
            thisKey = "mci",
        )

        assertEquals(ConnectPoolSelection.SOURCE_DEFAULT, selection.source)
        assertTrue(selection.edges.isEmpty())
    }

    @Test
    fun applyConnectEdgePoolKeepsDirectEndpointAndReplacesCloudflareSlots() {
        val direct = candidate(
            id = AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID,
            address = "origin.example",
            port = 2053,
        )
        val primary = candidate("uac-primary-google", "104.18.1.1", 443)
        val fallback = candidate("uac-fallback-quad9", "172.66.0.1", 443)
        val pool = listOf(
            edge("104.21.7.1", 443),
            edge("188.114.97.6", 443),
        )

        val applied = applyConnectEdgePool(listOf(direct, primary, fallback), pool)

        assertEquals(3, applied.size)
        assertEquals(AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID, applied[0].id)
        assertEquals("origin.example", applied[0].edge.address)
        assertEquals(2053, applied[0].edge.port)
        assertEquals("104.21.7.1", applied[1].edge.address)
        assertEquals(443, applied[1].edge.port)
        assertEquals("188.114.97.6", applied[2].edge.address)
        assertTrue(applied[1].id.startsWith("cfpool-"))
        assertTrue(applied[1].id.contains("uac-primary-google"))
        assertEquals("UAC SNI primary + Google DNS • 104.21.7.1:443", applied[1].label)
        assertEquals(primary.settings.dnsResolverUrl, applied[1].settings.dnsResolverUrl)
        assertEquals(primary.runtimeOptions, applied[1].runtimeOptions)
    }

    @Test
    fun emptyPoolLeavesHardcodedCandidatesUnchanged() {
        val primary = candidate("uac-primary-google", "104.18.1.1", 443)

        assertEquals(listOf(primary), applyConnectEdgePool(listOf(primary), emptyList()))
    }

    @Test
    fun poolAddressesCycleAcrossCloudflareSlots() {
        val slots = listOf(
            candidate("a", "104.18.1.1", 443),
            candidate("b", "104.18.1.1", 443),
            candidate("c", "172.66.0.1", 443),
        )
        val pool = listOf(edge("1.1.1.1"), edge("1.0.0.1"))

        val applied = applyConnectEdgePool(slots, pool)

        assertEquals(listOf("1.1.1.1", "1.0.0.1", "1.1.1.1"), applied.map { it.edge.address })
    }

    @Test
    fun applyConnectEdgePoolKeepsLastGoodAndDirectEndpoints() {
        val lastGood = candidate(
            id = AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID,
            address = "104.21.7.1",
            port = 443,
        )
        val direct = candidate(
            id = AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID,
            address = "origin.example",
            port = 2053,
        )
        val primary = candidate("uac-primary-google", "104.18.1.1", 443)
        val pool = listOf(edge("1.1.1.1"), edge("1.0.0.1"))

        val applied = applyConnectEdgePool(listOf(lastGood, direct, primary), pool)

        assertEquals("104.21.7.1", applied[0].edge.address)
        assertEquals(AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID, applied[0].id)
        assertEquals("origin.example", applied[1].edge.address)
        assertEquals("1.1.1.1", applied[2].edge.address)
    }

    @Test
    fun lastGoodConnectSitsAfterSavedRoutesAndBeforeDirect() {
        val direct = candidate(
            id = AdaptiveCandidatePlanner.MCI_DIRECT_COMPAT_ID,
            address = "origin.example",
            port = 2053,
        )
        val fallback = candidate("raw-1", "104.18.1.1", 443)
        val champion = candidate("saved-champion", "8.8.8.8", 443).copy(learned = true)
        val lastGood = candidate(
            id = AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID,
            address = "104.21.7.1",
            port = 443,
        )

        val ordered = prioritizeAdaptiveCandidates(
            raw = listOf(direct, fallback),
            savedRoute = champion,
            savedBackupRoute = null,
            learnedId = null,
            maxAdaptiveCandidates = AdaptiveCandidatePlanner.MAX_CANDIDATES,
            connectChampion = lastGood,
        )

        assertEquals(
            listOf(champion.id, lastGood.id, direct.id, fallback.id),
            ordered.map(AdaptiveCandidate::id),
        )
    }

    @Test
    fun lastGoodConnectDeduplicatesTheSameEndpointFromThePool() {
        val lastGood = candidate(
            id = AdaptiveCandidatePlanner.CONNECT_LAST_GOOD_ID,
            address = "104.21.7.1",
            port = 443,
        )
        val pooled = candidate("cfpool-104-21-7-1-443-raw", "104.21.7.1", 443)

        val ordered = prioritizeAdaptiveCandidates(
            raw = listOf(pooled),
            savedRoute = null,
            savedBackupRoute = null,
            learnedId = null,
            maxAdaptiveCandidates = AdaptiveCandidatePlanner.MAX_CANDIDATES,
            connectChampion = lastGood,
        )

        assertEquals(listOf(lastGood.id), ordered.map(AdaptiveCandidate::id))
    }

    @Test
    fun loopbackEdgesAreNotPersistableAndChampionLeadsThePool() {
        assertTrue(!persistableConnectEdge(edge("127.0.0.1")))
        assertTrue(persistableConnectEdge(edge("104.21.7.1")))
        val pool = poolWithChampionFirst(
            listOf(edge("1.1.1.1"), edge("104.21.7.1")),
            edge("104.21.7.1"),
        )
        assertEquals(listOf("104.21.7.1", "1.1.1.1"), pool.map { it.address })
        assertEquals("op|builtin:mci2", connectPoolScopeKey("op", "builtin:mci2"))
    }

    @Test
    fun savedRoutesStayInFrontAndRescueCanDropThem() {
        val raw = (1..AdaptiveCandidatePlanner.MAX_CANDIDATES).map { index ->
            candidate("raw-$index", "104.18.1.1", 443)
        }
        val champion = candidate("saved-champion", "8.8.8.8", 443).copy(learned = true)
        val backup = candidate("saved-backup", "9.9.9.9", 443)

        val withSaved = prioritizeAdaptiveCandidates(
            raw = raw,
            savedRoute = champion,
            savedBackupRoute = backup,
            learnedId = null,
            maxAdaptiveCandidates = AdaptiveCandidatePlanner.MAX_CANDIDATES,
        )
        val withoutSaved = prioritizeAdaptiveCandidates(
            raw = raw,
            savedRoute = null,
            savedBackupRoute = null,
            learnedId = null,
            maxAdaptiveCandidates = AdaptiveCandidatePlanner.MAX_CANDIDATES,
        )

        assertEquals(listOf(champion.id, backup.id), withSaved.take(2).map(AdaptiveCandidate::id))
        assertEquals(AdaptiveCandidatePlanner.MAX_CANDIDATES, withoutSaved.size)
        assertTrue(withoutSaved.none { it.id == champion.id || it.id == backup.id })
    }

    private fun edge(address: String, port: Int = 443): MciEdge =
        MciEdge(address, port, "connect-pool", 2)

    private fun candidate(id: String, address: String, port: Int): AdaptiveCandidate = AdaptiveCandidate(
        id = id,
        label = if (id == "uac-primary-google") "UAC SNI primary + Google DNS" else id,
        edge = MciEdge(address, port, id, 2),
        settings = settings,
        runtimeOptions = MciXrayRuntimeOptions.DEFAULT,
    )
}
