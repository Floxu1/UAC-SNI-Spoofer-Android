package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLatencyTesterTest {
    @Test
    fun medianIgnoresAHighOutlier() {
        assertEquals(
            205L,
            ProfileLatencyTester.medianSuccessful(listOf(180L, 220L, 205L, 800L, 198L)),
        )
    }

    @Test
    fun medianSupportsEvenSuccessfulSampleCount() {
        assertEquals(212L, ProfileLatencyTester.medianSuccessful(listOf(180L, 220L, 205L, 800L)))
    }

    @Test
    fun connectionCloseHeaderDisablesReuse() {
        val headers = "HTTP/1.1 204 No Content\r\nConnection: keep-alive, close\r\n\r\n"
            .toByteArray(Charsets.US_ASCII)
        assertEquals(true, ProfileLatencyTester.hasConnectionClose(headers))
    }
}
