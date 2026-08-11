package com.uacspoofer.mobile.mci
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import org.junit.Assert.*
import org.junit.Test
class AdvancedXraySettingsTest {
 @Test fun defaultsAreEmitted(){val c=MciXrayCore.buildConfig();assertTrue(c.contains("\"protocol\":\"trojan\""));assertTrue(c.contains("\"network\":\"ws\""));assertTrue(c.contains("\"security\":\"tls\""));assertTrue(c.contains("\"udp\":true"))}
 @Test fun udpAndProtocolSettingsAreApplied(){val c=MciXrayCore.buildConfig(settings=AdvancedSettingsData(socksUdp=false));assertTrue(c.contains("\"udp\":false"))}
 @Test fun retiredTelegramRouteIsNeverEmitted(){
  val forcedLegacySetting=AdvancedSettingsData.DEFAULT.copy(telegramRouteEnabled=true)
  val executableConfig=MciXrayCore.buildConfig(settings=forcedLegacySetting)
  val nativeConfig=MciNativeXrayConfig.build(settings=forcedLegacySetting)
  for(c in listOf(executableConfig,nativeConfig)){
   assertTrue(c.contains("\"tag\":\"proxy\""))
   assertFalse(c.contains("telegram-upload"))
   assertFalse(c.contains("149.154.165.0/24"))
   assertTrue(c.contains("\"ip\":[\"::/0\"]"))
  }
 }
 @Test fun telegramRouteCanBeDisabledWithoutChangingPrimary(){
  val c=MciXrayCore.buildConfig(settings=AdvancedSettingsData(telegramRouteEnabled=false))
  assertFalse(c.contains("telegram-proxy"))
  assertFalse(c.contains("telegram-upload"))
  assertTrue(c.contains("\"tag\":\"proxy\""))
  assertTrue(c.contains("\"address\":\"104.18.1.1\""))
 }
}
