package com.uacspoofer.mobile.mci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class MciFragmenterTest {
    @Test
    fun extractsSniFromClientHello() {
        val hello = clientHello("www.ignitelimit.com")
        assertEquals("www.ignitelimit.com", MciFragmenter.findSni(hello))
    }

    @Test
    fun exactFinalmaskMakesTwoRecordsInFirstWrite() {
        val payload = ByteArray(12) { it.toByte() }
        val input = tlsRecord(payload)
        val rewrite = MciFragmenter.rewriteFinalMaskWrites(input)

        assertNull(rewrite.trailingWrite)
        assertEquals(5, unsignedShort(rewrite.firstWrite, 3))
        val secondHeader = 5 + 5
        assertEquals(0x16, rewrite.firstWrite[secondHeader].toInt() and 0xff)
        assertEquals(payload.size - 5, unsignedShort(rewrite.firstWrite, secondHeader + 3))
        val rebuiltPayload =
            rewrite.firstWrite.copyOfRange(5, secondHeader) +
                rewrite.firstWrite.copyOfRange(secondHeader + 5, rewrite.firstWrite.size)
        assertArrayEquals(payload, rebuiltPayload)
    }

    @Test
    fun trailingBytesRemainASeparateWrite() {
        val record = tlsRecord(ByteArray(8) { (it + 1).toByte() })
        val trailing = byteArrayOf(0x17, 0x03, 0x03, 0x00, 0x00)
        val rewrite = MciFragmenter.rewriteFinalMaskWrites(record + trailing)

        assertNotNull(rewrite.trailingWrite)
        assertArrayEquals(trailing, rewrite.trailingWrite)
        assertArrayEquals(recordPayload(record), recordPayloadFromRewrite(rewrite.firstWrite))
    }

    @Test
    fun legacyFull5PreservesAllBytes() {
        val input = ByteArray(27) { (it * 3).toByte() }
        val chunks = MciFragmenter.fragment(input, FragmentStrategy.FULL5)
        assertEquals(listOf(5, 5, 5, 5, 5, 2), chunks.map(ByteArray::size))
        assertArrayEquals(input, chunks.fold(byteArrayOf()) { all, chunk -> all + chunk })
    }

    private fun clientHello(host: String): ByteArray {
        val name = host.toByteArray(Charsets.US_ASCII)
        val serverName = byteArrayOf(0x00) + short(name.size) + name
        val names = short(serverName.size) + serverName
        val extension = short(0) + short(names.size) + names
        val extensions = short(extension.size) + extension
        val body =
            byteArrayOf(0x03, 0x03) +
                ByteArray(32) +
                byteArrayOf(0x00) +
                short(2) + byteArrayOf(0x13, 0x01) +
                byteArrayOf(0x01, 0x00) +
                extensions
        val handshake = byteArrayOf(0x01) + uint24(body.size) + body
        return tlsRecord(handshake)
    }

    private fun tlsRecord(payload: ByteArray): ByteArray =
        byteArrayOf(0x16, 0x03, 0x03) + short(payload.size) + payload

    private fun recordPayload(record: ByteArray): ByteArray =
        record.copyOfRange(5, 5 + unsignedShort(record, 3))

    private fun recordPayloadFromRewrite(rewrite: ByteArray): ByteArray {
        val firstLength = unsignedShort(rewrite, 3)
        val secondHeader = 5 + firstLength
        val secondLength = unsignedShort(rewrite, secondHeader + 3)
        return rewrite.copyOfRange(5, secondHeader) +
            rewrite.copyOfRange(secondHeader + 5, secondHeader + 5 + secondLength)
    }

    private fun short(value: Int): ByteArray = byteArrayOf(
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    private fun uint24(value: Int): ByteArray = byteArrayOf(
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )

    private fun unsignedShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
}
