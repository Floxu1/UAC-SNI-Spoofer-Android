package com.uacspoofer.mobile.settings
import org.junit.Assert.*
import org.junit.Test
class AdvancedSettingsDataTest {
 @Test fun defaultsPreserveCurrentRuntime() { val d=AdvancedSettingsData.DEFAULT; assertEquals(1280,d.tunMtu); assertEquals(10808,d.socksPort); assertEquals(5,d.finalmaskLength); assertEquals(0,d.finalmaskDelayMs); assertEquals(listOf(2,100,2),d.edges().map{it.finalmaskMaxSplit}); assertEquals("www.ignitelimit.com",d.tlsSni); assertTrue(d.blockUdp443); assertFalse(d.telegramRouteEnabled); assertFalse(d.telegramSessionResumption); assertEquals("unsafe",d.telegramFingerprint); assertEquals("104.18.9.83",d.telegramAddress); assertEquals("104.18.8.83",d.telegramFallbackAddress); assertTrue(d.telegramCipherSuites.endsWith("TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256:TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256")) }
 @Test fun validationClampsAndNormalizes() { val v=AdvancedSettingsData(primaryPort=0,tunMtu=10,wsPath="assignment",finalmaskLength=0).validated(); assertEquals(1,v.primaryPort); assertEquals(576,v.tunMtu); assertEquals("/assignment",v.wsPath); assertEquals(1,v.finalmaskLength) }
}

