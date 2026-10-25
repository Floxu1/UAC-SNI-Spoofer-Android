package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import com.uacspoofer.mobile.engine.pow.MtuAutoScanner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.pow.AetherNative
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowEngineSettings
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowIdentityStatus
import com.uacspoofer.mobile.engine.pow.PowLearnedPathSnapshot
import com.uacspoofer.mobile.engine.pow.PowPathMemory
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PowMenuChoice(val id: String, val title: String, val hint: String = "")

/** One radius everywhere on this screen; pills are capsules. */
internal val PowShape = RoundedCornerShape(12.dp)
internal val PowTileShape = RoundedCornerShape(10.dp)
internal val PowPad = 12.dp
internal val PowGap = 10.dp
internal val PowHintSize = 12.sp
internal val PowHintLine = 17.sp
internal val PowBorder = Color.White.copy(alpha = 0.08f)
internal val PowSheetColor = Color(0xFF101C29)
internal val PowCardBrush = Brush.linearGradient(listOf(Color(0xE6142231), Color(0xD90B1724)))

@Composable
internal fun PowSettingsScreen(onBack: () -> Unit) {
    val accent = UacColors.DisconnectedBlue
    var pathVisible by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    ToolPageScaffold(
        accent = accent,
        verticalSpacing = PowGap,
        header = { PowCompactHeader(accent = accent, onBack = onBack) },
    ) {
        item { PowLivePathButton(accent = accent, onClick = { pathVisible = true }) }
        item { PowSettingsPanel() }
    }
    PowLivePathDialog(visible = pathVisible, onDismissRequest = { pathVisible = false })
}

@Composable
internal fun PowCompactHeader(
    accent: Color,
    onBack: () -> Unit,
    title: String = homeText("UAC PoW", "UAC PoW"),
    subtitle: String = homeText("Connection settings", "تنظیمات اتصال"),
    icon: ImageVector = Icons.Outlined.Hub,
) {
    val backLabel = homeText("Back to settings", "برگشت به تنظیمات")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0x99101C29))
                .border(1.dp, PowBorder, CircleShape)
                .clickable(onClick = onBack)
                .openDrawerOnDpadLeft(onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = backLabel,
                tint = UacColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(accent.copy(alpha = 0.14f), PowShape)
                .border(1.dp, accent.copy(alpha = 0.30f), PowShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = UacColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
            Text(
                subtitle,
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Start,
                maxLines = 1,
            )
        }
    }
}

/**
 * Lays the panel out right-to-left in Persian so labels, chevrons and switches sit
 * where a Persian reader expects them, and aligns every paragraph to its start.
 */
@Composable
internal fun PowDirectional(content: @Composable () -> Unit) {
    val persian = LocalHomePersian.current
    CompositionLocalProvider(
        LocalLayoutDirection provides if (persian) LayoutDirection.Rtl else LayoutDirection.Ltr,
        LocalTextStyle provides LocalTextStyle.current.copy(textAlign = TextAlign.Start),
        content = content,
    )
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
        PowMenuChoice(
            PowCoreConfig.FIREWALL_AUTO,
            homeText("Automatic", "خودکار"),
            homeText("Detects the network's filter on its own.", "فیلتر شبکه خودکار تشخیص داده می‌شود."),
        ),
        PowMenuChoice(
            PowCoreConfig.FIREWALL_IRANCELL,
            homeText("Irancell", "ایرانسل"),
            homeText("Routes tuned for the Irancell firewall.", "مسیرهای مناسب فایروال ایرانسل."),
        ),
        PowMenuChoice(
            PowCoreConfig.FIREWALL_MCI,
            homeText("Hamrah Aval", "همراه اول"),
            homeText("Routes tuned for the Hamrah Aval firewall.", "مسیرهای مناسب فایروال همراه اول."),
        ),
    )
    val recipes = listOf(
        PowCoreConfig.RECIPE_AUTO to homeText("Automatic", "خودکار"),
        PowCoreConfig.RECIPE_WARP_V6 to homeLtr("WireGuard · IPv6"),
        PowCoreConfig.RECIPE_WARP_ANY to homeLtr("WireGuard · IPv4 + IPv6"),
        PowCoreConfig.RECIPE_WOW_V6 to homeLtr("WARP-in-WARP · IPv6"),
        PowCoreConfig.RECIPE_WOW_ANY to homeLtr("WARP-in-WARP · IPv4 + IPv6"),
        PowCoreConfig.RECIPE_MASQUE_H2 to homeLtr("MASQUE · HTTP/2"),
        PowCoreConfig.RECIPE_MASQUE_H2_SAVED to
            homeText("MASQUE · HTTP/2 · saved only", "${homeLtr("MASQUE · HTTP/2")} · فقط ذخیره‌شده"),
        PowCoreConfig.RECIPE_MASQUE_H3 to homeLtr("MASQUE · HTTP/3"),
        PowCoreConfig.RECIPE_MASQUE_H2_SPLIT to
            homeText("MASQUE · HTTP/2 · split hello", "${homeLtr("MASQUE · HTTP/2")} · تکه‌تکه"),
    ).map { (id, title) -> PowMenuChoice(id, title, recipeHint(id)) }
    val scans = listOf(
        PowMenuChoice(
            PowCoreConfig.SCAN_TURBO,
            homeText("Fast", "سریع"),
            homeText("Short scan, quickest connect.", "اسکن کوتاه و اتصال سریع‌تر."),
        ),
        PowMenuChoice(
            PowCoreConfig.SCAN_BALANCED,
            homeText("Balanced", "متعادل"),
            homeText("A balance of speed and coverage.", "تعادل بین سرعت و تعداد آی‌پی‌های بررسی‌شده."),
        ),
        PowMenuChoice(
            PowCoreConfig.SCAN_THOROUGH,
            homeText("Thorough", "دقیق"),
            homeText("Checks more addresses, takes longer.", "آی‌پی‌های بیشتری بررسی می‌شود و زمان بیشتری می‌برد."),
        ),
    )
    val exits = listOf(
        PowMenuChoice(
            "chain",
            homeLtr("WARP → Psiphon"),
            homeText(
                "Traffic goes through WARP, then exits via Psiphon. Works best with WireGuard or MASQUE HTTP/2.",
                "ترافیک از WARP وارد Psiphon می‌شود. با WireGuard یا MASQUE HTTP/2 بهتر کار می‌کند.",
            ),
        ),
        PowMenuChoice(
            "warp",
            homeText("WARP only", "فقط WARP"),
            homeText(
                "Traffic exits straight through WARP. Psiphon stays off.",
                "ترافیک مستقیم از WARP خارج می‌شود و Psiphon خاموش می‌ماند.",
            ),
        ),
        PowMenuChoice(
            "psiphon",
            homeText("Psiphon only", "فقط Psiphon"),
            homeText(
                "Psiphon connects on its own; WARP is never started. Lighter on battery, harder through DPI.",
                "Psiphon بدون WARP وصل می‌شود. مصرف باتری کمتر، ولی عبور از DPI سخت‌تر.",
            ),
        ),
    )
    val fetches = listOf(
        PowMenuChoice(
            PowCoreConfig.KEY_AUTO,
            homeText("Automatic", "خودکار"),
            homeText(
                "First the three OOB methods, one at a time (disoob-byte0-ttl1, then oob-insni+5-ttl1, then oob-byte0-nottl). Only if all three fail: relay, ECH, split ClientHello and the current network together; the first ready route sends the request.",
                "اول سه روش OOB یکی‌یکی امتحان می‌شود (disoob-byte0-ttl1، بعد oob-insni+5-ttl1، بعد oob-byte0-nottl). فقط اگر هر سه ناموفق باشند، رله، ECH، تکه‌کردن ClientHello و شبکه فعلی با هم امتحان می‌شوند و اولین مسیر آماده درخواست را می‌فرستد.",
            ),
        ),
        PowMenuChoice(
            PowCoreConfig.KEY_OOB,
            homeText("OOB only", "فقط OOB"),
            homeText(
                "Only the OOB desync (Android: oob-byte0-nottl first, then ttl1 variants; TLS 1.2 then 1.3). Winning method tried first next time. No relay, ECH or split hello.",
                "فقط روش OOB، یکی‌یکی: disoob-byte0-ttl1، بعد oob-insni+5-ttl1، بعد oob-byte0-nottl (اول TLS 1.3، بعد TLS 1.2). روش برنده برای درخواست بعدی اول امتحان می‌شود. بدون رله، ECH یا تکه‌کردن ClientHello.",
            ),
        ),
        PowMenuChoice(
            PowCoreConfig.KEY_ECH,
            homeLtr("ECH"),
            homeText(
                "Only ECH on a clean Cloudflare edge. No split hello and no direct route.",
                "فقط ECH روی آی‌پی تمیز Cloudflare؛ بدون تکه‌کردن ClientHello و بدون مسیر مستقیم.",
            ),
        ),
        PowMenuChoice(
            PowCoreConfig.KEY_FRAGMENT,
            homeText("Split ClientHello", "تکه‌کردن ClientHello"),
            homeText(
                "Only split ClientHello on a clean Cloudflare edge. No ECH and no direct route.",
                "فقط ClientHello تکه‌تکه روی آی‌پی تمیز Cloudflare؛ بدون ECH و بدون مسیر مستقیم.",
            ),
        ),
        PowMenuChoice(
            PowCoreConfig.KEY_EXTERNAL,
            homeText("Current network", "از شبکه فعلی"),
            homeText(
                "Uses this network as-is (or another VPN you already turned on). No edge probe.",
                "از همین شبکه (یا VPN دیگری که روشن کرده‌ای) مستقیم می‌رود؛ آی‌پی تمیز اسکن نمی‌شود.",
            ),
        ),
    )
    val usesCleanH2 = settings.linkRecipe in setOf(
        PowCoreConfig.RECIPE_AUTO,
        PowCoreConfig.RECIPE_MASQUE_H2,
        PowCoreConfig.RECIPE_MASQUE_H2_SAVED,
        PowCoreConfig.RECIPE_MASQUE_H2_SPLIT,
    )

    PowDirectional {
        Column(verticalArrangement = Arrangement.spacedBy(PowGap)) {
            if (!AetherNative.available) {
                Text(
                    homeText(AetherNative.unavailableReason, "این گوشی از UAC PoW پشتیبانی نمی‌کند."),
                    color = UacColors.ErrorRed,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(UacColors.ErrorRed.copy(alpha = 0.08f), PowShape)
                        .border(1.dp, UacColors.ErrorRed.copy(alpha = 0.24f), PowShape)
                        .padding(PowPad),
                )
            }
            PowStatusCard(
                phase = powStatus.phase,
                progress = powStatus.progressPercent,
                described = lastConnect.describe(),
                cleanPeers = if (usesCleanH2) cleanPeers else null,
            )
            PowCard {
                PowPickerRow(
                    icon = Icons.Outlined.CellTower,
                    label = homeText("Carrier", "اپراتور"),
                    sheetTitle = homeText("Carrier firewall", "فایروال اپراتور"),
                    choices = firewalls,
                    selectedId = settings.firewallProfile,
                    accent = accent,
                    onSelect = { store.save(settings.copy(firewallProfile = it)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Route,
                    label = homeText("Connection method", "روش اتصال"),
                    sheetTitle = homeText("Connection method", "روش اتصال"),
                    choices = recipes,
                    selectedId = settings.linkRecipe,
                    accent = accent,
                    onSelect = { store.save(settings.copy(linkRecipe = it)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Radar,
                    label = homeText("Search mode", "حالت جستجو"),
                    sheetTitle = homeText("Search mode", "حالت جستجو"),
                    choices = scans,
                    selectedId = settings.scanMode,
                    accent = accent,
                    onSelect = { store.save(settings.copy(scanMode = it)) },
                )
                PowRowDivider()
                PowPickerRow(
                    icon = Icons.Outlined.Public,
                    label = homeText("Exit", "خروجی"),
                    sheetTitle = homeText("Exit", "خروجی"),
                    choices = exits,
                    selectedId = settings.exitMode,
                    accent = accent,
                    onSelect = { store.save(settings.copy(exitMode = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = homeText("Optimized mode", "حالت بهینه"),
                    subtitle = homeText("Lighter checks, less battery", "بررسی‌های سبک‌تر؛ باتری کمتر مصرف می‌شه"),
                    checked = settings.optimizedMode,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(optimizedMode = it)) },
                )
                PowRowDivider()
                PowSwitchRow(
                    icon = Icons.Outlined.Speed,
                    title = homeText("HTTP/3 tuning", "بهینه‌سازی HTTP/3"),
                    subtitle = homeText(
                        "Smoother packet pacing, suits Irancell",
                        "ارسال منظم‌تر داده؛ روی ایرانسل بهتر جواب می‌ده",
                    ),
                    checked = settings.quicTuned,
                    accent = accent,
                    onCheckedChange = { store.save(settings.copy(quicTuned = it)) },
                )
            }
            PowAdvancedCard(store = store, settings = settings, exits = exits, accent = accent)
            PowKeyCard(store = store, settings = settings, fetches = fetches, accent = accent)
            PowLearnedPathsCard(store = store, accent = accent)
        }
    }
}

/** «تنظیمات پیشرفته»: collapsible card with the HTTP/2 speed and tunnel options. */
@Composable
private fun PowAdvancedCard(
    store: PowEngineStore,
    settings: PowEngineSettings,
    exits: List<PowMenuChoice>,
    accent: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(260),
        label = "powAdvancedChevron",
    )
    val obfuscations = listOf(
        PowMenuChoice(
            PowEngineSettings.H2_OBFS_UNCHANGED,
            homeText("Unchanged", "بدون تغییر"),
            homeText("Same obfuscation as other methods.", "همان مبهم‌سازی بقیه روش‌ها."),
        ),
        PowMenuChoice(
            "light",
            homeText("Light", "سبک"),
            homeText("Less overhead; HTTP/2 already hides in TLS.", "سربار کمتر؛ HTTP/2 خودش داخل TLS پنهان است."),
        ),
        PowMenuChoice(
            "off",
            homeText("Off", "خاموش"),
            homeText("Fastest, least CPU. Use if the link is stable.", "سریع‌ترین و کم‌مصرف‌ترین؛ اگر اتصال پایدار است."),
        ),
    )
    val fragments = listOf(
        PowMenuChoice(
            PowEngineSettings.H2_FRAG_AUTO,
            homeText("Auto", "خودکار"),
            homeText("Recipe default; turns on when DPI is suspected.", "پیش‌فرض روش؛ اگه DPI حس بشه روشن می‌شه."),
        ),
        PowMenuChoice(
            PowEngineSettings.H2_FRAG_ON,
            homeText("On", "روشن"),
            homeText("Always split the ClientHello. A bit slower.", "همیشه ClientHello تکه می‌شه. کمی کندتر."),
        ),
        PowMenuChoice(
            PowEngineSettings.H2_FRAG_OFF,
            homeText("Off", "خاموش"),
            homeText("Never split. Fastest.", "هیچ‌وقت تکه نمی‌شه. سریع‌ترین."),
        ),
    )
    val mtus = listOf(
        PowMenuChoice(
            PowEngineSettings.TUN_MTU_DEFAULT.toString(),
            homeText("Default", "پیش‌فرض"),
            homeText("The app's MTU, capped at 1280.", "MTU خود برنامه، حداکثر ۱۲۸۰."),
        ),
        PowMenuChoice("1280", homeLtr("1280"), homeText("Safest on every network.", "امن‌ترین روی همه شبکه‌ها.")),
        PowMenuChoice("1350", homeLtr("1350"), homeText("Bigger packets, less overhead.", "بسته‌های بزرگ‌تر، سربار کمتر.")),
        PowMenuChoice("1400", homeLtr("1400"), homeText("Largest; may break on some lines.", "بزرگ‌ترین؛ روی بعضی خطوط مشکل دارد.")),
    ).let { list ->
        val id = settings.tunMtu.toString()
        if (list.none { it.id == id }) {
            list + PowMenuChoice(id, homeLtr(id), homeText("Custom value", "مقدار دلخواه"))
        } else {
            list
        }
    }
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
            PowIconTile(Icons.Outlined.Tune, accent)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    homeText("Advanced settings", "تنظیمات پیشرفته"),
                    color = UacColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    homeText("HTTP/2, MTU, tunnels, logs", "HTTP/2، MTU، تونل، لاگ"),
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = if (expanded) accent else UacColors.TextSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(chevron),
            )
        }
        PowExpandBody(visible = expanded) {
            PowPickerRow(
                icon = Icons.Outlined.Shield,
                label = homeText("HTTP/2 obfuscation", "مبهم‌سازی HTTP/2"),
                sheetTitle = homeText("Obfuscation on MASQUE HTTP/2", "مبهم‌سازی روی MASQUE HTTP/2"),
                choices = obfuscations,
                selectedId = settings.h2Obfuscation,
                accent = accent,
                onSelect = { store.save(settings.copy(h2Obfuscation = it)) },
            )
            PowRowDivider()
            PowPickerRow(
                icon = Icons.Outlined.Shield,
                label = homeText("H2 fragment", "H2 fragment"),
                sheetTitle = homeText("H2 fragment (MASQUE HTTP/2)", "H2 fragment (MASQUE HTTP/2)"),
                choices = fragments,
                selectedId = settings.h2FragmentMode,
                accent = accent,
                onSelect = { store.save(settings.copy(h2FragmentMode = it)) },
            )
            PowRowDivider()
            PowMtuPickerRow(
                icon = Icons.Outlined.Hub,
                label = homeText("Tunnel MTU", "MTU تونل"),
                sheetTitle = homeText("Tunnel MTU", "MTU تونل"),
                choices = mtus,
                selectedId = settings.tunMtu.toString(),
                accent = accent,
                onSelect = { id -> store.save(settings.copy(tunMtu = id.toIntOrNull() ?: PowEngineSettings.TUN_MTU_DEFAULT)) },
            )
            PowRowDivider()
            PowSwitchRow(
                icon = Icons.AutoMirrored.Outlined.AltRoute,
                title = homeText("Two tunnels at once", "دو تونل همزمان"),
                subtitle = homeText(
                    "Faster; each site keeps one tunnel",
                    "سریع‌تر؛ هر سایت از یک تونل ثابت",
                ),
                checked = settings.psiphonTunnelPool,
                accent = accent,
                onCheckedChange = { store.save(settings.copy(psiphonTunnelPool = it)) },
            )
            PowRowDivider()
            PowSwitchRow(
                icon = Icons.Outlined.BugReport,
                title = homeText("Logs to logcat", "لاگ در logcat"),
                subtitle = homeText("Read POW logs over adb (tag UAC-POW)", "برای عیب‌یابی با کامپیوتر (adb)"),
                checked = settings.logcatMirror,
                accent = accent,
                onCheckedChange = { store.save(settings.copy(logcatMirror = it)) },
            )
        }
    }
}

/** Compact live status: dot + state on the first line, one LTR technical line under it. */
@Composable
private fun PowStatusCard(
    phase: PowPhase,
    progress: Int,
    described: String,
    cleanPeers: List<String>?,
) {
    val connecting = phase in setOf(PowPhase.STARTING, PowPhase.OUTER, PowPhase.INNER, PowPhase.BRIDGING)
    val tone = when {
        phase == PowPhase.CONNECTED -> UacColors.ConnectedGreen
        phase == PowPhase.FAILED -> UacColors.ErrorRed
        connecting -> UacColors.DisconnectingAmber
        else -> UacColors.TextSecondary
    }
    val title = when {
        phase == PowPhase.CONNECTED -> homeText("Connected", "متصل")
        phase == PowPhase.FAILED -> homeText("Connection failed", "اتصال ناموفق")
        connecting -> homeText("Connecting…", "در حال اتصال…")
        else -> homeText("Not connected", "متصل نیست")
    }
    val detail: AnnotatedString? = when {
        phase == PowPhase.CONNECTED && described.isNotBlank() -> latinDigits(homeLtr(described))
        connecting && progress > 0 -> latinDigits(homeLtr("$progress%"))
        described.isNotBlank() -> latinDigits(homeText("Last: ", "آخرین: ") + homeLtr(described))
        else -> null
    }
    val cleanLine: AnnotatedString? = when {
        cleanPeers == null -> null
        cleanPeers.isEmpty() -> AnnotatedString(
            homeText("No clean HTTP/2 address saved yet", "هنوز آی‌پی تمیز HTTP/2 ذخیره نشده"),
        )
        else -> {
            val extra = cleanPeers.size - 2
            val list = cleanPeers.take(2).joinToString(", ") + if (extra > 0) " +$extra" else ""
            latinDigits(homeText("Clean IPs ", "آی‌پی تمیز ") + homeLtr(list))
        }
    }
    val pulse = rememberInfiniteTransition(label = "powDot")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (connecting) 0.25f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "powDotAlpha",
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
            modifier = Modifier
                .size(28.dp)
                .background(tone.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(dotAlpha)
                    .background(tone, CircleShape),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                title,
                color = if (phase == PowPhase.IDLE) UacColors.TextPrimary else tone,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (detail != null) {
                Text(
                    detail,
                    color = UacColors.TextPrimary.copy(alpha = 0.82f),
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (cleanLine != null) {
                Text(
                    cleanLine,
                    color = UacColors.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun recipeHint(recipe: String): String = when (recipe) {
    PowCoreConfig.RECIPE_WARP_V6 -> homeText(
        "WireGuard over IPv6 only. Skipped quickly if this phone has no IPv6.",
        "WireGuard فقط روی IPv6؛ اگر گوشی IPv6 نداشته باشد سریع رد می‌شود.",
    )
    PowCoreConfig.RECIPE_WARP_ANY -> homeText(
        "WireGuard over IPv4 and IPv6.",
        "WireGuard روی IPv4 و IPv6.",
    )
    PowCoreConfig.RECIPE_WOW_V6 -> homeText(
        "WARP inside WARP, IPv6 only.",
        "WARP داخل WARP، فقط روی IPv6.",
    )
    PowCoreConfig.RECIPE_WOW_ANY -> homeText(
        "WARP inside WARP, over IPv4 and IPv6.",
        "WARP داخل WARP، روی IPv4 و IPv6.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2 -> homeText(
        "Tries saved clean addresses first, then scans HTTP/2 and saves any new one.",
        "اول آی‌پی‌های تمیز ذخیره‌شده، بعد اسکن HTTP/2 و ذخیره آی‌پی جدید.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2_SAVED -> homeText(
        "Saved clean addresses only, no scan.",
        "فقط آی‌پی‌های ذخیره‌شده، بدون اسکن.",
    )
    PowCoreConfig.RECIPE_MASQUE_H3 -> homeText(
        "MASQUE over HTTP/3 and IPv4.",
        "MASQUE روی HTTP/3 و IPv4.",
    )
    PowCoreConfig.RECIPE_MASQUE_H2_SPLIT -> homeText(
        "MASQUE over HTTP/2 with a split ClientHello.",
        "MASQUE روی HTTP/2 با ClientHello تکه‌تکه.",
    )
    else -> homeText(
        "Picks the best route for your network.",
        "بهترین مسیر برای شبکه‌ات خودکار انتخاب می‌شود.",
    )
}

@Composable
internal fun PowCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PowShape)
            .background(PowCardBrush, PowShape)
            .border(1.dp, PowBorder, PowShape),
        content = { content() },
    )
}

@Composable
internal fun PowRowDivider() {
    HorizontalDivider(
        color = UacColors.Divider.copy(alpha = 0.45f),
        thickness = 1.dp,
        modifier = Modifier.padding(start = PowPad + 32.dp + PowGap, end = PowPad),
    )
}

@Composable
internal fun PowIconTile(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(tint.copy(alpha = 0.12f), PowTileShape)
            .border(1.dp, tint.copy(alpha = 0.18f), PowTileShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/** Small capsule used for the current value and for status badges. */
@Composable
internal fun PowPill(
    text: AnnotatedString,
    tint: Color,
    chevron: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .widthIn(max = 190.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = 0.26f), CircleShape)
            .padding(start = 10.dp, end = if (chevron) 6.dp else 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text,
            color = tint,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (chevron) {
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A compact on/off row: icon, title and a one-line subtitle, switch at the end. The whole row toggles. */
@Composable
internal fun PowSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = PowPad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        PowIconTile(icon, accent)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = UacColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = UacColors.TextSecondary,
                fontSize = 11.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The row handles the tap, so the switch skips its 48dp touch box.
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

/** A compact setting row: icon + label at the start, current value pill at the end; tap opens a sheet. */
@Composable
internal fun PowPickerRow(
    icon: ImageVector,
    label: String,
    sheetTitle: String,
    choices: List<PowMenuChoice>,
    selectedId: String,
    accent: Color,
    onSelect: (String) -> Unit,
    tileTint: Color = accent,
) {
    var open by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId } ?: choices.first()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { open = true }
            .padding(horizontal = PowPad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        PowIconTile(icon, tileTint)
        Text(
            label,
            color = UacColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PowPill(latinDigits(selected.title), accent, chevron = true)
    }
    if (open) {
        PowChoiceSheet(
            title = sheetTitle,
            choices = choices,
            selectedId = selected.id,
            accent = accent,
            onSelect = { if (it != selected.id) onSelect(it) },
            onDismiss = { open = false },
        )
    }
}

/** MTU picker with Scan + manual entry inside the same bottom sheet. */
@Composable
internal fun PowMtuPickerRow(
    icon: ImageVector,
    label: String,
    sheetTitle: String,
    choices: List<PowMenuChoice>,
    selectedId: String,
    accent: Color,
    onSelect: (String) -> Unit,
    tileTint: Color = accent,
) {
    var open by remember { mutableStateOf(false) }
    val selected = choices.firstOrNull { it.id == selectedId }
        ?: PowMenuChoice(selectedId, homeLtr(selectedId), homeText("Custom value", "مقدار دلخواه"))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable { open = true }
            .padding(horizontal = PowPad, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        PowIconTile(icon, tileTint)
        Text(
            label,
            color = UacColors.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        PowPill(latinDigits(selected.title), accent, chevron = true)
    }
    if (open) {
        PowMtuChoiceSheet(
            title = sheetTitle,
            choices = choices,
            selectedId = selectedId,
            accent = accent,
            onSelect = { id ->
                onSelect(id)
            },
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PowMtuChoiceSheet(
    title: String,
    choices: List<PowMenuChoice>,
    selectedId: String,
    accent: Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var tryingMtu by remember { mutableIntStateOf(0) }
    var scannedMtu by remember { mutableIntStateOf(MtuAutoScanner.lastScannedMtu(context)) }
    var customText by remember { mutableStateOf(if (selectedId == "0") "" else selectedId) }
    var customError by remember { mutableStateOf("") }
    val scannedLabelPrefix = homeText("Scanned MTU: ", "MTU اسکن‌شده: ")
    val customRangeError = homeText(
        "Enter a number between 1000 and 1500.",
        "عددی بین ۱۰۰۰ تا ۱۵۰۰ وارد کنید.",
    )

    fun closeAfterSelect(id: String) {
        onSelect(id)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    /** Apply MTU and keep this sheet open (used by Scan). */
    fun applyKeepOpen(id: String) {
        onSelect(id)
        customText = if (id == "0") "" else id
        customError = ""
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PowSheetColor,
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.24f), CircleShape),
            )
        },
    ) {
        PowDirectional {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    color = UacColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                choices.forEach { choice ->
                    val chosen = choice.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PowShape)
                            .background(if (chosen) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
                            .border(1.dp, if (chosen) accent.copy(alpha = 0.55f) else PowBorder, PowShape)
                            .clickable(enabled = !scanning) {
                                closeAfterSelect(choice.id)
                            }
                            .padding(horizontal = PowPad, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PowGap),
                    ) {
                        Icon(
                            if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (chosen) accent else UacColors.TextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                latinDigits(choice.title),
                                color = if (chosen) accent else UacColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (choice.hint.isNotBlank()) {
                                Text(
                                    latinDigits(choice.hint),
                                    color = UacColors.TextSecondary,
                                    fontSize = PowHintSize,
                                    lineHeight = PowHintLine,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(
                    color = UacColors.Divider.copy(alpha = 0.45f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                Text(
                    homeText("Scan or type your own", "اسکن یا مقدار دلخواه"),
                    color = UacColors.TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                PowHint(
                    homeText(
                        "Recommended is usually 1280. Scan checks 1500 down to 1000 and picks the largest that fits.",
                        "معمولاً ۱۲۸۰ بهترینه. اسکن از ۱۵۰۰ تا ۱۰۰۰ را چک می‌کند و بزرگ‌ترین اندازهٔ مناسب را برمی‌دارد.",
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PowGap),
                ) {
                    if (scanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = accent,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            if (tryingMtu > 0) {
                                homeText("Trying ", "در حال تست ") + homeLtr(tryingMtu.toString()) + "…"
                            } else {
                                homeText("Scanning…", "در حال اسکن…")
                            },
                            color = UacColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        PowButton(
                            text = homeText("Scan", "اسکن"),
                            enabled = true,
                            accent = accent,
                            filled = true,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scanning = true
                                customError = ""
                                scope.launch {
                                    val result = MtuAutoScanner.scan(context) { tryingMtu = it }
                                    scanning = false
                                    tryingMtu = 0
                                    scannedMtu = result.mtu
                                    applyKeepOpen(result.mtu.toString())
                                }
                            },
                        )
                    }
                }
                if (scannedMtu in MtuAutoScanner.MIN_SAVED..MtuAutoScanner.MAX_SAVED) {
                    Text(
                        latinDigits(scannedLabelPrefix + homeLtr(scannedMtu.toString())),
                        color = UacColors.ConnectedGreen,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PowShape)
                            .background(UacColors.ConnectedGreen.copy(alpha = 0.10f))
                            .border(1.dp, UacColors.ConnectedGreen.copy(alpha = 0.35f), PowShape)
                            .padding(horizontal = PowPad, vertical = 10.dp),
                    )
                }

                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        customText = it.filter { ch -> ch.isDigit() }.take(4)
                        customError = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !scanning,
                    label = { Text(homeText("Custom MTU", "مقدار دلخواه")) },
                    placeholder = { Text(homeLtr("1000–1500")) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = accent,
                        cursorColor = accent,
                        focusedLabelColor = accent,
                    ),
                )
                if (customError.isNotBlank()) {
                    Text(
                        customError,
                        color = UacColors.ErrorRed,
                        fontSize = PowHintSize,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                PowButton(
                    text = homeText("Apply custom", "اعمال مقدار دلخواه"),
                    enabled = !scanning && customText.isNotBlank(),
                    accent = accent,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val value = customText.toIntOrNull()
                        val clamped = value?.let(MtuAutoScanner::clampManual)
                        if (clamped == null) {
                            customError = customRangeError
                        } else {
                            customText = clamped.toString()
                            closeAfterSelect(clamped.toString())
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PowChoiceSheet(
    title: String,
    choices: List<PowMenuChoice>,
    selectedId: String,
    accent: Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PowSheetColor,
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 38.dp, height = 4.dp)
                    .background(Color.White.copy(alpha = 0.24f), CircleShape),
            )
        },
    ) {
        PowDirectional {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    title,
                    color = UacColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
                choices.forEach { choice ->
                    val chosen = choice.id == selectedId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PowShape)
                            .background(if (chosen) accent.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.03f))
                            .border(1.dp, if (chosen) accent.copy(alpha = 0.55f) else PowBorder, PowShape)
                            .clickable {
                                onSelect(choice.id)
                                scope.launch { sheetState.hide() }.invokeOnCompletion {
                                    if (!sheetState.isVisible) onDismiss()
                                }
                            }
                            .padding(horizontal = PowPad, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PowGap),
                    ) {
                        Icon(
                            if (chosen) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (chosen) accent else UacColors.TextSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                latinDigits(choice.title),
                                color = if (chosen) accent else UacColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (choice.hint.isNotBlank()) {
                                Text(
                                    latinDigits(choice.hint),
                                    color = UacColors.TextSecondary,
                                    fontSize = PowHintSize,
                                    lineHeight = PowHintLine,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                footer?.invoke()
            }
        }
    }
}

/** Collapsible row shared by the key and learned-path cards: icon, title, subtitle, badge, chevron. */
@Composable
private fun PowExpandRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: AnnotatedString?,
    badge: String,
    badgeColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onToggle)
            .padding(horizontal = PowPad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        PowIconTile(icon, tint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = UacColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null && subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = UacColors.TextSecondary,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        PowPill(latinDigits(badge), badgeColor)
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = UacColors.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
internal fun PowHint(text: String, color: Color = UacColors.TextSecondary) {
    Text(
        latinDigits(text),
        color = color,
        fontSize = PowHintSize,
        lineHeight = PowHintLine,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun PowButton(
    text: String,
    enabled: Boolean,
    accent: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = when {
            !enabled -> UacColors.TextSecondary
            filled -> Color.White
            else -> accent
        },
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(PowShape)
            .background(
                when {
                    !enabled -> Color.White.copy(alpha = 0.06f)
                    filled -> accent
                    else -> accent.copy(alpha = 0.10f)
                },
            )
            .border(1.dp, if (filled || !enabled) Color.Transparent else accent.copy(alpha = 0.35f), PowShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
    )
}

@Composable
internal fun PowExpandBody(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        Column {
            HorizontalDivider(color = UacColors.Divider.copy(alpha = 0.45f), thickness = 1.dp)
            content()
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
    var expanded by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fetchingLabel = homeText("Getting the key…", "در حال گرفتن کلید…")
    val busyLabel = homeText("Disconnect PoW first.", "اول اتصال PoW را قطع کن.")
    val fetchedLabel = homeText("Key saved.", "کلید ذخیره شد.")
    val failedLabel = homeText("Key request failed.", "گرفتن کلید ناموفق بود.")
    val certAskingLabel = homeText("Getting a certificate…", "در حال گرفتن گواهی…")
    val certButtonLabel = homeText("New certificate", "گواهی جدید")
    val certSavedLabel = homeText("New certificate saved.", "گواهی جدید ذخیره شد.")
    val certFailedLabel = homeText("Certificate request failed.", "گرفتن گواهی ناموفق بود.")
    val successNotes = setOf(fetchingLabel, fetchedLabel, certAskingLabel, certSavedLabel)
    val status = when {
        identity.enrolled && identity.masqueCertificate -> homeText("Saved", "ذخیره شده")
        identity.enrolled -> homeText("WARP key only", "فقط کلید WARP")
        identity.present -> homeText("Incomplete", "ناقص")
        else -> homeText("Not saved yet", "هنوز گرفته نشده")
    }
    val statusColor = when {
        identity.enrolled && identity.masqueCertificate -> UacColors.ConnectedGreen
        identity.enrolled || identity.present -> UacColors.DisconnectingAmber
        else -> UacColors.TextSecondary
    }
    val fetchTitle = (fetches.firstOrNull { it.id == settings.keyFetch } ?: fetches.first()).title
    PowCard {
        PowExpandRow(
            icon = Icons.Outlined.Key,
            tint = accent,
            title = homeText("Account key", "کلید حساب"),
            subtitle = latinDigits(homeText("Route: ", "روش گرفتن: ") + fetchTitle),
            badge = status,
            badgeColor = statusColor,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        PowExpandBody(expanded) {
            Column(
                modifier = Modifier.padding(PowPad),
                verticalArrangement = Arrangement.spacedBy(PowGap),
            ) {
                if (!identity.present) {
                    PowHint(
                        homeText(
                            "The first connect gets a key automatically. It lasts about a year.",
                            "در اولین اتصال، کلید خودکار گرفته می‌شود و حدود یک سال اعتبار دارد.",
                        ),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.03f), PowShape)
                            .border(1.dp, PowBorder, PowShape)
                            .padding(horizontal = PowPad, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (identity.deviceId.isNotBlank()) {
                            PowMemoryLine(homeText("Device", "دستگاه"), shortId(identity.deviceId))
                        }
                        if (identity.ipv4.isNotBlank()) PowMemoryLine(homeLtr("IPv4"), identity.ipv4)
                        if (identity.ipv6.isNotBlank()) PowMemoryLine(homeLtr("IPv6"), identity.ipv6)
                        if (identity.endpoint.isNotBlank()) {
                            PowMemoryLine(homeText("Endpoint", "آدرس سرور"), identity.endpoint)
                        }
                        if (identity.organization.isNotBlank()) {
                            PowMemoryLine(homeText("Organization", "سازمان"), identity.organization)
                        }
                        PowMemoryLine(
                            homeText("MASQUE certificate", "گواهی MASQUE"),
                            if (identity.masqueCertificate) {
                                identity.issuedLabel().ifBlank { homeText("Yes", "دارد") }
                            } else {
                                homeText("Missing", "ندارد")
                            },
                        )
                        if (identity.peerPublicPrefix.isNotBlank()) {
                            PowMemoryLine(homeText("Peer", "کلید همتا"), identity.peerPublicPrefix)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PowButton(
                        text = if (fetching) fetchingLabel else homeText("Get key now", "گرفتن کلید"),
                        enabled = !fetching,
                        accent = accent,
                        filled = true,
                        modifier = Modifier.weight(1f),
                        onClick = fetchKey@{
                            if (AetherNative.isRunning()) {
                                note = busyLabel
                                return@fetchKey
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
                        },
                    )
                    PowButton(
                        text = if (fetching) certAskingLabel else certButtonLabel,
                        enabled = !fetching,
                        accent = accent,
                        filled = false,
                        modifier = Modifier.weight(1f),
                        onClick = renewCert@{
                            if (AetherNative.isRunning()) {
                                note = busyLabel
                                return@renewCert
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
                        },
                    )
                }
                PowHint(
                    homeText(
                        "Get key: fetches a key and builds its MASQUE certificate. New certificate: refreshes the certificate on the current key; reconnect once it says saved.",
                        "«گرفتن کلید» کلید و گواهی MASQUE را می‌سازد. «گواهی جدید» روی همین کلید گواهی تازه می‌گیرد؛ بعد از «ذخیره شد» دوباره وصل شو.",
                    ),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PowShape)
                        .background(Color.White.copy(alpha = 0.03f))
                        .border(1.dp, PowBorder, PowShape),
                ) {
                    PowPickerRow(
                        icon = Icons.Outlined.Route,
                        label = homeText("Key request route", "روش گرفتن کلید"),
                        sheetTitle = homeText("Key request route", "روش گرفتن کلید"),
                        choices = fetches,
                        selectedId = settings.keyFetch,
                        accent = accent,
                        onSelect = { store.save(settings.copy(keyFetch = it)) },
                    )
                }
                val deletedNote = homeText("Deleted. The next connect gets a new key.", "حذف شد. اتصال بعدی کلید جدید می‌گیرد.")
                val missingNote = homeText("No key was saved.", "کلیدی ذخیره نشده بود.")
                if (note.isNotBlank()) {
                    val good = note in successNotes || note == deletedNote
                    Text(
                        latinDigits(note),
                        color = if (good) UacColors.ConnectedGreen else UacColors.DisconnectingAmber,
                        fontSize = 12.5.sp,
                        lineHeight = PowHintLine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                (if (good) UacColors.ConnectedGreen else UacColors.DisconnectingAmber).copy(alpha = 0.08f),
                                PowShape,
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Text(
                    homeText("Delete saved key", "حذف کلید ذخیره‌شده"),
                    color = UacColors.ErrorRed.copy(alpha = 0.9f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(PowShape)
                        .clickable {
                            val removed = PowIdentityStatus.forget(context)
                            revision += 1
                            note = if (removed) deletedNote else missingNote
                        }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun shortId(value: String): String =
    if (value.length <= 18) value else value.take(8) + "…" + value.takeLast(4)

private fun hasPersianLetters(value: String): Boolean = value.any { it in '\u0600'..'\u06FF' }

/** Technical values (IPs, ports, ids) always read left to right. */
private fun ltrValue(value: String): String = if (hasPersianLetters(value)) value else homeLtr(value)

private fun latinDigits(text: String): AnnotatedString = homeLatinDigits(text)

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
    val rawSummary = snapshot.summary()
    val summary = if (rawSummary.isBlank()) {
        AnnotatedString(homeText("Nothing saved yet", "هنوز چیزی ذخیره نشده"))
    } else {
        latinDigits(ltrValue(rawSummary))
    }

    val badge = when {
        snapshot.isEmpty -> homeText("Empty", "خالی")
        snapshot.networks.isNotEmpty() -> homeText("${snapshot.networks.size} networks", "${snapshot.networks.size} شبکه")
        else -> homeText("Saved", "ذخیره شده")
    }
    PowCard {
        PowExpandRow(
            icon = Icons.Outlined.History,
            tint = accent,
            title = homeText("Learned routes", "مسیرهای یادگرفته‌شده"),
            subtitle = summary,
            badge = badge,
            badgeColor = if (snapshot.isEmpty) UacColors.TextSecondary else accent,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        PowExpandBody(expanded) {
            Column(
                modifier = Modifier.padding(PowPad),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (snapshot.isEmpty) {
                    PowHint(
                        homeText(
                            "After a successful connect, the best route is saved here.",
                            "بعد از اولین اتصال موفق، بهترین مسیر اینجا ذخیره می‌شود.",
                        ),
                    )
                } else {
                    snapshot.globalOuter?.let { outer ->
                        PowMemoryLine(homeText("Outer layer", "لایه بیرونی"), PowCoreConfig.outerLabel(outer))
                    }
                    snapshot.networks.take(3).forEach { row ->
                        val outer = row.outer?.let(PowCoreConfig::outerLabel) ?: "—"
                        val rtt = row.rttMs?.let { " · ${it}ms" }.orEmpty()
                        PowMemoryLine(ltrValue(PowPathMemory.networkLabel(row.networkKey)), "$outer$rtt")
                    }
                    snapshot.masqueLastPeer?.let { peer ->
                        val hop = when (snapshot.globalOuter) {
                            "wireguard" -> "WireGuard"
                            "gool" -> "WoW"
                            else -> "MASQUE"
                        }
                        PowMemoryLine(homeLtr(hop), peer)
                    }
                    snapshot.wowLastPeer?.let { PowMemoryLine(homeLtr("WoW"), it) }
                    if (snapshot.masqueCachedGateways.isNotEmpty()) {
                        val extra = snapshot.masqueCachedGateways.size - 2
                        val value = buildString {
                            append(snapshot.masqueCachedGateways.take(2).joinToString(" · "))
                            if (extra > 0) append(" +$extra")
                        }
                        PowMemoryLine(homeText("Cached", "آدرس‌های کش‌شده"), value)
                    }
                    Text(
                        homeText("Forget routes", "پاک کردن مسیرها"),
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clip(PowShape)
                            .clickable {
                                store.forgetLearnedPaths()
                                revision += 1
                                forgotten = true
                            }
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                if (forgotten && snapshot.isEmpty) {
                    Text(
                        homeText("Cleared.", "پاک شد."),
                        color = UacColors.ConnectedGreen,
                        fontSize = PowHintSize,
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
            fontSize = 12.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
        Text(
            latinDigits(ltrValue(value)),
            color = UacColors.TextPrimary,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}
