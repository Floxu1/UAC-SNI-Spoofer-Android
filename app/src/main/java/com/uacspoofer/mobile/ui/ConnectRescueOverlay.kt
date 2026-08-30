package com.uacspoofer.mobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.uacspoofer.mobile.engine.EngineModeStore
import com.uacspoofer.mobile.ui.theme.UacColors
import com.uacspoofer.mobile.vpn.ConnectRescuePhase
import com.uacspoofer.mobile.vpn.ConnectRescueSnapshot
import com.uacspoofer.mobile.vpn.ConnectRescueStore
import java.util.Locale
import kotlinx.coroutines.delay

private val RescueCardShape = RoundedCornerShape(20.dp)
private val RescueLiveGreen = UacColors.ConnectedGreen
private val RescueFailRed = UacColors.ErrorRed

@Composable
internal fun ConnectRescueOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engineStore = remember(context) { EngineModeStore.get(context) }
    val engineMode by engineStore.mode.collectAsStateWithLifecycle()
    val snapshot by ConnectRescueStore.snapshot.collectAsStateWithLifecycle()
    LaunchedEffect(engineMode) {
        if (engineMode.isTor) ConnectRescueStore.hide()
    }
    LaunchedEffect(snapshot.generation, snapshot.phase) {
        if (snapshot.phase == ConnectRescuePhase.SUCCEEDED || snapshot.phase == ConnectRescuePhase.FAILED) {
            delay(2_600L)
            ConnectRescueStore.hideIf(snapshot.generation)
        }
    }
    AnimatedVisibility(
        visible = snapshot.visible && engineMode.isXray,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInVertically(
            tween(240, easing = FastOutSlowInEasing),
            initialOffsetY = { it / 3 },
        ),
        exit = fadeOut(tween(160)) + slideOutVertically(
            tween(180),
            targetOffsetY = { it / 4 },
        ),
    ) {
        ConnectRescueCard(snapshot)
    }
}

@Composable
private fun ConnectRescueCard(snapshot: ConnectRescueSnapshot) {
    val isPersian = LocalHomePersian.current
    val localizedFont = homeLocalizedFont()
    val accent = when (snapshot.phase) {
        ConnectRescuePhase.SUCCEEDED -> RescueLiveGreen
        ConnectRescuePhase.FAILED -> RescueFailRed
        else -> UacColors.ConnectingCyan
    }
    var elapsedSeconds by remember(snapshot.generation) { mutableLongStateOf(0L) }
    LaunchedEffect(snapshot.generation) {
        elapsedSeconds = 0L
        while (true) {
            delay(1_000L)
            elapsedSeconds++
        }
    }
    val animatedProgress by animateFloatAsState(
        targetValue = snapshot.overallProgress,
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "rescue-progress",
    )
    val elapsed = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .widthIn(max = 420.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(28.dp, RescueCardShape, ambientColor = accent.copy(alpha = 0.35f), spotColor = accent)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xF20A1623), Color(0xF00B1F2C), Color(0xF207131F)),
                    ),
                    RescueCardShape,
                )
                .border(1.dp, accent.copy(alpha = 0.34f), RescueCardShape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(accent.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                        .border(1.dp, accent.copy(alpha = 0.32f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.TravelExplore,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        homeText("Clean IP rescue", "نجات با IP تمیز"),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = localizedFont,
                    )
                    Text(
                        homeText(
                            "Step ${snapshot.stepNumber} of ${ConnectRescueSnapshot.TOTAL_STEPS}",
                            "مرحله ${snapshot.stepNumber} از ${ConnectRescueSnapshot.TOTAL_STEPS}",
                        ),
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = localizedFont,
                    )
                }
                RescueLiveBadge(
                    accent = accent,
                    elapsed = elapsed,
                    phase = snapshot.phase,
                )
            }
            Spacer(Modifier.height(10.dp))
            RescueStepRow(snapshot = snapshot, accent = accent)
            Spacer(Modifier.height(10.dp))
            Text(
                rescueTitle(snapshot),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = localizedFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                rescueDetail(snapshot),
                color = Color(0xFFC5D5E0),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                fontFamily = localizedFont,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val target = snapshot.currentTarget.trim()
            if (target.isNotEmpty() && snapshot.phase != ConnectRescuePhase.SUCCEEDED && snapshot.phase != ConnectRescuePhase.FAILED) {
                Spacer(Modifier.height(6.dp))
                Text(
                    target,
                    color = accent,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (isPersian) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = accent,
                trackColor = Color.White.copy(alpha = 0.08f),
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    rescueCounts(snapshot),
                    color = UacColors.TextSecondary,
                    fontSize = 10.5.sp,
                    fontFamily = localizedFont,
                )
                Text(
                    "${(animatedProgress * 100f).toInt()}%",
                    color = accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun RescueLiveBadge(
    accent: Color,
    elapsed: String,
    phase: ConnectRescuePhase,
) {
    val label = when (phase) {
        ConnectRescuePhase.SUCCEEDED -> homeText("DONE", "تمام")
        ConnectRescuePhase.FAILED -> homeText("FAIL", "ناموفق")
        else -> homeText("LIVE", "زنده")
    }
    val color = when (phase) {
        ConnectRescuePhase.SUCCEEDED -> RescueLiveGreen
        ConnectRescuePhase.FAILED -> RescueFailRed
        else -> accent
    }
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(elapsed, color = Color(0xFFBED0DD), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun RescueStepRow(snapshot: ConnectRescueSnapshot, accent: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(ConnectRescueSnapshot.TOTAL_STEPS) { index ->
            val number = index + 1
            if (index > 0) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(
                            if (number <= snapshot.stepNumber) accent.copy(alpha = 0.7f)
                            else Color.White.copy(alpha = 0.10f),
                        ),
                )
            }
            val completed = number < snapshot.stepNumber || snapshot.phase == ConnectRescuePhase.SUCCEEDED
            val active = number == snapshot.stepNumber && snapshot.phase != ConnectRescuePhase.SUCCEEDED
            val failed = snapshot.phase == ConnectRescuePhase.FAILED && number == snapshot.stepNumber
            val color = when {
                failed -> RescueFailRed
                completed -> RescueLiveGreen
                active -> accent
                else -> UacColors.TextSecondary
            }
            Box(
                modifier = Modifier
                    .size(if (active) 22.dp else 18.dp)
                    .background(
                        when {
                            failed -> RescueFailRed.copy(alpha = 0.16f)
                            completed -> RescueLiveGreen.copy(alpha = 0.16f)
                            active -> accent.copy(alpha = 0.18f)
                            else -> Color.White.copy(alpha = 0.04f)
                        },
                        CircleShape,
                    )
                    .border(1.dp, color.copy(alpha = if (active || completed || failed) 1f else 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number.toString(),
                    modifier = Modifier.wrapContentSize(unbounded = true),
                    color = color,
                    style = TextStyle(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        textAlign = TextAlign.Center,
                        lineHeight = 9.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
    }
}

@Composable
private fun rescueTitle(snapshot: ConnectRescueSnapshot): String = when (snapshot.phase) {
    ConnectRescuePhase.COLLECTING -> homeText("Collecting Cloudflare edges", "جمع‌آوری Edgeهای Cloudflare")
    ConnectRescuePhase.PREFLIGHT -> homeText("TCP / TLS preflight", "پیش‌آزمایش TCP و TLS")
    ConnectRescuePhase.SCREENING -> homeText("Xray + HTTP / DNS screen", "غربال با Xray و HTTP")
    ConnectRescuePhase.SELECTING -> homeText("Building a new IP list", "ساخت لیست جدید IP")
    ConnectRescuePhase.RETRYING -> homeText("Retrying connect", "تلاش دوباره برای اتصال")
    ConnectRescuePhase.SUCCEEDED -> homeText("A clean IP connected", "یک IP تمیز وصل شد")
    ConnectRescuePhase.FAILED -> homeText("Rescue did not find a route", "نجات به مسیر سالم نرسید")
    ConnectRescuePhase.HIDDEN -> ""
}

@Composable
private fun rescueDetail(snapshot: ConnectRescueSnapshot): String = when (snapshot.phase) {
    ConnectRescuePhase.COLLECTING -> homeText(
        "Loading official Cloudflare ranges and DNS edges for this network.",
        "رنج‌های رسمی Cloudflare و Edgeهای DNS این شبکه در حال بارگذاری است.",
    )
    ConnectRescuePhase.PREFLIGHT -> homeText(
        "Checking which IPs accept a real TCP and TLS handshake.",
        "کدام IPها دست‌دهی واقعی TCP و TLS را قبول می‌کنند.",
    )
    ConnectRescuePhase.SCREENING -> homeText(
        "Testing the same config through Xray, then HTTP and DNS.",
        "همان کانفیگ با Xray و بعد HTTP و DNS آزمایش می‌شود.",
    )
    ConnectRescuePhase.SELECTING -> homeText(
        "Keeping a short, subnet-diverse list of IPs that actually work.",
        "یک لیست کوتاه و متنوع از IPهایی که واقعاً کار کرده‌اند نگه داشته می‌شود.",
    )
    ConnectRescuePhase.RETRYING -> homeText(
        "The old 11 candidates failed. Connecting with the rescued IPs.",
        "۱۱ مسیر قبلی خطا دادند. اتصال با IPهای نجات‌یافته شروع شده.",
    )
    ConnectRescuePhase.SUCCEEDED -> homeText(
        "Rescue found a working edge. The tunnel is staying on this IP.",
        "نجات یک Edge سالم پیدا کرد. تونل روی همین IP می‌ماند.",
    )
    ConnectRescuePhase.FAILED -> homeText(
        "No new clean Cloudflare IP passed the checks on this network.",
        "در این شبکه هیچ IP تمیز جدیدی از چک‌ها عبور نکرد.",
    )
    ConnectRescuePhase.HIDDEN -> ""
}

@Composable
private fun rescueCounts(snapshot: ConnectRescueSnapshot): String = when (snapshot.phase) {
    ConnectRescuePhase.RETRYING -> homeText(
        "Trying ${snapshot.retryIndex} of ${snapshot.retryTotal}",
        "تلاش ${snapshot.retryIndex} از ${snapshot.retryTotal}",
    )
    ConnectRescuePhase.SUCCEEDED -> homeText(
        "${snapshot.foundCount} clean IPs ready",
        "${snapshot.foundCount} IP تمیز آماده شد",
    )
    ConnectRescuePhase.FAILED -> homeText("Rescue stopped", "عملیات نجات متوقف شد")
    else -> {
        val checked = if (snapshot.total > 0) {
            homeText(
                "${snapshot.completed} of ${snapshot.total} checked",
                "${snapshot.completed} از ${snapshot.total} بررسی شد",
            )
        } else {
            homeText("Scanning", "در حال پویش")
        }
        if (snapshot.healthy > 0) {
            checked + homeText(" · ${snapshot.healthy} healthy", " · ${snapshot.healthy} سالم")
        } else {
            checked
        }
    }
}
