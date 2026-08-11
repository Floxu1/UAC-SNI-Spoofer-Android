package com.uacspoofer.mobile.vpn
import org.junit.Assert.*
import org.junit.Test
class AdvancedTunnelSettingsTest {
 @Test fun defaultRouteParsesAndCustomRouteIsSupported(){assertEquals("0.0.0.0" to 0,TunRouteParser.parse("0.0.0.0/0"));assertEquals("10.0.0.0" to 8,TunRouteParser.parse("10.0.0.0/8"))}
 @Test(expected=IllegalArgumentException::class) fun invalidRouteRejected(){TunRouteParser.parse("bad-route")}
}
