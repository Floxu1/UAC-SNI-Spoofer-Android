package com.uacspoofer.mobile.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdateManagerTest {
    @Test fun comparesSemanticReleaseVersions() {
        assertTrue(isVersionNewer("v1.0.8", "1.0.7"))
        assertTrue(isVersionNewer("2.0", "1.99.99"))
        assertFalse(isVersionNewer("v1.0.7", "1.0.7"))
        assertFalse(isVersionNewer("v1.0.5", "1.0.7"))
    }
}

class ReleaseApkSelectorTest {
    private val githubAssets = listOf(
        asset("UAC-2.0.5-arm64-v8a.apk"),
        asset("UAC-2.0.5-armeabi-v7a.apk"),
        asset("UAC-2.0.5-universal.apk"),
        asset("UAC-2.0.5-x86.apk"),
        asset("UAC-2.0.5-x86_64.apk"),
        asset("app-release.apk"),
        asset("app-tv-armeabi-v7a.apk"),
        asset("WHICH-APK.txt"),
    )

    @Test
    fun arm64InstallStaysOnArm64AndIgnoresUniversal() {
        assertEquals(
            "UAC-2.0.5-arm64-v8a.apk",
            selectReleaseApk(githubAssets, APK_VARIANT_ARM64)?.name,
        )
    }

    @Test
    fun universalInstallStaysOnNamedUniversalNotAppRelease() {
        assertEquals(
            "UAC-2.0.5-universal.apk",
            selectReleaseApk(githubAssets, APK_VARIANT_UNIVERSAL)?.name,
        )
    }

    @Test
    fun armeabiInstallDoesNotReceiveTvOrArm64() {
        assertEquals(
            "UAC-2.0.5-armeabi-v7a.apk",
            selectReleaseApk(githubAssets, APK_VARIANT_ARMEABI_V7A)?.name,
        )
    }

    @Test
    fun x86_64IsNotConfusedWithX86() {
        assertEquals("UAC-2.0.5-x86_64.apk", selectReleaseApk(githubAssets, APK_VARIANT_X86_64)?.name)
        assertEquals("UAC-2.0.5-x86.apk", selectReleaseApk(githubAssets, APK_VARIANT_X86)?.name)
    }

    @Test
    fun tvPrefersTvApkThenFallsBackToPhoneV7a() {
        assertEquals(
            "app-tv-armeabi-v7a.apk",
            selectReleaseApk(githubAssets, APK_VARIANT_TV)?.name,
        )
        assertEquals(
            "UAC-2.0.5-armeabi-v7a.apk",
            selectReleaseApk(
                githubAssets.filterNot { it.name.contains("tv") },
                APK_VARIANT_TV,
            )?.name,
        )
    }

    @Test
    fun appReleaseIsOnlyAUniversalFallback() {
        val onlyFat = listOf(asset("app-release.apk"), asset("UAC-2.0.6-arm64-v8a.apk"))
        assertEquals("app-release.apk", selectReleaseApk(onlyFat, APK_VARIANT_UNIVERSAL)?.name)
        assertEquals("UAC-2.0.6-arm64-v8a.apk", selectReleaseApk(onlyFat, APK_VARIANT_ARM64)?.name)
    }

    @Test
    fun missingMatchingAbiIsNotReplacedWithAnotherAbi() {
        val arm64Only = listOf(asset("UAC-2.0.6-arm64-v8a.apk"), asset("UAC-2.0.6-universal.apk"))
        assertNull(selectReleaseApk(arm64Only, APK_VARIANT_ARMEABI_V7A))
        assertNull(selectReleaseApk(listOf(asset("UAC-2.0.6-universal.apk")), APK_VARIANT_ARM64))
    }

    @Test
    fun oldAndroid7plusNamesStillMatchTheInstalledAbi() {
        val legacy = listOf(
            asset("UAC-2.0.4-arm64-v8a-Android7plus.apk"),
            asset("UAC-2.0.4-universal-Android7plus.apk"),
            asset("UAC-2.0.4-armeabi-v7a-Android7plus.apk"),
        )
        assertEquals(
            "UAC-2.0.4-arm64-v8a-Android7plus.apk",
            selectReleaseApk(legacy, APK_VARIANT_ARM64)?.name,
        )
        assertEquals(
            "UAC-2.0.4-universal-Android7plus.apk",
            selectReleaseApk(legacy, APK_VARIANT_UNIVERSAL)?.name,
        )
    }

    @Test
    fun unsignedAssetsAreIgnored() {
        assertNull(
            selectReleaseApk(
                listOf(asset("UAC-2.0.6-arm64-v8a-unsigned.apk")),
                APK_VARIANT_ARM64,
            ),
        )
    }

    @Test
    fun packedAbisDistinguishSplitFromUniversal() {
        val split = tempApk("lib/arm64-v8a/libxray.so")
        val universal = tempApk(
            "lib/arm64-v8a/libxray.so",
            "lib/armeabi-v7a/libxray.so",
            "lib/x86_64/libxray.so",
            "lib/x86/libxray.so",
        )
        try {
            assertEquals(setOf(APK_VARIANT_ARM64), abisPackedInApk(split.path))
            assertEquals(
                setOf(APK_VARIANT_ARM64, APK_VARIANT_ARMEABI_V7A, APK_VARIANT_X86_64, APK_VARIANT_X86),
                abisPackedInApk(universal.path),
            )
            assertEquals(
                APK_VARIANT_ARM64,
                variantFromPackedAbis(abisPackedInApk(split.path), arrayOf(APK_VARIANT_ARM64), tvMode = false),
            )
            assertEquals(
                APK_VARIANT_UNIVERSAL,
                variantFromPackedAbis(abisPackedInApk(universal.path), arrayOf(APK_VARIANT_ARM64), tvMode = false),
            )
            assertEquals(
                APK_VARIANT_TV,
                variantFromPackedAbis(setOf(APK_VARIANT_ARMEABI_V7A), arrayOf(APK_VARIANT_ARMEABI_V7A), tvMode = true),
            )
        } finally {
            split.delete()
            universal.delete()
        }
    }

    @Test
    fun zipEntryPathsMapToKnownAbisOnly() {
        assertEquals(APK_VARIANT_ARM64, packedAbiFromZipEntry("lib/arm64-v8a/libgojni.so"))
        assertEquals(APK_VARIANT_X86_64, packedAbiFromZipEntry("lib\\x86_64\\libtor.so"))
        assertNull(packedAbiFromZipEntry("assets/geoip.dat"))
        assertNull(packedAbiFromZipEntry("lib/unknown/libfoo.so"))
    }

    private fun asset(name: String) = ReleaseApkAsset(
        name = name,
        url = "https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/download/2.0.5/$name",
    )

    private fun tempApk(vararg entries: String): File {
        val file = File.createTempFile("uac-apk-variant", ".apk")
        ZipOutputStream(file.outputStream()).use { zip ->
            for (entry in entries) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
        return file
    }
}
