package com.uacspoofer.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.settings.AdvancedSettingsData
import com.uacspoofer.mobile.settings.AdvancedSettingsStore
import com.uacspoofer.mobile.settings.CONNECTION_MODE_PROXY
import com.uacspoofer.mobile.settings.CONNECTION_MODE_TUNNEL
import com.uacspoofer.mobile.ui.theme.UacColors

/**
 * Engine 1 (Xray) settings: only the knobs the config builder and VPN service really read.
 * Built-in server identity, edges and internal TUN plumbing stay stored but are not shown.
 */
@Composable
internal fun XraySettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = {
            PowCompactHeader(
                accent = accent,
                onBack = onBack,
                title = homeText("Xray", "Xray"),
                subtitle = homeText("Engine 1 settings", "تنظیمات موتور ۱"),
                icon = Icons.Outlined.Tune,
            )
        },
    ) {
        item { XraySettingsPanel() }
    }
}

@Composable
private fun XraySettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { AdvancedSettingsStore(context) }
    val s by store.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { store.snapshot() }
    val accent = UacColors.DisconnectedBlue
    var resetDialog by remember { mutableStateOf(false) }
    var flowVisible by remember { mutableStateOf(false) }
    fun save(block: (AdvancedSettingsData) -> AdvancedSettingsData) { store.update(block) }

    val onLabel = homeText("on", "روشن")
    val offLabel = homeText("off", "خاموش")
    val onOff = { on: Boolean -> if (on) onLabel else offLabel }
    val proxy = s.connectionMode == CONNECTION_MODE_PROXY

    val modes = listOf(
        PowMenuChoice(CONNECTION_MODE_TUNNEL, homeText("Tunnel (VPN)", "تونل (VPN)"), homeText("Every app on the phone goes through the VPN.", "همه‌ی برنامه‌های گوشی از VPN رد می‌شن.")),
        PowMenuChoice(CONNECTION_MODE_PROXY, homeText("Proxy only", "فقط پروکسی"), homeText("Only apps you set up yourself use it.", "فقط برنامه‌هایی که خودت تنظیم کنی ازش رد می‌شن.")),
    )
    val mtus = listOf(
        PowMenuChoice("1280", homeLtr("1280"), homeText("Safest, works everywhere.", "مطمئن‌ترین؛ همه‌جا کار می‌کنه.")),
        PowMenuChoice("1350", homeLtr("1350"), homeText("A little faster.", "یه کم سریع‌تر.")),
        PowMenuChoice("1400", homeLtr("1400"), homeText("Faster; may fail on some lines.", "سریع‌تر؛ روی بعضی خط‌ها مشکل داره.")),
        PowMenuChoice("1500", homeLtr("1500"), homeText("Wi-Fi only.", "فقط برای وای‌فای.")),
    ).let { list -> if (list.none { it.id == s.tunMtu.toString() }) list + PowMenuChoice(s.tunMtu.toString(), homeLtr(s.tunMtu.toString()), homeText("Custom value", "مقدار دلخواه")) else list }
    val dohChoices = buildList {
        add(PowMenuChoice("https://cloudflare-dns.com/dns-query", homeText("Cloudflare", "Cloudflare"), homeText("Fast and reliable.", "سریع و مطمئن.")))
        add(PowMenuChoice("https://dns.google/dns-query", homeText("Google", "Google"), homeText("Fast and reliable.", "سریع و مطمئن.")))
        add(PowMenuChoice("https://dns.quad9.net/dns-query", homeText("Quad9", "Quad9"), homeText("Blocks known bad sites.", "سایت‌های خطرناک رو می‌بنده.")))
        add(PowMenuChoice("https://dns.adguard-dns.com/dns-query", homeText("AdGuard", "AdGuard"), homeText("Blocks ads too.", "تبلیغ‌ها رو هم می‌بنده.")))
        if (none { it.id == s.dnsResolverUrl }) add(PowMenuChoice(s.dnsResolverUrl, homeText("Custom", "دلخواه"), homeLtr(s.dnsResolverUrl)))
    }
    val strategies = listOf(
        PowMenuChoice("UseIPv4", homeLtr("UseIPv4"), homeText("IPv4 only. Best for Iran.", "فقط IPv4؛ برای ایران بهترینه.")),
        PowMenuChoice("UseIP", homeLtr("UseIP"), homeText("IPv4 and IPv6.", "هم IPv4 هم IPv6.")),
        PowMenuChoice("AsIs", homeLtr("AsIs"), homeText("Let the server decide.", "سرور خودش تصمیم بگیره.")),
    )
    val dohTitle = dohChoices.firstOrNull { it.id == s.dnsResolverUrl }?.title.orEmpty()
    val speedOn = listOfNotNull(
"Fast Open".takeIf { s.tcpFastOpen },
        homeText("big buffer", "بافر بزرگ").takeIf { s.bigBuffers },
        "Mux".takeIf { s.muxEnabled },
    )

    PowDirectional {
        Column(verticalArrangement = Arrangement.spacedBy(PowGap)) {
            XrayLiveFlowButton(accent = accent, onClick = { flowVisible = true })
            XrayLiveFlowDialog(visible = flowVisible, onDismissRequest = { flowVisible = false })

            XraySection(
                icon = Icons.Outlined.Public,
                title = homeText("Connection", "اتصال"),
                summary = (modes.firstOrNull { it.id == s.connectionMode }?.title ?: "") +
                    if (proxy) homeText(" · port ", " · پورت ") + s.socksPort else "",
                accent = accent,
                initiallyExpanded = true,
            ) {
                PowPickerRow(
                    icon = Icons.Outlined.VpnKey,
                    label = homeText("Connection mode", "حالت اتصال"),
                    sheetTitle = homeText("How apps use the connection", "برنامه‌ها چطور از اتصال استفاده کنن"),
                    choices = modes,
                    selectedId = s.connectionMode,
                    accent = accent,
                    onSelect = { id -> save { it.copy(connectionMode = id) } },
                )
                if (proxy) {
                    PowRowDivider()
                    Column(modifier = Modifier.padding(horizontal = PowPad, vertical = 8.dp)) {
                        PowHint(homeText("Set apps to 127.0.0.1 and this port.", "برنامه‌ها رو روی 127.0.0.1 و این پورت تنظیم کن."))
                        XrayIntField(homeText("SOCKS port", "پورت SOCKS"), s.socksPort, 1..65535, Modifier.fillMaxWidth().padding(top = 6.dp)) { v -> save { it.copy(socksPort = v) } }
                    }
                    PowRowDivider()
                    PowSwitchRow(
                        icon = Icons.Outlined.SettingsEthernet,
                        title = homeText("UDP", "UDP"),
                        subtitle = homeText("Needed for voice calls and games.", "برای تماس صوتی و بازی‌ها لازمه."),
                        checked = s.socksUdp,
                        accent = accent,
                        onCheckedChange = { v -> save { it.copy(socksUdp = v) } },
                    )
                }
            }

            XraySection(
                icon = Icons.Outlined.Speed,
                title = homeText("Speed", "سرعت"),
                summary = (if (speedOn.isEmpty()) homeText("All off", "همه خاموش") else speedOn.joinToString("، ")) + " · MTU ${s.tunMtu}",
                accent = accent,
            ) {
PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("TCP Fast Open", "TCP Fast Open"),
                    subtitle = homeText("Sites open a little sooner.", "سایت‌ها یه کم زودتر باز می‌شن."),
                    checked = s.tcpFastOpen,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(tcpFastOpen = v) } },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.SettingsEthernet,
                    title = homeText("Larger buffers", "بافر بزرگ‌تر"),
                    subtitle = homeText("Videos play more smoothly.", "ویدیوها روون‌تر پخش می‌شن."),
                    checked = s.bigBuffers,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(bigBuffers = v) } },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Timer,
                    title = homeText("Warm up on screen on", "آماده‌سازی با روشن شدن صفحه"),
                    subtitle = homeText("When you unlock, the connection is ready to answer faster.", "با روشن شدن گوشی، اتصال آماده می‌شه تا سریع‌تر جواب بده."),
                    checked = s.prewarm,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(prewarm = v) } },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Hub,
                    title = homeText("Mux", "Mux"),
                    subtitle = homeText("Sends several connections together; sometimes faster. Not for ECH.", "چند اتصال رو با هم می‌فرسته؛ گاهی سرعت رو بیشتر می‌کنه. روی ECH اثری نداره."),
                    checked = s.muxEnabled,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(muxEnabled = v) } },
                )
                if (s.muxEnabled) {
                    Column(modifier = Modifier.padding(horizontal = PowPad, vertical = 6.dp)) {
                        XrayIntField(homeText("Connections per Mux", "تعداد اتصال در Mux"), s.muxConcurrency, 1..1024, Modifier.fillMaxWidth()) { v -> save { it.copy(muxConcurrency = v) } }
                    }
                }
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Lan,
                    label = homeText("MTU", "MTU"),
                    sheetTitle = homeText("Packet size (MTU)", "اندازه‌ی بسته (MTU)"),
                    choices = mtus,
                    selectedId = s.tunMtu.toString(),
                    accent = accent,
                    onSelect = { id -> id.toIntOrNull()?.let { v -> save { it.copy(tunMtu = v) } } },
                )
                Column(modifier = Modifier.padding(start = PowPad, end = PowPad, bottom = 8.dp)) {
                    PowHint(homeText("Packet size; lower it if some sites don't open.", "اندازه‌ی بسته‌ها؛ اگه بعضی سایت‌ها باز نمی‌شن کمش کن."))
                }
            }

            XraySection(
                icon = Icons.Outlined.Dns,
                title = homeText("DNS", "DNS"),
                summary = dohTitle + if (s.dnsServeStale) homeText(" · smart cache", " · کش هوشمند") else "",
                accent = accent,
            ) {
                PowPickerRow(
                    icon = Icons.Outlined.Dns,
                    label = homeText("DNS server", "سرور DNS"),
                    sheetTitle = homeText("Finds the address of each site", "آدرس هر سایت رو پیدا می‌کنه"),
                    choices = dohChoices,
                    selectedId = s.dnsResolverUrl,
                    accent = accent,
                    onSelect = { id -> save { it.copy(dnsResolverUrl = id) } },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = homeText("Smart DNS cache", "کش هوشمند DNS"),
                    subtitle = homeText("Sites you visited before open instantly.", "سایت‌هایی که قبلاً رفتی فوری باز می‌شن."),
                    checked = s.dnsServeStale,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(dnsServeStale = v) } },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("IPv4 only", "فقط IPv4"),
                    subtitle = homeText("Avoids slow IPv6 attempts; best left on.", "جلوی کندی‌های IPv6 رو می‌گیره؛ بهتره روشن بمونه."),
                    checked = s.ipv4Only,
                    accent = accent,
                    onCheckedChange = { v -> save { it.copy(ipv4Only = v) } },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Public,
                    label = homeText("Server address lookup", "پیدا کردن آدرس سرور"),
                    sheetTitle = homeText("If unsure, keep UseIPv4", "اگه مطمئن نیستی، UseIPv4 بمونه"),
                    choices = strategies,
                    selectedId = s.domainStrategy,
                    accent = accent,
                    onSelect = { id -> save { it.copy(domainStrategy = id) } },
                )
            }

            XraySection(
                icon = Icons.Outlined.Timer,
                title = homeText("Stability", "پایداری"),
                summary = homeText("Keep-alive every ${s.keepAliveIntervalSeconds} s", "زنده نگه‌داشتن اتصال هر ${s.keepAliveIntervalSeconds} ثانیه"),
                accent = accent,
            ) {
                Column(modifier = Modifier.padding(horizontal = PowPad, vertical = 8.dp)) {
                    PowHint(homeText("Keeps the connection alive so it doesn't drop. Lower = fewer drops, a bit more battery.", "اتصال رو زنده نگه می‌داره تا وسط کار قطع نشه؛ عدد کمتر یعنی قطعی کمتر ولی کمی باتری بیشتر."))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XrayIntField(homeText("First check (s)", "اولین بررسی (ثانیه)"), s.keepAliveIdleSeconds, 1..7200, Modifier.weight(1f)) { v -> save { it.copy(keepAliveIdleSeconds = v) } }
                        XrayIntField(homeText("Then every (s)", "بعد هر (ثانیه)"), s.keepAliveIntervalSeconds, 1..600, Modifier.weight(1f)) { v -> save { it.copy(keepAliveIntervalSeconds = v) } }
                    }
                }
            }

            XraySection(
                icon = Icons.Outlined.BugReport,
                title = homeText("Troubleshooting", "عیب‌یابی"),
                summary = homeText("Changes apply on the next connect", "تغییرات از اتصال بعدی اعمال می‌شن"),
                accent = accent,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { resetDialog = true }
                        .padding(horizontal = PowPad, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PowGap),
                ) {
                    PowIconTile(Icons.Outlined.RestartAlt, UacColors.ErrorRed)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(homeText("Restore defaults", "بازگشت به پیش‌فرض"), color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(homeText("Every Xray setting goes back to the start.", "همه‌ی تنظیمات Xray برمی‌گرده به حالت اول."), color = UacColors.TextSecondary, fontSize = 11.5.sp, maxLines = 2)
                    }
                }
            }
        }
    }
    if (resetDialog) {
        AlertDialog(
            onDismissRequest = { resetDialog = false },
            title = { Text(homeText("Restore defaults?", "بازگشت به پیش‌فرض؟")) },
            text = { Text(homeText("Every Xray setting goes back to the tested defaults.", "همه تنظیمات Xray به مقادیر تست‌شده برمی‌گردند.")) },
            confirmButton = { TextButton({ store.resetDefaults(); resetDialog = false }) { Text(homeText("Restore", "بازگردانی"), color = UacColors.ErrorRed) } },
            dismissButton = { TextButton({ resetDialog = false }) { Text(homeText("Cancel", "انصراف")) } },
        )
    }
}
@Composable
private fun XraySection(
    icon: ImageVector,
    title: String,
    summary: String,
    accent: Color,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(260), label = "xraySectionChevron")
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
                Text(homeLatinDigits(summary), color = UacColors.TextSecondary, fontSize = 11.5.sp, maxLines = 2, lineHeight = 15.sp)
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

/** Number field that keeps what the user types and only saves a valid in-range value. */
@Composable
private fun XrayIntField(label: String, value: Int, range: IntRange, modifier: Modifier = Modifier, onValid: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter(Char::isDigit).take(6)
            text.toIntOrNull()?.takeIf { it in range && it != value }?.let(onValid)
        },
        modifier = modifier,
        label = { Text(label, fontSize = 11.sp) },
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
