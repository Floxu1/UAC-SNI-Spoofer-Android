package com.uacspoofer.mobile.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkGuardSettings(
    val killSwitch: Boolean = false,
    val autoConnect: Boolean = false,
    val lanShare: Boolean = false,
    val dnsSinkhole: Boolean = true,
    val webRtcBlock: Boolean = true,
    val quicBlock: Boolean = false,
    val ipv6Block: Boolean = true,
)

class NetworkGuardStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutableSettings = MutableStateFlow(read())
    val settings: StateFlow<NetworkGuardSettings> = mutableSettings.asStateFlow()

    fun snapshot(): NetworkGuardSettings = mutableSettings.value

    @Synchronized
    fun setKillSwitch(enabled: Boolean) {
        persist(mutableSettings.value.copy(killSwitch = enabled))
    }

    @Synchronized
    fun setAutoConnect(enabled: Boolean) {
        persist(mutableSettings.value.copy(autoConnect = enabled))
        if (enabled) clearUserStopped()
    }

    @Synchronized
    fun setLanShare(enabled: Boolean) {
        persist(mutableSettings.value.copy(lanShare = enabled))
    }

    @Synchronized
    fun setDnsSinkhole(enabled: Boolean) {
        persist(mutableSettings.value.copy(dnsSinkhole = enabled))
    }

    @Synchronized
    fun setWebRtcBlock(enabled: Boolean) {
        persist(mutableSettings.value.copy(webRtcBlock = enabled))
    }

    @Synchronized
    fun setQuicBlock(enabled: Boolean) {
        persist(mutableSettings.value.copy(quicBlock = enabled))
    }

    @Synchronized
    fun setIpv6Block(enabled: Boolean) {
        persist(mutableSettings.value.copy(ipv6Block = enabled))
    }

    private fun read(): NetworkGuardSettings = NetworkGuardSettings(
        killSwitch = prefs.getBoolean(KEY_KILL_SWITCH, false),
        autoConnect = prefs.getBoolean(KEY_AUTO_CONNECT, false),
        lanShare = prefs.getBoolean(KEY_LAN_SHARE, false),
        dnsSinkhole = prefs.getBoolean(KEY_DNS_SINKHOLE, true),
        webRtcBlock = prefs.getBoolean(KEY_WEBRTC_BLOCK, true),
        quicBlock = prefs.getBoolean(KEY_QUIC_BLOCK, false),
        ipv6Block = prefs.getBoolean(KEY_IPV6_BLOCK, true),
    )

    private fun persist(value: NetworkGuardSettings) {
        prefs.edit()
            .putBoolean(KEY_KILL_SWITCH, value.killSwitch)
            .putBoolean(KEY_AUTO_CONNECT, value.autoConnect)
            .putBoolean(KEY_LAN_SHARE, value.lanShare)
            .putBoolean(KEY_DNS_SINKHOLE, value.dnsSinkhole)
            .putBoolean(KEY_WEBRTC_BLOCK, value.webRtcBlock)
            .putBoolean(KEY_QUIC_BLOCK, value.quicBlock)
            .putBoolean(KEY_IPV6_BLOCK, value.ipv6Block)
            .apply()
        mutableSettings.value = value
    }

    companion object {
        private const val PREFS = "network_guard_v1"
        private const val KEY_KILL_SWITCH = "kill_switch"
        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_LAN_SHARE = "lan_share"
        private const val KEY_DNS_SINKHOLE = "dns_sinkhole"
        private const val KEY_WEBRTC_BLOCK = "webrtc_block"
        private const val KEY_QUIC_BLOCK = "quic_block_v2"
        private const val KEY_IPV6_BLOCK = "ipv6_block"

        private val mutableBlocking = MutableStateFlow(false)
        val blocking: StateFlow<Boolean> = mutableBlocking.asStateFlow()

        @Volatile
        var userStoppedThisProcess: Boolean = false
            private set

        fun markUserStopped() {
            userStoppedThisProcess = true
        }

        fun clearUserStopped() {
            userStoppedThisProcess = false
        }

        fun setBlocking(value: Boolean) {
            mutableBlocking.value = value
        }

        @Volatile private var instance: NetworkGuardStore? = null

        fun get(context: Context): NetworkGuardStore = instance ?: synchronized(this) {
            instance ?: NetworkGuardStore(context.applicationContext).also { instance = it }
        }
    }
}
