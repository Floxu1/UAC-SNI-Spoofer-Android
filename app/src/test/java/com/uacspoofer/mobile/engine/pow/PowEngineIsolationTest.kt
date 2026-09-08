package com.uacspoofer.mobile.engine.pow

import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowEngineIsolationTest {
    @Test
    fun powNeverSharesFlagsWithXrayOrTor() {
        val pow = EngineMode.UAC_POW
        assertTrue(pow.isPow)
        assertFalse(pow.isXray)
        assertFalse(pow.isTor)
        assertFalse(EngineMode.XRAY_CF.isPow)
        assertFalse(EngineMode.TOR_WEBTUNNEL.isPow)
    }

    @Test
    fun homeToggleCyclesThreeEnginesWithoutSkippingPow() {
        assertEquals(EngineMode.TOR_WEBTUNNEL, EngineMode.XRAY_CF.next())
        assertEquals(EngineMode.UAC_POW, EngineMode.TOR_WEBTUNNEL.next())
        assertEquals(EngineMode.XRAY_CF, EngineMode.UAC_POW.next())
    }

    @Test
    fun powLookupDoesNotReuseXrayOrTorCacheKeys() {
        assertEquals(
            "${ExitIpInfoRepository.POW_LOOKUP_ID}:auto",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false, powEngine = true),
        )
        assertEquals(
            "${ExitIpInfoRepository.POW_LOOKUP_ID}:de",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false, "DE", powEngine = true),
        )
        assertEquals(
            "${ExitIpInfoRepository.TOR_LOOKUP_ID}:de",
            ExitIpInfoRepository.lookupId("builtin:mci", torEngine = true, "DE", powEngine = true),
        )
        assertEquals("builtin:mci", ExitIpInfoRepository.lookupId("builtin:mci", torEngine = false))
    }

    @Test
    fun outerAutoTriesWireguardThenMasqueThenWow() {
        val auto = PowCoreConfig.candidates(PowEngineSettings.DEFAULT)
        assertEquals(listOf("wireguard", "masque", "gool"), auto)
        assertEquals(
            listOf("masque"),
            PowCoreConfig.candidates(PowEngineSettings(outerTransport = "masque")),
        )
        assertEquals("MASQUE", PowCoreConfig.outerLabel("masque"))
        assertEquals("WireGuard", PowCoreConfig.outerLabel("wireguard"))
        assertEquals("WoW", PowCoreConfig.outerLabel("gool"))
        assertFalse(PowEngineSettings.DEFAULT.h2Fragmentation)
    }

    @Test
    fun psiphonStrategiesPreferDirectOverMeekThenFallback() {
        assertEquals("F,A", PowPsiphonProtocols.signature())
        assertEquals(2, PowPsiphonProtocols.LADDER.size)
        assertEquals(PowPsiphonProtocols.FAST_DIRECT, PowPsiphonProtocols.LADDER[0].preferredProtocols)
        assertTrue(PowPsiphonProtocols.CHAINABLE.containsAll(PowPsiphonProtocols.FAST_DIRECT))
        assertTrue(PowPsiphonProtocols.CHAINABLE.containsAll(listOf("FRONTED-MEEK-OSSH", "TLS-OSSH", "OSSH")))
        assertFalse(PowPsiphonProtocols.FAST_DIRECT.any { it.contains("MEEK") })
        assertFalse(PowPsiphonProtocols.CHAINABLE.any { it.contains("INPROXY", ignoreCase = true) })
        assertFalse(PowPsiphonProtocols.FAST_DIRECT.any { it.contains("QUIC") })
    }

    @Test
    fun powInnerHopUsesDedicatedProcessAndLibraries() {
        assertTrue(GoJniLoader.isPowProcessName("com.uacspoofer.mobile:uacpow"))
        assertFalse(GoJniLoader.isPowProcessName("com.uacspoofer.mobile"))
        assertFalse(GoJniLoader.isPowProcessName("com.uacspoofer.mobile:tor"))
        assertEquals("gopsi", GoJniLoader.POW_LIBRARY)
        assertEquals("gojni", GoJniLoader.XRAY_LIBRARY)
        assertEquals(
            "com.uacspoofer.mobile.engine.pow.PowPsiphonService",
            PowPsiphonIpc.SERVICE_CLASS,
        )
        assertEquals("server_entries.txt", PowPsiphonIpc.ASSET_SERVER_ENTRIES)
        assertEquals("config", PowPsiphonIpc.KEY_CONFIG)
    }
}

class PowTunRelayConfigTest {
    @Test
    fun chainMtuCapsAt1280AndFloorsAt1200() {
        assertEquals(1_280, PowTunRelayConfig.mtuForChain(1_500))
        assertEquals(1_280, PowTunRelayConfig.mtuForChain(1_280))
        assertEquals(1_200, PowTunRelayConfig.mtuForChain(1_200))
        assertEquals(1_200, PowTunRelayConfig.mtuForChain(1_000))
        assertEquals(1_200, PowTunRelayConfig.mtuForChain(1_280, networkMtu = 1_200))
        assertEquals(1_280, PowTunRelayConfig.mtuForChain(1_280, networkMtu = 1_500))
    }

    @Test
    fun yamlRoutesThroughLocalSocksWithMapDnsAndCappedMtu() {
        val yaml = PowTunRelayConfig.yaml(
            mtu = 1_500,
            socksPort = PowCoreConfig.SOCKS_PORT,
            tunIpv4 = "10.0.0.1",
            mapDns = "10.0.0.2",
        )
        assertTrue(yaml.contains("mtu: 1280"))
        assertTrue(yaml.contains("tcp-buffer-size: 524224"))
        assertTrue(yaml.contains("task-stack-size: 557056"))
        assertTrue(yaml.contains("connect-timeout: 15000"))
        assertTrue(yaml.contains("port: ${PowCoreConfig.SOCKS_PORT}"))
        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue(yaml.contains("udp: 'tcp'"))
        assertFalse(yaml.contains("udp: 'udp'"))
        assertEquals(1_818, PowCoreConfig.TUN_SOCKS_PORT)
        assertEquals(1_819, PowCoreConfig.SOCKS_PORT)
        assertTrue(PowCoreConfig.TUN_SOCKS_PORT != PowCoreConfig.SOCKS_PORT)
        assertTrue(yaml.contains("mapdns:"))
        assertTrue(yaml.contains("address: '10.0.0.2'"))
        assertFalse(yaml.contains("udpgw", ignoreCase = true))
        assertFalse(yaml.contains("vless", ignoreCase = true))
        assertFalse(yaml.contains("xray", ignoreCase = true))
        assertFalse(yaml.contains("ipv6:"))
    }
}

class PowRegionsTest {
    @Test
    fun automaticHasNoEgressFilter() {
        assertEquals("", PowRegions.normalize(""))
        assertEquals("ir", PowRegions.normalize("ir"))
        assertNull(PowRegions.egressRegion(""))
        assertEquals("DE", PowRegions.egressRegion("de"))
        assertEquals("de", PowRegions.normalize("DE"))
        assertTrue(PowRegions.RECOMMENDED.containsAll(listOf("de", "nl", "us")))
        assertFalse(PowRegions.RECOMMENDED.contains("ir"))
    }
}

class PowQualityPolicyTest {
    @Test
    fun pingMustDoubleAndClearAFloorBeforeRetune() {
        assertFalse(PowQualityPolicy.isDegraded(80, 160))
        assertTrue(PowQualityPolicy.isDegraded(80, 400))
        assertTrue(PowQualityPolicy.isDegraded(500, 1_000))
        assertTrue(PowQualityPolicy.isDegraded(200, -1))
        assertFalse(PowQualityPolicy.isDegraded(0, 900))
        assertEquals(120L, PowQualityPolicy.median(listOf(80, 120, 900)))
        assertEquals(0L, PowQualityPolicy.median(listOf(-1, 0)))
    }

    @Test
    fun staleCacheFileIsDeletedAndFreshCacheIsKept() {
        val stale = java.io.File.createTempFile("pow-cache", ".json")
        stale.writeText("{}")
        val staleNow = stale.lastModified() + PowQualityPolicy.CACHE_TTL_MS + 5_000L
        assertTrue(PowQualityPolicy.expireFileIfStale(stale, staleNow))
        assertFalse(stale.exists())

        val fresh = java.io.File.createTempFile("pow-cache", ".json")
        fresh.writeText("{}")
        assertFalse(PowQualityPolicy.expireFileIfStale(fresh, fresh.lastModified()))
        assertTrue(fresh.exists())
        fresh.delete()
    }

    @Test
    fun retuneKeepsTheCurrentOuterFirstThenTheRestOfTheLadder() {
        assertEquals(
            listOf("masque", "wireguard", "gool"),
            PowQualityPolicy.outerOrder(listOf("wireguard", "masque", "gool"), "masque"),
        )
        assertEquals(
            listOf("wireguard", "masque", "gool"),
            PowQualityPolicy.outerOrder(listOf("wireguard", "masque", "gool"), ""),
        )
    }

    @Test
    fun liveRefreshUsesFreshDiscoveryWithoutXrayOrRescue() {
        assertEquals("cache", PowCoreConfig.DISCOVERY_CACHE)
        assertEquals("fresh", PowCoreConfig.DISCOVERY_FRESH)
        assertEquals(12L * 60_000L, PowQualityPolicy.CACHE_TTL_MS)
        assertTrue(PowQualityPolicy.FAIL_FAST_MS < 3_000)
        assertTrue(PowQualityPolicy.SETTLE_MS < 60_000L)
        assertFalse(PowCoreConfig.DISCOVERY_FRESH.contains("xray", ignoreCase = true))
        assertFalse(PowCoreConfig.DISCOVERY_FRESH.contains("rescue", ignoreCase = true))
    }
}
