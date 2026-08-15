package com.uacspoofer.mobile.vpn

import com.uacspoofer.mobile.mci.MciXrayRuntimeOptions
import com.uacspoofer.mobile.settings.AdvancedSettingsData

data class RouteTuningProfile(
    val id: String,
    val label: String,
    val fragmentEnabled: Boolean,
    val maxSplit: Int,
    val delayMs: Int,
    val muxEnabledOverride: Boolean? = null,
)

object RouteTestArchitecture {
    const val EDGE_LIMIT = 10
    const val RESOLVER_COUNT = 5
    const val TUNING_COUNT = 5
    const val MTU_COUNT = 4
    const val MAX_LOGICAL_CANDIDATES = EDGE_LIMIT * RESOLVER_COUNT * TUNING_COUNT * MTU_COUNT

    fun tuningProfiles(settings: AdvancedSettingsData): List<RouteTuningProfile> = listOf(
        RouteTuningProfile(
            id = "control-off",
            label = "Fragment off",
            fragmentEnabled = false,
            maxSplit = 1,
            delayMs = 0,
        ),
        RouteTuningProfile(
            id = "fast-split",
            label = "Fast split",
            fragmentEnabled = true,
            maxSplit = 2,
            delayMs = 0,
        ),
        RouteTuningProfile(
            id = "stable-split",
            label = "Stable split",
            fragmentEnabled = true,
            maxSplit = 2,
            delayMs = 20,
        ),
        RouteTuningProfile(
            id = "deep-split",
            label = "Deep split",
            fragmentEnabled = true,
            maxSplit = 100,
            delayMs = 5,
        ),
        RouteTuningProfile(
            id = "upload-compat",
            label = "Upload compatibility",
            fragmentEnabled = true,
            maxSplit = 2,
            delayMs = 0,
            muxEnabledOverride = false,
        ),
    ).also { profiles ->
        check(profiles.size == TUNING_COUNT)
        check(profiles.map(RouteTuningProfile::id).distinct().size == profiles.size)
        settings.validated()
    }

    fun mtuValues(currentMtu: Int): List<Int> {
        val safeCurrent = currentMtu.coerceIn(576, 9_000)
        return linkedSetOf(safeCurrent, 1_280, 1_360, 1_400, 1_200, 1_500)
            .take(MTU_COUNT)
    }

    fun tuningKey(candidate: AdaptiveCandidate): String = listOf(
        candidate.edge.address,
        candidate.edge.port,
        candidate.edge.finalmaskMaxSplit,
        candidate.settings.finalmaskPacket,
        candidate.settings.finalmaskLength,
        candidate.settings.finalmaskDelayMs,
        candidate.runtimeOptions.finalmaskEnabled,
        candidate.runtimeOptions.muxEnabledOverride,
        candidate.runtimeOptions.identityOverride,
        candidate.runtimeOptions.preserveEmptyAlpn,
        candidate.runtimeOptions.preserveTransportFields,
    ).joinToString("|")

    fun resolverFamilyKey(candidate: AdaptiveCandidate): String = listOf(
        tuningKey(candidate),
        AdaptiveDnsResolvers.idFor(candidate.settings.dnsResolverUrl),
    ).joinToString("|")

    fun logicalKey(candidate: AdaptiveCandidate): String = listOf(
        resolverFamilyKey(candidate),
        candidate.settings.tunMtu,
    ).joinToString("|")

    fun runtimeOptions(
        base: MciXrayRuntimeOptions,
        tuning: RouteTuningProfile,
    ): MciXrayRuntimeOptions = base.copy(
        finalmaskEnabled = tuning.fragmentEnabled,
        muxEnabledOverride = tuning.muxEnabledOverride,
    )
}
