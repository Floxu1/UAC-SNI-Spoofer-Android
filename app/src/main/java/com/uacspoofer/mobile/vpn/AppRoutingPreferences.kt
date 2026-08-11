package com.uacspoofer.mobile.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppRoutingMode {
    ALL_APPS,
    BYPASS_SELECTED,
    VPN_ONLY_SELECTED,
}

data class AppRoutingSettings(
    val mode: AppRoutingMode = AppRoutingMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
)

data class InstalledVpnApp(
    val label: String,
    val packageName: String,
)


object AppRoutingPreferences {
    private const val PREFS = "app_routing"
    private const val KEY_MODE = "mode"
    private const val KEY_PACKAGES = "selected_packages"
    private const val TAG = "UAC-AppRouting"

    private val lock = Any()
    private val mutableSettings = MutableStateFlow(AppRoutingSettings())
    val settings: StateFlow<AppRoutingSettings> = mutableSettings.asStateFlow()

    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            mutableSettings.value = read(context.applicationContext)
            initialized = true
        }
    }

    fun snapshot(context: Context): AppRoutingSettings {
        initialize(context)
        
        return read(context.applicationContext).also { mutableSettings.value = it }
    }

    fun setMode(context: Context, mode: AppRoutingMode) {
        initialize(context)
        persist(context.applicationContext, mutableSettings.value.copy(mode = mode))
    }

    fun setPackageSelected(context: Context, packageName: String, selected: Boolean) {
        initialize(context)
        val packages = mutableSettings.value.selectedPackages.toMutableSet().apply {
            if (selected) add(packageName) else remove(packageName)
        }
        persist(context.applicationContext, mutableSettings.value.copy(selectedPackages = packages))
    }

    fun installedApps(context: Context): List<InstalledVpnApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }
        return activities
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                InstalledVpnApp(
                    label = info.loadLabel(packageManager).toString().ifBlank { packageName },
                    packageName = packageName,
                )
            }
            .distinctBy(InstalledVpnApp::packageName)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            .toList()
    }

    
    fun applyTo(builder: android.net.VpnService.Builder, context: Context) {
        val current = snapshot(context)
        when (current.mode) {
            AppRoutingMode.ALL_APPS -> builder.addDisallowedApplication(context.packageName)

            AppRoutingMode.BYPASS_SELECTED -> {
                builder.addDisallowedApplication(context.packageName)
                current.selectedPackages.forEach { packageName ->
                    runCatching { builder.addDisallowedApplication(packageName) }
                        .onFailure { Log.w(TAG, "Ignoring unavailable bypass package: $packageName") }
                }
            }

            AppRoutingMode.VPN_ONLY_SELECTED -> {
                
                var allowedCount = 0
                current.selectedPackages.asSequence()
                    .filterNot { it == context.packageName }
                    .forEach { packageName ->
                    runCatching { builder.addAllowedApplication(packageName) }
                        .onSuccess { allowedCount++ }
                        .onFailure { Log.w(TAG, "Ignoring unavailable VPN-only package: $packageName") }
                }
                if (allowedCount == 0) {
                    
                    builder.addDisallowedApplication(context.packageName)
                    Log.w(TAG, "VPN-only mode has no selected apps; using all-app routing for this session")
                }
            }
        }
        Log.i(
            TAG,
            "Applied ${current.mode.name.lowercase(Locale.US)} with ${current.selectedPackages.size} selected apps",
        )
    }

    private fun read(context: Context): AppRoutingSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = runCatching {
            AppRoutingMode.valueOf(prefs.getString(KEY_MODE, AppRoutingMode.ALL_APPS.name).orEmpty())
        }.getOrDefault(AppRoutingMode.ALL_APPS)
        return AppRoutingSettings(
            mode = mode,
            selectedPackages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toSet(),
        )
    }

    private fun persist(context: Context, value: AppRoutingSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, value.mode.name)
            .putStringSet(KEY_PACKAGES, value.selectedPackages.toSet())
            .apply()
        mutableSettings.value = value
    }
}
