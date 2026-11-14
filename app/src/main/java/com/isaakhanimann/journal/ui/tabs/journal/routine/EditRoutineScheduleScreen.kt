package com.isaakhanimann.journal.ui.tabs.journal.routine

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.ui.tabs.journal.getLocalizedNameRes
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditRoutineScheduleScreen(
    navigateBack: () -> Unit,
    viewModel: EditRoutineScheduleViewModel = hiltViewModel()
) {
    val customSubstances by viewModel.customSubstancesFlow.collectAsState()
    val customUnits by viewModel.customUnitsFlow.collectAsState()
    val context = LocalContext.current

    var showSubstancePicker by remember { mutableStateOf(false) }
    var showCustomUnitPicker by remember { mutableStateOf(false) }
    var pickingTimeForSlotIndex by remember { mutableIntStateOf(-1) }
    var pickingAnchorTime by remember { mutableStateOf(false) }
    var pickingDateTarget by remember { mutableStateOf<String?>(null) } // "start" | "end"

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.saveSchedule(onSaved = navigateBack)
    }

    val performSave = {
        val needNotifPerm = viewModel.isEnabled && viewModel.notifyOnTrigger &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needNotifPerm) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.saveSchedule(onSaved = navigateBack)
        }
    }

    if (showSubstancePicker) {
        SubstanceAndCustomSubstancePickerDialog(
            viewModel = viewModel,
            customSubstances = customSubstances.map { it.name to it.units },
            onSelect = { name, defaultUnits ->
                viewModel.selectBuiltInOrCustomSubstance(name, defaultUnits)
                showSubstancePicker = false
            },
            onDismiss = { showSubstancePicker = false }
        )
    }

    if (showCustomUnitPicker) {
        AlertDialog(
            onDismissRequest = { showCustomUnitPicker = false },
            title = { Text(stringResource(R.string.routine_edit_pick_custom_unit)) },
            text = {
                val filteredUnits = if (viewModel.substanceName.isNotBlank()) {
                    customUnits.filter { it.substanceName == viewModel.substanceName }
                        .ifEmpty { customUnits }
                } else {
                    customUnits
                }
                if (filteredUnits.isEmpty()) {
                    Text(stringResource(R.string.settings_custom_units_empty_title))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(filteredUnits, key = { it.id }) { unit ->
                            val subDisplay = SubstanceLocalizationHelper.getSubstanceDisplayName(unit.substanceName)
                            val routeName = stringResource(unit.administrationRoute.getLocalizedNameRes())
                            ListItem(
                                headlineContent = { Text("$subDisplay · ${unit.name}") },
                                supportingContent = {
                                    Text("$routeName · ${unit.getDoseOfOneUnitDescription()}")
                                },
                                modifier = Modifier.clickable {
                                    viewModel.selectCustomUnit(unit)
                                    showCustomUnitPicker = false
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCustomUnitPicker = false }) {
                    Text(stringResource(R.string.routine_common_close))
                }
            }
        )
    }

    if (pickingTimeForSlotIndex >= 0 && pickingTimeForSlotIndex in viewModel.slots.indices) {
        val currentSlot = viewModel.slots[pickingTimeForSlotIndex]
        val timeState = rememberTimePickerState(
            initialHour = currentSlot.hour,
            initialMinute = currentSlot.minute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickingTimeForSlotIndex = -1 },
            title = { Text(stringResource(R.string.routine_slot_time)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateSlot(pickingTimeForSlotIndex) { slot ->
                            slot.copy(hour = timeState.hour, minute = timeState.minute)
                        }
                        pickingTimeForSlotIndex = -1
                    }
                ) {
                    Text(stringResource(R.string.routine_edit_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingTimeForSlotIndex = -1 }) {
                    Text(stringResource(R.string.routine_common_cancel))
                }
            }
        )
    }

    if (pickingAnchorTime) {
        val timeState = rememberTimePickerState(
            initialHour = viewModel.anchorHour,
            initialMinute = viewModel.anchorMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { pickingAnchorTime = false },
            title = { Text(stringResource(R.string.routine_anchor_time_label)) },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.anchorHour = timeState.hour
                        viewModel.anchorMinute = timeState.minute
                        pickingAnchorTime = false
                    }
                ) {
                    Text(stringResource(R.string.routine_edit_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingAnchorTime = false }) {
                    Text(stringResource(R.string.routine_common_cancel))
                }
            }
        )
    }

    pickingDateTarget?.let { target ->
        val initialMillis = when (target) {
            "start" -> viewModel.startEpochDay?.let { it * 86_400_000L }
            "end" -> viewModel.endEpochDay?.let { it * 86_400_000L }
            else -> null
        }
        val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickingDateTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        val selectedDay = dateState.selectedDateMillis?.let { millis ->
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        }
                        if (target == "start") viewModel.startEpochDay = selectedDay
                        if (target == "end") viewModel.endEpochDay = selectedDay
                        pickingDateTarget = null
                    }
                ) {
                    Text(stringResource(R.string.routine_edit_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickingDateTarget = null }) {
                    Text(stringResource(R.string.routine_common_cancel))
                }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(
                            if (viewModel.isEditMode) R.string.routine_edit_title_edit
                            else R.string.routine_edit_title_new
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    TextButton(onClick = performSave) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.routine_edit_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            viewModel.validationErrorRes?.let { errRes ->
                Text(
                    text = stringResource(errRes),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }

            // Section 1: Substance, Route, CustomUnit, Consumer
            SectionTitle(stringResource(R.string.routine_edit_substance_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val displaySubName = if (viewModel.substanceName.isBlank()) {
                        stringResource(R.string.routine_edit_pick_substance)
                    } else {
                        SubstanceLocalizationHelper.getSubstanceDisplayName(viewModel.substanceName)
                    }
                    OutlinedButton(
                        onClick = { showSubstancePicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(displaySubName)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showCustomUnitPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = viewModel.selectedCustomUnit?.let {
                                    "${it.name} (${it.getDoseOfOneUnitDescription()})"
                                } ?: stringResource(R.string.routine_edit_pick_custom_unit)
                            )
                        }
                        if (viewModel.selectedCustomUnit != null) {
                            IconButton(onClick = viewModel::clearCustomUnit) {
                                Icon(
                                    Icons.Outlined.Clear,
                                    contentDescription = stringResource(R.string.routine_edit_clear_custom_unit)
                                )
                            }
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AdministrationRoute.entries.forEach { route ->
                            FilterChip(
                                selected = viewModel.selectedRoute == route,
                                onClick = { viewModel.selectRoute(route) },
                                label = { Text(stringResource(route.getLocalizedNameRes())) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = viewModel.consumerNameText,
                        onValueChange = { viewModel.consumerNameText = it },
                        label = { Text(stringResource(R.string.routine_edit_consumer_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (viewModel.consumerSuggestions.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            viewModel.consumerSuggestions.take(6).forEach { name ->
                                AssistChip(
                                    onClick = { viewModel.consumerNameText = name },
                                    label = { Text(name) }
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Recurrence Rule
            SectionTitle(stringResource(R.string.routine_edit_recurrence_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val modes = listOf(
                            RecurrenceType.DAILY to R.string.routine_mode_daily,
                            RecurrenceType.WEEKLY to R.string.routine_mode_weekly,
                            RecurrenceType.EVERY_N_DAYS to R.string.routine_mode_every_n_days,
                            RecurrenceType.EVERY_N_MINUTES to R.string.routine_mode_every_n_minutes,
                            RecurrenceType.CYCLE_ON_OFF to R.string.routine_mode_cycle_on_off
                        )
                        modes.forEach { (type, labelRes) ->
                            FilterChip(
                                selected = viewModel.recurrenceType == type,
                                onClick = { viewModel.recurrenceType = type },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                    }

                    when (viewModel.recurrenceType) {
                        RecurrenceType.DAILY -> Unit
                        RecurrenceType.WEEKLY -> {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val weekdays = listOf(
                                    1 to R.string.routine_weekday_mon,
                                    2 to R.string.routine_weekday_tue,
                                    3 to R.string.routine_weekday_wed,
                                    4 to R.string.routine_weekday_thu,
                                    5 to R.string.routine_weekday_fri,
                                    6 to R.string.routine_weekday_sat,
                                    7 to R.string.routine_weekday_sun
                                )
                                weekdays.forEach { (iso, res) ->
                                    FilterChip(
                                        selected = MedicationSchedule.isDaySelected(viewModel.daysOfWeekMask, iso),
                                        onClick = { viewModel.toggleWeekday(iso) },
                                        label = { Text(stringResource(res)) }
                                    )
                                }
                            }
                        }
                        RecurrenceType.EVERY_N_DAYS -> {
                            OutlinedTextField(
                                value = viewModel.intervalDaysText,
                                onValueChange = { viewModel.intervalDaysText = it.filter(Char::isDigit) },
                                label = { Text(stringResource(R.string.routine_every_n_days_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        RecurrenceType.EVERY_N_MINUTES -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = viewModel.intervalHoursText,
                                    onValueChange = { viewModel.intervalHoursText = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.routine_every_hours_label)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = viewModel.intervalExtraMinutesText,
                                    onValueChange = { viewModel.intervalExtraMinutesText = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.routine_every_minutes_label)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedButton(
                                onClick = { pickingAnchorTime = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Outlined.AccessTime, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "${stringResource(R.string.routine_anchor_time_label)}: " +
                                        "%02d:%02d".format(viewModel.anchorHour, viewModel.anchorMinute)
                                )
                            }
                        }
                        RecurrenceType.CYCLE_ON_OFF -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = viewModel.cycleOnDaysText,
                                    onValueChange = { viewModel.cycleOnDaysText = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.routine_cycle_on_label)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = viewModel.cycleOffDaysText,
                                    onValueChange = { viewModel.cycleOffDaysText = it.filter(Char::isDigit) },
                                    label = { Text(stringResource(R.string.routine_cycle_off_label)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: Time Slots & Independent Doses
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(stringResource(R.string.routine_slots_section))
                if (viewModel.recurrenceType != RecurrenceType.EVERY_N_MINUTES) {
                    TextButton(onClick = viewModel::addSlot) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.routine_add_slot))
                    }
                }
            }

            val visibleSlots = if (viewModel.recurrenceType == RecurrenceType.EVERY_N_MINUTES) {
                viewModel.slots.take(1)
            } else {
                viewModel.slots
            }
            visibleSlots.forEachIndexed { index, slot ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (viewModel.recurrenceType != RecurrenceType.EVERY_N_MINUTES) {
                                OutlinedButton(onClick = { pickingTimeForSlotIndex = index }) {
                                    Icon(
                                        Icons.Outlined.AccessTime,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("%02d:%02d".format(slot.hour, slot.minute))
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.routine_slot_dose),
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }
                            if (visibleSlots.size > 1) {
                                IconButton(onClick = { viewModel.removeSlot(index) }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = stringResource(R.string.routine_common_delete)
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = slot.doseText,
                                onValueChange = { raw ->
                                    viewModel.updateSlot(index) { it.copy(doseText = raw) }
                                },
                                label = { Text(stringResource(R.string.routine_slot_dose)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = slot.unitsText,
                                onValueChange = { raw ->
                                    viewModel.updateSlot(index) { it.copy(unitsText = raw) }
                                },
                                label = { Text(stringResource(R.string.routine_slot_units)) },
                                singleLine = true,
                                modifier = Modifier.weight(0.8f)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = slot.isEstimate,
                                onCheckedChange = { checked ->
                                    viewModel.updateSlot(index) { it.copy(isEstimate = checked) }
                                }
                            )
                            Text(
                                text = stringResource(R.string.routine_slot_estimate),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (slot.isEstimate) {
                                Spacer(modifier = Modifier.width(12.dp))
                                OutlinedTextField(
                                    value = slot.sdText,
                                    onValueChange = { raw ->
                                        viewModel.updateSlot(index) { it.copy(sdText = raw) }
                                    },
                                    label = { Text(stringResource(R.string.routine_slot_sd)) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        OutlinedTextField(
                            value = slot.noteText,
                            onValueChange = { raw ->
                                viewModel.updateSlot(index) { it.copy(noteText = raw) }
                            },
                            label = { Text(stringResource(R.string.routine_slot_note)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Section 4: Behavior, Bounds & Smart Grouping
            SectionTitle(stringResource(R.string.routine_advanced_section))
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.routine_toggle_enabled),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Switch(
                            checked = viewModel.isEnabled,
                            onCheckedChange = { viewModel.isEnabled = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.routine_toggle_notify),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = stringResource(R.string.routine_toggle_notify_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = viewModel.notifyOnTrigger,
                            onCheckedChange = { viewModel.notifyOnTrigger = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { pickingDateTarget = "start" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                RoutineUiHelpers.formatEpochDay(viewModel.startEpochDay)
                                    ?: stringResource(R.string.routine_start_date_now)
                            )
                        }
                        if (viewModel.startEpochDay != null) {
                            IconButton(onClick = { viewModel.startEpochDay = null }) {
                                Icon(Icons.Outlined.Clear, contentDescription = null)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { pickingDateTarget = "end" },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                RoutineUiHelpers.formatEpochDay(viewModel.endEpochDay)
                                    ?: stringResource(R.string.routine_end_date_none)
                            )
                        }
                        if (viewModel.endEpochDay != null) {
                            IconButton(onClick = { viewModel.endEpochDay = null }) {
                                Icon(Icons.Outlined.Clear, contentDescription = null)
                            }
                        }
                    }

                    OutlinedTextField(
                        value = viewModel.maxOccurrencesText,
                        onValueChange = { viewModel.maxOccurrencesText = it.filter(Char::isDigit) },
                        label = { Text(stringResource(R.string.routine_max_occurrences_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = viewModel.customHalfLifeText,
                        onValueChange = { viewModel.customHalfLifeText = it },
                        label = { Text(stringResource(R.string.routine_half_life_override_label)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    val (halfLifeHours, totalDuration) = viewModel.getPkHintData()
                    val pkHint = when {
                        halfLifeHours != null -> stringResource(
                            R.string.routine_half_life_auto_hint,
                            "t½ ≈ ${halfLifeHours}h"
                        )
                        totalDuration != null -> stringResource(
                            R.string.routine_half_life_auto_hint,
                            totalDuration
                        )
                        else -> stringResource(R.string.routine_half_life_missing_hint)
                    }
                    Text(
                        text = pkHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = viewModel.scheduleNoteText,
                        onValueChange = { viewModel.scheduleNoteText = it },
                        label = { Text(stringResource(R.string.routine_note_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Upcoming Triggers Preview
            val upcoming = viewModel.buildUpcomingPreview(3)
            if (upcoming.isNotEmpty()) {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        upcoming.forEach { instant ->
                            Text(
                                text = stringResource(
                                    R.string.routine_schedules_next_trigger,
                                    RoutineUiHelpers.formatInstantDateTime(instant)
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SubstanceAndCustomSubstancePickerDialog(
    viewModel: EditRoutineScheduleViewModel,
    customSubstances: List<Pair<String, String>>,
    onSelect: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val matchingCustom = remember(searchQuery, customSubstances) {
        customSubstances.filter { (name, _) ->
            searchQuery.isBlank() ||
                name.contains(searchQuery, ignoreCase = true) ||
                SubstanceLocalizationHelper.getSubstanceDisplayName(name).contains(searchQuery, ignoreCase = true)
        }
    }
    val matchingBuiltIn = remember(searchQuery) {
        viewModel.builtInSubstances.filter { viewModel.matchesSearch(it, searchQuery) }.take(80)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.routine_edit_pick_substance)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.routine_search_substance_placeholder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(matchingCustom, key = { "custom_${it.first}" }) { (name, units) ->
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text(units) },
                            modifier = Modifier.clickable { onSelect(name, units) }
                        )
                        HorizontalDivider()
                    }
                    items(matchingBuiltIn, key = { "sub_${it.name}" }) { sub ->
                        val zh = SubstanceLocalizationHelper.getSubstanceDisplayName(sub.name)
                        ListItem(
                            headlineContent = { Text(zh) },
                            supportingContent = {
                                if (zh != sub.name) Text(sub.name) else null
                            },
                            modifier = Modifier.clickable { onSelect(sub.name, null) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.routine_common_close))
            }
        }
    )
}
