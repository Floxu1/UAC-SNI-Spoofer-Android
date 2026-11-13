package com.uacspoofer.mobile.widget

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.Engine4UnlockStore
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.location.GpsSpoofTarget
import com.uacspoofer.mobile.profiles.CountryMetadata
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.ui.CountryFlagResources
import com.uacspoofer.mobile.ui.MainActivity
import com.uacspoofer.mobile.vpn.ConnectionMetricsStore
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import com.uacspoofer.mobile.vpn.LivePing
import com.uacspoofer.mobile.vpn.TrafficStatsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

class UacHomeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        updateAll(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> toggle(context)
            ACTION_ENGINE -> {
                val mode = EngineMode.fromStored(intent.getStringExtra(EXTRA_ENGINE))
                switchEngine(context.applicationContext, mode)
            }
        }
    }

    companion object {
        private const val ACTION_TOGGLE = "com.uacspoofer.mobile.widget.TOGGLE"
        private const val ACTION_ENGINE = "com.uacspoofer.mobile.widget.ENGINE"
        private const val EXTRA_ENGINE = "engine"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val engineGate = AtomicBoolean(false)
        private val glowGeneration = AtomicInteger(0)
        private var glowJob: Job? = null
        private var sampleJob: Job? = null
        @Volatile private var bound = false

        fun bind(app: Application) {
            if (bound) return
            bound = true
            scope.launch {
                ConnectionStateStore.state.collect {
                    updateAll(app)
                    syncGlow(app)
                    syncSamples(app)
                }
            }
            scope.launch {
                EngineModeStore.get(app).mode.collect { updateAll(app) }
            }
            scope.launch {
                Engine4UnlockStore.get(app).unlocked.collect { updateAll(app) }
            }
            scope.launch {
                TrafficStatsStore.stats.collect { pushMeters(app) }
            }
            scope.launch {
                ConnectionMetricsStore.metrics.collect { pushMeters(app) }
            }
            scope.launch {
                ExitIpInfoRepository.get(app).state.collect { updateAll(app) }
            }
            scope.launch {
                TorEngineStore.get(app).settings.collect { updateAll(app) }
            }
            scope.launch {
                PowEngineStore.get(app).settings.collect { updateAll(app) }
            }
            scope.launch {
                PowStatusStore.status
                    .map { it.exitRegion }
                    .distinctUntilChanged()
                    .collect { updateAll(app) }
            }
        }

        fun updateAll(context: Context) {
            val app = context.applicationContext
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, UacHomeWidgetProvider::class.java))
            for (id in ids) {
                manager.updateAppWidget(id, render(app, manager, id))
            }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int): RemoteViews {
            val state = ConnectionStateStore.state.value
            val engine = EngineModeStore.get(context).snapshot()
            val directUnlocked = Engine4UnlockStore.get(context).snapshot()
            val face = face(state)
            val density = context.resources.displayMetrics.density.coerceAtMost(2f)
            val (widthPx, heightPx) = widgetPixels(context, manager, widgetId)
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            views.setImageViewBitmap(R.id.widget_art, UacWidgetAssets.panel(context, widthPx, heightPx))
            views.setImageViewBitmap(R.id.widget_shield, UacWidgetAssets.shield(context))
            views.setImageViewBitmap(R.id.widget_button, UacWidgetAssets.power(face.accent))
            views.setImageViewBitmap(R.id.widget_ping_icon, UacWidgetAssets.pingIcon())
            views.setImageViewBitmap(R.id.widget_globe_icon, UacWidgetAssets.globeIcon())
            views.setImageViewBitmap(R.id.widget_down_icon, UacWidgetAssets.downIcon())
            views.setImageViewBitmap(R.id.widget_up_icon, UacWidgetAssets.upIcon())
            applyMeters(views)
            applyCountry(context, views)
            views.setImageViewBitmap(R.id.widget_status_dot, UacWidgetArt.dot(px(density, 12f), face.dot))
            views.setImageViewBitmap(
                R.id.widget_signal,
                UacWidgetArt.signal(px(density, 16f), px(density, 13f), face.accent, face.bars),
            )
            views.setTextViewText(R.id.widget_status, face.status)
            views.setOnClickPendingIntent(R.id.widget_button, pending(context, toggleIntent(context), 1))
            val open = openApp(context)
            views.setOnClickPendingIntent(R.id.widget_country_card, open)
            views.setOnClickPendingIntent(R.id.widget_brand, open)
            views.setOnClickPendingIntent(R.id.widget_vpn, open)
            views.setOnClickPendingIntent(R.id.widget_shield, open)
            paintEngine(
                views,
                R.id.widget_engine_xray,
                R.id.widget_engine_xray_bg,
                R.id.widget_engine_xray_label,
                EngineMode.XRAY_CF,
                engine,
                face.accent,
                context,
                density,
                50f,
                11,
            )
            paintEngine(
                views,
                R.id.widget_engine_tor,
                R.id.widget_engine_tor_bg,
                R.id.widget_engine_tor_label,
                EngineMode.TOR_WEBTUNNEL,
                engine,
                face.accent,
                context,
                density,
                42f,
                12,
            )
            paintEngine(
                views,
                R.id.widget_engine_pow,
                R.id.widget_engine_pow_bg,
                R.id.widget_engine_pow_label,
                EngineMode.UAC_POW,
                engine,
                face.accent,
                context,
                density,
                44f,
                13,
            )
            if (directUnlocked || engine.isDirect) {
                views.setViewVisibility(R.id.widget_engine_direct, View.VISIBLE)
                paintEngine(
                    views,
                    R.id.widget_engine_direct,
                    R.id.widget_engine_direct_bg,
                    R.id.widget_engine_direct_label,
                    EngineMode.UAC_DIRECT,
                    engine,
                    face.accent,
                    context,
                    density,
                    58f,
                    14,
                )
            } else {
                views.setViewVisibility(R.id.widget_engine_direct, View.GONE)
            }
            return views
        }

        private fun paintEngine(
            views: RemoteViews,
            frameId: Int,
            backgroundId: Int,
            labelId: Int,
            mode: EngineMode,
            selected: EngineMode,
            accent: Int,
            context: Context,
            density: Float,
            widthDp: Float,
            requestCode: Int,
        ) {
            val on = mode == selected
            views.setImageViewBitmap(
                backgroundId,
                UacWidgetArt.pill(px(density, widthDp), px(density, 24f), accent, on),
            )
            views.setTextColor(labelId, if (on) Color.WHITE else 0xFF8A99AD.toInt())
            views.setOnClickPendingIntent(frameId, pending(context, engineIntent(context, mode), requestCode))
        }

        private fun widgetPixels(context: Context, manager: AppWidgetManager, widgetId: Int): Pair<Int, Int> {
            val options = manager.getAppWidgetOptions(widgetId)
            val density = context.resources.displayMetrics.density
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 320
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 } ?: 168
            return px(density, widthDp.toFloat()) to px(density, heightDp.toFloat())
        }

        private fun px(density: Float, dp: Float): Int = (dp * density).toInt().coerceAtLeast(1)

        private fun syncGlow(app: Context) {
            val live = isGlowing(ConnectionStateStore.state.value)
            if (!live) {
                glowGeneration.incrementAndGet()
                glowJob?.cancel()
                glowJob = null
                return
            }
            if (glowJob?.isActive == true) return
            val generation = glowGeneration.incrementAndGet()
            val started = SystemClock.elapsedRealtime()
            glowJob = scope.launch(Dispatchers.Default) {
                while (isActive && glowGeneration.get() == generation) {
                    val state = ConnectionStateStore.state.value
                    if (!isGlowing(state)) break
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val glow = 0.62f + 0.58f * (0.5f + 0.5f * sin(elapsed / 280.0).toFloat())
                    val phase = (elapsed % 1_800L) / 1_800f
                    val bitmap = UacWidgetAssets.power(face(state).accent, glow, phase)
                    withContext(Dispatchers.Main) {
                        if (glowGeneration.get() != generation) return@withContext
                        pushButton(app, bitmap)
                    }
                    delay(64)
                }
            }
        }

        private fun syncSamples(app: Context) {
            if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) {
                sampleJob?.cancel()
                sampleJob = null
                return
            }
            if (sampleJob?.isActive == true) return
            sampleJob = scope.launch(Dispatchers.IO) {
                delay(LivePing.SETTLE_MS)
                while (isActive && ConnectionStateStore.state.value == ConnectionState.CONNECTED) {
                    val measuredAt = ConnectionMetricsStore.metrics.value.measuredAtMs
                    val fresh = measuredAt != null && System.currentTimeMillis() - measuredAt < 7_000L
                    if (!fresh) {
                        LivePing.awaitChipSlot(app) {
                            ConnectionStateStore.state.value == ConnectionState.CONNECTED
                        }
                        if (ConnectionStateStore.state.value != ConnectionState.CONNECTED) break
                        val sample = runCatching { LivePing.measure(app) }.getOrNull()
                        if (sample != null) ConnectionMetricsStore.publishLivePing(sample)
                    }
                    delay(LivePing.INTERVAL_MS)
                }
            }
        }

        private fun pushMeters(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UacHomeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            applyMeters(views)
            for (id in ids) manager.partiallyUpdateAppWidget(id, views)
        }

        private fun applyMeters(views: RemoteViews) {
            val connected = ConnectionStateStore.state.value == ConnectionState.CONNECTED
            val ping = ConnectionMetricsStore.metrics.value.latencyMs
            views.setTextViewText(
                R.id.widget_ping_value,
                if (connected && ping != null && ping > 0L) "$ping ms" else "—",
            )
            val stats = TrafficStatsStore.stats.value
            views.setTextViewText(
                R.id.widget_down_value,
                if (connected) rate(stats.downloadBytesPerSecond) else "—",
            )
            views.setTextViewText(
                R.id.widget_up_value,
                if (connected) rate(stats.uploadBytesPerSecond) else "—",
            )
        }

        private fun applyCountry(context: Context, views: RemoteViews) {
            val country = widgetCountry(context)
            views.setTextViewText(R.id.widget_country_name, country.name)
            if (country.flagRes != null) {
                views.setViewVisibility(R.id.widget_country_flag, View.VISIBLE)
                views.setImageViewResource(R.id.widget_country_flag, country.flagRes)
            } else {
                views.setViewVisibility(R.id.widget_country_flag, View.GONE)
            }
        }

        private fun widgetCountry(context: Context): WidgetCountry {
            val engine = EngineModeStore.get(context).snapshot()
            val persian = context.resources.configuration.locales[0].language.equals("fa", ignoreCase = true)
            val locale = if (persian) Locale("fa") else Locale.ENGLISH
            if (ConnectionStateStore.state.value == ConnectionState.CONNECTED) {
                val exit = ExitIpInfoRepository.get(context).state.value.info
                val fromExit = exit?.let { CountryMetadata.resolve(it.countryCode, it.country) }?.takeIf { it.isKnown }
                if (fromExit != null) return named(fromExit.countryCode, persian)
            }
            if (engine.isDirect) {
                return WidgetCountry(if (persian) "مستقیم" else "Direct", null)
            }
            if (engine.isTor) {
                val code = TorEngineStore.get(context).snapshot().exitCountryCode
                if (code.isBlank()) return WidgetCountry(if (persian) "خودکار" else "Automatic", null)
                return WidgetCountry(TorExitCountry.displayName(code, locale), CountryFlagResources.drawableFor(code))
            }
            if (engine.isPow) {
                val live = PowStatusStore.status.value.exitRegion
                val code = live.ifBlank { PowEngineStore.get(context).snapshot().exitCountryCode }
                if (code.isBlank()) return WidgetCountry(if (persian) "خودکار" else "Automatic", null)
                return WidgetCountry(PowRegions.name(code, locale), CountryFlagResources.drawableFor(code))
            }
            val profileCountry = ProfileStore(context).selectedProfile().country
            if (!profileCountry.isKnown) return WidgetCountry("—", null)
            return named(profileCountry.countryCode, persian)
        }

        private fun named(code: String?, persian: Boolean): WidgetCountry {
            val name = GpsSpoofTarget.displayName(code, persian).ifBlank { "—" }
            return WidgetCountry(name, CountryFlagResources.drawableFor(code))
        }

        private fun rate(bytesPerSecond: Long): String {
            val value = bytesPerSecond.coerceAtLeast(0L).toDouble()
            if (value < 1.0) return "0 KB/s"
            return when {
                value >= 1_048_576.0 -> String.format(Locale.US, "%.1f MB/s", value / 1_048_576.0)
                value >= 10_240.0 -> String.format(Locale.US, "%.0f KB/s", value / 1_024.0)
                else -> String.format(Locale.US, "%.2f KB/s", value / 1_024.0)
            }
        }

        private fun pushButton(context: Context, bitmap: android.graphics.Bitmap) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, UacHomeWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_home)
            views.setImageViewBitmap(R.id.widget_button, bitmap)
            for (id in ids) manager.partiallyUpdateAppWidget(id, views)
        }

        private fun isGlowing(state: ConnectionState): Boolean =
            state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING

        private fun face(state: ConnectionState): Face = when (state) {
            ConnectionState.DISCONNECTED -> Face(0xFF299EFF.toInt(), 0xFF299EFF.toInt(), "وصل نیست", 1)
            ConnectionState.CONNECTING -> Face(0xFF27D7FF.toInt(), 0xFF27D7FF.toInt(), "در حال اتصال", 2)
            ConnectionState.CONNECTED -> Face(0xFF00FF95.toInt(), 0xFF2CE672.toInt(), "وصل است", 3)
            ConnectionState.DISCONNECTING -> Face(0xFFFFB44A.toInt(), 0xFFFFB44A.toInt(), "در حال قطع", 2)
            ConnectionState.ERROR -> Face(0xFFFF3344.toInt(), 0xFFFF3344.toInt(), "خطا در اتصال", 1)
        }

        private fun toggle(context: Context) {
            when (ConnectionStateStore.state.value) {
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                -> disconnect(context)
                ConnectionState.DISCONNECTING -> Unit
                ConnectionState.DISCONNECTED,
                ConnectionState.ERROR,
                -> connect(context)
            }
        }

        private fun connect(context: Context) {
            val app = context.applicationContext
            if (needsPermission(app)) {
                openPermissionFlow(context)
                return
            }
            if (!ConnectionStateStore.tryBeginConnect()) return
            runCatching { VpnController.start(app) }
                .onFailure { ConnectionStateStore.markError() }
        }

        private fun disconnect(context: Context) {
            val app = context.applicationContext
            if (!ConnectionStateStore.tryBeginDisconnect()) return
            runCatching { VpnController.stop(app) }
                .onFailure { ConnectionStateStore.markDisconnected() }
        }

        private fun switchEngine(context: Context, mode: EngineMode) {
            scope.launch {
                if (!engineGate.compareAndSet(false, true)) return@launch
                try {
                    val store = EngineModeStore.get(context)
                    if (store.snapshot() == mode) return@launch
                    val live = ConnectionStateStore.state.value == ConnectionState.CONNECTED ||
                        ConnectionStateStore.state.value == ConnectionState.CONNECTING
                    if (live) {
                        if (!ConnectionStateStore.tryBeginDisconnect()) return@launch
                        runCatching { VpnController.stop(context) }
                        val settled = withTimeoutOrNull(15_000L) {
                            ConnectionStateStore.state.first { canChangeEngineMode(it) }
                        }
                        if (settled == null) return@launch
                    } else if (!canChangeEngineMode(ConnectionStateStore.state.value)) {
                        return@launch
                    }
                    if (store.setMode(mode) != EngineModeChangeResult.APPLIED) return@launch
                    if (live) {
                        delay(250)
                        connect(context)
                    }
                } finally {
                    engineGate.set(false)
                    updateAll(context)
                }
            }
        }

        private fun needsPermission(context: Context): Boolean {
            val proxyMode = AdvancedSettingsStore(context).snapshot().connectionMode == CONNECTION_MODE_PROXY
            val needsVpn = !proxyMode && VpnService.prepare(context) != null
            val needsNotification =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
            return needsVpn || needsNotification
        }

        private fun openPermissionFlow(context: Context) {
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CONNECT_FROM_QUICK_TILE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
        }

        private fun openApp(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return pending(context, intent, 2, activity = true)
        }

        private fun toggleIntent(context: Context): Intent =
            Intent(context, UacHomeWidgetProvider::class.java).setAction(ACTION_TOGGLE)

        private fun engineIntent(context: Context, mode: EngineMode): Intent =
            Intent(context, UacHomeWidgetProvider::class.java)
                .setAction(ACTION_ENGINE)
                .putExtra(EXTRA_ENGINE, mode.id)

        private fun pending(
            context: Context,
            intent: Intent,
            requestCode: Int,
            activity: Boolean = false,
        ): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return if (activity) {
                PendingIntent.getActivity(context, requestCode, intent, flags)
            } else {
                PendingIntent.getBroadcast(context, requestCode, intent, flags)
            }
        }
    }
}

private data class WidgetCountry(
    val name: String,
    val flagRes: Int?,
)

private data class Face(
    val accent: Int,
    val dot: Int,
    val status: String,
    val bars: Int,
)
