package com.uacspoofer.mobile.engine.direct

import com.uacspoofer.mobile.vpn.AdaptiveDnsResolvers
import com.uacspoofer.mobile.vpn.SocksDnsProbe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectXrayConfigTest {
    @Test fun selectedDnsIsFirstForForeignDomainsAndKeepsDomesticRules() {
        val resolver = AdaptiveDnsResolvers.QUAD9
        val config = JSONObject(DirectXrayConfig.build(
            DirectEngineSettings.DEFAULT, "127.0.0.1", 10808, 1280,
            nativeTun = true, wireGuardSocksPort = 18432, sessionDns = resolver,
        ))
        val dns = config.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        assertEquals(resolver.url, servers.getJSONObject(0).getString("address"))
        assertEquals("dns-domestic", servers.getJSONObject(1).getString("tag"))
        assertEquals(resolver.url, servers.getJSONObject(2).getString("address"))
        assertEquals("9.9.9.9", dns.getJSONObject("hosts").getJSONArray("dns.quad9.net").getString(0))
    }

    @Test fun dnsBenchmarkRequiresAValidPublicAnswer() {
        val query = SocksDnsProbe().buildDnsQuery(0, "m.youtube.com")
        val headerAndQuestion = query.copyOf().apply {
            this[2] = 0x81.toByte()
            this[3] = 0x80.toByte()
            this[7] = 1
        }
        val record = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
        val answer = headerAndQuestion + record + byteArrayOf(142.toByte(), 250.toByte(), 190.toByte(), 46)
        assertTrue(DirectDnsSelector.answersWithPublicIpv4(query, answer))
        assertFalse(DirectDnsSelector.answersWithPublicIpv4(query, answer.copyOf(answer.size - 1)))
        assertFalse(DirectDnsSelector.answersWithPublicIpv4(query, headerAndQuestion + record + byteArrayOf(10, 10, 34, 1)))
    }

    private fun build(s: DirectEngineSettings = DirectEngineSettings.DEFAULT, nativeTun: Boolean = true) =
        JSONObject(DirectXrayConfig.build(s, "127.0.0.1", 10808, 1280, nativeTun = nativeTun))

    @Test fun adaptiveConfigRoutesTlsThroughObservedNonTerminatingOutbounds() {
        for (nativeTun in listOf(true, false)) {
            val config = build(nativeTun = nativeTun)
            val outbounds = config.getJSONArray("outbounds")
            val candidates = (0 until outbounds.length()).map(outbounds::getJSONObject)
                .filter { it.getString("tag").startsWith(DirectXrayConfig.ADAPTIVE_PREFIX) }
            assertEquals(4, candidates.size)
            assertTrue(candidates.all { it.getString("protocol") == "freedom" })

            val observer = config.getJSONObject("burstObservatory").getJSONObject("pingConfig")
            assertEquals(DirectXrayConfig.PROBE_URL, observer.getString("destination"))
            val routing = config.getJSONObject("routing")
            val balancer = routing.getJSONArray("balancers").getJSONObject(0)
            assertEquals("leastLoad", balancer.getJSONObject("strategy").getString("type"))
            assertEquals(DirectXrayConfig.TAG_PLAIN, balancer.getString("fallbackTag"))
            val rules = routing.getJSONArray("rules")
            assertTrue((0 until rules.length()).map(rules::getJSONObject).any {
                it.optString("balancerTag") == balancer.getString("tag") && it.optString("port") == "443"
            })

            val inbounds = config.getJSONArray("inbounds")
            assertEquals(if (nativeTun) 2 else 1, inbounds.length())
            assertTrue(inbounds.getJSONObject(0).getJSONObject("sniffing").getBoolean("routeOnly"))
            assertFalse(config.toString().contains("tlsSettings"))
            assertFalse(config.toString().contains("certificate"))
        }
    }

    @Test fun localMasksUseLoopbackAndResolveDestinationsInsideXray() {
        val config = JSONObject(
            DirectXrayConfig.build(
                DirectEngineSettings.DEFAULT, "127.0.0.1", 10808, 1280,
                nativeTun = true, maskPorts = mapOf("oob" to 19005, "record-sni" to 19006),
            )
        )
        val outbounds = config.getJSONArray("outbounds")
        val byTag = (0 until outbounds.length()).map(outbounds::getJSONObject).associateBy { it.getString("tag") }
        for (mask in listOf("oob", "record-sni")) {
            val dialer = byTag.getValue("direct-hello-$mask")
                .getJSONObject("streamSettings").getJSONObject("sockopt")
            assertEquals("ForceIPv4", dialer.getString("domainStrategy"))
            val local = byTag.getValue(dialer.getString("dialerProxy"))
            assertEquals("socks", local.getString("protocol"))
            assertEquals("127.0.0.1", local.getJSONObject("settings")
                .getJSONArray("servers").getJSONObject(0).getString("address"))
        }
        val dns = config.getJSONObject("dns").getJSONArray("servers")
        val dnsTags = (0 until dns.length()).map { dns.getJSONObject(it).getString("tag") }
        assertTrue(dnsTags.indexOf("dns-cloudflare") < dnsTags.indexOf("dns-unfiltered"))

        val routing = config.getJSONObject("routing")
        val balancer = routing.getJSONArray("balancers").getJSONObject(0)
        assertEquals("direct-hello-record-sni", balancer.getString("fallbackTag"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertTrue((0 until rules.length()).map(rules::getJSONObject).any {
            it.optJSONArray("inboundTag")?.optString(0) == "dns-cloudflare" &&
                it.optString("outboundTag") == "direct-hello-record-sni"
        })
    }

    @Test fun manualModeRemainsAvailableWithLegacySettings() {
        val settings = DirectEngineSettings.DEFAULT.copy(
            adaptiveClientHello = false,
            fragmentEnabled = true,
            helloLengths = "0,104,1",
            unfilteredDns = "h2c://1.1.1.1/dns-query",
        )
        val config = build(settings)
        assertFalse(config.has("burstObservatory"))
        assertTrue(config.toString().contains("\"packets\":\"tlshello\""))
        assertEquals("1,104,1", settings.validated().helloLengths)
        assertEquals(DirectEngineSettings.DEFAULT_UNFILTERED_DNS, settings.validated().unfilteredDns)
    }

    @Test fun iranDirectUsesGeoTags() {
        val on = build(DirectEngineSettings.DEFAULT.copy(iranDirect = true)).toString()
        assertTrue(on.contains("geosite:category-ir"))
        assertTrue(on.contains("geoip:ir"))
        assertTrue(on.contains("geoip:private"))
        val off = build(DirectEngineSettings.DEFAULT.copy(iranDirect = false)).toString()
        assertFalse(off.contains("geosite:category-ir"))
        assertFalse(off.contains("geoip:ir"))
        assertTrue(off.contains("geoip:private"))
    }

    @Test fun wireGuardOnlyRoutesThroughAnEstablishedLocalChain() {
        val defaults = DirectEngineSettings.DEFAULT
        assertTrue(defaults.wireGuardOverDirect)
        assertEquals("", defaults.exitCountryCode)
        assertFalse(build(defaults).toString().contains(DirectXrayConfig.TAG_WIREGUARD))
        val config = JSONObject(DirectXrayConfig.build(
            defaults.copy(wireGuardOverDirect = true), "127.0.0.1", 10808, 1280,
            nativeTun = true, wireGuardSocksPort = 18432,
        ))
        val outbounds = config.getJSONArray("outbounds")
        val chain = (0 until outbounds.length()).map(outbounds::getJSONObject)
            .single { it.getString("tag") == DirectXrayConfig.TAG_WIREGUARD }
        val server = chain.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(18432, server.getInt("port"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val destinations = (0 until rules.length()).map { rules.getJSONObject(it).optString("outboundTag") }
        assertTrue(destinations.indexOf("dns-out") < destinations.indexOf(DirectXrayConfig.TAG_WIREGUARD))
        assertTrue(destinations.indexOf(DirectXrayConfig.TAG_WIREGUARD) < destinations.lastIndexOf(DirectXrayConfig.TAG_UDP))
    }

    @Test fun countryExitBlocksGeneralUdpBeforeTheTcpOnlyProxy() {
        val settings = DirectEngineSettings.DEFAULT.copy(wireGuardOverDirect = true, exitCountryCode = " DE ")
        assertEquals("de", settings.validated().exitCountryCode)
        val config = JSONObject(DirectXrayConfig.build(
            settings, "127.0.0.1", 10808, 1280, nativeTun = true,
            wireGuardSocksPort = 18432, countryExit = true,
        ))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val routing = (0 until rules.length()).map(rules::getJSONObject)
        val exit = routing.indexOfFirst {
            it.optString("network") == "tcp,udp" && it.optString("outboundTag") == DirectXrayConfig.TAG_WIREGUARD
        }
        assertEquals("udp", routing[exit - 1].getString("network"))
        assertEquals("block", routing[exit - 1].getString("outboundTag"))
    }

    @Test fun chainedRouteKeepsYoutubeAndItsDnsInsideTheTunnel() {
        val config = JSONObject(DirectXrayConfig.build(
            DirectEngineSettings.DEFAULT, "127.0.0.1", 10808, 1280,
            nativeTun = true, wireGuardSocksPort = 18432,
        ))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val routing = (0 until rules.length()).map(rules::getJSONObject)
        val video = routing.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("domain:googlevideo.com") == true }
        val domestic = routing.indexOfFirst { it.optJSONArray("ip")?.optString(0) == "geoip:ir" }
        assertTrue(video in 0 until domestic)
        assertEquals(DirectXrayConfig.TAG_WIREGUARD, routing[video].getString("outboundTag"))
        val publicDns = routing.single { it.optJSONArray("inboundTag")?.optString(0) == "dns-unfiltered" }
        assertEquals(DirectXrayConfig.TAG_WIREGUARD, publicDns.getString("outboundTag"))
        val servers = config.getJSONObject("dns").getJSONArray("servers")
        assertTrue(servers.getJSONObject(0).getJSONArray("domains").toString().contains("domain:googlevideo.com"))
        assertFalse(servers.toString().contains("https+local://"))
        assertFalse(config.has("burstObservatory"))
    }
}
