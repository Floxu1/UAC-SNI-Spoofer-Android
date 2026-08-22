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
    @Test
    fun builtInTwoIsLocalForwardProfile() {
        assertTrue(LocalForwardProfile.isLocalForward(ProxyProfile.UAC_SNI_BUILT_IN_2))
        assertFalse(LocalForwardProfile.isLocalForward(ProxyProfile.UAC_SNI_BUILT_IN))
    }

    @Test
    fun builtInTwoIdentityComesFromRawUriDespiteLoopbackEndpoint() {
        val identity = LocalForwardProfile.routingIdentity(
            ProxyProfile.UAC_SNI_BUILT_IN_2,
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
        assertNull(DirectCompatProfileParser.parse(ProxyProfile.UAC_SNI_BUILT_IN_2))
        val identity = DirectCompatProfileParser.parseIdentity(ProxyProfile.UAC_SNI_BUILT_IN_2)
        assertNotNull(identity)
        assertEquals("api-ir.behroozuac.dpdns.org", identity!!.sni)
    }

    @Test
    fun builtInTwoUsesRoutingPortForCloudflareSuitability() {
        val identity = LocalForwardProfile.routingIdentity(
            ProxyProfile.UAC_SNI_BUILT_IN_2,
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
