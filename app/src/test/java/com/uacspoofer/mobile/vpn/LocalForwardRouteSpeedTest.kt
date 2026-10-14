package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalForwardRouteSpeedTest {
    /** The former built-in 2 shape: a link that only points at the on-device forwarder. */
    private val LOCAL_FORWARD_FIXTURE = ProxyProfile(
        id = "test:local-forward",
        name = "local forward",
        protocol = ProxyProtocol.TROJAN,
        credential = "humanity",
        serverHost = "127.0.0.1",
        serverPort = 40443,
        network = "ws",
        security = "tls",
        sni = "api-ir.behroozuac.dpdns.org",
        host = "api-ir.behroozuac.dpdns.org",
        path = "/assignment",
        alpn = "http/1.1",
        fingerprint = "chrome",
        rawUri = "trojan://humanity@127.0.0.1:40443?type=ws&security=tls&sni=api-ir.behroozuac.dpdns.org&host=api-ir.behroozuac.dpdns.org&path=%2Fassignment&alpn=http%2F1.1&fp=chrome#humanity-user",
        isBuiltIn = true,
    )
    @Test
    fun builtInTwoIsLocalForwardProfile() {
        assertTrue(LocalForwardProfile.isLocalForward(LOCAL_FORWARD_FIXTURE))
        assertFalse(LocalForwardProfile.isLocalForward(ProxyProfile.ADVANCED_SETTINGS_TEMPLATE))
    }

    @Test
    fun builtInTwoIdentityComesFromRawUriDespiteLoopbackEndpoint() {
        val identity = LocalForwardProfile.routingIdentity(
            LOCAL_FORWARD_FIXTURE,
            AdvancedSettingsData.DEFAULT,
        )
        assertEquals(ProxyProtocol.TROJAN, identity.protocol)
        assertEquals("api-ir.behroozuac.dpdns.org", identity.sni)
        assertEquals("api-ir.behroozuac.dpdns.org", identity.host)
        assertEquals("/assignment", identity.path)
        assertEquals("http/1.1", identity.alpn)
    }

    @Test
    fun directCompatParserRejectsLoopbackButKeepsIdentity() {
        assertNull(DirectCompatProfileParser.parse(LOCAL_FORWARD_FIXTURE))
        val identity = DirectCompatProfileParser.parseIdentity(LOCAL_FORWARD_FIXTURE)
        assertNotNull(identity)
        assertEquals("api-ir.behroozuac.dpdns.org", identity!!.sni)
    }

    @Test
    fun builtInTwoUsesRoutingPortForCloudflareSuitability() {
        val identity = LocalForwardProfile.routingIdentity(
            LOCAL_FORWARD_FIXTURE,
            AdvancedSettingsData.DEFAULT,
        )
        val decision = evaluateCloudflareSuitability(
            identity = identity,
            port = LocalForwardProfile.ROUTING_PORT,
            candidates = emptyList(),
            ranges = emptyList(),
            trustedProfile = false,
        )
        assertFalse(decision.status.name == "INELIGIBLE" && decision.reason.contains("port"))
    }
}
