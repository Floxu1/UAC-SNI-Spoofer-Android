package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.core.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class NetworkGuardPolicyTest {
    @Test
    fun killSwitchHoldsOnlyInTunnelMode() {
        assertTrue(NetworkGuardPolicy.holdOnFailure(killSwitch = true, proxyMode = false))
        assertFalse(NetworkGuardPolicy.holdOnFailure(killSwitch = true, proxyMode = true))
        assertFalse(NetworkGuardPolicy.holdOnFailure(killSwitch = false, proxyMode = false))
    }

    @Test
    fun swipeKeepsServiceWhenArmed() {
        assertTrue(NetworkGuardPolicy.stayAliveOnSwipe(killSwitch = true, autoConnect = false))
        assertTrue(NetworkGuardPolicy.stayAliveOnSwipe(killSwitch = false, autoConnect = true))
        assertFalse(NetworkGuardPolicy.stayAliveOnSwipe(killSwitch = false, autoConnect = false))
    }

    @Test
    fun autoStartNeedsConsentAndDoesNotFightTheUser() {
        assertTrue(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = true,
                state = ConnectionState.DISCONNECTED,
                userStopped = false,
                hasConsent = true,
            ),
        )
        assertTrue(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = true,
                state = ConnectionState.ERROR,
                userStopped = false,
                hasConsent = true,
            ),
        )
        assertFalse(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = true,
                state = ConnectionState.DISCONNECTED,
                userStopped = true,
                hasConsent = true,
            ),
        )
        assertFalse(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = true,
                state = ConnectionState.DISCONNECTED,
                userStopped = false,
                hasConsent = false,
            ),
        )
        assertFalse(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = false,
                state = ConnectionState.DISCONNECTED,
                userStopped = false,
                hasConsent = true,
            ),
        )
        assertFalse(
            NetworkGuardPolicy.canAutoStart(
                autoConnect = true,
                state = ConnectionState.CONNECTED,
                userStopped = false,
                hasConsent = true,
            ),
        )
    }

    @Test
    fun privacyFiltersDefaultOn() {
        val defaults = com.uacspoofer.mobile.settings.NetworkGuardSettings()
        assertTrue(defaults.dnsSinkhole)
        assertTrue(defaults.webRtcBlock)
        assertFalse(defaults.quicBlock)
        assertTrue(defaults.ipv6Block)
        assertFalse(defaults.killSwitch)
    }
}

class MonthlyTrafficLedgerTest {
    @Test
    fun accumulatesUntilMonthChanges() {
        val january = MonthlyTrafficSnapshot("2026-01", 100, 200)
        val sameMonth = MonthlyTrafficLedger.apply(january, "2026-01", 50, 25)
        assertEquals(150, sameMonth.uploadBytes)
        assertEquals(225, sameMonth.downloadBytes)
        assertEquals(375, sameMonth.totalBytes)

        val nextMonth = MonthlyTrafficLedger.apply(sameMonth, "2026-02", 10, 5)
        assertEquals("2026-02", nextMonth.monthKey)
        assertEquals(10, nextMonth.uploadBytes)
        assertEquals(5, nextMonth.downloadBytes)
    }

    @Test
    fun monthKeyUsesCalendarMonth() {
        val utc = TimeZone.getTimeZone("UTC")
        val calendar = java.util.Calendar.getInstance(utc)
        calendar.set(2026, java.util.Calendar.MAY, 11, 12, 0, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        assertEquals("2026-05", MonthlyTrafficLedger.monthKey(calendar.timeInMillis, utc))
    }
}

class LanSharePolicyTest {
    @Test
    fun acceptsLanAndLoopbackOnly() {
        assertTrue(LanSharePolicy.acceptClient("192.168.1.40"))
        assertTrue(LanSharePolicy.acceptClient("10.0.0.8"))
        assertTrue(LanSharePolicy.acceptClient("127.0.0.1"))
        assertFalse(LanSharePolicy.acceptClient("8.8.8.8"))
        assertFalse(LanSharePolicy.acceptClient("198.18.0.1"))
        assertFalse(LanSharePolicy.acceptClient(null))
    }

    @Test
    fun endpointHidesBlankAddresses() {
        assertEquals("192.168.1.40:18080", LanSharePolicy.endpoint("192.168.1.40"))
        assertNull(LanSharePolicy.endpoint("  "))
        assertEquals(18_080, LanSharePolicy.PORT)
    }
}
