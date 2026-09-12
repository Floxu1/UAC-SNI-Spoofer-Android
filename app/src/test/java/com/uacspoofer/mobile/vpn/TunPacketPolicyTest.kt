package com.uacspoofer.mobile.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunPacketPolicyTest {
    @Test
    fun trackerSuffixesMatchSubdomainsOnly() {
        assertTrue(TrackerBlocklist.isBlocked("google-analytics.com"))
        assertTrue(TrackerBlocklist.isBlocked("ssl.google-analytics.com"))
        assertTrue(TrackerBlocklist.isBlocked("pagead2.googlesyndication.com"))
        assertTrue(TrackerBlocklist.isBlocked("connect.facebook.net"))
        assertFalse(TrackerBlocklist.isBlocked("google.com"))
        assertFalse(TrackerBlocklist.isBlocked("youtube.com"))
        assertFalse(TrackerBlocklist.isBlocked("facebook.com"))
        assertFalse(TrackerBlocklist.isBlocked(""))
    }

    @Test
    fun sinkholeBlocksTrackerDnsAndForwardsTheRest() {
        val tracker = ipv4UdpDns("ssl.google-analytics.com", qtype = 1)
        assertEquals(
            TunPacketAction.SINKHOLE,
            TunPacketPolicy.inspect(tracker, tracker.size, isSinkholeEnabled = true, isWebRtcBlockerEnabled = false),
        )
        val safe = ipv4UdpDns("example.com", qtype = 1)
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(safe, safe.size, isSinkholeEnabled = true, isWebRtcBlockerEnabled = false),
        )
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(tracker, tracker.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = false),
        )
    }

    @Test
    fun sinkholeReplyIsLocalIpv4ZeroAndSwapsAddresses() {
        val query = ipv4UdpDns("ads.doubleclick.net", qtype = 1)
        val reply = ByteArray(query.size + 32)
        val n = TunPacketPolicy.buildDnsSinkholeReply(query, query.size, reply)
        assertTrue(n > 28)
        assertArrayEquals(query.copyOfRange(12, 16), reply.copyOfRange(16, 20))
        assertArrayEquals(query.copyOfRange(16, 20), reply.copyOfRange(12, 16))
        assertEquals(0, reply[n - 4].toInt())
        assertEquals(0, reply[n - 3].toInt())
        assertEquals(0, reply[n - 2].toInt())
        assertEquals(0, reply[n - 1].toInt())
        assertEquals(0x81.toByte(), reply[20 + 8 + 2])
    }

    @Test
    fun webrtcDropsStunCookieAndKnownPortsButNotQuic() {
        val stun = ipv4Udp(srcPort = 50000, dstPort = 443, payload = stunBinding())
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(stun, stun.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = true),
        )
        val turnPort = ipv4Udp(srcPort = 50000, dstPort = 3478, payload = ByteArray(8) { 1 })
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(turnPort, turnPort.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = true),
        )
        val googleStun = ipv4Udp(srcPort = 50000, dstPort = 19302, payload = ByteArray(8) { 1 })
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(googleStun, googleStun.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = true),
        )
        val quic = ipv4Udp(srcPort = 50000, dstPort = 443, payload = ByteArray(20) { 0x0A })
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(quic, quic.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = true),
        )
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(stun, stun.size, isSinkholeEnabled = false, isWebRtcBlockerEnabled = false),
        )
    }

    @Test
    fun qnameDecoderLowercasesHost() {
        val packet = dnsQuestion("SSL.Google-Analytics.COM")
        val scratch = CharArray(256)
        val len = TunPacketPolicy.decodeQname(packet, 12, packet.size, scratch)
        assertEquals("ssl.google-analytics.com", String(scratch, 0, len))
    }

    @Test
    fun quicBlockerDropsUdp443AndForwardsOtherUdp() {
        val quic = ipv4Udp(srcPort = 50000, dstPort = 443, payload = ByteArray(20) { 0x0A })
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(
                quic,
                quic.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isQuicBlockerEnabled = true,
            ),
        )
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(
                quic,
                quic.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isQuicBlockerEnabled = false,
            ),
        )
        val dns = ipv4Udp(srcPort = 53000, dstPort = 53, payload = ByteArray(12))
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(
                dns,
                dns.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isQuicBlockerEnabled = true,
            ),
        )
        val ipv6Quic = ipv6Udp(srcPort = 50000, dstPort = 443, payload = ByteArray(20) { 0x0A })
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(
                ipv6Quic,
                ipv6Quic.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isQuicBlockerEnabled = true,
            ),
        )
        val truncated = ByteArray(22) { 0 }
        truncated[0] = 0x45
        truncated[9] = 17
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(
                truncated,
                truncated.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isQuicBlockerEnabled = true,
            ),
        )
    }

    @Test
    fun ipv6BlockerDropsAllIpv6AndLeavesIpv4() {
        val ipv6 = ipv6Udp(srcPort = 50000, dstPort = 80, payload = ByteArray(8))
        assertEquals(
            TunPacketAction.DROP,
            TunPacketPolicy.inspect(
                ipv6,
                ipv6.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isIpv6BlockerEnabled = true,
            ),
        )
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(
                ipv6,
                ipv6.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isIpv6BlockerEnabled = false,
            ),
        )
        val ipv4 = ipv4Udp(srcPort = 50000, dstPort = 80, payload = ByteArray(8))
        assertEquals(
            TunPacketAction.FORWARD,
            TunPacketPolicy.inspect(
                ipv4,
                ipv4.size,
                isSinkholeEnabled = false,
                isWebRtcBlockerEnabled = false,
                isIpv6BlockerEnabled = true,
            ),
        )
    }

    private fun ipv4UdpDns(host: String, qtype: Int): ByteArray {
        val question = dnsQuestion(host, qtype)
        return ipv4Udp(srcPort = 53000, dstPort = 53, payload = question)
    }

    private fun dnsQuestion(host: String, qtype: Int = 1): ByteArray {
        val labels = host.split('.')
        val qnameLen = labels.sumOf { 1 + it.length } + 1
        val packet = ByteArray(12 + qnameLen + 4)
        packet[0] = 0x12
        packet[1] = 0x34
        packet[5] = 1
        var pos = 12
        for (label in labels) {
            packet[pos++] = label.length.toByte()
            for (ch in label) packet[pos++] = ch.code.toByte()
        }
        packet[pos++] = 0
        packet[pos++] = (qtype ushr 8).toByte()
        packet[pos++] = qtype.toByte()
        packet[pos++] = 0
        packet[pos] = 1
        return packet
    }

    private fun ipv4Udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        packet[2] = (total ushr 8).toByte()
        packet[3] = total.toByte()
        packet[8] = 64
        packet[9] = 17
        packet[12] = 10
        packet[13] = 0
        packet[14] = 0
        packet[15] = 2
        packet[16] = 1
        packet[17] = 1
        packet[18] = 1
        packet[19] = 1
        packet[20] = (srcPort ushr 8).toByte()
        packet[21] = srcPort.toByte()
        packet[22] = (dstPort ushr 8).toByte()
        packet[23] = dstPort.toByte()
        val udpLen = 8 + payload.size
        packet[24] = (udpLen ushr 8).toByte()
        packet[25] = udpLen.toByte()
        System.arraycopy(payload, 0, packet, 28, payload.size)
        return packet
    }

    private fun ipv6Udp(srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val packet = ByteArray(40 + udpLen)
        packet[0] = 0x60
        packet[4] = (udpLen ushr 8).toByte()
        packet[5] = udpLen.toByte()
        packet[6] = 17
        packet[7] = 64
        packet[40] = (srcPort ushr 8).toByte()
        packet[41] = srcPort.toByte()
        packet[42] = (dstPort ushr 8).toByte()
        packet[43] = dstPort.toByte()
        packet[44] = (udpLen ushr 8).toByte()
        packet[45] = udpLen.toByte()
        System.arraycopy(payload, 0, packet, 48, payload.size)
        return packet
    }

    private fun stunBinding(): ByteArray {
        val payload = ByteArray(20)
        payload[0] = 0x00
        payload[1] = 0x01
        payload[4] = 0x21
        payload[5] = 0x12
        payload[6] = 0xA4.toByte()
        payload[7] = 0x42
        return payload
    }
}
