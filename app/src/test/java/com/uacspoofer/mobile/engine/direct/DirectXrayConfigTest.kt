package com.uacspoofer.mobile.engine.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DirectXrayConfigTest {
    private val ca = DirectCa.Material(
        certificatePemLines = listOf("-----BEGIN CERTIFICATE-----", "MIIB", "-----END CERTIFICATE-----"),
        keyPemLines = listOf("-----BEGIN PRIVATE KEY-----", "MIGH", "-----END PRIVATE KEY-----"),
        crtFile = File("."),
    )

    private fun mitm(s: DirectEngineSettings = DirectEngineSettings.DEFAULT) =
        DirectXrayConfig.build(s, "127.0.0.1", 10808, 1280, nativeTun = true, ca = ca, mitmH11Port = 18666, mitmH211Port = 18777)

    private fun plain(s: DirectEngineSettings) =
        DirectXrayConfig.build(s, "127.0.0.1", 10808, 1280, nativeTun = true, ca = null)

    @Test fun defaultsUseMitmGoogleAndEch() {
        val c = mitm()
        assertTrue(c.contains("\"protocol\":\"tunnel\""))
        assertTrue(c.contains("www.google.com"))
        assertTrue(c.contains("echConfigList"))
        assertTrue(c.contains(DirectEngineSettings.CLOUDFLARE_ECH_CONFIG_LIST))
        assertFalse(c.contains("\"protocol\":\"vless\""))
    }

    @Test fun fragmentOptionalAndOffByDefault() {
        assertFalse(DirectEngineSettings.DEFAULT.fragmentEnabled)
        val withFrag = plain(DirectEngineSettings.DEFAULT.copy(googleFronting = false, echEnabled = false, fragmentEnabled = true))
        assertTrue(withFrag.contains("\"packets\":\"tlshello\""))
    }

    @Test fun iranDirectUsesGeoTags() {
        val on = mitm(DirectEngineSettings.DEFAULT.copy(iranDirect = true))
        assertTrue(on.contains("geosite:category-ir"))
        assertTrue(on.contains("geoip:ir"))
        assertTrue(on.contains("geoip:private"))
        val off = mitm(DirectEngineSettings.DEFAULT.copy(iranDirect = false))
        assertFalse(off.contains("geosite:category-ir"))
        assertFalse(off.contains("geoip:ir"))
        assertTrue(off.contains("geoip:private"))
    }
}