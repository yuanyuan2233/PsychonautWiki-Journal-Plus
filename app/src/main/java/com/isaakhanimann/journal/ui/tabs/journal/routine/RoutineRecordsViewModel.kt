package com.isaakhanimann.journal.ui.tabs.journal.routine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanionAndCustomUnit
import com.isaakhanimann.journal.data.routine.RoutineSchedulerManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class RoutineRecordsFilter {
    ALL,
    UNCONFIRMED,
    COPY_MEMO
}

data class RoutineRecordUiItem(
    val ingestionWithDetails: IngestionWithCompanionAndCustomUnit,
    val parentExperience: Experience?,
    val isSameDayCopyMemo: Boolean
)

@HiltViewModel
class RoutineRecordsViewModel @Inject constructor(
    private val routineDao: RoutineDao,
    private val schedulerManager: RoutineSchedulerManager
) : ViewModel() {

    private val _filter = MutableStateFlow(RoutineRecordsFilter.ALL)
    val filter: StateFlow<RoutineRecordsFilter> = _filter.asStateFlow()

    val recordsFlow: StateFlow<List<RoutineRecordUiItem>> = combine(
        routineDao.getRoutineIngestionsWithDetailsFlow(),
        routineDao.getAllExperiencesSimpleFlow(),
        _filter
    ) { ingestions, experiences, currentFilter ->
        val expMap = experiences.associateBy { it.id }
        ingestions.map { item ->
            val parent = expMap[item.ingestion.experienceId]
            val isMemo = parent != null && !parent.isRoutineOnly
            RoutineRecordUiItem(
                ingestionWithDetails = item,
                parentExperience = parent,
                isSameDayCopyMemo = isMemo
            )
        }.filter { record ->
            when (currentFilter) {
                RoutineRecordsFilter.ALL -> true
                RoutineRecordsFilter.UNCONFIRMED -> !record.ingestionWithDetails.ingestion.isConfirmed
                RoutineRecordsFilter.COPY_MEMO -> record.isSameDayCopyMemo
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val unconfirmedCountFlow: StateFlow<Int> = routineDao.getUnconfirmedRoutineCountFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    fun setFilter(filter: RoutineRecordsFilter) {
        _filter.value = filter
    }

    fun confirmIngestion(ingestionId: Int) = viewModelScope.launch {
        schedulerManager.confirmDose(ingestionId)
    }

    fun skipIngestion(ingestionId: Int) = viewModelScope.launch {
        schedulerManager.skipOrDeleteRoutineIngestion(ingestionId)
    }

    fun confirmAllUnconfirmed() = viewModelScope.launch {
        schedulerManager.confirmAllUnconfirmed()
    }

    fun syncNow() = viewModelScope.launch {
        schedulerManager.reconcileAndRescheduleAll()
    }
}
