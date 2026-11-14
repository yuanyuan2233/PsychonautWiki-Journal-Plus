/*
 * Copyright (c) 2026. PsychonautWiki Journal Chinese Maintenance Branch.
 */

package com.isaakhanimann.journal.ui.tabs.stats.tolerance

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestionsAndCompanions
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import javax.inject.Inject

enum class ToleranceTimeRange(@StringRes val labelRes: Int, val days: Long) {
    ONE_MONTH(R.string.tolerance_range_1m, 30),
    THREE_MONTHS(R.string.tolerance_range_3m, 90),
    SIX_MONTHS(R.string.tolerance_range_6m, 180),
    ONE_YEAR(R.string.tolerance_range_1y, 365)
}

data class ToleranceUiState(
    val isLoading: Boolean = true,
    val selectedRange: ToleranceTimeRange = ToleranceTimeRange.THREE_MONTHS,
    val toleranceWindows: List<ToleranceWindow> = emptyList(),
    val simulatedIngestions: List<SubstanceAndDay> = emptyList(),
    val customizations: Map<String, SubstanceToleranceCustomization> = emptyMap(),
    val substancesWithNoToleranceData: List<String> = emptyList()
)

@HiltViewModel
class ToleranceViewModel @Inject constructor(
    private val experienceRepository: ExperienceRepository,
    private val substanceRepo: SubstanceRepositoryInterface,
    private val customizationStore: ToleranceCustomizationStore
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(ToleranceTimeRange.THREE_MONTHS)
    private val _simulatedIngestions = MutableStateFlow<List<SubstanceAndDay>>(emptyList())
    private val _customizations = MutableStateFlow<Map<String, SubstanceToleranceCustomization>>(emptyMap())

    private val _uiState = MutableStateFlow(ToleranceUiState())
    val uiState: StateFlow<ToleranceUiState> = _uiState.asStateFlow()

    init {
        loadCustomizations()
        observeData()
    }

    private fun loadCustomizations() {
        _customizations.value = customizationStore.getAllCustomizations()
    }

    private fun observeData() {
        viewModelScope.launch {
            combine(
                experienceRepository.getSortedExperiencesWithIngestionsAndCustomUnitsFlow(),
                _selectedRange,
                _simulatedIngestions,
                _customizations
            ) { experiencesWithIngestions: List<ExperienceWithIngestionsAndCompanions>,
                range: ToleranceTimeRange,
                simulated: List<SubstanceAndDay>,
                customMap: Map<String, SubstanceToleranceCustomization> ->

                val now = Instant.now()
                val fromInstant = now.minus(range.days, ChronoUnit.DAYS)
                val zoneId = ZoneId.systemDefault()
                // 考虑长半衰期耐受（如 MDMA 归零约需 75 天）及用户自定义长周期（最长 3650 天），动态扩展回溯窗口
                val maxCustomLookbackDays = customMap.values
                    .mapNotNull { it.customZeroDays }
                    .filter { it.isFinite() && it > 0.0 }
                    .maxOrNull()
                    ?.let { ceil(it).toLong() }
                    ?: 0L
                val lookbackLimit = fromInstant.minus(maxCustomLookbackDays.coerceIn(120L, 3650L), ChronoUnit.DAYS)

                // 提取用户历史上的真实摄入（>= lookbackLimit），按时间升序归并同一本地日历日以避免 UTC 跨日偏移与冲突
                val realIngestions = experiencesWithIngestions.flatMap { exp ->
                    exp.ingestionsWithCompanionAndCustomUnit
                        .map { it.ingestion }
                        .filter { ing -> !ing.time.isBefore(lookbackLimit) }
                        .map { ing ->
                            SubstanceAndDay(
                                substanceName = ing.substanceName,
                                time = ing.time
                            )
                        }
                }

                val allIngestionPairs = (realIngestions + simulated)
                    .sortedBy { it.time }
                    .distinctBy { Pair(it.substanceName, it.time.atZone(zoneId).toLocalDate()) }

                // 后台协程执行核心药代算法与双轨自适应计算
                val (windows, noDataList) = withContext(Dispatchers.Default) {
                    val companions = experienceRepository.getAllSubstanceCompanions()
                    val calculatedAllWindows = ToleranceCalculator.getToleranceWindows(
                        substanceAndDays = allIngestionPairs,
                        substanceCompanions = companions,
                        substanceRepo = substanceRepo,
                        customizations = customMap
                    )
                    val visibleWindows = calculatedAllWindows.filter { window ->
                        // 窗口终点在所选时间范围起点之后，即在此时间段内仍处于耐受期
                        window.end.isAfter(fromInstant)
                    }

                    // 仅当物质本身完全没有耐受模型时才列入 missing（排除“已有模型但在当前窗口前已归零”的误报）
                    val allWindowNames = calculatedAllWindows.map { it.substanceName }.toSet()
                    val recentNames = (realIngestions.filter { !it.time.isBefore(fromInstant) } + simulated)
                        .map { it.substanceName }
                        .toSet()
                    val missing = recentNames.minus(allWindowNames).sorted()

                    Pair(visibleWindows, missing)
                }

                ToleranceUiState(
                    isLoading = false,
                    selectedRange = range,
                    toleranceWindows = windows,
                    simulatedIngestions = simulated,
                    customizations = customMap,
                    substancesWithNoToleranceData = noDataList
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun selectRange(range: ToleranceTimeRange) {
        _selectedRange.value = range
    }

    /**
     * 模拟添加一次计划摄入（推演未来耐受变化）
     */
    fun addSimulatedIngestion(substanceName: String, time: Instant = Instant.now()) {
        val trimmed = substanceName.trim()
        if (trimmed.isEmpty()) return
        val canonical = com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
            .getCanonicalEnglishName(trimmed)
            ?: trimmed
        val resolvedName = substanceRepo.getSubstance(canonical)?.name ?: canonical
        _simulatedIngestions.value = _simulatedIngestions.value + SubstanceAndDay(resolvedName, time)
    }

    fun clearSimulatedIngestions() {
        _simulatedIngestions.value = emptyList()
    }

    /**
     * 保存用户对某种物质的主观耐受自适应调整（体感覆盖）
     */
    fun saveCustomization(customization: SubstanceToleranceCustomization) {
        customizationStore.saveCustomization(customization)
        loadCustomizations()
    }

    /**
     * 一键标记该物质今天已恢复基线（完全归零）
     */
    fun markRecoveredToday(substanceName: String) {
        val existing = customizationStore.getCustomization(substanceName)
            ?: SubstanceToleranceCustomization(substanceName)
        val updated = existing.copy(manualResetInstant = Instant.now())
        customizationStore.saveCustomization(updated)
        loadCustomizations()
    }

    /**
     * 重置为系统默认药代理论模型
     */
    fun resetToDefault(substanceName: String) {
        customizationStore.resetToDefault(substanceName)
        loadCustomizations()
    }
}
