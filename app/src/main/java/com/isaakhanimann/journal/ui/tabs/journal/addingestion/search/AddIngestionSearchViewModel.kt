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

package com.isaakhanimann.journal.ui.tabs.journal.addingestion.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.isaakhanimann.journal.data.room.experiences.ExperienceRepository
import com.isaakhanimann.journal.data.room.experiences.entities.AdaptiveColor
import com.isaakhanimann.journal.data.room.experiences.entities.AppColor
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanionAndCustomUnit
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.repositories.SearchRepository
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepository
import com.isaakhanimann.journal.ui.tabs.journal.addingestion.search.suggestion.models.CustomUnitDoseSuggestion
import com.isaakhanimann.journal.ui.tabs.journal.addingestion.search.suggestion.models.DoseAndUnit
import com.isaakhanimann.journal.ui.tabs.journal.addingestion.search.suggestion.models.Suggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class AddIngestionSearchViewModel @Inject constructor(
    experienceRepo: ExperienceRepository,
    val substanceRepo: SubstanceRepository,
    private val searchRepo: SearchRepository,
) : ViewModel() {

    private val _searchTextFlow = MutableStateFlow("")
    val searchTextFlow = _searchTextFlow.asStateFlow()

    fun updateSearchText(searchText: String) {
        viewModelScope.launch {
            _searchTextFlow.emit(searchText)
        }
    }

    val filteredSubstancesFlow = combine(
        searchTextFlow,
        experienceRepo.getSortedLastUsedSubstanceNamesFlow(limit = 200)
    ) { searchText, recents ->
        return@combine searchRepo.getMatchingSubstances(
            searchText = searchText,
            filterCategories = emptyList(),
            recentlyUsedSubstanceNamesSorted = recents
        ).map { it.toSubstanceModel() }
    }.flowOn(Dispatchers.Default)
        .stateIn(
        initialValue = emptyList(),
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    private val customUnitsFlow = experienceRepo.getCustomUnitsFlow(false)

    val filteredCustomUnitsFlow = combine(
        customUnitsFlow,
        filteredSubstancesFlow,
        searchTextFlow
    ) { customUnit, filteredSubstances, searchText ->
        val filteredNameSet = filteredSubstances.mapTo(HashSet(filteredSubstances.size * 2)) { it.name }
        customUnit.filter { custom ->
            custom.substanceName in filteredNameSet || custom.name.contains(
                other = searchText,
                ignoreCase = true
            ) || custom.substanceName.contains(
                other = searchText,
                ignoreCase = true
            ) || custom.unit.contains(
                other = searchText,
                ignoreCase = true
            ) || custom.note.contains(other = searchText, ignoreCase = true)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(
        initialValue = emptyList(),
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )

    private val customSubstancesFlow = experienceRepo.getCustomSubstancesFlow()

    val filteredCustomSubstancesFlow =
        customSubstancesFlow.combine(searchTextFlow) { customSubstances, searchText ->
            customSubstances.filter { custom ->
                custom.name.contains(other = searchText, ignoreCase = true)
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(
            initialValue = emptyList(),
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000)
        )

    val filteredSuggestions: StateFlow<List<Suggestion>> = combine(
        experienceRepo.getSortedIngestionsWithSubstanceCompanionsFlow(limit = 1000),
        customSubstancesFlow,
        filteredSubstancesFlow,
        searchTextFlow
    ) { ingestions, customSubstances, filteredSubstances, searchText ->
        val suggestions = getSuggestions(ingestions, customSubstances)
        val filteredSubstanceNames = filteredSubstances.map { it.name }
        return@combine suggestions.filter { sug ->
            sug.isInSearch(
                searchText = searchText,
                substanceNames = filteredSubstanceNames
            )
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(
        initialValue = emptyList(),
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000)
    )


    private fun getSuggestions(
        ingestions: List<IngestionWithCompanionAndCustomUnit>,
        customSubstances: List<CustomSubstance>
    ): List<Suggestion> = buildSuggestions(
        ingestions = ingestions,
        customSubstances = customSubstances,
        resolveSubstance = substanceRepo::getSubstance
    )

    companion object {
        private const val MAX_DOSES_PER_SUGGESTION = 8

        internal fun buildSuggestions(
            ingestions: List<IngestionWithCompanionAndCustomUnit>,
            customSubstances: List<CustomSubstance>,
            resolveSubstance: (String) -> Substance?
        ): List<Suggestion> {
            val groupedBySubstance = ingestions.groupBy { it.ingestion.substanceName }
            val rawSuggestions = groupedBySubstance.flatMap { entry ->
                getSuggestionsForSubstance(
                    substanceName = entry.key,
                    ingestionsGroupedBySubstance = entry.value,
                    customSubstances = customSubstances,
                    resolveSubstance = resolveSubstance
                )
            }
            return mergeSuggestionsByStableKey(rawSuggestions)
        }

        private fun mergeSuggestionsByStableKey(candidates: List<Suggestion>): List<Suggestion> {
            if (candidates.isEmpty()) return emptyList()
            // 先按时间倒序合并，保证相同物质不同别名/语言记录合并后保留最新颜色与最近剂量序列
            val sortedCandidates = candidates.sortedByDescending { it.sortInstant }
            val mergedByKey = LinkedHashMap<String, Suggestion>(sortedCandidates.size)
            for (candidate in sortedCandidates) {
                val key = candidate.stableKey
                val existing = mergedByKey[key]
                mergedByKey[key] = if (existing == null) {
                    candidate
                } else {
                    mergePair(existing, candidate)
                }
            }
            return mergedByKey.values.sortedByDescending { it.sortInstant }
        }

        private fun mergePair(newer: Suggestion, older: Suggestion): Suggestion {
            val maxInstant = maxOf(newer.sortInstant, older.sortInstant)
            return when {
                newer is Suggestion.PureSubstanceSuggestion &&
                    older is Suggestion.PureSubstanceSuggestion -> {
                    newer.copy(
                        dosesAndUnit = (newer.dosesAndUnit + older.dosesAndUnit)
                            .distinctBy { it.comparatorValue }
                            .take(MAX_DOSES_PER_SUGGESTION),
                        sortInstant = maxInstant
                    )
                }

                newer is Suggestion.CustomUnitSuggestion &&
                    older is Suggestion.CustomUnitSuggestion -> {
                    newer.copy(
                        dosesAndUnit = (newer.dosesAndUnit + older.dosesAndUnit)
                            .distinctBy { it.comparatorValue }
                            .take(MAX_DOSES_PER_SUGGESTION),
                        sortInstant = maxInstant
                    )
                }

                newer is Suggestion.CustomSubstanceSuggestion &&
                    older is Suggestion.CustomSubstanceSuggestion -> {
                    newer.copy(
                        dosesAndUnit = (newer.dosesAndUnit + older.dosesAndUnit)
                            .distinctBy { it.comparatorValue }
                            .take(MAX_DOSES_PER_SUGGESTION),
                        sortInstant = maxInstant
                    )
                }

                else -> newer
            }
        }

        private fun getSuggestionsForSubstance(
            substanceName: String,
            ingestionsGroupedBySubstance: List<IngestionWithCompanionAndCustomUnit>,
            customSubstances: List<CustomSubstance>,
            resolveSubstance: (String) -> Substance?
        ): List<Suggestion> {
            val color = ingestionsGroupedBySubstance
                .firstNotNullOfOrNull { it.substanceCompanion?.color }
                ?: return emptyList()
            val substance = resolveSubstance(substanceName)
            val isPredefinedSubstance = substance != null
            val customSubstance = customSubstances.firstOrNull { it.name == substanceName }
            val groupedRoute =
                ingestionsGroupedBySubstance.groupBy { it.ingestion.administrationRoute }
            if (!isPredefinedSubstance && customSubstance == null) {
                return emptyList()
            }
            return groupedRoute.flatMap { routeEntry ->
                val administrationRoute = routeEntry.key
                val ingestionsForSubstanceAndRoute = routeEntry.value

                val customUnitSuggestions = getCustomUnitSuggestionsForSubstance(
                    ingestionsForSubstanceAndRoute = ingestionsForSubstanceAndRoute,
                    color = color
                )
                val results = mutableListOf<Suggestion>()
                results.addAll(customUnitSuggestions)
                if (substance != null) {
                    getPureSubstanceSuggestion(
                        ingestionsForSubstanceAndRoute = ingestionsForSubstanceAndRoute,
                        substance = substance,
                        administrationRoute = administrationRoute,
                        color = color
                    )?.let(results::add)
                }
                if (customSubstance != null) {
                    getCustomSubstanceSuggestion(
                        ingestionsForSubstanceAndRoute = ingestionsForSubstanceAndRoute,
                        customSubstance = customSubstance,
                        administrationRoute = administrationRoute,
                        color = color
                    )?.let(results::add)
                }
                results
            }
        }

        private fun getCustomUnitSuggestionsForSubstance(
            ingestionsForSubstanceAndRoute: List<IngestionWithCompanionAndCustomUnit>,
            color: AppColor
        ): List<Suggestion.CustomUnitSuggestion> {
            val groupedByUnit = ingestionsForSubstanceAndRoute
                .filter { it.customUnit != null && !(it.customUnit?.isArchived ?: true) }
                .groupBy { it.customUnit }
            return groupedByUnit.mapNotNull { unitGroup ->
                val customUnit = unitGroup.key ?: return@mapNotNull null
                val ingestionsWithUnit = unitGroup.value
                val dosesAndUnit = ingestionsWithUnit.map { it.ingestion }.map { ingestion ->
                    CustomUnitDoseSuggestion(
                        dose = ingestion.dose,
                        isEstimate = ingestion.isDoseAnEstimate,
                        estimatedDoseStandardDeviation = ingestion.estimatedDoseStandardDeviation
                    )
                }.distinctBy { it.comparatorValue }.take(MAX_DOSES_PER_SUGGESTION)
                if (dosesAndUnit.isEmpty()) {
                    return@mapNotNull null
                }
                Suggestion.CustomUnitSuggestion(
                    customUnit = customUnit,
                    adaptiveColor = color,
                    dosesAndUnit = dosesAndUnit,
                    sortInstant = ingestionsWithUnit.mapNotNull { it.ingestion.creationDate }
                        .maxOfOrNull { it } ?: Instant.MIN
                )
            }
        }

        private fun getPureSubstanceSuggestion(
            ingestionsForSubstanceAndRoute: List<IngestionWithCompanionAndCustomUnit>,
            substance: Substance,
            administrationRoute: AdministrationRoute,
            color: AppColor
        ): Suggestion.PureSubstanceSuggestion? {
            val ingestionsToConsider = ingestionsForSubstanceAndRoute
                .filter { it.customUnit == null }
                .map { it.ingestion }
            if (ingestionsToConsider.isEmpty()) {
                return null
            }
            val dosesAndUnit = buildDoseSuggestions(ingestionsToConsider)
            return Suggestion.PureSubstanceSuggestion(
                administrationRoute = administrationRoute,
                substanceName = substance.name,
                adaptiveColor = color,
                dosesAndUnit = dosesAndUnit,
                sortInstant = ingestionsToConsider.mapNotNull { it.creationDate }
                    .maxOfOrNull { it } ?: Instant.MIN
            )
        }

        private fun getCustomSubstanceSuggestion(
            ingestionsForSubstanceAndRoute: List<IngestionWithCompanionAndCustomUnit>,
            customSubstance: CustomSubstance,
            administrationRoute: AdministrationRoute,
            color: AppColor
        ): Suggestion.CustomSubstanceSuggestion? {
            val ingestionsToConsider = ingestionsForSubstanceAndRoute
                .filter { it.customUnit == null }
                .map { it.ingestion }
            if (ingestionsToConsider.isEmpty()) {
                return null
            }
            val dosesAndUnit = buildDoseSuggestions(ingestionsToConsider)
            return Suggestion.CustomSubstanceSuggestion(
                administrationRoute = administrationRoute,
                customSubstance = customSubstance,
                adaptiveColor = color,
                dosesAndUnit = dosesAndUnit,
                sortInstant = ingestionsToConsider.mapNotNull { it.creationDate }
                    .maxOfOrNull { it } ?: Instant.MIN
            )
        }

        private fun buildDoseSuggestions(ingestions: List<com.isaakhanimann.journal.data.room.experiences.entities.Ingestion>): List<DoseAndUnit> {
            return ingestions.mapNotNull { ingestion ->
                val unit = ingestion.units ?: return@mapNotNull null
                DoseAndUnit(
                    dose = ingestion.dose,
                    unit = unit,
                    isEstimate = ingestion.isDoseAnEstimate,
                    estimatedDoseStandardDeviation = ingestion.estimatedDoseStandardDeviation
                )
            }.distinctBy { it.comparatorValue }.take(MAX_DOSES_PER_SUGGESTION)
        }
    }
}