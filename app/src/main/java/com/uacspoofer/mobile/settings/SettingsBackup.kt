package com.uacspoofer.mobile.settings

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.uacspoofer.mobile.BuildConfig
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.engine.direct.DirectEngineSettings
import com.uacspoofer.mobile.engine.direct.DirectEngineStore
import com.uacspoofer.mobile.engine.pow.PowAdaptiveObfuscation
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowEngineSettings
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.engine.tor.TorEngineSettings
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorExitCountry
import com.uacspoofer.mobile.location.GpsSpoofStore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.ui.ConnectPanelMode
import com.uacspoofer.mobile.ui.ConnectPanelStore
import com.uacspoofer.mobile.ui.DrawerLanguage
import com.uacspoofer.mobile.ui.LanguageStore
import com.uacspoofer.mobile.ui.SniMakerController
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.vpn.AppRoutingMode
import com.uacspoofer.mobile.vpn.AppRoutingPreferences
import com.uacspoofer.mobile.vpn.AppRoutingSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * User settings backup. Caches, last-connect memory, migration flags and engine files stay put.
 * Schema 1 is the identity document; later versions can rename keys in [migrate].
 */
object SettingsBackup {
    const val FORMAT = "uac-settings-backup"
    const val SCHEMA = 1

    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    data class Preview(val versionName: String, val createdAt: String)

    sealed class Load {
        data class Ok(val document: JSONObject) : Load()
        data object Bad : Load()
        data object Io : Load()
    }

    sealed class Outcome {
        data class Restored(val applied: Int, val ignored: Int) : Outcome()
        data object Reset : Outcome()
        data object BadFile : Outcome()
        data object DisconnectFailed : Outcome()
        data object Failed : Outcome()
    }

    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "UAC-backup-$stamp.json"
    }

    fun exportJson(context: Context): String = exportDocument(context).toString(2)

    fun write(context: Context, uri: Uri): Boolean = runCatching {
        val json = exportJson(context)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
        } ?: return false
        true
    }.getOrDefault(false)

    fun read(context: Context, uri: Uri): Load = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return Load.Io
        if (bytes.size > MAX_BYTES) return Load.Bad
        val doc = JSONObject(bytes.toString(Charsets.UTF_8))
        if (doc.optString("format") != FORMAT) return Load.Bad
        if (doc.has("schemaVersion")) {
            val version = doc.get("schemaVersion")
            if (version !is Int && version !is Long) return Load.Bad
        }
        if (doc.optJSONObject("settings") == null) return Load.Bad
        if (doc.has("splitTunnel") && doc.optJSONObject("splitTunnel") == null) return Load.Bad
        Load.Ok(migrate(doc))
    }.getOrElse { error ->
        if (error is org.json.JSONException) Load.Bad else Load.Io
    }

    fun preview(document: JSONObject): Preview = Preview(
        versionName = document.optString("appVersionName").ifBlank { "—" },
        createdAt = document.optString("createdAt").ifBlank { "—" },
    )

    /** Version 1 returns the same document. Newer files still apply only keys this build knows. */
    fun migrate(document: JSONObject): JSONObject {
        var version = document.optInt("schemaVersion", SCHEMA)
        if (version < 1) version = 1
        while (version < SCHEMA) {
            version++
        }
        return document
    }

    suspend fun restore(context: Context, document: JSONObject): Outcome {
        val migrated = runCatching { migrate(document) }.getOrNull() ?: return Outcome.BadFile
        if (migrated.optString("format") != FORMAT) return Outcome.BadFile
        val settings = migrated.optJSONObject("settings") ?: return Outcome.BadFile
        if (!settle(context)) return Outcome.DisconnectFailed
        val tally = Tally()
        val planned = runCatching { plan(context, settings, migrated.optJSONObject("splitTunnel"), tally) }
            .getOrElse { return Outcome.BadFile }
        val snap = capture(context)
        return runCatching {
            apply(context, planned, tally)
            bump()
            Outcome.Restored(tally.applied, tally.ignored)
        }.getOrElse {
            runCatching { writeSnapshot(context, snap) }
            bump()
            Outcome.Failed
        }
    }

    suspend fun reset(context: Context): Outcome {
        if (!settle(context)) return Outcome.DisconnectFailed
        val snap = capture(context)
        return runCatching {
            apply(context, defaults(), null)
            bump()
            Outcome.Reset
        }.getOrElse {
            runCatching { writeSnapshot(context, snap) }
            bump()
            Outcome.Failed
        }
    }

    private fun exportDocument(context: Context): JSONObject {
        val app = context.applicationContext
        val settings = JSONObject()
        val theme = AppThemeStore.get(app).theme.value
        val language = LanguageStore.get(app).snapshot()
        val engine = EngineModeStore.get(app).snapshot()
        val guard = NetworkGuardStore.get(app).snapshot()
        val domains = DirectDomainWhitelist.get(app).snapshot()
        val profiles = ProfileStore(app)
        val advanced = AdvancedSettingsStore(app).snapshot()
        val pow = PowEngineStore.get(app).snapshot()
        val tor = TorEngineStore.get(app).snapshot()
        val direct = DirectEngineStore.get(app).snapshot()
        val sni = sniPrefs(app)
        val adaptive = app.getSharedPreferences(POW_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PowAdaptiveObfuscation.KEY_ADAPTIVE, false)
        val ai = app.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).getBoolean(AI_ENABLED, true)
        val panels = ConnectPanelStore.get(app)

        settings.putTyped(THEME, "string", theme.name)
        settings.putTyped(LANGUAGE, "string", language.name)
        settings.putTyped(ENGINE, "string", engine.id)
        settings.putTyped(KILL, "bool", guard.killSwitch)
        settings.putTyped(AUTO, "bool", guard.autoConnect)
        settings.putTyped(LAN, "bool", guard.lanShare)
        settings.putTyped(DNS, "bool", guard.dnsSinkhole)
        settings.putTyped(WEBRTC, "bool", guard.webRtcBlock)
        settings.putTyped(QUIC, "bool", guard.quicBlock)
        settings.putTyped(IPV6, "bool", guard.ipv6Block)
        settings.putTyped(IRAN, "bool", guard.iranDirect)
        settings.putTyped(DOMAINS, "string", domains.joinToString("\n"))
        settings.putTyped(GPS, "bool", GpsSpoofStore.get(app).snapshot())
        settings.putTyped(PROFILES, "string", profiles.backupProfilesJson())
        settings.putTyped(SELECTED, "string", profiles.backupSelectedId())
        putAdvanced(settings, advanced)
        putPow(settings, pow, adaptive)
        putTor(settings, tor)
        putDirect(settings, direct)
        settings.putTyped(SNI_URL, "string", sni.url)
        settings.putTyped(SNI_WORKERS, "int", sni.workers)
        settings.putTyped(SNI_TIMEOUT, "int", sni.timeout)
        settings.putTyped(AI, "bool", ai)
        settings.putTyped(PANEL_STATUS, "string", panels.status.value.name)
        settings.putTyped(PANEL_STEPS, "string", panels.steps.value.name)

        val routing = AppRoutingPreferences.snapshot(app)
        val split = JSONObject()
            .put("mode", routing.mode.name)
            .put("packages", JSONArray(routing.selectedPackages.toList()))
        val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())
        return JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("createdAt", stamp)
            .put("settings", settings)
            .put("splitTunnel", split)
    }

    private fun plan(context: Context, settings: JSONObject, split: JSONObject?, tally: Tally): Planned {
        countUnknown(settings, tally)
        countSplitExtras(split, tally)
        val bag = Bag(settings, tally)
        val app = context.applicationContext
        val themeNow = AppThemeStore.get(app).theme.value
        val languageNow = LanguageStore.get(app).snapshot()
        val engineNow = EngineModeStore.get(app).snapshot()
        val guardNow = NetworkGuardStore.get(app).snapshot()
        val domainsNow = DirectDomainWhitelist.get(app).snapshot()
        val gpsNow = GpsSpoofStore.get(app).snapshot()
        val advancedNow = AdvancedSettingsStore(app).snapshot()
        val powNow = PowEngineStore.get(app).snapshot()
        val adaptiveNow = app.getSharedPreferences(POW_PREFS, Context.MODE_PRIVATE)
            .getBoolean(PowAdaptiveObfuscation.KEY_ADAPTIVE, false)
        val torNow = TorEngineStore.get(app).snapshot()
        val directNow = DirectEngineStore.get(app).snapshot()
        val sniNow = sniPrefs(app)
        val aiNow = app.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).getBoolean(AI_ENABLED, true)
        val panelsNow = ConnectPanelStore.get(app)
        val panelModes = ConnectPanelMode.entries.map { it.name }.toSet()
        val routingNow = AppRoutingPreferences.snapshot(app)

        val theme = bag.choice(THEME, themeNow.name, AppTheme.NEON.name, AppTheme.entries.map { it.name }.toSet(), lower = false)
        val language = bag.choice(LANGUAGE, languageNow.name, DrawerLanguage.PERSIAN.name, setOf("PERSIAN", "ENGLISH"), lower = false)
        val engine = bag.engine(ENGINE, engineNow)
        val guard = NetworkGuardSettings(
            killSwitch = bag.bool(KILL, guardNow.killSwitch),
            autoConnect = bag.bool(AUTO, guardNow.autoConnect),
            lanShare = bag.bool(LAN, guardNow.lanShare),
            dnsSinkhole = bag.bool(DNS, guardNow.dnsSinkhole),
            webRtcBlock = bag.bool(WEBRTC, guardNow.webRtcBlock),
            quicBlock = bag.bool(QUIC, guardNow.quicBlock),
            ipv6Block = bag.bool(IPV6, guardNow.ipv6Block),
            iranDirect = bag.bool(IRAN, guardNow.iranDirect),
        )
        val domains = if (!settings.has(DOMAINS)) {
            domainsNow
        } else {
            val domainText = bag.text(DOMAINS, domainsNow.joinToString("\n")) { it }
            if (domainText == null) {
                domainsNow
            } else {
                val parts = domainText.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
                val bad = parts.count { DirectDomainWhitelist.normalize(it) == null }
                if (bad > 0) tally.ignored += bad
                parts
            }
        }
        val gps = bag.bool(GPS, gpsNow)
        val profiles = readOptionalString(settings, PROFILES, tally)
        val selected = readOptionalString(settings, SELECTED, tally)
        val advanced = mergeAdvanced(advancedNow, bag)
        val pow = mergePow(powNow, bag)
        val adaptive = bag.bool(ADAPTIVE, adaptiveNow)
        val tor = mergeTor(torNow, bag)
        val direct = mergeDirect(directNow, bag)
        val sniUrl = bag.text(SNI_URL, sniNow.url) { raw ->
            val clean = raw.trim()
            clean.takeIf { it.length in 1..SniMakerController.MAX_URL_CHARS }
        } ?: SniMakerController.DEFAULT_SUBSCRIPTION_URL
        val sniWorkers = bag.num(SNI_WORKERS, sniNow.workers, SniMakerController.DEFAULT_WORKERS) {
            it in SniMakerController.MIN_WORKERS..SniMakerController.MAX_WORKERS
        }
        val sniTimeout = bag.num(SNI_TIMEOUT, sniNow.timeout, SniMakerController.DEFAULT_TIMEOUT_MS) {
            it in SniMakerController.MIN_TIMEOUT_MS..SniMakerController.MAX_TIMEOUT_MS
        }
        val ai = bag.bool(AI, aiNow)
        val statusPanel = ConnectPanelMode.entries.first {
            it.name == bag.choice(
                PANEL_STATUS,
                panelsNow.status.value.name,
                ConnectPanelMode.COLLAPSED.name,
                panelModes,
                lower = false,
            )
        }
        val stepsPanel = ConnectPanelMode.entries.first {
            it.name == bag.choice(
                PANEL_STEPS,
                panelsNow.steps.value.name,
                ConnectPanelMode.COLLAPSED.name,
                panelModes,
                lower = false,
            )
        }
        val routing = mergeRouting(context, routingNow, split, tally)
        val iran = when {
            settings.has(IRAN) -> guard.iranDirect
            settings.has("${DIRECT}iranDirect") -> direct.iranDirect
            settings.has("${ADV}iranDirect") -> advanced.iranDirect
            else -> guardNow.iranDirect
        }
        return Planned(
            theme = AppTheme.entries.first { it.name == theme },
            language = if (language == "ENGLISH") DrawerLanguage.ENGLISH else DrawerLanguage.PERSIAN,
            engine = engine,
            guard = guard.copy(iranDirect = iran),
            domains = domains,
            gps = gps,
            profilesJson = profiles,
            selectedId = selected,
            advanced = advanced.copy(iranDirect = iran),
            pow = pow,
            adaptive = adaptive,
            tor = tor,
            direct = direct.copy(iranDirect = iran),
            sniUrl = sniUrl,
            sniWorkers = sniWorkers,
            sniTimeout = sniTimeout,
            ai = ai,
            routing = routing,
            statusPanel = statusPanel,
            stepsPanel = stepsPanel,
        )
    }

    private fun defaults(): Planned {
        val sni = SniMakerController
        return Planned(
            theme = AppTheme.NEON,
            language = DrawerLanguage.PERSIAN,
            engine = EngineMode.XRAY_CF,
            guard = NetworkGuardSettings(),
            domains = emptyList(),
            gps = false,
            profilesJson = "[]",
            selectedId = com.uacspoofer.mobile.profiles.ProxyProfile.AUTO_ID,
            advanced = AdvancedSettingsData.DEFAULT,
            pow = PowEngineSettings.DEFAULT,
            adaptive = false,
            tor = TorEngineSettings.DEFAULT,
            direct = DirectEngineSettings.DEFAULT,
            sniUrl = sni.DEFAULT_SUBSCRIPTION_URL,
            sniWorkers = sni.DEFAULT_WORKERS,
            sniTimeout = sni.DEFAULT_TIMEOUT_MS,
            ai = true,
            routing = AppRoutingSettings(),
            profilesReset = true,
            statusPanel = ConnectPanelMode.COLLAPSED,
            stepsPanel = ConnectPanelMode.COLLAPSED,
        )
    }

    private fun apply(context: Context, planned: Planned, tally: Tally?) {
        val app = context.applicationContext
        AppThemeStore.get(app).set(planned.theme)
        LanguageStore.get(app).set(planned.language)
        GpsSpoofStore.get(app).setEnabled(planned.gps)
        DirectDomainWhitelist.get(app).replace(planned.domains)
        if (planned.profilesReset) {
            ProfileStore(app).resetCustomProfiles()
        } else if (planned.profilesJson != null || planned.selectedId != null) {
            val (appliedProfiles, ignoredProfiles) = ProfileStore(app).replaceFromBackup(
                planned.profilesJson,
                planned.selectedId,
            )
            if (tally != null) {
                tally.applied += appliedProfiles
                tally.ignored += ignoredProfiles
            }
        }
        AdvancedSettingsStore(app).save(planned.advanced)
        PowEngineStore.get(app).save(planned.pow)
        app.getSharedPreferences(POW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PowAdaptiveObfuscation.KEY_ADAPTIVE, planned.adaptive)
            .apply()
        TorEngineStore.get(app).save(planned.tor)
        DirectEngineStore.get(app).save(planned.direct)
        NetworkGuardStore.get(app).replace(planned.guard)
        alignIran(app)
        sniPrefs(app).write(planned.sniUrl, planned.sniWorkers, planned.sniTimeout)
        app.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AI_ENABLED, planned.ai)
            .apply()
        AppRoutingPreferences.replace(app, planned.routing)
        ConnectPanelStore.get(app).setStatus(planned.statusPanel)
        ConnectPanelStore.get(app).setSteps(planned.stepsPanel)
        val mode = EngineModeStore.get(app).setMode(planned.engine)
        if (mode == EngineModeChangeResult.BLOCKED_WHILE_ACTIVE) error("engine blocked")
    }

    private fun alignIran(context: Context) {
        val iran = NetworkGuardStore.get(context).snapshot().iranDirect
        val advanced = AdvancedSettingsStore(context)
        advanced.save(advanced.snapshot().copy(iranDirect = iran))
        val direct = DirectEngineStore.get(context)
        direct.save(direct.snapshot().copy(iranDirect = iran))
    }

    private data class Planned(
        val theme: AppTheme,
        val language: DrawerLanguage,
        val engine: EngineMode,
        val guard: NetworkGuardSettings,
        val domains: List<String>,
        val gps: Boolean,
        val profilesJson: String?,
        val selectedId: String?,
        val advanced: AdvancedSettingsData,
        val pow: PowEngineSettings,
        val adaptive: Boolean,
        val tor: TorEngineSettings,
        val direct: DirectEngineSettings,
        val sniUrl: String,
        val sniWorkers: Int,
        val sniTimeout: Int,
        val ai: Boolean,
        val routing: AppRoutingSettings,
        val profilesReset: Boolean = false,
        val statusPanel: ConnectPanelMode,
        val stepsPanel: ConnectPanelMode,
    )

    private data class Snapshot(
        val theme: AppTheme,
        val language: DrawerLanguage,
        val engine: EngineMode,
        val guard: NetworkGuardSettings,
        val domains: List<String>,
        val gps: Boolean,
        val profilesJson: String,
        val selectedId: String,
        val advanced: AdvancedSettingsData,
        val pow: PowEngineSettings,
        val adaptive: Boolean,
        val tor: TorEngineSettings,
        val direct: DirectEngineSettings,
        val sniUrl: String,
        val sniWorkers: Int,
        val sniTimeout: Int,
        val ai: Boolean,
        val routing: AppRoutingSettings,
        val statusPanel: ConnectPanelMode,
        val stepsPanel: ConnectPanelMode,
    )

    private fun capture(context: Context): Snapshot {
        val app = context.applicationContext
        val profiles = ProfileStore(app)
        val sni = sniPrefs(app)
        return Snapshot(
            theme = AppThemeStore.get(app).theme.value,
            language = LanguageStore.get(app).snapshot(),
            engine = EngineModeStore.get(app).snapshot(),
            guard = NetworkGuardStore.get(app).snapshot(),
            domains = DirectDomainWhitelist.get(app).snapshot(),
            gps = GpsSpoofStore.get(app).snapshot(),
            profilesJson = profiles.backupProfilesJson(),
            selectedId = profiles.backupSelectedId(),
            advanced = AdvancedSettingsStore(app).snapshot(),
            pow = PowEngineStore.get(app).snapshot(),
            adaptive = app.getSharedPreferences(POW_PREFS, Context.MODE_PRIVATE)
                .getBoolean(PowAdaptiveObfuscation.KEY_ADAPTIVE, false),
            tor = TorEngineStore.get(app).snapshot(),
            direct = DirectEngineStore.get(app).snapshot(),
            sniUrl = sni.url,
            sniWorkers = sni.workers,
            sniTimeout = sni.timeout,
            ai = app.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE).getBoolean(AI_ENABLED, true),
            routing = AppRoutingPreferences.snapshot(app),
            statusPanel = ConnectPanelStore.get(app).status.value,
            stepsPanel = ConnectPanelStore.get(app).steps.value,
        )
    }

    private fun writeSnapshot(context: Context, snap: Snapshot) {
        val app = context.applicationContext
        AppThemeStore.get(app).set(snap.theme)
        LanguageStore.get(app).set(snap.language)
        GpsSpoofStore.get(app).setEnabled(snap.gps)
        DirectDomainWhitelist.get(app).replace(snap.domains)
        ProfileStore(app).restoreProfilePrefs(snap.profilesJson, snap.selectedId)
        AdvancedSettingsStore(app).save(snap.advanced)
        PowEngineStore.get(app).save(snap.pow)
        app.getSharedPreferences(POW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PowAdaptiveObfuscation.KEY_ADAPTIVE, snap.adaptive)
            .apply()
        TorEngineStore.get(app).save(snap.tor)
        DirectEngineStore.get(app).save(snap.direct)
        NetworkGuardStore.get(app).replace(snap.guard)
        alignIran(app)
        sniPrefs(app).write(snap.sniUrl, snap.sniWorkers, snap.sniTimeout)
        app.getSharedPreferences(AI_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AI_ENABLED, snap.ai)
            .apply()
        AppRoutingPreferences.replace(app, snap.routing)
        ConnectPanelStore.get(app).setStatus(snap.statusPanel)
        ConnectPanelStore.get(app).setSteps(snap.stepsPanel)
        EngineModeStore.get(app).setMode(snap.engine)
    }

    private suspend fun settle(context: Context): Boolean {
        if (canChangeEngineMode(ConnectionStateStore.state.value)) return true
        ConnectionStateStore.tryBeginDisconnect()
        VpnController.stop(context)
        return withTimeoutOrNull(15_000L) {
            ConnectionStateStore.state.first { canChangeEngineMode(it) }
        } != null
    }

    private fun bump() {
        mutableRevision.value += 1
    }

    private class Tally {
        var applied: Int = 0
        var ignored: Int = 0
    }

    private sealed interface Entry {
        data class Bool(val value: Boolean) : Entry
        data class IntVal(val value: Int) : Entry
        data class Str(val value: String) : Entry
        data object Bad : Entry
    }

    private class Bag(private val settings: JSONObject, private val tally: Tally) {
        fun bool(key: String, current: Boolean): Boolean {
            if (!settings.has(key)) return current
            return when (val entry = read(settings, key)) {
                is Entry.Bool -> entry.value.also { tally.applied++ }
                else -> current.also { tally.ignored++ }
            }
        }

        fun num(key: String, current: Int, default: Int, ok: (Int) -> Boolean): Int {
            if (!settings.has(key)) return current
            val entry = read(settings, key)
            if (entry !is Entry.IntVal || !ok(entry.value)) {
                tally.ignored++
                return default
            }
            tally.applied++
            return entry.value
        }

        fun text(key: String, current: String, map: (String) -> String?): String? {
            if (!settings.has(key)) return current
            val entry = read(settings, key)
            if (entry !is Entry.Str) {
                tally.ignored++
                return current
            }
            val mapped = map(entry.value)
            return if (mapped != null) {
                tally.applied++
                mapped
            } else {
                tally.ignored++
                null
            }
        }

        fun choice(key: String, current: String, default: String, allowed: Set<String>, lower: Boolean = true): String {
            val mapped = text(key, current) { raw ->
                val clean = if (lower) raw.trim().lowercase(Locale.US) else raw.trim()
                clean.takeIf { it in allowed }
            }
            return mapped ?: if (settings.has(key)) default else current
        }

        fun noteDerivedBool(key: String, derived: Boolean) {
            if (!settings.has(key)) return
            when (val entry = read(settings, key)) {
                is Entry.Bool -> if (entry.value == derived) tally.applied++ else tally.ignored++
                else -> tally.ignored++
            }
        }

        fun country(key: String, current: String, normalize: (String?) -> String): String {
            if (!settings.has(key)) return current
            return when (val entry = read(settings, key)) {
                is Entry.Str -> {
                    if (entry.value.trim().isEmpty()) {
                        tally.applied++
                        ""
                    } else {
                        val norm = normalize(entry.value)
                        if (norm.isEmpty()) {
                            tally.ignored++
                            ""
                        } else {
                            tally.applied++
                            norm
                        }
                    }
                }
                else -> {
                    tally.ignored++
                    current
                }
            }
        }

        fun engine(key: String, current: EngineMode): EngineMode {
            if (!settings.has(key)) return current
            val entry = read(settings, key)
            if (entry !is Entry.Str) {
                tally.ignored++
                return current
            }
            val raw = entry.value.trim()
            val known = raw.equals("act_hitch", ignoreCase = true) ||
                EngineMode.entries.any { it.id.equals(raw, ignoreCase = true) }
            return if (known) {
                tally.applied++
                EngineMode.fromStored(raw)
            } else {
                tally.ignored++
                EngineMode.XRAY_CF
            }
        }
    }

    private fun readOptionalString(settings: JSONObject, key: String, tally: Tally): String? {
        if (!settings.has(key)) return null
        return when (val entry = read(settings, key)) {
            is Entry.Str -> entry.value
            else -> {
                tally.ignored++
                null
            }
        }
    }

    private fun read(settings: JSONObject, key: String): Entry {
        val obj = settings.optJSONObject(key) ?: return Entry.Bad
        if (!obj.has("type") || !obj.has("value") || obj.isNull("value")) return Entry.Bad
        return when (obj.optString("type")) {
            "bool" -> (obj.get("value") as? Boolean)?.let { Entry.Bool(it) } ?: Entry.Bad
            "int" -> intOf(obj.get("value"))?.let { Entry.IntVal(it) } ?: Entry.Bad
            "string" -> (obj.get("value") as? String)?.let { Entry.Str(it) } ?: Entry.Bad
            else -> Entry.Bad
        }
    }

    private fun intOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE.toLong() }?.toInt()
        is Double -> value.takeIf { it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
        else -> null
    }

    private fun countUnknown(settings: JSONObject, tally: Tally) {
        val keys = settings.keys()
        while (keys.hasNext()) {
            if (keys.next() !in KNOWN) tally.ignored++
        }
    }

    private fun countSplitExtras(split: JSONObject?, tally: Tally) {
        if (split == null) return
        val keys = split.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key != "mode" && key != "packages") tally.ignored++
        }
    }

    private fun mergeRouting(context: Context, current: AppRoutingSettings, split: JSONObject?, tally: Tally): AppRoutingSettings {
        if (split == null) return current
        val mode = if (!split.has("mode")) {
            current.mode
        } else {
            val raw = split.optString("mode")
            val parsed = runCatching { AppRoutingMode.valueOf(raw) }.getOrNull()
            if (parsed == null) {
                tally.ignored++
                null
            } else {
                tally.applied++
                parsed
            }
        }
        if (mode == null) return current
        if (!split.has("packages")) return current.copy(mode = mode)
        val array = split.optJSONArray("packages")
        if (array == null) {
            tally.ignored++
            return current.copy(mode = mode)
        }
        val kept = mutableSetOf<String>()
        repeat(array.length()) { index ->
            val name = array.optString(index).trim()
            if (name.isEmpty()) {
                tally.ignored++
            } else if (packageInstalled(context, name)) {
                kept += name
            } else {
                tally.ignored++
            }
        }
        tally.applied++
        return AppRoutingSettings(mode, kept)
    }

    private fun packageInstalled(context: Context, packageName: String): Boolean = runCatching {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, 0)
        }
    }.isSuccess

    private fun mergeAdvanced(current: AdvancedSettingsData, bag: Bag): AdvancedSettingsData {
        val d = AdvancedSettingsData.DEFAULT
        val p = ADV
        fun req(key: String, now: String, fallback: String) = bag.text("$p$key", now) { raw ->
            raw.trim().take(512).ifBlank { null }
        } ?: fallback
        return AdvancedSettingsData(
            connectionMode = bag.choice("$p" + "connectionMode", current.connectionMode, d.connectionMode, setOf(CONNECTION_MODE_TUNNEL, CONNECTION_MODE_PROXY)),
            primaryAddress = req("primaryAddress", current.primaryAddress, d.primaryAddress),
            primaryPort = bag.num("${p}primaryPort", current.primaryPort, d.primaryPort) { it in 1..65535 },
            primaryMaxSplit = bag.num("${p}primaryMaxSplit", current.primaryMaxSplit, d.primaryMaxSplit) { it in 1..10000 },
            irancellAddress = req("irancellAddress", current.irancellAddress, d.irancellAddress),
            irancellPort = bag.num("${p}irancellPort", current.irancellPort, d.irancellPort) { it in 1..65535 },
            irancellMaxSplit = bag.num("${p}irancellMaxSplit", current.irancellMaxSplit, d.irancellMaxSplit) { it in 1..10000 },
            fallbackAddress = req("fallbackAddress", current.fallbackAddress, d.fallbackAddress),
            fallbackPort = bag.num("${p}fallbackPort", current.fallbackPort, d.fallbackPort) { it in 1..65535 },
            fallbackMaxSplit = bag.num("${p}fallbackMaxSplit", current.fallbackMaxSplit, d.fallbackMaxSplit) { it in 1..10000 },
            telegramRouteEnabled = bag.bool("${p}telegramRouteEnabled", current.telegramRouteEnabled),
            telegramAddress = req("telegramAddress", current.telegramAddress, d.telegramAddress),
            telegramFallbackAddress = req("telegramFallbackAddress", current.telegramFallbackAddress, d.telegramFallbackAddress),
            telegramPort = bag.num("${p}telegramPort", current.telegramPort, d.telegramPort) { it in 1..65535 },
            telegramMaxSplit = bag.num("${p}telegramMaxSplit", current.telegramMaxSplit, d.telegramMaxSplit) { it in 1..10000 },
            telegramFingerprint = req("telegramFingerprint", current.telegramFingerprint, d.telegramFingerprint),
            telegramCipherSuites = bag.text("${p}telegramCipherSuites", current.telegramCipherSuites) { raw ->
                raw.trim().take(512).replace(',', ':').ifBlank { null }
            } ?: d.telegramCipherSuites,
            telegramSessionResumption = bag.bool("${p}telegramSessionResumption", current.telegramSessionResumption),
            outboundProtocol = bag.choice("${p}outboundProtocol", current.outboundProtocol, d.outboundProtocol, setOf("vless", "trojan")),
            transportNetwork = bag.choice("${p}transportNetwork", current.transportNetwork, d.transportNetwork, setOf("ws")),
            transportSecurity = bag.choice("${p}transportSecurity", current.transportSecurity, d.transportSecurity, setOf("tls")),
            trojanPassword = req("trojanPassword", current.trojanPassword, d.trojanPassword),
            tlsSni = req("tlsSni", current.tlsSni, d.tlsSni),
            tlsAlpn = req("tlsAlpn", current.tlsAlpn, d.tlsAlpn),
            tlsFingerprint = req("tlsFingerprint", current.tlsFingerprint, d.tlsFingerprint),
            wsHost = req("wsHost", current.wsHost, d.wsHost),
            wsPath = bag.text("${p}wsPath", current.wsPath) { raw ->
                val clean = raw.trim().take(512).ifBlank { return@text null }
                if (clean.startsWith("/")) clean else "/$clean"
            } ?: d.wsPath,
            finalmaskPacket = req("finalmaskPacket", current.finalmaskPacket, d.finalmaskPacket),
            finalmaskLength = bag.num("${p}finalmaskLength", current.finalmaskLength, d.finalmaskLength) { it in 1..65535 },
            finalmaskDelayMs = bag.num("${p}finalmaskDelayMs", current.finalmaskDelayMs, d.finalmaskDelayMs) { it in 0..60000 },
            muxEnabled = bag.bool("${p}muxEnabled", current.muxEnabled),
            muxConcurrency = bag.num("${p}muxConcurrency", current.muxConcurrency, d.muxConcurrency) { it in 1..1024 },
            iranDirect = bag.bool("${p}iranDirect", current.iranDirect),
            domainStrategy = req("domainStrategy", current.domainStrategy, d.domainStrategy),
            routingDomainStrategy = req("routingDomainStrategy", current.routingDomainStrategy, d.routingDomainStrategy),
            ipv4Only = bag.bool("${p}ipv4Only", current.ipv4Only),
            keepAliveIdleSeconds = bag.num("${p}keepAliveIdleSeconds", current.keepAliveIdleSeconds, d.keepAliveIdleSeconds) { it in 1..7200 },
            keepAliveIntervalSeconds = bag.num("${p}keepAliveIntervalSeconds", current.keepAliveIntervalSeconds, d.keepAliveIntervalSeconds) { it in 1..600 },
            blockUdp443 = bag.bool("${p}blockUdp443", current.blockUdp443),
            socksAddress = req("socksAddress", current.socksAddress, d.socksAddress),
            socksPort = bag.num("${p}socksPort", current.socksPort, d.socksPort) { it in 1..65535 },
            socksUdp = bag.bool("${p}socksUdp", current.socksUdp),
            tunMtu = bag.num("${p}tunMtu", current.tunMtu, d.tunMtu) { it in 576..9000 },
            tunAddress = req("tunAddress", current.tunAddress, d.tunAddress),
            nativeDns = req("nativeDns", current.nativeDns, d.nativeDns),
            dnsResolverUrl = bag.text("${p}dnsResolverUrl", current.dnsResolverUrl) { raw ->
                raw.trim().take(512).takeIf { it.startsWith("https://") && it.contains("/dns-query") }
            } ?: d.dnsResolverUrl,
            tunRoute = bag.text("${p}tunRoute", current.tunRoute, ::routeOrNull) ?: d.tunRoute,
            dnsServeStale = bag.bool("${p}dnsServeStale", current.dnsServeStale),
            tcpFastOpen = bag.bool("${p}tcpFastOpen", current.tcpFastOpen),
            bigBuffers = bag.bool("${p}bigBuffers", current.bigBuffers),
            prewarm = bag.bool("${p}prewarm", current.prewarm),
        ).validated()
    }

    private fun routeOrNull(raw: String): String? {
        val parts = raw.trim().split('/', limit = 2)
        val octets = parts.firstOrNull()?.split('.')?.mapNotNull { it.toIntOrNull() }.orEmpty()
        val prefix = parts.getOrNull(1)?.toIntOrNull()
        if (octets.size != 4 || octets.any { it !in 0..255 } || prefix !in 0..32) return null
        return octets.joinToString(".") + "/" + prefix
    }

    private fun mergePow(current: PowEngineSettings, bag: Bag): PowEngineSettings {
        val d = PowEngineSettings.DEFAULT
        val p = POW
        val transports = buildSet {
            add(PowCoreConfig.OUTER_AUTO)
            addAll(PowCoreConfig.OUTER_LADDER)
        }
        return PowEngineSettings(
            exitCountryCode = bag.country("${p}exit_country", current.exitCountryCode, PowRegions::normalize),
            outerTransport = bag.choice("${p}outer_transport", current.outerTransport, d.outerTransport, transports),
            obfuscationProfile = bag.choice("${p}obfuscation_profile", current.obfuscationProfile, d.obfuscationProfile, PowEngineSettings.OBFUSCATION_PROFILES),
            h2Fragmentation = bag.bool("${p}h2_fragmentation", current.h2Fragmentation),
            scanMode = bag.text("${p}scan_mode", current.scanMode) { raw ->
                val key = raw.trim().lowercase(Locale.US)
                val known = key in PowCoreConfig.SCAN_MODES || key == "fast" || key == "deep"
                if (known) PowCoreConfig.normalizeScanMode(key) else null
            } ?: d.scanMode,
            optimizedMode = bag.bool("${p}optimized_mode", current.optimizedMode),
            firewallProfile = bag.choice("${p}firewall_profile", current.firewallProfile, d.firewallProfile, PowCoreConfig.FIREWALLS),
            linkRecipe = bag.choice("${p}link_recipe", current.linkRecipe, d.linkRecipe, PowCoreConfig.RECIPES),
            chainPsiphon = current.chainPsiphon,
            exitMode = bag.choice("${p}exit_mode", current.exitMode, d.exitMode, PowEngineSettings.EXIT_MODES),
            keyFetch = bag.choice("${p}key_fetch", current.keyFetch, d.keyFetch, PowCoreConfig.KEY_FETCHES),
            psiphonTunnelPool = bag.bool("${p}psiphon_tunnel_pool", current.psiphonTunnelPool),
            quicTuned = bag.bool("${p}quic_tuned", current.quicTuned),
            logcatMirror = bag.bool("${p}adv_logcat_mirror", current.logcatMirror),
            h2Obfuscation = bag.choice("${p}adv_h2_obfuscation", current.h2Obfuscation, d.h2Obfuscation, PowEngineSettings.H2_OBFS_CHOICES),
            tunMtu = bag.num("${p}adv_tun_mtu", current.tunMtu, d.tunMtu) { it == 0 || it in PowEngineSettings.TUN_MTU_MIN..PowEngineSettings.TUN_MTU_MAX },
            h2FragmentMode = bag.choice("${p}adv_h2_fragment_mode", current.h2FragmentMode, d.h2FragmentMode, PowEngineSettings.H2_FRAG_CHOICES),
            socketProtectEnabled = bag.bool("${p}socket_protect_enabled", current.socketProtectEnabled),
            h2Fingerprint = bag.choice("${p}adv_h2_fingerprint", current.h2Fingerprint, d.h2Fingerprint, PowEngineSettings.H2_FP_CHOICES.toSet()),
            masqueIpVersion = bag.choice("${p}adv_masque_ip_version", current.masqueIpVersion, d.masqueIpVersion, PowEngineSettings.MASQUE_IP_CHOICES.toSet()),
        ).validated().also { validated ->
            bag.noteDerivedBool("${p}chain_psiphon", validated.chainPsiphon)
        }
    }

    private fun mergeTor(current: TorEngineSettings, bag: Bag): TorEngineSettings {
        val d = TorEngineSettings.DEFAULT
        val p = TOR
        val socks = bag.num("${p}socks_port", current.socksPort, d.socksPort) { it in 1024..65535 }
        val control = bag.num("${p}control_port", current.controlPort, d.controlPort) { it in 1024..65535 && it != socks }
            .let { port -> if (port == socks) (socks + 1).coerceAtMost(65535) else port }
        return TorEngineSettings(
            bridgeLines = bag.text("${p}bridge_lines", current.bridgeLines) { raw ->
                raw.trim().takeIf { it.length <= 100_000 }
            } ?: d.bridgeLines,
            socksPort = socks,
            controlPort = control,
            fragmentMode = bag.choice("${p}fragment_mode", current.fragmentMode, d.fragmentMode, TorEngineSettings.FRAGMENT_MODES),
            fragmentPacket = bag.text("${p}fragment_packet", current.fragmentPacket) { raw ->
                raw.trim().take(64).ifBlank { null }
            } ?: d.fragmentPacket,
            fragmentLength = bag.num("${p}fragment_length", current.fragmentLength, d.fragmentLength) { it in 1..256 },
            fragmentDelayMs = bag.num("${p}fragment_delay", current.fragmentDelayMs, d.fragmentDelayMs) { it in 0..1000 },
            exitCountryCode = bag.country("${p}exit_country", current.exitCountryCode, TorExitCountry::normalize),
            exitStrict = bag.bool("${p}exit_strict", current.exitStrict),
            fastExit = bag.bool("${p}fast_exit", current.fastExit),
            blockUdp = bag.bool("${p}block_udp", current.blockUdp),
            reducedPadding = bag.bool("${p}reduced_padding", current.reducedPadding),
            singleProcess = bag.bool("${p}single_process", current.singleProcess),
            stageTimeouts = bag.bool("${p}stage_timeouts", current.stageTimeouts),
            watchdog = bag.bool("${p}watchdog", current.watchdog),
            bridgeRefresh = bag.bool("${p}bridge_refresh", current.bridgeRefresh),
            bridgeRefreshUrl = bag.text("${p}bridge_refresh_url", current.bridgeRefreshUrl) { raw ->
                raw.trim().takeIf { it.length <= 2048 }
            } ?: "",
            logcatMirror = bag.bool("${p}logcat_mirror", current.logcatMirror),
            dualGuards = bag.bool("${p}dual_guards", current.dualGuards),
            throughputScoring = bag.bool("${p}throughput_scoring", current.throughputScoring),
            bigBuffers = bag.bool("${p}big_buffers", current.bigBuffers),
            fastRelays = bag.bool("${p}fast_relays", current.fastRelays),
            multiCircuit = bag.bool("${p}multi_circuit", current.multiCircuit),
            slowCircuitSwap = bag.bool("${p}slow_circuit_swap", current.slowCircuitSwap),
            tunMtu = bag.num("${p}tun_mtu", current.tunMtu, 0) { it == 0 || it in TorEngineSettings.MTU_CHOICES },
        ).validated()
    }

    private fun mergeDirect(current: DirectEngineSettings, bag: Bag): DirectEngineSettings {
        val d = DirectEngineSettings.DEFAULT
        val p = DIRECT
        fun lengths(key: String, now: String, fallback: String) = bag.text(key, now) { raw ->
            val cleaned = DirectEngineSettings.cleanList(raw, fallback)
            val normalized = raw.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
            cleaned.takeIf { normalized.isNotEmpty() && cleaned == normalized }
        } ?: fallback
        return DirectEngineSettings(
            googleFronting = bag.bool("${p}googleFronting", current.googleFronting),
            echEnabled = bag.bool("${p}echEnabled", current.echEnabled),
            fragmentEnabled = bag.bool("${p}fragmentEnabled", current.fragmentEnabled),
            variant = bag.choice("${p}variant", current.variant, d.variant, setOf(DirectEngineSettings.VARIANT_A, DirectEngineSettings.VARIANT_B), lower = false),
            helloLengths = lengths("${p}helloLengths", current.helloLengths, d.helloLengths),
            helloDelays = lengths("${p}helloDelays", current.helloDelays, d.helloDelays),
            helloMaxSplit = bag.num("${p}helloMaxSplit", current.helloMaxSplit, d.helloMaxSplit) { it in 0..1000 },
            secondStageEnabled = bag.bool("${p}secondStageEnabled", current.secondStageEnabled),
            secondLengths = lengths("${p}secondLengths", current.secondLengths, d.secondLengths),
            secondDelays = lengths("${p}secondDelays", current.secondDelays, d.secondDelays),
            secondMaxSplit = bag.num("${p}secondMaxSplit", current.secondMaxSplit, d.secondMaxSplit) { it in 0..1000 },
            tcpFragmentEnabled = bag.bool("${p}tcpFragmentEnabled", current.tcpFragmentEnabled),
            tcpLengths = lengths("${p}tcpLengths", current.tcpLengths, d.tcpLengths),
            tcpDelays = lengths("${p}tcpDelays", current.tcpDelays, d.tcpDelays),
            tcpMaxSplit = bag.num("${p}tcpMaxSplit", current.tcpMaxSplit, d.tcpMaxSplit) { it in 0..1000 },
            udpNoise = bag.bool("${p}udpNoise", current.udpNoise),
            blockQuic = bag.bool("${p}blockQuic", current.blockQuic),
            ipv6 = bag.bool("${p}ipv6", current.ipv6),
            iranDirect = bag.bool("${p}iranDirect", current.iranDirect),
            unfilteredDns = bag.text("${p}unfilteredDns", current.unfilteredDns) { raw ->
                val clean = raw.trim()
                clean.takeIf {
                    it.startsWith("https://") || it.startsWith("h2c://") || it.startsWith("tcp://") ||
                        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(it) ||
                        (it.contains(':') && !it.contains('/'))
                }
            } ?: d.unfilteredDns,
            domesticDns = bag.text("${p}domesticDns", current.domesticDns) { raw ->
                raw.trim().ifBlank { null }
            } ?: d.domesticDns,
        ).validated()
    }

    private fun putAdvanced(settings: JSONObject, value: AdvancedSettingsData) {
        val p = ADV
        settings.putTyped("${p}connectionMode", "string", value.connectionMode)
        settings.putTyped("${p}primaryAddress", "string", value.primaryAddress)
        settings.putTyped("${p}primaryPort", "int", value.primaryPort)
        settings.putTyped("${p}primaryMaxSplit", "int", value.primaryMaxSplit)
        settings.putTyped("${p}irancellAddress", "string", value.irancellAddress)
        settings.putTyped("${p}irancellPort", "int", value.irancellPort)
        settings.putTyped("${p}irancellMaxSplit", "int", value.irancellMaxSplit)
        settings.putTyped("${p}fallbackAddress", "string", value.fallbackAddress)
        settings.putTyped("${p}fallbackPort", "int", value.fallbackPort)
        settings.putTyped("${p}fallbackMaxSplit", "int", value.fallbackMaxSplit)
        settings.putTyped("${p}telegramRouteEnabled", "bool", value.telegramRouteEnabled)
        settings.putTyped("${p}telegramAddress", "string", value.telegramAddress)
        settings.putTyped("${p}telegramFallbackAddress", "string", value.telegramFallbackAddress)
        settings.putTyped("${p}telegramPort", "int", value.telegramPort)
        settings.putTyped("${p}telegramMaxSplit", "int", value.telegramMaxSplit)
        settings.putTyped("${p}telegramFingerprint", "string", value.telegramFingerprint)
        settings.putTyped("${p}telegramCipherSuites", "string", value.telegramCipherSuites)
        settings.putTyped("${p}telegramSessionResumption", "bool", value.telegramSessionResumption)
        settings.putTyped("${p}outboundProtocol", "string", value.outboundProtocol)
        settings.putTyped("${p}transportNetwork", "string", value.transportNetwork)
        settings.putTyped("${p}transportSecurity", "string", value.transportSecurity)
        settings.putTyped("${p}trojanPassword", "string", value.trojanPassword)
        settings.putTyped("${p}tlsSni", "string", value.tlsSni)
        settings.putTyped("${p}tlsAlpn", "string", value.tlsAlpn)
        settings.putTyped("${p}tlsFingerprint", "string", value.tlsFingerprint)
        settings.putTyped("${p}wsHost", "string", value.wsHost)
        settings.putTyped("${p}wsPath", "string", value.wsPath)
        settings.putTyped("${p}finalmaskPacket", "string", value.finalmaskPacket)
        settings.putTyped("${p}finalmaskLength", "int", value.finalmaskLength)
        settings.putTyped("${p}finalmaskDelayMs", "int", value.finalmaskDelayMs)
        settings.putTyped("${p}muxEnabled", "bool", value.muxEnabled)
        settings.putTyped("${p}muxConcurrency", "int", value.muxConcurrency)
        settings.putTyped("${p}iranDirect", "bool", value.iranDirect)
        settings.putTyped("${p}domainStrategy", "string", value.domainStrategy)
        settings.putTyped("${p}routingDomainStrategy", "string", value.routingDomainStrategy)
        settings.putTyped("${p}ipv4Only", "bool", value.ipv4Only)
        settings.putTyped("${p}keepAliveIdleSeconds", "int", value.keepAliveIdleSeconds)
        settings.putTyped("${p}keepAliveIntervalSeconds", "int", value.keepAliveIntervalSeconds)
        settings.putTyped("${p}blockUdp443", "bool", value.blockUdp443)
        settings.putTyped("${p}socksAddress", "string", value.socksAddress)
        settings.putTyped("${p}socksPort", "int", value.socksPort)
        settings.putTyped("${p}socksUdp", "bool", value.socksUdp)
        settings.putTyped("${p}tunMtu", "int", value.tunMtu)
        settings.putTyped("${p}tunAddress", "string", value.tunAddress)
        settings.putTyped("${p}nativeDns", "string", value.nativeDns)
        settings.putTyped("${p}dnsResolverUrl", "string", value.dnsResolverUrl)
        settings.putTyped("${p}tunRoute", "string", value.tunRoute)
        settings.putTyped("${p}dnsServeStale", "bool", value.dnsServeStale)
        settings.putTyped("${p}tcpFastOpen", "bool", value.tcpFastOpen)
        settings.putTyped("${p}bigBuffers", "bool", value.bigBuffers)
        settings.putTyped("${p}prewarm", "bool", value.prewarm)
    }

    private fun putPow(settings: JSONObject, value: PowEngineSettings, adaptive: Boolean) {
        val p = POW
        settings.putTyped("${p}exit_country", "string", value.exitCountryCode)
        settings.putTyped("${p}outer_transport", "string", value.outerTransport)
        settings.putTyped("${p}obfuscation_profile", "string", value.obfuscationProfile)
        settings.putTyped("${p}h2_fragmentation", "bool", value.h2Fragmentation)
        settings.putTyped("${p}h2_fragment_adaptive", "bool", adaptive)
        settings.putTyped("${p}scan_mode", "string", value.scanMode)
        settings.putTyped("${p}optimized_mode", "bool", value.optimizedMode)
        settings.putTyped("${p}firewall_profile", "string", value.firewallProfile)
        settings.putTyped("${p}link_recipe", "string", value.linkRecipe)
        settings.putTyped("${p}chain_psiphon", "bool", value.chainPsiphon)
        settings.putTyped("${p}exit_mode", "string", value.exitMode)
        settings.putTyped("${p}key_fetch", "string", value.keyFetch)
        settings.putTyped("${p}psiphon_tunnel_pool", "bool", value.psiphonTunnelPool)
        settings.putTyped("${p}quic_tuned", "bool", value.quicTuned)
        settings.putTyped("${p}adv_logcat_mirror", "bool", value.logcatMirror)
        settings.putTyped("${p}adv_h2_obfuscation", "string", value.h2Obfuscation)
        settings.putTyped("${p}adv_tun_mtu", "int", value.tunMtu)
        settings.putTyped("${p}adv_h2_fragment_mode", "string", value.h2FragmentMode)
        settings.putTyped("${p}socket_protect_enabled", "bool", value.socketProtectEnabled)
        settings.putTyped("${p}adv_h2_fingerprint", "string", value.h2Fingerprint)
        settings.putTyped("${p}adv_masque_ip_version", "string", value.masqueIpVersion)
    }

    private fun putTor(settings: JSONObject, value: TorEngineSettings) {
        val p = TOR
        settings.putTyped("${p}bridge_lines", "string", value.bridgeLines)
        settings.putTyped("${p}socks_port", "int", value.socksPort)
        settings.putTyped("${p}control_port", "int", value.controlPort)
        settings.putTyped("${p}fragment_mode", "string", value.fragmentMode)
        settings.putTyped("${p}fragment_packet", "string", value.fragmentPacket)
        settings.putTyped("${p}fragment_length", "int", value.fragmentLength)
        settings.putTyped("${p}fragment_delay", "int", value.fragmentDelayMs)
        settings.putTyped("${p}exit_country", "string", value.exitCountryCode)
        settings.putTyped("${p}exit_strict", "bool", value.exitStrict)
        settings.putTyped("${p}fast_exit", "bool", value.fastExit)
        settings.putTyped("${p}block_udp", "bool", value.blockUdp)
        settings.putTyped("${p}reduced_padding", "bool", value.reducedPadding)
        settings.putTyped("${p}single_process", "bool", value.singleProcess)
        settings.putTyped("${p}stage_timeouts", "bool", value.stageTimeouts)
        settings.putTyped("${p}watchdog", "bool", value.watchdog)
        settings.putTyped("${p}bridge_refresh", "bool", value.bridgeRefresh)
        settings.putTyped("${p}bridge_refresh_url", "string", value.bridgeRefreshUrl)
        settings.putTyped("${p}logcat_mirror", "bool", value.logcatMirror)
        settings.putTyped("${p}dual_guards", "bool", value.dualGuards)
        settings.putTyped("${p}throughput_scoring", "bool", value.throughputScoring)
        settings.putTyped("${p}big_buffers", "bool", value.bigBuffers)
        settings.putTyped("${p}fast_relays", "bool", value.fastRelays)
        settings.putTyped("${p}multi_circuit", "bool", value.multiCircuit)
        settings.putTyped("${p}slow_circuit_swap", "bool", value.slowCircuitSwap)
        settings.putTyped("${p}tun_mtu", "int", value.tunMtu)
    }

    private fun putDirect(settings: JSONObject, value: DirectEngineSettings) {
        val p = DIRECT
        settings.putTyped("${p}googleFronting", "bool", value.googleFronting)
        settings.putTyped("${p}echEnabled", "bool", value.echEnabled)
        settings.putTyped("${p}fragmentEnabled", "bool", value.fragmentEnabled)
        settings.putTyped("${p}variant", "string", value.variant)
        settings.putTyped("${p}helloLengths", "string", value.helloLengths)
        settings.putTyped("${p}helloDelays", "string", value.helloDelays)
        settings.putTyped("${p}helloMaxSplit", "int", value.helloMaxSplit)
        settings.putTyped("${p}secondStageEnabled", "bool", value.secondStageEnabled)
        settings.putTyped("${p}secondLengths", "string", value.secondLengths)
        settings.putTyped("${p}secondDelays", "string", value.secondDelays)
        settings.putTyped("${p}secondMaxSplit", "int", value.secondMaxSplit)
        settings.putTyped("${p}tcpFragmentEnabled", "bool", value.tcpFragmentEnabled)
        settings.putTyped("${p}tcpLengths", "string", value.tcpLengths)
        settings.putTyped("${p}tcpDelays", "string", value.tcpDelays)
        settings.putTyped("${p}tcpMaxSplit", "int", value.tcpMaxSplit)
        settings.putTyped("${p}udpNoise", "bool", value.udpNoise)
        settings.putTyped("${p}blockQuic", "bool", value.blockQuic)
        settings.putTyped("${p}ipv6", "bool", value.ipv6)
        settings.putTyped("${p}iranDirect", "bool", value.iranDirect)
        settings.putTyped("${p}unfilteredDns", "string", value.unfilteredDns)
        settings.putTyped("${p}domesticDns", "string", value.domesticDns)
    }

    private fun JSONObject.putTyped(key: String, type: String, value: Any) {
        put(key, JSONObject().put("type", type).put("value", value))
    }

    private class Sni(val url: String, val workers: Int, val timeout: Int) {
        fun write(nextUrl: String, nextWorkers: Int, nextTimeout: Int) {
            prefs.edit()
                .putString(SniMakerController.KEY_URL, nextUrl)
                .putInt(SniMakerController.KEY_WORKERS, nextWorkers)
                .putInt(SniMakerController.KEY_TIMEOUT, nextTimeout)
                .apply()
        }

        lateinit var prefs: android.content.SharedPreferences
    }

    private fun sniPrefs(context: Context): Sni {
        val prefs = context.getSharedPreferences(SniMakerController.PREFERENCES_NAME, Context.MODE_PRIVATE)
        return Sni(
            url = prefs.getString(SniMakerController.KEY_URL, SniMakerController.DEFAULT_SUBSCRIPTION_URL)
                ?: SniMakerController.DEFAULT_SUBSCRIPTION_URL,
            workers = prefs.getInt(SniMakerController.KEY_WORKERS, SniMakerController.DEFAULT_WORKERS),
            timeout = prefs.getInt(SniMakerController.KEY_TIMEOUT, SniMakerController.DEFAULT_TIMEOUT_MS),
        ).also { it.prefs = prefs }
    }

    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val ADV = "advanced_runtime_settings/"
    private const val POW = "uac_pow_engine_v1/"
    private const val TOR = "tor_engine_v1/"
    private const val DIRECT = "engine_direct_v2/"
    private const val POW_PREFS = "uac_pow_engine_v1"
    private const val AI_PREFS = "uac_ai_clean_hop"
    private const val AI_ENABLED = "enabled"
    private const val THEME = "uac_app_theme/theme"
    private const val LANGUAGE = "app_preferences/language"
    private const val ENGINE = "connection_engine_v1/engine_mode"
    private const val KILL = "network_guard_v1/kill_switch"
    private const val AUTO = "network_guard_v1/auto_connect"
    private const val LAN = "network_guard_v1/lan_share"
    private const val DNS = "network_guard_v1/dns_sinkhole"
    private const val WEBRTC = "network_guard_v1/webrtc_block"
    private const val QUIC = "network_guard_v1/quic_block_v2"
    private const val IPV6 = "network_guard_v1/ipv6_block"
    private const val IRAN = "network_guard_v1/iran_direct"
    private const val DOMAINS = "direct_domain_whitelist_v1/domains"
    private const val GPS = "gps_spoof_v1/enabled"
    private const val PROFILES = "uac_proxy_profiles_v2/profiles"
    private const val SELECTED = "uac_proxy_profiles_v2/selected"
    private const val ADAPTIVE = "uac_pow_engine_v1/h2_fragment_adaptive"
    private const val SNI_URL = "sni_config_maker/subscription_url"
    private const val SNI_WORKERS = "sni_config_maker/test_workers"
    private const val SNI_TIMEOUT = "sni_config_maker/test_timeout_ms"
    private const val AI = "uac_ai_clean_hop/enabled"
    private const val PANEL_STATUS = "uac_connect_panels/status"
    private const val PANEL_STEPS = "uac_connect_panels/steps"

    private val KNOWN = setOf(
        THEME, LANGUAGE, ENGINE, KILL, AUTO, LAN, DNS, WEBRTC, QUIC, IPV6, IRAN, DOMAINS, GPS,
        PROFILES, SELECTED, ADAPTIVE, SNI_URL, SNI_WORKERS, SNI_TIMEOUT, AI, PANEL_STATUS, PANEL_STEPS,
        "${ADV}connectionMode", "${ADV}primaryAddress", "${ADV}primaryPort", "${ADV}primaryMaxSplit",
        "${ADV}irancellAddress", "${ADV}irancellPort", "${ADV}irancellMaxSplit",
        "${ADV}fallbackAddress", "${ADV}fallbackPort", "${ADV}fallbackMaxSplit",
        "${ADV}telegramRouteEnabled", "${ADV}telegramAddress", "${ADV}telegramFallbackAddress",
        "${ADV}telegramPort", "${ADV}telegramMaxSplit", "${ADV}telegramFingerprint",
        "${ADV}telegramCipherSuites", "${ADV}telegramSessionResumption",
        "${ADV}outboundProtocol", "${ADV}transportNetwork", "${ADV}transportSecurity",
        "${ADV}trojanPassword", "${ADV}tlsSni", "${ADV}tlsAlpn", "${ADV}tlsFingerprint",
        "${ADV}wsHost", "${ADV}wsPath", "${ADV}finalmaskPacket", "${ADV}finalmaskLength",
        "${ADV}finalmaskDelayMs", "${ADV}muxEnabled", "${ADV}muxConcurrency", "${ADV}iranDirect",
        "${ADV}domainStrategy", "${ADV}routingDomainStrategy", "${ADV}ipv4Only",
        "${ADV}keepAliveIdleSeconds", "${ADV}keepAliveIntervalSeconds", "${ADV}blockUdp443",
        "${ADV}socksAddress", "${ADV}socksPort", "${ADV}socksUdp", "${ADV}tunMtu",
        "${ADV}tunAddress", "${ADV}nativeDns", "${ADV}dnsResolverUrl", "${ADV}tunRoute",
        "${ADV}dnsServeStale", "${ADV}tcpFastOpen", "${ADV}bigBuffers", "${ADV}prewarm",
        "${POW}exit_country", "${POW}outer_transport", "${POW}obfuscation_profile",
        "${POW}h2_fragmentation", "${POW}scan_mode", "${POW}optimized_mode",
        "${POW}firewall_profile", "${POW}link_recipe", "${POW}chain_psiphon", "${POW}exit_mode",
        "${POW}key_fetch", "${POW}psiphon_tunnel_pool", "${POW}quic_tuned",
        "${POW}adv_logcat_mirror", "${POW}adv_h2_obfuscation", "${POW}adv_tun_mtu",
        "${POW}adv_h2_fragment_mode", "${POW}socket_protect_enabled",
        "${POW}adv_h2_fingerprint", "${POW}adv_masque_ip_version",
        "${TOR}bridge_lines", "${TOR}socks_port", "${TOR}control_port", "${TOR}fragment_mode",
        "${TOR}fragment_packet", "${TOR}fragment_length", "${TOR}fragment_delay",
        "${TOR}exit_country", "${TOR}exit_strict", "${TOR}fast_exit", "${TOR}block_udp",
        "${TOR}reduced_padding", "${TOR}single_process", "${TOR}stage_timeouts", "${TOR}watchdog",
        "${TOR}bridge_refresh", "${TOR}bridge_refresh_url", "${TOR}logcat_mirror",
        "${TOR}dual_guards", "${TOR}throughput_scoring", "${TOR}big_buffers", "${TOR}fast_relays",
        "${TOR}multi_circuit", "${TOR}slow_circuit_swap", "${TOR}tun_mtu",
        "${DIRECT}googleFronting", "${DIRECT}echEnabled", "${DIRECT}fragmentEnabled",
        "${DIRECT}variant", "${DIRECT}helloLengths", "${DIRECT}helloDelays", "${DIRECT}helloMaxSplit",
        "${DIRECT}secondStageEnabled", "${DIRECT}secondLengths", "${DIRECT}secondDelays",
        "${DIRECT}secondMaxSplit", "${DIRECT}tcpFragmentEnabled", "${DIRECT}tcpLengths",
        "${DIRECT}tcpDelays", "${DIRECT}tcpMaxSplit", "${DIRECT}udpNoise", "${DIRECT}blockQuic",
        "${DIRECT}ipv6", "${DIRECT}iranDirect", "${DIRECT}unfilteredDns", "${DIRECT}domesticDns",
    )
}
