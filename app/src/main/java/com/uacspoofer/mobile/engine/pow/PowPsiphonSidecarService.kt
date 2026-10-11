package com.uacspoofer.mobile.engine.pow

/**
 * Second Psiphon instance for "two tunnels at once". psi.Start is a singleton per
 * process, so the second tunnel needs its own process (:uacpow2, see the manifest).
 */
class PowPsiphonSidecarService : PowPsiphonService()
