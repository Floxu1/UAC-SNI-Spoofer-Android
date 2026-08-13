package com.uacspoofer.mobile.mci

import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.settings.AdvancedSettingsData


internal object MciNativeXrayConfig {
    fun build(
        edge: MciEdge = MciConfig.PRIMARY_EDGE,
        settings: AdvancedSettingsData = AdvancedSettingsData.DEFAULT,
        profile: ProxyProfile = ProxyProfile.MCI_BUILT_IN,
        runtimeOptions: MciXrayRuntimeOptions = MciXrayRuntimeOptions.DEFAULT,
    ): String = MciXrayConfigBuilder.build(
        edge,
        settings,
        profile,
        nativeTun = true,
        runtimeOptions = runtimeOptions,
    )
}
