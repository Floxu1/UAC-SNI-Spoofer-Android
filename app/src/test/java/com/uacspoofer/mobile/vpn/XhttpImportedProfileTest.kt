package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.mci.MciConfig
import com.uacspoofer.mobile.mci.MciXrayCore
import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.profiles.DirectCompatProfileParser
import com.uacspoofer.mobile.profiles.LocalForwardProfile
import com.uacspoofer.mobile.profiles.ProfileUriParser
import com.uacspoofer.mobile.profiles.ProxyProtocol
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XhttpImportedProfileTest {
    private val uri =
        "vless://11111111-2222-3333-4444-555555555555@104.18.1.1:2053" +
            "?encryption=none&security=tls&sni=cdn.example.com&fp=chrome" +
            "&alpn=h2%2Chttp%2F1.1&insecure=0&allowInsecure=0&type=xhttp" +
            "&host=cdn.example.com&path=%2Fcdn%2Fhls&mode=auto" +
            "#sample-xhttp-cf-2053"

    @Test
    fun importedPublicXhttpKeepsOriginalCloudflareEndpoint() {
        val profile = ProfileUriParser.parse(uri, id = "xhttp-cf")
        val direct = DirectCompatProfileParser.parse(profile)

        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("xhttp", profile.network)
        assertEquals("auto", profile.xhttpMode)
        assertEquals("/cdn/hls", profile.path)
        assertEquals("h2,http/1.1", profile.alpn)
        assertFalse(LocalForwardProfile.isLocalForward(profile))
        assertNotNull(direct)
        assertEquals("104.18.1.1", direct!!.address)
        assertEquals(2053, direct.port)
        assertEquals("xhttp", direct.identity.network)
        assertEquals("auto", direct.identity.xhttpMode)
        assertEquals("/cdn/hls", direct.identity.path)
        assertEquals("h2,http/1.1", direct.identity.alpn)
    }

    @Test
    fun directCompatConfigMatchesDesktopXhttpEndpoint() {
        val profile = ProfileUriParser.parse(uri, id = "xhttp-cf")
        val direct = requireNotNull(DirectCompatProfileParser.parse(profile))
        val config = MciXrayCore.buildConfig(
            edge = MciConfig.PRIMARY_EDGE.copy(address = direct.address, port = direct.port),
            settings = AdvancedSettingsData.DEFAULT.copy(muxEnabled = true),
            profile = profile,
            runtimeOptions = MciXrayRuntimeOptions(
                identityOverride = direct.identity,
                finalmaskEnabled = false,
                preserveEmptyAlpn = true,
                preserveTransportFields = true,
            ),
        )

        assertTrue(config.contains("\"protocol\":\"vless\""))
        assertTrue(config.contains("\"address\":\"104.18.1.1\""))
        assertTrue(config.contains("\"port\":2053"))
        assertTrue(config.contains("\"network\":\"xhttp\""))
        assertTrue(config.contains("\"xhttpSettings\""))
        assertTrue(config.contains("\"path\":\"/cdn/hls\""))
        assertTrue(config.contains("\"host\":\"cdn.example.com\""))
        assertTrue(config.contains("\"mode\":\"auto\""))
        assertTrue(config.contains("\"serverName\":\"cdn.example.com\""))
        assertTrue(config.contains("\"alpn\":[\"h2\",\"http/1.1\"]"))
        assertTrue(config.contains("\"mux\":{\"enabled\":false"))
        assertFalse(config.contains("\"finalmask\""))
        assertFalse(config.contains("\"httpupgradeSettings\""))
        assertFalse(config.contains("\"vnext\":[{\"address\":\"127.0.0.1\""))
    }
}
