package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.pow.PowCoreConfig
import com.uacspoofer.mobile.engine.pow.PowEngineSettings
import com.uacspoofer.mobile.engine.pow.PowEngineStore
import com.uacspoofer.mobile.engine.pow.PowLiveExit
import com.uacspoofer.mobile.engine.pow.PowPhase
import com.uacspoofer.mobile.engine.pow.PowRegions
import com.uacspoofer.mobile.engine.pow.PowStatusStore
import com.uacspoofer.mobile.ui.theme.UacColors

private enum class PathState { ACTIVE, CONNECTING, BYPASSED, FAILED, IDLE }

private data class PathNode(
    val key: String,
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val port: String,
    val state: PathState,
    val details: List<Pair<String, String>>,
)

private fun PathState.color(): Color = when (this) {
    PathState.ACTIVE -> UacColors.ConnectedGreen
    PathState.CONNECTING -> UacColors.DisconnectingAmber
    PathState.FAILED -> UacColors.ErrorRed
    PathState.BYPASSED -> Color(0xFF5B6878)
    PathState.IDLE -> UacColors.DisconnectedBlue
}

@Composable
private fun PathState.label(): String = when (this) {
    PathState.ACTIVE -> homeText("Active", "فعال")
    PathState.CONNECTING -> homeText("Connecting", "در حال اتصال")
    PathState.FAILED -> homeText("Failed", "خطا")
    PathState.BYPASSED -> homeText("Bypassed", "رد شده")
    PathState.IDLE -> homeText("Idle", "آماده")
}

/** Compact entry row at the top of PoW settings. */
@Composable
internal fun PowLivePathButton(accent: Color, onClick: () -> Unit) {
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val dot = when (status.phase) {
        PowPhase.CONNECTED -> UacColors.ConnectedGreen
        PowPhase.FAILED -> UacColors.ErrorRed
        PowPhase.IDLE -> UacColors.TextSecondary
        else -> UacColors.DisconnectingAmber
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Color(0xE6142231), Color(0xD90B1724))), shape)
            .border(1.dp, accent.copy(alpha = 0.28f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(accent.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Route, null, tint = accent, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                homeText("Live path", "مسیر زنده"),
                color = UacColors.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = homeLocalizedFont(),
            )
            Text(
                homeText("See how your traffic flows right now", "ببین ترافیکت الان از کجا رد می‌شه"),
                color = UacColors.TextSecondary, fontSize = 11.5.sp, fontFamily = homeLocalizedFont(),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronLeft, null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun PowLivePathDialog(visible: Boolean, onDismissRequest: () -> Unit) {
    AnimatedHomeMetricDialog(
        visible = visible,
        title = homeText("Live path", "مسیر زنده"),
        subtitle = homeText("Device to internet, hop by hop", "از گوشی تا اینترنت، قدم به قدم"),
        icon = Icons.Outlined.Route,
        onDismissRequest = onDismissRequest,
        expanded = true,
        panelModifier = Modifier.fillMaxWidth().fillMaxHeight(0.86f),
    ) {
        // Only composed while the dialog is shown, so nothing here runs in the background.
        if (visible) PowLivePathContent(Modifier.weight(1f))
    }
}

@Composable
private fun PowLivePathContent(modifier: Modifier) {
    val context = LocalContext.current
    val store = remember(context) { PowEngineStore.get(context) }
    val status by PowStatusStore.status.collectAsStateWithLifecycle()
    val live by PowLiveExit.path.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val last by store.lastConnect.collectAsStateWithLifecycle()
    val phase = status.phase
    val connected = phase == PowPhase.CONNECTED
    val mode = live.mode.ifBlank { settings.exitMode }
    val warpUsed = mode == PowEngineSettings.EXIT_CHAIN || mode == PowEngineSettings.EXIT_WARP
    val psiphonUsed = mode != PowEngineSettings.EXIT_WARP
    val retuning = live.retuning && connected

    fun stateFor(used: Boolean, readyAt: Int): PathState = when {
        !used -> PathState.BYPASSED
        phase == PowPhase.IDLE -> PathState.IDLE
        phase == PowPhase.FAILED -> PathState.FAILED
        retuning -> PathState.CONNECTING
        connected -> PathState.ACTIVE
        phaseRank(phase) > readyAt -> PathState.ACTIVE
        else -> PathState.CONNECTING
    }

    val off = homeText("Off", "خاموش")
    val on = homeText("On", "روشن")
    val recipe = when {
        live.outerProtocol == "masque" -> "MASQUE " + (if (live.outerTransport == "h3") "HTTP/3" else "HTTP/2")
        live.outerProtocol.isNotBlank() -> PowCoreConfig.outerLabel(live.outerProtocol)
        else -> settings.linkRecipe
    }
    val gateway = last.peer.ifBlank { last.ip }.ifBlank { "—" }
    val obfs = PowCoreConfig.obfuscationFor(
        settings, "masque",
        PowCoreConfig.outerPlan(settings, false),
    )
    val exitCountry = status.exitRegion.takeIf { it.isNotBlank() }?.let { "${PowRegions.name(it)} ($it)" }
        ?: homeText("Automatic", "خودکار")
    val psiphonPort = PowLiveExit.socksPort

    val nodes = listOf(
        PathNode(
            "device", Icons.Outlined.Smartphone, homeText("Device", "گوشی"),
            homeText("Your apps", "برنامه‌های شما"), "",
            if (phase == PowPhase.IDLE) PathState.IDLE else if (phase == PowPhase.FAILED) PathState.FAILED else PathState.ACTIVE,
            emptyList(),
        ),
        PathNode(
            "tun", Icons.Outlined.Hub, "TUN",
            "tun2socks · MTU " + (if (live.mtu > 0) live.mtu.toString() else "—"),
            "",
            stateFor(true, 0),
            listOf(
                homeText("Relay", "رله") to "tun2socks",
                "MTU" to (if (live.mtu > 0) live.mtu.toString() else "—"),
                homeText("UDP", "UDP") to homeText("Over TCP", "روی TCP"),
            ),
        ),
        PathNode(
            "warp", Icons.Outlined.Cloud, "WARP",
            if (warpUsed) "$recipe · $gateway" else homeText("Not used in this mode", "توی این حالت استفاده نمی‌شه"),
            ":${PowCoreConfig.CHAIN_SOCKS_PORT}",
            stateFor(warpUsed, 2),
            listOf(
                homeText("Recipe", "روش") to recipe,
                homeText("Gateway", "گیت‌وی") to homeLtr(gateway),
                homeText("H2 fragment", "H2 fragment") to (live.h2Fragment?.let { if (it) on else off } ?: "—") +
                    " · " + settings.h2FragmentMode,
                homeText("Obfuscation", "مبهم‌سازی") to obfs,
                homeText("SOCKS port", "پورت SOCKS") to homeLtr("127.0.0.1:${PowCoreConfig.CHAIN_SOCKS_PORT}"),
            ),
        ),
        PathNode(
            "psiphon", Icons.Outlined.Shield, "Psiphon",
            if (psiphonUsed) listOf(live.psiphonProtocol.ifBlank { "—" }, exitCountry).joinToString(" · ")
            else homeText("Not used in this mode", "توی این حالت استفاده نمی‌شه"),
            ":$psiphonPort",
            stateFor(psiphonUsed, 3),
            listOf(
                homeText("Protocol", "پروتکل") to live.psiphonProtocol.ifBlank { "—" },
                homeText("Exit country", "کشور خروجی") to exitCountry,
                homeText("Route", "مسیر") to if (mode == PowEngineSettings.EXIT_CHAIN) homeText("Through WARP", "از روی WARP") else homeText("Direct", "مستقیم"),
                homeText("SOCKS port", "پورت SOCKS") to homeLtr("127.0.0.1:$psiphonPort"),
            ),
        ),
        PathNode(
            "net", Icons.Outlined.Language, homeText("Internet", "اینترنت"),
            if (mode == PowEngineSettings.EXIT_WARP) homeText("Cloudflare exit", "خروجی Cloudflare") else exitCountry,
            "",
            if (connected) PathState.ACTIVE else if (phase == PowPhase.FAILED) PathState.FAILED else if (phase == PowPhase.IDLE) PathState.IDLE else PathState.CONNECTING,
            emptyList(),
        ),
    )

    var openKey by remember { mutableStateOf<String?>(null) }
    val flowing = connected && !retuning
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        nodes.forEachIndexed { index, node ->
            PathNodeCard(node, expanded = openKey == node.key) {
                if (node.details.isNotEmpty()) openKey = if (openKey == node.key) null else node.key
            }
            if (node.key == "psiphon" && live.ghost && psiphonUsed) GhostChip()
            if (index < nodes.lastIndex) {
                val next = nodes[index + 1]
                val bypass = node.state == PathState.BYPASSED || next.state == PathState.BYPASSED
                PathConnector(
                    color = if (bypass) PathState.BYPASSED.color() else next.state.color(),
                    flowing = flowing && !bypass,
                    dashed = bypass,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PathLegend()
    }
}

private fun phaseRank(phase: PowPhase): Int = when (phase) {
    PowPhase.IDLE -> 0
    PowPhase.STARTING -> 1
    PowPhase.OUTER -> 2
    PowPhase.INNER -> 3
    PowPhase.BRIDGING -> 4
    PowPhase.CONNECTED -> 5
    PowPhase.FAILED -> 0
}

@Composable
private fun PathNodeCard(node: PathNode, expanded: Boolean, onClick: () -> Unit) {
    val font = homeLocalizedFont()
    val tint = node.state.color()
    val bypassed = node.state == PathState.BYPASSED
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(tint.copy(alpha = if (bypassed) 0.03f else 0.10f), Color(0xD90B1724)),
                ),
                shape,
            )
            .border(1.dp, tint.copy(alpha = if (bypassed) 0.18f else 0.38f), shape)
            .clickable(enabled = node.details.isNotEmpty(), onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .drawBehind {
                        if (!bypassed) {
                            drawCircle(
                                Brush.radialGradient(listOf(tint.copy(alpha = 0.35f), Color.Transparent)),
                                radius = size.minDimension * 0.75f,
                            )
                        }
                    }
                    .background(tint.copy(alpha = if (bypassed) 0.08f else 0.16f), CircleShape)
                    .border(1.dp, tint.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(node.icon, null, tint = if (bypassed) UacColors.TextSecondary else tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        node.title, color = if (bypassed) UacColors.TextSecondary else UacColors.TextPrimary,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = font,
                    )
                    if (node.port.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Text(homeLtr(node.port), color = UacColors.TextSecondary, fontSize = 10.sp)
                    }
                }
                Text(
                    node.subtitle, color = UacColors.TextSecondary, fontSize = 11.5.sp, fontFamily = font,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            StatusChip(node.state)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp)
                    .background(Color(0x66071421), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                node.details.forEach { (k, v) ->
                    Row {
                        Text(k, color = UacColors.TextSecondary, fontSize = 12.sp, fontFamily = font, modifier = Modifier.weight(1f))
                        Text(v, color = UacColors.TextPrimary, fontSize = 12.sp, fontFamily = font, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(state: PathState) {
    val c = state.color()
    Row(
        Modifier
            .background(c.copy(alpha = 0.14f), RoundedCornerShape(50))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(c, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(state.label(), color = c, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, fontFamily = homeLocalizedFont())
    }
}

@Composable
private fun GhostChip() {
    val c = UacColors.ConnectingCyan
    Row(
        Modifier.fillMaxWidth().padding(start = 28.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.CallSplit, null, tint = c, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            homeText("Ghost handover tunnel warming", "تونل جایگزین (ghost) در حال آماده‌شدن") + "  " + homeLtr(":1822"),
            color = c, fontSize = 11.sp, fontFamily = homeLocalizedFont(),
        )
    }
}

@Composable
private fun PathConnector(color: Color, flowing: Boolean, dashed: Boolean) {
    val phase = if (flowing) {
        val transition = rememberInfiniteTransition(label = "powPathFlow")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "powPathFlowPhase",
        ).value
    } else 0f
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val x = 32.dp.toPx()
        drawLine(
            color.copy(alpha = if (dashed) 0.35f else 0.55f), Offset(x, 0f), Offset(x, size.height),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
        )
        if (flowing) {
            val y = size.height * phase
            drawCircle(color.copy(alpha = 0.35f), radius = 5.dp.toPx(), center = Offset(x, y))
            drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

@Composable
private fun PathLegend() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(PathState.ACTIVE, PathState.CONNECTING, PathState.BYPASSED, PathState.FAILED).forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(s.color(), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(s.label(), color = UacColors.TextSecondary, fontSize = 10.5.sp, fontFamily = homeLocalizedFont())
            }
        }
    }
}
