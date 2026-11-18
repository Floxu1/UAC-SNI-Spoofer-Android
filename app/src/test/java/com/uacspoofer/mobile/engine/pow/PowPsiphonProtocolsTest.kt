package com.uacspoofer.mobile.engine.pow

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PowPsiphonProtocolsTest {
    private fun config(
        strategy: PowPsiphonStrategy,
        country: String? = null,
        direct: Boolean = false,
        regionBias: Boolean = false,
    ) = JSONObject(PowPsiphonProtocols.buildConfig(
        socksPort = 1819,
        dataDirectory = "/test/pow",
        preferredRegion = country,
        strategy = strategy,
        direct = direct,
        regionBias = regionBias,
    ))

    private fun protocols(config: JSONObject, key: String): List<String> {
        val values = config.getJSONArray(key)
        return (0 until values.length()).map(values::getString)
    }

    @Test
    fun manualGermanyKeepsTheSameLightProtocolLimitsAsAutomatic() {
        val light = PowPsiphonProtocols.ladder(direct = false).first()
        val automatic = config(light)
        val germany = config(light, country = "DE")
        assertEquals("DE", germany.getString("EgressRegion"))
        for (key in listOf("LimitTunnelProtocols", "InitialLimitTunnelProtocols")) {
            assertEquals(PowPsiphonProtocols.FAST_DIRECT, protocols(germany, key))
            assertEquals(protocols(automatic, key), protocols(germany, key))
        }
        assertEquals(16, germany.getInt("InitialLimitTunnelProtocolsCandidateCount"))
        assertEquals(25, germany.getInt("EstablishTunnelTimeoutSeconds"))
        assertEquals(20, automatic.getInt("EstablishTunnelTimeoutSeconds"))
        assertEquals(automatic.getString("UpstreamProxyURL"), germany.getString("UpstreamProxyURL"))
    }

    @Test
    fun manualGermanyAlsoKeepsTheFallbackStrategyLimits() {
        val fallback = PowPsiphonProtocols.ladder(direct = false)[1]
        val germany = config(fallback, country = "DE")
        assertEquals(PowPsiphonProtocols.FRONTED, protocols(germany, "LimitTunnelProtocols"))
        assertEquals(
            PowPsiphonProtocols.FRONTED.filter(PowPsiphonProtocols.CHAINABLE::contains),
            protocols(germany, "InitialLimitTunnelProtocols"),
        )
        assertEquals(24, germany.getInt("InitialLimitTunnelProtocolsCandidateCount"))
    }

    @Test
    fun nearbyCountryRetainsTheLightStrategyTimeout() {
        val light = PowPsiphonProtocols.ladder(direct = false).first()
        val nearby = config(light, country = "DE", regionBias = true)
        assertEquals("DE", nearby.getString("EgressRegion"))
        assertEquals(20, nearby.getInt("EstablishTunnelTimeoutSeconds"))
        assertEquals(PowPsiphonProtocols.FAST_DIRECT, protocols(nearby, "LimitTunnelProtocols"))
    }

    @Test
    fun directPsiphonKeepsItsExistingCarrierOverrides() {
        val light = PowPsiphonProtocols.LADDER.first { it.name == "F" }
        val germany = config(light, country = "DE", direct = true)
        assertEquals("DE", germany.getString("EgressRegion"))
        assertEquals(listOf("TLS-OSSH", "SHADOWSOCKS-OSSH"), protocols(germany, "LimitTunnelProtocols"))
        assertEquals(35, germany.getInt("EstablishTunnelTimeoutSeconds"))
        assertEquals(false, germany.has("UpstreamProxyURL"))
    }
}
