package com.isaakhanimann.journal.ui.tabs.journal.routine

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.ui.tabs.journal.getLocalizedNameRes
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutineSchedulesScreen(
    navigateBack: () -> Unit,
    navigateToCreateSchedule: () -> Unit,
    navigateToEditSchedule: (Int) -> Unit,
    viewModel: RoutineSchedulesViewModel = hiltViewModel()
) {
    val schedules by viewModel.schedulesFlow.collectAsStateWithLifecycle()
    val catchUpDays by viewModel.catchUpDaysFlow.collectAsStateWithLifecycle()
    val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsStateWithLifecycle()
    val canScheduleExactAlarms by viewModel.canScheduleExactAlarms.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val openSettingsFailedMsg = stringResource(R.string.routine_perm_open_settings_failed)

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    var scheduleToDelete by remember { mutableStateOf<MedicationSchedule?>(null) }

    scheduleToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { scheduleToDelete = null },
            title = { Text(stringResource(R.string.routine_schedules_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.routine_schedules_delete_desc,
                        SubstanceLocalizationHelper.getSubstanceDisplayName(target.substanceName)
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSchedule(target)
                        scheduleToDelete = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.routine_common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { scheduleToDelete = null }) {
                    Text(stringResource(R.string.routine_common_cancel))
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.routine_schedules_title)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = navigateToCreateSchedule) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.routine_schedules_add)
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item(key = "perm_notification") {
                    PermissionBannerCard(
                        title = stringResource(R.string.routine_perm_notification_title),
                        description = stringResource(R.string.routine_perm_notification_desc),
                        buttonLabel = stringResource(R.string.routine_perm_notification_btn),
                        onClick = {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    )
                }
            }

            if (!canScheduleExactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item(key = "perm_exact_alarm") {
                    PermissionBannerCard(
                        title = stringResource(R.string.routine_perm_exact_alarm_title),
                        description = stringResource(R.string.routine_perm_exact_alarm_desc),
                        buttonLabel = stringResource(R.string.routine_perm_exact_alarm_btn),
                        onClick = {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            runCatching { context.startActivity(intent) }.onFailure {
                                scope.launch {
                                    snackbarHostState.showSnackbar(openSettingsFailedMsg)
                                }
                            }
                        }
                    )
                }
            }

            item(key = "catch_up_card") {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.routine_catch_up_title),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.routine_catch_up_subtitle, catchUpDays),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(1, 3, 7, 14, 30).forEach { days ->
                                FilterChip(
                                    selected = catchUpDays == days,
                                    onClick = { viewModel.setCatchUpDays(days) },
                                    label = { Text(stringResource(R.string.routine_catch_up_chip_days, days)) }
                                )
                            }
                        }
                    }
                }
            }

            if (schedules.isEmpty()) {
                item(key = "empty_schedules") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Alarm,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.routine_schedules_empty_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.routine_schedules_empty_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = schedules,
                    key = { it.schedule.id }
                ) { item ->
                    ScheduleCard(
                        item = item,
                        onEdit = { navigateToEditSchedule(item.schedule.id) },
                        onToggleEnabled = { enabled ->
                            viewModel.setScheduleEnabled(item.schedule.id, enabled)
                        },
                        onDelete = { scheduleToDelete = item.schedule }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionBannerCard(
    title: String,
    description: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Button(
                onClick = onClick,
                modifier = Modifier.padding(start = 10.dp)
            ) {
                Text(text = buttonLabel)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(
    item: ScheduleListItemUiState,
    onEdit: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val schedule = item.schedule
    val slots = schedule.decodedSlots()
    val localizedName = SubstanceLocalizationHelper.getSubstanceDisplayName(schedule.substanceName)
    val routeName = stringResource(schedule.administrationRoute.getLocalizedNameRes())

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val titleText = if (item.customUnitName != null) {
                        "$localizedName · ${item.customUnitName}"
                    } else {
                        localizedName
                    }
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = listOfNotNull(
                            routeName,
                            RoutineUiHelpers.formatRecurrenceSummary(schedule),
                            schedule.consumerName?.takeIf { it.isNotBlank() }
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = schedule.isEnabled,
                        onCheckedChange = onToggleEnabled
                    )
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = stringResource(R.string.routine_common_delete)
                        )
                    }
                }
            }

            if (slots.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    slots.forEach { slot ->
                        val doseLabel = RoutineUiHelpers.formatDoseText(
                            dose = slot.dose,
                            units = slot.units,
                            isEstimate = slot.isDoseAnEstimate,
                            sd = slot.estimatedDoseStandardDeviation,
                            customUnit = null
                        )
                        val chipText = if (doseLabel != null) {
                            "${RoutineUiHelpers.formatSlotTime(slot)} · $doseLabel"
                        } else {
                            RoutineUiHelpers.formatSlotTime(slot)
                        }
                        AssistChip(onClick = onEdit, label = { Text(chipText) })
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (schedule.notifyOnTrigger) {
                            Icons.Outlined.NotificationsActive
                        } else {
                            Icons.Outlined.NotificationsOff
                        },
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            !schedule.isEnabled -> stringResource(R.string.routine_schedules_disabled)
                            schedule.notifyOnTrigger -> stringResource(R.string.routine_schedules_notify_on)
                            else -> stringResource(R.string.routine_schedules_notify_off)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val nextText = item.nextTriggerInstant?.let {
                    stringResource(
                        R.string.routine_schedules_next_trigger,
                        RoutineUiHelpers.formatInstantDateTime(it)
                    )
                } ?: stringResource(R.string.routine_schedules_no_upcoming)
                Text(
                    text = nextText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (schedule.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
