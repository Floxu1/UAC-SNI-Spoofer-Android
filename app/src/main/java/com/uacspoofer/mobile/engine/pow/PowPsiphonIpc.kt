package com.uacspoofer.mobile.engine.pow

internal object PowPsiphonIpc {
    const val SERVICE_CLASS = "com.uacspoofer.mobile.engine.pow.PowPsiphonService"
    const val KEY_CONFIG = "config"
    const val ASSET_SERVER_ENTRIES = "server_entries.txt"
    const val KEY_MESSAGE = "message"
    const val KEY_REGION = "region"
    const val KEY_ADDRESS = "address"
    const val KEY_REGIONS = "regions"
    const val KEY_SENT = "sent"
    const val KEY_RECEIVED = "received"
    const val KEY_FD = "fd"
    const val KEY_ERROR = "error"

    const val MSG_START = 1
    const val MSG_STOP = 2
    const val MSG_CONNECTED = 3
    const val MSG_EXITING = 4
    const val MSG_CONNECTING = 5
    const val MSG_SOCKS_PORT = 6
    const val MSG_DIAGNOSTIC = 7
    const val MSG_BYTES = 8
    const val MSG_CLIENT_REGION = 9
    const val MSG_SERVER_REGION = 10
    const val MSG_EGRESS_REGIONS = 11
    const val MSG_CLIENT_ADDRESS = 12
    const val MSG_PROTECT = 13
    const val MSG_PROTECT_RESULT = 14
    const val MSG_START_FAILED = 15
    const val MSG_STOPPED = 16
}
