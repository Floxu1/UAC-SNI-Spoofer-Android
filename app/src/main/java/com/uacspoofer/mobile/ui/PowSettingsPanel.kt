package com.uacspoofer.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.pow.AetherNative
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowEngineSettings
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowIdentityStatus
import com.uacspoofer.mobile.engine.pow.PowLearnedPathSnapshot
import com.uacspoofer.mobile.engine.pow.PowPathMemory
import com.uacspoofer.mobile.ui.theme.UacColors

private data class PowMenuChoice(val id: String, val title: String)

@Composable
internal fun PowSettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    ToolPageScaffold(
        accent = accent,
        header = {
            ToolPageHeader(
                title = homeText("UAC PoW", "UAC PoW"),
                subtitle = homeText("How it connects", "چطوری وصل بشه؟"),
                icon = Icons.Outlined.Hub,
                accent = accent,
                onMenuClick = onBack,
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
            )
        },
    ) {
        item { PowSettingsPanel() }
    }
}

@Composable
private fun PowSettingsPanel() {
    val context = LocalContext.current
    val store = remember(context) { PowEngineStore.get(context) }
    val settings by store.settings.collectAsStateWithLifecycle()
    val lastConnect by store.lastConnect.collectAsStateWithLifecycle()
    val powStatus by PowStatusStore.status.collectAsStateWithLifecycle()
    val sessionUp = powStatus.phase == PowPhase.CONNECTED
    val cleanPeers = remember(settings.linkRecipe, sessionUp) {
        PowCoreConfig.cleanH2Peers(context)
    }
    val accent = UacColors.DisconnectedBlue
    val firewalls = listOf(
        PowMenuChoice(PowCoreConfig.FIREWALL_AUTO, homeText("Automatic", "خودکار")),
        PowMenuChoice(PowCoreConfig.FIREWALL_IRANCELL, homeText("Irancell", "ایرانسل")),
        PowMenuChoice(PowCoreConfig.FIREWALL_MCI, homeText("Hamrah Aval", "همراه اول")),
    )
    val recipes = listOf(
        PowMenuChoice(PowCoreConfig.RECIPE_AUTO, homeText("Automatic", "خودکار")),
        PowMenuChoice(PowCoreConfig.RECIPE_WARP_V6, "WireGuard · IPv6"),
        PowMenuChoice(PowCoreConfig.RECIPE_WARP_ANY, "WireGuard · IPv4 + IPv6"),
        PowMenuChoice(PowCoreConfig.RECIPE_WOW_V6, "WARP-in-WARP · IPv6"),
        PowMenuChoice(PowCoreConfig.RECIPE_WOW_ANY, "WARP-in-WARP · IPv4 + IPv6"),
        PowMenuChoice(PowCoreConfig.RECIPE_MASQUE_H2, "MASQUE · HTTP/2"),
        PowMenuChoice(PowCoreConfig.RECIPE_MASQUE_H2_SAVED, "MASQUE · HTTP/2 · saved"),
        PowMenuChoice(PowCoreConfig.RECIPE_MASQUE_H3, "MASQUE · HTTP/3"),
        PowMenuChoice(PowCoreConfig.RECIPE_MASQUE_H2_SPLIT, "MASQUE · HTTP/2 · split"),
    )
    val scans = listOf(
        PowMenuChoice(PowCoreConfig.SCAN_TURBO, homeText("Fast", "سریع")),
        PowMenuChoice(PowCoreConfig.SCAN_BALANCED, homeText("Balanced", "بالانس")),
        PowMenuChoice(PowCoreConfig.SCAN_THOROUGH, homeText("Deep", "عمیق")),
    )
    val exits = listOf(
        PowMenuChoice("chain", homeText("WARP, then Psiphon", "وارپ، بعد سایفون")),
        PowMenuChoice("warp", homeText("WARP only", "فقط وارپ")),
    )
    val fetches = listOf(
        PowMenuChoice(PowCoreConfig.KEY_ECH, "ECH"),
        PowMenuChoice(PowCoreConfig.KEY_FRAGMENT, homeText("Split hello", "قطع سلام")),
        PowMenuChoice(PowCoreConfig.KEY_EXTERNAL, homeText("Current network", "شبکه فعلی")),
    )

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!AetherNative.available) {
            Text(
                homeText(AetherNative.unavailableReason, "این گوشی از UAC PoW پشتیبانی نمی‌کنه."),
                color = UacColors.ErrorRed,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UacColors.ErrorRed.copy(alpha = 0.08f), ToolCardShape)
                    .border(1.dp, UacColors.ErrorRed.copy(alpha = 0.24f), ToolCardShape)
                    .padding(12.dp),
            )
        }
        SettingsCard {
            SectionLabel(homeText("Connection", "اتصال"))
            PowDropField(
                label = homeText("Firewall", "فایروال"),
                choices = firewalls,
                selectedId = settings.firewallProfile,
                accent = accent,
                onSelect = { store.save(settings.copy(firewallProfile = it)) },
            )
            PowDropField(
                label = homeText("Method", "روش"),
                choices = recipes,
                selectedId = settings.linkRecipe,
                accent = accent,
                onSelect = { store.save(settings.copy(linkRecipe = it)) },
            )
            Text(
                if (settings.linkRecipe == PowCoreConfig.RECIPE_AUTO && sessionUp && lastConnect.protocol.isNotBlank()) {
                    homeText("Connected with", "وصل شد با") + " " + lastConnect.describe()
                } else if (settings.linkRecipe == PowCoreConfig.RECIPE_AUTO) {
                    homeText(
                        "After a connect, the mode that came up is shown here.",
                        "بعد از وصل، روشی که بالا آمده اینجا می‌آید.",
                    )
                } else {
                    recipeHint(settings.linkRecipe)
                },
                color = if (settings.linkRecipe == PowCoreConfig.RECIPE_AUTO && sessionUp && lastConnect.protocol.isNotBlank()) {
                    UacColors.ConnectedGreen
                } else {
                    UacColors.TextSecondary
                },
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                style = plainOrder,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (cleanPeers.isEmpty()) {
                    homeText(
                        "Clean HTTP/2 list is empty. A connect that stays up adds the address.",
                        "لیست آی‌پی تمیز HTTP/2 خالی است. وصلی که بالا بماند آدرس را اضافه می‌کند.",
                    )
                } else {
                    homeText("Clean HTTP/2 list", "لیست آی‌پی تمیز HTTP/2") +
                        ": " + cleanPeers.joinToString(", ")
                },
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                style = plainOrder,
                modifier = Modifier.fillMaxWidth(),
            )
            PowDropField(
                label = homeText("Search", "سرچ"),
                choices = scans,
                selectedId = settings.scanMode,
                accent = accent,
                onSelect = { store.save(settings.copy(scanMode = it)) },
            )
            PowDropField(
                label = homeText("Exit", "خروجی"),
                choices = exits,
                selectedId = if (settings.chainPsiphon) "chain" else "warp",
                accent = accent,
                onSelect = { store.save(settings.copy(chainPsiphon = it == "chain")) },
            )
            Text(
                if (settings.chainPsiphon) {
                    homeText(
                        "Psiphon carries the exit. Plain WARP or MASQUE HTTP/2 suits this.",
                        "Psiphon بعد از WARP. برای این حالت WireGuard یا MASQUE HTTP/2.",
                    )
                } else {
                    homeText(
                        "Traffic leaves through WARP. Psiphon stays off.",
                        "WARP تنها. Psiphon خاموش می‌ماند.",
                    )
                },
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                style = plainOrder,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    homeText("Optimized mode", "حالت بهینه"),
                    color = UacColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.optimizedMode,
                    onCheckedChange = { store.save(settings.copy(optimizedMode = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = accent,
                        checkedThumbColor = Color.White,
                    ),
                )
            }
        }
        PowKeyCard(store = store, settings = settings, fetches = fetches, accent = accent)
        PowLearnedPathsCard(store = store, accent = accent)
    }
}

@Composable
private fun recipeHint(recipe: String): String = when (recipe) {
    PowCoreConfig.RECIPE_WARP_V6 -> homeText(
        "WireGuard on IPv6 only. It stops quickly if this phone has no IPv6.",
        "WireGuard فقط IPv6. اگر IPv6 نباشد زود رد می‌شود.",
    )
    PowCoreConfig.RECIPE_WARP_ANY -> homeText(
        "WireGuard on IPv4 and IPv6.",
        "WireGuard روی IPv4 و IPv6.",
    )
    PowCoreConfig.RECIPE_WOW_V6 -> homeText(
        "WARP inside WARP, IPv6 only.",
        "WARP-in-WARP فقط IPv6.",
    )
    PowCoreConfig.RECIPE_WOW_ANY -> homeText(
        "WARP inside WARP, either IP type.",
        "WARP-in-WARP روی IPv4 و IPv6.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2 -> homeText(
        "Saved clean addresses first, then an HTTP/2 scan that adds any new one.",
        "اول آی‌پی‌های تمیز ذخیره‌شده، بعد اسکن HTTP/2. آی‌پی جدید به لیست اضافه می‌شود.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2_SAVED -> homeText(
        "Only the saved clean addresses. No scan. If this fails, the saved address is the stuck stage.",
        "فقط آی‌پی‌های تمیز ذخیره‌شده. اسکن نمی‌شود. اگر این رد شد، گیر همان آدرس ذخیره‌شده است.",
    )
    PowCoreConfig.RECIPE_MASQUE_H3 -> homeText(
        "MASQUE over HTTP/3 and IPv4.",
        "MASQUE HTTP/3 روی IPv4.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2_SPLIT -> homeText(
        "MASQUE over HTTP/2 with the ClientHello split.",
        "MASQUE HTTP/2 با سلام قطع‌شده.",
    )
    else -> homeText(
        "Irancell: MASQUE HTTP/3 then WARP. Hamrah Aval: MASQUE HTTP/2, saved addresses first.",
        "ایرانسل: MASQUE HTTP/3 بعد WARP. همراه اول: MASQUE HTTP/2، اول آی‌پی تمیز.",
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = { content() },
    )
}

@Composable
private fun PowDropField(
    label: String,
    choices: List<PowMenuChoice>,
    selectedId: String,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId } ?: choices.first()
    val shape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label,
            color = UacColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, if (open) accent else Color.White.copy(alpha = 0.12f), shape)
                    .clickable { open = true }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    selected.title,
                    color = UacColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = plainOrder,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = UacColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier
                    .widthIn(min = 220.dp)
                    .background(Color(0xFF142231)),
            ) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                choice.title,
                                color = if (choice.id == selected.id) Color.White else UacColors.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (choice.id == selected.id) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = plainOrder,
                            )
                        },
                        onClick = {
                            open = false
                            if (choice.id != selected.id) onSelect(choice.id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PowKeyCard(
    store: PowEngineStore,
    settings: PowEngineSettings,
    fetches: List<PowMenuChoice>,
    accent: Color,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val identity = remember(revision) {
        runCatching { PowIdentityStatus.read(context) }.getOrDefault(PowIdentityStatus.EMPTY)
    }
    var expanded by remember { mutableStateOf(!identity.enrolled) }
    var note by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fetchingLabel = homeText("Asking for the key…", "دارم کلید را می‌گیرم…")
    val busyLabel = homeText("Disconnect PoW first.", "اول اتصال PoW را قطع کن.")
    val fetchedLabel = homeText("Key saved.", "کلید ذخیره شد.")
    val failedLabel = homeText("Key request failed.", "گرفتن کلید نشد.")
    val certAskingLabel = homeText("Asking for a certificate…", "دارم گواهی تازه می‌گیرم…")
    val certButtonLabel = homeText("Get a new certificate", "الان گواهی تازه بگیر")
    val certSavedLabel = homeText("New certificate saved.", "گواهی تازه ذخیره شد.")
    val certFailedLabel = homeText("Certificate request failed.", "گرفتن گواهی نشد.")
    val status = when {
        identity.enrolled && identity.masqueCertificate -> homeText("Saved", "گرفته شده")
        identity.enrolled -> homeText("WARP key only", "فقط کلید وارپ")
        identity.present -> homeText("Incomplete", "ناقص")
        else -> homeText("Not saved", "گرفته نشده")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    homeText("Account key", "کلید حساب"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    status,
                    color = if (identity.enrolled) UacColors.ConnectedGreen else UacColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!identity.present) {
                    Text(
                        homeText(
                            "The next connect asks for a key. One key lasts about a year.",
                            "وصل بعدی کلید را می‌گیرد. یک کلید حدود یک سال می‌ماند.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    if (identity.deviceId.isNotBlank()) {
                        PowMemoryLine(homeText("Device", "دستگاه"), shortId(identity.deviceId))
                    }
                    if (identity.ipv4.isNotBlank()) PowMemoryLine("IPv4", identity.ipv4)
                    if (identity.ipv6.isNotBlank()) PowMemoryLine("IPv6", identity.ipv6)
                    if (identity.endpoint.isNotBlank()) {
                        PowMemoryLine(homeText("Endpoint", "آدرس"), identity.endpoint)
                    }
                    if (identity.organization.isNotBlank()) {
                        PowMemoryLine(homeText("Org", "سازمان"), identity.organization)
                    }
                    PowMemoryLine(
                        homeText("MASQUE cert", "گواهی MASQUE"),
                        if (identity.masqueCertificate) {
                            identity.issuedLabel().ifBlank { homeText("Yes", "دارد") }
                        } else {
                            homeText("Missing", "ندارد")
                        },
                    )
                    if (identity.peerPublicPrefix.isNotBlank()) {
                        PowMemoryLine(homeText("Peer", "همتا"), identity.peerPublicPrefix)
                    }
                }
                Text(
                    homeText(
                        "Gets the key from 188.114.97.6 and enrolls the MASQUE certificate on it.",
                        "کلید را از 188.114.97.6 می‌گیرد و گواهی MASQUE را روی همان کلید می‌سازد.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    style = plainOrder,
                )
                Text(
                    if (fetching) fetchingLabel else homeText("Get key now", "الان کلید را بگیر"),
                    color = if (fetching) UacColors.TextSecondary else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (fetching) Color.White.copy(alpha = 0.06f) else accent)
                        .clickable(enabled = !fetching) {
                            if (AetherNative.isRunning()) {
                                note = busyLabel
                                return@clickable
                            }
                            fetching = true
                            note = fetchingLabel
                            scope.launch {
                                val seen = mutableSetOf<String>()
                                fun flushKeyLog() {
                                    AetherNative.lastLog().lineSequence().forEach { line ->
                                        if (line.startsWith("Key:") && seen.add(line)) {
                                            AppLogRepository.info(LogSource.POW, line.take(500))
                                        }
                                    }
                                }
                                val failure = withContext(Dispatchers.IO) {
                                    coroutineScope {
                                        val work = async {
                                            runCatching {
                                            AetherNative.prepare(
                                                PowCoreConfig.keyRequestJson(context, settings, "masque"),
                                            )
                                            }.exceptionOrNull()
                                        }
                                        while (work.isActive) {
                                            flushKeyLog()
                                            delay(400)
                                        }
                                        flushKeyLog()
                                        work.await()
                                    }
                                }
                                flushKeyLog()
                                fetching = false
                                revision += 1
                                note = if (failure == null) {
                                    fetchedLabel
                                } else {
                                    AetherNative.lastError().ifBlank { failure.message ?: failedLabel }
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                )
                Text(
                    homeText(
                        "Makes a new MASQUE certificate on the key you already have. Connect after it says saved.",
                        "روی کلیدی که هست یک گواهی تازه می‌سازد. وقتی نوشت ذخیره شد، بعدش وصل شو.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    style = plainOrder,
                )
                Text(
                    if (fetching) certAskingLabel else certButtonLabel,
                    color = if (fetching) UacColors.TextSecondary else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (fetching) Color.White.copy(alpha = 0.06f) else accent)
                        .clickable(enabled = !fetching) {
                            if (AetherNative.isRunning()) {
                                note = busyLabel
                                return@clickable
                            }
                            fetching = true
                            note = certAskingLabel
                            scope.launch {
                                val seen = mutableSetOf<String>()
                                fun flushCertLog() {
                                    AetherNative.lastLog().lineSequence().forEach { line ->
                                        if ((line.startsWith("Cert:") || line.startsWith("Key:")) && seen.add(line)) {
                                            AppLogRepository.info(LogSource.POW, line.take(500))
                                        }
                                    }
                                }
                                val failure = withContext(Dispatchers.IO) {
                                    coroutineScope {
                                        val work = async {
                                            runCatching {
                                                AetherNative.prepare(PowCoreConfig.certRenewJson(context, settings))
                                            }.exceptionOrNull()
                                        }
                                        while (work.isActive) {
                                            flushCertLog()
                                            delay(400)
                                        }
                                        flushCertLog()
                                        work.await()
                                    }
                                }
                                flushCertLog()
                                fetching = false
                                revision += 1
                                note = if (failure == null) {
                                    certSavedLabel
                                } else {
                                    AetherNative.lastError().ifBlank { failure.message ?: certFailedLabel }
                                }
                            }
                        }
                        .padding(vertical = 10.dp),
                )
                PowDropField(
                    label = homeText("Next key request", "گرفتن کلید بعدی"),
                    choices = fetches,
                    selectedId = settings.keyFetch,
                    accent = accent,
                    onSelect = { store.save(settings.copy(keyFetch = it)) },
                )
                Text(
                    when (settings.keyFetch) {
                        PowCoreConfig.KEY_FRAGMENT -> homeText(
                            "The API name stays visible and the ClientHello is split.",
                            "اسم API دیده می‌شود و سلام قطع می‌شود.",
                        )
                        PowCoreConfig.KEY_EXTERNAL -> homeText(
                            "Turn another VPN on first, connect once, then turn that VPN off.",
                            "اول یک وی‌پی‌ان دیگر را روشن کن، یک بار وصل شو، بعد آن را خاموش کن.",
                        )
                        else -> homeText(
                            "The API name is hidden. This is the usual way through a filter.",
                            "اسم API پنهان می‌شود. راه معمول رد شدن از فیلتر همین است.",
                        )
                    },
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
                val deletedNote = homeText("Deleted. The next connect asks again.", "پاک شد. وصل بعدی دوباره می‌گیرد.")
                val missingNote = homeText("No key was saved.", "کلیدی ذخیره نشده بود.")
                Text(
                    homeText("Delete saved key", "پاک کردن کلید ذخیره‌شده"),
                    color = accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            val removed = PowIdentityStatus.forget(context)
                            revision += 1
                            note = if (removed) deletedNote else missingNote
                        }
                        .padding(vertical = 4.dp),
                )
                if (note.isNotBlank()) {
                    Text(note, color = UacColors.ConnectedGreen, fontSize = 12.sp)
                }
            }
        }
    }
}

private val plainOrder = androidx.compose.ui.text.TextStyle(textDirection = TextDirection.Content)

private fun shortId(value: String): String =
    if (value.length <= 18) value else value.take(8) + "…" + value.takeLast(4)

@Composable
private fun PowLearnedPathsCard(
    store: PowEngineStore,
    accent: Color,
) {
    var revision by remember { mutableIntStateOf(0) }
    val snapshot = remember(revision) {
        runCatching { store.learnedPaths() }.getOrElse { PowLearnedPathSnapshot.EMPTY }
    }
    var expanded by remember { mutableStateOf(false) }
    var forgotten by remember { mutableStateOf(false) }
    val summary = snapshot.summary().ifBlank { homeText("None yet", "هنوز خالی") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    homeText("Learned paths", "مسیرهای یادگرفته"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!expanded) {
                    Text(
                        summary,
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (snapshot.isEmpty) {
                    Text(
                        homeText(
                            "After a successful connect, the winning hop shows up here.",
                            "بعد از وصل موفق، مسیر برنده اینجا می‌آید.",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                } else {
                    snapshot.globalOuter?.let { outer ->
                        PowMemoryLine(homeText("Outer", "لایه بیرونی"), PowCoreConfig.outerLabel(outer))
                    }
                    snapshot.networks.take(3).forEach { row ->
                        val outer = row.outer?.let(PowCoreConfig::outerLabel) ?: "—"
                        val rtt = row.rttMs?.let { " · ${it}ms" }.orEmpty()
                        PowMemoryLine(PowPathMemory.networkLabel(row.networkKey), "$outer$rtt")
                    }
                    snapshot.masqueLastPeer?.let { peer ->
                        val hop = when (snapshot.globalOuter) {
                            "wireguard" -> "WireGuard"
                            "gool" -> "WoW"
                            else -> "MASQUE"
                        }
                        PowMemoryLine(hop, peer)
                    }
                    snapshot.wowLastPeer?.let { PowMemoryLine("WoW", it) }
                    if (snapshot.masqueCachedGateways.isNotEmpty()) {
                        val extra = snapshot.masqueCachedGateways.size - 2
                        val value = buildString {
                            append(snapshot.masqueCachedGateways.take(2).joinToString(" · "))
                            if (extra > 0) append(" +$extra")
                        }
                        PowMemoryLine(homeText("Cached", "کش"), value)
                    }
                    Text(
                        homeText("Forget", "پاک کردن"),
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                store.forgetLearnedPaths()
                                revision += 1
                                forgotten = true
                            }
                            .padding(vertical = 4.dp),
                    )
                }
                if (forgotten && snapshot.isEmpty) {
                    Text(
                        homeText("Cleared.", "پاک شد."),
                        color = UacColors.ConnectedGreen,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PowMemoryLine(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            value,
            color = UacColors.TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
