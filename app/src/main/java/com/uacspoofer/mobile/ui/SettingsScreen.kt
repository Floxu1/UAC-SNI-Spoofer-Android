package com.uacspoofer.mobile.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.location.GpsSpoofRuntime
import com.uacspoofer.mobile.location.GpsSpoofStore
import com.uacspoofer.mobile.location.GpsSpoofTarget
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.AutoConnectCoordinator
import com.uacspoofer.mobile.vpn.AutoConnectOrigin
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import com.uacspoofer.mobile.vpn.LanSharePolicy
import com.uacspoofer.mobile.vpn.LanShareStatus
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import com.uacspoofer.mobile.vpn.UacQuickSettingsTileService

private val SettingsGroupShape = RoundedCornerShape(14.dp)
private val SettingsGroupColor = Color(0xE6121C28)
private val SettingsHairlineColor = Color.White.copy(alpha = 0.07f)

@Composable
internal fun SettingsScreen(
    onMenuClick: () -> Unit,
    onAdvancedSettingsClick: () -> Unit,
    onPowSettingsClick: () -> Unit,
    onTorSettingsClick: () -> Unit,
) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineStore.mode.collectAsStateWithLifecycle()
    val localizedTextStyle = homeLocalizedTextStyle()
    var tileNotice by remember { mutableStateOf<String?>(null) }
    val accent = UacColors.DisconnectedBlue
    val tileAddedMessage = homeText("Tile added successfully", "دکمه به پنل اضافه شد")
    val tileAlreadyAddedMessage = homeText("Tile is already in Quick Settings", "دکمه از قبل داخل پنل هست")
    val tileNotAddedMessage = homeText("Tile was not added", "دکمه اضافه نشد")
    val tileManualMessage = homeText(
        "Open Quick Settings edit mode and drag UAC SNI Spoofer into the panel",
        "ویرایش پنل تنظیمات سریع رو باز کن و UAC SNI Spoofer رو به پنل بکش",
    )
    val tileErrorMessage = homeText("Could not request the tile", "درخواست افزودن دکمه انجام نشد")
    val diagnosticsOpen = remember { mutableStateOf(false) }
    val secretTaps = remember { mutableIntStateOf(0) }
    val lastSecretTapAt = remember { mutableLongStateOf(0L) }

    if (diagnosticsOpen.value) {
        RuntimeDiagnosticsScreen(onClose = { diagnosticsOpen.value = false })
        return
    }

    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
        ToolPageScaffold(
            accent = accent,
            verticalSpacing = 16.dp,
            header = {
                ToolPageHeader(
                    title = homeText("Settings", "تنظیمات"),
                    subtitle = homeText("App and connection preferences", "تنظیمات برنامه و اتصال"),
                    icon = Icons.Outlined.Settings,
                    accent = accent,
                    onMenuClick = onMenuClick,
                )
            },
        ) {
            item {
                SettingsGroup(homeText("Connection & Network Settings", "تنظیمات اتصال و شبکه")) {
                    AutoConnectSettingsRow(showDivider = true)
                    LanShareSettingsRow(showDivider = true)
                    QuicBlockSettingsRow(showDivider = true)
                    TrafficUsageSettingsRow(showDivider = false)
                }
            }
            item {
                SettingsGroup(homeText("Privacy & Security", "حریم خصوصی و امنیت")) {
                    KillSwitchSettingsRow(showDivider = true)
                    GpsSpoofSettingsRow(showDivider = true)
                    DnsSinkholeSettingsRow(showDivider = true)
                    WebRtcBlockSettingsRow(showDivider = true)
                    Ipv6BlockSettingsRow(showDivider = false)
                }
            }
            item {
                SettingsGroup(homeText("More", "سایر")) {
                    if (engineMode.isPow) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Hub,
                            title = homeText("UAC PoW", "UAC PoW"),
                            summary = homeText("Outer hop and MASQUE", "لایه بیرونی و MASQUE"),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onPowSettingsClick,
                        )
                    }
                    if (engineMode.isTor) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Shield,
                            title = homeText("Tor", "Tor"),
                            summary = homeText(
                                "WebTunnel bridges and TLS hop",
                                "بریج‌های WebTunnel و پرش TLS",
                            ),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onTorSettingsClick,
                        )
                    }
                    if (engineMode.isXray) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Tune,
                            title = homeText("Advanced Settings", "تنظیمات پیشرفته"),
                            summary = homeText(
                                "Connection mode, DNS, TUN and transport",
                                "حالت اتصال، DNS، TUN و انتقال",
                            ),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onAdvancedSettingsClick,
                        )
                    }
                    QuickSettingsTileRow(
                        notice = tileNotice,
                        showDivider = false,
                        onAdd = {
                            requestQuickSettingsTile(context) { result ->
                                tileNotice = when (result) {
                                    TileRequestResult.ADDED -> tileAddedMessage
                                    TileRequestResult.ALREADY_ADDED -> tileAlreadyAddedMessage
                                    TileRequestResult.NOT_ADDED -> tileNotAddedMessage
                                    TileRequestResult.ADD_MANUALLY -> tileManualMessage
                                    TileRequestResult.ERROR -> tileErrorMessage
                                }
                            }
                        },
                    )
                }
            }
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(168.dp)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastSecretTapAt.longValue < 500L) {
                                    secretTaps.intValue += 1
                                } else {
                                    secretTaps.intValue = 1
                                }
                                lastSecretTapAt.longValue = now
                                if (secretTaps.intValue >= 3) {
                                    secretTaps.intValue = 0
                                    diagnosticsOpen.value = true
                                }
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun KillSwitchSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val blocking by NetworkGuardStore.blocking.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val proxyMode = advanced.connectionMode == CONNECTION_MODE_PROXY
    val summary = when {
        proxyMode -> homeText("Tunnel mode only", "فقط حالت تونل")
        blocking -> homeText("Blocking leaks", "نشت مسدود است")
        settings.killSwitch && connection == ConnectionState.CONNECTED ->
            homeText("Armed", "فعال")
        settings.killSwitch -> homeText("On", "روشن")
        else -> homeText("Off", "خاموش")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.Block,
        title = homeText("Kill switch", "کلید قطع اضطراری"),
        summary = summary,
        enabled = settings.killSwitch,
        summaryColor = when {
            blocking -> UacColors.DisconnectingAmber
            settings.killSwitch -> UacColors.ConnectedGreen
            else -> UacColors.TextSecondary
        },
        help = homeText(
            "If the VPN drops, other apps stay offline until you reconnect or turn this off. Tunnel mode only.",
            "اگر VPN قطع شود، بقیه برنامه‌ها آفلاین می‌مانند تا دوباره وصل شوی یا این گزینه را خاموش کنی. فقط حالت تونل.",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setKillSwitch(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun AutoConnectSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    SettingsToggleRow(
        icon = Icons.Outlined.Bolt,
        title = homeText("Auto connect", "اتصال خودکار"),
        summary = if (settings.autoConnect) homeText("On", "روشن") else homeText("Off", "خاموش"),
        enabled = settings.autoConnect,
        help = homeText(
            "Connects after reboot and when the app opens, if VPN permission is already granted.",
            "بعد از روشن شدن گوشی و باز شدن برنامه، اگر مجوز VPN از قبل داده شده باشد، خودش وصل می‌شود.",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setAutoConnect(checked)
            if (checked) {
                AutoConnectCoordinator.ensureWatching(context)
                AutoConnectCoordinator.tryStart(context, AutoConnectOrigin.SETTING)
            }
        },
    )
}

@Composable
private fun LanShareSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val endpoint by LanShareStatus.endpoint.collectAsStateWithLifecycle()
    val ip = endpoint.address?.takeIf { endpoint.listening && it.isNotBlank() }
    val port = if (endpoint.listening) endpoint.port else LanSharePolicy.PORT
    val ready = ip != null
    val summary = when {
        !settings.lanShare -> homeText("Off", "خاموش")
        connection != ConnectionState.CONNECTED -> homeText("After connect", "بعد از وصل")
        ready -> "$ip:$port"
        endpoint.detail == "no lan" -> homeText("Needs Wi‑Fi", "وای‌فای لازم است")
        else -> homeText("Starting…", "در حال شروع…")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.WifiTethering,
        title = homeText("LAN sharing", "اشتراک در شبکه"),
        summary = summary,
        enabled = settings.lanShare,
        help = homeText(
            "On the other device, set SOCKS5 to this IP and port. Same Wi‑Fi is enough — hotspot is optional. Trusted networks only, no password.",
            "روی دستگاه دیگر، پروکسی SOCKS5 را روی همین IP و پورت بگذار. همان وای‌فای کافی است — هات‌اسپات لازم نیست. فقط شبکه مورد اعتماد، بدون رمز.",
        ),
        extra = if (settings.lanShare) {
            {
                LanShareEndpointPanel(
                    ip = ip,
                    port = port,
                    connected = connection == ConnectionState.CONNECTED,
                    needsWifi = endpoint.detail == "no lan",
                )
            }
        } else null,
        autoExpand = settings.lanShare,
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setLanShare(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun QuicBlockSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val proxyMode = advanced.connectionMode == CONNECTION_MODE_PROXY
    val summary = when {
        proxyMode -> homeText("Tunnel mode only", "فقط حالت تونل")
        settings.quicBlock -> homeText("On", "روشن")
        else -> homeText("Off", "خاموش")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.Speed,
        title = homeText("QUIC Blocker", "مسدودساز QUIC"),
        summary = summary,
        enabled = settings.quicBlock,
        help = homeText(
            "Blocks UDP 443 so apps like YouTube, Instagram, and browsers fall back to TCP. That often fixes slow or stuck video loading.",
            "ترافیک UDP 443 را مسدود می‌کند. این کار برنامه‌هایی مثل یوتیوب، اینستاگرام و مرورگرها را مجبور به استفاده از TCP کرده و مشکل کندی و لودینگ ویدیوها را برطرف می‌کند.",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setQuicBlock(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun TrafficUsageSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val monthly by remember(context) { MonthlyTrafficStore.get(context) }.usage.collectAsStateWithLifecycle()
    val monthTotal = monthlyTrafficLabel(monthly.totalBytes)
    SettingsToggleRow(
        icon = Icons.Outlined.Schedule,
        title = homeText("Traffic usage", "نمایش ترافیک مصرفی"),
        summary = homeText("This month · $monthTotal", "این ماه · $monthTotal"),
        enabled = false,
        help = homeText(
            "VPN traffic on this phone. Session resets when you disconnect.",
            "ترافیک VPN همین گوشی. جلسه با قطع شدن از نو شروع می‌شود.",
        ),
        extra = { TrafficUsagePanels() },
        showDivider = showDivider,
        onCheckedChange = null,
    )
}

@Composable
private fun DnsSinkholeSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val proxyMode = advanced.connectionMode == CONNECTION_MODE_PROXY
    val summary = when {
        proxyMode -> homeText("Tunnel mode only", "فقط حالت تونل")
        settings.dnsSinkhole -> homeText("On", "روشن")
        else -> homeText("Off", "خاموش")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.Dns,
        title = homeText("Block Trackers & Ads", "مسدودسازی ردیاب و تبلیغات"),
        summary = summary,
        enabled = settings.dnsSinkhole,
        help = homeText(
            "DNS Sinkhole. Locally blocks known tracker domains to improve speed and privacy.",
            "DNS Sinkhole. دامنه‌های ردیاب (تِرَکر) شناخته شده را به صورت محلی مسدود می‌کند تا سرعت و حریم خصوصی بهبود یابد.",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setDnsSinkhole(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun WebRtcBlockSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val proxyMode = advanced.connectionMode == CONNECTION_MODE_PROXY
    val summary = when {
        proxyMode -> homeText("Tunnel mode only", "فقط حالت تونل")
        settings.webRtcBlock -> homeText("On", "روشن")
        else -> homeText("Off", "خاموش")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.VideocamOff,
        title = homeText("Prevent WebRTC IP Leak", "جلوگیری از نشت IP در WebRTC"),
        summary = summary,
        enabled = settings.webRtcBlock,
        help = homeText(
            "Drops STUN/TURN packets so WebRTC cannot leak your real IP. May break in-browser voice/video calls such as Google Meet.",
            "پکت‌های STUN/TURN را دراپ می‌کند تا از نشت آی‌پی واقعی شما توسط WebRTC جلوگیری کند. (ممکن است تماس‌های صوتی/تصویری تحت وب مثل Google Meet را قطع کند).",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setWebRtcBlock(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun Ipv6BlockSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val advancedStore = remember(context) { AdvancedSettingsStore(context) }
    val advanced by advancedStore.state.collectAsStateWithLifecycle()
    val proxyMode = advanced.connectionMode == CONNECTION_MODE_PROXY
    val summary = when {
        proxyMode -> homeText("Tunnel mode only", "فقط حالت تونل")
        settings.ipv6Block -> homeText("On", "روشن")
        else -> homeText("Off", "خاموش")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.PublicOff,
        title = homeText("Prevent IPv6 Leak", "جلوگیری از نشت IPv6"),
        summary = summary,
        enabled = settings.ipv6Block,
        help = homeText(
            "Drops all IPv6 on the tunnel so your real IPv6 address cannot leak.",
            "تمام ارتباطات IPv6 را در تونل مسدود می‌کند تا از لو رفتن آی‌پی واقعی شما جلوگیری شود.",
        ),
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setIpv6Block(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun GpsSpoofSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { GpsSpoofStore.get(context) }
    val enabled by store.enabled.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val engineMode by remember(context) { EngineModeStore.get(context) }.mode.collectAsStateWithLifecycle()
    val torSettings by remember(context) { TorEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    val powSettings by remember(context) { PowEngineStore.get(context) }.settings.collectAsStateWithLifecycle()
    val exitState by remember(context) { ExitIpInfoRepository.get(context) }.state.collectAsStateWithLifecycle()
    val profileCountry = remember(engineMode, connection) {
        ProfileStore(context).selectedProfile().country.countryCode
    }
    val countryCode = GpsSpoofTarget.countryCode(
        engine = engineMode,
        profileCountry = profileCountry,
        torExit = torSettings.exitCountryCode,
        powExit = powSettings.exitCountryCode,
        exitIpCountry = exitState.info?.countryCode,
    )
    val countryName = GpsSpoofTarget.displayName(countryCode, isPersian)
    val lifecycleOwner = LocalLifecycleOwner.current
    var mockAllowed by remember { mutableStateOf(GpsSpoofRuntime.isMockLocationAllowed(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mockAllowed = GpsSpoofRuntime.isMockLocationAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val needsSetup = enabled && !mockAllowed
    val summary = when {
        !enabled -> homeText("Off", "خاموش")
        needsSetup -> homeText("Needs setup", "نیاز به تنظیم")
        connection != ConnectionState.CONNECTED -> homeText("After connect", "بعد از وصل")
        countryName.isNotBlank() -> countryName
        else -> homeText("Waiting…", "منتظر…")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.MyLocation,
        title = homeText("GPS location", "موقعیت GPS"),
        summary = summary,
        enabled = enabled,
        summaryColor = when {
            needsSetup -> UacColors.DisconnectingAmber
            enabled && connection == ConnectionState.CONNECTED && mockAllowed && countryCode != null ->
                UacColors.ConnectedGreen
            enabled -> UacColors.TextSecondary
            else -> UacColors.TextSecondary
        },
        help = homeText(
            "Apps see the selected country, not your real location.",
            "برنامه‌ها کشور انتخاب‌شده را می‌بینند، نه جای واقعی تو.",
        ),
        extra = if (needsSetup) {
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(UacColors.DisconnectingAmber.copy(alpha = 0.10f))
                        .clickable { GpsSpoofRuntime.openDeveloperSettings(context) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = UacColors.DisconnectingAmber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        homeText("Set as mock location", "mock location را همین برنامه بگذار"),
                        color = UacColors.TextPrimary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else null,
        autoExpand = needsSetup,
        showDivider = showDivider,
        onCheckedChange = { store.setEnabled(it) },
    )
}

@Composable
private fun LanShareEndpointPanel(
    ip: String?,
    port: Int,
    connected: Boolean,
    needsWifi: Boolean,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val ready = !ip.isNullOrBlank()
    val copyValue = if (ready) "$ip:$port" else null
    val ipText = ip ?: when {
        !connected -> homeText("Connect VPN first", "اول VPN را وصل کن")
        needsWifi -> homeText("Needs Wi‑Fi", "وای‌فای لازم است")
        else -> homeText("Waiting…", "منتظر…")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LanShareValueRow(homeText("Type", "نوع"), "SOCKS5", ready = true)
        LanShareValueRow(homeText("IP", "آی‌پی"), ipText, ready = ready)
        LanShareValueRow(homeText("Port", "پورت"), port.toString(), ready = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = if (ready) 0.08f else 0.04f))
                .clickable(enabled = copyValue != null) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("UAC SOCKS5", copyValue),
                    )
                    copied = true
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = if (ready) UacColors.ConnectedGreen else UacColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                when {
                    !ready -> homeText("IP appears after connect", "آی‌پی بعد از وصل شدن می‌آید")
                    copied -> homeText("Copied $copyValue", "کپی شد $copyValue")
                    else -> homeText("Copy $copyValue", "کپی $copyValue")
                },
                color = UacColors.TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LanShareValueRow(
    caption: String,
    value: String,
    ready: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            caption,
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
            modifier = Modifier.width(52.dp),
        )
        Text(
            value,
            color = if (ready) UacColors.TextPrimary else UacColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (ready) FontFamily.Monospace else homeLocalizedFont(),
            textAlign = TextAlign.Start,
            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            color = UacColors.TextSecondary.copy(alpha = 0.78f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SettingsGroupShape)
                .background(SettingsGroupColor)
                .animateContentSize(),
            content = content,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    summary: String,
    enabled: Boolean,
    help: String,
    showDivider: Boolean,
    extra: (@Composable () -> Unit)? = null,
    autoExpand: Boolean = false,
    summaryColor: Color? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(autoExpand) }
    LaunchedEffect(autoExpand) {
        if (autoExpand) expanded = true
    }
    val statusColor = summaryColor ?: if (enabled) UacColors.ConnectedGreen else UacColors.TextSecondary
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsRowIcon(icon, enabled)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = UacColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 19.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        summary,
                        color = statusColor,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(4.dp))
                Box(
                    modifier = Modifier.size(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        tint = UacColors.TextSecondary.copy(alpha = 0.72f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (onCheckedChange != null) {
                Switch(
                    checked = enabled,
                    onCheckedChange = onCheckedChange,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = UacColors.ConnectedGreen,
                        checkedThumbColor = Color.White,
                        uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                        uncheckedThumbColor = Color.White.copy(alpha = 0.88f),
                        uncheckedBorderColor = Color.Transparent,
                    ),
                )
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 50.dp, end = 14.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    help,
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                extra?.invoke()
            }
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    isPersian: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon, enabled = false)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = UacColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary,
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (isPersian) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = UacColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun QuickSettingsTileRow(
    notice: String?,
    showDivider: Boolean,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAdd)
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(Icons.Outlined.DashboardCustomize, enabled = false)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    homeText("Quick Settings tile", "دکمه تنظیمات سریع"),
                    color = UacColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    homeText("From the notification shade", "از پنل بالای گوشی"),
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(UacColors.ConnectedGreen)
                    .clickable(onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = homeText("Add", "افزودن"),
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        notice?.let { message ->
            Text(
                message,
                color = UacColors.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(start = 50.dp, end = 14.dp, bottom = 10.dp),
            )
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, enabled: Boolean) {
    val tint = if (enabled) UacColors.ConnectedGreen else UacColors.TextSecondary
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun SettingsHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 50.dp)
            .height(0.6.dp)
            .background(SettingsHairlineColor),
    )
}

private enum class TileRequestResult { ADDED, ALREADY_ADDED, NOT_ADDED, ADD_MANUALLY, ERROR }

private fun requestQuickSettingsTile(
    context: android.content.Context,
    onResult: (TileRequestResult) -> Unit,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onResult(TileRequestResult.ADD_MANUALLY)
        return
    }
    runCatching {
        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
            ?: error("StatusBarManager unavailable")
        statusBarManager.requestAddTileService(
            ComponentName(context, UacQuickSettingsTileService::class.java),
            context.getString(R.string.quick_settings_tile_label),
            Icon.createWithResource(context, R.drawable.ic_stat_vpn),
            context.mainExecutor,
        ) { result ->
            onResult(
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileRequestResult.ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileRequestResult.ALREADY_ADDED
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileRequestResult.NOT_ADDED
                    else -> TileRequestResult.ERROR
                },
            )
        }
    }.onFailure { onResult(TileRequestResult.ERROR) }
}
