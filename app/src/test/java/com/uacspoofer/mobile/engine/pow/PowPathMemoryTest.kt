package com.uacspoofer.mobile.engine.pow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowPathMemoryTest {
    @Test
    fun tomlPeerReadsLastWorkingGateway() {
        assertEquals(
            "162.159.199.1:443",
            PowPathMemory.parseTomlPeer(
                """
                peer = "162.159.199.1:443"
                profile = "balanced"
                """.trimIndent(),
            ),
        )
        assertNull(PowPathMemory.parseTomlPeer(""))
        assertNull(PowPathMemory.parseTomlPeer("profile = \"balanced\""))
    }

    @Test
    fun masqueCacheKeepsIpAndPort() {
        val gateways = PowPathMemory.parseMasqueGateways(
            """{"gateways":[{"ip":"162.159.198.2","port":443,"rtt_ms":80},{"ip":"162.159.199.1","port":1701}]}""",
        )
        assertEquals(listOf("162.159.198.2:443", "162.159.199.1:1701"), gateways)
        assertTrue(PowPathMemory.parseMasqueGateways("{}").isEmpty())
    }

    @Test
    fun networkLabelStaysHuman() {
        assertEquals("Wi-Fi · home", PowPathMemory.networkLabel("wifi:home"))
        assertEquals("Cell · mci", PowPathMemory.networkLabel("cell:mci"))
        assertEquals("Wi-Fi", PowPathMemory.networkLabel("wifi:unknown"))
    }

    @Test
    fun scoreboardShapeKeyIsNotParsedAsStrategyIndex() {
        assertEquals(
            "wifi:home",
            PowNetworkScoreboard.prefNetworkKey("pow_net_strat_shape_wifi:home"),
        )
        assertEquals(
            "wifi:home",
            PowNetworkScoreboard.prefNetworkKey("pow_net_strat_wifi:home"),
        )
        assertEquals(
            "wifi:home",
            PowNetworkScoreboard.prefNetworkKey("pow_net_outer_wifi:home"),
        )
        assertTrue(PowNetworkScoreboard.matchesCurrent("wifi:cafe", "wifi:unknown"))
        assertTrue(!PowNetworkScoreboard.matchesCurrent("cell:mci", "wifi:unknown"))
    }

    @Test
    fun learnedPathSummaryStaysOneLine() {
        val snap = PowLearnedPathSnapshot(
            currentNetworkKey = "wifi:home",
            globalOuter = "masque",
            networks = emptyList(),
            masqueLastPeer = "162.159.198.2:443",
            wowLastPeer = null,
            masqueCachedGateways = emptyList(),
        )
        assertEquals("MASQUE · 162.159.198.2:443", snap.summary())
        assertEquals("WireGuard", PowLearnedPathSnapshot.EMPTY.copy(globalOuter = "wireguard").summary())
    }
}
