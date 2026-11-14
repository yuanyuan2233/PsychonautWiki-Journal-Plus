/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.ui.tabs.settings

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.routine.RoutineSchedulerManager
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import com.isaakhanimann.journal.ui.tabs.stats.tolerance.SubstanceToleranceCustomization
import com.isaakhanimann.journal.ui.tabs.stats.tolerance.ToleranceCustomizationStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject

data class SettingsSnackbarEvent(
    @StringRes val messageRes: Int,
    val duration: SnackbarDuration = SnackbarDuration.Short
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val experienceRepository: ExperienceRepository,
    private val routineDao: RoutineDao,
    private val routineSchedulerManager: RoutineSchedulerManager,
    private val fileSystemConnection: FileSystemConnection,
    private val userPreferences: UserPreferences,
    private val toleranceCustomizationStore: ToleranceCustomizationStore,
) : ViewModel() {

    fun saveDosageDotsAreHidden(value: Boolean) = viewModelScope.launch {
        userPreferences.saveDosageDotsAreHidden(value)
    }

    fun saveAreSubstanceHeightsIndependent(value: Boolean) = viewModelScope.launch {
        userPreferences.saveAreSubstanceHeightsIndependent(value)
    }

    fun saveIsTimelineHidden(value: Boolean) = viewModelScope.launch {
        userPreferences.saveIsTimelineHidden(value)
    }

    val isTimelineHiddenFlow = userPreferences.isTimelineHiddenFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val areSubstanceHeightsIndependentFlow = userPreferences.areSubstanceHeightsIndependentFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val areDosageDotsHiddenFlow = userPreferences.areDosageDotsHiddenFlow.stateIn(
        initialValue = false,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    val appLanguageFlow = userPreferences.appLanguageFlow.stateIn(
        initialValue = AppLanguage.FOLLOW_SYSTEM,
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    fun saveAppLanguage(value: AppLanguage) = viewModelScope.launch {
        userPreferences.saveAppLanguage(value)
    }

    val snackbarHostState = SnackbarHostState()

    private val _snackbarEvents = Channel<SettingsSnackbarEvent>(Channel.BUFFERED)
    val snackbarEvents = _snackbarEvents.receiveAsFlow()

    private val dataOperationMutex = Mutex()

    private suspend fun emitSnackbar(
        @StringRes messageRes: Int,
        duration: SnackbarDuration = SnackbarDuration.Short
    ) {
        _snackbarEvents.send(SettingsSnackbarEvent(messageRes = messageRes, duration = duration))
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            dataOperationMutex.withLock {
                val rawText = try {
                    withContext(Dispatchers.IO) {
                        fileSystemConnection.getTextFromUri(uri)
                    }
                } catch (_: SecurityException) {
                    emitSnackbar(R.string.settings_import_permission_denied)
                    return@withLock
                } catch (_: FileNotFoundException) {
                    emitSnackbar(R.string.settings_file_not_found)
                    return@withLock
                } catch (_: IOException) {
                    emitSnackbar(R.string.settings_import_read_failed)
                    return@withLock
                } catch (_: Exception) {
                    emitSnackbar(R.string.settings_import_read_failed)
                    return@withLock
                }

                val text = rawText.removePrefix("\uFEFF")
                if (text.isBlank()) {
                    emitSnackbar(R.string.settings_import_empty_backup)
                    return@withLock
                }

                val journalExport = try {
                    withContext(Dispatchers.Default) {
                        val json = Json {
                            ignoreUnknownKeys = true
                            coerceInputValues = true
                        }
                        json.decodeFromString<JournalExport>(text)
                    }
                } catch (e: Exception) {
                    println("Error when decoding: ${e.message}")
                    emitSnackbar(R.string.settings_decoding_file_failed)
                    return@withLock
                }

                if (journalExport.isEmpty()) {
                    emitSnackbar(R.string.settings_import_empty_backup)
                    return@withLock
                }

                try {
                    withContext(Dispatchers.IO) {
                        routineSchedulerManager.cancelAllAlarms()
                        experienceRepository.replaceEverything(journalExport)
                        val customizations = journalExport.toleranceCustomizations
                            .filter { it.substanceName.isNotBlank() }
                            .map { exportItem ->
                                SubstanceToleranceCustomization(
                                    substanceName = exportItem.substanceName,
                                    customFullDays = exportItem.customFullDays?.takeIf { it.isFinite() && it >= 0.0 },
                                    customZeroDays = exportItem.customZeroDays?.takeIf { it.isFinite() && it >= 0.0 },
                                    manualResetInstant = exportItem.manualResetInstant
                                )
                            }
                        if (!toleranceCustomizationStore.replaceAllCustomizations(customizations)) {
                            throw IOException("Failed to restore tolerance customizations")
                        }
                        routineSchedulerManager.reconcileAndRescheduleAll()
                    }
                    emitSnackbar(R.string.settings_import_successful)
                } catch (e: Exception) {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            routineSchedulerManager.reconcileAndRescheduleAll()
                        }
                    }
                    println("Error when importing: ${e.message}")
                    emitSnackbar(R.string.settings_import_failed)
                }
            }
        }
    }

    fun exportFile(uri: Uri) {
        viewModelScope.launch {
            dataOperationMutex.withLock {
                try {
                    val journalExport = withContext(Dispatchers.IO) {
                        val experiencesWithIngestionsAndRatings =
                            experienceRepository.getAllExperiencesWithIngestionsTimedNotesAndRatingsSorted()
                        val experiencesSerializable = experiencesWithIngestionsAndRatings.map {
                            val location = it.experience.location
                            ExperienceSerializable(
                                title = it.experience.title,
                                text = it.experience.text,
                                creationDate = it.experience.creationDate,
                                sortDate = it.experience.sortDate,
                                isFavorite = it.experience.isFavorite,
                                ingestions = it.ingestions.map { ingestion ->
                                    IngestionSerializable(
                                        substanceName = ingestion.substanceName,
                                        time = ingestion.time,
                                        endTime = ingestion.endTime,
                                        creationDate = ingestion.creationDate,
                                        administrationRoute = ingestion.administrationRoute,
                                        dose = ingestion.dose,
                                        estimatedDoseStandardDeviation = ingestion.estimatedDoseStandardDeviation,
                                        isDoseAnEstimate = ingestion.isDoseAnEstimate,
                                        units = ingestion.units,
                                        notes = ingestion.notes,
                                        stomachFullness = ingestion.stomachFullness,
                                        consumerName = ingestion.consumerName,
                                        customUnitId = ingestion.customUnitId,
                                        isConfirmed = ingestion.isConfirmed,
                                        routineScheduleId = ingestion.routineScheduleId,
                                        scheduledEpochMillis = ingestion.scheduledEpochMillis
                                    )
                                },
                                location = if (location != null) {
                                    LocationSerializable(
                                        name = location.name,
                                        latitude = location.latitude,
                                        longitude = location.longitude
                                    )
                                } else {
                                    null
                                },
                                ratings = it.ratings.map { rating ->
                                    RatingSerializable(
                                        option = rating.option,
                                        time = rating.time,
                                        creationDate = rating.creationDate
                                    )
                                },
                                timedNotes = it.timedNotes.map { timedNote ->
                                    TimedNoteSerializable(
                                        creationDate = timedNote.creationDate,
                                        time = timedNote.time,
                                        note = timedNote.note,
                                        color = timedNote.color,
                                        isPartOfTimeline = timedNote.isPartOfTimeline
                                    )
                                },
                                isRoutineOnly = it.experience.isRoutineOnly
                            )
                        }
                        val customUnitsSerializable = experienceRepository.getAllCustomUnitsSorted().map {
                            CustomUnitSerializable(
                                id = it.id,
                                substanceName = it.substanceName,
                                name = it.name,
                                creationDate = it.creationDate,
                                administrationRoute = it.administrationRoute,
                                dose = it.dose,
                                estimatedDoseStandardDeviation = it.estimatedDoseStandardDeviation,
                                isEstimate = it.isEstimate,
                                isArchived = it.isArchived,
                                unit = it.unit,
                                unitPlural = it.unitPlural,
                                originalUnit = it.originalUnit,
                                note = it.note
                            )
                        }
                        val toleranceCustomizationsSerializable = toleranceCustomizationStore
                            .getAllCustomizationsForExport()
                            .map { customization ->
                                ToleranceCustomizationExport(
                                    substanceName = customization.substanceName,
                                    customFullDays = customization.customFullDays?.takeIf { it.isFinite() && it >= 0.0 },
                                    customZeroDays = customization.customZeroDays?.takeIf { it.isFinite() && it >= 0.0 },
                                    manualResetInstant = customization.manualResetInstant
                                )
                            }
                        val schedulesSerializable = routineDao.getAllSchedulesSorted().map {
                            MedicationScheduleSerializable(
                                id = it.id,
                                substanceName = it.substanceName,
                                administrationRoute = it.administrationRoute,
                                customUnitId = it.customUnitId,
                                consumerName = it.consumerName,
                                isEnabled = it.isEnabled,
                                notifyOnTrigger = it.notifyOnTrigger,
                                recurrenceType = it.recurrenceType,
                                daysOfWeekMask = it.daysOfWeekMask,
                                intervalDays = it.intervalDays,
                                intervalMinutes = it.intervalMinutes,
                                cycleOnDays = it.cycleOnDays,
                                cycleOffDays = it.cycleOffDays,
                                timeSlots = it.decodedSlots(),
                                anchorEpochMillis = it.anchorEpochMillis,
                                startEpochDay = it.startEpochDay,
                                endEpochDay = it.endEpochDay,
                                maxOccurrences = it.maxOccurrences,
                                customHalfLifeHours = it.customHalfLifeHours,
                                note = it.note,
                                createdAt = it.createdAt,
                                updatedAt = it.updatedAt
                            )
                        }
                        val occurrencesSerializable = routineDao.getAllOccurrencesSorted().map {
                            RoutineOccurrenceSerializable(
                                scheduleId = it.scheduleId,
                                slotId = it.slotId,
                                scheduledEpochMillis = it.scheduledEpochMillis,
                                status = it.status,
                                ingestionId = it.ingestionId,
                                handledAt = it.handledAt
                            )
                        }
                        JournalExport(
                            experiences = experiencesSerializable,
                            substanceCompanions = experienceRepository.getAllSubstanceCompanions(),
                            customSubstances = experienceRepository.getAllCustomSubstances(),
                            customUnits = customUnitsSerializable,
                            toleranceCustomizations = toleranceCustomizationsSerializable,
                            medicationSchedules = schedulesSerializable,
                            routineOccurrences = occurrencesSerializable
                        )
                    }
                    val jsonText = withContext(Dispatchers.Default) {
                        Json.encodeToString(journalExport)
                    }
                    withContext(Dispatchers.IO) {
                        fileSystemConnection.saveTextInUri(uri, text = jsonText)
                    }
                    emitSnackbar(R.string.settings_export_successful)
                } catch (_: Exception) {
                    emitSnackbar(R.string.settings_export_failed)
                }
            }
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            dataOperationMutex.withLock {
                try {
                    withContext(Dispatchers.IO) {
                        routineSchedulerManager.cancelAllAlarms()
                        experienceRepository.deleteEverything()
                        if (!toleranceCustomizationStore.clearAllCustomizations()) {
                            throw IOException("Failed to clear tolerance customizations")
                        }
                    }
                    emitSnackbar(R.string.settings_deleted_everything_snackbar)
                } catch (_: Exception) {
                    emitSnackbar(R.string.settings_delete_everything_failed)
                }
            }
        }
    }
}