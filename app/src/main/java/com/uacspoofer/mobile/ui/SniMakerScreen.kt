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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    ToolPageBackground(accent = accent) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(horizontal = 14.dp),
        ) {
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
                text = controller.notice,
                color = Color(0xFF8FA7BA),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            title = { Text("Clear results?", color = Color.White, fontWeight = FontWeight.SemiBold) },
            text = {
                Text(
                    if (controller.loading || controller.testing || controller.saving) {
                    "The active operation will stop and all current results and selections will be removed."
                    } else {
                    "All current results and selections will be removed."
                    },
                    color = UacColors.TextSecondary,
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
                    Text("CLEAR", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmationVisible = false }) { Text("CANCEL") }
            },
        )
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier
                .size(46.dp)
                .background(Color(0x99101C29), CircleShape)
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
        ) {
            Icon(Icons.Outlined.Menu, "Open navigation", tint = Color.White)
        }
        Column(Modifier.weight(1f)) {
            Text(
            "SNI Config Maker",
                color = Color.White,
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            Text(
                when {
                testing -> "Live testing • $healthy healthy"
                total > 0 -> "$total configurations • $healthy healthy"
                else -> "Subscription and clipboard profiles"
                },
                color = UacColors.TextSecondary,
                fontSize = 10.5.sp,
                maxLines = 1,
            )
        }
        TopActionButton(
            icon = Icons.Outlined.CloudDownload,
            description = "Import configurations",
            accent = Color(0xFF35D6FF),
            onClick = onImportClick,
        )
        TopActionButton(
            icon = Icons.Outlined.Tune,
            description = "Test settings",
            accent = Color(0xFF9EB6CA),
            onClick = onSettingsClick,
        )
        TopActionButton(
            icon = Icons.Outlined.DeleteSweep,
            description = "Clear current results",
            accent = Color(0xFFFF7187),
            enabled = canClear,
            onClick = onClearClick,
        )
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
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(42.dp)
            .background(Color(0x99101C29), RoundedCornerShape(13.dp))
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(13.dp)),
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) accent else UacColors.TextSecondary.copy(alpha = 0.28f),
            modifier = Modifier.size(21.dp),
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
            loading -> "Receiving configurations…"
            saving -> "Saving healthy configurations…"
            testing -> "$completed of $total tested • $testingCount active"
            total > 0 -> "$completed of $total tested"
            else -> "Import profiles to begin"
                    },
                    color = Color(0xFFC9D8E5),
                    fontSize = 10.5.sp,
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
                Text(if (testing) "STOP" else "TEST", fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            IconButton(
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
            "Save healthy configurations",
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
    Column(modifier = modifier.background(Color(0x66071522), RoundedCornerShape(12.dp))) {
        MakerHeader(
            allMarked = allMarked,
            sortMode = sortMode,
            onToggleAll = onToggleAll,
            onSortStatus = onSortStatus,
        )
        HorizontalDivider(color = Color(0xFF1C354A), thickness = 1.dp)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.CloudDownload, null, tint = UacColors.TextSecondary.copy(alpha = 0.45f))
                    Spacer(Modifier.height(9.dp))
                    Text("No configurations", color = UacColors.TextSecondary, fontSize = 12.sp)
                    Text("Tap the download icon to import", color = UacColors.TextSecondary.copy(alpha = 0.65f), fontSize = 10.sp)
                }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(rows, key = { it.profile.id }) { row ->
                    MakerResultRowFull(row = row, onToggle = { onToggle(row.profile.id) })
                }
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
    Row(
        modifier = Modifier.fillMaxWidth().height(39.dp).padding(end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = allMarked,
            onCheckedChange = { onToggleAll() },
            modifier = Modifier.width(37.dp),
            colors = makerCheckboxColors(),
        )
        Text("Country", color = Color(0xFF77CEE9), fontSize = 9.sp, modifier = Modifier.width(62.dp))
        Row(
            modifier = Modifier
                .width(82.dp)
                .fillMaxHeight()
                .clickable(onClick = onSortStatus),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
            "Status",
                color = if (sortMode == MakerSortMode.ORIGINAL) Color(0xFF77CEE9) else Color(0xFF35D6FF),
                fontSize = 9.sp,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                when (sortMode) {
                    MakerSortMode.ORIGINAL -> Icons.Outlined.UnfoldMore
                    MakerSortMode.HEALTHY_FIRST -> Icons.Outlined.ArrowUpward
                    MakerSortMode.FAILED_FIRST -> Icons.Outlined.ArrowDownward
                },
            "Sort by status",
                tint = if (sortMode == MakerSortMode.ORIGINAL) UacColors.TextSecondary else Color(0xFF35D6FF),
                modifier = Modifier.size(14.dp),
            )
        }
        Text("Ping", color = Color(0xFF77CEE9), fontSize = 9.sp, modifier = Modifier.width(52.dp))
        Text("Configuration", color = Color(0xFF77CEE9), fontSize = 9.sp, modifier = Modifier.weight(1f))
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
            Text("Import configurations", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Choose one source. Receive reads only the selected source.",
                color = UacColors.TextSecondary,
                fontSize = 11.sp,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                ImportSourceChoice(
                    icon = Icons.Outlined.CloudDownload,
                    label = "SUBSCRIPTION URL",
                    selected = controller.importSource == MakerImportSource.SUBSCRIPTION,
                    modifier = Modifier.weight(1f),
                    onClick = { controller.selectImportSource(MakerImportSource.SUBSCRIPTION) },
                )
                ImportSourceChoice(
                    icon = Icons.Outlined.ContentPaste,
                    label = "CLIPBOARD",
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
                    label = { Text("Subscription URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = toolTextFieldColors(Color(0xFF35D6FF)),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp),
                )
                IconButton(
                    onClick = controller::resetSubscriptionUrl,
                    modifier = Modifier
                        .size(48.dp)
                        .background(Color(0x99101C29), RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0x5535D6FF), RoundedCornerShape(12.dp)),
                ) {
                    Icon(
                        Icons.Outlined.RestartAlt,
                    "Reset subscription URL",
                        tint = Color(0xFF35D6FF),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            OutlinedTextField(
                value = controller.pastedConfigs,
                onValueChange = controller::updatePastedConfigs,
                modifier = Modifier.fillMaxWidth().height(132.dp),
                label = { Text("Clipboard configurations / Base64") },
                maxLines = 6,
                colors = toolTextFieldColors(Color(0xFF35D6FF)),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 9.5.sp, fontFamily = FontFamily.Monospace),
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
                Text("PASTE FROM CLIPBOARD", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
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
                    "RECEIVE FROM URL"
                    } else {
                    "RECEIVE FROM CLIPBOARD"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
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
                fontSize = 9.5.sp,
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
                    Text("Test settings", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Captured when the next test run starts", color = UacColors.TextSecondary, fontSize = 10.5.sp)
                }
            }
            TestModeDropdown(
                selected = controller.testMode,
                onSelected = controller::updateTestMode,
            )
            if (controller.testMode == MakerTestMode.DEEP_ADAPTIVE) {
                SettingStepper(
                    title = "Concurrent tests",
                    subtitle = "More threads are faster but use more memory",
                    valueText = controller.workerCount.toString(),
                    canDecrease = controller.workerCount > SniMakerController.MIN_WORKERS,
                    canIncrease = controller.workerCount < SniMakerController.MAX_WORKERS,
                    onDecrease = { controller.updateWorkerCount(controller.workerCount - 1) },
                    onIncrease = { controller.updateWorkerCount(controller.workerCount + 1) },
                )
                SettingStepper(
                    title = "Timeout per configuration",
                    subtitle = "Total time allowed for all route attempts",
                    valueText = "${controller.timeoutMs / 1000}s",
                    canDecrease = controller.timeoutMs > SniMakerController.MIN_TIMEOUT_MS,
                    canIncrease = controller.timeoutMs < SniMakerController.MAX_TIMEOUT_MS,
                    onDecrease = { controller.updateTimeoutMs(controller.timeoutMs - SniMakerController.TIMEOUT_STEP_MS) },
                    onIncrease = { controller.updateTimeoutMs(controller.timeoutMs + SniMakerController.TIMEOUT_STEP_MS) },
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                TextButton(
                    onClick = controller::resetTestSettings,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(Icons.Outlined.RestartAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("RESET")
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(44.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196E3)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("DONE", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TestModeDropdown(
    selected: MakerTestMode,
    onSelected: (MakerTestMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val title = when (selected) {
        MakerTestMode.COMPATIBILITY -> "Compatibility Scan"
        MakerTestMode.DEEP_ADAPTIVE -> "Deep Adaptive Test"
    }
    val subtitle = when (selected) {
        MakerTestMode.COMPATIBILITY -> "Same reliable ping method used in Configs + country flag"
        MakerTestMode.DEEP_ADAPTIVE -> "Hard test across Edge, DNS and Fragment candidates"
    }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .border(1.dp, Color(0x5535D6FF), RoundedCornerShape(14.dp)),
            color = Color(0xB30A1826),
            shape = RoundedCornerShape(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Test method", color = Color(0xFF77CEE9), fontSize = 9.5.sp)
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = UacColors.TextSecondary, fontSize = 9.2.sp)
                }
                Icon(Icons.Outlined.KeyboardArrowDown, "Select test method", tint = Color(0xFF35D6FF))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .widthIn(min = 300.dp)
                .background(Color(0xFF132433)),
        ) {
            MakerTestMode.entries.forEach { mode ->
                val modeTitle = when (mode) {
                    MakerTestMode.COMPATIBILITY -> "Compatibility Scan (Default)"
                    MakerTestMode.DEEP_ADAPTIVE -> "Deep Adaptive Test"
                }
                val modeSubtitle = when (mode) {
                    MakerTestMode.COMPATIBILITY -> "Configs ping method + automatic country flag"
                    MakerTestMode.DEEP_ADAPTIVE -> "Hard Edge, DNS and Fragment candidate test"
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                modeTitle,
                                color = if (mode == selected) Color(0xFF35D6FF) else Color.White,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(modeSubtitle, color = UacColors.TextSecondary, fontSize = 9.sp)
                        }
                    },
                    onClick = {
                        onSelected(mode)
                        expanded = false
                    },
                )
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
                Text(title, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = UacColors.TextSecondary, fontSize = 9.5.sp, maxLines = 1)
            }
            IconButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Remove, "Decrease", tint = if (canDecrease) Color(0xFF35D6FF) else UacColors.TextSecondary.copy(alpha = 0.35f))
            }
            Text(
                valueText,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.width(48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Outlined.Add, "Increase", tint = if (canIncrease) Color(0xFF35D6FF) else UacColors.TextSecondary.copy(alpha = 0.35f))
            }
        }
    }
}

@Composable
private fun makerCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = Color(0xFF2196E3),
    uncheckedColor = Color(0xFF7690A8),
    checkmarkColor = Color.White,
)
