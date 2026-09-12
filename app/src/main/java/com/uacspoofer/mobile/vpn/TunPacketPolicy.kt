package com.uacspoofer.mobile.vpn

internal enum class TunPacketAction {
    FORWARD,
    DROP,
    SINKHOLE,
}

/**
 * Pure packet classifier for the TUN filter. No I/O and no preference reads.
 * Callers pass cached booleans from [TunPacketFilter].
 */
internal object TunPacketPolicy {
    const val STUN_MAGIC_0 = 0x21.toByte()
    const val STUN_MAGIC_1 = 0x12.toByte()
    const val STUN_MAGIC_2 = 0xA4.toByte()
    const val STUN_MAGIC_3 = 0x42.toByte()

    fun inspect(
        packet: ByteArray,
        length: Int,
        isSinkholeEnabled: Boolean,
        isWebRtcBlockerEnabled: Boolean,
        isQuicBlockerEnabled: Boolean = false,
        isIpv6BlockerEnabled: Boolean = false,
        qnameScratch: CharArray = QNAME_SCRATCH.get() ?: CharArray(256),
    ): TunPacketAction {
        if (length < 1) return TunPacketAction.FORWARD
        if (!isSinkholeEnabled && !isWebRtcBlockerEnabled && !isQuicBlockerEnabled && !isIpv6BlockerEnabled) {
            return TunPacketAction.FORWARD
        }
        val version = (packet[0].toInt() and 0xF0) shr 4
        if (isIpv6BlockerEnabled && version == 6) return TunPacketAction.DROP
        return when (version) {
            4 -> inspectIpv4(
                packet,
                length,
                isSinkholeEnabled,
                isWebRtcBlockerEnabled,
                isQuicBlockerEnabled,
                qnameScratch,
            )
            6 -> inspectIpv6(
                packet,
                length,
                isSinkholeEnabled,
                isWebRtcBlockerEnabled,
                isQuicBlockerEnabled,
                qnameScratch,
            )
            else -> TunPacketAction.FORWARD
        }
    }

    fun buildDnsSinkholeReply(query: ByteArray, queryLength: Int, reply: ByteArray): Int {
        if (queryLength < 20 || reply.size < queryLength + 32) return 0
        val version = (query[0].toInt() and 0xF0) shr 4
        return when (version) {
            4 -> buildIpv4DnsReply(query, queryLength, reply)
            6 -> buildIpv6DnsReply(query, queryLength, reply)
            else -> 0
        }
    }

    private fun inspectIpv4(
        packet: ByteArray,
        length: Int,
        isSinkholeEnabled: Boolean,
        isWebRtcBlockerEnabled: Boolean,
        isQuicBlockerEnabled: Boolean,
        qnameScratch: CharArray,
    ): TunPacketAction {
        if (length < 20) return TunPacketAction.FORWARD
        val ihl = (packet[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > length) return TunPacketAction.FORWARD
        if (packet[9].toInt() and 0xFF != PROTO_UDP) return TunPacketAction.FORWARD
        if (length < ihl + 8) return TunPacketAction.FORWARD
        val fragOff = ((packet[6].toInt() and 0x1F) shl 8) or (packet[7].toInt() and 0xFF)
        if (fragOff != 0) return TunPacketAction.FORWARD
        val udpOff = ihl
        if (udpOff + 4 > length) return TunPacketAction.FORWARD
        val srcPort = u16(packet, udpOff)
        val dstPort = u16(packet, udpOff + 2)
        if (isQuicBlockerEnabled && dstPort == 443) return TunPacketAction.DROP
        val payloadOff = udpOff + 8
        val payloadLen = length - payloadOff
        if (payloadLen < 0) return TunPacketAction.FORWARD
        if (isSinkholeEnabled && isBlockedDnsQuery(packet, payloadOff, payloadLen, length, dstPort, qnameScratch)) {
            return TunPacketAction.SINKHOLE
        }
        if (isWebRtcBlockerEnabled && shouldDropWebRtc(srcPort, dstPort, packet, payloadOff, payloadLen)) {
            return TunPacketAction.DROP
        }
        return TunPacketAction.FORWARD
    }

    private fun inspectIpv6(
        packet: ByteArray,
        length: Int,
        isSinkholeEnabled: Boolean,
        isWebRtcBlockerEnabled: Boolean,
        isQuicBlockerEnabled: Boolean,
        qnameScratch: CharArray,
    ): TunPacketAction {
        if (length < 48) return TunPacketAction.FORWARD
        if (packet[6].toInt() and 0xFF != PROTO_UDP) return TunPacketAction.FORWARD
        val udpOff = 40
        if (udpOff + 4 > length) return TunPacketAction.FORWARD
        val srcPort = u16(packet, udpOff)
        val dstPort = u16(packet, udpOff + 2)
        if (isQuicBlockerEnabled && dstPort == 443) return TunPacketAction.DROP
        val payloadOff = udpOff + 8
        val payloadLen = length - payloadOff
        if (payloadLen < 0) return TunPacketAction.FORWARD
        if (isSinkholeEnabled && isBlockedDnsQuery(packet, payloadOff, payloadLen, length, dstPort, qnameScratch)) {
            return TunPacketAction.SINKHOLE
        }
        if (isWebRtcBlockerEnabled && shouldDropWebRtc(srcPort, dstPort, packet, payloadOff, payloadLen)) {
            return TunPacketAction.DROP
        }
        return TunPacketAction.FORWARD
    }

    private fun isBlockedDnsQuery(
        packet: ByteArray,
        payloadOff: Int,
        payloadLen: Int,
        length: Int,
        dstPort: Int,
        qnameScratch: CharArray,
    ): Boolean {
        if (dstPort != 53 || payloadLen < 12) return false
        if ((packet[payloadOff + 2].toInt() and 0x80) != 0) return false
        val hostLen = decodeQname(packet, payloadOff + 12, length, qnameScratch)
        return hostLen > 0 && TrackerBlocklist.isBlocked(String(qnameScratch, 0, hostLen))
    }

    fun shouldDropWebRtc(
        srcPort: Int,
        dstPort: Int,
        packet: ByteArray,
        payloadOff: Int,
        payloadLen: Int,
    ): Boolean {
        if (isStunTurnPort(dstPort) || isStunTurnPort(srcPort)) return true
        return isStunPayload(packet, payloadOff, payloadLen)
    }

    fun isStunTurnPort(port: Int): Boolean = when (port) {
        3478, 3479, 3480, 3481, 5349, 5350 -> true
        in 19302..19309 -> true
        else -> false
    }

    fun isStunPayload(packet: ByteArray, payloadOff: Int, payloadLen: Int): Boolean {
        if (payloadLen < 20) return false
        if (payloadOff < 0 || payloadOff + 8 > packet.size) return false
        return packet[payloadOff + 4] == STUN_MAGIC_0 &&
            packet[payloadOff + 5] == STUN_MAGIC_1 &&
            packet[payloadOff + 6] == STUN_MAGIC_2 &&
            packet[payloadOff + 7] == STUN_MAGIC_3
    }

    /**
     * Decodes a DNS QNAME into [scratch] as a lowercase hostname.
     * Returns hostname length, or 0 if the name is empty/malformed.
     */
    fun decodeQname(packet: ByteArray, offset: Int, length: Int, scratch: CharArray): Int {
        var pos = offset
        var written = 0
        var jumps = 0
        while (pos < length && jumps < 16) {
            val label = packet[pos].toInt() and 0xFF
            if (label == 0) {
                return written
            }
            if (label and 0xC0 == 0xC0) {
                if (pos + 1 >= length) return 0
                val pointer = ((label and 0x3F) shl 8) or (packet[pos + 1].toInt() and 0xFF)
                if (pointer >= length) return 0
                pos = pointer
                jumps++
                continue
            }
            if (label and 0xC0 != 0) return 0
            pos++
            if (pos + label > length) return 0
            if (written != 0) {
                if (written >= scratch.size) return 0
                scratch[written] = '.'
                written++
            }
            var i = 0
            while (i < label) {
                if (written >= scratch.size) return 0
                var ch = packet[pos + i].toInt() and 0xFF
                if (ch in 65..90) ch += 32
                scratch[written] = ch.toChar()
                written++
                i++
            }
            pos += label
        }
        return 0
    }

    private fun buildIpv4DnsReply(query: ByteArray, queryLength: Int, reply: ByteArray): Int {
        val ihl = (query[0].toInt() and 0xF) * 4
        if (ihl < 20 || queryLength < ihl + 8 + 12) return 0
        val dnsOff = ihl + 8
        val dnsLen = buildDnsMessage(query, dnsOff, queryLength, reply, dnsOff)
        if (dnsLen <= 0) return 0
        System.arraycopy(query, 0, reply, 0, dnsOff)
        swapBytes(reply, 12, 16, 4)
        swapBytes(reply, ihl, ihl + 2, 2)
        val total = dnsOff + dnsLen
        reply[2] = (total ushr 8).toByte()
        reply[3] = total.toByte()
        reply[10] = 0
        reply[11] = 0
        val ipSum = internetChecksum(reply, 0, ihl)
        reply[10] = (ipSum ushr 8).toByte()
        reply[11] = ipSum.toByte()
        val udpLen = 8 + dnsLen
        reply[ihl + 4] = (udpLen ushr 8).toByte()
        reply[ihl + 5] = udpLen.toByte()
        reply[ihl + 6] = 0
        reply[ihl + 7] = 0
        return total
    }

    private fun buildIpv6DnsReply(query: ByteArray, queryLength: Int, reply: ByteArray): Int {
        if (queryLength < 48 + 12) return 0
        val dnsOff = 48
        val dnsLen = buildDnsMessage(query, dnsOff, queryLength, reply, dnsOff)
        if (dnsLen <= 0) return 0
        System.arraycopy(query, 0, reply, 0, dnsOff)
        swapBytes(reply, 8, 24, 16)
        swapBytes(reply, 40, 42, 2)
        val udpLen = 8 + dnsLen
        reply[4] = (udpLen ushr 8).toByte()
        reply[5] = udpLen.toByte()
        reply[44] = (udpLen ushr 8).toByte()
        reply[45] = udpLen.toByte()
        reply[46] = 0
        reply[47] = 0
        val sum = udpChecksumIpv6(reply, udpLen)
        reply[46] = (sum ushr 8).toByte()
        reply[47] = sum.toByte()
        return dnsOff + dnsLen
    }

    private fun buildDnsMessage(
        query: ByteArray,
        dnsOff: Int,
        queryLength: Int,
        reply: ByteArray,
        replyDnsOff: Int,
    ): Int {
        val questionEnd = skipQname(query, dnsOff + 12, queryLength)
        if (questionEnd <= 0 || questionEnd + 4 > queryLength) return 0
        val qtype = u16(query, questionEnd)
        val questionLen = (questionEnd + 4) - dnsOff
        System.arraycopy(query, dnsOff, reply, replyDnsOff, questionLen)
        val flagsOff = replyDnsOff + 2
        val nxdomain = qtype != 1 && qtype != 28
        val flags = if (nxdomain) 0x8183 else 0x8180
        reply[flagsOff] = (flags ushr 8).toByte()
        reply[flagsOff + 1] = flags.toByte()
        reply[replyDnsOff + 4] = 0
        reply[replyDnsOff + 5] = 1
        reply[replyDnsOff + 6] = 0
        reply[replyDnsOff + 7] = if (nxdomain) 0 else 1
        reply[replyDnsOff + 8] = 0
        reply[replyDnsOff + 9] = 0
        reply[replyDnsOff + 10] = 0
        reply[replyDnsOff + 11] = 0
        if (nxdomain) return questionLen
        var pos = replyDnsOff + questionLen
        reply[pos++] = 0xC0.toByte()
        reply[pos++] = 0x0C.toByte()
        reply[pos++] = (qtype ushr 8).toByte()
        reply[pos++] = qtype.toByte()
        reply[pos++] = 0
        reply[pos++] = 1
        reply[pos++] = 0
        reply[pos++] = 0
        reply[pos++] = 1
        reply[pos++] = 0x2C.toByte()
        if (qtype == 1) {
            reply[pos++] = 0
            reply[pos++] = 4
            reply[pos++] = 0
            reply[pos++] = 0
            reply[pos++] = 0
            reply[pos++] = 0
        } else {
            reply[pos++] = 0
            reply[pos++] = 16
            var i = 0
            while (i < 16) {
                reply[pos++] = 0
                i++
            }
        }
        return pos - replyDnsOff
    }

    private fun skipQname(packet: ByteArray, offset: Int, length: Int): Int {
        var pos = offset
        var jumps = 0
        while (pos < length && jumps < 16) {
            val label = packet[pos].toInt() and 0xFF
            if (label == 0) return pos + 1
            if (label and 0xC0 == 0xC0) {
                return if (pos + 1 < length) pos + 2 else 0
            }
            if (label and 0xC0 != 0) return 0
            pos += 1 + label
            jumps++
        }
        return 0
    }

    private fun swapBytes(buf: ByteArray, a: Int, b: Int, size: Int) {
        var i = 0
        while (i < size) {
            val tmp = buf[a + i]
            buf[a + i] = buf[b + i]
            buf[b + i] = tmp
            i++
        }
    }

    private fun u16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun internetChecksum(buf: ByteArray, offset: Int, length: Int, extra: Long = 0L): Int {
        var sum = extra
        var i = 0
        while (i + 1 < length) {
            sum += ((buf[offset + i].toInt() and 0xFF) shl 8) or (buf[offset + i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < length) sum += (buf[offset + i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun udpChecksumIpv6(packet: ByteArray, udpLen: Int): Int {
        var sum = 0L
        var i = 8
        while (i < 40) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += udpLen
        sum += PROTO_UDP
        return internetChecksum(packet, 40, udpLen, extra = sum)
    }

    private const val PROTO_UDP = 17

    private val QNAME_SCRATCH = object : ThreadLocal<CharArray>() {
        override fun initialValue(): CharArray = CharArray(256)
    }
}
