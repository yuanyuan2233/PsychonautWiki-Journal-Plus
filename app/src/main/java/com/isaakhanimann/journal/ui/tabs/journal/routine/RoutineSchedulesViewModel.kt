package com.isaakhanimann.journal.ui.tabs.journal.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.routine.RecurrenceCalculator
import com.isaakhanimann.journal.data.routine.RoutineNotificationHelper
import com.isaakhanimann.journal.data.routine.RoutineSchedulerManager
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ScheduleListItemUiState(
    val schedule: MedicationSchedule,
    val nextTriggerInstant: Instant?,
    val customUnitName: String?
)

@HiltViewModel
class RoutineSchedulesViewModel @Inject constructor(
    routineDao: RoutineDao,
    experienceRepository: ExperienceRepository,
    private val userPreferences: UserPreferences,
    private val schedulerManager: RoutineSchedulerManager,
    private val notificationHelper: RoutineNotificationHelper
) : ViewModel() {

    val schedulesFlow: StateFlow<List<ScheduleListItemUiState>> = combine(
        routineDao.getAllSchedulesFlow(),
        experienceRepository.getAllCustomUnitsFlow()
    ) { schedules, customUnits ->
        val unitMap = customUnits.associateBy { it.id }
        val now = Instant.now()
        schedules.map { schedule ->
            val next = if (schedule.isEnabled) {
                RecurrenceCalculator.getNextOccurrenceAfter(schedule, now)?.scheduledInstant
            } else {
                null
            }
            ScheduleListItemUiState(
                schedule = schedule,
                nextTriggerInstant = next,
                customUnitName = schedule.customUnitId?.let { unitMap[it]?.name }
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val catchUpDaysFlow: StateFlow<Int> = userPreferences.routineCatchUpDaysFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = UserPreferences.DEFAULT_ROUTINE_CATCH_UP_DAYS
    )

    private val _hasNotificationPermission = MutableStateFlow(notificationHelper.hasNotificationPermission())
    val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

    private val _canScheduleExactAlarms = MutableStateFlow(schedulerManager.canScheduleExactAlarms())
    val canScheduleExactAlarms: StateFlow<Boolean> = _canScheduleExactAlarms.asStateFlow()

    fun refreshPermissions() {
        _hasNotificationPermission.value = notificationHelper.hasNotificationPermission()
        _canScheduleExactAlarms.value = schedulerManager.canScheduleExactAlarms()
    }

    fun setScheduleEnabled(scheduleId: Int, enabled: Boolean) = viewModelScope.launch {
        schedulerManager.setScheduleEnabled(scheduleId, enabled)
    }

    fun deleteSchedule(schedule: MedicationSchedule) = viewModelScope.launch {
        schedulerManager.deleteSchedule(schedule)
    }

    fun setCatchUpDays(days: Int) = viewModelScope.launch {
        userPreferences.saveRoutineCatchUpDays(days)
        schedulerManager.reconcileAndRescheduleAll()
    }
}
