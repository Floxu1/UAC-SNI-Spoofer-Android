package com.uacspoofer.mobile

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File

/**
 * One-time "Clear Data" when an older install is updated to versionCode >= [RESET_VERSION].
 * Runs from Application.attachBaseContext (before content providers and any service starts),
 * main process only. The marker is written after the wipe so it never repeats.
 */
object UpgradeReset {
    const val RESET_VERSION = 450L
    const val MARKER_PREFS = "uac_upgrade_reset"
    private const val KEY_DONE = "reset_done_version"

    enum class Action { WIPE, MARK_ONLY, NONE }

    fun decide(currentVersion: Long, markerPresent: Boolean, hasExistingData: Boolean): Action = when {
        currentVersion < RESET_VERSION -> Action.NONE
        markerPresent -> Action.NONE
        hasExistingData -> Action.WIPE
        else -> Action.MARK_ONLY
    }

    fun run(base: Context) {
        runCatching {
            if (!isMainProcess(base)) return
            val prefs = base.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
            val markerPresent = prefs.contains(KEY_DONE)
            val action = decide(versionCode(base), markerPresent, if (markerPresent) true else hasExistingData(base))
            if (action == Action.NONE) return
            if (action == Action.WIPE) wipe(base)
            base.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_DONE, versionCode(base)).commit()
        }
    }

    private fun hasExistingData(base: Context): Boolean {
        val dataDir = File(base.applicationInfo.dataDir)
        val prefs = File(dataDir, "shared_prefs").list().orEmpty().any { it != "$MARKER_PREFS.xml" }
        val files = File(dataDir, "files").list().orEmpty().isNotEmpty()
        val dbs = File(dataDir, "databases").list().orEmpty().isNotEmpty()
        return prefs || files || dbs
    }

    private fun wipe(base: Context) {
        runCatching { base.stopService(Intent(base, com.uacspoofer.mobile.vpn.UacVpnService::class.java)) }
        val dataDir = File(base.applicationInfo.dataDir)
        dataDir.listFiles().orEmpty().forEach { child ->
            if (child.name == "lib") return@forEach
            runCatching { child.deleteRecursively() }
        }
        runCatching { base.externalCacheDir?.deleteRecursively() }
        runCatching { base.getExternalFilesDir(null)?.deleteRecursively() }
    }

    private fun versionCode(base: Context): Long {
        @Suppress("DEPRECATION")
        val info = base.packageManager.getPackageInfo(base.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun isMainProcess(base: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            android.app.Application.getProcessName()
        } else {
            val am = base.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == base.packageName
    }
}
