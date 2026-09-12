package com.uacspoofer.mobile.update

import java.util.zip.ZipFile

internal data class ReleaseApkAsset(
    val name: String,
    val url: String,
)

internal const val APK_VARIANT_ARM64 = "arm64-v8a"
internal const val APK_VARIANT_ARMEABI_V7A = "armeabi-v7a"
internal const val APK_VARIANT_X86_64 = "x86_64"
internal const val APK_VARIANT_X86 = "x86"
internal const val APK_VARIANT_UNIVERSAL = "universal"
internal const val APK_VARIANT_TV = "tv-armeabi-v7a"

internal val KNOWN_APK_ABIS = setOf(
    APK_VARIANT_ARM64,
    APK_VARIANT_ARMEABI_V7A,
    APK_VARIANT_X86_64,
    APK_VARIANT_X86,
)

internal fun variantFromPackedAbis(
    packed: Set<String>,
    supportedAbis: Array<String>,
    tvMode: Boolean,
): String {
    if (tvMode) return APK_VARIANT_TV
    return when {
        packed.size > 1 -> APK_VARIANT_UNIVERSAL
        packed.size == 1 -> packed.single()
        else -> supportedAbis.firstOrNull { it in KNOWN_APK_ABIS } ?: APK_VARIANT_ARM64
    }
}

internal fun abisPackedInApk(apkPath: String): Set<String> {
    val found = linkedSetOf<String>()
    ZipFile(apkPath).use { zip ->
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val name = entries.nextElement().name.replace('\\', '/')
            val abi = packedAbiFromZipEntry(name) ?: continue
            found += abi
        }
    }
    return found
}

internal fun packedAbiFromZipEntry(entryName: String): String? {
    val name = entryName.replace('\\', '/')
    if (!name.startsWith("lib/")) return null
    val abi = name.substringAfter('/').substringBefore('/')
    return abi.takeIf { it in KNOWN_APK_ABIS }
}

internal fun classifyApkAssetName(fileName: String): String? {
    val name = fileName.lowercase()
    if (!name.endsWith(".apk")) return null
    if ("unsigned" in name) return null
    if (name.contains("-tv-") || name.startsWith("app-tv") || name.contains("tv-armeabi")) {
        return APK_VARIANT_TV
    }
    return when {
        APK_VARIANT_ARM64 in name -> APK_VARIANT_ARM64
        APK_VARIANT_ARMEABI_V7A in name -> APK_VARIANT_ARMEABI_V7A
        APK_VARIANT_X86_64 in name -> APK_VARIANT_X86_64
        Regex("(^|[^a-z0-9])x86([^a-z0-9]|\\.apk$)").containsMatchIn(name) -> APK_VARIANT_X86
        APK_VARIANT_UNIVERSAL in name -> APK_VARIANT_UNIVERSAL
        name == "app-release.apk" || name == "uac-spoofer.apk" -> APK_VARIANT_UNIVERSAL
        else -> null
    }
}

internal fun selectReleaseApk(
    assets: List<ReleaseApkAsset>,
    installedVariant: String,
): ReleaseApkAsset? {
    val variantsToTry = if (installedVariant == APK_VARIANT_TV) {
        listOf(APK_VARIANT_TV, APK_VARIANT_ARMEABI_V7A)
    } else {
        listOf(installedVariant)
    }
    for (variant in variantsToTry) {
        val best = assets.mapNotNull { asset ->
            val score = scoreReleaseApk(asset.name, variant)
            if (score > 0) asset to score else null
        }.maxWithOrNull(
            compareBy<Pair<ReleaseApkAsset, Int>> { it.second }
                .thenBy { it.first.name },
        )
        if (best != null) return best.first
    }
    return null
}

internal fun scoreReleaseApk(fileName: String, variant: String): Int {
    if (classifyApkAssetName(fileName) != variant) return 0
    val name = fileName.lowercase()
    var score = 100
    if (name.startsWith("uac-")) score += 20
    if (variant == APK_VARIANT_UNIVERSAL && APK_VARIANT_UNIVERSAL in name) score += 15
    if ("android7plus" in name) score -= 5
    return score
}
