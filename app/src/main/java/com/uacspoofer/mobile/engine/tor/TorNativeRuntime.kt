package com.uacspoofer.mobile.engine.tor

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File
import java.util.zip.ZipFile

internal data class TorInstalledRuntime(
    val tor: File,
    val webtunnel: File,
)

/**
 * Prefer PackageManager-extracted JNI libs. App-copied files under filesDir cannot be
 * executed on Android 10+ (W^X); those copies are only a space-free fallback and must
 * still be launched through the system linker.
 */
internal object TorNativeRuntime {
    fun install(context: Context): TorInstalledRuntime {
        val binDir = File(context.filesDir, "tor/bin").apply { mkdirs() }
        return TorInstalledRuntime(
            tor = resolve(context, "libtor.so", File(binDir, "tor")),
            webtunnel = resolve(context, "libwebtunnel.so", File(binDir, "webtunnel")),
        )
    }

    private fun resolve(context: Context, nativeName: String, fallback: File): File {
        val native = File(context.applicationInfo.nativeLibraryDir, nativeName)
        if (native.isFile && native.length() > 0L && !native.absolutePath.any { it.isWhitespace() }) {
            return native
        }
        materialize(context, nativeName, native, fallback)
        return fallback
    }

    private fun materialize(context: Context, nativeName: String, source: File, destination: File) {
        if (source.isFile && source.length() > 0L) {
            source.copyTo(destination, overwrite = true)
        } else {
            extractFromApk(context, nativeName, destination)
        }
        destination.setReadable(true, false)
        destination.setExecutable(true, false)
        runCatching { Os.chmod(destination.absolutePath, MODE_0700) }
        check(destination.isFile && destination.length() > 0L) {
            "$nativeName could not be installed at ${destination.absolutePath}"
        }
        check(!destination.absolutePath.any { it.isWhitespace() }) {
            "Tor binary path cannot contain spaces: ${destination.absolutePath}"
        }
    }

    private fun extractFromApk(context: Context, nativeName: String, destination: File) {
        val abis = Build.SUPPORTED_ABIS
        val apks = buildList {
            add(File(context.applicationInfo.sourceDir))
            context.applicationInfo.splitSourceDirs.orEmpty().forEach { add(File(it)) }
        }.filter { it.isFile }
        for (apk in apks) {
            ZipFile(apk).use { zip ->
                val entry = abis.firstNotNullOfOrNull { abi ->
                    zip.getEntry("lib/$abi/$nativeName")
                } ?: return@use
                destination.outputStream().use { out ->
                    zip.getInputStream(entry).use { input -> input.copyTo(out) }
                }
            }
            if (destination.isFile && destination.length() > 0L) return
        }
        error("$nativeName is missing from ${context.applicationInfo.nativeLibraryDir} and APK (${abis.joinToString()})")
    }

    private const val MODE_0700 = 448
}
