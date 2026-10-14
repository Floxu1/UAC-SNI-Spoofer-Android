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
        assertEquals(listOf("masque", "gool", "wireguard"), auto)
        assertEquals(
            listOf("masque"),
            PowCoreConfig.candidates(PowEngineSettings(outerTransport = "masque")),
        )
        assertEquals("MASQUE", PowCoreConfig.outerLabel("masque"))
        assertEquals("WireGuard", PowCoreConfig.outerLabel("wireguard"))
        assertEquals("WoW", PowCoreConfig.outerLabel("gool"))
        assertTrue(PowEngineSettings.DEFAULT.h2Fragmentation)
        assertTrue(PowEngineSettings.DEFAULT.optimizedMode)
        assertFalse(PowEngineSettings(optimizedMode = false).validated().optimizedMode)
        assertEquals(PowCoreConfig.SCAN_BALANCED, PowEngineSettings.DEFAULT.scanMode)
        assertEquals(PowCoreConfig.SCAN_TURBO, PowCoreConfig.normalizeScanMode("fast"))
        assertEquals(PowCoreConfig.SCAN_TURBO, PowCoreConfig.normalizeScanMode("TURBO"))
        assertEquals(PowCoreConfig.SCAN_THOROUGH, PowCoreConfig.normalizeScanMode("deep"))
        assertEquals(PowCoreConfig.SCAN_THOROUGH, PowCoreConfig.normalizeScanMode("thorough"))
        assertEquals(PowCoreConfig.SCAN_BALANCED, PowCoreConfig.normalizeScanMode(null))
        assertEquals(PowCoreConfig.SCAN_BALANCED, PowCoreConfig.normalizeScanMode("nope"))
        assertEquals(PowCoreConfig.SCAN_TURBO, PowEngineSettings(scanMode = "FAST").validated().scanMode)
        assertEquals("turbo", PowCoreConfig.SCAN_TURBO)
        assertEquals("balanced", PowCoreConfig.SCAN_BALANCED)
        assertEquals("thorough", PowCoreConfig.SCAN_THOROUGH)
        assertTrue(PowCoreConfig.outerBudgetMs("wireguard", scanMode = "balanced") >= 36_000L)
        assertTrue(PowCoreConfig.outerBudgetMs("masque", scanMode = "thorough") > PowCoreConfig.outerBudgetMs("masque", scanMode = "balanced"))
        assertTrue(PowCoreConfig.outerBudgetMs("masque", scanMode = "turbo") < PowCoreConfig.outerBudgetMs("masque", scanMode = "balanced"))
        assertTrue(PowCoreConfig.outerBudgetMs("wireguard", retune = true) < PowCoreConfig.outerBudgetMs("wireguard", scanMode = "turbo"))
        val irancell = PowCoreConfig.outerPlan(PowEngineSettings(linkRecipe = PowCoreConfig.RECIPE_AUTO), mci = false)
        assertEquals(listOf("masque", "gool", "wireguard"), irancell.protocols)
        assertEquals("h3", irancell.masqueTransport)
        assertEquals("v4", irancell.ipFor("wireguard"))
        val hamrah = PowCoreConfig.outerPlan(PowEngineSettings(linkRecipe = PowCoreConfig.RECIPE_AUTO), mci = true)
        assertEquals(listOf("masque"), hamrah.protocols)
        assertEquals("both", hamrah.ipFor("masque"))
        assertEquals("both", hamrah.ipFor("wireguard"))
        assertEquals("h2", hamrah.masqueTransport)
        assertTrue(hamrah.fixedOrder)
        val h2 = PowCoreConfig.outerPlan(PowEngineSettings(linkRecipe = PowCoreConfig.RECIPE_MASQUE_H2), mci = false)
        assertEquals(listOf("masque"), h2.protocols)
        assertEquals("both", h2.ipFor("masque"))
        assertFalse(h2.fragment)
        assertTrue(
            PowCoreConfig.outerPlan(
                PowEngineSettings(linkRecipe = PowCoreConfig.RECIPE_MASQUE_H2_SPLIT),
                mci = false,
            ).fragment,
        )
        assertEquals(PowCoreConfig.RECIPE_AUTO, PowEngineSettings(linkRecipe = "nope").validated().linkRecipe)
        assertEquals(PowCoreConfig.FIREWALL_AUTO, PowEngineSettings(firewallProfile = "mtn").validated().firewallProfile)
        assertTrue(PowEngineSettings.DEFAULT.chainPsiphon)
        assertEquals(PowCoreConfig.KEY_AUTO, PowEngineSettings.DEFAULT.keyFetch)
        val parsed = PowIdentityStatus.parseTomlScalars(
            "device_id = \"abc\"\ncert_issued_at = 10\nwg_private_key = \"AAAA\"\n",
        )
        assertEquals("abc", parsed["device_id"])
        assertEquals("10", parsed["cert_issued_at"])
        assertEquals(36_000L, PowCoreConfig.outerBudgetMs("wireguard"))
        assertEquals(120_000L, PowCoreConfig.outerBudgetMs("masque"))
        assertEquals(55_000L, PowCoreConfig.outerBudgetMs("wireguard", scanMode = "thorough"))
        assertEquals(180_000L, PowCoreConfig.outerBudgetMs("masque", scanMode = "thorough"))
    }

    @Test
    fun psiphonStrategiesPreferDirectOverMeekThenFallback() {
        assertEquals("A,F", PowPsiphonProtocols.signature())
        assertEquals(2, PowPsiphonProtocols.LADDER.size)
        assertEquals(PowPsiphonProtocols.FRONTED, PowPsiphonProtocols.LADDER[0].preferredProtocols)
        assertTrue(PowPsiphonProtocols.CHAINABLE.containsAll(PowPsiphonProtocols.FAST_DIRECT))
        assertTrue(PowPsiphonProtocols.CHAINABLE.containsAll(listOf("FRONTED-MEEK-OSSH", "TLS-OSSH", "OSSH")))
        assertFalse(PowPsiphonProtocols.FAST_DIRECT.any { it.contains("MEEK") })
        assertFalse(PowPsiphonProtocols.CHAINABLE.any { it.contains("INPROXY", ignoreCase = true) })
        assertFalse(PowPsiphonProtocols.FAST_DIRECT.any { it.contains("QUIC") })
        assertEquals(
            listOf("208.67.222.222:5353", "9.9.9.9:9953", "208.67.220.220:5353"),
            PowPsiphonProtocols.ALTERNATE_DNS,
        )
    }

    @Test
    fun chainedLadderTriesLightProtocolsBeforeFrontedMeek() {
        val chained = PowPsiphonProtocols.ladder(direct = false)
        assertEquals(listOf("F", "A"), chained.map { it.name })
        assertEquals(PowPsiphonProtocols.FAST_DIRECT, chained[0].preferredProtocols)
        assertEquals(PowPsiphonProtocols.CHAINED_LIGHT_TIMEOUT_SECONDS, chained[0].timeoutSeconds)
        assertTrue(chained[0].timeoutSeconds < PowPsiphonProtocols.LADDER[1].timeoutSeconds)
        assertEquals("F,A", PowPsiphonProtocols.signature(direct = false))
        // Direct Psiphon keeps the original fronted-first order.
        assertEquals(PowPsiphonProtocols.LADDER, PowPsiphonProtocols.ladder(direct = true))
        assertEquals("A,F", PowPsiphonProtocols.signature(direct = true))
        assertEquals(listOf("IR"), PowPsiphonProtocols.SPLIT_TUNNEL_REGIONS)
        assertTrue(PowPsiphonProtocols.SERVER_AFFINITY_GRACE_MS in 1_000..2_000)
    }

    @Test
    fun startStrategyPrefersLowerRttThenRememberedWinner() {
        val ladder = PowPsiphonProtocols.ladder(direct = false)
        assertEquals(0, PowPsiphonProtocols.startIndex(ladder, null, emptyMap()))
        assertEquals(1, PowPsiphonProtocols.startIndex(ladder, 1, emptyMap()))
        assertEquals(1, PowPsiphonProtocols.startIndex(ladder, 1, mapOf("F" to 300L)))
        assertEquals(0, PowPsiphonProtocols.startIndex(ladder, 1, mapOf("F" to 300L, "A" to 900L)))
        assertEquals(1, PowPsiphonProtocols.startIndex(ladder, 0, mapOf("F" to 900L, "A" to 300L)))
        assertEquals(0, PowPsiphonProtocols.startIndex(ladder, 7, emptyMap()))
    }

    @Test
    fun automaticExitLeansOnNearbyCountries() {
        assertEquals("NL", PowRegions.autoBiasRegion("nl", emptyList()))
        assertEquals("DE", PowRegions.autoBiasRegion("US", emptyList()))
        assertEquals("DE", PowRegions.autoBiasRegion(null, emptyList()))
        assertEquals("NL", PowRegions.autoBiasRegion(null, listOf("US", "NL", "JP")))
        assertNull(PowRegions.autoBiasRegion(null, listOf("US", "JP")))
        assertNull(PowRegions.autoBiasRegion("DE", listOf("US")))
        assertNull(PowRegions.autoBiasRegion(null, emptyList(), rememberedOnly = true))
        assertEquals("PL", PowRegions.autoBiasRegion("PL", emptyList(), rememberedOnly = true))
        assertFalse(PowRegions.NEARBY.contains("US"))
        assertFalse(PowRegions.NEARBY.contains("IR"))
    }

    @Test
    fun onlyIranianNamesSkipPsiphonInTheRelay() {
        assertTrue(PowSocksConnectOnly.isDomesticHost("shaparak.ir"))
        assertTrue(PowSocksConnectOnly.isDomesticHost("www.bmi.ir."))
        assertTrue(PowSocksConnectOnly.isDomesticHost("SANA.AC.IR"))
        assertFalse(PowSocksConnectOnly.isDomesticHost("ir"))
        assertFalse(PowSocksConnectOnly.isDomesticHost(".ir"))
        assertFalse(PowSocksConnectOnly.isDomesticHost("fair"))
        assertFalse(PowSocksConnectOnly.isDomesticHost("google.com"))
        assertFalse(PowSocksConnectOnly.isDomesticHost("ir.example.com"))
    }

    @Test
    fun powInnerHopUsesDedicatedProcessAndLibraries() {
        assertTrue(GoJniLoader.isPowProcessName("com.uacspoofer.mobile:uacpow"))
        assertTrue(GoJniLoader.isPowProcessName("com.uacspoofer.mobile:uacpow2"))
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
        assertFalse(com.uacspoofer.mobile.vpn.LanSharePolicy.PORT == PowCoreConfig.SOCKS_PORT)
        assertFalse(com.uacspoofer.mobile.vpn.LanSharePolicy.PORT == PowCoreConfig.TUN_SOCKS_PORT)
    }
}

class PowPsiphonTunnelPoolTest {
    @Test
    fun dualTunnelIsOffByDefault() {
        assertFalse(PowEngineSettings().psiphonTunnelPool)
        assertFalse(PowEngineSettings.DEFAULT.validated().psiphonTunnelPool)
        assertTrue(PowEngineSettings(psiphonTunnelPool = true).validated().psiphonTunnelPool)
    }

    @Test
    fun quicTuningIsOffByDefault() {
        assertFalse(PowEngineSettings().quicTuned)
        assertFalse(PowEngineSettings.DEFAULT.validated().quicTuned)
        assertTrue(PowEngineSettings(quicTuned = true).validated().quicTuned)
    }

    @Test
    fun secondTunnelHasItsOwnProcessAndPort() {
        assertEquals(
            "com.uacspoofer.mobile.engine.pow.PowPsiphonSidecarService",
            PowPsiphonIpc.SIDECAR_SERVICE_CLASS,
        )
        val taken = setOf(
            PowCoreConfig.SOCKS_PORT,
            PowCoreConfig.TUN_SOCKS_PORT,
            PowCoreConfig.CHAIN_SOCKS_PORT,
            PowGhostHandover.SHADOW_CHAIN_PORT,
            PowGhostHandover.SHADOW_SOCKS_PORT,
        )
        assertFalse(PowCoreConfig.SIDECAR_SOCKS_PORT in taken)
    }
}

class PowStickyRouteTest {
    @org.junit.Before
    fun clean() = PowStickyRoute.reset()

    @Test
    fun serviceDomainsShareOneGroup() {
        val meta = listOf(
            "www.instagram.com", "scontent-fra5-1.cdninstagram.com", "video.fbcdn.net",
            "graph.facebook.com", "mmg.whatsapp.net", "i.instagram.com.",
        )
        assertTrue(meta.all { PowStickyRoute.groupOf(it) == "meta" })
        val google = listOf(
            "www.youtube.com", "rr3---sn-abc.googlevideo.com", "i.ytimg.com", "yt3.ggpht.com",
            "youtubei.googleapis.com", "www.google.com", "www.google.de", "www.google.co.uk",
        )
        assertTrue(google.all { PowStickyRoute.groupOf(it) == "google" })
        assertEquals("example.com", PowStickyRoute.groupOf("cdn.static.example.com"))
        assertEquals("bbc.co.uk", PowStickyRoute.groupOf("news.bbc.co.uk"))
        assertEquals("ip:157.240.1.35", PowStickyRoute.groupOf("157.240.1.35"))
        assertEquals("ip:2a03:2880::1", PowStickyRoute.groupOf("[2a03:2880::1]"))
    }

    @Test
    fun heavyServicesStartOnDifferentTunnels() {
        assertEquals(PowStickyRoute.TUNNEL_2, PowStickyRoute.preferredFor("meta"))
        assertEquals(PowStickyRoute.TUNNEL_1, PowStickyRoute.preferredFor("google"))
        val hashed = (1..200).map { PowStickyRoute.preferredFor("site$it.com") }
        assertTrue(hashed.count { it == PowStickyRoute.TUNNEL_2 } in 60..140)
        assertEquals(PowStickyRoute.preferredFor("site7.com"), PowStickyRoute.preferredFor("site7.com"))
    }

    @Test
    fun nothingIsPinnedUntilTunnelTwoIsUp() {
        val early = PowStickyRoute.choose("video.fbcdn.net", tunnel2Up = false)
        assertEquals(PowStickyRoute.TUNNEL_1, early.tunnel)
        assertFalse(PowStickyRoute.active)
        assertEquals(0, PowStickyRoute.pinnedCount())
    }

    @Test
    fun aServiceKeepsItsTunnelAndMovesAtMostOnce() {
        val first = PowStickyRoute.choose("www.instagram.com", tunnel2Up = true)
        assertEquals(PowStickyRoute.TUNNEL_2, first.tunnel)
        assertTrue(first.fresh)
        val cdn = PowStickyRoute.choose("scontent.cdninstagram.com", tunnel2Up = true)
        assertEquals(PowStickyRoute.TUNNEL_2, cdn.tunnel)
        assertFalse(cdn.fresh)
        val down = PowStickyRoute.choose("video.fbcdn.net", tunnel2Up = false)
        assertEquals(PowStickyRoute.TUNNEL_1, down.tunnel)
        assertTrue(down.moved)
        val back = PowStickyRoute.choose("www.instagram.com", tunnel2Up = true)
        assertEquals(PowStickyRoute.TUNNEL_1, back.tunnel)
        assertFalse(back.moved)
        PowStickyRoute.reset()
        assertFalse(PowStickyRoute.active)
        assertEquals(0, PowStickyRoute.pinnedCount())
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
        assertTrue(yaml.contains("mtu: ${PowTunRelayConfig.MAX_CUSTOM_MTU}"))
        assertEquals(1_280, PowTunRelayConfig.mtuForChain(1_500))
        assertEquals(1_350, PowTunRelayConfig.mtuForChain(1_350, 1_500, PowTunRelayConfig.MAX_CUSTOM_MTU))
        assertEquals(1_300, PowTunRelayConfig.mtuForChain(1_400, 1_300, PowTunRelayConfig.MAX_CUSTOM_MTU))
        val adv = PowEngineSettings(h2Obfuscation = "LIGHT", tunMtu = 999).validated()
        assertEquals("light", adv.h2Obfuscation)
        assertEquals(PowEngineSettings.TUN_MTU_DEFAULT, adv.tunMtu)
        assertTrue(PowEngineSettings.DEFAULT.ghostHandover && PowEngineSettings.DEFAULT.h2SpeedPick)
        assertFalse(PowEngineSettings.DEFAULT.psiphonTunnelPool)
        val h2Plan = PowCoreConfig.outerPlan(PowEngineSettings(), mci = false)
        assertEquals("off", PowCoreConfig.obfuscationFor(PowEngineSettings(h2Obfuscation = "off"), "masque", h2Plan))
        assertEquals("balanced", PowCoreConfig.obfuscationFor(PowEngineSettings(h2Obfuscation = "off"), "wireguard", h2Plan))
        assertEquals("balanced", PowCoreConfig.obfuscationFor(PowEngineSettings(), "masque", h2Plan))
        assertTrue(yaml.contains("tcp-buffer-size: 262144"))
        assertTrue(yaml.contains("task-stack-size: 262144"))
        assertTrue(yaml.contains("connect-timeout: 2000"))
        assertTrue(yaml.contains("port: ${PowCoreConfig.SOCKS_PORT}"))
        assertTrue(yaml.contains("address: '127.0.0.1'"))
        assertTrue(yaml.contains("udp: 'tcp'"))
        assertFalse(yaml.contains("udp: 'udp'"))
        assertEquals(1_818, PowCoreConfig.TUN_SOCKS_PORT)
        assertEquals(1_819, PowCoreConfig.SOCKS_PORT)
        assertTrue(PowCoreConfig.TUN_SOCKS_PORT != PowCoreConfig.SOCKS_PORT)
        assertTrue(yaml.contains("mapdns:"))
        assertTrue(yaml.contains("cache-size: ${PowTunRelayConfig.MAPDNS_CACHE_SIZE}"))
        assertTrue(PowTunRelayConfig.MAPDNS_CACHE_SIZE >= 10_000)
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
    fun busyTunnelIsNotJudged() {
        assertTrue(PowQualityPolicy.isBusy(250L * 1024L, 2))
        assertTrue(PowQualityPolicy.isBusy(0L, PowQualityPolicy.BUSY_ACTIVE_STREAMS))
        assertFalse(PowQualityPolicy.isBusy(50L * 1024L, 3))
        assertEquals(204_800L, PowQualityPolicy.bytesPerSecond(409_600L, 2_000L))
        assertEquals(0L, PowQualityPolicy.bytesPerSecond(-5L, 2_000L))
        assertEquals(0L, PowQualityPolicy.bytesPerSecond(100L, 0L))
    }

    @Test
    fun passiveCheckUsesLowQuartileAndFourStrikes() {
        // One queued download (900, 1400 ms) must not drag the stat up.
        assertEquals(90L, PowQualityPolicy.p25(listOf(80, 90, 110, 120, 900, 1_400, 100, 95)))
        assertEquals(80L, PowQualityPolicy.p25(listOf(80, 120, 900)))
        assertEquals(0L, PowQualityPolicy.p25(listOf(0, -3)))
        assertEquals(900L, PowQualityPolicy.percentile(listOf(80, 120, 900), 100))
        assertEquals(4, PowQualityPolicy.PASSIVE_BAD_STREAK)
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

class PowDnsWarmTest {
    @Test
    fun hostNamesAreCleanedAndIpLiteralsSkipped() {
        assertEquals("www.google.com", PowDnsWarm.normalizeHost("WWW.Google.com."))
        assertNull(PowDnsWarm.normalizeHost("142.250.1.1"))
        assertNull(PowDnsWarm.normalizeHost("localhost"))
        assertNull(PowDnsWarm.normalizeHost("bad host.com"))
        assertNull(PowDnsWarm.normalizeHost("a..b.com"))
        assertNull(PowDnsWarm.normalizeHost(""))
    }

    @Test
    fun topListDecaysOldCountsAndKeepsTwenty() {
        val stored = mapOf("old.example" to 40, "steady.example" to 6)
        val session = mapOf("steady.example" to 5, "new.example" to 30)
        val merged = PowDnsWarm.mergeTop(stored, session)
        assertEquals(listOf("new.example", "old.example", "steady.example"), merged.keys.toList())
        assertEquals(20, merged["old.example"])
        assertEquals(8, merged["steady.example"])
        val many = (1..50).associate { "h$it.example" to it }
        assertEquals(PowDnsWarm.TOP_LEARNED, PowDnsWarm.mergeTop(emptyMap(), many).size)
        val encoded = PowDnsWarm.encode(merged)
        assertEquals(merged, PowDnsWarm.decode(encoded))
        assertTrue(PowDnsWarm.decode("junk,=3,a.com=x").isEmpty())
    }

    @Test
    fun warmPlanIsLightAndRespectsPowerSave() {
        val learned = (1..30).map { "site$it.example" }
        val optimized = PowDnsWarm.plan(learned, optimized = true, powerSave = false)
        assertEquals(12 + PowDnsWarm.POPULAR.size, optimized.size)
        assertTrue(optimized.containsAll(PowDnsWarm.POPULAR))
        assertEquals(20 + PowDnsWarm.POPULAR.size, PowDnsWarm.plan(learned, optimized = false, powerSave = false).size)
        val saver = PowDnsWarm.plan(learned, optimized = true, powerSave = true)
        assertEquals(5, saver.size)
        assertFalse(saver.any { it in PowDnsWarm.POPULAR })
        assertEquals(PowDnsWarm.POPULAR.size, PowDnsWarm.plan(listOf("google.com"), true, false).size)
        assertTrue(PowDnsWarm.POPULAR.containsAll(listOf("googlevideo.com", "web.telegram.org", "twimg.com")))
        assertFalse(PowDnsWarm.POPULAR.any(PowSocksConnectOnly::isDomesticHost))
        assertTrue(PowDnsWarm.REFRESH_OPTIMIZED_MS >= PowDnsWarm.REFRESH_FULL_MS)
    }
}
