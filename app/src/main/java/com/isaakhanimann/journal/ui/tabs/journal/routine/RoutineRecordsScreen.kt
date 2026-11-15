package com.isaakhanimann.journal.ui.tabs.journal.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.AdaptiveColor
import com.isaakhanimann.journal.data.room.experiences.entities.AppColor
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.ui.tabs.journal.components.CopyMemoBadge
import com.isaakhanimann.journal.ui.tabs.journal.components.CopyMemoExplanationDialog
import com.isaakhanimann.journal.ui.tabs.journal.components.UnconfirmedDoseActionDialog
import com.isaakhanimann.journal.ui.tabs.journal.components.UnconfirmedMedicationBadge
import com.isaakhanimann.journal.ui.tabs.journal.getLocalizedNameRes
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineRecordsScreen(
    navigateBack: () -> Unit,
    navigateToManageSchedules: () -> Unit,
    navigateToExperience: (Int) -> Unit,
    navigateToEditIngestion: (Int) -> Unit,
    viewModel: RoutineRecordsViewModel = hiltViewModel()
) {
    val records by viewModel.recordsFlow.collectAsStateWithLifecycle()
    val currentFilter by viewModel.filter.collectAsStateWithLifecycle()
    val unconfirmedCount by viewModel.unconfirmedCountFlow.collectAsStateWithLifecycle()

    var pendingActionIngestion by remember { mutableStateOf<Ingestion?>(null) }
    var selectedMemoItem by remember { mutableStateOf<RoutineRecordUiItem?>(null) }

    pendingActionIngestion?.let { ing ->
        UnconfirmedDoseActionDialog(
            substanceName = ing.substanceName,
            doseTimeText = RoutineUiHelpers.formatInstantDateTime(ing.time),
            onConfirmTaken = {
                viewModel.confirmIngestion(ing.id)
                pendingActionIngestion = null
            },
            onSkipDelete = {
                viewModel.skipIngestion(ing.id)
                pendingActionIngestion = null
            },
            onEditRecord = {
                pendingActionIngestion = null
                navigateToEditIngestion(ing.id)
            },
            onDismiss = { pendingActionIngestion = null }
        )
    }

    selectedMemoItem?.let { memoItem ->
        val exp = memoItem.parentExperience
        if (exp != null) {
            CopyMemoExplanationDialog(
                experienceTitle = SubstanceLocalizationHelper.getSubstanceDisplayName(exp.title),
                substanceName = memoItem.ingestionWithDetails.ingestion.substanceName,
                onOpenExperience = {
                    selectedMemoItem = null
                    navigateToExperience(exp.id)
                },
                onDismiss = { selectedMemoItem = null }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.routine_records_title)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::syncNow) {
                        Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = stringResource(R.string.routine_records_reconcile_now)
                        )
                    }
                    TextButton(onClick = navigateToManageSchedules) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = stringResource(R.string.routine_records_manage_schedules))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = currentFilter == RoutineRecordsFilter.ALL,
                    onClick = { viewModel.setFilter(RoutineRecordsFilter.ALL) },
                    label = { Text(stringResource(R.string.routine_records_filter_all)) }
                )
                FilterChip(
                    selected = currentFilter == RoutineRecordsFilter.UNCONFIRMED,
                    onClick = { viewModel.setFilter(RoutineRecordsFilter.UNCONFIRMED) },
                    label = { Text(stringResource(R.string.routine_records_filter_unconfirmed)) }
                )
                FilterChip(
                    selected = currentFilter == RoutineRecordsFilter.COPY_MEMO,
                    onClick = { viewModel.setFilter(RoutineRecordsFilter.COPY_MEMO) },
                    label = { Text(stringResource(R.string.routine_records_filter_memo)) }
                )
            }

            if (unconfirmedCount > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.routine_records_pending_summary, unconfirmedCount),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        TextButton(
                            onClick = viewModel::confirmAllUnconfirmed,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = stringResource(R.string.routine_records_confirm_all))
                        }
                    }
                }
            }

            if (records.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Alarm,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.routine_records_empty_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.routine_records_empty_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = navigateToManageSchedules) {
                            Text(text = stringResource(R.string.routine_records_manage_schedules))
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = records,
                        key = { it.ingestionWithDetails.ingestion.id }
                    ) { item ->
                        RoutineRecordCard(
                            item = item,
                            onCardClick = {
                                val ing = item.ingestionWithDetails.ingestion
                                if (!ing.isConfirmed) {
                                    pendingActionIngestion = ing
                                } else {
                                    navigateToEditIngestion(ing.id)
                                }
                            },
                            onUnconfirmedBadgeClick = {
                                pendingActionIngestion = item.ingestionWithDetails.ingestion
                            },
                            onMemoBadgeClick = {
                                selectedMemoItem = item
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineRecordCard(
    item: RoutineRecordUiItem,
    onCardClick: () -> Unit,
    onUnconfirmedBadgeClick: () -> Unit,
    onMemoBadgeClick: () -> Unit
) {
    val ingWithCompanion = item.ingestionWithDetails
    val ing = ingWithCompanion.ingestion
    val customUnit = ingWithCompanion.customUnit
    val isDark = isSystemInDarkTheme()
    val barColor = (ingWithCompanion.substanceCompanion?.color ?: AppColor(AdaptiveColor.BLUE))
        .getComposeColor(isDark)

    val localizedName = SubstanceLocalizationHelper.getSubstanceDisplayName(ing.substanceName)
    val title = if (customUnit != null) {
        "$localizedName · ${customUnit.name}"
    } else {
        localizedName
    }
    val doseText = RoutineUiHelpers.formatIngestionDoseText(ing, customUnit)
    val routeText = stringResource(ing.administrationRoute.getLocalizedNameRes())
    val timeText = RoutineUiHelpers.formatInstantDateTime(ing.time)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.isSameDayCopyMemo) {
                            CopyMemoBadge(onClick = onMemoBadgeClick)
                        }
                        if (!ing.isConfirmed) {
                            UnconfirmedMedicationBadge(onClick = onUnconfirmedBadgeClick)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val detailParts = buildList {
                    if (doseText != null) add(doseText)
                    add(routeText)
                    add(timeText)
                    ing.consumerName?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" · ")
                Text(
                    text = detailParts,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ing.notes?.takeIf { it.isNotBlank() }?.let { note ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (item.isSameDayCopyMemo && item.parentExperience != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.routine_memo_attached_prefix,
                            SubstanceLocalizationHelper.getSubstanceDisplayName(item.parentExperience.title)
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
