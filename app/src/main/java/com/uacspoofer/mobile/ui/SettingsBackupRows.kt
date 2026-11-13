package com.uacspoofer.mobile.ui

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.uacspoofer.mobile.R
import com.uacspoofer.mobile.core.ConnectionStateStore
import com.uacspoofer.mobile.engine.canChangeEngineMode
import com.uacspoofer.mobile.settings.SettingsBackup
import java.util.Locale
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun SettingsBackupRows(isPersian: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by ConnectionStateStore.state.collectAsStateWithLifecycle()
    val connected = !canChangeEngineMode(connection)
    var working by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<JSONObject?>(null) }
    var pendingResetAfterBackup by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun text(id: Int, vararg args: Any): String = settingsText(context, isPersian, id, *args)

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            pendingResetAfterBackup = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            working = true
            val wrote = SettingsBackup.write(context, uri)
            if (!wrote) {
                pendingResetAfterBackup = false
                message = text(R.string.settings_backup_failed)
                working = false
                return@launch
            }
            if (pendingResetAfterBackup) {
                pendingResetAfterBackup = false
                message = when (val outcome = SettingsBackup.reset(context)) {
                    SettingsBackup.Outcome.Reset -> text(R.string.settings_reset_done)
                    SettingsBackup.Outcome.DisconnectFailed -> text(R.string.settings_disconnect_failed)
                    else -> text(R.string.settings_action_failed)
                }
            } else {
                message = text(R.string.settings_backup_saved)
            }
            working = false
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val loaded = SettingsBackup.read(context, uri)) {
            is SettingsBackup.Load.Ok -> pendingRestore = loaded.document
            SettingsBackup.Load.Bad -> message = text(R.string.settings_restore_bad_file)
            SettingsBackup.Load.Io -> message = text(R.string.settings_action_failed)
        }
    }

    Column {
        SettingsNavRow(
            icon = Icons.Outlined.Save,
            title = text(R.string.settings_backup_title),
            summary = text(R.string.settings_backup_summary),
            isPersian = isPersian,
            showDivider = true,
            onClick = {
                if (working) return@SettingsNavRow
                pendingResetAfterBackup = false
                createBackup.launch(SettingsBackup.suggestedFileName())
            },
        )
        SettingsNavRow(
            icon = Icons.Outlined.Restore,
            title = text(R.string.settings_restore_title),
            summary = text(R.string.settings_restore_summary),
            isPersian = isPersian,
            showDivider = true,
            onClick = {
                if (working) return@SettingsNavRow
                openBackup.launch(arrayOf("application/json", "application/*", "*/*"))
            },
        )
        SettingsNavRow(
            icon = Icons.Outlined.RestartAlt,
            title = text(R.string.settings_reset_title),
            summary = text(R.string.settings_reset_summary),
            isPersian = isPersian,
            showDivider = true,
            onClick = {
                if (!working) resetOpen = true
            },
        )
    }

    val restoreDoc = pendingRestore
    if (restoreDoc != null) {
        val preview = SettingsBackup.preview(restoreDoc)
        AlertDialog(
            onDismissRequest = { if (!working) pendingRestore = null },
            title = { Text(text(R.string.settings_restore_confirm_title)) },
            text = {
                Text(
                    buildString {
                        append(text(R.string.settings_restore_confirm_body, preview.versionName, preview.createdAt))
                        if (connected) {
                            append("\n\n")
                            append(text(R.string.settings_restore_confirm_disconnect))
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            val outcome = SettingsBackup.restore(context, restoreDoc)
                            pendingRestore = null
                            message = when (outcome) {
                                is SettingsBackup.Outcome.Restored -> text(
                                    R.string.settings_restore_done,
                                    outcome.applied,
                                    outcome.ignored,
                                )
                                SettingsBackup.Outcome.BadFile -> text(R.string.settings_restore_bad_file)
                                SettingsBackup.Outcome.DisconnectFailed -> text(R.string.settings_disconnect_failed)
                                else -> text(R.string.settings_action_failed)
                            }
                            working = false
                        }
                    },
                ) { Text(text(R.string.settings_restore_apply)) }
            },
            dismissButton = {
                TextButton(enabled = !working, onClick = { pendingRestore = null }) {
                    Text(text(R.string.settings_cancel))
                }
            },
        )
    }

    if (resetOpen) {
        AlertDialog(
            onDismissRequest = { if (!working) resetOpen = false },
            title = { Text(text(R.string.settings_reset_confirm_title)) },
            text = {
                Text(
                    buildString {
                        append(text(R.string.settings_reset_confirm_body))
                        if (connected) {
                            append("\n\n")
                            append(text(R.string.settings_reset_confirm_disconnect))
                        }
                    },
                )
            },
            confirmButton = {
                Button(
                    enabled = !working,
                    onClick = {
                        resetOpen = false
                        scope.launch {
                            working = true
                            message = when (val outcome = SettingsBackup.reset(context)) {
                                SettingsBackup.Outcome.Reset -> text(R.string.settings_reset_done)
                                SettingsBackup.Outcome.DisconnectFailed -> text(R.string.settings_disconnect_failed)
                                else -> text(R.string.settings_action_failed)
                            }
                            working = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF4D6A),
                        contentColor = Color.White,
                    ),
                ) { Text(text(R.string.settings_reset_now)) }
            },
            dismissButton = {
                Column {
                    TextButton(
                        enabled = !working,
                        onClick = {
                            resetOpen = false
                            pendingResetAfterBackup = true
                            createBackup.launch(SettingsBackup.suggestedFileName())
                        },
                    ) { Text(text(R.string.settings_reset_backup_first)) }
                    TextButton(enabled = !working, onClick = { resetOpen = false }) {
                        Text(text(R.string.settings_cancel))
                    }
                }
            },
        )
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(message.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { message = null }) { Text(text(R.string.settings_ok)) }
            },
        )
    }
}

private fun settingsText(context: android.content.Context, isPersian: Boolean, id: Int, vararg args: Any): String {
    val locale = if (isPersian) Locale("fa") else Locale.ENGLISH
    val config = Configuration(context.resources.configuration)
    config.setLocale(locale)
    val resources = context.createConfigurationContext(config).resources
    return if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args)
}
