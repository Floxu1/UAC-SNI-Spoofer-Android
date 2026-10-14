package com.uacspoofer.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.core.ConnectionState
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.core.VpnController
import com.uacspoofer.mobile.engine.tor.TorDaemon
import com.uacspoofer.mobile.engine.tor.TorEngineSettings
import com.uacspoofer.mobile.engine.tor.TorEngineStore
import com.uacspoofer.mobile.engine.tor.TorPhase
import com.uacspoofer.mobile.engine.tor.TorStatusStore
import com.uacspoofer.mobile.engine.tor.WebTunnelBridgeParser
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale

@Composable
internal fun TorSettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = {
            PowCompactHeader(
                accent = accent,
                onBack = onBack,
                title = homeText("Tor", "Tor"),
                subtitle = homeText("Connection settings", "تنظیمات اتصال"),
                icon = Icons.Outlined.Shield,
            )
        },
    ) {
        item { TorSettingsPanel() }
    }
}

@Composable
private fun TorSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { TorEngineStore.get(context) }
    val daemon = remember(context) { TorDaemon(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val lastConnect by store.lastConnect.collectAsStateWithLifecycle()
    val torStatus by TorStatusStore.status.collectAsStateWithLifecycle()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val accent = UacColors.DisconnectedBlue
    val parsedBridges = remember(settings.bridgeLines) { WebTunnelBridgeParser.parseAll(settings.bridgeLines) }
    val ignoredBridgeLines = remember(settings.bridgeLines) {
        settings.bridgeLines.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .count() - parsedBridges.size
    }
    val torBinaryReady = remember { daemon.locateTorBinary() != null }
    val pluginReady = remember { daemon.locateWebTunnelPlugin() != null }

    val pinned = settings.exitCountryCode
    val exitChoices = buildList {
        add(
            PowMenuChoice(
                "auto",
                homeText("Automatic", "خودکار"),
                homeText("Tor picks any exit.", "Tor خودش هر خروجی‌ای را انتخاب می‌کند."),
            ),
        )
        add(
            PowMenuChoice(
                "fast",
                homeText("Fastest nearby (DE · NL · CH…)", "سریع‌ترین نزدیک (آلمان، هلند، سوئیس…)"),
                homeText(
                    "Nearby, well-provisioned exits. Lower latency from Iran.",
                    "خروجی‌های نزدیک و پرظرفیت؛ تأخیر کمتر از ایران.",
                ),
            ),
        )
        if (pinned.isNotEmpty()) {
            val locale = if (LocalHomePersian.current) Locale("fa") else Locale.ENGLISH
            add(
                PowMenuChoice(
                    "pinned",
                    Locale("", pinned.uppercase(Locale.US)).getDisplayCountry(locale).ifBlank { pinned.uppercase(Locale.US) },
                    homeText("Chosen on the country screen. Must exit there.", "از صفحه کشورها انتخاب شده؛ حتماً از همان خارج می‌شود."),
                ),
            )
        }
    }
    val exitId = when {
        pinned.isNotEmpty() -> "pinned"
        settings.fastExit -> "fast"
        else -> "auto"
    }
    val fragments = listOf(
        PowMenuChoice(
            TorEngineSettings.FRAGMENT_AUTO,
            homeText("Auto", "خودکار"),
            homeText(
                "Splits the WebTunnel ClientHello; turns itself off if the bridge plugin refuses.",
                "ClientHello پل WebTunnel تکه می‌شود؛ اگر افزونه پل قبول نکند خودش خاموش می‌شود.",
            ),
        ),
        PowMenuChoice(
            TorEngineSettings.FRAGMENT_ON,
            homeText("On", "روشن"),
            homeText("Always split the ClientHello. Best against DPI.", "همیشه ClientHello تکه می‌شود؛ بهترین در برابر DPI."),
        ),
        PowMenuChoice(
            TorEngineSettings.FRAGMENT_OFF,
            homeText("Off", "خاموش"),
            homeText("Never split. Fastest when the bridge is not filtered.", "هیچ‌وقت تکه نمی‌شود؛ اگر پل فیلتر نیست سریع‌ترین است."),
        ),
    )
    fun applyExit(id: String) {
        val next = when (id) {
            "fast" -> settings.copy(exitCountryCode = "", exitStrict = false, fastExit = true)
            "auto" -> settings.copy(exitCountryCode = "", exitStrict = false, fastExit = false)
            else -> return
        }
        store.save(next)
        if (connection == ConnectionState.CONNECTED || connection == ConnectionState.CONNECTING) {
            VpnController.applyTorExit(context)
        }
    }

    val bridgeSummary = buildString {
        append(
            if (parsedBridges.isEmpty()) {
                homeText("No pasted bridges", "پل دستی ندارید")
            } else {
                homeText("${parsedBridges.size} pasted", "${parsedBridges.size} پل دستی")
            },
        )
        if (ignoredBridgeLines > 0) append(homeText(" · $ignoredBridgeLines ignored", " · $ignoredBridgeLines خط نامعتبر"))
        append(homeText(" · built-in list on", " · فهرست داخلی فعال"))
    }
    val refreshedCount = remember(settings.bridgeRefresh, lastConnect) {
        WebTunnelBridgeParser.parseAll(store.refreshedBridges).size
    }
    val mtus = listOf(
        PowMenuChoice("0", homeText("Default", "پیش‌فرض"), homeText("The app's tunnel MTU.", "MTU خود برنامه.")),
        PowMenuChoice("1280", homeLtr("1280"), homeText("Safest on every network.", "امن‌ترین روی همه شبکه‌ها.")),
        PowMenuChoice("1350", homeLtr("1350"), homeText("Bigger packets, less overhead.", "بسته‌های بزرگ‌تر، سربار کمتر.")),
        PowMenuChoice("1400", homeLtr("1400"), homeText("Largest; may break on some lines.", "بزرگ‌ترین؛ روی بعضی خطوط مشکل دارد.")),
    )
    val onLabel = homeText("on", "روشن")
    val offLabel = homeText("off", "خاموش")
    val onOff = { on: Boolean -> if (on) onLabel else offLabel }
    var flowVisible by remember { mutableStateOf(false) }
    val exitTitle = exitChoices.firstOrNull { it.id == exitId }?.title.orEmpty()
    val fragmentTitle = fragments.firstOrNull { it.id == settings.fragmentMode }?.title.orEmpty()
    val mtuTitle = if (settings.tunMtu == 0) homeText("default", "پیش‌فرض") else settings.tunMtu.toString()

    PowDirectional {
        Column(verticalArrangement = Arrangement.spacedBy(PowGap)) {
            if (!torBinaryReady) {
                TorNotice(
                    homeText(
                        "Tor runtime is not bundled. Connect fails until libtor.so is present.",
                        "موتور Tor در این نسخه نیست؛ تا libtor.so نباشد اتصال برقرار نمی‌شود.",
                    ),
                )
            }
            if (!pluginReady) {
                TorNotice(homeText("WebTunnel plugin is missing (libwebtunnel.so).", "افزونه WebTunnel (libwebtunnel.so) پیدا نشد."))
            }
            TorLiveFlowButton(accent = accent, onClick = { flowVisible = true })
            TorLiveFlowDialog(visible = flowVisible, onDismissRequest = { flowVisible = false })
            TorStatusCard(phase = torStatus.phase, progress = torStatus.bootstrapPercent, described = lastConnect.describe())

            TorSection(
                icon = Icons.Outlined.Public,
                title = homeText("Connection", "اتصال"),
                summary = homeText("Exit: ", "خروجی: ") + exitTitle + homeText(" · watchdog ", " · نگهبان ") + onOff(settings.watchdog),
                accent = accent,
                initiallyExpanded = true,
            ) {
                PowPickerRow(
                    icon = Icons.Outlined.Public,
                    label = homeText("Exit", "خروجی"),
                    sheetTitle = homeText("Tor exit", "خروجی Tor"),
                    choices = exitChoices,
                    selectedId = exitId,
                    accent = accent,
                    onSelect = ::applyExit,
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Favorite,
                    title = homeText("Light watchdog", "نگهبان سبک"),
                    subtitle = homeText("One check a minute, screen on only", "اگه اتصال قطع شد خودش وصلش می‌کنه؛ فقط وقتی صفحه روشنه"),
                    checked = settings.watchdog,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(watchdog = it)) },
                )
            }

            TorSection(
                icon = Icons.Outlined.Hub,
                title = homeText("Bridges", "پل‌ها"),
                summary = bridgeSummary,
                accent = accent,
                summaryColor = if (ignoredBridgeLines > 0) UacColors.ErrorRed else UacColors.TextSecondary,
            ) {
                Column(modifier = Modifier.padding(PowPad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowHint(homeText("Your lines go first; built-in list is always kept", "خطوط شما اول امتحان می‌شوند؛ فهرست داخلی همیشه هست"))
                    OutlinedTextField(
                        value = settings.bridgeLines,
                        onValueChange = { store.save(settings.copy(bridgeLines = it)) },
                        modifier = Modifier.fillMaxWidth().height(148.dp),
                        placeholder = {
                            Text(
                                "webtunnel 192.0.2.10:443 FINGERPRINT url=https://example.com/path",
                                color = UacColors.TextSecondary.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                            )
                        },
                        textStyle = TextStyle(color = Color.White, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                        colors = torFieldColors(),
                    )
                    PowHint(
                        homeText(
                            "Bridges are scored per network (success, failures, latency, speed); failing ones rest 30 min.",
                            "پل‌ها برای هر شبکه امتیاز می‌گیرند (موفقیت، خطا، تأخیر، سرعت)؛ پل‌های خراب ۳۰ دقیقه کنار می‌روند.",
                        ) + if (refreshedCount > 0) homeText(" · $refreshedCount refreshed", " · $refreshedCount پل تازه") else "",
                    )
                }
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.CloudDownload,
                    title = homeText("Daily bridge refresh", "تازه‌سازی روزانه پل‌ها"),
                    subtitle = homeText("Once a day, fetched through Tor itself", "روزی یک بار پل‌های تازه می‌گیره"),
                    checked = settings.bridgeRefresh,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(bridgeRefresh = it)) },
                )
                if (settings.bridgeRefresh) {
                    Column(modifier = Modifier.padding(horizontal = PowPad, vertical = 6.dp)) {
                        TorMiniField(
                            label = homeText("Bridge list URL (empty = Tor Moat)", "آدرس فهرست پل (خالی = Moat تور)"),
                            value = settings.bridgeRefreshUrl,
                            onChange = { store.save(settings.copy(bridgeRefreshUrl = it)) },
                            keyboardType = KeyboardType.Uri,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            TorSection(
                icon = Icons.Outlined.Shield,
                title = homeText("DPI bypass", "دور زدن DPI"),
                summary = homeText("Fragment: ", "تکه‌کردن: ") + fragmentTitle +
                    if (settings.fragmentEnabled) " · ${settings.fragmentLength}B · ${settings.fragmentDelayMs}ms" else "",
                accent = accent,
            ) {
                PowPickerRow(
                    icon = Icons.Outlined.Shield,
                    label = homeText("WebTunnel fragment", "تکه‌کردن WebTunnel"),
                    sheetTitle = homeText("Split the WebTunnel ClientHello", "تکه‌کردن ClientHello پل WebTunnel"),
                    choices = fragments,
                    selectedId = settings.fragmentMode,
                    accent = accent,
                    onSelect = { store.save(settings.copy(fragmentMode = it)) },
                )
                if (settings.fragmentEnabled) {
                    PowRowDivider()
                    Column(modifier = Modifier.padding(horizontal = PowPad, vertical = 8.dp)) {
                        PowHint(homeText("Packet type, bytes per piece, delay between pieces.", "نوع بسته، تعداد بایت هر تکه، فاصله بین تکه‌ها."))
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TorMiniField(
                                label = homeText("Packet", "بسته"),
                                value = settings.fragmentPacket,
                                onChange = { store.save(settings.copy(fragmentPacket = it)) },
                                modifier = Modifier.weight(1.2f),
                            )
                            TorMiniField(
                                label = homeText("Bytes", "بایت"),
                                value = settings.fragmentLength.toString(),
                                onChange = { raw -> raw.toIntOrNull()?.let { store.save(settings.copy(fragmentLength = it)) } },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.8f),
                            )
                            TorMiniField(
                                label = homeText("Delay ms", "تأخیر ms"),
                                value = settings.fragmentDelayMs.toString(),
                                onChange = { raw ->
                                    if (raw.isBlank()) store.save(settings.copy(fragmentDelayMs = 0))
                                    else raw.toIntOrNull()?.let { store.save(settings.copy(fragmentDelayMs = it)) }
                                },
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(0.9f),
                            )
                        }
                    }
                }
            }

            TorSection(
                icon = Icons.Outlined.Speed,
                title = homeText("Speed", "سرعت"),
                summary = homeText("Guards ", "گارد ") + (if (settings.dualGuards) "2" else "1") +
                    homeText(" · MTU ", " · MTU ") + mtuTitle +
                    homeText(" · padding ", " · پدینگ ") + if (settings.reducedPadding) homeText("low", "کم") else homeText("normal", "عادی"),
                accent = accent,
            ) {
                PowSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.AltRoute,
                    title = homeText("Two bridges at once", "دو پل همزمان"),
                    subtitle = homeText("2 guards + conflux for throughput", "با دو پل همزمان سرعت بیشتر می‌شه"),
                    checked = settings.dualGuards,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(dualGuards = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = homeText("Rank bridges by speed", "رتبه‌بندی پل با سرعت"),
                    subtitle = homeText("One 256 KB test per session, screen on", "پل‌های سریع‌تر اول امتحان می‌شن"),
                    checked = settings.throughputScoring,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(throughputScoring = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    title = homeText("Larger buffers", "بافر بزرگ‌تر"),
                    subtitle = homeText("256 KB tunnel buffers + TCP_NODELAY", "ویدیوها روون‌تر پخش می‌شن"),
                    checked = settings.bigBuffers,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(bigBuffers = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Fast relays", "رله‌های پرسرعت"),
                    subtitle = homeText("Middle hops from highest-bandwidth relays", "از سرورهای پرسرعت‌تر Tor استفاده می‌کنه"),
                    checked = settings.fastRelays,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(fastRelays = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Multi-circuit", "چند مداره"),
                    subtitle = homeText("Separate circuit per destination (IsolateDestAddr)", "هر سایت مسیر جدا می‌گیره؛ صفحه‌ها همزمان سریع‌تر باز می‌شن"),
                    checked = settings.multiCircuit,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(multiCircuit = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Replace slow circuits", "تعویض مدار کند"),
                    subtitle = homeText("Under load < 150 KB/s → new circuits (max every 2 min)", "اگه مسیر کند شد، خودش یه مسیر تازه می‌سازه"),
                    checked = settings.slowCircuitSwap,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(slowCircuitSwap = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Reduced padding", "پدینگ کمتر"),
                    subtitle = homeText("Fewer padding cells, less battery", "ترافیک اضافه کمتر؛ باتری کمتر مصرف می‌شه"),
                    checked = settings.reducedPadding,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(reducedPadding = it)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Hub,
                    label = homeText("Tunnel MTU", "MTU تونل"),
                    sheetTitle = homeText("Tor tunnel MTU", "MTU تونل Tor"),
                    choices = mtus,
                    selectedId = settings.tunMtu.toString(),
                    accent = accent,
                    onSelect = { id -> store.save(settings.copy(tunMtu = id.toIntOrNull() ?: 0)) },
                )
            }

            TorSection(
                icon = Icons.Outlined.Timer,
                title = homeText("Stability", "پایداری"),
                summary = homeText("Swap ", "تعویض پل ") + onOff(settings.singleProcess) +
                    homeText(" · stage timeouts ", " · مهلت مرحله‌ای ") + onOff(settings.stageTimeouts) +
                    homeText(" · UDP ", " · UDP ") + if (settings.blockUdp) homeText("blocked", "مسدود") else homeText("open", "باز"),
                accent = accent,
            ) {
                PowSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.AltRoute,
                    title = homeText("Swap bridges without restart", "تعویض پل بدون ری‌استارت"),
                    subtitle = homeText("One Tor process; next batch via control port", "عوض کردن پل بدون قطع و وصل کامل"),
                    checked = settings.singleProcess,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(singleProcess = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Timer,
                    title = homeText("Stage timeouts", "مهلت مرحله‌ای"),
                    subtitle = homeText("Drop a bridge that misses the handshake fast", "پل‌های کند زودتر کنار می‌رن تا اتصال سریع‌تر بشه"),
                    checked = settings.stageTimeouts,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(stageTimeouts = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Block,
                    title = homeText("Block UDP", "مسدود کردن UDP"),
                    subtitle = homeText("Tor has no UDP; apps fall back to TCP faster", "Tor از UDP پشتیبانی نمی‌کنه؛ برنامه‌ها زودتر از TCP استفاده می‌کنن"),
                    checked = settings.blockUdp,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(blockUdp = it)) },
                )
            }

            TorSection(
                icon = Icons.Outlined.BugReport,
                title = homeText("Diagnostics", "عیب‌یابی"),
                summary = homeText("logcat ", "logcat ") + onOff(settings.logcatMirror),
                accent = accent,
            ) {
                PowSwitchRow(
                    icon = Icons.Outlined.BugReport,
                    title = homeText("Logs to logcat", "لاگ در logcat"),
                    subtitle = homeText("Read Tor logs over adb (tag UAC-TOR)", "برای عیب‌یابی با کامپیوتر (adb)"),
                    checked = settings.logcatMirror,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(logcatMirror = it)) },
                )
            }
        }
    }
}

/** Collapsible category card, same look as the PoW «تنظیمات پیشرفته» card. */
@Composable
private fun TorSection(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    accent: Color,
    initiallyExpanded: Boolean = false,
    summaryColor: Color = UacColors.TextSecondary,
    content: @Composable () -> Unit,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(260),
        label = "torSectionChevron",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PowShape)
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (expanded) accent.copy(alpha = 0.35f) else UacColors.Divider.copy(alpha = 0.45f), PowShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { expanded = !expanded }
                .padding(horizontal = PowPad, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PowGap),
        ) {
            PowIconTile(icon, accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    homeLatinDigits(summary),
                    color = summaryColor,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = if (expanded) accent else UacColors.TextSecondary,
                modifier = Modifier.size(22.dp).rotate(chevron),
            )
        }
        PowExpandBody(visible = expanded) { content() }
    }
}
@Composable
private fun TorStatusCard(phase: TorPhase, progress: Int, described: String) {
    val connecting = phase in setOf(TorPhase.STARTING, TorPhase.BOOTSTRAPPING, TorPhase.BRIDGING)
    val tone = when {
        phase == TorPhase.CONNECTED -> UacColors.ConnectedGreen
        phase == TorPhase.FAILED -> UacColors.ErrorRed
        connecting -> UacColors.DisconnectingAmber
        else -> UacColors.TextSecondary
    }
    val title = when {
        phase == TorPhase.CONNECTED -> homeText("Connected", "متصل")
        phase == TorPhase.FAILED -> homeText("Connection failed", "اتصال ناموفق")
        connecting -> homeText("Connecting…", "در حال اتصال…")
        else -> homeText("Not connected", "متصل نیست")
    }
    val detail: String? = when {
        connecting && progress > 0 -> homeText("Bootstrap ", "راه‌اندازی ") + homeLtr("$progress%")
        described.isNotBlank() -> homeText("Last: ", "آخرین: ") + homeLtr(described)
        else -> null
    }
    val pulse = rememberInfiniteTransition(label = "torDot")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (connecting) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "torDotAlpha",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PowShape)
            .background(Brush.horizontalGradient(listOf(tone.copy(alpha = 0.14f), Color(0xD90B1724))))
            .border(1.dp, tone.copy(alpha = 0.28f), PowShape)
            .padding(horizontal = PowPad, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        Box(
            modifier = Modifier.size(28.dp).background(tone.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(10.dp).alpha(dotAlpha).background(tone, CircleShape))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = if (phase == TorPhase.IDLE) UacColors.TextPrimary else tone,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (detail != null) {
                Text(
                    homeLatinDigits(detail),
                    color = UacColors.TextPrimary.copy(alpha = 0.82f),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun TorNotice(text: String) {
    val color = UacColors.ErrorRed
    Text(
        text = text,
        color = color,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), PowShape)
            .border(1.dp, color.copy(alpha = 0.24f), PowShape)
            .padding(PowPad),
    )
}

@Composable
private fun TorMiniField(
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
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = torFieldColors(),
    )
}

@Composable
private fun torFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = UacColors.DisconnectedBlue,
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    focusedLabelColor = UacColors.TextSecondary,
    unfocusedLabelColor = UacColors.TextSecondary,
    cursorColor = UacColors.DisconnectedBlue,
)