package com.uacspoofer.mobile.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import java.util.Date
import java.text.DateFormat
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.uacspoofer.mobile.mci.GeoAssets
import com.uacspoofer.mobile.mci.DomesticDirect
import com.uacspoofer.mobile.settings.DirectDomainWhitelist
import java.text.SimpleDateFormat
import java.util.Locale
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.ui.theme.AppTheme
import com.uacspoofer.mobile.ui.theme.AppThemeStore
import com.uacspoofer.mobile.ui.theme.orDefault
import com.uacspoofer.mobile.ui.theme.paletteFor
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.location.GpsSpoofRuntime
import com.uacspoofer.mobile.location.GpsSpoofStore
import com.uacspoofer.mobile.location.GpsSpoofTarget
import com.uacspoofer.mobile.logging.CrashReportStore
import com.uacspoofer.mobile.profiles.ProfileStore
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.TunBackend
import com.uacspoofer.mobile.settings.TunBackendStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.CONNECTION_MODE_TUNNEL
import com.uacspoofer.mobile.settings.LanShareMode
import com.uacspoofer.mobile.settings.NetworkGuardStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.AutoConnectCoordinator
import com.uacspoofer.mobile.vpn.AutoConnectOrigin
import com.uacspoofer.mobile.vpn.ExitIpInfoRepository
import com.uacspoofer.mobile.vpn.LanSharePolicy
import com.uacspoofer.mobile.vpn.LanShareStatus
import com.uacspoofer.mobile.vpn.MonthlyTrafficStore
import com.uacspoofer.mobile.vpn.UacQuickSettingsTileService

private val SettingsGroupShape = RoundedCornerShape(20.dp)
private val SettingsGroupColor get() = UacColors.SettingsSurface
private val SettingsRowTextInset = 58.dp

private enum class SettingsSection(val icon: ImageVector) {
    APPEARANCE(Icons.Outlined.Palette),
    CONNECTION(Icons.Outlined.VpnKey),
    PRIVACY(Icons.Outlined.Shield),
    TOOLS(Icons.Outlined.Tune),
}

@Composable
internal fun SettingsScreen(
    onMenuClick: () -> Unit,
    themeEditorEnabled: Boolean,
    onEditTheme: () -> Unit,
    onAdvancedSettingsClick: () -> Unit,
    onPowSettingsClick: () -> Unit,
    onTorSettingsClick: () -> Unit,
    onDirectSettingsClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineStore.mode.collectAsStateWithLifecycle()
    val localizedTextStyle = homeLocalizedTextStyle().copy(textAlign = TextAlign.Start)
    val layoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var tileNotice by remember { mutableStateOf<String?>(null) }
    val accent = UacColors.DisconnectedBlue
    val tileAddedMessage = homeText("Tile added successfully", "دکمه به پنل اضافه شد")
    val tileAlreadyAddedMessage = homeText("Tile is already in Quick Settings", "این دکمه از قبل در تنظیمات سریع هست")
    val tileNotAddedMessage = homeText("Tile was not added", "دکمه اضافه نشد")
    val tileManualMessage = homeText(
        "Open Quick Settings edit mode and drag UAC SNI Spoofer into the panel",
        "پنل تنظیمات سریع را باز کنید. در بخش ویرایش، دکمهٔ UAC SNI Spoofer را به پنل بکشید.",
    )
    val tileErrorMessage = homeText("Could not request the tile", "درخواست افزودن دکمه انجام نشد")
    val diagnosticsOpen = remember { mutableStateOf(false) }
    val secretTaps = remember { mutableIntStateOf(0) }
    val lastSecretTapAt = remember { mutableLongStateOf(0L) }

    if (diagnosticsOpen.value) {
        RuntimeDiagnosticsScreen(onClose = { diagnosticsOpen.value = false })
        return
    }

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        ToolPageScaffold(
            accent = accent,
            verticalSpacing = 22.dp,
            listState = listState,
            textStyle = localizedTextStyle,
            header = {
                SettingsPageHeader(
                    onMenuClick = onMenuClick,
                    onSectionClick = { section ->
                        scope.launch { listState.animateScrollToItem(section.ordinal) }
                    },
                )
            },
        ) {
            item(key = SettingsSection.APPEARANCE) {
                SettingsGroup(homeText("App appearance", "ظاهر برنامه")) {
                    AppThemeSettingsRow(themeEditorEnabled, onEditTheme)
                }
            }
            item(key = SettingsSection.CONNECTION) {
                SettingsGroup(homeText("Connection and network", "اتصال و شبکه")) {
                    ConnectionModeSettingsRow(showDivider = true)
                    TunBackendSettingsRow()
                    if (engineMode.isPow) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Hub,
                            title = homeText("UAC PoW", "UAC PoW"),
                            summary = homeText("Connection path and MASQUE", "مسیر اتصال و تنظیمات MASQUE"),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onPowSettingsClick,
                        )
                    }
                    if (engineMode.isTor) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Shield,
                            title = homeText("Tor settings", "تنظیمات Tor"),
                            summary = homeText(
                                "Bridges, exit location and connection options",
                                "پل‌ها، موقعیت خروجی و گزینه‌های اتصال",
                            ),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onTorSettingsClick,
                        )
                    }
                    if (engineMode.isDirect) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Tune,
                            title = homeText("Direct settings", "تنظیمات Direct"),
                            summary = homeText(
                                "DNS and connection options for serverless mode",
                                "تنظیمات DNS و اتصال در حالت بدون سرور",
                            ),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onDirectSettingsClick,
                        )
                    }
                    if (engineMode.isXray) {
                        SettingsNavRow(
                            icon = Icons.Outlined.Tune,
                            title = homeText("Xray settings", "تنظیمات Xray"),
                            summary = homeText(
                                "DNS, TUN and transport options",
                                "تنظیمات DNS، تونل و روش انتقال",
                            ),
                            isPersian = isPersian,
                            showDivider = true,
                            onClick = onAdvancedSettingsClick,
                        )
                    }
                    AutoConnectSettingsRow(showDivider = true)
                    LanShareSettingsRow(showDivider = true)
                    QuicBlockSettingsRow(showDivider = true)
                    IranDirectSettingsRow(showDivider = true)
                    ManualDirectDomainsSettingsRow(showDivider = true)
                    TrafficUsageSettingsRow(showDivider = false)
                }
            }
            item(key = SettingsSection.PRIVACY) {
                SettingsGroup(homeText("Privacy and security", "امنیت و حریم خصوصی")) {
                    KillSwitchSettingsRow(showDivider = true)
                    GpsSpoofSettingsRow(showDivider = true)
                    DnsSinkholeSettingsRow(showDivider = true)
                    WebRtcBlockSettingsRow(showDivider = true)
                    Ipv6BlockSettingsRow(showDivider = false)
                }
            }
            item(key = SettingsSection.TOOLS) {
                SettingsGroup(homeText("Tools and maintenance", "ابزارها و نگهداری")) {
                    SettingsBackupRows(isPersian = isPersian)
                    CrashReportSettingsRow(showDivider = true)
                    QuickSettingsTileRow(
                        notice = tileNotice,
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
private fun SettingsPageHeader(
    onMenuClick: () -> Unit,
    onSectionClick: (SettingsSection) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = WideShell.PageContentMax - 36.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    homeText("General settings", "تنظیمات کلی"),
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = UacColors.TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 35.sp,
                )
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(SettingsGroupColor, CircleShape)
                        .border(1.dp, UacColors.CardBorder, CircleShape)
                        .openDrawerOnDpadLeft(onMenuClick),
                ) {
                    Icon(
                        Icons.Outlined.Menu,
                        contentDescription = homeText("Open navigation", "باز کردن منوی برنامه"),
                        tint = UacColors.TextPrimary,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(SettingsGroupColor)
                    .border(1.dp, UacColors.CardBorder, shape)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (section in SettingsSection.entries) {
                    Column(
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(role = Role.Button) { onSectionClick(section) }
                            .padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(section.icon, null, tint = UacColors.DisconnectedBlue, modifier = Modifier.size(18.dp))
                        Text(
                            when (section) {
                                SettingsSection.APPEARANCE -> homeText("Appearance", "ظاهر")
                                SettingsSection.CONNECTION -> homeText("Connection", "اتصال")
                                SettingsSection.PRIVACY -> homeText("Security", "امنیت")
                                SettingsSection.TOOLS -> homeText("Tools", "ابزارها")
                            },
                            color = UacColors.TextPrimary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TunBackendSettingsRow() {
    val context = LocalContext.current
    val store = remember(context) { TunBackendStore(context) }
    var selected by remember(store) { mutableStateOf(store.selected()) }
    var open by remember { mutableStateOf(false) }
    val title = homeText("TUN engine", "موتور تونل (TUN)")
    val reconnect = homeText(
        "Reconnect the VPN to apply this change.",
        "برای اعمال تغییر، VPN را قطع و دوباره وصل کنید.",
    )
    val choices = listOf(
        PowMenuChoice(
            TunBackend.HEV.id,
            homeText("HEV (Default)", "HEV (پیش‌فرض)"),
            homeText("Uses the stable tunnel implementation.", "از پیاده‌سازی پایدار تونل استفاده می‌کند."),
        ),
        PowMenuChoice(
            TunBackend.ZEPTUN.id,
            homeText("Zeptun (Experimental)", "Zeptun (آزمایشی)"),
            homeText(
                "Uses Zeptun to process VPN traffic.",
                "ترافیک VPN را با Zeptun پردازش می‌کند.",
            ),
        ),
    )
    val current = choices.first { it.id == selected.id }
    SettingsNavRow(
        icon = Icons.Outlined.VpnKey,
        title = title,
        summary = current.title,
        isPersian = LocalHomePersian.current,
        showDivider = true,
        onClick = { open = true },
    )
    if (open) {
        PowChoiceSheet(
            title = title,
            choices = choices,
            selectedId = selected.id,
            accent = UacColors.DisconnectedBlue,
            onSelect = { id ->
                val next = TunBackend.fromId(id)
                if (next != selected) {
                    store.select(next)
                    selected = next
                    val state = ConnectionStateStore.state.value
                    if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
                        android.widget.Toast.makeText(context, reconnect, android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun ConnectionModeSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { AdvancedSettingsStore(context) }
    val advanced by store.state.collectAsStateWithLifecycle()
    val proxy = advanced.connectionMode == CONNECTION_MODE_PROXY
    var open by remember { mutableStateOf(false) }
    val modes = listOf(
        PowMenuChoice(
            CONNECTION_MODE_TUNNEL,
            homeText("Tunnel (VPN)", "تونل (VPN)"),
            homeText("Routes device traffic through the VPN, subject to your app routing rules.",
                "ترافیک دستگاه طبق تنظیمات مسیریابی برنامه‌ها از VPN عبور می‌کند."),
        ),
        PowMenuChoice(
            CONNECTION_MODE_PROXY,
            homeText("Proxy only", "فقط پروکسی"),
            homeText("Apps that you configure to use the proxy connect through it.",
                "برنامه‌هایی که برای استفاده از پروکسی تنظیم کرده‌اید از آن عبور می‌کنند."),
        ),
    )
    val current = modes.firstOrNull { it.id == advanced.connectionMode } ?: modes.first()
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsNavRow(
            icon = Icons.Outlined.VpnKey,
            title = homeText("Connection mode", "حالت اتصال"),
            summary = if (proxy) {
                homeText("Proxy only, port ${advanced.socksPort}", "فقط پروکسی روی پورت ${advanced.socksPort}")
            } else {
                current.title
            },
            isPersian = LocalHomePersian.current,
            showDivider = false,
            onClick = { open = true },
        )
        if (proxy) {
            Column(
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    homeLatinDigits(homeText(
                        "Set apps to 127.0.0.1 and this port. Applies to the next connection.",
                        "در برنامه‌ها، آدرس 127.0.0.1 و پورت زیر را وارد کنید. تغییر از اتصال بعدی اعمال می‌شود.",
                    )),
                    color = UacColors.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                SocksPortField(advanced.socksPort) { port ->
                    store.update { it.copy(socksPort = port) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            homeText("UDP", "UDP"),
                            color = UacColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            homeText("Needed for voice calls and games.", "برای تماس صوتی و بعضی بازی‌ها لازم است."),
                            color = UacColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    Switch(
                        checked = advanced.socksUdp,
                        onCheckedChange = { enabled -> store.update { it.copy(socksUdp = enabled) } },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = UacColors.DisconnectedBlue,
                            checkedThumbColor = Color.White,
                        ),
                    )
                }
            }
        }
        if (showDivider) SettingsHairline()
    }
    if (open) {
        PowChoiceSheet(
            title = homeText("Connection mode", "حالت اتصال برنامه‌ها"),
            choices = modes,
            selectedId = current.id,
            accent = UacColors.DisconnectedBlue,
            onSelect = { id ->
                if (id != advanced.connectionMode) store.update { it.copy(connectionMode = id) }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun SocksPortField(value: Int, onValid: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in 1024..65535 } == true
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(5)
            text.toIntOrNull()?.takeIf { it in 1024..65535 && it != value }?.let(onValid)
        },
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(
            textDirection = TextDirection.Ltr,
            textAlign = TextAlign.Left,
            fontFamily = FontFamily.Default,
        ),
        label = { Text(homeText("SOCKS port", "پورت SOCKS"), fontSize = 11.sp) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = UacColors.DisconnectedBlue,
            unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
            focusedLabelColor = UacColors.TextSecondary,
            unfocusedLabelColor = UacColors.TextSecondary,
            cursorColor = UacColors.DisconnectedBlue,
        ),
    )
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
        blocking -> homeText("Internet access blocked", "دسترسی به اینترنت مسدود است")
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
            "اگر VPN قطع شود، دسترسی برنامه‌ها به اینترنت بسته می‌شود. برای برقراری دسترسی، VPN را دوباره وصل کنید یا این گزینه را خاموش کنید. فقط در حالت تونل فعال است.",
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
            "اگر مجوز VPN را داده باشید، پس از روشن شدن دستگاه یا باز کردن برنامه، اتصال به‌طور خودکار برقرار می‌شود.",
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
    val port = settings.lanSharePort
    val currentEndpoint = endpoint.port == port && endpoint.mode == settings.lanShareMode
    val ip = endpoint.address?.takeIf {
        endpoint.listening && currentEndpoint && it.isNotBlank()
    }
    val unavailablePort = currentEndpoint && endpoint.detail == LanSharePolicy.PORT_UNAVAILABLE
    val ready = ip != null
    val summary = when {
        !settings.lanShare -> homeText("Off", "خاموش")
        connection != ConnectionState.CONNECTED -> homeText("Waiting for VPN", "در انتظار اتصال VPN")
        ready -> {
            val address = "$ip:$port"
            val label = if (settings.lanShareMode == LanShareMode.PLAYSTATION_5) "PS5 · $address" else address
            homeText(label, homeLtr(label))
        }
        unavailablePort -> homeText("Port unavailable", "پورت در دسترس نیست")
        currentEndpoint && endpoint.detail == "no lan" -> homeText("Needs Wi‑Fi", "وای‌فای لازم است")
        else -> homeText("Starting…", "در حال شروع…")
    }
    SettingsToggleRow(
        icon = Icons.Outlined.WifiTethering,
        title = homeText("Share VPN on local network", "اشتراک VPN در شبکهٔ محلی"),
        summary = summary,
        enabled = settings.lanShare,
        help = homeText(
            "Choose SOCKS5 for compatible devices, or PlayStation 5 for the console's HTTP proxy. Both devices must be on the same network. Trusted networks only, no password.",
            "برای دستگاه‌های سازگار، SOCKS5 را انتخاب کنید. کنسول PlayStation 5 از پروکسی HTTP استفاده می‌کند. چون این اتصال رمز ورود ندارد، هر دو دستگاه را به یک شبکهٔ مطمئن وصل کنید.",
        ),
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LanSharePortEditor(port) {
                    store.setLanSharePort(it)
                    if (store.snapshot().lanShare) VpnController.applyNetworkGuard(context)
                }
                if (settings.lanShare) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LanShareMode.entries.forEach { mode ->
                            val selected = settings.lanShareMode == mode
                            TextButton(
                                onClick = {
                                    if (store.snapshot().lanShareMode != mode) {
                                        store.setLanShareMode(mode)
                                        VpnController.applyNetworkGuard(context)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                                    .semantics { this.selected = selected; role = Role.RadioButton }
                                    .background(if (selected) UacColors.ConnectedGreen.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(10.dp))
                                    .border(1.dp, if (selected) UacColors.ConnectedGreen else UacColors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                            ) {
                                Text(if (mode == LanShareMode.PLAYSTATION_5) "PlayStation 5" else "SOCKS5",
                                    color = if (selected) UacColors.TextPrimary else UacColors.TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    LanShareEndpointPanel(
                        ip = ip,
                        port = port,
                        connected = connection == ConnectionState.CONNECTED,
                        needsWifi = currentEndpoint && endpoint.detail == "no lan",
                        unavailablePort = unavailablePort,
                        mode = settings.lanShareMode,
                    )
                    if (settings.lanShareMode == LanShareMode.PLAYSTATION_5) {
                        Text(homeText(
                            "On PS5, go to Settings, Network, Settings, then Set Up Internet Connection. Select your network and open Advanced Settings. Set Proxy Server to Use and enter the address and port separately.",
                            "در PS5، از منوی Settings وارد Network شوید. در بخش Settings گزینهٔ Set Up Internet Connection را بزنید. شبکهٔ خود را انتخاب کنید و Advanced Settings را باز کنید. در Proxy Server گزینهٔ Use را بزنید و آدرس و پورت را جداگانه وارد کنید.",
                        ), color = UacColors.TextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                        Text(homeText(
                            "HTTP/HTTPS proxy for compatible console services and downloads. UDP game traffic, voice chat and NAT are not tunneled by this mode.",
                            "این پروکسی برای دانلودها و سرویس‌های سازگار کنسول است. ترافیک UDP بازی، گفت‌وگوی صوتی و NAT از آن عبور نمی‌کنند.",
                        ), color = UacColors.TextSecondary, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
            }
        },
        autoExpand = settings.lanShare,
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setLanShare(checked)
            VpnController.applyNetworkGuard(context)
        },
    )
}

@Composable
private fun LanSharePortEditor(port: Int, onSave: (Int) -> Unit) {
    var text by rememberSaveable(port) { mutableStateOf(port.toString()) }
    val nextPort = text.toIntOrNull()?.takeIf(LanSharePolicy::isValidPort)
    val canSave = nextPort != null && nextPort != port
    val focus = LocalFocusManager.current
    fun save() {
        if (canSave && nextPort != null) {
            onSave(nextPort)
            focus.clearFocus()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter(Char::isDigit) },
            modifier = Modifier.weight(1f),
            label = { Text(homeText("Sharing port", "پورت اشتراک")) },
            singleLine = true,
            isError = text.isNotEmpty() && nextPort == null,
            supportingText = if (text.isNotEmpty() && nextPort == null) {
                { Text(homeText("Enter a number from 1 to 65535.", "عددی از ۱ تا ۶۵۵۳۵ وارد کنید.")) }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        TextButton(enabled = canSave, onClick = ::save) {
            Text(homeText("Save", "ذخیره"))
        }
    }
}



@Composable
private fun IranDirectSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val isPersian = LocalHomePersian.current
    val store = remember(context) { NetworkGuardStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var statusText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun pick(en: String, fa: String) = if (isPersian) fa else en
    fun refreshStatus() {
        val st = runCatching { GeoAssets.status(context) }.getOrNull()
        statusText = if (st == null) {
            pick("Not ready yet", "هنوز آماده نیست")
        } else {
            val day = runCatching {
                SimpleDateFormat("d MMM", if (isPersian) Locale("fa") else Locale.US)
                    .format(Date(st.updatedAtMs))
            }.getOrNull()
            when {
                st.tag.startsWith("latest@") && day != null ->
                    pick("Updated · $day", "به‌روز · $day")
                day != null ->
                    pick("Ready · $day", "آماده · $day")
                else ->
                    pick("Ready", "آماده")
            }
        }
    }
    LaunchedEffect(Unit) { refreshStatus() }
    SettingsToggleRow(
        icon = Icons.AutoMirrored.Outlined.AltRoute,
        title = homeText("Direct access to Iranian sites", "دسترسی مستقیم به سایت‌های ایرانی"),
        summary = if (settings.iranDirect) homeText("On", "روشن") else homeText("Off", "خاموش"),
        enabled = settings.iranDirect,
        help = homeText(
            "Traffic to Iranian sites uses your regular internet connection.",
            "ترافیک سایت‌های ایرانی از اتصال عادی اینترنت عبور می‌کند.",
        ),
        extra = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    statusText,
                    color = UacColors.TextSecondary.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        if (busy) return@TextButton
                        busy = true
                        notice = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    GeoAssets.updateFromNetwork(context)
                                    DomesticDirect.reloadFromFiles(context)
                                }
                            }
                            busy = false
                            result.onSuccess {
                                notice = pick("Updated", "به‌روز شد")
                                refreshStatus()
                            }.onFailure { err ->
                                notice = pick(
                                    "Failed",
                                    "ناموفق",
                                )
                            }
                        }
                    },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = UacColors.ConnectedGreen,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (busy) {
                            homeText("Updating…", "در حال به‌روزرسانی…")
                        } else {
                            homeText("Update", "به‌روزرسانی")
                        },
                        color = UacColors.ConnectedGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            notice?.let {
                Text(it, color = UacColors.ConnectedGreen, fontSize = 11.sp)
            }
        },
        showDivider = showDivider,
        onCheckedChange = { checked ->
            store.setIranDirect(checked)
        },
    )
}

@Composable
private fun ManualDirectDomainsSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val store = remember(context) { DirectDomainWhitelist.get(context) }
    val domains by store.domains.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val invalidHint = homeText(
        "Type a site like ",
        "آدرس سایت را مثل ",
    ) + homeLtr("digikala.com")
    fun tryAdd() {
        val ok = store.add(draft)
        if (ok) {
            draft = ""
            error = null
            focus.clearFocus()
            VpnController.applyDirectWhitelist(context)
        } else {
            error = invalidHint
        }
    }
    val summary = when {
        domains.isEmpty() -> homeText("Empty", "خالی")
        else -> homeText(
            "${domains.size} site${if (domains.size == 1) "" else "s"}",
            "${domains.size} سایت",
        )
    }
    SettingsToggleRow(
        icon = Icons.Outlined.Link,
        title = homeText("My direct domains", "دامنه‌های مستقیم من"),
        summary = summary,
        enabled = domains.isNotEmpty(),
        help = homeText(
            "Sites you add here open without the VPN.",
            "سایت‌هایی که اینجا اضافه کنی، بدون فیلترشکن باز می‌شن.",
        ),
        extra = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                domains.forEach { host ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            host,
                            color = UacColors.TextPrimary,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        )
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = homeText("Remove", "حذف"),
                            tint = UacColors.TextSecondary,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .clickable {
                                    store.remove(host)
                                    VpnController.applyDirectWhitelist(context)
                                }
                                .padding(6.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            draft = it
                            error = null
                        },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text(
                                homeLtr("digikala.com"),
                                color = UacColors.TextSecondary.copy(alpha = 0.55f),
                                fontSize = 13.sp,
                                style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = UacColors.TextPrimary,
                            fontSize = 13.sp,
                            textDirection = TextDirection.Ltr,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { tryAdd() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = UacColors.ConnectedGreen.copy(alpha = 0.55f),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                            cursorColor = UacColors.ConnectedGreen,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                    )
                    TextButton(
                        onClick = { tryAdd() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            tint = UacColors.ConnectedGreen,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            homeText("Add", "افزودن"),
                            color = UacColors.ConnectedGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                error?.let {
                    Text(it, color = Color(0xFFFF8A95), fontSize = 11.sp)
                }
            }
        },
        showDivider = showDivider,
        onCheckedChange = null,
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
        title = homeText("Block QUIC", "مسدود کردن QUIC"),
        summary = summary,
        enabled = settings.quicBlock,
        help = homeText(
            "Blocks UDP 443 so apps like YouTube, Instagram, and browsers fall back to TCP. That often fixes slow or stuck video loading.",
            "ترافیک UDP روی پورت 443 مسدود می‌شود تا برنامه‌های سازگار به TCP برگردند. این گزینه ممکن است به رفع مشکل بارگذاری ویدیو کمک کند.",
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
        title = homeText("Traffic usage", "مصرف ترافیک"),
        summary = homeText("$monthTotal this month", "مصرف این ماه $monthTotal"),
        enabled = false,
        help = homeText(
            "VPN traffic on this phone. Session resets when you disconnect.",
            "مصرف VPN همین دستگاه. شمارندهٔ هر اتصال با قطع VPN بازنشانی می‌شود.",
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
            "درخواست‌های DNS برای دامنه‌های شناخته‌شدهٔ تبلیغاتی و ردیاب‌ها روی دستگاه مسدود می‌شوند.",
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
            "با مسدود کردن بسته‌های STUN و TURN از افشای آدرس IP در WebRTC جلوگیری می‌کند. ممکن است تماس‌های تحت وب، مثل Google Meet، دچار اختلال شوند.",
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
            "ترافیک IPv6 در تونل مسدود می‌شود تا آدرس واقعی IPv6 دستگاه فاش نشود.",
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
        connection != ConnectionState.CONNECTED -> homeText("Waiting for VPN", "در انتظار اتصال VPN")
        countryName.isNotBlank() -> homeText(countryName, countryName)
        else -> homeText("Waiting for location", "در انتظار موقعیت خروجی")
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
            "موقعیت GPS که به برنامه‌ها گزارش می‌شود بر اساس کشور خروجی VPN تنظیم می‌شود.",
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
                        homeText("Select the mock location app", "انتخاب برنامهٔ موقعیت مکانی آزمایشی"),
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
    unavailablePort: Boolean,
    mode: LanShareMode,
) {
    val context = LocalContext.current
    var copied by remember(ip, port, mode) { mutableStateOf(false) }
    val ready = !ip.isNullOrBlank()
    val copyValue = if (!ready) null else if (mode == LanShareMode.PLAYSTATION_5) ip else "$ip:$port"
    val ipText = ip ?: when {
        !connected -> homeText("Connect VPN first", "ابتدا VPN را وصل کنید")
        needsWifi -> homeText("Needs Wi‑Fi", "وای‌فای لازم است")
        unavailablePort -> homeText("Choose another port", "پورت دیگری انتخاب کنید")
        else -> homeText("Waiting…", "منتظر…")
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LanShareValueRow(homeText("Type", "نوع"), mode.proxyType, ready = true)
        LanShareValueRow(homeText("IP", "آی‌پی"), ipText, ready = ready)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = if (ready) 0.08f else 0.04f))
                .clickable(enabled = copyValue != null) {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("UAC ${mode.proxyType}", copyValue),
                    )
                    copied = true
                }
                .defaultMinSize(minHeight = 48.dp)
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
                    !ready -> homeText("IP appears after connecting", "آدرس IP پس از اتصال نمایش داده می‌شود")
                    copied -> homeText("Copied to clipboard", "در حافظهٔ موقت کپی شد")
                    mode == LanShareMode.PLAYSTATION_5 -> homeText("Copy IP address", "کپی آدرس IP")
                    else -> homeText("Copy address and port", "کپی آدرس و پورت")
                },
                color = UacColors.TextPrimary,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
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
            style = if (ready) LocalTextStyle.current.copy(textDirection = TextDirection.Ltr) else LocalTextStyle.current,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AppThemeSettingsRow(themeEditorEnabled: Boolean, onEditTheme: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AppThemeStore.get(context) }
    val theme by store.theme.collectAsStateWithLifecycle()
    val savedThemes by store.savedThemes.collectAsStateWithLifecycle()
    val selectedId by store.selectedId.collectAsStateWithLifecycle()
    val unlockTaps = remember { FiveTapSequence() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (base in AppTheme.entries) {
            AppThemeChoice(
                theme = base,
                selected = theme == base && selectedId == null,
                onClick = {
                    if (store.theme.value != base || store.selectedId.value != null) store.set(base)
                    if (base == AppTheme.NEON) {
                        if (unlockTaps.tap(SystemClock.elapsedRealtime())) onEditTheme()
                    } else {
                        unlockTaps.reset()
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (themeEditorEnabled && savedThemes.isNotEmpty()) {
        SettingsHairline()
        Text(
            homeText("Saved themes", "تم‌های ذخیره‌شده"),
            color = UacColors.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 14.dp, top = 12.dp).semantics { heading() },
        )
        for (saved in savedThemes) {
            val selected = selectedId == saved.id
            Row(
                modifier = Modifier.fillMaxWidth()
                    .selectable(selected = selected, role = Role.RadioButton, onClick = {
                        unlockTaps.reset()
                        store.selectSaved(saved.id)
                    })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(homeText(saved.name, saved.name), color = UacColors.TextPrimary, fontSize = 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(saved.base.displayName(), color = UacColors.TextSecondary, fontSize = 13.sp)
                }
                IconButton(onClick = {
                    unlockTaps.reset()
                    store.selectSaved(saved.id)
                    onEditTheme()
                }) {
                    Icon(Icons.Outlined.Edit, homeText("Edit ${saved.name}", "ویرایش ${saved.name}"),
                        tint = UacColors.TextPrimary)
                }
            }
        }
    }
}

internal class FiveTapSequence {
    private var lastTapAt = 0L
    private var count = 0

    fun tap(now: Long): Boolean {
        if (now - lastTapAt >= 500L) reset()
        lastTapAt = now
        count++
        if (count < 5) return false
        reset()
        return true
    }

    fun reset() {
        count = 0
    }
}

private fun AppTheme.displayName(): String = name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }

@Composable
private fun AppThemeChoice(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = remember(theme) { paletteFor(theme, tokens = null) }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (selected) UacColors.DisconnectedBlue.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (selected) UacColors.DisconnectedBlue else UacColors.Divider, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(palette.backgroundTop, palette.settingsSurface, palette.backgroundBottom))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier.size(31.dp)
                    .border(1.dp, palette.connected.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(23.dp).background(palette.surface, CircleShape)
                    .border(1.dp, palette.connected, CircleShape))
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = null,
                    tint = palette.backgroundTop,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(15.dp)
                        .background(palette.connected, CircleShape).padding(2.dp),
                )
            }
        }
        Text(
            theme.displayName(),
            color = if (selected) UacColors.TextPrimary else UacColors.TextSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
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
            color = UacColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 23.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).semantics { heading() },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SettingsGroupShape)
                .background(SettingsGroupColor)
                .border(1.dp, UacColors.Divider, SettingsGroupShape)
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
    var expanded by rememberSaveable { mutableStateOf(autoExpand) }
    LaunchedEffect(autoExpand) {
        if (autoExpand) expanded = true
    }
    val statusColor = summaryColor ?: if (enabled) UacColors.ConnectedGreen else UacColors.TextSecondary
    val detailsAction = if (expanded) homeText("Hide details", "بستن توضیحات") else homeText("Show details", "نمایش توضیحات")
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(role = Role.Button, onClickLabel = detailsAction) { expanded = !expanded }
                    .defaultMinSize(minHeight = 60.dp)
                    .padding(start = 8.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingsRowIcon(icon, enabled)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = UacColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    )
                    Text(
                        homeLatinDigits(summary),
                        color = statusColor,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 2,
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
                    modifier = Modifier.semantics { contentDescription = title },
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
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    homeLatinDigits(help),
                    color = UacColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                extra?.invoke()
            }
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
internal fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    summary: String,
    isPersian: Boolean,
    showDivider: Boolean,
    actionIcon: ImageVector = if (isPersian) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 66.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsRowIcon(icon, enabled = false)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = UacColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                )
                Text(
                    homeLatinDigits(summary),
                    color = UacColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
            Icon(
                actionIcon,
                contentDescription = null,
                tint = UacColors.TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (showDivider) SettingsHairline()
    }
}

@Composable
private fun CrashReportSettingsRow(showDivider: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var copied by remember { mutableStateOf(false) }
    var hasReport by remember { mutableStateOf(CrashReportStore.hasReport(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasReport = CrashReportStore.hasReport(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsNavRow(
        icon = Icons.Outlined.ContentCopy,
        title = homeText("Copy crash report", "کپی گزارش خطا"),
        summary = when {
            copied -> homeText("Copied. You can paste it in a message.", "گزارش کپی شد. می‌توانید آن را در پیام جای‌گذاری کنید.")
            hasReport -> homeText("Copy the most recent report.", "آخرین گزارش آمادهٔ کپی است.")
            else -> homeText("No crash report yet", "هنوز گزارشی ثبت نشده است")
        },
        isPersian = LocalHomePersian.current,
        showDivider = showDivider,
        onClick = {
            val payload = CrashReportStore.copyPayload(context)
            if (payload.isNullOrBlank()) {
                hasReport = false
                copied = false
            } else {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("UAC crash report", payload))
                hasReport = true
                copied = true
            }
        },
    )
}

@Composable
private fun QuickSettingsTileRow(
    notice: String?,
    onAdd: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        SettingsNavRow(
            icon = Icons.Outlined.DashboardCustomize,
            title = homeText("Add a Quick Settings button", "افزودن دکمه به تنظیمات سریع"),
            summary = homeText("Connect or disconnect from the Android panel.", "از پنل اندروید VPN را وصل یا قطع کنید."),
            isPersian = LocalHomePersian.current,
            showDivider = false,
            actionIcon = Icons.Outlined.Add,
            onClick = onAdd,
        )
        notice?.let { message ->
            Text(
                message,
                color = UacColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(start = SettingsRowTextInset, end = 14.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun SettingsRowIcon(icon: ImageVector, enabled: Boolean) {
    val tint = if (enabled) UacColors.ConnectedGreen else UacColors.TextSecondary
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SettingsHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowTextInset, end = 14.dp)
            .height(1.dp)
            .background(UacColors.DividerOverride.orDefault(Color.White.copy(alpha = 0.07f))),
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
