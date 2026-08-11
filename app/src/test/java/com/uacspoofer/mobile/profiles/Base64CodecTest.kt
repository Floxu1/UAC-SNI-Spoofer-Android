package com.uacspoofer.mobile.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Base64CodecTest {
    @Test
    fun roundTripsUtf8WithAndWithoutPadding() {
        val original = "vless://example\nTrojan • test"
        val encoded = Base64Codec.encode(original.toByteArray(Charsets.UTF_8))
        assertEquals(original, Base64Codec.decode(encoded).toString(Charsets.UTF_8))
        assertEquals(original, Base64Codec.decode(encoded.trimEnd('=')).toString(Charsets.UTF_8))
    }
}
