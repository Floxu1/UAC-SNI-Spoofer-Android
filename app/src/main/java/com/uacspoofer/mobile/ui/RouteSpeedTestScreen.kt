package com.uacspoofer.mobile.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.profiles.RoutePreparationProgress
import com.uacspoofer.mobile.profiles.RoutePreparationStep
import com.uacspoofer.mobile.profiles.ProxyProfile
import com.uacspoofer.mobile.ui.theme.UacColors
import java.util.Locale
import kotlinx.coroutines.delay

private val RouteAccent = Color(0xFF39D9FF)
private val RouteGreen = Color(0xFF25E49A)
private val RouteRed = Color(0xFFFF6F88)
private val RouteAmber = Color(0xFFFFB454)
private const val USER_SCROLL_HOLD_MS = 10_000L
private const val AUTO_SCROLL_CHECK_MS = 500L
private val DIAGNOSTIC_VALUE_REGEX = Regex("""([A-Za-z][A-Za-z0-9]*)=([^\s]+)""")
private const val MAX_DIAGNOSTIC_CHIPS = 22
private const val LTR_ISOLATE = '\u2066'
private const val POP_DIRECTIONAL_ISOLATE = '\u2069'
private const val WORD_JOINER = '\u2060'

private fun ltr(value: String): String = "$LTR_ISOLATE$value$POP_DIRECTIONAL_ISOLATE"
private fun String.withNonBreakingHalfSpaces(): String =
    replace("\u200C", "$WORD_JOINER\u200C$WORD_JOINER")

@Composable
private fun routeFontSize(english: Float, persian: Float): TextUnit =
    if (LocalHomePersian.current) persian.sp else english.sp

@Composable
private fun routeMaxLines(english: Int, persian: Int): Int =
    if (LocalHomePersian.current) persian else english

@Composable
private fun routeOverflow(): TextOverflow =
    if (LocalHomePersian.current) TextOverflow.Clip else TextOverflow.Ellipsis

private enum class RouteSortOption(val title: String) {
    SPEED("Speed"),
    PING("Ping"),
    CONFIDENCE("Confidence"),
    OVERALL("Overall"),
}

@Composable
private fun RouteSortOption.localizedTitle(): String = when (this) {
    RouteSortOption.SPEED -> homeText(title, "سرعت")
    RouteSortOption.PING -> homeText(title, "پینگ")
    RouteSortOption.CONFIDENCE -> homeText(title, "اطمینان")
    RouteSortOption.OVERALL -> homeText(title, "کلی")
}

@Composable
private fun localizedStageTitle(stage: RouteTournamentStage): String = when (stage) {
    RouteTournamentStage.QUALIFIER -> homeText(stage.title, "مرحله مقدماتی")
    RouteTournamentStage.VERIFICATION -> homeText(stage.title, "بررسی Resolverها")
    RouteTournamentStage.MTU_VALIDATION -> homeText(stage.title, "اعتبارسنجی ${ltr("MTU")}")
    RouteTournamentStage.STABILITY -> homeText(stage.title, "پایداری")
    RouteTournamentStage.STRESS -> homeText(stage.title, "تست فشار")
    RouteTournamentStage.CHAMPIONSHIP -> homeText(stage.title, "مرحله نهایی ${ltr("A-B-B-A")}")
    RouteTournamentStage.COMPLETE -> homeText(stage.title, "تمام‌شده")
}

@Composable
private fun localizedSavedRouteRole(role: String): String {
    if (!LocalHomePersian.current) return role
    return when (role.lowercase(Locale.US)) {
        "champion" -> "برنده"
        "backup" -> "پشتیبان"
        else -> role
    }
}

@Composable
private fun localizedRouteNotice(value: String): String {
    if (!LocalHomePersian.current) return value
    val readyNotice = Regex("""^(\d+) route genomes ready for (.+)$""").matchEntire(value)?.let { match ->
        "${match.groupValues[1]} ترکیب مسیر برای ${ltr(match.groupValues[2])} آماده است"
    } ?: value
    return readyNotice
        .replace("Pause the Tournament before reloading the profile and network", "قبل از بارگذاری دوباره کانفیگ و شبکه، مسابقه رو متوقف کن")
        .replace("Reloading the current configuration and network…", "در حال بارگذاری دوباره کانفیگ و شبکه فعلی…")
        .replace("Route Tournament paused • use the current best route or resume", "مسابقه متوقف شده • از بهترین مسیر فعلی استفاده کن یا ادامه بده")
        .replace("Route Tournament paused • tap RESUME to continue", "مسابقه متوقف شده • برای ادامه روی «ادامه» بزن")
        .replace("Tournament paused • current Champion can be used now", "مسابقه متوقف شده • الان می‌تونی از برنده فعلی استفاده کنی")
        .replace("Pause the Tournament before opening the previous Championship", "قبل از لود آخرین مسیر، مسابقه رو متوقف کن")
        .replace("Loading last saved routes…", "در حال لود آخرین مسیرهای ذخیره‌شده…")
        .replace("Could not load saved routes:", "لود مسیرهای ذخیره‌شده انجام نشد:")
        .replace("Current profile and network are not ready yet", "کانفیگ و شبکه فعلی هنوز آماده نیستن")
        .replace("No previous Championship list is available yet", "هنوز لیست مسیر ذخیره‌شده وجود نداره")
        .replace("No saved route list is available yet", "هنوز لیست مسیر ذخیره‌شده وجود نداره")
        .replace("Detecting network and restoring the Route Tournament…", "در حال شناسایی شبکه و بازیابی مسابقه مسیرها…")
        .replace("Tournament complete • no route passed the complete connectivity check", "مسابقه تمام شد • هیچ مسیری تست کامل اتصال رو رد نکرد")
        .replace("Tournament complete • Champion and backup are ready", "مسابقه تمام شد • برنده و مسیر پشتیبان آماده‌ان")
        .replace("Route Tournament is already complete", "مسابقه مسیرها قبلاً کامل شده")
        .replace("Live Tournament ranking restored", "رتبه‌بندی زنده بازیابی شد")
        .replace("Could not prepare routes:", "مسیرها آماده نشدن:")
        .replace("Network unavailable", "شبکه در دسترس نیست")
        .replace("Champion and backup saved for automatic recovery on this network", "برنده و پشتیبان برای بازیابی خودکار روی این شبکه ذخیره شدن")
        .replace("saved for this network and configuration", "برای این شبکه و کانفیگ ذخیره شد")
        .replace("Loaded", "بارگذاری شد:")
        .replace("routes from the previous Championship", "مسیر از لیست ذخیره‌شده")
        .replace("routes ranked by ping, speed and overall score", "مسیر با رتبه پینگ، سرعت و امتیاز کلی")
        .replace("Live", "رتبه‌بندی زنده مرحله")
        .replace("ranking restored", "بازیابی شد")
        .replace("Tournament complete • Champion confidence", "مسابقه تمام شد • اطمینان برنده")
        .replace("• backup ready", "• مسیر پشتیبان آماده است")
        .replace("fully healthy result is ready in", "نتیجه کاملاً سالمی آماده نیست در")
        .replace("healthy routes promoted from", "مسیر سالم از این مرحله عبور کرد:")
        .replace("healthy routes promoted • starting", "مسیر سالم عبور کرد • شروع")
        .replace("healthy", "سالم")
        .replace("samples left", "تست باقی‌مانده")
        .replace("workers", "پردازش")
        .replace("restored • tap RESUME to continue", "بازیابی شد • برای ادامه روی «ادامه» بزن")
        .replace("Qualifying", "مرحله مقدماتی")
        .replace("Verification", "بررسی دوباره")
        .replace("Stability", "پایداری")
        .replace("Stress test", "تست فشار")
        .replace("ABBA final", "مرحله نهایی A-B-B-A")
        .replace("Complete", "تمام‌شده")
}

@Composable
private fun localizedFailureFingerprint(value: String): String {
    if (!LocalHomePersian.current) return value
    return when (value) {
        "Healthy" -> "سالم"
        "TLS/SNI handshake blocked" -> "ارتباط ${ltr("TLS/SNI")} مسدود شده"
        "TCP path reset" -> "مسیر ${ltr("TCP")} ریست شده"
        "DNS path failed after HTTP succeeded" -> "بعد از موفقیت ${ltr("HTTP")}، مسیر ${ltr("DNS")} شکست خورد"
        "Partial egress • only some targets worked" -> "خروجی ناقص • فقط بعضی مقصدها کار کردن"
        "HTTP egress blocked while DNS worked" -> "با وجود کار کردن ${ltr("DNS")}، خروجی ${ltr("HTTP")} مسدود بود"
        "Unstable or stalled throughput" -> "سرعت ناپایدار یا متوقف‌شده"
        "Route timeout before complete connectivity" -> "مسیر قبل از کامل شدن اتصال Timeout شد"
        "Edge unreachable on this network" -> "${ltr("Edge")} روی این شبکه در دسترس نیست"
        "Connectivity probe rejected the route" -> "تست اتصال این مسیر رو رد کرد"
        else -> value
    }
}

@Composable
internal fun RouteSpeedTestScreen(
    controller: RouteSpeedTestController,
    onBackClick: () -> Unit,
) {
    var fullTestConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    var sortOption by rememberSaveable { mutableStateOf(RouteSortOption.OVERALL) }
    var sortMenuVisible by remember { mutableStateOf(false) }
    var savedDetailsVisible by rememberSaveable { mutableStateOf(false) }
    var stageDetailsVisible by rememberSaveable { mutableStateOf(false) }
    var profileMenuVisible by remember { mutableStateOf(false) }
    var userScrollHoldUntil by remember { mutableLongStateOf(0L) }
    val listState = rememberLazyListState()
    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f) {
                    userScrollHoldUntil = SystemClock.elapsedRealtime() + USER_SCROLL_HOLD_MS
                }
                return Offset.Zero
            }
        }
    }
    BackHandler(onBack = onBackClick)
    LaunchedEffect(controller) { controller.loadProfileLibrary() }
    val rows = sortRouteRows(controller.visibleRows(), sortOption)
    val rankedOrderKey = rows.fold(1L) { hash, row -> hash * 31L + row.candidateId.hashCode() }
    val progress = if (controller.phaseTotalCount <= 0) 0f else {
        controller.phaseCompletedCount.toFloat() / controller.phaseTotalCount.toFloat()
    }
    LaunchedEffect(controller.testing, controller.viewingFinalStageHistory) {
        if (!controller.testing || controller.viewingFinalStageHistory) return@LaunchedEffect
        listState.scrollToItem(0)
        while (controller.testing && !controller.viewingFinalStageHistory) {
            delay(AUTO_SCROLL_CHECK_MS)
            if (
                SystemClock.elapsedRealtime() >= userScrollHoldUntil &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                listState.scrollToItem(0)
            }
        }
    }
    LaunchedEffect(controller.viewingFinalStageHistory) {
        if (controller.viewingFinalStageHistory) {
            listState.scrollToItem(0)
        }
    }
    LaunchedEffect(sortOption) {
        listState.scrollToItem(0)
    }
    LaunchedEffect(rankedOrderKey, controller.testing, controller.viewingFinalStageHistory) {
        if (
            controller.testing &&
            !controller.viewingFinalStageHistory &&
            SystemClock.elapsedRealtime() >= userScrollHoldUntil &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        ) {
            listState.scrollToItem(0)
        }
    }

    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
    ToolPageBackground(accent = RouteAccent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
        ) {
            WideSplitColumn(
                headerPadding = 12.dp,
                header = {
                    Spacer(Modifier.height(7.dp))
                    RouteSpeedHeader(
                loading = controller.loading,
                onBackClick = onBackClick,
                onRefresh = controller::refresh,
            )
                },
            ) {
            Spacer(Modifier.height(7.dp))
            RouteTestProfileSelector(
                profiles = controller.profileLibrary.allProfiles,
                selectedId = controller.profileLibrary.selectedId,
                saved = controller.savedRouteProfileId == controller.profileLibrary.selectedId,
                expanded = profileMenuVisible,
                enabled = !controller.loading && !controller.testing,
                onExpand = { profileMenuVisible = true },
                onDismiss = { profileMenuVisible = false },
                onSelect = { profile ->
                    profileMenuVisible = false
                    controller.selectTestProfile(profile.id)
                },
            )
            Spacer(Modifier.height(8.dp))
            CompactRouteTestSummary(
                profileName = controller.profileName,
                networkLabel = controller.networkLabel,
                savedChampionLabel = controller.savedChampionLabel,
                preparation = controller.preparationProgress,
                stage = controller.currentStage,
                completed = controller.phaseCompletedCount,
                total = controller.phaseTotalCount,
                progress = progress,
                testing = controller.testing,
                paused = controller.paused,
                loading = controller.loading,
                canStart = controller.canStartTest,
                canAdvance = controller.canAdvanceNow,
                advanceReadyCount = controller.advanceReadyCount,
                onToggleTest = {
                    when {
                        controller.testing -> controller.pauseTest()
                        controller.paused -> controller.resumeTest()
                        else -> fullTestConfirmationVisible = true
                    }
                },
                onStartNew = { fullTestConfirmationVisible = true },
                onAdvance = controller::advanceStageNow,
                onSavedRouteClick = { savedDetailsVisible = true },
                onStageClick = { stageDetailsVisible = true },
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.BarChart,
                    contentDescription = null,
                    tint = RouteAccent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (controller.viewingFinalStageHistory) {
                        homeText("Last saved routes", "آخرین مسیرهای ذخیره‌شده")
                    } else {
                        homeText("Live Tournament Ranking", "رتبه‌بندی زنده")
                    },
                    color = Color.White,
                    fontSize = routeFontSize(13f, 15f),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (controller.viewingFinalStageHistory) {
                    TextButton(onClick = controller::showLiveRanking) {
                        Text(homeText("LIVE", "زنده"), color = RouteGreen, fontSize = routeFontSize(9f, 10.5f), fontWeight = FontWeight.Bold)
                    }
                }
                Box {
                    Surface(
                        color = Color(0x99101C29),
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(9.dp))
                            .clickable { sortMenuVisible = true },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(homeText("Sort by: ", "مرتب‌سازی: "), color = UacColors.TextSecondary, fontSize = routeFontSize(9f, 10.5f))
                            Text(sortOption.localizedTitle(), color = Color.White, fontSize = routeFontSize(9f, 10.5f), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(5.dp))
                            Icon(Icons.Outlined.KeyboardArrowDown, null, tint = RouteAccent, modifier = Modifier.size(15.dp))
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuVisible,
                        onDismissRequest = { sortMenuVisible = false },
                        containerColor = Color(0xFF112130),
                    ) {
                        RouteSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        option.localizedTitle(),
                                        color = if (option == sortOption) RouteAccent else Color.White,
                                        fontSize = routeFontSize(11f, 12.5f),
                                    )
                                },
                                onClick = {
                                    sortOption = option
                                    sortMenuVisible = false
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            if (controller.loading && rows.isEmpty()) {
                RoutePreparationLivePanel(
                    progress = controller.preparationProgress,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            } else if (rows.isEmpty()) {
                if (controller.hasPreparedPlan) {
                    EmptyRouteState(onRefresh = controller::refresh)
                } else {
                    RouteTestReadyState(
                        profileName = controller.profileLibrary.selectedProfile.name,
                        onStart = { fullTestConfirmationVisible = true },
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(userScrollConnection),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(rows, key = { _, row -> row.candidateId }) { index, row ->
                        RouteResultCard(
                            rank = index + 1,
                            row = row,
                            recommended = row.candidateId == controller.recommendedCandidateId,
                            backup = row.candidateId == controller.backupCandidateId,
                            selected = row.candidateId == controller.selectedCandidateId,
                            testing = controller.testing,
                            onSelect = { controller.selectRoute(row.candidateId) },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            }
        }
    }
    if (fullTestConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { fullTestConfirmationVisible = false },
            containerColor = Color(0xFF101E2B),
            title = {
                Text(homeText("Start Route Test?", "تست مسیر شروع بشه؟"), color = Color.White, fontWeight = FontWeight.SemiBold)
            },
            text = {
                Text(
                    homeText(
                        if (controller.hasPreparedPlan) {
                            "All ${controller.rows.size} Edge × DNS × Fragment routes enter a fast batched HTTP preflight. " +
                            "Up to the best 96 then receive isolated HTTP and DNS verification; later stages remain repeated and isolated, and the final two are compared A-B-B-A. " +
                            "This can take several minutes and use about 100 MB of data."
                        } else {
                            "The app first discovers healthy edges and builds the route list for the selected profile, then starts the tournament automatically. This can take several minutes."
                        },
                        if (controller.hasPreparedPlan) {
                            "هر ${controller.rows.size} ترکیب ${ltr("Edge × DNS × Fragment")} وارد پیش‌آزمایش گروهی و سریع ${ltr("HTTP")} می‌شه. " +
                            "بعد، تا ۹۶ مسیر برتر با تست مستقل ${ltr("HTTP + DNS")} بررسی می‌شن؛ مرحله‌های بعد هم مستقل و تکراری هستن و در پایان دو مسیر با الگوی ${ltr("A-B-B-A")} مقایسه می‌شن. " +
                            "این تست ممکنه چند دقیقه طول بکشه و حدود ۱۰۰ مگابایت اینترنت مصرف کنه."
                        } else {
                            "اول ${ltr("Edge")}های سالم برای کانفیگ انتخاب‌شده پیدا می‌شن و لیست مسیرها ساخته می‌شه؛ بعد تست به‌صورت خودکار شروع می‌شه. این روند ممکنه چند دقیقه طول بکشه."
                        },
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = routeFontSize(11f, 12.5f),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        fullTestConfirmationVisible = false
                        controller.startTest()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF168FD3)),
                ) {
                    Text(homeText("START", "شروع"), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { fullTestConfirmationVisible = false }) { Text(homeText("CANCEL", "لغو")) }
            },
        )
    }
    if (savedDetailsVisible) {
        SavedRouteProfileDialog(
            details = controller.savedRouteDetails,
            finalStageAvailable = controller.canLoadLastRouteList,
            savedRouteSaved = controller.savedRouteDetails != null,
            onLoadFinalStage = {
                controller.loadLastFinalStageList()
                savedDetailsVisible = false
            },
            onDismiss = { savedDetailsVisible = false },
        )
    }
    if (stageDetailsVisible) {
        StageDetailsDialog(
            stage = controller.currentStage,
            onDismiss = { stageDetailsVisible = false },
        )
    }
    }
}

@Composable
private fun RouteSpeedHeader(
    loading: Boolean,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteIconButton(
            onClick = onBackClick,
            modifier = Modifier
                .size(42.dp)
                .background(Color(0xCC101E2B), RoundedCornerShape(14.dp))
                .border(1.dp, Color.White.copy(alpha = 0.11f), RoundedCornerShape(14.dp)),
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, homeText("Back", "برگشت"), tint = Color.White)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                homeText("Route Speed Test", "تست سرعت مسیر"),
                color = Color.White,
                fontSize = routeFontSize(18f, 20f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                homeText("Adaptive Tournament • Champion", "مسابقه تطبیقی • انتخاب برنده"),
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(9.5f, 11.5f),
                maxLines = 1,
            )
        }
        RemoteIconButton(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier
                .size(42.dp)
                .background(Color(0x99101C29), RoundedCornerShape(13.dp))
                .border(1.dp, RouteAccent.copy(alpha = 0.23f), RoundedCornerShape(13.dp)),
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(19.dp), color = RouteAccent, strokeWidth = 2.dp)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Outlined.Refresh,
                        homeText("Reload current configuration and network", "بارگذاری دوباره کانفیگ و شبکه فعلی"),
                        tint = RouteAccent,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(homeText("RELOAD", "بارگذاری"), color = RouteAccent, fontSize = routeFontSize(6f, 8f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RouteTestProfileSelector(
    profiles: List<ProxyProfile>,
    selectedId: String,
    saved: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    onSelect: (ProxyProfile) -> Unit,
) {
    val selected = profiles.firstOrNull { it.id == selectedId } ?: profiles.firstOrNull()
    Box(Modifier.fillMaxWidth()) {
        Surface(
            color = Color(0xCC0D1B28),
            shape = RoundedCornerShape(13.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, RouteAccent.copy(alpha = 0.2f), RoundedCornerShape(13.dp))
                .clickable(enabled = enabled, onClick = onExpand),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .background(RouteAccent.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = RouteAccent, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        homeText("TEST PROFILE", "کانفیگ تست"),
                        color = UacColors.TextSecondary,
                        fontSize = routeFontSize(7.5f, 8.8f),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        selected?.name ?: homeText("No profile", "بدون کانفیگ"),
                        color = Color.White,
                        fontSize = routeFontSize(10.5f, 12f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                selected?.let { profile ->
                    Text(
                        profile.protocol.wireName.uppercase(Locale.US),
                        color = RouteAccent,
                        fontSize = routeFontSize(8f, 8.8f),
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (saved) {
                    Spacer(Modifier.width(7.dp))
                    Surface(color = RouteGreen.copy(alpha = 0.12f), shape = RoundedCornerShape(7.dp)) {
                        Text(
                            homeText("ROUTE SAVED", "مسیر ذخیره‌شده"),
                            color = RouteGreen,
                            fontSize = routeFontSize(6.8f, 7.8f),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = UacColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF112130),
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 360.dp),
        ) {
            profiles.forEach { profile ->
                val isSelected = profile.id == selectedId
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            if (isSelected) Icons.Outlined.CheckCircle else Icons.Outlined.Hub,
                            null,
                            tint = if (isSelected) RouteGreen else UacColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = {
                        Column {
                            Text(
                                profile.name,
                                color = if (isSelected) RouteGreen else Color.White,
                                fontSize = routeFontSize(10.5f, 12f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${profile.protocol.wireName.uppercase(Locale.US)} • ${profile.network.uppercase(Locale.US)}",
                                color = UacColors.TextSecondary,
                                fontSize = routeFontSize(7.5f, 8.3f),
                            )
                        }
                    },
                    onClick = { onSelect(profile) },
                )
            }
        }
    }
}

private fun RoutePreparationProgress.overallProgress(): Float {
    val itemProgress = when {
        total > 0 -> completed.toFloat().div(total.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }
    return ((step.number - 1) + itemProgress).div(RoutePreparationStep.TOTAL.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun RoutePreparationStep.localizedTitle(): String = when (this) {
    RoutePreparationStep.PROFILE_SNAPSHOT -> homeText("Profile snapshot", "ثبت کانفیگ")
    RoutePreparationStep.NETWORK_DETECTION -> homeText("Network detection", "شناسایی شبکه")
    RoutePreparationStep.EDGE_POOL -> homeText("Edge collection", "جمع‌آوری ${ltr("Edge")}")
    RoutePreparationStep.TCP_TLS_PREFLIGHT -> homeText("TCP/TLS preflight", "پیش‌آزمایش ${ltr("TCP/TLS")}")
    RoutePreparationStep.XRAY_SCREENING -> homeText("Xray screening", "غربال با ${ltr("Xray")}")
    RoutePreparationStep.CONNECTIVITY_VALIDATION -> homeText("HTTP + DNS validation", "بررسی ${ltr("HTTP + DNS")}")
    RoutePreparationStep.ROUTE_MATRIX -> homeText("Route matrix", "ساخت مسیرها")
}

@Composable
private fun preparationLiveDetail(progress: RoutePreparationProgress): String = when (progress.step) {
    RoutePreparationStep.PROFILE_SNAPSHOT -> homeText(
        "Freezing the selected profile without changing its transport fields",
        "تنظیمات کانفیگ انتخاب‌شده بدون تغییر برای تست ثبت می‌شن",
    )
    RoutePreparationStep.NETWORK_DETECTION -> homeText(
        "Reading the active network, carrier and fingerprint",
        "شبکه فعال، اپراتور و اثرانگشت شبکه در حال شناساییه",
    )
    RoutePreparationStep.EDGE_POOL -> homeText(
        "Collecting current, original, saved, DNS and official Cloudflare edges",
        "${ltr("Edge")}های فعلی، اصلی، ذخیره‌شده، ${ltr("DNS")} و محدوده‌های رسمی ${ltr("Cloudflare")} جمع‌آوری می‌شن",
    )
    RoutePreparationStep.TCP_TLS_PREFLIGHT -> homeText(
        "Testing TCP/TLS on ${progress.currentTarget.ifBlank { "the next edge" }}",
        "در حال بررسی ${ltr("TCP/TLS")} روی ${ltr(progress.currentTarget.ifBlank { "Edge بعدی" })}",
    )
    RoutePreparationStep.XRAY_SCREENING -> homeText(
        "Screening ${progress.currentTarget.ifBlank { "edge routes" }} with isolated Xray starts",
        "مسیر ${ltr(progress.currentTarget.ifBlank { "Edge" })} با اجرای مستقل ${ltr("Xray")} غربال می‌شه",
    )
    RoutePreparationStep.CONNECTIVITY_VALIDATION -> homeText(
        "Checking HTTP and DNS on ${progress.currentTarget.ifBlank { "healthy edges" }}",
        "${ltr("HTTP و DNS")} روی ${ltr(progress.currentTarget.ifBlank { "Edgeهای سالم" })} بررسی می‌شن",
    )
    RoutePreparationStep.ROUTE_MATRIX -> homeText(
        "Building Edge × DNS × tuning × MTU candidates",
        "ترکیب‌های ${ltr("Edge × DNS × Tuning × MTU")} در حال ساخته‌شدنه",
    )
}

@Composable
private fun RoutePreparationLivePanel(
    progress: RoutePreparationProgress,
    modifier: Modifier = Modifier,
) {
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000L)
            elapsedSeconds++
        }
    }
    val overall = progress.overallProgress()
    val elapsed = String.format(Locale.US, "%02d:%02d", elapsedSeconds / 60L, elapsedSeconds % 60L)
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Surface(
            color = Color(0xD90A1926),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .border(1.dp, RouteAccent.copy(alpha = 0.24f), RoundedCornerShape(16.dp)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            homeText(
                                "Step ${progress.step.number} of ${RoutePreparationStep.TOTAL}",
                                "مرحله ${progress.step.number} از ${RoutePreparationStep.TOTAL}",
                            ),
                            color = RouteAccent,
                            fontSize = routeFontSize(9f, 10.5f),
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            progress.step.localizedTitle(),
                            color = Color.White,
                            fontSize = routeFontSize(14f, 16f),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Surface(color = RouteGreen.copy(alpha = 0.11f), shape = RoundedCornerShape(9.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(7.dp).background(RouteGreen, CircleShape))
                            Spacer(Modifier.width(5.dp))
                            Text(homeText("LIVE", "زنده"), color = RouteGreen, fontSize = routeFontSize(8f, 9f), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(7.dp))
                            Text(ltr(elapsed), color = Color(0xFFBED0DD), fontSize = routeFontSize(8f, 9f))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RoutePreparationStep.entries.forEachIndexed { index, item ->
                        if (index > 0) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(if (item.number <= progress.step.number) RouteAccent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.09f)),
                            )
                        }
                        val completedStep = item.number < progress.step.number
                        val activeStep = item == progress.step
                        Box(
                            modifier = Modifier
                                .size(if (activeStep) 25.dp else 22.dp)
                                .background(
                                    when {
                                        completedStep -> RouteGreen.copy(alpha = 0.16f)
                                        activeStep -> RouteAccent.copy(alpha = 0.18f)
                                        else -> Color.White.copy(alpha = 0.035f)
                                    },
                                    CircleShape,
                                )
                                .border(
                                    1.dp,
                                    when {
                                        completedStep -> RouteGreen
                                        activeStep -> RouteAccent
                                        else -> Color.White.copy(alpha = 0.13f)
                                    },
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                item.number.toString(),
                                color = when {
                                    completedStep -> RouteGreen
                                    activeStep -> RouteAccent
                                    else -> UacColors.TextSecondary
                                },
                                fontSize = routeFontSize(8f, 8.8f),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(13.dp))
                Text(
                    preparationLiveDetail(progress),
                    color = Color(0xFFD7E5ED),
                    fontSize = routeFontSize(10.5f, 12.5f),
                    lineHeight = routeFontSize(14f, 17f),
                    maxLines = 3,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress.total > 0) {
                            homeText(
                                "${progress.completed} of ${progress.total} checked",
                                "${progress.completed} از ${progress.total} بررسی شده",
                            )
                        } else {
                            homeText("Preparing candidates…", "در حال آماده‌سازی گزینه‌ها…")
                        },
                        color = UacColors.TextSecondary,
                        fontSize = routeFontSize(9f, 10.5f),
                    )
                    Spacer(Modifier.weight(1f))
                    if (progress.healthy > 0) {
                        Text(
                            homeText("${progress.healthy} healthy", "${progress.healthy} سالم"),
                            color = RouteGreen,
                            fontSize = routeFontSize(9f, 10.5f),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { overall },
                    color = RouteAccent,
                    trackColor = Color.White.copy(alpha = 0.07f),
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    homeText(
                        "Progress is updated after every real network result",
                        "پیشرفت بعد از دریافت هر نتیجه واقعی شبکه به‌روز می‌شه",
                    ),
                    color = UacColors.TextSecondary.copy(alpha = 0.78f),
                    fontSize = routeFontSize(8f, 9.5f),
                )
            }
        }
    }
}

@Composable
private fun CompactRouteTestSummary(
    profileName: String,
    networkLabel: String,
    savedChampionLabel: String?,
    preparation: RoutePreparationProgress,
    stage: RouteTournamentStage,
    completed: Int,
    total: Int,
    progress: Float,
    testing: Boolean,
    paused: Boolean,
    loading: Boolean,
    canStart: Boolean,
    canAdvance: Boolean,
    advanceReadyCount: Int,
    onToggleTest: () -> Unit,
    onStartNew: () -> Unit,
    onAdvance: () -> Unit,
    onSavedRouteClick: () -> Unit,
    onStageClick: () -> Unit,
) {
    val displayCompleted = if (loading) preparation.completed else completed
    val displayTotal = if (loading) preparation.total else total
    val displayProgress = if (loading) preparation.overallProgress() else progress
    val statusColor = when {
        loading || total <= 0 -> RouteAccent
        paused -> RouteAmber
        else -> RouteGreen
    }
    val statusText = when {
        loading -> homeText("Loading", "در حال بارگذاری")
        testing -> homeText("Testing", "در حال تست")
        paused -> homeText("Paused", "متوقف‌شده")
        total <= 0 -> homeText("Ready", "آماده")
        else -> homeText("Healthy", "سالم")
    }
    Surface(
        color = Color(0xD90A1926),
        shape = RoundedCornerShape(15.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RouteAccent.copy(alpha = 0.22f), RoundedCornerShape(15.dp)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(37.dp)
                        .background(RouteAccent.copy(alpha = 0.075f), CircleShape)
                        .border(1.dp, RouteAccent.copy(alpha = 0.48f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = RouteAccent, modifier = Modifier.size(19.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profileName,
                        color = Color.White,
                        fontSize = routeFontSize(11.8f, 13.2f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        networkLabel,
                        color = UacColors.TextSecondary,
                        fontSize = routeFontSize(7.8f, 8.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(color = statusColor.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (loading || total <= 0) Icons.Outlined.PlayArrow else Icons.Outlined.CheckCircle,
                            null,
                            tint = statusColor,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            statusText,
                            color = statusColor,
                            fontSize = routeFontSize(8f, 9.2f),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (loading) {
                        homeText(
                            "Step ${preparation.step.number} of ${RoutePreparationStep.TOTAL}",
                            "مرحله ${preparation.step.number} از ${RoutePreparationStep.TOTAL}",
                        )
                    } else {
                        homeText("$displayCompleted / $displayTotal samples", "$displayCompleted از $displayTotal تست")
                    },
                    color = Color(0xFFBED0DD),
                    fontSize = routeFontSize(8.8f, 9.8f),
                    fontWeight = FontWeight.Medium,
                    style = LocalTextStyle.current.copy(
                        textDirection = if (LocalHomePersian.current) TextDirection.Rtl else TextDirection.Content,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (LocalHomePersian.current) ltr("${(displayProgress * 100).toInt()}%") else "${(displayProgress * 100).toInt()}%",
                    color = RouteAccent,
                    fontSize = routeFontSize(8.8f, 9.8f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { displayProgress.coerceIn(0f, 1f) },
                color = if (testing || loading) RouteAccent else RouteGreen,
                trackColor = Color.White.copy(alpha = 0.07f),
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.075f), thickness = 0.6.dp)
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiniSummaryAction(
                    icon = Icons.Outlined.Shield,
                    text = when {
                        loading -> preparation.step.localizedTitle()
                        total <= 0 -> homeText("Manual start", "شروع دستی")
                        else -> localizedStageTitle(stage)
                    },
                    color = RouteGreen,
                    onClick = onStageClick,
                    modifier = Modifier.weight(0.9f),
                )
                MiniSummaryDivider()
                MiniSummaryAction(
                    icon = Icons.Outlined.Folder,
                    text = if (savedChampionLabel != null) {
                        homeText("Route saved", "مسیر ذخیره شد")
                    } else {
                        homeText("Saved profile", "پروفایل ذخیره‌شده")
                    },
                    color = if (savedChampionLabel != null) RouteGreen else RouteAccent,
                    onClick = onSavedRouteClick,
                    modifier = Modifier.weight(1f),
                )
                MiniSummaryDivider()
                Button(
                    onClick = onToggleTest,
                    enabled = testing || paused || (canStart && !loading),
                    modifier = Modifier.weight(1.05f).height(34.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (testing) Color(0xFFA64154) else Color(0xFF238EE9),
                        contentColor = Color.White,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp),
                ) {
                    Icon(
                        if (testing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when {
                            testing -> homeText("PAUSE", "توقف")
                            paused -> homeText("RESUME", "ادامه")
                            stage == RouteTournamentStage.COMPLETE -> homeText("RUN AGAIN", "اجرای دوباره")
                            completed > 0 -> homeText("START AGAIN", "شروع دوباره")
                            else -> homeText("START TEST", "شروع تست")
                        },
                        fontSize = routeFontSize(7.8f, 9f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
            if (paused || canAdvance) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (paused) {
                        OutlinedButton(
                            onClick = onStartNew,
                            enabled = total > 0 && !loading,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, RouteAccent.copy(alpha = 0.42f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RouteAccent),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) {
                            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(homeText("NEW TEST", "تست جدید"), fontSize = routeFontSize(7.5f, 8.5f), fontWeight = FontWeight.Bold)
                        }
                    }
                    if (canAdvance) {
                        OutlinedButton(
                            onClick = onAdvance,
                            modifier = Modifier.weight(1f).height(30.dp),
                            shape = RoundedCornerShape(9.dp),
                            border = BorderStroke(1.dp, RouteGreen.copy(alpha = 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RouteGreen),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                        ) {
                            Icon(Icons.Outlined.SkipNext, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(3.dp))
                            Text(
                                homeText("ADVANCE $advanceReadyCount", "مرحله بعد: $advanceReadyCount"),
                                fontSize = routeFontSize(7.5f, 8.5f),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniSummaryAction(
    icon: ImageVector,
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.height(34.dp).clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text,
            color = Color(0xFFBED0DD),
            fontSize = routeFontSize(7.6f, 8.8f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RowScope.MiniSummaryDivider() {
    Box(Modifier.width(1.dp).height(25.dp).background(Color.White.copy(alpha = 0.09f)))
}

@Composable
private fun RouteTestSummary(
    profileName: String,
    networkLabel: String,
    savedRouteProfileName: String?,
    savedChampionLabel: String?,
    savedBackupLabel: String?,
    notice: String,
    stage: RouteTournamentStage,
    completed: Int,
    total: Int,
    progress: Float,
    testing: Boolean,
    paused: Boolean,
    loading: Boolean,
    canAdvance: Boolean,
    advanceReadyCount: Int,
    onToggleTest: () -> Unit,
    onStartNew: () -> Unit,
    onAdvance: () -> Unit,
    onSavedRouteClick: () -> Unit,
    onStageClick: () -> Unit,
) {
    Surface(
        color = Color(0xD90A1926),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, RouteAccent.copy(alpha = 0.18f), RoundedCornerShape(18.dp)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(RouteAccent.copy(alpha = 0.075f), CircleShape)
                        .border(1.dp, RouteAccent.copy(alpha = 0.46f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = RouteAccent, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        profileName,
                        color = Color.White,
                        fontSize = routeFontSize(13.5f, 15f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = routeMaxLines(1, 2),
                        overflow = routeOverflow(),
                    )
                    Text(
                        networkLabel,
                        color = UacColors.TextSecondary,
                        fontSize = routeFontSize(8.8f, 10.2f),
                        maxLines = routeMaxLines(1, 2),
                        overflow = routeOverflow(),
                    )
                }
                Surface(
                    color = RouteGreen.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = RouteGreen, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (testing) homeText("Testing", "در حال تست") else homeText("Healthy", "سالم"),
                            color = RouteGreen,
                            fontSize = routeFontSize(8.8f, 10f),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    homeText("$completed / $total samples", "$completed از $total تست"),
                    color = Color(0xFFBED0DD),
                    fontSize = routeFontSize(10f, 11.2f),
                    fontWeight = FontWeight.Medium,
                    style = LocalTextStyle.current.copy(
                        textDirection = if (LocalHomePersian.current) TextDirection.Rtl else TextDirection.Content,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (LocalHomePersian.current) ltr("${(progress * 100).toInt()}%") else "${(progress * 100).toInt()}%",
                    color = RouteAccent,
                    fontSize = routeFontSize(10f, 11.2f),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                color = if (testing) RouteAccent else RouteGreen,
                trackColor = Color.White.copy(alpha = 0.07f),
                modifier = Modifier.fillMaxWidth().height(2.5.dp),
            )
            Spacer(Modifier.height(5.dp))
            Text(
                localizedRouteNotice(notice),
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(8.5f, 10.2f),
                maxLines = routeMaxLines(1, 2),
                overflow = routeOverflow(),
            )
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                TournamentInfoCard(
                    icon = Icons.Outlined.CheckCircle,
                    iconColor = if (savedChampionLabel != null) RouteGreen else UacColors.TextSecondary,
                    title = homeText("Saved route profile", "پروفایل مسیر ذخیره‌شده"),
                    subtitle = if (savedChampionLabel != null) {
                        "${savedRouteProfileName ?: profileName} • ${savedChampionLabel} • ${savedBackupLabel ?: homeText("No backup", "بدون پشتیبان")}"
                    } else {
                        homeText("Not saved for this profile", "برای این کانفیگ ذخیره نشده")
                    },
                    modifier = Modifier.weight(1f),
                    technicalSubtitle = true,
                    onClick = onSavedRouteClick,
                )
                TournamentInfoCard(
                    icon = Icons.Outlined.Shield,
                    iconColor = RouteGreen,
                    title = localizedStageTitle(stage),
                    subtitle = stageCompactDescription(stage, total),
                    modifier = Modifier.weight(1f),
                    onClick = onStageClick,
                )
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onToggleTest,
                    enabled = total > 0 && !loading,
                    modifier = Modifier.weight(
                        when {
                            paused && canAdvance -> 0.32f
                            paused -> 0.5f
                            canAdvance -> 0.42f
                            else -> 1f
                        },
                    ).height(39.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (testing) Color(0xFFA64154) else Color(0xFF238EE9),
                        contentColor = Color.White,
                    ),
                ) {
                    Icon(
                        if (testing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                        null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            testing -> homeText("PAUSE", "توقف موقت")
                            paused -> homeText("RESUME", "ادامه")
                            stage == RouteTournamentStage.COMPLETE -> homeText("RUN AGAIN", "اجرای دوباره")
                            completed > 0 -> homeText("START AGAIN", "شروع دوباره")
                            else -> homeText("START ROUTE TOURNAMENT", "شروع مسابقه مسیرها")
                        },
                        fontSize = routeFontSize(9f, 10f),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                if (paused) {
                    OutlinedButton(
                        onClick = onStartNew,
                        enabled = total > 0 && !loading,
                        modifier = Modifier.weight(if (canAdvance) 0.31f else 0.5f).height(39.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, RouteAccent.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RouteAccent),
                    ) {
                        Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(homeText("NEW TEST", "تست جدید"), fontSize = routeFontSize(8f, 9.5f), fontWeight = FontWeight.Bold, maxLines = 1)
                    }
                }
                if (canAdvance) {
                    Button(
                        onClick = onAdvance,
                        modifier = Modifier.weight(if (paused) 0.37f else 0.58f).height(39.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RouteGreen.copy(alpha = 0.15f),
                            contentColor = RouteGreen,
                        ),
                        border = BorderStroke(1.dp, RouteGreen.copy(alpha = 0.45f)),
                    ) {
                        Icon(Icons.Outlined.SkipNext, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (paused) {
                                homeText("ADVANCE $advanceReadyCount", "مرحله بعد: $advanceReadyCount")
                            } else {
                                homeText("ADVANCE $advanceReadyCount NOW", "مرحله بعد: $advanceReadyCount")
                            },
                            fontSize = if (paused) routeFontSize(8f, 9.2f) else routeFontSize(9f, 9.5f),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TournamentInfoCard(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    technicalSubtitle: Boolean = false,
    onClick: () -> Unit = {},
) {
    val shape = RoundedCornerShape(10.dp)
    val isPersian = LocalHomePersian.current
    Surface(
        color = Color.White.copy(alpha = 0.026f),
        shape = shape,
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.075f), shape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPersian) {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = homeText("Open details", "نمایش جزئیات"),
                    tint = UacColors.TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp).scale(scaleX = -1f, scaleY = 1f),
                )
                Spacer(Modifier.width(5.dp))
            }
            if (!isPersian) {
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .background(iconColor.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(17.dp))
                }
                Spacer(Modifier.width(7.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (isPersian) title.withNonBreakingHalfSpaces() else title,
                    color = Color(0xFFBED0DD),
                    fontSize = routeFontSize(9.2f, 10.5f),
                    fontWeight = FontWeight.Medium,
                    maxLines = routeMaxLines(1, 2),
                    overflow = routeOverflow(),
                    textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                    style = LocalTextStyle.current.copy(
                        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (isPersian && technicalSubtitle) ltr(subtitle) else if (isPersian) subtitle.withNonBreakingHalfSpaces() else subtitle,
                    color = UacColors.TextSecondary,
                    fontSize = routeFontSize(8f, 9.4f),
                    maxLines = routeMaxLines(1, 2),
                    overflow = routeOverflow(),
                    textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                    style = LocalTextStyle.current.copy(
                        textDirection = when {
                            isPersian && technicalSubtitle -> TextDirection.Ltr
                            isPersian -> TextDirection.Rtl
                            else -> TextDirection.Content
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (isPersian) {
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .background(iconColor.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, null, tint = iconColor, modifier = Modifier.size(17.dp))
                }
            } else {
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = homeText("Open details", "نمایش جزئیات"),
                    tint = UacColors.TextSecondary.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SavedRouteProfileDialog(
    details: SavedRouteProfileDetails?,
    finalStageAvailable: Boolean,
    savedRouteSaved: Boolean,
    onLoadFinalStage: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0C1B28),
        shape = RoundedCornerShape(20.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(RouteGreen.copy(alpha = 0.1f), CircleShape)
                    .border(1.dp, RouteGreen.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Hub, null, tint = RouteGreen, modifier = Modifier.size(24.dp))
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(homeText("Saved route profile", "پروفایل مسیر ذخیره‌شده"), color = Color.White, fontSize = routeFontSize(18f, 20f), fontWeight = FontWeight.SemiBold)
                Text(
                    homeText(
                        "Bound to one configuration and network fingerprint",
                        "مخصوص همین کانفیگ و اثرانگشت شبکه",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = routeFontSize(11f, 12.5f),
                    lineHeight = routeFontSize(15f, 18f),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 570.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (details == null) {
                    ProfileDialogEmptyState()
                } else {
                    Surface(
                        color = RouteGreen.copy(alpha = 0.065f),
                        shape = RoundedCornerShape(13.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, RouteGreen.copy(alpha = 0.22f), RoundedCornerShape(13.dp)),
                    ) {
                        Row(
                            modifier = Modifier.padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = RouteGreen, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    details.profileName,
                                    color = Color.White,
                                    fontSize = routeFontSize(15f, 16.5f),
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${details.profileType} • ${details.protocol} • ${details.profileId}",
                                    color = RouteGreen,
                                    fontSize = routeFontSize(10f, 11.5f),
                                    lineHeight = routeFontSize(14f, 17f),
                                )
                            }
                        }
                    }
                    DialogSection(homeText("CONFIGURATION", "کانفیگ")) {
                        DialogValue(homeText("Original server", "سرور اصلی"), details.server)
                        DialogValue(homeText("Transport / Security", "Transport / Security"), "${details.transport} / ${details.security}")
                        DialogValue("SNI", details.sni)
                        DialogValue("Host", details.host)
                        DialogValue("Path", details.path)
                        DialogValue("ALPN / Fingerprint", "${details.alpn} / ${details.fingerprint}")
                    }
                    DialogSection(homeText("NETWORK & CARRIER", "شبکه و اپراتور")) {
                        DialogValue("Transport", details.networkTransport.replaceFirstChar(Char::uppercase))
                        DialogValue(homeText("Carrier", "اپراتور"), details.carrier)
                        DialogValue(homeText("Carrier class", "دسته اپراتور"), details.carrierClass)
                        DialogValue(homeText("Provider / ASN", "سرویس‌دهنده / ASN"), "${details.provider} / ${details.asn}")
                        DialogValue(homeText("Network fingerprint", "اثر انگشت شبکه"), details.networkFingerprint, monospace = true)
                        DialogValue("MTU / IP", "${details.networkMtu} / ${details.ipSupport}")
                        DialogValue(
                            homeText("State", "وضعیت"),
                            "${if (details.validated) homeText("Validated", "تأییدشده") else homeText("Not validated", "تأییدنشده")} • " +
                                if (details.metered) homeText("Metered", "حجمی") else homeText("Unmetered", "غیرحجمی"),
                        )
                    }
                    details.champion?.let { SavedRouteBlock(homeText("CHAMPION ROUTE", "مسیر برنده"), it, RouteGreen) }
                    details.backup?.let { SavedRouteBlock(homeText("BACKUP ROUTE", "مسیر پشتیبان"), it, RouteAmber) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(homeText("CLOSE", "بستن"), color = RouteAccent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onLoadFinalStage, enabled = finalStageAvailable) {
                Icon(Icons.Outlined.BarChart, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    if (savedRouteSaved) {
                        homeText("LOAD LAST ROUTE", "لود آخرین مسیر")
                    } else {
                        homeText("NO SAVED ROUTE", "مسیر ذخیره نشده")
                    },
                    fontSize = routeFontSize(10f, 11.5f),
                    fontWeight = FontWeight.Bold,
                )
            }
        },
    )
}

@Composable
private fun ProfileDialogEmptyState() {
    Surface(color = Color.White.copy(alpha = 0.025f), shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = UacColors.TextSecondary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(6.dp))
            Text(homeText("No route profile is saved", "هنوز پروفایل مسیری ذخیره نشده"), color = Color.White, fontSize = routeFontSize(13f, 15f), fontWeight = FontWeight.Medium)
            Text(
                homeText("Select USE on a healthy route to save it", "برای ذخیره، گزینه استفاده روی یک مسیر سالم رو بزن"),
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(10.5f, 12.5f),
                lineHeight = routeFontSize(15f, 18f),
            )
        }
    }
}

@Composable
private fun DialogSection(title: String, content: @Composable () -> Unit) {
    val isPersian = LocalHomePersian.current
    Surface(color = Color.White.copy(alpha = 0.025f), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                if (isPersian) title.withNonBreakingHalfSpaces() else title,
                color = RouteAccent,
                fontSize = routeFontSize(10f, 12f),
                fontWeight = FontWeight.Bold,
                letterSpacing = if (LocalHomePersian.current) 0.sp else 0.7.sp,
            )
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun DialogValue(label: String, value: String, monospace: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            color = UacColors.TextSecondary,
            fontSize = routeFontSize(10.5f, 12f),
            lineHeight = routeFontSize(15f, 18f),
            modifier = Modifier.weight(0.38f),
        )
        Text(
            value,
            color = Color(0xFFD7E5ED),
            fontSize = routeFontSize(11f, 12f),
            lineHeight = routeFontSize(15f, 18f),
            fontWeight = FontWeight.Medium,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            modifier = Modifier.weight(0.62f),
        )
    }
}

@Composable
private fun SavedRouteBlock(title: String, route: SavedRouteDetails, accent: Color) {
    val isPersian = LocalHomePersian.current
    Surface(
        color = accent.copy(alpha = 0.045f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(
                if (isPersian) title.withNonBreakingHalfSpaces() else title,
                color = accent,
                fontSize = routeFontSize(10f, 12f),
                fontWeight = FontWeight.Bold,
                letterSpacing = if (LocalHomePersian.current) 0.sp else 0.7.sp,
            )
            Spacer(Modifier.height(5.dp))
            DialogValue(homeText("Name / Role", "نام / نقش"), "${route.label} / ${localizedSavedRouteRole(route.role)}")
            DialogValue("Edge", route.edge, monospace = true)
            DialogValue("DNS Resolver", route.resolver)
            DialogValue("Fragment", route.fragment, monospace = true)
            DialogValue("TUN MTU", route.mtu.toString())
            DialogValue(
                homeText("Compatibility", "سازگاری"),
                if (route.directCompat) homeText("Direct compatibility", "اتصال مستقیم سازگار") else homeText("Adaptive route", "مسیر تطبیقی"),
            )
            DialogValue("Candidate ID", route.id, monospace = true)
        }
    }
}

@Composable
private fun StageDetailsDialog(stage: RouteTournamentStage, onDismiss: () -> Unit) {
    val info = stageExplanation(stage)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0C1B28),
        shape = RoundedCornerShape(20.dp),
        icon = {
            Box(
                modifier = Modifier.size(48.dp).background(RouteAccent.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Shield, null, tint = RouteAccent, modifier = Modifier.size(25.dp))
            }
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(localizedStageTitle(stage), color = Color.White, fontSize = routeFontSize(18f, 21f), fontWeight = FontWeight.SemiBold)
                Text(homeText("Route Tournament stage", "مرحله مسابقه مسیرها"), color = RouteAccent, fontSize = routeFontSize(11f, 13f), fontWeight = FontWeight.Medium)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StageInfoBlock(homeText("PURPOSE", "هدف"), info.purpose, RouteAccent)
                StageInfoBlock(homeText("WHAT IS TESTED", "چه چیزهایی تست می‌شن"), info.tests, RouteGreen)
                StageInfoBlock(homeText("PROMOTION RULE", "شرط رفتن به مرحله بعد"), info.promotion, RouteAmber)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    StageStat(homeText("Workers", "پردازش‌ها"), stage.workers.toString(), Modifier.weight(1f))
                    StageStat(homeText("Samples", "تست‌ها"), stage.samplesPerCandidate.toString(), Modifier.weight(1f))
                    StageStat(
                        homeText("Shortlist", "منتخب‌ها"),
                        if (stage.shortlistSize == Int.MAX_VALUE) homeText("All", "همه") else stage.shortlistSize.toString(),
                        Modifier.weight(1f),
                    )
                }
                Text(
                    homeText(
                        "ADVANCE NOW only promotes routes that already completed a fully healthy probe in this stage.",
                        "رفتن به مرحله بعد فقط مسیرهایی رو منتقل می‌کنه که تست کامل همین مرحله رو سالم رد کرده باشن.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = routeFontSize(10.5f, 12.5f),
                    lineHeight = routeFontSize(15f, 19f),
                    textAlign = if (LocalHomePersian.current) TextAlign.Right else TextAlign.Start,
                    style = LocalTextStyle.current.copy(
                        textDirection = if (LocalHomePersian.current) TextDirection.Rtl else TextDirection.Content,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(homeText("GOT IT", "متوجه شدم"), color = RouteAccent, fontWeight = FontWeight.Bold) }
        },
    )
}

private data class StageExplanation(val purpose: String, val tests: String, val promotion: String)

@Composable
private fun stageExplanation(stage: RouteTournamentStage): StageExplanation = when (stage) {
    RouteTournamentStage.QUALIFIER -> StageExplanation(
        homeText("Quickly eliminate unreachable Edge and Fragment paths without changing the isolated final tests.", "مسیرهای Edge و Fragment که در دسترس نیستن، سریع کنار گذاشته می‌شن؛ تست‌های نهایی همچنان مستقل انجام می‌شن."),
        homeText(
            "Equivalent routes share a batched Xray HTTP preflight. Failures are retried once; DNS and speed are not finalized here.",
            "مسیرهای هم‌ارز، پیش‌آزمایش ${ltr("HTTP")} رو با یک اجرای گروهی ${ltr("Xray")} انجام می‌دن. خطاها یک بار دوباره بررسی می‌شن و نتیجه ${ltr("DNS")} یا سرعت در این مرحله نهایی نیست.",
        ),
        homeText(
            "Up to 250 Edge, DNS and tuning families move to isolated HTTP, DNS, upload and download verification.",
            "تا ۲۵۰ خانواده سالم ${ltr("Edge / DNS / Tuning")} وارد بررسی مستقل ${ltr("HTTP")}، ${ltr("DNS")}، آپلود و دانلود می‌شن.",
        ),
    )
    RouteTournamentStage.VERIFICATION -> StageExplanation(
        homeText("Compare every resolver independently without repeating MTUs that cannot affect proxy traffic.", "هر ${ltr("Resolver")} مستقل بررسی می‌شه، بدون تکرار ${ltr("MTU")}هایی که روی ترافیک Proxy اثری ندارن."),
        homeText("A fresh isolated Xray route checks HTTP, DNS, real upload and download, latency and jitter.", "یک مسیر تازه و مستقل ${ltr("Xray")}، ${ltr("HTTP")}، ${ltr("DNS")}، آپلود و دانلود واقعی، تأخیر و نوسان رو می‌سنجه."),
        homeText("The best 24 diverse route families are expanded into four real native MTUs.", "۲۴ خانواده برتر و متنوع، برای چهار ${ltr("MTU")} واقعی باز می‌شن."),
    )
    RouteTournamentStage.MTU_VALIDATION -> StageExplanation(
        homeText("Measure MTU on the real Android TUN path instead of pretending proxy-only MTUs are different.", "${ltr("MTU")} روی مسیر واقعی ${ltr("TUN")} اندروید سنجیده می‌شه؛ نه روی مسیر Proxy که ${ltr("MTU")} در اون اثری نداره."),
        homeText("An app-only native VPN checks HTTP, DNS, upload, download and actual Xray TX/RX growth for every MTU.", "یک ${ltr("VPN")} آزمایشی فقط برای خود برنامه، ${ltr("HTTP")}، ${ltr("DNS")}، آپلود، دانلود و رشد واقعی ${ltr("TX/RX")} در ${ltr("Xray")} رو بررسی می‌کنه."),
        homeText("If native MTU fails, routes that passed Resolver verification still advance to stability on the SOCKS path.", "اگر ${ltr("MTU")} واقعی رد بشه، مسیرهایی که در بررسی ${ltr("Resolver")} قبول شدن با همون مسیر ${ltr("SOCKS")} به پایداری می‌رن."),
    )
    RouteTournamentStage.STABILITY -> StageExplanation(
        homeText("Measure repeatability, jitter and reliability over multiple samples.", "تکرارپذیری، نوسان تأخیر و قابل‌اعتماد بودن مسیر در چند تست سنجیده می‌شه."),
        homeText("Two cold samples per route; pass rate, P95 latency, jitter, DNS success and median throughput.", "برای هر مسیر دو تست تازه انجام می‌شه و نرخ موفقیت، تأخیر ${ltr("P95")}، نوسان، موفقیت ${ltr("DNS")} و سرعت میانه بررسی می‌شن."),
        homeText("The 6 strongest stable routes advance to the stress test.", "۶ مسیر پایدارتر وارد تست فشار می‌شن."),
    )
    RouteTournamentStage.STRESS -> StageExplanation(
        homeText("Find routes that remain fast and healthy under repeated cold starts.", "مسیرهایی پیدا می‌شن که در اتصال‌های تازه و تکراری همچنان سریع و سالم بمونن."),
        homeText("Three repeated full probes with stricter scoring for failures, stalls and latency spikes.", "سه تست کامل و تکراری با امتیازدهی سخت‌گیرانه‌تر برای خطا، مکث و جهش تأخیر انجام می‌شه."),
        homeText("The top 2 finalists become Champion candidates.", "دو مسیر برتر به مرحله انتخاب برنده می‌رن."),
    )
    RouteTournamentStage.CHAMPIONSHIP -> StageExplanation(
        homeText("Choose the Champion and a reliable automatic recovery Backup.", "مسیر برنده و یک مسیر پشتیبان مطمئن برای بازیابی خودکار انتخاب می‌شن."),
        homeText("The final routes run in A-B-B-A order to reduce time-order and network-drift bias.", "مسیرهای نهایی با ترتیب ${ltr("A-B-B-A")} تست می‌شن تا تغییرات لحظه‌ای شبکه نتیجه رو منحرف نکنه."),
        homeText("Winner is ranked by reliability, HTTP/DNS success, throughput, P95 latency, jitter and confidence.", "برنده با توجه به پایداری، موفقیت ${ltr("HTTP/DNS")}، سرعت، تأخیر ${ltr("P95")}، نوسان و میزان اطمینان رتبه‌بندی می‌شه."),
    )
    RouteTournamentStage.COMPLETE -> StageExplanation(
        homeText("Present the final ranking and automatically save Champion and Backup for this profile and network.", "رتبه‌بندی نهایی نمایش داده می‌شه و برنده و پشتیبان برای همین کانفیگ و شبکه خودکار ذخیره می‌شن."),
        homeText("No background probe is running; all displayed measurements come from completed stages.", "دیگه تستی در پس‌زمینه اجرا نمی‌شه و همه عددها نتیجه مرحله‌های کامل‌شده هستن."),
        homeText("Use a healthy result to bind its Champion and Backup to this configuration and network fingerprint.", "یک نتیجه سالم رو انتخاب کن تا برنده و پشتیبان برای همین کانفیگ و اثرانگشت شبکه ذخیره بشن."),
    )
}

@Composable
private fun StageInfoBlock(title: String, text: String, accent: Color) {
    val isPersian = LocalHomePersian.current
    val directionalStyle = LocalTextStyle.current.copy(
        textDirection = if (isPersian) TextDirection.Rtl else TextDirection.Content,
    )
    Surface(color = accent.copy(alpha = 0.045f), shape = RoundedCornerShape(11.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(
                if (isPersian) title.withNonBreakingHalfSpaces() else title,
                color = accent,
                fontSize = routeFontSize(10f, 12.5f),
                fontWeight = FontWeight.Bold,
                letterSpacing = if (isPersian) 0.sp else 0.7.sp,
                textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                style = directionalStyle,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (isPersian) text.withNonBreakingHalfSpaces() else text,
                color = Color(0xFFBED0DD),
                fontSize = routeFontSize(11.5f, 13.5f),
                lineHeight = routeFontSize(16f, 20.5f),
                textAlign = if (isPersian) TextAlign.Right else TextAlign.Start,
                style = directionalStyle,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun StageStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(9.dp), modifier = modifier) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(label, color = UacColors.TextSecondary, fontSize = routeFontSize(9.5f, 11f))
        }
    }
}

@Composable
private fun RouteResultCard(
    rank: Int,
    row: RouteSpeedRow,
    recommended: Boolean,
    backup: Boolean,
    selected: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
) {
    var expanded by rememberSaveable(row.candidateId) { mutableStateOf(false) }
    val color = row.status.color()
    val routeTitle = routeCardTitle(row)
    val confidenceText = localizedConfidenceLabel(row.confidence)
    val shape = RoundedCornerShape(14.dp)
    Surface(
        color = when {
            selected -> RouteGreen.copy(alpha = 0.075f)
            recommended -> RouteAccent.copy(alpha = 0.065f)
            backup -> RouteAmber.copy(alpha = 0.06f)
            else -> Color(0xC90A1926)
        },
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .border(
                1.dp,
                when {
                    selected -> RouteGreen.copy(alpha = 0.48f)
                    recommended -> RouteAccent.copy(alpha = 0.4f)
                    backup -> RouteAmber.copy(alpha = 0.34f)
                    else -> Color.White.copy(alpha = 0.075f)
                },
                shape,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 9.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(27.dp)
                        .border(1.5.dp, rankColor(rank), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$rank", color = Color.White, fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(7.dp))
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(0xFFF4F7F9), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.status == RouteSpeedStatus.STARTING || row.status == RouteSpeedStatus.TESTING) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = RouteAccent, strokeWidth = 1.8.dp)
                    } else {
                        DnsBrandIcon(row.resolverKey)
                    }
                }
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        routeTitle,
                        color = Color.White,
                        fontSize = routeFontSize(9.2f, 10.3f),
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 10.3.sp,
                        maxLines = 2,
                        softWrap = true,
                    )
                    Text(
                        row.route,
                        color = UacColors.TextSecondary,
                        fontSize = routeFontSize(7.2f, 8.2f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConfidenceSignalIndicator(
                    confidence = row.confidence,
                    contentDescription = confidenceText,
                    modifier = Modifier.size(width = 15.dp, height = 15.dp),
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.ChevronRight,
                    if (expanded) homeText("Hide details", "بستن جزئیات") else homeText("Show details", "نمایش جزئیات"),
                    tint = UacColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            if (!expanded) {
                Spacer(Modifier.height(4.dp))
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.5.dp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RouteMetric(homeText("Ping", "پینگ"), row.latencyMs?.let { "$it ms" } ?: "—", RouteGreen, Modifier.weight(1f))
                    RouteMetric(homeText("Speed", "سرعت"), formatThroughput(row.throughputKbps), Color(0xFF2CA8FF), Modifier.weight(1f))
                    RouteMetric(homeText("Confidence", "اطمینان"), confidenceText, confidenceColor(row.confidence), Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (recommended) ExpandedRouteBadge(homeText("Champion", "برنده"), Icons.Outlined.EmojiEvents, Color(0xFF2CA8FF))
                    if (backup) ExpandedRouteBadge(homeText("Backup", "پشتیبان"), Icons.Outlined.Shield, RouteAmber)
                    if (selected) ExpandedRouteBadge(homeText("Selected", "انتخاب‌شده"), Icons.Outlined.CheckCircle, RouteGreen)
                    ExpandedRouteBadge(row.status.localizedLabel(), Icons.Outlined.FavoriteBorder, color)
                }
                Spacer(Modifier.height(8.dp))
                ExpandedRoutePanel(
                    row = row,
                    routeTitle = routeTitle,
                    confidenceText = confidenceText,
                    recommended = recommended,
                    selected = selected,
                    testing = testing,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun ExpandedRouteBadge(text: String, icon: ImageVector, color: Color) {
    Surface(
        color = color.copy(alpha = 0.09f),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.border(1.dp, color.copy(alpha = 0.36f), RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text(text, color = color, fontSize = routeFontSize(8f, 9.2f), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ExpandedRoutePanel(
    row: RouteSpeedRow,
    routeTitle: String,
    confidenceText: String,
    recommended: Boolean,
    selected: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
) {
    val accent = if (row.usable) RouteGreen else RouteRed
    val shape = RoundedCornerShape(15.dp)
    Surface(
        color = Color(0xE6112638),
        shape = shape,
        modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.09f), shape),
    ) {
        Column(Modifier.padding(7.dp)) {
            if (row.usable) {
                ExpandedUseRouteButton(
                    recommended = recommended,
                    selected = selected,
                    testing = testing,
                    onSelect = onSelect,
                )
                Spacer(Modifier.height(5.dp))
            }
            ExpandedSectionHeader(Icons.Outlined.Map, homeText("Full Route", "مسیر کامل"))
            Spacer(Modifier.height(3.dp))
            Text(routeTitle, color = Color(0xFFD7E5ED), fontSize = routeFontSize(9.5f, 10.5f), lineHeight = routeFontSize(12f, 14f))
            Text(row.route, color = UacColors.TextSecondary, fontSize = routeFontSize(8.5f, 9.2f), lineHeight = routeFontSize(11f, 13f))
            Text(
                homeText("Candidate: ${row.candidateId}", "گزینه: ${row.candidateId}"),
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(8f, 8.8f),
                fontFamily = FontFamily.Monospace,
                lineHeight = 11.sp,
            )
            ExpandedDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ExpandedIdentityBlock(
                    icon = Icons.Outlined.Public,
                    title = homeText("Endpoint", "مقصد"),
                    value = "${row.edgeKey}\n${friendlyResolver(row.resolverKey)}",
                    modifier = Modifier.weight(1f),
                )
                ExpandedIdentityBlock(
                    icon = Icons.Outlined.PersonOutline,
                    title = homeText("Candidate", "گزینه"),
                    value = row.candidateId,
                    modifier = Modifier.weight(1f),
                )
            }
            ExpandedDivider()
            ExpandedSectionHeader(Icons.Outlined.MonitorHeart, homeText("Metrics", "معیارها"))
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ExpandedMetric(homeText("Ping", "پینگ"), row.latencyMs?.let { "$it ms" } ?: "—", RouteGreen, Modifier.weight(1f))
                ExpandedMetric(homeText("Speed", "سرعت"), formatThroughput(row.throughputKbps), Color(0xFF4A91FF), Modifier.weight(1f))
                ExpandedMetric(homeText("Confidence", "اطمینان"), confidenceText, confidenceColor(row.confidence), Modifier.weight(1f))
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ExpandedMetric("HTTP", if (row.httpAttempted > 0) "${row.httpSucceeded}/${row.httpAttempted}" else "—", RouteGreen, Modifier.weight(1f))
                ExpandedMetric("DNS", if (row.sampleCount > 0) "${row.dnsSuccessCount}/${row.sampleCount}" else "—", RouteGreen, Modifier.weight(1f))
                ExpandedMetric(homeText("Samples", "تست‌ها"), "${row.successfulSamples}/${row.sampleCount}", RouteGreen, Modifier.weight(1f))
                ExpandedMetric(homeText("Jitter", "نوسان"), row.jitterMs?.let { "$it ms" } ?: "—", RouteGreen, Modifier.weight(1f))
            }
            Spacer(Modifier.height(3.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ExpandedMetric(homeText("Upload", "آپلود"), formatThroughput(row.uploadKbps), RouteGreen, Modifier.weight(1f))
                ExpandedMetric(homeText("Download", "دانلود"), formatThroughput(row.downloadKbps), Color(0xFF4A91FF), Modifier.weight(1f))
                ExpandedMetric("TX/RX", "${row.txDelta}/${row.rxDelta}", RouteGreen, Modifier.weight(1f))
                ExpandedMetric("MTU", if (row.mtuValidated) "${row.mtu} ✓" else row.mtu.toString(), if (row.mtuValidated) RouteGreen else RouteAmber, Modifier.weight(1f))
            }
            ExpandedDivider()
            ExpandedSectionHeader(Icons.Outlined.Shield, homeText("Health Summary", "خلاصه سلامت"))
            Spacer(Modifier.height(1.dp))
            Text(
                localizedFailureFingerprint(row.failureFingerprint),
                color = accent,
                fontSize = routeFontSize(9f, 10f),
                fontWeight = FontWeight.Medium,
                lineHeight = 11.sp,
            )
            ExpandedDivider()
            ExpandedSectionHeader(Icons.Outlined.Code, homeText("Diagnostics", "جزئیات فنی"))
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                routeDiagnostics(row).forEach { (label, value) ->
                    DiagnosticChip(label, value, if (isPositiveDiagnostic(value)) RouteGreen else RouteAccent)
                }
            }
        }
    }
}

@Composable
private fun ExpandedUseRouteButton(
    recommended: Boolean,
    selected: Boolean,
    testing: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedButton(
        onClick = onSelect,
        enabled = !selected && !testing,
        modifier = Modifier.fillMaxWidth().height(36.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) RouteGreen.copy(alpha = 0.5f) else RouteGreen,
        ),
        border = BorderStroke(1.2.dp, RouteGreen.copy(alpha = if (selected) 0.2f else 0.75f)),
    ) {
        Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            if (selected) {
                homeText("Route Selected", "مسیر انتخاب شد")
            } else if (recommended) {
                homeText("Use Champion + Backup", "استفاده از برنده و پشتیبان")
            } else {
                homeText("Use This Route", "استفاده از این مسیر")
            },
            fontSize = routeFontSize(9.5f, 10.8f),
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ExpandedSectionHeader(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFF4A91FF), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, color = Color.White, fontSize = routeFontSize(9.5f, 10.5f), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExpandedIdentityBlock(icon: ImageVector, title: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Color(0xFF4A91FF), modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = routeFontSize(9f, 10.5f), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(1.dp))
            Text(
                value,
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(8f, 8.8f),
                fontFamily = FontFamily.Monospace,
                lineHeight = 10.5.sp,
            )
        }
    }
}

@Composable
private fun ExpandedMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        color = Color.Black.copy(alpha = 0.12f),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(6.dp)),
    ) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 2.dp)) {
            Text(
                label,
                color = UacColors.TextSecondary,
                fontSize = routeFontSize(6.6f, 7.6f),
                lineHeight = routeFontSize(8f, 8.5f),
                maxLines = 1,
            )
            Text(
                value,
                color = color,
                fontSize = routeFontSize(8.4f, 9.1f),
                lineHeight = routeFontSize(10f, 10.5f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ExpandedDivider() {
    Spacer(Modifier.height(3.dp))
    HorizontalDivider(color = Color.White.copy(alpha = 0.075f), thickness = 0.6.dp)
    Spacer(Modifier.height(3.dp))
}

@Composable
private fun DiagnosticChip(label: String, value: String, color: Color) {
    Surface(
        color = Color(0xFF071522),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.075f), RoundedCornerShape(6.dp)),
    ) {
        Row(Modifier.padding(horizontal = 5.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = UacColors.TextSecondary, fontSize = 7.sp)
            Spacer(Modifier.width(3.dp))
            Text(value, color = color, fontSize = 7.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

private fun routeDiagnostics(row: RouteSpeedRow): List<Pair<String, String>> {
    val values = linkedMapOf(
        "accepted" to row.usable.toString(),
        "stage" to row.stageReached.title,
        "score" to row.score.toString(),
        "http" to if (row.httpAttempted > 0) "${row.httpSucceeded}/${row.httpAttempted}" else "—",
        "dns" to row.dnsSucceeded.toString(),
        "samples" to "${row.successfulSamples}/${row.sampleCount}",
        "bytes" to row.payloadBytes.toString(),
        "latency" to (row.latencyMs?.let { "${it}ms" } ?: "—"),
        "p95" to (row.p95LatencyMs?.let { "${it}ms" } ?: "—"),
        "dnsLatency" to (row.dnsLatencyMs?.let { "${it}ms" } ?: "—"),
        "jitter" to (row.jitterMs?.let { "${it}ms" } ?: "—"),
        "upload" to formatThroughput(row.uploadKbps),
        "download" to formatThroughput(row.downloadKbps),
        "txDelta" to row.txDelta.toString(),
        "rxDelta" to row.rxDelta.toString(),
        "transfer" to "${row.transferSuccessCount}/${row.sampleCount}",
        "mtuValidated" to row.mtuValidated.toString(),
        "resolver" to friendlyResolver(row.resolverKey),
        "mtu" to row.mtu.toString(),
    )
    DIAGNOSTIC_VALUE_REGEX.findAll(row.detail).forEach { match ->
        val label = match.groupValues[1]
        val value = match.groupValues[2].trimEnd(',', ']', ')').take(26)
        if (label !in values && value.isNotBlank()) values[label] = value
    }
    return values.entries.take(MAX_DIAGNOSTIC_CHIPS).map { it.key to it.value }
}

private fun isPositiveDiagnostic(value: String): Boolean =
    value.equals("true", ignoreCase = true) ||
        value.equals("healthy", ignoreCase = true) ||
        value.toIntOrNull()?.let { it > 0 } == true ||
        "/" in value

@Composable
private fun RouteBadge(text: String, color: Color) {
    Spacer(Modifier.width(5.dp))
    Surface(color = color.copy(alpha = 0.13f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            color = color,
            fontSize = routeFontSize(7f, 8.5f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DnsBrandIcon(resolver: String) {
    val key = resolver.lowercase(Locale.US)
    val drawable = when {
        "cloudflare" in key -> R.drawable.ic_dns_cloudflare
        "google" in key -> R.drawable.ic_dns_google
        "quad9" in key -> R.drawable.ic_dns_quad9
        "opendns" in key || "open-dns" in key -> R.drawable.ic_dns_opendns
        "nextdns" in key || "next-dns" in key -> R.drawable.ic_dns_nextdns
        "adguard" in key -> R.drawable.ic_dns_adguard
        else -> null
    }
    when {
        drawable != null -> Image(
            painter = painterResource(drawable),
            contentDescription = friendlyResolver(resolver),
            modifier = if ("opendns" in key || "open-dns" in key) {
                Modifier.width(27.dp).height(13.dp)
            } else {
                Modifier.size(22.dp)
            },
        )
        else -> {
            Text(
                friendlyResolver(resolver).take(2).uppercase(Locale.US),
                color = resolverColor(resolver),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RouteMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = Color.White.copy(alpha = 0.03f), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = UacColors.TextSecondary.copy(alpha = 0.75f), fontSize = routeFontSize(6.7f, 8.3f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(value, color = color, fontSize = routeFontSize(8.3f, 9.5f), fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun RowScope.DetailPill(label: String, value: String, success: Boolean) {
    Column(Modifier.weight(1f)) {
        Text(label, color = UacColors.TextSecondary.copy(alpha = 0.7f), fontSize = routeFontSize(6.8f, 8.2f), maxLines = 1)
        Text(
            value,
            color = if (success) RouteGreen else UacColors.TextSecondary,
            fontSize = routeFontSize(8.7f, 9.6f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = routeOverflow(),
        )
    }
}

@Composable
private fun RouteTestReadyState(
    profileName: String,
    onStart: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0x99101C29),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, RouteAccent.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .background(RouteAccent.copy(alpha = 0.1f), CircleShape)
                        .border(1.dp, RouteAccent.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Hub, null, tint = RouteAccent, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    homeText("Ready when you are", "هر وقت آماده‌ای شروع کن"),
                    color = Color.White,
                    fontSize = routeFontSize(14f, 16f),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    homeText(
                        "$profileName is selected. Nothing starts until you tap the button.",
                        "کانفیگ ${ltr(profileName)} انتخاب شده و تا وقتی دکمه رو نزنی، هیچ تستی شروع نمی‌شه.",
                    ),
                    color = UacColors.TextSecondary,
                    fontSize = routeFontSize(10f, 12f),
                    lineHeight = routeFontSize(14f, 17f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun EmptyRouteState(onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = UacColors.TextSecondary, modifier = Modifier.size(30.dp))
            Spacer(Modifier.height(9.dp))
            Text(homeText("No routes available", "مسیر قابل استفاده‌ای پیدا نشد"), color = Color.White, fontSize = routeFontSize(13f, 15f), fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(homeText("Check the network and refresh the route list", "شبکه رو بررسی کن و لیست مسیرها رو دوباره بساز"), color = UacColors.TextSecondary, fontSize = routeFontSize(10f, 12f))
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(homeText("REFRESH", "تازه‌سازی"))
            }
        }
    }
}

private fun RouteSpeedStatus.color(): Color = when (this) {
    RouteSpeedStatus.QUEUED -> Color(0xFF71889C)
    RouteSpeedStatus.STARTING, RouteSpeedStatus.TESTING -> RouteAccent
    RouteSpeedStatus.PASSED -> RouteGreen
    RouteSpeedStatus.FAILED -> RouteRed
    RouteSpeedStatus.STOPPED -> RouteAmber
}

@Composable
private fun RouteSpeedStatus.localizedLabel(): String = when (this) {
    RouteSpeedStatus.QUEUED -> homeText("Queued", "در صف")
    RouteSpeedStatus.STARTING -> homeText("Starting", "در حال شروع")
    RouteSpeedStatus.TESTING -> homeText("Testing", "در حال تست")
    RouteSpeedStatus.PASSED -> homeText("Healthy", "سالم")
    RouteSpeedStatus.FAILED -> homeText("Failed", "ناموفق")
    RouteSpeedStatus.STOPPED -> homeText("Stopped", "متوقف")
}

private fun sortRouteRows(rows: List<RouteSpeedRow>, option: RouteSortOption): List<RouteSpeedRow> {
    fun statusBucket(row: RouteSpeedRow): Int = when {
        row.usable -> 0
        row.status == RouteSpeedStatus.TESTING || row.status == RouteSpeedStatus.STARTING -> 1
        row.status == RouteSpeedStatus.QUEUED -> 2
        else -> 3
    }
    val metricComparator = when (option) {
        RouteSortOption.SPEED -> compareByDescending<RouteSpeedRow> { it.throughputKbps }
            .thenBy { it.latencyMs ?: Long.MAX_VALUE }
        RouteSortOption.PING -> compareBy<RouteSpeedRow> { it.latencyMs ?: Long.MAX_VALUE }
            .thenByDescending { it.throughputKbps }
        RouteSortOption.CONFIDENCE -> compareByDescending<RouteSpeedRow> { it.confidence }
            .thenByDescending { it.tournamentScore }
        RouteSortOption.OVERALL -> compareByDescending<RouteSpeedRow> { it.tournamentScore }
            .thenByDescending { it.confidence }
    }
    return rows.sortedWith(
        compareBy<RouteSpeedRow>(::statusBucket)
            .then(metricComparator)
            .thenBy { it.candidateId },
    )
}

@Composable
private fun stageCompactDescription(stage: RouteTournamentStage, total: Int): String = when (stage) {
    RouteTournamentStage.QUALIFIER -> homeText("$total routes • batched HTTP preflight", "$total مسیر • پیش‌آزمایش گروهی ${ltr("HTTP")}")
    RouteTournamentStage.VERIFICATION -> homeText("Up to 250 resolver families • isolated transfer", "تا ۲۵۰ خانواده ${ltr("Resolver")} • تست مستقل انتقال")
    RouteTournamentStage.MTU_VALIDATION -> homeText("96 native routes • real MTU and TX/RX", "۹۶ مسیر واقعی • سنجش ${ltr("MTU و TX/RX")}")
    RouteTournamentStage.STABILITY -> homeText("24 routes • repeated stability", "۲۴ مسیر • تست تکراری پایداری")
    RouteTournamentStage.STRESS -> homeText("6 finalists • repeated tests", "۶ مسیر نهایی • تست‌های تکراری")
    RouteTournamentStage.CHAMPIONSHIP -> homeText("2 finalists • A-B-B-A comparison", "۲ مسیر نهایی • مقایسه ${ltr("A-B-B-A")}")
    RouteTournamentStage.COMPLETE -> homeText("Champion and backup are ready", "برنده و مسیر پشتیبان آماده‌ان")
}

private fun routeCardTitle(row: RouteSpeedRow): String =
    "${friendlyResolver(row.resolverKey)} • ${row.fragmentKey} • MTU ${row.mtu}"

private fun friendlyResolver(value: String): String = when {
    value.contains("cloudflare", ignoreCase = true) -> "Cloudflare"
    value.contains("google", ignoreCase = true) -> "Google DNS"
    value.contains("quad9", ignoreCase = true) -> "Quad9"
    value.contains("opendns", ignoreCase = true) -> "OpenDNS"
    value.contains("adguard", ignoreCase = true) -> "AdGuard"
    value.contains("shecan", ignoreCase = true) -> "Shecan"
    else -> value.ifBlank { "System DNS" }
}

private fun resolverColor(value: String): Color = when {
    value.contains("cloudflare", ignoreCase = true) -> Color(0xFFF28B2C)
    value.contains("google", ignoreCase = true) -> Color(0xFF4285F4)
    value.contains("quad9", ignoreCase = true) -> Color(0xFF6D4AFF)
    value.contains("adguard", ignoreCase = true) -> Color(0xFF68BC71)
    else -> RouteAccent
}

private fun rankColor(rank: Int): Color = when (rank) {
    1 -> Color(0xFF71D9FF)
    2 -> Color(0xFFD6E4EC)
    3 -> Color(0xFFD99861)
    else -> Color(0xFF7890A3)
}

@Composable
private fun ConfidenceSignalIndicator(
    confidence: Int,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val activeBars = when {
        confidence >= 70 -> 3
        confidence >= 40 -> 2
        confidence > 0 -> 1
        else -> 0
    }
    val barColor = confidenceColor(confidence)
    val barHeights = listOf(5.dp, 8.dp, 11.dp)
    Row(
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        horizontalArrangement = Arrangement.spacedBy(1.5.dp, Alignment.End),
        verticalAlignment = Alignment.Bottom,
    ) {
        barHeights.forEachIndexed { index, height ->
            if (index < activeBars) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(height)
                        .background(barColor, RoundedCornerShape(1.dp)),
                )
            }
        }
    }
}

@Composable
private fun localizedConfidenceLabel(confidence: Int): String = when {
    confidence >= 70 -> homeText("High", "بالا")
    confidence >= 40 -> homeText("Medium", "متوسط")
    confidence > 0 -> homeText("Low", "کم")
    else -> "—"
}

private fun confidenceColor(confidence: Int): Color = when {
    confidence >= 70 -> RouteGreen
    confidence >= 40 -> RouteAmber
    confidence > 0 -> RouteRed
    else -> UacColors.TextSecondary
}

private fun formatThroughput(kbps: Long): String = when {
    kbps <= 0L -> "—"
    kbps >= 1_000L -> String.format(Locale.US, "%.1f Mbps", kbps / 1_000.0)
    else -> "${kbps} Kbps"
}

private fun formatBytes(bytes: Int): String = when {
    bytes <= 0 -> "—"
    bytes >= 1_024 * 1_024 -> String.format(Locale.US, "%.1f MB", bytes / (1_024.0 * 1_024.0))
    bytes >= 1_024 -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
