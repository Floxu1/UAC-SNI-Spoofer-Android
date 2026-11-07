package com.uacspoofer.mobile.engine.pow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PemUtilTest {
    @Test
    fun normalizePem_stripsBlankLinesAndRewraps64() {
        val broken = """
            -----BEGIN CERTIFICATE-----
            MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA7VJTUt9Us8cKBwGi0m8Z
            tyCPxmqCqTUL4g6b1Z4=

            XyzPadBodyLineThatIsNotRealButLongEnoughABCDEFGHIJKLMNOP
            -----END CERTIFICATE-----
        """.trimIndent()
        val fixed = PemUtil.normalizePem(broken)
        assertFalse(fixed.contains("\n\n"))
        val lines = fixed.trimEnd().lines()
        assertTrue(lines.first().startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(lines.last().startsWith("-----END CERTIFICATE-----"))
        val body = lines.drop(1).dropLast(1)
        assertTrue(body.isNotEmpty())
        assertTrue(body.all { it.length <= 64 })
        assertEquals(fixed, PemUtil.normalizePem(fixed))
    }

    @Test
    fun normalizePem_emptyStaysEmpty() {
        assertEquals("", PemUtil.normalizePem(""))
    }
}