package com.uacspoofer.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProcMemoryMapsTest {
    @Test
    fun classifiesTorAndXrayLibraries() {
        assertEquals("tor", ProcMemoryMaps.classifyMapping("/data/app/~~x==/lib/arm64/libtor.so"))
        assertEquals("webtunnel", ProcMemoryMaps.classifyMapping("/data/app/libwebtunnel.so"))
        assertEquals("tun2socks", ProcMemoryMaps.classifyMapping("/data/app/libhev-socks5-tunnel.so"))
        assertEquals("xray", ProcMemoryMaps.classifyMapping("/data/app/libgojni.so"))
        assertEquals("graphics", ProcMemoryMaps.classifyMapping("/vendor/lib64/libEGL.so"))
        assertEquals("so:libfoo.so", ProcMemoryMaps.classifyMapping("/data/app/libfoo.so"))
    }

    @Test
    fun parsesPssFromSmapsSnippet() {
        val file = File.createTempFile("smaps", "txt")
        file.writeText(
            """
            7a000000-7a010000 r-xp 00000000 fd:00 1 /data/app/libtor.so
            Pss:                4096 kB
            7a010000-7a020000 r-xp 00000000 fd:00 2 /data/app/libgojni.so
            Pss:                1024 kB
            """.trimIndent(),
        )
        val buckets = ProcMemoryMaps.readPssBuckets(file).associate { it.id to it.bytes }
        assertEquals(4096L * 1024L, buckets["tor"])
        assertEquals(1024L * 1024L, buckets["xray"])
        file.delete()
    }

    @Test
    fun ranksGrowingTorAboveStableJavaHeap() {
        val ranked = ProcMemoryMaps.rankSuspects(
            latest = listOf(
                MemoryBucket("java_heap", 80L * 1024L * 1024L),
                MemoryBucket("tor", 30L * 1024L * 1024L),
            ),
            slopes = mapOf(
                "java_heap" to 0.2f,
                "tor" to 6.4f,
            ),
            minSamplesReady = true,
            leakSlope = 1.2f,
        )
        assertEquals("tor", ranked.first().id)
        assertTrue(ranked.first().growing)
    }
}
