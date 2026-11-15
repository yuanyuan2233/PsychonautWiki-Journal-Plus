/*
 * Copyright (c) 2022. Isaak Hanimann.
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

package com.isaakhanimann.journal.ui.tabs.journal.addingestion.interactions

import android.content.Context
import androidx.compose.runtime.getValue
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
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.routine.SmartExperienceSelector
import com.isaakhanimann.journal.data.substances.EnzymaticDdiFinding
import com.isaakhanimann.journal.data.substances.UnifiedSubstanceResolver
import com.isaakhanimann.journal.data.substances.classes.InteractionType
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepository
import com.isaakhanimann.journal.ui.main.navigation.graphs.CheckInteractionsRoute
import com.isaakhanimann.journal.ui.tabs.journal.experience.components.getDurationText
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import com.isaakhanimann.journal.ui.tabs.settings.combinations.CombinationSettingsStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class CheckInteractionsViewModel @Inject constructor(
    private val substanceRepo: SubstanceRepository,
    private val experienceRepo: ExperienceRepository,
    private val combinationSettingsStorage: CombinationSettingsStorage,
    private val interactionChecker: InteractionChecker,
    private val unifiedSubstanceResolver: UnifiedSubstanceResolver,
    private val routineDao: RoutineDao,
    state: SavedStateHandle,
) : ViewModel() {
    private val checkInteractionsRoute = state.toRoute<CheckInteractionsRoute>()
    val substanceName = checkInteractionsRoute.substanceName
    var substance by mutableStateOf(
        unifiedSubstanceResolver.resolveSubstance(substanceName = substanceName)
            ?: createFallbackSubstance(substanceName)
    )
        private set
    val dangerousInteractions: List<String>
        get() = substance.interactions?.dangerous ?: emptyList()
    val unsafeInteractions: List<String>
        get() = substance.interactions?.unsafe ?: emptyList()
    val uncertainInteractions: List<String>
        get() = substance.interactions?.uncertain ?: emptyList()

    var isSearchingForInteractions by mutableStateOf(true)
    var isShowingAlert by mutableStateOf(false)
    var alertInteractionType by mutableStateOf<InteractionType?>(null)
    var alertItems by mutableStateOf<List<AlertItem>>(emptyList())
    private var allCustomSubstances: List<CustomSubstance> = emptyList()
    private var resolvedSubstancesByName: Map<String, Substance> = emptyMap()
    private var latestActiveIngestions: List<Ingestion> = emptyList()

    sealed interface AlertItem {
        val type: InteractionType
        data class IngestionAlert(override val type: InteractionType, val ingestion: Ingestion) : AlertItem
        data class ExtraAlert(override val type: InteractionType, val extra: String) : AlertItem
        data class EnzymaticDdiAlert(
            override val type: InteractionType,
            val finding: EnzymaticDdiFinding,
            val relatedIngestion: Ingestion? = null,
        ) : AlertItem
    }

    init {
        viewModelScope.launch {
            allCustomSubstances = experienceRepo.getAllCustomSubstances()
            unifiedSubstanceResolver.resolveSubstance(
                substanceName = substanceName,
                customSubstances = allCustomSubstances,
            )?.let { resolvedTarget ->
                substance = resolvedTarget
            }

            val now = Instant.now()
            val lookbackCutoff = now.minus(
                SmartExperienceSelector.MAX_INTERACTION_LOOKBACK_HOURS,
                ChronoUnit.HOURS
            )
            val candidates = experienceRepo.getLatestIngestionOfEverySubstanceSinceDate(lookbackCutoff)
            val scheduleHalfLifeMap = mutableMapOf<Int, Double?>()
            candidates.mapNotNull { it.routineScheduleId }.distinct().forEach { scheduleId ->
                scheduleHalfLifeMap[scheduleId] = routineDao.getSchedule(scheduleId)?.customHalfLifeHours
            }

            val resolvedMap = linkedMapOf<String, Substance>()
            latestActiveIngestions = candidates.filter { ingestion ->
                val scheduleHalfLife = ingestion.routineScheduleId?.let { scheduleHalfLifeMap[it] }
                val ingSubstance = unifiedSubstanceResolver.resolveSubstance(
                    substanceName = ingestion.substanceName,
                    customSubstances = allCustomSubstances,
                    scheduleCustomHalfLifeHours = scheduleHalfLife,
                )
                if (ingSubstance != null) {
                    resolvedMap[ingestion.substanceName] = ingSubstance
                }
                val washoutHours = SmartExperienceSelector.resolveInteractionWashoutHours(
                    customHalfLifeHours = scheduleHalfLife,
                    substance = ingSubstance,
                    route = ingestion.administrationRoute
                )
                val cutoff = now.minusMillis((washoutHours * 3_600_000.0).toLong())
                !ingestion.time.isBefore(cutoff)
            }
            resolvedSubstancesByName = resolvedMap
            checkInteractionsAndMaybeShowAlert()
            isSearchingForInteractions = false
        }
    }

    private suspend fun checkInteractionsAndMaybeShowAlert() {
        val filteredIngestions = getIngestionsWithInteraction()
        val dangerousIngestions =
            filteredIngestions.filter { it.interactionType == InteractionType.DANGEROUS }
                .map { it.ingestion }.sortedByDescending { it.time }
        val unsafeIngestions =
            filteredIngestions.filter { it.interactionType == InteractionType.UNSAFE }
                .map { it.ingestion }.sortedByDescending { it.time }
        val uncertainIngestions =
            filteredIngestions.filter { it.interactionType == InteractionType.UNCERTAIN }
                .map { it.ingestion }.sortedByDescending { it.time }
        val enabledExtraInteractions =
            combinationSettingsStorage.enabledInteractionsFlow.first()
        val dangerousExtras =
            enabledExtraInteractions.filter {
                interactionChecker.matchesExtraInteraction(substance.interactions?.dangerous, it)
            }
        val unsafeExtras =
            enabledExtraInteractions.filter {
                interactionChecker.matchesExtraInteraction(substance.interactions?.unsafe, it)
            }
        val uncertainExtras =
            enabledExtraInteractions.filter {
                interactionChecker.matchesExtraInteraction(substance.interactions?.uncertain, it)
            }

        val enzymaticDdiAlerts = collectEnzymaticDdiAlerts(enabledExtraInteractions)
        val dangerousDdi = enzymaticDdiAlerts.filter { it.type == InteractionType.DANGEROUS }
        val unsafeDdi = enzymaticDdiAlerts.filter { it.type == InteractionType.UNSAFE }
        val uncertainDdi = enzymaticDdiAlerts.filter { it.type == InteractionType.UNCERTAIN }

        alertInteractionType =
            if (dangerousIngestions.isNotEmpty() || dangerousExtras.isNotEmpty() || dangerousDdi.isNotEmpty()) {
                InteractionType.DANGEROUS
            } else if (unsafeIngestions.isNotEmpty() || unsafeExtras.isNotEmpty() || unsafeDdi.isNotEmpty()) {
                InteractionType.UNSAFE
            } else if (uncertainIngestions.isNotEmpty() || uncertainExtras.isNotEmpty() || uncertainDdi.isNotEmpty()) {
                InteractionType.UNCERTAIN
            } else {
                return
            }
        val items = mutableListOf<AlertItem>()
        dangerousIngestions.forEach { items.add(AlertItem.IngestionAlert(InteractionType.DANGEROUS, it)) }
        dangerousExtras.forEach { items.add(AlertItem.ExtraAlert(InteractionType.DANGEROUS, it)) }
        dangerousDdi.forEach { items.add(it) }
        unsafeIngestions.forEach { items.add(AlertItem.IngestionAlert(InteractionType.UNSAFE, it)) }
        unsafeExtras.forEach { items.add(AlertItem.ExtraAlert(InteractionType.UNSAFE, it)) }
        unsafeDdi.forEach { items.add(it) }
        uncertainIngestions.forEach { items.add(AlertItem.IngestionAlert(InteractionType.UNCERTAIN, it)) }
        uncertainExtras.forEach { items.add(AlertItem.ExtraAlert(InteractionType.UNCERTAIN, it)) }
        uncertainDdi.forEach { items.add(it) }
        alertItems = items
        isShowingAlert = true
    }

    private fun collectEnzymaticDdiAlerts(
        enabledExtraInteractions: Collection<String>,
    ): List<AlertItem.EnzymaticDdiAlert> {
        val activeIngestionByName = linkedMapOf<String, Ingestion>()
        val coAdministeredSubstances = mutableListOf<Substance>()
        latestActiveIngestions
            .sortedByDescending { it.time }
            .forEach { ingestion ->
                val resolved = resolvedSubstancesByName[ingestion.substanceName] ?: return@forEach
                val key = resolved.name.lowercase(Locale.ROOT)
                if (!resolved.name.equals(substance.name, ignoreCase = true) &&
                    !activeIngestionByName.containsKey(key)
                ) {
                    activeIngestionByName[key] = ingestion
                    coAdministeredSubstances.add(resolved)
                }
            }

        enabledExtraInteractions.forEach { extra ->
            val resolvedExtra = unifiedSubstanceResolver.resolveSubstance(
                substanceName = extra,
                customSubstances = allCustomSubstances,
            ) ?: if (extra.equals("Grapefruit", ignoreCase = true)) {
                createFallbackSubstance("Grapefruit").copy(
                    commonNames = listOf("葡萄柚", "西柚", "Grapefruit juice")
                )
            } else {
                null
            }
            if (resolvedExtra != null &&
                !resolvedExtra.name.equals(substance.name, ignoreCase = true) &&
                coAdministeredSubstances.none { it.name.equals(resolvedExtra.name, ignoreCase = true) }
            ) {
                coAdministeredSubstances.add(resolvedExtra)
            }
        }

        return interactionChecker.getEnzymaticDdiBetween(
            targetSubstance = substance,
            coAdministeredSubstances = coAdministeredSubstances,
        ).distinctBy {
            "${it.perpetratorName.lowercase(Locale.ROOT)}|${it.victimName.lowercase(Locale.ROOT)}|${it.enzyme.lowercase(Locale.ROOT)}"
        }.map { finding ->
            val partnerName = if (finding.victimName.equals(substance.name, ignoreCase = true)) {
                finding.perpetratorName
            } else {
                finding.victimName
            }
            val relatedIngestion = activeIngestionByName[partnerName.lowercase(Locale.ROOT)]
                ?: latestActiveIngestions.firstOrNull {
                    it.substanceName.equals(partnerName, ignoreCase = true)
                }
            AlertItem.EnzymaticDdiAlert(
                type = classifyEnzymaticDdiSeverity(finding),
                finding = finding,
                relatedIngestion = relatedIngestion,
            )
        }
    }

    private fun classifyEnzymaticDdiSeverity(finding: EnzymaticDdiFinding): InteractionType {
        val isMarkedChange = finding.isProdrugActivationBlocked ||
            finding.halfLifeMultiplier >= 2.0 ||
            finding.halfLifeMultiplier <= 0.55 ||
            finding.activeMetaboliteMultiplier >= 2.0 ||
            finding.activeMetaboliteMultiplier <= 0.5
        return if (isMarkedChange) InteractionType.UNSAFE else InteractionType.UNCERTAIN
    }

    fun getFormattedAlertText(context: Context): String {
        val now = Instant.now()
        val isZh = SubstanceLocalizationHelper.isChinese()
        val messages = mutableListOf<String>()
        for (item in alertItems) {
            when (item) {
                is AlertItem.IngestionAlert -> {
                    val timeDiff = getDurationText(fromInstant = item.ingestion.time, toInstant = now, context = context)
                    val localizedName = SubstanceLocalizationHelper.getLocalizedName(item.ingestion.substanceName)
                    val strRes = when (item.type) {
                        InteractionType.DANGEROUS -> R.string.journal_alert_dangerous_with_time
                        InteractionType.UNSAFE -> R.string.journal_alert_unsafe_with_time
                        InteractionType.UNCERTAIN -> R.string.journal_alert_uncertain_with_time
                    }
                    val baseMsg = context.getString(strRes, localizedName, timeDiff)
                    val hoursAgo = Duration.between(item.ingestion.time, now).toHours()
                    val pk = resolvedSubstancesByName[item.ingestion.substanceName]?.pharmacokinetics
                        ?: substanceRepo.getSubstance(item.ingestion.substanceName)?.pharmacokinetics
                    val isLongWashout = hoursAgo > 48L && pk != null && (
                        pk.activeMetabolites.isNotEmpty() ||
                            pk.washoutHours != null ||
                            (pk.resolvedEffectiveHalfLifeHours ?: 0.0) >= 24.0
                        )
                    val msg = if (isLongWashout) {
                        baseMsg + context.getString(R.string.journal_alert_residual_washout_suffix)
                    } else {
                        baseMsg
                    }
                    messages.add(msg)
                }
                is AlertItem.ExtraAlert -> {
                    val localizedExtra = SubstanceLocalizationHelper.getLocalizedName(item.extra)
                    val strRes = when (item.type) {
                        InteractionType.DANGEROUS -> R.string.journal_alert_dangerous_extra
                        InteractionType.UNSAFE -> R.string.journal_alert_unsafe_extra
                        InteractionType.UNCERTAIN -> R.string.journal_alert_uncertain_extra
                    }
                    messages.add(context.getString(strRes, localizedExtra))
                }
                is AlertItem.EnzymaticDdiAlert -> {
                    val summary = if (isZh) item.finding.summaryZh else item.finding.summaryEn
                    val related = item.relatedIngestion
                    val msg = if (related != null) {
                        val timeDiff = getDurationText(fromInstant = related.time, toInstant = now, context = context)
                        context.getString(
                            R.string.journal_alert_cyp_ddi_with_time,
                            item.finding.enzyme,
                            timeDiff,
                            summary,
                        )
                    } else {
                        context.getString(
                            R.string.journal_alert_cyp_ddi_header,
                            item.finding.enzyme,
                            summary,
                        )
                    }
                    messages.add(msg)
                }
            }
        }
        return messages.distinct().joinToString(separator = "\n")
    }

    private fun getIngestionsWithInteraction(): List<IngestionInteraction> {
        val additionalSubstances = buildList {
            add(substance)
            addAll(resolvedSubstancesByName.values)
        }
        return latestActiveIngestions.mapNotNull { ingestion ->
            val interaction = interactionChecker.getInteractionBetween(
                aName = substanceName,
                bName = ingestion.substanceName,
                additionalSubstances = additionalSubstances,
            )
            if (interaction != null) {
                return@mapNotNull IngestionInteraction(
                    ingestion = ingestion,
                    interactionType = interaction.interactionType
                )
            } else {
                return@mapNotNull null
            }
        }
    }

    private fun createFallbackSubstance(name: String): Substance = Substance(
        name = name,
        commonNames = emptyList(),
        url = "",
        isApproved = false,
        tolerance = null,
        crossTolerances = emptyList(),
        addictionPotential = null,
        toxicities = emptyList(),
        categories = emptyList(),
        roas = emptyList(),
    )

    data class IngestionInteraction(
        val ingestion: Ingestion,
        val interactionType: InteractionType
    )


}