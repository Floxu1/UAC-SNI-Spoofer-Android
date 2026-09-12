package com.uacspoofer.mobile.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object LanShareStatus {
    private val mutableEndpoint = MutableStateFlow(LanShareEndpoint())
    val endpoint: StateFlow<LanShareEndpoint> = mutableEndpoint.asStateFlow()

    fun update(value: LanShareEndpoint) {
        mutableEndpoint.value = value
    }

    fun clear() {
        mutableEndpoint.value = LanShareEndpoint()
    }
}
