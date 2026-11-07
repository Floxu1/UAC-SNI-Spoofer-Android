package com.uacspoofer.mobile.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.uacspoofer.mobile.logging.AppLogRepository
import com.uacspoofer.mobile.logging.LogExport
import com.uacspoofer.mobile.logging.LogSource
import com.uacspoofer.mobile.ui.theme.UacColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TelegramBlue = Color(0xFF2AABEE)

/** Small sheet on the log screen: save the log as .txt or send it to the developer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LogExportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val snapshot = remember { LogExport.snapshot(context) }
    val accent = UacColors.DisconnectedBlue

    val savedMsg = homeText("Log file saved", "فایل لاگ ذخیره شد")
    val saveFailedMsg = homeText("Couldn't save the file", "ذخیره نشد، دوباره امتحان کن")
    val tgHint = homeText(
        "Developer id copied. Search it in Telegram and send the file.",
        "آیدی سازنده کپی شد. توی تلگرام جستجوش کن و فایل رو براش بفرست",
    )
    val tgLinkHint = homeText(
        "Telegram not found. Developer id copied.",
        "تلگرام پیدا نشد. آیدی سازنده کپی شد",
    )
    val tgFailMsg = homeText("Couldn't open Telegram", "تلگرام باز نشد")

    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { LogExport.writeTo(context, uri, snapshot.text) }
            Toast.makeText(context, if (ok) savedMsg else saveFailedMsg, Toast.LENGTH_SHORT).show()
            if (ok) {
                AppLogRepository.info(LogSource.APP, "Log saved as ${snapshot.fileName}")
                close()
            }
        }
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
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(PowGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PowGap),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(accent.copy(alpha = 0.14f), PowShape)
                            .border(1.dp, accent.copy(alpha = 0.30f), PowShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Description, null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            homeText("Log file", "فایل لاگ"),
                            color = UacColors.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            homeText(
                                "${snapshot.lineCount} lines · ${sizeLabel(snapshot.bytes)}",
                                "${snapshot.lineCount} خط · ${sizeLabelFa(snapshot.bytes)}",
                            ),
                            color = UacColors.TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                        )
                    }
                }

                PowCard {
                    LogExportOption(
                        icon = Icons.Outlined.SaveAlt,
                        tint = accent,
                        title = homeText("Save file", "ذخیره فایل"),
                        hint = homeText("Pick where to keep it", "هر جا خواستی نگهش دار"),
                        onClick = { saver.launch(snapshot.fileName) },
                    )
                    PowRowDivider()
                    LogExportOption(
                        icon = Icons.AutoMirrored.Outlined.Send,
                        tint = TelegramBlue,
                        title = homeText("Send to developer on Telegram", "ارسال به تلگرام سازنده"),
                        hint = "@${LogExport.DEVELOPER_TELEGRAM}",
                        hintLtr = true,
                        onClick = {
                            val result = LogExport.sendToTelegram(context, snapshot)
                            val msg = when (result) {
                                LogExport.TelegramResult.TELEGRAM,
                                LogExport.TelegramResult.CHOOSER -> tgHint
                                LogExport.TelegramResult.LINK -> tgLinkHint
                                LogExport.TelegramResult.FAILED -> tgFailMsg
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            if (result != LogExport.TelegramResult.FAILED) close()
                        },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(PowShape)
                        .clickable { close() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        homeText("Close", "بستن"),
                        color = UacColors.TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogExportOption(
    icon: ImageVector,
    tint: Color,
    title: String,
    hint: String,
    hintLtr: Boolean = false,
    onClick: () -> Unit,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PowPad, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PowGap),
    ) {
        PowIconTile(icon = icon, tint = tint)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = UacColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hintLtr) "\u2068$hint\u2069" else hint,
                color = UacColors.TextSecondary,
                fontSize = PowHintSize,
                lineHeight = PowHintLine,
                maxLines = 1,
                textAlign = TextAlign.Start,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (rtl) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = UacColors.TextSecondary.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp),
        )
    }
}

private fun sizeLabel(bytes: Long): String =
    if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)

private fun sizeLabelFa(bytes: Long): String =
    if (bytes < 1024) "$bytes بایت" else if (bytes < 1024 * 1024) "${bytes / 1024} کیلوبایت" else String.format(java.util.Locale.US, "%.1f مگابایت", bytes / 1048576.0)
