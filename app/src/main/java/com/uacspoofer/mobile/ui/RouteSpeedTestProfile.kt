package com.uacspoofer.mobile.ui

internal fun resolveRouteSpeedTestProfileId(
    selectedId: String,
    profileIds: Collection<String>,
    connected: Boolean,
    activeProfileId: String?,
): String {
    if (connected && !activeProfileId.isNullOrBlank() && profileIds.any { it == activeProfileId }) {
        return activeProfileId
    }
    return selectedId
}
