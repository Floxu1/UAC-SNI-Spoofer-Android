package com.uacspoofer.mobile.location

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.LocationProvider
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object GpsSpoofRuntime {
    private const val TICK_MS = 1_000L
    private const val WALK_DEG = 0.00018
    private const val MAX_OFFSET_DEG = 0.012
    private val providers = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("fused")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = AtomicBoolean(false)
    private val providersMutex = Mutex()
    private var lastCountry: String? = null
    private var walkLat = 0.0
    private var walkLng = 0.0
    private var mockDeniedLogged = false

    fun attach(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                GpsSpoofStore.get(app).enabled,
                ConnectionStateStore.state,
            ) { enabled, state -> enabled && state == ConnectionState.CONNECTED }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (active) injectLoop(app) else stopProviders(app)
                }
        }
    }

    fun isMockLocationAllowed(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION,
                    Process.myUid(),
                    context.packageName,
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    fun openDeveloperSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val launched = runCatching { context.startActivity(intent) }.isSuccess
            if (launched) return
        }
    }

    private suspend fun injectLoop(context: Context) {
        mockDeniedLogged = false
        lastCountry = null
        while (true) {
            val code = GpsSpoofTarget.fromContext(context)
            val point = GpsSpoofCoordinates.forCode(code)
            if (code == null || point == null) {
                delay(TICK_MS)
                continue
            }
            if (code != lastCountry) {
                lastCountry = code
                walkLat = 0.0
                walkLng = 0.0
                AppLogRepository.info(LogSource.APP, "GPS spoof armed for $code")
            }
            walkLat = (walkLat + Random.nextDouble(-WALK_DEG, WALK_DEG)).coerceIn(-MAX_OFFSET_DEG, MAX_OFFSET_DEG)
            walkLng = (walkLng + Random.nextDouble(-WALK_DEG, WALK_DEG)).coerceIn(-MAX_OFFSET_DEG, MAX_OFFSET_DEG)
            val lngScale = cos(Math.toRadians(point.latitude)).coerceAtLeast(0.2)
            val location = buildLocation(
                latitude = point.latitude + walkLat,
                longitude = point.longitude + (walkLng / lngScale),
            )
            val pushed = push(context, location)
            delay(if (pushed) TICK_MS else 3_000L)
        }
    }

    private suspend fun push(context: Context, sample: Location): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return providersMutex.withLock {
            try {
                var pushed = false
                for (name in providers) {
                    try {
                        ensureProvider(manager, name)
                        manager.setTestProviderLocation(
                            name,
                            Location(sample).apply { provider = name },
                        )
                        pushed = true
                    } catch (error: SecurityException) {
                        throw error
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        AppLogRepository.debug(LogSource.APP, "GPS spoof skipped $name: ${error.message}")
                    }
                }
                if (pushed) mockDeniedLogged = false
                pushed
            } catch (error: SecurityException) {
                if (!mockDeniedLogged) {
                    mockDeniedLogged = true
                    AppLogRepository.warning(
                        LogSource.APP,
                        "GPS spoof needs this app as the mock location app",
                        error,
                    )
                }
                false
            }
        }
    }

    private suspend fun stopProviders(context: Context) {
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        providersMutex.withLock {
            for (name in providers) {
                runCatching { manager.setTestProviderEnabled(name, false) }
                runCatching { manager.removeTestProvider(name) }
            }
        }
        if (lastCountry != null) {
            AppLogRepository.info(LogSource.APP, "GPS spoof stopped")
        }
        lastCountry = null
        walkLat = 0.0
        walkLng = 0.0
        mockDeniedLogged = false
    }

    @Suppress("DEPRECATION")
    private fun ensureProvider(manager: LocationManager, name: String) {
        try {
            manager.addTestProvider(
                name,
                false,
                false,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        } catch (_: IllegalArgumentException) {
            // Provider already exists.
        }
        manager.setTestProviderEnabled(name, true)
        runCatching {
            manager.setTestProviderStatus(
                name,
                LocationProvider.AVAILABLE,
                null,
                System.currentTimeMillis(),
            )
        }
    }

    private fun buildLocation(latitude: Double, longitude: Double): Location {
        return Location(LocationManager.GPS_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = Random.nextDouble(6.0, 16.0).toFloat()
            altitude = Random.nextDouble(18.0, 64.0)
            bearing = Random.nextDouble(0.0, 360.0).toFloat()
            speed = Random.nextDouble(0.05, 0.35).toFloat()
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                verticalAccuracyMeters = 12f
                speedAccuracyMetersPerSecond = 0.4f
                bearingAccuracyDegrees = 8f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                isMock = true
            }
        }
    }
}
