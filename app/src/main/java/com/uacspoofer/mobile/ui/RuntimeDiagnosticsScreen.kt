package com.uacspoofer.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
internal fun RuntimeDiagnosticsScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val sampler = remember(context) { RuntimeDiagnosticsSampler(context) }
    var snapshot by remember { mutableStateOf<RuntimeSnapshot?>(null) }
    var leak by remember { mutableStateOf(LeakSignal.WATCHING) }
    var history by remember { mutableStateOf<List<Float>>(emptyList()) }
    val accent = UacColors.DisconnectedBlue
    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    BackHandler(onBack = onClose)

    LaunchedEffect(sampler) {
        while (isActive) {
            val next = withContext(Dispatchers.Default) { sampler.capture() }
            snapshot = next
            leak = sampler.leakSignal()
            history = sampler.javaHistoryMb()
            delay(RuntimeDiagnosticsSampler.SAMPLE_INTERVAL_MS)
        }
    }

    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
        ToolPageScaffold(
            accent = accent,
            header = {
                ToolPageHeader(
                    title = homeText("Runtime diagnostics", "وضعیت لحظه‌ای برنامه"),
                    subtitle = homeText("CPU, RAM and leak watch", "پردازنده، رم و بررسی نشت حافظه"),
                    icon = Icons.Outlined.Memory,
                    accent = accent,
                    onMenuClick = onClose,
                    navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                    navigationDescription = homeText("Back to settings", "برگشت به تنظیمات"),
                )
            },
        ) {
                item {
                    LeakStatusCard(signal = leak, primaryId = snapshot?.primarySuspectId)
                }
                item {
                    LeakSourceCard(snapshot = snapshot, accent = accent)
                }
                item {
                    MetricCard(
                        title = homeText("CPU", "پردازنده"),
                        value = snapshot?.let { "%.1f%%".format(it.cpuPercent) } ?: "—",
                        detail = snapshot?.let {
                            homeText(
                                "Process share · ${it.cores} cores",
                                "سهم همین برنامه · ${it.cores} هسته",
                            )
                        }.orEmpty(),
                        icon = Icons.Outlined.Speed,
                        accent = accent,
                    )
                }
                item {
                    MetricCard(
                        title = homeText("Java heap", "حافظه جاوا"),
                        value = snapshot?.let { RuntimeDiagnosticsSampler.formatMb(it.javaUsedBytes) } ?: "—",
                        detail = snapshot?.let {
                            homeText(
                                "Max ${RuntimeDiagnosticsSampler.formatMb(it.javaMaxBytes)}",
                                "سقف ${RuntimeDiagnosticsSampler.formatMb(it.javaMaxBytes)}",
                            )
                        }.orEmpty(),
                        icon = Icons.Outlined.Memory,
                        accent = accent,
                    )
                }
                item {
                    MetricCard(
                        title = homeText("Native heap", "حافظه نیتیو"),
                        value = snapshot?.let { RuntimeDiagnosticsSampler.formatMb(it.nativeUsedBytes) } ?: "—",
                        detail = snapshot?.let {
                            homeText(
                                "PSS ${RuntimeDiagnosticsSampler.formatMb(it.pssBytes)} · free ${RuntimeDiagnosticsSampler.formatMb(it.deviceAvailBytes)}",
                                "${homeLtr("PSS")} ${RuntimeDiagnosticsSampler.formatMb(it.pssBytes)} · آزاد ${RuntimeDiagnosticsSampler.formatMb(it.deviceAvailBytes)}",
                            )
                        }.orEmpty(),
                        icon = Icons.Outlined.Memory,
                        accent = accent,
                    )
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ToolCardBrush, ToolCardShape)
                            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
                            .padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            homeText("Java heap over time", "روند حافظه جاوا"),
                            color = UacColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        HeapSparkline(values = history, accent = accent, modifier = Modifier.fillMaxWidth().height(84.dp))
                        Text(
                            homeText(
                                "A steady climb after sitting still can mean a leak.",
                                "اگر برنامه بی‌کار باشد و نمودار پیوسته بالا برود، احتمال نشت حافظه هست.",
                            ),
                            color = UacColors.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
                item {
                    Button(
                        onClick = { sampler.requestGc() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(homeText("Force garbage collection", "اجبار پاکسازی حافظه"), fontWeight = FontWeight.SemiBold)
                    }
                }
                item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun LeakStatusCard(signal: LeakSignal, primaryId: String?) {
    val (title, body, color) = when (signal) {
        LeakSignal.WATCHING -> Triple(
            homeText("Watching heap", "در حال نمونه‌گیری"),
            homeText("Need about 20 seconds of samples.", "حدود ۲۰ ثانیه نمونه لازم است."),
            UacColors.TextSecondary,
        )
        LeakSignal.STABLE -> Triple(
            homeText("Heap looks stable", "حافظه پایدار به نظر می‌رسد"),
            homeText("No sustained climb in Java or native heap.", "رشد مداوم در جاوا یا نیتیو دیده نشد."),
            UacColors.ConnectedGreen,
        )
        LeakSignal.JAVA_GROWTH -> Triple(
            homeText("Possible Java leak", "احتمال نشت حافظه جاوا"),
            homeText("Java heap is climbing while this screen is open.", "حافظه جاوا در همین صفحه در حال بالا رفتن است."),
            UacColors.DisconnectingAmber,
        )
        LeakSignal.NATIVE_GROWTH -> Triple(
            homeText("Possible native leak", "احتمال نشت حافظه نیتیو"),
            homeText("Native heap is climbing. Tor/Xray buffers can cause this.", "حافظه نیتیو بالا می‌رود. بافر Tor یا Xray هم می‌تواند این را بسازد."),
            UacColors.DisconnectingAmber,
        )
        LeakSignal.BOTH -> Triple(
            homeText("Possible leak (Java + native)", "احتمال نشت (جاوا و نیتیو)"),
            homeText("Both heaps are climbing. Stay on this screen and watch the graph.", "هر دو نمودار بالا می‌روند. در همین صفحه بمان و نمودار را ببین."),
            UacColors.ErrorRed,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, color.copy(alpha = 0.35f), ToolCardShape)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WarningAmber, null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(10.dp))
            Text(title, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(body, color = UacColors.TextSecondary, fontSize = 12.5.sp, lineHeight = 18.sp)
        if (!primaryId.isNullOrBlank() && signal != LeakSignal.WATCHING) {
            Text(
                homeText(
                    "Likely source: ${suspectEnglishName(primaryId)}",
                    "مظنون اصلی: ${suspectPersianName(primaryId)}",
                ),
                color = UacColors.TextPrimary,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            homeText(
                "This is a trend watch, not a proof. Open this page, wait, and compare after Force GC.",
                "این فقط روند را نشان می‌دهد، نه اثبات قطعی. صفحه را باز بگذار، صبر کن و بعد از پاکسازی مقایسه کن.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun LeakSourceCard(snapshot: RuntimeSnapshot?, accent: Color) {
    val suspects = snapshot?.suspects.orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, accent.copy(alpha = 0.28f), ToolCardShape)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            homeText("Where memory is growing", "کجا حافظه بالا می‌رود"),
            color = UacColors.TextPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            homeText(
                "Ranked by climb rate, then size. This names app parts, not a single Java class.",
                "مرتب‌شده بر اساس سرعت رشد، بعد حجم. این بخش‌های برنامه را نام می‌برد، نه یک کلاس جاوا.",
            ),
            color = UacColors.TextSecondary,
            fontSize = 12.sp,
            lineHeight = 17.sp,
        )
        if (suspects.isEmpty()) {
            Text(
                homeText("Collecting maps…", "در حال خواندن نقشه حافظه…"),
                color = UacColors.TextSecondary,
                fontSize = 13.sp,
            )
        } else {
            suspects.forEach { suspect ->
                val growing = suspect.growing
                val color = when {
                    snapshot?.primarySuspectId == suspect.id && growing -> UacColors.DisconnectingAmber
                    growing -> accent
                    else -> UacColors.TextSecondary
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            homeText(suspectEnglishName(suspect.id), suspectPersianName(suspect.id)),
                            color = UacColors.TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = if (snapshot?.primarySuspectId == suspect.id) FontWeight.SemiBold else FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            RuntimeDiagnosticsSampler.formatMb(suspect.bytes),
                            color = color,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    val slopeLabel = "%+.1f MB/min".format(suspect.slopeMbPerMin)
                    val prefix = if (growing) homeText("Climbing ", "در حال رشد ") else ""
                    Text(
                        "$prefix$slopeLabel · ${homeText(suspectEnglishNote(suspect.id), suspectPersianNote(suspect.id))}",
                        color = color,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun suspectEnglishName(id: String): String = when {
    id == "tor" -> "Tor engine (libtor)"
    id == "webtunnel" -> "WebTunnel plugin"
    id == "tun2socks" -> "Tor TUN pipe (hev)"
    id == "xray" -> "Xray / libv2ray"
    id == "java_heap" -> "Java heap"
    id == "native_heap" -> "Native heap"
    id == "graphics" -> "UI / graphics"
    id == "code" -> "Mapped code"
    id == "stack" -> "Thread stacks"
    id == "private_other" -> "Other private"
    id == "logs" -> "Live logs buffer"
    id == "art" -> "ART / Dalvik"
    id == "android" -> "Android runtime"
    id == "other" -> "Other native"
    id.startsWith("so:") -> id.removePrefix("so:")
    else -> id
}

private fun suspectPersianName(id: String): String = when {
    id == "tor" -> "موتور ${homeLtr("Tor")} (${homeLtr("libtor")})"
    id == "webtunnel" -> "پلاگین ${homeLtr("WebTunnel")}"
    id == "tun2socks" -> "لوله ${homeLtr("TUN")} تور"
    id == "xray" -> "${homeLtr("Xray")} / ${homeLtr("libv2ray")}"
    id == "java_heap" -> "هیپ جاوا"
    id == "native_heap" -> "هیپ نیتیو"
    id == "graphics" -> "رابط کاربری / گرافیک"
    id == "code" -> "کد نقشه‌شده"
    id == "stack" -> "استک نخ‌ها"
    id == "private_other" -> "سایر حافظه خصوصی"
    id == "logs" -> "بافر لاگ زنده"
    id == "art" -> "${homeLtr("ART")} / دالویک"
    id == "android" -> "ران‌تایم اندروید"
    id == "other" -> "سایر نیتیو"
    id.startsWith("so:") -> homeLtr(id.removePrefix("so:"))
    else -> homeLtr(id)
}

private fun suspectEnglishNote(id: String): String = when (id) {
    "tor" -> "JNI Tor daemon"
    "webtunnel" -> "pluggable transport"
    "tun2socks" -> "device traffic to Tor SOCKS"
    "xray" -> "TUN / SOCKS core"
    "java_heap" -> "Kotlin / Compose objects"
    "graphics" -> "Compose, Fresco, GPU buffers"
    "logs" -> "AppLogRepository"
    else -> "process mapping"
}

private fun suspectPersianNote(id: String): String = when (id) {
    "tor" -> "دیمون ${homeLtr("JNI Tor")}"
    "webtunnel" -> "انتقال‌دهنده بریج"
    "tun2socks" -> "ترافیک دستگاه به ${homeLtr("SOCKS")} تور"
    "xray" -> "هسته ${homeLtr("TUN / SOCKS")}"
    "java_heap" -> "اشیای ${homeLtr("Kotlin / Compose")}"
    "graphics" -> "${homeLtr("Compose")}، ${homeLtr("Fresco")}، بافر GPU"
    "logs" -> homeLtr("AppLogRepository")
    else -> "نقشه حافظه فرآیند"
}

@Composable
private fun MetricCard(
    title: String,
    value: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ToolCardBrush, ToolCardShape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), ToolCardShape)
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = UacColors.TextSecondary, fontSize = 12.sp)
            Text(value, color = UacColors.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (detail.isNotBlank()) {
                Text(detail, color = UacColors.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun HeapSparkline(values: List<Float>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .padding(8.dp),
    ) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val max = values.max().coerceAtLeast(min + 0.1f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.lastIndex).coerceAtLeast(1).toFloat()
            val y = size.height - ((value - min) / (max - min) * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        val last = values.last()
        val lastX = size.width
        val lastY = size.height - ((last - min) / (max - min) * size.height)
        drawCircle(accent, radius = 3.5.dp.toPx(), center = Offset(lastX, lastY))
    }
}
