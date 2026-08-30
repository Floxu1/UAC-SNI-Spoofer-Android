package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneImportLanTest {
    @Test
    fun parseFormDecodesVlessLine() {
        val body = "configs=vless%3A%2F%2Fuuid%40host%3A443%23node"
        assertEquals("vless://uuid@host:443#node", PhoneImportLan.parseFormConfigs(body))
    }

    @Test
    fun parseFormRejectsEmpty() {
        assertEquals("", PhoneImportLan.parseFormConfigs("other=1"))
        assertEquals("", PhoneImportLan.parseFormConfigs(""))
    }

    @Test
    fun lanPickerPrefersWifiAndSkipsTun() {
        val ip = PhoneImportLan.pickLanIpv4(
            sequenceOf(
                "tun0" to "198.18.0.1",
                "rmnet0" to "10.11.12.13",
                "wlan0" to "192.168.1.40",
                "eth0" to "192.168.1.2",
            ),
        )
        assertEquals("192.168.1.40", ip)
    }

    @Test
    fun fakeDnsAndLoopbackAreNotLan() {
        assertFalse(PhoneImportLan.isReachableLanIpv4("198.18.0.1"))
        assertFalse(PhoneImportLan.isReachableLanIpv4("100.64.1.2"))
        assertFalse(PhoneImportLan.isReachableLanIpv4("127.0.0.1"))
        assertTrue(PhoneImportLan.isReachableLanIpv4("10.0.0.5"))
        assertTrue(PhoneImportLan.isReachableLanIpv4("172.16.1.8"))
    }

    @Test
    fun noUsableAddressReturnsNull() {
        assertNull(
            PhoneImportLan.pickLanIpv4(
                sequenceOf("tun0" to "198.18.0.1", "lo" to "127.0.0.1"),
            ),
        )
    }
}
