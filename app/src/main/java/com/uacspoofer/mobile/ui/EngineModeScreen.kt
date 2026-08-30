package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineMode
import com.uacspoofer.mobile.engine.EngineModeChangeResult
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.engine.tor.TorDaemon
import com.uacspoofer.mobile.engine.tor.TorEngineSettings
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.WebTunnelBridgeParser
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun EngineModeScreen(
    onBackClick: () -> Unit,
    onReconnect: () -> Unit,
) {
    val context = LocalContext.current
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val torStore = remember(context) { TorEngineStore.get(context) }
    val daemon = remember(context) { TorDaemon(context) }
    val mode by engineStore.mode.collectAsStateWithLifecycle()
    val connectionState by ConnectionStateStore.state.collectAsStateWithLifecycle()
    var settings by remember { mutableStateOf(torStore.snapshot()) }
    var notice by remember {
        mutableStateOf("Engine changes apply on the next connection")
    }
    var noticeIsError by remember { mutableStateOf(false) }
    var pendingCutover by remember { mutableStateOf<EngineMode?>(null) }
    var cutoverInProgress by remember { mutableStateOf(false) }
    val parsedBridges = remember(settings.bridgeLines) { WebTunnelBridgeParser.parseAll(settings.bridgeLines) }
    val ignoredBridgeLines = remember(settings.bridgeLines) {
        settings.bridgeLines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .count() - parsedBridges.size
    }
    val torBinaryReady = daemon.locateTorBinary() != null
    val pluginReady = daemon.locateWebTunnelPlugin() != null
    val accent = UacColors.DisconnectedBlue

    fun showNotice(text: String, error: Boolean = false) {
        notice = text
        noticeIsError = error
    }

    fun persistTorSettings(): Boolean {
        settings = torStore.save(settings)
        return true
    }

    fun applyMode(target: EngineMode): Boolean {
        persistTorSettings()
        return when (engineStore.setMode(target)) {
            EngineModeChangeResult.APPLIED -> {
                showNotice(
                    if (target.isTor) {
                        "Tor / WebTunnel selected — Xray / Cloudflare stays off"
                    } else {
                        "Xray / Cloudflare selected — Tor / WebTunnel stays off"
                    },
                )
                true
            }
            EngineModeChangeResult.BLOCKED_WHILE_ACTIVE -> {
                showNotice("Disconnect first, or confirm a cut-over", error = true)
                false
            }
        }
    }

    LaunchedEffect(cutoverInProgress, pendingCutover) {
        val target = pendingCutover ?: return@LaunchedEffect
        if (!cutoverInProgress) return@LaunchedEffect
        persistTorSettings()
        val state = ConnectionStateStore.state.value
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            ConnectionStateStore.tryBeginDisconnect()
            runCatching { VpnController.stop(context) }
        }
        val settled = withTimeoutOrNull(15_000L) {
            ConnectionStateStore.state.first { canChangeEngineMode(it) }
        }
        if (settled == null) {
            cutoverInProgress = false
            pendingCutover = null
            showNotice("Disconnect timed out — engine was not changed", error = true)
            return@LaunchedEffect
        }
        val applied = applyMode(target)
        cutoverInProgress = false
        pendingCutover = null
        if (applied) {
            delay(250)
            onReconnect()
        }
    }

    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("Connection engine", "موتور اتصال"),
                subtitle = homeText(
                    "Xray and Tor never share a session",
                    "`Xray` و `Tor` هیچ‌وقت همزمان اجرا نمی‌شن",
                ),
                icon = Icons.Outlined.Hub,
                accent = accent,
                onMenuClick = onBackClick,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
            )
        },
    ) {
            item {
                EngineSelectorCard(
                    mode = mode,
                    enabled = !cutoverInProgress,
                    onSelect = { target ->
                        if (target == mode) return@EngineSelectorCard
                        when (connectionState) {
                            ConnectionState.DISCONNECTED, ConnectionState.ERROR -> applyMode(target)
                            ConnectionState.DISCONNECTING -> showNotice(
                                "Wait until disconnect finishes, then switch",
                                error = true,
                            )
                            ConnectionState.CONNECTING, ConnectionState.CONNECTED -> pendingCutover = target
                        }
                    },
                )
            }
            if (!torBinaryReady) {
                item {
                    EngineNoticeCard(
                        text = homeText(
                            "Tor runtime is not bundled yet. Connect in this engine fails until libtor.so is present.",
                            "باینری `Tor` هنوز داخل برنامه نیست. تا وقتی `libtor.so` نباشد، اتصال این موتور انجام نمی‌شود.",
                        ),
                        error = true,
                    )
                }
            }
            if (mode.isTor && parsedBridges.isNotEmpty() && !pluginReady) {
                item {
                    EngineNoticeCard(
                        text = homeText(
                            "WebTunnel plugin is missing. Classic Tor can start without bridges; pasted bridges need libwebtunnel.so.",
                            "پلاگین `WebTunnel` پیدا نشد. `Tor` بدون بریج ممکن است بالا بیاید؛ بریج‌های چسبانده‌شده به `libwebtunnel.so` نیاز دارند.",
                        ),
                        error = true,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToolCardBrush, ToolCardShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SectionLabel(homeText("WebTunnel bridges", "بریج‌های `WebTunnel`"))
                    Text(
                        homeText(
                            "Paste webtunnel lines, or leave empty to try OnionHop's built-in WebTunnel list. vless / xhttp configs are not bridges. Classic Tor without bridges is not used.",
                            "خط‌های `webtunnel` را بچسبان، یا خالی بگذار تا لیست داخلی `OnionHop` امتحان شود. کانفیگ `vless` / `xhttp` بریج نیست. `Classic Tor` بدون بریج استفاده نمی‌شود.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    OutlinedTextField(
                        value = settings.bridgeLines,
                        onValueChange = { settings = settings.copy(bridgeLines = it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp),
                        placeholder = {
                            Text(
                                "webtunnel 192.0.2.10:443 url=https://example.com/path",
                                color = UacColors.TextSecondary.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        ),
                        colors = engineFieldColors(),
                    )
                    Text(
                        buildBridgeSummary(parsedBridges.size, ignoredBridgeLines),
                        color = if (ignoredBridgeLines > 0) UacColors.ErrorRed else accent,
                        fontSize = 12.sp,
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToolCardBrush, ToolCardShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel(homeText("WebTunnel TLS hop", "پرش TLS در `WebTunnel`"))
                    Text(
                        homeText(
                            "Fragment applies only to the HTTPS hop, never to inner Tor traffic.",
                            " `fragment` فقط روی پرش `HTTPS` اعمال می‌شود، نه روی ترافیک داخلی `Tor`.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            homeText("Fragment hop", "تکه کردن پرش"),
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Switch(
                            checked = settings.fragmentEnabled,
                            onCheckedChange = { settings = settings.copy(fragmentEnabled = it) },
                        )
                    }
                    if (settings.fragmentEnabled) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            EngineMiniField(
                                label = homeText("Packet", "پکت"),
                                value = settings.fragmentPacket,
                                onChange = { settings = settings.copy(fragmentPacket = it) },
                                modifier = Modifier.weight(1.2f),
                            )
                            EngineMiniField(
                                label = homeText("Length", "طول"),
                                value = settings.fragmentLength.toString(),
                                onChange = { raw ->
                                    val parsed = raw.toIntOrNull()
                                    settings = when {
                                        parsed != null -> settings.copy(fragmentLength = parsed)
                                        raw.isBlank() -> settings.copy(fragmentLength = 1)
                                        else -> settings
                                    }
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.8f),
                            )
                            EngineMiniField(
                                label = homeText("Delay ms", "تاخیر `ms`"),
                                value = settings.fragmentDelayMs.toString(),
                                onChange = { raw ->
                                    val parsed = raw.toIntOrNull()
                                    settings = when {
                                        parsed != null -> settings.copy(fragmentDelayMs = parsed)
                                        raw.isBlank() -> settings.copy(fragmentDelayMs = 0)
                                        else -> settings
                                    }
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.9f),
                            )
                        }
                    }
                }
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ToolCardBrush, ToolCardShape)
                        .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SectionLabel(homeText("Internal Tor SOCKS", "`SOCKS` داخلی `Tor`"))
                    Text(
                        homeText(
                            "Tor always listens on 127.0.0.1:${TorEngineSettings.SOCKS_PORT}. That is an inner hop, not Connection mode.",
                            "موتور `Tor` همیشه روی `127.0.0.1:${TorEngineSettings.SOCKS_PORT}` گوش می‌دهد. این مسیر داخلی است، نه حالت اتصال.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    Text(
                        homeText(
                            "Tunnel = device VPN into that SOCKS. Proxy = only that SOCKS, no VPN.",
                            "حالت `Tunnel` یعنی VPN دستگاه به همین `SOCKS`. حالت `Proxy` یعنی فقط همان `SOCKS`، بدون VPN.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            item {
                Text(
                    text = homeText(notice, notice.localizeEngineNotice()),
                    color = if (noticeIsError) UacColors.ErrorRed else accent,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            (if (noticeIsError) UacColors.ErrorRed else accent).copy(alpha = 0.08f),
                            RoundedCornerShape(12.dp),
                        )
                        .border(
                            1.dp,
                            (if (noticeIsError) UacColors.ErrorRed else accent).copy(alpha = 0.2f),
                            RoundedCornerShape(12.dp),
                        )
                        .padding(12.dp),
                )
            }
            item {
                Button(
                    onClick = {
                        persistTorSettings()
                        showNotice("Tor settings saved — used on the next Tor connection")
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text(homeText("Save Tor settings", "ذخیره تنظیمات `Tor`"), fontWeight = FontWeight.SemiBold)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }

    pendingCutover?.let { target ->
        if (!cutoverInProgress) {
            AlertDialog(
                onDismissRequest = { pendingCutover = null },
                title = {
                    Text(homeText("Switch engine?", "موتور عوض شود؟"))
                },
                text = {
                    Text(
                        homeText(
                            "The current session will drop, then ${target.englishTitle()} starts. Xray and Tor never run together.",
                            "اتصال فعلی قطع می‌شود و بعد ${target.persianTitle()} شروع می‌شود. `Xray` و `Tor` همزمان اجرا نمی‌شوند.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            cutoverInProgress = true
                        },
                    ) {
                        Text(homeText("Disconnect and switch", "قطع و تعویض"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { pendingCutover = null }) {
                        Text(homeText("Cancel", "انصراف"))
                    }
                },
            )
        }
    }
}

@Composable
private fun EngineSelectorCard(
    mode: EngineMode,
    enabled: Boolean,
    onSelect: (EngineMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionLabel(homeText("Active engine", "موتور فعال"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EngineModeButton(
                label = homeText("Xray · Cloudflare", "`Xray` · `Cloudflare`"),
                selected = mode.isXray,
                enabled = enabled,
                onClick = { onSelect(EngineMode.XRAY_CF) },
                modifier = Modifier.weight(1f),
            )
            EngineModeButton(
                label = homeText("Tor · WebTunnel", "`Tor` · `WebTunnel`"),
                selected = mode.isTor,
                enabled = enabled,
                onClick = { onSelect(EngineMode.TOR_WEBTUNNEL) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            if (mode.isTor) {
                homeText(
                    "Connect uses Tor / WebTunnel only. Cloudflare rescue and adaptive CF IPs stay off.",
                    "اتصال فقط از `Tor` / `WebTunnel` می‌رود. نجات `Cloudflare` و IPهای تطبیقی خاموش می‌مانند.",
                )
            } else {
                homeText(
                    "Connect uses today’s Xray / Cloudflare path. Tor daemon stays stopped.",
                    "اتصال همان مسیر فعلی `Xray` / `Cloudflare` است. فرآیند `Tor` روشن نمی‌شود.",
                )
            },
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun EngineModeButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = UacColors.DisconnectedBlue),
        ) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EngineNoticeCard(text: String, error: Boolean) {
    val color = if (error) UacColors.ErrorRed else UacColors.DisconnectedBlue
    Text(
        text = text,
        color = color,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), ToolCardShape)
            .border(1.dp, color.copy(alpha = 0.24f), ToolCardShape)
            .padding(14.dp),
    )
}

@Composable
private fun EngineMiniField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = engineFieldColors(),
    )
}

@Composable
private fun engineFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = UacColors.DisconnectedBlue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    focusedLabelColor = UacColors.TextSecondary,
    unfocusedLabelColor = UacColors.TextSecondary,
    cursorColor = UacColors.DisconnectedBlue,
)

@Composable
private fun buildBridgeSummary(valid: Int, ignored: Int): String = when {
    valid == 0 && ignored == 0 -> homeText(
        "Empty list — OnionHop built-in WebTunnel bridges will be tried",
        "لیست خالی — بریج‌های داخلی `WebTunnel` از `OnionHop` امتحان می‌شوند",
    )
    ignored > 0 -> homeText(
        "$valid WebTunnel bridge(s) ready · $ignored line(s) ignored",
        "$valid بریج `WebTunnel` آماده · $ignored خط نادیده گرفته شد",
    )
    else -> homeText(
        "$valid WebTunnel bridge(s) ready",
        "$valid بریج `WebTunnel` آماده است",
    )
}

private fun EngineMode.englishTitle(): String = when (this) {
    EngineMode.XRAY_CF -> "Xray / Cloudflare"
    EngineMode.TOR_WEBTUNNEL -> "Tor / WebTunnel"
}

private fun EngineMode.persianTitle(): String = when (this) {
    EngineMode.XRAY_CF -> "`Xray` / `Cloudflare`"
    EngineMode.TOR_WEBTUNNEL -> "`Tor` / `WebTunnel`"
}

private fun String.localizeEngineNotice(): String = when (this) {
    "Engine changes apply on the next connection" ->
        "تغییر موتور از اتصال بعدی اعمال می‌شود"
    "Tor / WebTunnel selected — Xray / Cloudflare stays off" ->
        "`Tor` / `WebTunnel` انتخاب شد — `Xray` / `Cloudflare` خاموش می‌ماند"
    "Xray / Cloudflare selected — Tor / WebTunnel stays off" ->
        "`Xray` / `Cloudflare` انتخاب شد — `Tor` / `WebTunnel` خاموش می‌ماند"
    "Disconnect first, or confirm a cut-over" ->
        "اول قطع کن، یا تعویض با قطع اتصال را تایید کن"
    "Wait until disconnect finishes, then switch" ->
        "صبر کن قطع تمام شود، بعد موتور را عوض کن"
    "Disconnect timed out — engine was not changed" ->
        "قطع اتصال زمان‌بر شد — موتور عوض نشد"
    "Tor settings saved — used on the next Tor connection" ->
        "تنظیمات `Tor` ذخیره شد — در اتصال بعدی `Tor` استفاده می‌شود"
    else -> this
}
