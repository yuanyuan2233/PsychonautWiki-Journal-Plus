package com.isaakhanimann.journal.ui.tabs.journal.routine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleSlotCodec
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import com.isaakhanimann.journal.data.routine.RecurrenceCalculator
import com.isaakhanimann.journal.data.routine.RoutineSchedulerManager
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepository
import com.isaakhanimann.journal.ui.main.navigation.graphs.EditRoutineScheduleRoute
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.Inject

data class SlotDraftUiState(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int = 8,
    val minute: Int = 0,
    val doseText: String = "",
    val unitsText: String = "mg",
    val isEstimate: Boolean = false,
    val sdText: String = "",
    val noteText: String = ""
)

@HiltViewModel
class EditRoutineScheduleViewModel @Inject constructor(
    private val routineDao: RoutineDao,
    private val experienceRepository: ExperienceRepository,
    private val substanceRepository: SubstanceRepository,
    private val schedulerManager: RoutineSchedulerManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val routeArgs = savedStateHandle.toRoute<EditRoutineScheduleRoute>()
    val isEditMode: Boolean = routeArgs.scheduleId != null

    val builtInSubstances: List<Substance> = substanceRepository.getAllSubstances()

    val customSubstancesFlow: StateFlow<List<CustomSubstance>> =
        experienceRepository.getCustomSubstancesFlow().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val customUnitsFlow: StateFlow<List<CustomUnit>> =
        experienceRepository.getCustomUnitsFlow(isArchived = false).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    var consumerSuggestions by mutableStateOf<List<String>>(emptyList())
        private set

    var substanceName by mutableStateOf("")
    var selectedRoute by mutableStateOf(AdministrationRoute.ORAL)
    var selectedCustomUnit by mutableStateOf<CustomUnit?>(null)
    var consumerNameText by mutableStateOf("")
    var isEnabled by mutableStateOf(true)
    var notifyOnTrigger by mutableStateOf(true)
    var recurrenceType by mutableStateOf(RecurrenceType.DAILY)
    var daysOfWeekMask by mutableIntStateOf(MedicationSchedule.ALL_DAYS_MASK)
    var intervalDaysText by mutableStateOf("2")
    var intervalHoursText by mutableStateOf("8")
    var intervalExtraMinutesText by mutableStateOf("0")
    var anchorHour by mutableIntStateOf(8)
    var anchorMinute by mutableIntStateOf(0)
    var anchorDateEpochDay by mutableStateOf(LocalDate.now().toEpochDay())
    var cycleOnDaysText by mutableStateOf("5")
    var cycleOffDaysText by mutableStateOf("2")
    var startEpochDay by mutableStateOf<Long?>(null)
    var endEpochDay by mutableStateOf<Long?>(null)
    var maxOccurrencesText by mutableStateOf("")
    var customHalfLifeText by mutableStateOf("")
    var scheduleNoteText by mutableStateOf("")
    var slots by mutableStateOf(listOf(SlotDraftUiState()))
    var validationErrorRes by mutableStateOf<Int?>(null)
        private set
    private var existingCreatedAt: Instant? = null

    init {
        viewModelScope.launch {
            consumerSuggestions = experienceRepository.getSortedIngestions(200)
                .first()
                .mapNotNull { it.consumerName?.takeIf { n -> n.isNotBlank() } }
                .distinct()

            val scheduleId = routeArgs.scheduleId
            if (scheduleId != null) {
                routineDao.getSchedule(scheduleId)?.let { existing ->
                    loadExistingSchedule(existing)
                }
            } else {
                prefillFromRouteArgs()
            }
        }
    }

    private suspend fun loadExistingSchedule(schedule: MedicationSchedule) {
        existingCreatedAt = schedule.createdAt
        substanceName = schedule.substanceName
        selectedRoute = schedule.administrationRoute
        selectedCustomUnit = schedule.customUnitId?.let { experienceRepository.getCustomUnit(it) }
        consumerNameText = schedule.consumerName.orEmpty()
        isEnabled = schedule.isEnabled
        notifyOnTrigger = schedule.notifyOnTrigger
        recurrenceType = schedule.recurrenceType
        daysOfWeekMask = schedule.daysOfWeekMask
        intervalDaysText = schedule.intervalDays.toString()
        val mins = schedule.intervalMinutes.coerceAtLeast(1)
        intervalHoursText = (mins / 60).toString()
        intervalExtraMinutesText = (mins % 60).toString()
        val anchorZdt = Instant.ofEpochMilli(schedule.anchorEpochMillis).atZone(ZoneId.systemDefault())
        anchorDateEpochDay = anchorZdt.toLocalDate().toEpochDay()
        anchorHour = anchorZdt.hour
        anchorMinute = anchorZdt.minute
        cycleOnDaysText = schedule.cycleOnDays.toString()
        cycleOffDaysText = schedule.cycleOffDays.toString()
        startEpochDay = schedule.startEpochDay
        endEpochDay = schedule.endEpochDay
        maxOccurrencesText = schedule.maxOccurrences?.toString().orEmpty()
        customHalfLifeText = schedule.customHalfLifeHours?.toString().orEmpty()
        scheduleNoteText = schedule.note.orEmpty()
        val decoded = schedule.decodedSlots()
        slots = if (decoded.isEmpty()) {
            listOf(SlotDraftUiState())
        } else {
            decoded.map { s ->
                SlotDraftUiState(
                    id = s.id,
                    hour = s.hour,
                    minute = s.minute,
                    doseText = s.dose?.let { d -> if (d % 1.0 == 0.0) d.toInt().toString() else d.toString() }.orEmpty(),
                    unitsText = s.units.orEmpty(),
                    isEstimate = s.isDoseAnEstimate,
                    sdText = s.estimatedDoseStandardDeviation?.toString().orEmpty(),
                    noteText = s.notes.orEmpty()
                )
            }
        }
    }

    private suspend fun prefillFromRouteArgs() {
        val unitId = routeArgs.initialCustomUnitId
        if (unitId != null) {
            val unit = experienceRepository.getCustomUnit(unitId)
            if (unit != null) {
                selectCustomUnit(unit)
                return
            }
        }
        val subName = routeArgs.initialSubstanceName?.takeIf { it.isNotBlank() }
        if (subName != null) {
            val customSub = customSubstancesFlow.first().firstOrNull { it.name == subName }
            val route = routeArgs.initialRouteName
                ?.let { raw -> AdministrationRoute.entries.firstOrNull { it.name == raw } }
                ?: substanceRepository.getSubstance(subName)?.roas?.firstOrNull()?.route
                ?: AdministrationRoute.ORAL
            val defaultUnit = substanceRepository.getSubstance(subName)?.getRoa(route)?.roaDose?.units
                ?: customSub?.units
                ?: "mg"
            substanceName = subName
            selectedRoute = route
            slots = listOf(SlotDraftUiState(unitsText = defaultUnit))
        }
    }

    fun selectBuiltInOrCustomSubstance(name: String, defaultUnits: String?) {
        substanceName = name
        selectedCustomUnit = null
        val sub = substanceRepository.getSubstance(name)
        val firstRoa = sub?.roas?.firstOrNull()
        if (firstRoa != null) {
            selectedRoute = firstRoa.route
        }
        val suggestedUnit = firstRoa?.roaDose?.units ?: defaultUnits ?: "mg"
        slots = slots.map { slot ->
            if (slot.unitsText.isBlank() || slot.unitsText == "mg") slot.copy(unitsText = suggestedUnit) else slot
        }
        validationErrorRes = null
    }

    fun selectCustomUnit(unit: CustomUnit) {
        selectedCustomUnit = unit
        substanceName = unit.substanceName
        selectedRoute = unit.administrationRoute
        slots = slots.map { slot ->
            slot.copy(unitsText = unit.unit)
        }
        validationErrorRes = null
    }

    fun clearCustomUnit() {
        val currentSub = substanceName
        selectedCustomUnit = null
        val defaultUnit = substanceRepository.getSubstance(currentSub)
            ?.getRoa(selectedRoute)?.roaDose?.units ?: "mg"
        slots = slots.map { slot -> slot.copy(unitsText = defaultUnit) }
    }

    fun selectRoute(route: AdministrationRoute) {
        selectedRoute = route
        if (selectedCustomUnit != null && selectedCustomUnit?.administrationRoute != route) {
            selectedCustomUnit = null
        }
        val defaultUnit = substanceRepository.getSubstance(substanceName)
            ?.getRoa(route)?.roaDose?.units
        if (!defaultUnit.isNullOrBlank()) {
            slots = slots.map { if (it.unitsText.isBlank()) it.copy(unitsText = defaultUnit) else it }
        }
    }

    fun toggleWeekday(isoDayOfWeek: Int) {
        daysOfWeekMask = daysOfWeekMask xor MedicationSchedule.dayBit(isoDayOfWeek)
        validationErrorRes = null
    }

    fun addSlot() {
        val last = slots.lastOrNull()
        val nextHour = ((last?.hour ?: 8) + 4) % 24
        val nextUnits = selectedCustomUnit?.unit ?: last?.unitsText ?: "mg"
        slots = slots + SlotDraftUiState(
            hour = nextHour,
            minute = last?.minute ?: 0,
            doseText = last?.doseText.orEmpty(),
            unitsText = nextUnits
        )
    }

    fun updateSlot(index: Int, transform: (SlotDraftUiState) -> SlotDraftUiState) {
        if (index !in slots.indices) return
        val mutable = slots.toMutableList()
        mutable[index] = transform(mutable[index])
        slots = mutable
    }

    fun removeSlot(index: Int) {
        if (slots.size <= 1 || index !in slots.indices) return
        val mutable = slots.toMutableList()
        mutable.removeAt(index)
        slots = mutable
    }

    fun getPkHintData(): Pair<Double?, String?> {
        val sub = substanceRepository.getSubstance(substanceName) ?: return null to null
        val halfLife = sub.pharmacokinetics?.resolvedEffectiveHalfLifeHours
        val total = sub.getRoa(selectedRoute)?.roaDuration?.total?.text
        return halfLife to total
    }

    fun buildUpcomingPreview(count: Int = 3): List<Instant> {
        val draft = buildScheduleOrNull(previewOnly = true) ?: return emptyList()
        val results = mutableListOf<Instant>()
        var cursor = Instant.now()
        repeat(count) {
            val next = RecurrenceCalculator.getNextOccurrenceAfter(draft, cursor) ?: return results
            results.add(next.scheduledInstant)
            cursor = next.scheduledInstant
        }
        return results
    }

    private var isSaving = false

    fun saveSchedule(onSaved: () -> Unit) {
        if (isSaving) return
        val schedule = buildScheduleOrNull(previewOnly = false) ?: return
        isSaving = true
        viewModelScope.launch {
            try {
                schedulerManager.saveScheduleAndReschedule(schedule)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    onSaved()
                }
            } finally {
                isSaving = false
            }
        }
    }

    private fun parseLocaleDecimal(raw: String): Double? =
        raw.replace(',', '.').trim().toDoubleOrNull()

    private fun buildScheduleOrNull(previewOnly: Boolean): MedicationSchedule? {
        if (substanceName.isBlank()) {
            if (!previewOnly) validationErrorRes = R.string.routine_validation_need_substance
            return null
        }
        if (slots.isEmpty()) {
            if (!previewOnly) validationErrorRes = R.string.routine_validation_need_slot
            return null
        }
        if (recurrenceType == RecurrenceType.WEEKLY && (daysOfWeekMask and MedicationSchedule.ALL_DAYS_MASK) == 0) {
            if (!previewOnly) validationErrorRes = R.string.routine_validation_need_weekdays
            return null
        }
        val intervalDays = intervalDaysText.toIntOrNull() ?: 1
        val intervalHours = intervalHoursText.toIntOrNull() ?: 0
        val intervalExtraMins = intervalExtraMinutesText.toIntOrNull() ?: 0
        val totalIntervalMinutes = intervalHours * 60 + intervalExtraMins
        val cycleOn = cycleOnDaysText.toIntOrNull() ?: 1
        val cycleOff = cycleOffDaysText.toIntOrNull() ?: 0

        if ((recurrenceType == RecurrenceType.EVERY_N_DAYS && intervalDays <= 0) ||
            (recurrenceType == RecurrenceType.EVERY_N_MINUTES && totalIntervalMinutes <= 0) ||
            (recurrenceType == RecurrenceType.CYCLE_ON_OFF && cycleOn <= 0)
        ) {
            if (!previewOnly) validationErrorRes = R.string.routine_validation_bad_interval
            return null
        }

        val startDay = startEpochDay
        val endDay = endEpochDay
        if (startDay != null && endDay != null && startDay > endDay) {
            if (!previewOnly) validationErrorRes = R.string.routine_validation_bad_date_range
            return null
        }

        val parsedHalfLife = if (customHalfLifeText.isBlank()) {
            null
        } else {
            val h = parseLocaleDecimal(customHalfLifeText)
            if (h == null || !h.isFinite() || h <= 0.0) {
                if (!previewOnly) validationErrorRes = R.string.routine_validation_bad_number
                return null
            }
            h
        }

        val slotModels = ArrayList<ScheduleTimeSlot>(slots.size)
        for (slot in slots) {
            val parsedDose = if (slot.doseText.isBlank()) {
                null
            } else {
                val d = parseLocaleDecimal(slot.doseText)
                if (d == null || !d.isFinite() || d <= 0.0) {
                    if (!previewOnly) validationErrorRes = R.string.routine_validation_bad_number
                    return null
                }
                d
            }
            val parsedSd = if (slot.sdText.isBlank() || !slot.isEstimate) {
                null
            } else {
                val s = parseLocaleDecimal(slot.sdText)
                if (s == null || !s.isFinite() || s < 0.0) {
                    if (!previewOnly) validationErrorRes = R.string.routine_validation_bad_number
                    return null
                }
                s
            }
            slotModels.add(
                ScheduleTimeSlot(
                    id = slot.id,
                    hour = slot.hour.coerceIn(0, 23),
                    minute = slot.minute.coerceIn(0, 59),
                    dose = parsedDose,
                    units = slot.unitsText.trim().takeIf { it.isNotBlank() },
                    isDoseAnEstimate = slot.isEstimate,
                    estimatedDoseStandardDeviation = parsedSd,
                    notes = slot.noteText.trim().takeIf { it.isNotBlank() }
                )
            )
        }

        val now = Instant.now()
        val zoneId = ZoneId.systemDefault()
        val anchorEpochMillis = ZonedDateTime.of(
            LocalDate.ofEpochDay(anchorDateEpochDay),
            LocalTime.of(anchorHour.coerceIn(0, 23), anchorMinute.coerceIn(0, 59)),
            zoneId
        ).toInstant().toEpochMilli()

        val encodedSlots = ScheduleSlotCodec.encodeSlots(slotModels)

        if (!previewOnly) validationErrorRes = null
        return MedicationSchedule(
            id = routeArgs.scheduleId ?: 0,
            substanceName = substanceName.trim(),
            administrationRoute = selectedRoute,
            customUnitId = selectedCustomUnit?.id,
            consumerName = consumerNameText.trim().takeIf { it.isNotBlank() },
            isEnabled = isEnabled,
            notifyOnTrigger = notifyOnTrigger,
            recurrenceType = recurrenceType,
            daysOfWeekMask = daysOfWeekMask,
            intervalDays = intervalDays.coerceAtLeast(1),
            intervalMinutes = totalIntervalMinutes.coerceAtLeast(1),
            cycleOnDays = cycleOn.coerceAtLeast(1),
            cycleOffDays = cycleOff.coerceAtLeast(0),
            timeSlotsJson = encodedSlots,
            anchorEpochMillis = anchorEpochMillis,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay,
            maxOccurrences = maxOccurrencesText.toIntOrNull()?.takeIf { it > 0 },
            customHalfLifeHours = parsedHalfLife,
            note = scheduleNoteText.trim().takeIf { it.isNotBlank() },
            createdAt = existingCreatedAt ?: now,
            updatedAt = now
        )
    }

    fun matchesSearch(substance: Substance, query: String): Boolean {
        if (query.isBlank()) return true
        val zhName = SubstanceLocalizationHelper.getSubstanceDisplayName(substance.name)
        return substance.name.contains(query, ignoreCase = true) ||
            zhName.contains(query, ignoreCase = true) ||
            substance.commonNames.any { it.contains(query, ignoreCase = true) }
    }
}
