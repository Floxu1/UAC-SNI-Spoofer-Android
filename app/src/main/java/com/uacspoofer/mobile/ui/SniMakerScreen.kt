package com.uacspoofer.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.profiles.SniCandidateStage
import com.uacspoofer.mobile.ui.theme.UacColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SniMakerScreen(
    onMenuClick: () -> Unit,
    controller: SniMakerController,
) {
    var importSheetVisible by remember { mutableStateOf(false) }
    var settingsSheetVisible by remember { mutableStateOf(false) }
    var clearConfirmationVisible by remember { mutableStateOf(false) }
    val resultsListState = rememberLazyListState()
    val visibleRows by remember(controller) { derivedStateOf { controller.visibleRows() } }
    val completed by remember(controller) {
        derivedStateOf { controller.healthyCount + controller.failedCount }
    }
    val progress by remember(controller) {
        derivedStateOf {
            if (controller.rows.isEmpty()) 0f
            else completed.toFloat() / controller.rows.size.toFloat()
        }
    }
    val accent = Color(0xFF35D6FF)

    LaunchedEffect(controller.healthyCount, controller.sortMode, controller.testing) {
        if (controller.healthyCount > 0 && controller.sortMode == MakerSortMode.HEALTHY_FIRST) {
            resultsListState.scrollToItem(0)
        }
    }

    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
    ToolPageBackground(accent = accent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues()),
        ) {
            WideSplitColumn(
                headerPadding = 14.dp,
                header = {
                    Spacer(Modifier.height(7.dp))
                    MakerTopBar(
                total = controller.rows.size,
                healthy = controller.healthyCount,
                testing = controller.testing,
                onMenuClick = onMenuClick,
                onImportClick = { importSheetVisible = true },
                onSettingsClick = { settingsSheetVisible = true },
                canClear = controller.rows.isNotEmpty() || controller.loading || controller.testing || controller.saving,
                onClearClick = { clearConfirmationVisible = true },
            )
                },
            ) {
            Spacer(Modifier.height(11.dp))

            MakerProgressStrip(
                total = controller.rows.size,
                completed = completed,
                progress = progress,
                testing = controller.testing,
                testingCount = controller.testingCount,
                loading = controller.loading,
                saving = controller.saving,
                healthyCount = controller.healthyCount,
                onTestClick = controller::toggleTests,
                onSaveClick = controller::saveHealthy,
            )

            Text(
                text = localizedMakerNotice(controller.notice),
                color = Color(0xFF8FA7BA),
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (LocalHomePersian.current) androidx.compose.ui.text.style.TextAlign.Right else androidx.compose.ui.text.style.TextAlign.Start,
                style = LocalTextStyle.current.copy(
                    textDirection = if (LocalHomePersian.current) TextDirection.Rtl else TextDirection.Content,
                ),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
            )

            MakerResultsTable(
                rows = visibleRows,
                sortMode = controller.sortMode,
                listState = resultsListState,
                allMarked = controller.rows.isNotEmpty() && controller.rows.all(MakerConfigRow::marked),
                modifier = Modifier.fillMaxWidth().weight(1f),
                onToggle = controller::toggleMarked,
                onToggleAll = controller::toggleAllMarked,
                onSortStatus = controller::cycleStatusSort,
            )
            Spacer(Modifier.height(7.dp))
            }
        }
    }

    if (importSheetVisible) {
        ImportSourceSheet(
            controller = controller,
            onDismiss = { importSheetVisible = false },
            onReceive = {
                controller.receiveConfigs()
                importSheetVisible = false
            },
        )
    }
    if (settingsSheetVisible) {
        TestSettingsSheet(
            controller = controller,
            onDismiss = { settingsSheetVisible = false },
        )
    }
    if (clearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { clearConfirmationVisible = false },
            containerColor = Color(0xFF101E2B),
            title = { Text(homeText("Clear results?", "نتیجه‌ها پاک بشن؟"), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (controller.loading || controller.testing || controller.saving) {
                        homeText(
                            "The active operation will stop and all current results and selections will be removed.",
                            "عملیات در حال اجرا متوقف می‌شه و همه نتیجه‌ها و انتخاب‌های فعلی پاک می‌شن.",
                        )
                    } else {
                        homeText("All current results and selections will be removed.", "همه نتیجه‌ها و انتخاب‌های فعلی پاک می‌شن.")
                    },
                    color = UacColors.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        clearConfirmationVisible = false
                        controller.clearResults()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB84459)),
                ) {
                    Text(homeText("CLEAR", "پاک‌کردن"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmationVisible = false }) { Text(homeText("CANCEL", "لغو"), fontSize = 12.5.sp) }
            },
        )
    }
    }
}

@Composable
private fun MakerTopBar(
    total: Int,
    healthy: Int,
    testing: Boolean,
    onMenuClick: () -> Unit,
    onImportClick: () -> Unit,
    onSettingsClick: () -> Unit,
    canClear: Boolean,
    onClearClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(0x99101C29), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                .clickable(onClick = onMenuClick)
                .openDrawerOnDpadLeft(onMenuClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Menu, homeText("Open navigation", "بازکردن منو"), tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                homeText("Config Maker", "ساخت کانفیگ"),
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    testing -> homeText("Live testing • $healthy healthy", "تست زنده • $healthy سالم")
                    total > 0 -> homeText("$total configurations • $healthy healthy", "$total کانفیگ • $healthy سالم")
                    else -> homeText("Subscription and clipboard profiles", "کانفیگ از لینک اشتراک یا کلیپ‌بورد")
                },
                color = UacColors.TextSecondary,
                fontSize = 9.8.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopActionButton(
                icon = Icons.Outlined.CloudDownload,
                description = homeText("Import configurations", "واردکردن کانفیگ‌ها"),
                accent = Color(0xFF35D6FF),
                onClick = onImportClick,
            )
            TopActionButton(
                icon = Icons.Outlined.Tune,
                description = homeText("Test settings", "تنظیمات تست"),
                accent = Color(0xFF9EB6CA),
                onClick = onSettingsClick,
            )
            TopActionButton(
                icon = Icons.Outlined.DeleteSweep,
                description = homeText("Clear current results", "پاک‌کردن نتیجه‌ها"),
                accent = Color(0xFFFF7187),
                enabled = canClear,
                onClick = onClearClick,
            )
        }
    }
}

@Composable
private fun TopActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(shape)
            .background(Color(0x99101C29), shape)
            .border(1.dp, accent.copy(alpha = 0.30f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) accent else UacColors.TextSecondary.copy(alpha = 0.28f),
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun MakerProgressStrip(
    total: Int,
    completed: Int,
    progress: Float,
    testing: Boolean,
    testingCount: Int,
    loading: Boolean,
    saving: Boolean,
    healthyCount: Int,
    onTestClick: () -> Unit,
    onSaveClick: () -> Unit,
) {
    Surface(
        color = Color(0xB30A1826),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, top = 9.dp, bottom = 9.dp, end = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        loading -> homeText("Receiving configurations…", "در حال دریافت کانفیگ‌ها…")
                        saving -> homeText("Saving healthy configurations…", "در حال ذخیره کانفیگ‌های سالم…")
                        testing -> homeText("$completed of $total tested • $testingCount active", "$completed از $total تست شد • $testingCount فعال")
                        total > 0 -> homeText("$completed of $total tested", "$completed از $total تست شد")
                        else -> homeText("Import profiles to begin", "برای شروع، کانفیگ وارد کن")
                    },
                    color = Color(0xFFC9D8E5),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (testing) Color(0xFF35D6FF) else UacColors.ConnectedGreen,
                    trackColor = Color.White.copy(alpha = 0.07f),
                )
            }
            Button(
                onClick = onTestClick,
                enabled = total > 0 && !loading && !saving,
                modifier = Modifier.height(38.dp).widthIn(min = 92.dp),
                shape = RoundedCornerShape(11.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (testing) Color(0xFF9F4052) else Color(0xFF1B91DA),
                    contentColor = Color.White,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.Speed, null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    if (testing) homeText("STOP", "توقف") else homeText("TEST", "تست"),
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            RemoteIconButton(
                onClick = onSaveClick,
                enabled = healthyCount > 0 && !testing && !loading && !saving,
                modifier = Modifier.size(38.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(21.dp),
                        color = UacColors.ConnectedGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Outlined.Save,
                        homeText("Save healthy configurations", "ذخیره کانفیگ‌های سالم"),
                        tint = if (healthyCount > 0 && !testing && !loading) UacColors.ConnectedGreen else UacColors.TextSecondary.copy(alpha = 0.35f),
                    )
                }
            }
        }
    }
}

@Composable
private fun MakerResultsTable(
    rows: List<MakerConfigRow>,
    sortMode: MakerSortMode,
    listState: LazyListState,
    allMarked: Boolean,
    modifier: Modifier,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onSortStatus: () -> Unit,
) {
    Column(modifier = modifier) {
        MakerHeader(
            allMarked = allMarked,
            sortMode = sortMode,
            onToggleAll = onToggleAll,
            onSortStatus = onSortStatus,
        )
        Spacer(Modifier.height(5.dp))
        if (rows.isEmpty()) {
            Box(
                Modifier.fillMaxSize().background(Color(0x66071522), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudDownload, null, tint = UacColors.TextSecondary.copy(alpha = 0.45f))
                    Spacer(Modifier.height(9.dp))
                    Text(homeText("No configurations", "هنوز کانفیگی اضافه نشده"), color = UacColors.TextSecondary, fontSize = 13.sp)
                    Text(homeText("Tap the download icon to import", "برای واردکردن کانفیگ، آیکون دانلود رو بزن"), color = UacColors.TextSecondary.copy(alpha = 0.65f), fontSize = 10.5.sp)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(rows, key = { it.profile.id }) { row ->
                    MakerResultCard(row = row, onToggle = { onToggle(row.profile.id) })
                }
                item { Spacer(Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun MakerHeader(
    allMarked: Boolean,
    sortMode: MakerSortMode,
    onToggleAll: () -> Unit,
    onSortStatus: () -> Unit,
) {
    val sortLabel = when (sortMode) {
        MakerSortMode.ORIGINAL -> homeText("Default order", "چیدمان پیش‌فرض")
        MakerSortMode.HEALTHY_FIRST -> homeText("Healthy first", "سالم‌ها اول")
        MakerSortMode.FAILED_FIRST -> homeText("Failed first", "ناموفق‌ها اول")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(35.dp)
            .background(Color(0x66071522), RoundedCornerShape(10.dp))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = allMarked,
            onCheckedChange = { onToggleAll() },
            modifier = Modifier.size(34.dp),
            colors = makerCheckboxColors(),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            homeText("Select all", "انتخاب همه"),
            color = Color(0xFFC9D8E5),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .clickable(onClick = onSortStatus)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                sortLabel,
                color = if (sortMode == MakerSortMode.ORIGINAL) Color(0xFF77CEE9) else Color(0xFF35D6FF),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                when (sortMode) {
                    MakerSortMode.ORIGINAL -> Icons.Outlined.UnfoldMore
                    MakerSortMode.HEALTHY_FIRST -> Icons.Outlined.ArrowUpward
                    MakerSortMode.FAILED_FIRST -> Icons.Outlined.ArrowDownward
                },
                homeText("Sort by status", "مرتب‌سازی وضعیت"),
                tint = if (sortMode == MakerSortMode.ORIGINAL) UacColors.TextSecondary else Color(0xFF35D6FF),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun MakerResultCard(row: MakerConfigRow, onToggle: () -> Unit) {
    val statusColor = when (row.status) {
        MakerTestStatus.QUEUED -> Color(0xFF8295A7)
        MakerTestStatus.TESTING -> Color(0xFF35D6FF)
        MakerTestStatus.HEALTHY -> UacColors.ConnectedGreen
        MakerTestStatus.FAILED -> Color(0xFFFF6F88)
    }
    val statusLabel = when (row.status) {
        MakerTestStatus.QUEUED -> homeText("Queued", "در صف")
        MakerTestStatus.TESTING -> homeText("Testing", "در حال تست")
        MakerTestStatus.HEALTHY -> homeText("Healthy", "سالم")
        MakerTestStatus.FAILED -> homeText("Failed", "ناموفق")
    }
    val candidateColor = when (row.candidateStage) {
        SniCandidateStage.STARTING, SniCandidateStage.PROBING -> Color(0xFF35D6FF)
        SniCandidateStage.REJECTED -> Color(0xFFFFB454)
        SniCandidateStage.FAILED, SniCandidateStage.EXHAUSTED -> Color(0xFFFF6F88)
        SniCandidateStage.PASSED -> UacColors.ConnectedGreen
        null -> UacColors.TextSecondary
    }
    val candidateTitle = when (row.candidateStage) {
        SniCandidateStage.PASSED -> homeText("Selected route (winner)", "مسیر انتخاب‌شده (برنده)")
        SniCandidateStage.EXHAUSTED -> homeText("Best available result", "بهترین نتیجه موجود")
        SniCandidateStage.FAILED, SniCandidateStage.REJECTED -> homeText("Last tested candidate", "آخرین گزینه تست‌شده")
        else -> homeText("Current candidate", "گزینه فعلی")
    }
    val shape = RoundedCornerShape(14.dp)
    var detailsExpanded by rememberSaveable(row.profile.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xD9071827), shape)
            .border(
                width = if (row.marked) 1.1.dp else 0.7.dp,
                color = if (row.marked) statusColor.copy(alpha = 0.78f) else Color(0xFF1A405A),
                shape = shape,
            )
            .animateContentSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { detailsExpanded = !detailsExpanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.marked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.size(34.dp),
                colors = makerCheckboxColors(),
            )
            Spacer(Modifier.width(7.dp))
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                if (row.country.isKnown) {
                    CountryFlagIcon(row.country, size = 31.dp)
                } else {
                    Text("—", color = UacColors.TextSecondary, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    row.profile.name,
                    color = Color(0xFFE4EEF5),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    row.displayUri,
                    color = Color(0xFF8EA7B9),
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(7.dp))
            Surface(color = statusColor.copy(alpha = 0.105f), shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(statusLabel, color = statusColor, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
            Spacer(Modifier.width(7.dp))
            Icon(
                if (detailsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                homeText("Candidate details", "جزئیات گزینه"),
                tint = UacColors.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        if (row.candidateId.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(candidateColor.copy(alpha = 0.075f), RoundedCornerShape(11.dp))
                    .border(0.7.dp, candidateColor.copy(alpha = 0.24f), RoundedCornerShape(11.dp))
                    .clickable { detailsExpanded = !detailsExpanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(35.dp)
                        .background(candidateColor.copy(alpha = 0.09f), CircleShape)
                        .border(0.8.dp, candidateColor.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (row.candidateStage == SniCandidateStage.PASSED) {
                        Icon(Icons.Outlined.EmojiEvents, null, tint = candidateColor, modifier = Modifier.size(18.dp))
                    } else if (row.status == MakerTestStatus.TESTING) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), color = candidateColor, strokeWidth = 1.8.dp)
                    } else {
                        Box(Modifier.size(8.dp).background(candidateColor, CircleShape))
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(candidateTitle, color = candidateColor, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        row.candidateId,
                        color = Color(0xFFE2EDF4),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        row.candidateLabel,
                        color = UacColors.TextSecondary,
                        fontSize = 9.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (detailsExpanded) {
                Column(Modifier.padding(start = 44.dp, top = 7.dp, end = 4.dp, bottom = 2.dp)) {
                    Text(
                        homeText(
                            "Candidate ${row.candidateIndex} of ${row.candidateCount}",
                            "گزینه ${row.candidateIndex} از ${row.candidateCount}",
                        ),
                        color = candidateColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(homeText("Result", "نتیجه"), color = UacColors.TextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        row.candidateDetail.ifBlank { homeText("Waiting for probe result…", "در انتظار نتیجه تست…") },
                        color = candidateColor.copy(alpha = 0.92f),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(homeText("Route", "مسیر"), color = UacColors.TextSecondary, fontSize = 9.5.sp, fontWeight = FontWeight.Medium)
                    Text(
                        row.candidateRoute.ifBlank { homeText("Preparing route…", "در حال آماده‌سازی مسیر…") },
                        color = Color(0xFFAFC2D0),
                        fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun MakerResultRowFull(row: MakerConfigRow, onToggle: () -> Unit) {
    val statusColor = when (row.status) {
        MakerTestStatus.QUEUED -> Color(0xFF71889C)
        MakerTestStatus.TESTING -> Color(0xFF35D6FF)
        MakerTestStatus.HEALTHY -> UacColors.ConnectedGreen
        MakerTestStatus.FAILED -> Color(0xFFFF6F88)
    }
    val candidateColor = when (row.candidateStage) {
        SniCandidateStage.STARTING, SniCandidateStage.PROBING -> Color(0xFF35D6FF)
        SniCandidateStage.REJECTED -> Color(0xFFFFB454)
        SniCandidateStage.FAILED, SniCandidateStage.EXHAUSTED -> Color(0xFFFF6F88)
        SniCandidateStage.PASSED -> UacColors.ConnectedGreen
        null -> UacColors.TextSecondary
    }
    val stageLabel = when (row.candidateStage) {
        SniCandidateStage.STARTING -> "Starting"
        SniCandidateStage.PROBING -> "Probing"
        SniCandidateStage.REJECTED -> "Rejected"
        SniCandidateStage.FAILED -> "Candidate failed"
        SniCandidateStage.PASSED -> "Winner"
        SniCandidateStage.EXHAUSTED -> "All candidates failed - best result"
        null -> ""
    }
    var detailsExpanded by rememberSaveable(row.profile.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .padding(end = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = row.marked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.width(37.dp),
                colors = makerCheckboxColors(),
            )
            Row(Modifier.width(62.dp), verticalAlignment = Alignment.CenterVertically) {
                if (row.country.isKnown) {
                    CountryFlagIcon(row.country, size = 20.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = if (row.status == MakerTestStatus.TESTING) "..." else row.country.countryCode ?: "-",
                    color = if (row.country.isKnown) Color(0xFFC9D8E5) else UacColors.TextSecondary,
                    fontSize = 9.5.sp,
                    fontWeight = if (row.country.isKnown) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(Modifier.width(82.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(
                    text = when (row.status) {
                        MakerTestStatus.QUEUED -> "Queued"
                        MakerTestStatus.TESTING -> "Testing"
                        MakerTestStatus.HEALTHY -> "Healthy"
                        MakerTestStatus.FAILED -> "Failed"
                    },
                    color = if (row.status == MakerTestStatus.QUEUED) UacColors.TextSecondary else statusColor,
                    fontSize = 9.sp,
                    maxLines = 1,
                )
            }
            Text(
                text = row.latencyMs?.let { "$it ms" } ?: "-",
                color = if (row.latencyMs != null) UacColors.ConnectedGreen else UacColors.TextSecondary,
                fontSize = 9.sp,
                modifier = Modifier.width(52.dp),
                maxLines = 1,
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.country.isKnown) {
                        CountryFlagIcon(row.country, size = 16.dp)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = row.profile.name,
                        color = Color(0xFFD6E4EF),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = row.displayUri,
                    color = Color(0xFF7995AA),
                    fontSize = 8.3.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (row.candidateId.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 37.dp, end = 8.dp, bottom = 8.dp)
                    .background(candidateColor.copy(alpha = 0.075f), RoundedCornerShape(9.dp))
                    .border(0.5.dp, candidateColor.copy(alpha = 0.24f), RoundedCornerShape(9.dp))
                    .animateContentSize()
                    .clickable { detailsExpanded = !detailsExpanded }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.status == MakerTestStatus.TESTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            color = candidateColor,
                            strokeWidth = 1.4.dp,
                        )
                    } else {
                        Box(Modifier.size(7.dp).background(candidateColor, CircleShape))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Candidate ${row.candidateIndex} of ${row.candidateCount}  •  $stageLabel",
                        color = candidateColor,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (detailsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                        contentDescription = if (detailsExpanded) "Hide candidate details" else "Show candidate details",
                        tint = candidateColor,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = "${row.candidateId} - ${row.candidateLabel}",
                    color = Color(0xFFD8E7F2),
                    fontSize = 8.7.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detailsExpanded) {
                    Spacer(Modifier.height(7.dp))
                    HorizontalDivider(color = candidateColor.copy(alpha = 0.18f), thickness = 0.5.dp)
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = "Result",
                        color = UacColors.TextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = row.candidateDetail.ifBlank { "Waiting for probe result..." },
                        color = candidateColor.copy(alpha = 0.92f),
                        fontSize = 8.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = "Route",
                        color = UacColors.TextSecondary,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = row.candidateRoute.ifBlank { "Preparing route..." },
                        color = Color(0xFF9DB7C9),
                        fontSize = 8.2.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.055f), thickness = 0.5.dp)
}

@Composable
private fun MakerResultRow(row: MakerConfigRow, onToggle: () -> Unit) {
    val statusColor = when (row.status) {
        MakerTestStatus.QUEUED -> Color(0xFF71889C)
        MakerTestStatus.TESTING -> Color(0xFF35D6FF)
        MakerTestStatus.HEALTHY -> UacColors.ConnectedGreen
        MakerTestStatus.FAILED -> Color(0xFFFF6F88)
    }
    val candidateColor = when (row.candidateStage) {
        SniCandidateStage.STARTING, SniCandidateStage.PROBING -> Color(0xFF35D6FF)
        SniCandidateStage.REJECTED -> Color(0xFFFFB454)
        SniCandidateStage.FAILED, SniCandidateStage.EXHAUSTED -> Color(0xFFFF6F88)
        SniCandidateStage.PASSED -> UacColors.ConnectedGreen
        null -> UacColors.TextSecondary
    }
    val candidateStageLabel = when (row.candidateStage) {
        SniCandidateStage.STARTING -> "Starting"
        SniCandidateStage.PROBING -> "Probing"
        SniCandidateStage.REJECTED -> "Rejected"
        SniCandidateStage.FAILED -> "Candidate failed"
        SniCandidateStage.PASSED -> "Winner"
        SniCandidateStage.EXHAUSTED -> "All candidates failed; best result"
        null -> ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (row.candidateId.isBlank()) 55.dp else 86.dp)
            .clickable(onClick = onToggle)
            .padding(end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.marked,
            onCheckedChange = { onToggle() },
            modifier = Modifier.width(37.dp),
            colors = makerCheckboxColors(),
        )
        Row(Modifier.width(62.dp), verticalAlignment = Alignment.CenterVertically) {
            if (row.country.isKnown) {
                CountryFlagIcon(row.country, size = 20.dp)
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = when (row.status) {
                    MakerTestStatus.TESTING -> "…"
                    else -> row.country.countryCode ?: "—"
                },
                color = if (row.country.isKnown) Color(0xFFC9D8E5) else UacColors.TextSecondary,
                fontSize = 9.5.sp,
                fontWeight = if (row.country.isKnown) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        Row(Modifier.width(82.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(statusColor, CircleShape))
            Spacer(Modifier.width(5.dp))
            Text(
                when (row.status) {
        MakerTestStatus.QUEUED -> "Queued"
        MakerTestStatus.TESTING -> "Testing"
        MakerTestStatus.HEALTHY -> "Healthy"
        MakerTestStatus.FAILED -> "Failed"
                },
                color = if (row.status == MakerTestStatus.QUEUED) UacColors.TextSecondary else statusColor,
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
        Text(
            row.latencyMs?.let { "$it ms" } ?: "–",
            color = if (row.latencyMs != null) UacColors.ConnectedGreen else UacColors.TextSecondary,
            fontSize = 9.sp,
            modifier = Modifier.width(52.dp),
            maxLines = 1,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.country.isKnown) {
                    CountryFlagIcon(row.country, size = 16.dp)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    row.profile.name,
                    color = Color(0xFFD6E4EF),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                row.displayUri,
                color = Color(0xFF7995AA),
                fontSize = 8.3.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.candidateId.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (row.status == MakerTestStatus.TESTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(9.dp),
                            color = candidateColor,
                            strokeWidth = 1.2.dp,
                        )
                    } else {
                        Box(Modifier.size(6.dp).background(candidateColor, CircleShape))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "${row.candidateIndex}/${row.candidateCount} | ${row.candidateId} | ${row.candidateLabel}",
                        color = candidateColor,
                        fontSize = 8.2.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "$candidateStageLabel: ${row.candidateDetail} | ${row.candidateRoute}",
                    color = candidateColor.copy(alpha = 0.82f),
                    fontSize = 7.6.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.055f), thickness = 0.5.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSourceSheet(
    controller: SniMakerController,
    onDismiss: () -> Unit,
    onReceive: () -> Unit,
) {
    val context = LocalContext.current
    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF101E2B),
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp).size(width = 42.dp, height = 4.dp)
                    .background(Color(0xFF52697B), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(homeText("Import configurations", "واردکردن کانفیگ‌ها"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(
                homeText(
                    "Choose one source. Receive reads only the selected source.",
                    "یکی از روش‌ها رو انتخاب کن؛ فقط اطلاعات همان بخش دریافت می‌شه.",
                ),
                color = UacColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ImportSourceChoice(
                    icon = Icons.Outlined.CloudDownload,
                    label = homeText("SUBSCRIPTION URL", "لینک اشتراک"),
                    selected = controller.importSource == MakerImportSource.SUBSCRIPTION,
                    modifier = Modifier.weight(1f),
                    onClick = { controller.selectImportSource(MakerImportSource.SUBSCRIPTION) },
                )
                ImportSourceChoice(
                    icon = Icons.Outlined.ContentPaste,
                    label = homeText("CLIPBOARD", "کلیپ‌بورد"),
                    selected = controller.importSource == MakerImportSource.CLIPBOARD,
                    modifier = Modifier.weight(1f),
                    onClick = { controller.selectImportSource(MakerImportSource.CLIPBOARD) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = controller.subscriptionUrl,
                    onValueChange = controller::updateSubscriptionUrl,
                    modifier = Modifier.weight(1f),
                    label = { Text(homeText("Subscription URL", "لینک اشتراک"), fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = toolTextFieldColors(Color(0xFF35D6FF)),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, textDirection = TextDirection.Ltr),
                )
                RemoteIconButton(
                    onClick = controller::resetSubscriptionUrl,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0x99101C29), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x5535D6FF), RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                        homeText("Reset subscription URL", "بازنشانی لینک اشتراک"),
                        tint = Color(0xFF35D6FF),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            OutlinedTextField(
                value = controller.pastedConfigs,
                onValueChange = controller::updatePastedConfigs,
                modifier = Modifier.fillMaxWidth().height(132.dp),
                label = { Text(homeText("Clipboard configurations / Base64", "کانفیگ‌های کلیپ‌بورد / Base64"), fontSize = 12.sp) },
                maxLines = 6,
                colors = toolTextFieldColors(Color(0xFF35D6FF)),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
            )
            OutlinedButton(
                onClick = {
                    val incoming = context.getSystemService(ClipboardManager::class.java)
                        ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    controller.loadClipboard(incoming)
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Outlined.ContentPaste, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(homeText("PASTE FROM CLIPBOARD", "چسباندن از کلیپ‌بورد"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onReceive,
                enabled = controller.hasSelectedInput && !controller.testing && !controller.loading && !controller.saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2196E3),
                    contentColor = Color.White,
                ),
            ) {
                Icon(
                    if (controller.importSource == MakerImportSource.SUBSCRIPTION) {
                        Icons.Outlined.CloudDownload
                    } else {
                        Icons.Outlined.ContentPaste
                    },
                    null,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (controller.importSource == MakerImportSource.SUBSCRIPTION) {
                        homeText("RECEIVE FROM URL", "دریافت از لینک")
                    } else {
                        homeText("RECEIVE FROM CLIPBOARD", "دریافت از کلیپ‌بورد")
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    }
}

@Composable
private fun ImportSourceChoice(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = Color(0xFF35D6FF)
    val shape = RoundedCornerShape(12.dp)
    Surface(
        color = if (selected) accent.copy(alpha = 0.14f) else Color(0x660A1826),
        shape = shape,
        modifier = modifier
            .height(44.dp)
            .border(1.dp, if (selected) accent else Color.White.copy(alpha = 0.10f), shape),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                null,
                tint = if (selected) accent else UacColors.TextSecondary,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                color = if (selected) Color.White else UacColors.TextSecondary,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestSettingsSheet(
    controller: SniMakerController,
    onDismiss: () -> Unit,
) {
    val baseTextStyle = LocalTextStyle.current
    val localizedTextStyle = homeLocalizedFont()?.let { baseTextStyle.copy(fontFamily = it) } ?: baseTextStyle
    CompositionLocalProvider(LocalTextStyle provides localizedTextStyle) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF101E2B),
        contentColor = Color.White,
        dragHandle = {
            Box(
                Modifier.padding(vertical = 10.dp).size(width = 42.dp, height = 4.dp)
                    .background(Color(0xFF52697B), RoundedCornerShape(2.dp)),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null, tint = Color(0xFF35D6FF))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(homeText("Test settings", "تنظیمات تست"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        homeText("Captured when the next test run starts", "این تنظیمات از شروع تست بعدی اعمال می‌شن"),
                        color = UacColors.TextSecondary,
                        fontSize = 12.5.sp,
                    )
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xB30A1826),
                shape = RoundedCornerShape(14.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(homeText("Test method", "روش تست"), color = Color(0xFF77CEE9), fontSize = 11.sp)
                    Text(
                        homeText("Deep Adaptive Test", "تست تطبیقی عمیق"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        homeText(
                            "Hard test across Edge, DNS and Fragment candidates",
                            "همه گزینه‌های مسیر با تست سخت بررسی می‌شن",
                        ),
                        color = UacColors.TextSecondary,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            SettingStepper(
                    title = homeText("Concurrent tests", "تست‌های هم‌زمان"),
                    subtitle = homeText("More threads are faster but use more memory", "تعداد بیشتر سریع‌تره، ولی حافظه بیشتری مصرف می‌کنه"),
                    valueText = controller.workerCount.toString(),
                    canDecrease = controller.workerCount > SniMakerController.MIN_WORKERS,
                    canIncrease = controller.workerCount < SniMakerController.MAX_WORKERS,
                    onDecrease = { controller.updateWorkerCount(controller.workerCount - 1) },
                    onIncrease = { controller.updateWorkerCount(controller.workerCount + 1) },
                )
                SettingStepper(
                    title = homeText("Timeout per configuration", "زمان انتظار هر کانفیگ"),
                    subtitle = homeText("Total time allowed for all route attempts", "حداکثر زمان برای امتحان‌کردن همه مسیرها"),
                    valueText = "${controller.timeoutMs / 1000}s",
                    canDecrease = controller.timeoutMs > SniMakerController.MIN_TIMEOUT_MS,
                    canIncrease = controller.timeoutMs < SniMakerController.MAX_TIMEOUT_MS,
                    onDecrease = { controller.updateTimeoutMs(controller.timeoutMs - SniMakerController.TIMEOUT_STEP_MS) },
                    onIncrease = { controller.updateTimeoutMs(controller.timeoutMs + SniMakerController.TIMEOUT_STEP_MS) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TextButton(
                    onClick = controller::resetTestSettings,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(homeText("RESET", "بازنشانی"), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196E3)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(homeText("DONE", "انجام شد"), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    }
}

@Composable
private fun SettingStepper(
    title: String,
    subtitle: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Surface(color = Color(0xB30A1826), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = UacColors.TextSecondary, fontSize = 11.sp, maxLines = 2, lineHeight = 15.sp)
            }
            RemoteIconButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Remove, homeText("Decrease", "کم‌کردن"), tint = if (canDecrease) Color(0xFF35D6FF) else UacColors.TextSecondary.copy(alpha = 0.35f))
            }
            Text(
                valueText,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            RemoteIconButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Add, homeText("Increase", "بیشترکردن"), tint = if (canIncrease) Color(0xFF35D6FF) else UacColors.TextSecondary.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun localizedMakerNotice(message: String): String {
    if (!LocalHomePersian.current) return message
    val translated = when {
        message == "Add a subscription URL or paste configurations" -> "یک لینک اشتراک اضافه کن یا کانفیگ‌ها رو اینجا بچسبون"
        message == "Default subscription URL restored" -> "لینک اشتراک پیش‌فرض برگردانده شد"
        message == "Clipboard loaded" -> "محتوای کلیپ‌بورد دریافت شد"
        message.startsWith("Large clipboard loaded safely • ") -> {
            val count = message.substringAfter("• ").substringBefore(' ')
            "محتوای بزرگ کلیپ‌بورد با خیال راحت دریافت شد • $count نویسه"
        }
        message == "Receiving subscription configurations…" -> "در حال دریافت کانفیگ‌های اشتراک…"
        message == "Decoding clipboard configurations…" -> "در حال پردازش کانفیگ‌های کلیپ‌بورد…"
        message.startsWith("Receive failed: ") -> "دریافت انجام نشد: ${message.substringAfter(": ")}"
        message.startsWith("Preparing adaptive test for ") -> {
            val count = message.substringAfter("for ").substringBefore(' ')
            "در حال آماده‌سازی تست تطبیقی برای $count کانفیگ…"
        }
        message.startsWith("Deep Adaptive Test | ") -> {
            val parts = message.split(" | ")
            val count = parts.getOrNull(1)?.substringBefore(' ').orEmpty()
            val workers = parts.getOrNull(2)?.substringBefore(' ').orEmpty()
            val timeout = parts.getOrNull(3).orEmpty()
            "تست تطبیقی عمیق • $count کانفیگ • $workers تست هم‌زمان • زمان انتظار $timeout"
        }
        message.startsWith("Test complete • ") -> {
            val parts = message.split(" • ")
            val healthy = parts.getOrNull(1)?.substringBefore(' ').orEmpty()
            val failed = parts.getOrNull(2)?.substringBefore(' ').orEmpty()
            "تست تمام شد • $healthy سالم • $failed ناموفق"
        }
        message.startsWith("Tests stopped • ") -> {
            val healthy = message.substringAfter("• ").substringBefore(' ')
            "تست متوقف شد • $healthy نتیجه سالم نگه داشته شد"
        }
        message.startsWith("Adaptive test setup failed: ") -> "راه‌اندازی تست تطبیقی انجام نشد: ${message.substringAfter(": ")}"
        message == "Results cleared" -> "نتیجه‌ها پاک شدند"
        message == "No healthy configuration selected" -> "هیچ کانفیگ سالمی انتخاب نشده"
        message.startsWith("Saving ") && message.endsWith(" healthy configurations…") -> {
            val count = message.substringAfter("Saving ").substringBefore(' ')
            "در حال ذخیره $count کانفیگ سالم…"
        }
        message.endsWith(" healthy configurations saved to Configs") -> {
            val count = message.substringBefore(' ')
            "$count کانفیگ سالم در بخش کانفیگ‌ها ذخیره شد"
        }
        message.startsWith("Save failed: ") -> "ذخیره انجام نشد: ${message.substringAfter(": ")}"
        message.contains(" new configurations added") -> message
            .replace(Regex("(\\d+) new configurations added"), "$1 کانفیگ جدید اضافه شد")
            .replace(Regex("(\\d+) total"), "$1 کانفیگ در فهرست")
            .replace(Regex("(\\d+) duplicates kept once"), "$1 مورد تکراری فقط یک‌بار نگه داشته شد")
            .replace(Regex("(\\d+) skipped by list limit"), "$1 مورد به‌دلیل محدودیت فهرست رد شد")
            .replace(Regex("(\\d+) Base64 decoded"), "$1 داده Base64 باز شد")
            .replace(Regex("(\\d+) invalid skipped"), "$1 مورد خراب رد شد")
            .replace("input limit reached", "ورودی به سقف مجاز رسید")
        else -> message
    }
    return homeText(message, translated)
}

@Composable
private fun makerCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = Color(0xFF2196E3),
    uncheckedColor = Color(0xFF7690A8),
    checkmarkColor = Color.White,
)

private fun makerLtr(value: String): String = "\u2066$value\u2069"
