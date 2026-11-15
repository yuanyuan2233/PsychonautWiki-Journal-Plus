/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import com.isaakhanimann.journal.data.room.experiences.entities.AppColor
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.EnzymaticDdiFinding
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.round

enum class PkChartMode {
    SINGLE_DOSE,
    STEADY_STATE,
}

data class PkCurveSeries(
    val label: String,
    val isParent: Boolean,
    val isMetabolite: Boolean,
    val isTotalActive: Boolean,
    val points: List<Pair<Double, Double>>,
)

data class PkSimulationResult(
    val mode: PkChartMode,
    val totalHorizonHours: Double,
    val dosingIntervalHours: Double?,
    val steadyStateHoursEstimate: Double?,
    val series: List<PkCurveSeries>,
)

data class PkPoint(
    val hoursFromStart: Float,
    val normalizedValue: Float,
    val activeEquivalentMg: Double? = null,
)

data class RealDoseEvent(
    val substanceName: String,
    val timeEpochSecond: Long,
    val dose: Double?,
    val units: String? = null,
    val isFromCurrentExperience: Boolean,
    val isConfirmed: Boolean = true,
    val endTimeEpochSecond: Long? = null,
    val route: AdministrationRoute? = null,
    val bioavailabilityFactor: Double? = null,
)

data class LivePkSubstanceInput(
    val canonicalName: String,
    val displayName: String,
    val color: AppColor,
    val pk: Pharmacokinetics,
    val doseEvents: List<RealDoseEvent>,
    val unadjustedPk: Pharmacokinetics? = null,
    val ddiFindings: List<EnzymaticDdiFinding> = emptyList(),
)

data class LiveMetaboliteSeries(
    val name: String,
    val halfLifeHours: Double,
    val exposureRatio: Double,
    val points: List<PkPoint>,
    val currentNormalizedValue: Float?,
)

data class SubstanceLivePkProfile(
    val canonicalName: String,
    val displayName: String,
    val color: AppColor,
    val pk: Pharmacokinetics,
    val windowStartEpochSecond: Long,
    val windowEndEpochSecond: Long,
    val totalHorizonHours: Float,
    val currentIngestionCount: Int,
    val carryoverIngestionCount: Int,
    val lookbackDaysMax: Int,
    val doseEventsInWindow: List<RealDoseEvent>,
    val parentPoints: List<PkPoint>,
    val metaboliteSeries: List<LiveMetaboliteSeries>,
    val totalActivePoints: List<PkPoint>,
    val carryoverBaselineFractionAtStart: Float,
    val currentRelativeActiveLevel: Float?,
    val windowPeakRelativeToSingleDose: Float,
    val currentActiveEquivalentMg: Double? = null,
    val peakActiveEquivalentMg: Double? = null,
    val unadjustedTotalActivePoints: List<PkPoint> = emptyList(),
    val ddiFindings: List<EnzymaticDdiFinding> = emptyList(),
)

data class ExperienceLivePkSimulation(
    val windowStartEpochSecond: Long,
    val windowEndEpochSecond: Long,
    val currentTimeEpochSecond: Long?,
    val profiles: List<SubstanceLivePkProfile>,
)

object PkCurveCalculator {

    private val LN2 = ln(2.0)
    private const val ZERO_ORDER_KM = 0.08
    private const val ZERO_ORDER_DT_MAX = 0.025

    private data class MetParam(
        val name: String,
        val kfMet: Double,
        val keMet: Double,
        val ratio: Double,
        val halfLifeHours: Double,
    )

    private data class ResolvedPkModel(
        val parentHl: Double,
        val effHl: Double,
        val keParent: Double,
        val tMaxParent: Double,
        val kaParent: Double,
        val isProdrug: Boolean,
        val isExtendedRelease: Boolean,
        val isZeroOrder: Boolean,
        val mets: List<MetParam>,
    )

    private data class EventWithOffset(
        val event: RealDoseEvent,
        val offsetHours: Double,
        val weight: Double,
        val tMaxRoute: Double,
    )

    private data class MicroDoseEvent(
        val offsetHours: Double,
        val weight: Double,
        val tMaxRoute: Double,
        val kaRoute: Double,
        val route: AdministrationRoute?,
    )

    private fun resolveModel(
        pk: Pharmacokinetics,
        canonicalName: String? = null,
    ): ResolvedPkModel? {
        val parentHl = pk.halfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: return null
        val effHl = pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() } ?: parentHl
        val keParent = LN2 / parentHl
        val tMaxParent = pk.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: (parentHl * 0.2).coerceIn(0.25, 6.0)
        val kaParent = solveAbsorptionRate(ke = keParent, tMax = tMaxParent)
        val isZeroOrder = pk.pkRole == "ZERO_ORDER" ||
            canonicalName.equals("Alcohol", ignoreCase = true) ||
            canonicalName.equals("Ethanol", ignoreCase = true)
        val mets = pk.activeMetabolites
            .filter { (it.halfLifeHours ?: 0.0) > 0.0 && it.halfLifeHours!!.isFinite() }
            .map { met ->
                val metHl = met.halfLifeHours!!
                val keMet = LN2 / metHl
                val kfMet = met.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }?.let { tMaxMet ->
                    solveAbsorptionRate(ke = keMet, tMax = tMaxMet)
                } ?: keParent
                val ratio = (met.exposureRatio ?: 0.5).coerceIn(0.01, 25.0)
                MetParam(
                    name = met.name,
                    kfMet = kfMet,
                    keMet = keMet,
                    ratio = ratio,
                    halfLifeHours = metHl
                )
            }
        return ResolvedPkModel(
            parentHl = parentHl,
            effHl = effHl,
            keParent = keParent,
            tMaxParent = tMaxParent,
            kaParent = kaParent,
            isProdrug = pk.pkRole == "PRODRUG",
            isExtendedRelease = pk.pkRole == "EXTENDED_RELEASE",
            isZeroOrder = isZeroOrder,
            mets = mets,
        )
    }

    fun simulate(
        pk: Pharmacokinetics,
        parentLabel: String,
        totalLabel: String,
        mode: PkChartMode,
    ): PkSimulationResult? {
        val model = resolveModel(pk) ?: return null

        val dosingIntervalHours: Double?
        val doseTimes: List<Double>
        val horizonHours: Double

        if (mode == PkChartMode.SINGLE_DOSE) {
            dosingIntervalHours = null
            doseTimes = listOf(0.0)
            horizonHours = if (model.isExtendedRelease) {
                val tSlow = maxOf(model.tMaxParent * 1.6, 3.0)
                maxOf(model.effHl * 5.0, tSlow * 3.0, 6.0)
            } else {
                (model.effHl * 5.0).coerceAtLeast(6.0)
            }
        } else {
            val tau = resolveClinicalDosingIntervalHours(pk = pk, effHl = model.effHl)
            dosingIntervalHours = tau
            val targetHours = maxOf(model.effHl * 4.5, tau * 6.0)
            val numDoses = ceil(targetHours / tau).toInt().coerceIn(5, 60)
            horizonHours = numDoses * tau
            doseTimes = List(numDoses) { idx -> idx * tau }
        }

        val sampleTimes = buildSampleGrid(
            horizonHours = horizonHours,
            tMaxParent = model.tMaxParent,
            doseTimes = doseTimes,
            mode = mode,
            isExtendedRelease = model.isExtendedRelease,
        )

        val rawParent = if (model.isZeroOrder) {
            val micro = doseTimes.map { dTime ->
                MicroDoseEvent(
                    offsetHours = dTime,
                    weight = 1.0,
                    tMaxRoute = model.tMaxParent,
                    kaRoute = model.kaParent,
                    route = null,
                )
            }
            evaluateZeroOrderOnGrid(
                sampleHours = sampleTimes,
                microEvents = micro,
                model = model,
                extraQueryHours = emptyList(),
            ).first
        } else {
            DoubleArray(sampleTimes.size) { i ->
                val t = sampleTimes[i]
                var pVal = 0.0
                for (dTime in doseTimes) {
                    if (t < dTime) break
                    pVal += unitParentResponse(
                        dt = t - dTime,
                        tMaxRoute = model.tMaxParent,
                        kaRoute = model.kaParent,
                        model = model,
                        route = null,
                    )
                }
                pVal
            }
        }

        val rawMets = Array(model.mets.size) { DoubleArray(sampleTimes.size) }
        val rawTotal = DoubleArray(sampleTimes.size)

        for (i in sampleTimes.indices) {
            val t = sampleTimes[i]
            val pVal = rawParent[i]
            var totalVal = if (model.isProdrug) 0.0 else pVal
            for (mIdx in model.mets.indices) {
                val met = model.mets[mIdx]
                var mVal = 0.0
                for (dTime in doseTimes) {
                    if (t < dTime) break
                    mVal += met.ratio * unitBateman(t - dTime, met.kfMet, met.keMet)
                }
                rawMets[mIdx][i] = mVal
                totalVal += mVal
            }
            rawTotal[i] = totalVal
        }

        var maxVal = 0.0
        for (v in rawParent) if (v > maxVal) maxVal = v
        for (arr in rawMets) for (v in arr) if (v > maxVal) maxVal = v
        for (v in rawTotal) if (v > maxVal) maxVal = v
        if (maxVal <= 0.0 || !maxVal.isFinite()) return null

        val seriesList = mutableListOf<PkCurveSeries>()
        if (model.mets.isNotEmpty()) {
            seriesList.add(
                PkCurveSeries(
                    label = totalLabel,
                    isParent = false,
                    isMetabolite = false,
                    isTotalActive = true,
                    points = sampleTimes.indices.map { i -> sampleTimes[i] to (rawTotal[i] / maxVal).coerceIn(0.0, 1.0) }
                )
            )
        }
        seriesList.add(
            PkCurveSeries(
                label = parentLabel,
                isParent = true,
                isMetabolite = false,
                isTotalActive = false,
                points = sampleTimes.indices.map { i -> sampleTimes[i] to (rawParent[i] / maxVal).coerceIn(0.0, 1.0) }
            )
        )
        model.mets.forEachIndexed { mIdx, met ->
            seriesList.add(
                PkCurveSeries(
                    label = met.name,
                    isParent = false,
                    isMetabolite = true,
                    isTotalActive = false,
                    points = sampleTimes.indices.map { i -> sampleTimes[i] to (rawMets[mIdx][i] / maxVal).coerceIn(0.0, 1.0) }
                )
            )
        }

        val steadyStateEstimate = if (mode == PkChartMode.STEADY_STATE) {
            (model.effHl * 4.5).coerceAtMost(horizonHours)
        } else {
            null
        }

        return PkSimulationResult(
            mode = mode,
            totalHorizonHours = horizonHours,
            dosingIntervalHours = dosingIntervalHours,
            steadyStateHoursEstimate = steadyStateEstimate,
            series = seriesList,
        )
    }

    /**
     * 基于真实给药时间戳与过去 42 天历史给药记录的多剂次多态卷积（一级/双相缓释/零级饱和 + 区间积分 + CYP DDI 对比）：
     * 在当前日记时间窗 [windowStartEpochSecond, windowEndEpochSecond] 内重建母体、活性代谢物与总活性暴露曲线。
     */
    fun simulateExperienceLivePk(
        substances: List<LivePkSubstanceInput>,
        windowStartEpochSecond: Long,
        windowEndEpochSecond: Long,
        currentTimeEpochSecond: Long?,
        gridSteps: Int = 140,
        includeCarryover: Boolean = true,
    ): ExperienceLivePkSimulation {
        val safeEnd = windowEndEpochSecond.coerceAtLeast(windowStartEpochSecond + 3600L)
        val horizonHours = (safeEnd - windowStartEpochSecond) / 3600.0
        val nowOffsetHours = currentTimeEpochSecond
            ?.takeIf { it in windowStartEpochSecond..safeEnd }
            ?.let { (it - windowStartEpochSecond) / 3600.0 }

        val profiles = substances.mapNotNull { input ->
            val model = resolveModel(input.pk, input.canonicalName) ?: return@mapNotNull null
            val filteredEvents = if (includeCarryover) {
                input.doseEvents
            } else {
                input.doseEvents.filter { it.isFromCurrentExperience }
            }
            if (filteredEvents.isEmpty()) return@mapNotNull null

            val baselinePk = input.unadjustedPk ?: input.pk
            val baselineModel = input.unadjustedPk?.let { resolveModel(it, input.canonicalName) } ?: model
            val parentDdiScale = if (model.isZeroOrder || input.unadjustedPk == null) {
                1.0
            } else {
                (model.parentHl / baselineModel.parentHl).coerceIn(0.1, 10.0)
            }

            val doseSetup = prepareDoseWeightsAndReference(
                events = filteredEvents,
                pk = baselinePk,
            )

            val originalOffsets = filteredEvents.indices
                .map { idx ->
                    val ev = filteredEvents[idx]
                    val startOffset = (ev.timeEpochSecond - windowStartEpochSecond) / 3600.0
                    val tMaxRoute = input.pk.resolveTMaxHoursForRoute(ev.route)
                    EventWithOffset(
                        event = ev,
                        offsetHours = startOffset,
                        weight = doseSetup.weights[idx],
                        tMaxRoute = tMaxRoute,
                    )
                }
                .filter { it.offsetHours <= horizonHours }
                .sortedBy { it.offsetHours }
            if (originalOffsets.isEmpty()) return@mapNotNull null

            val priorOriginal = originalOffsets.filter { it.offsetHours < 0.0 }
            val inWindowOriginal = originalOffsets.filter { item ->
                val endOffset = item.event.endTimeEpochSecond
                    ?.takeIf { it > item.event.timeEpochSecond }
                    ?.let { (it - windowStartEpochSecond) / 3600.0 }
                    ?: item.offsetHours
                item.offsetHours in 0.0..horizonHours ||
                    endOffset in 0.0..horizonHours ||
                    (item.offsetHours < 0.0 && endOffset > horizonHours)
            }
            val currentExperienceOriginal = originalOffsets.filter { it.event.isFromCurrentExperience }

            val microEvents = expandEventsToMicroDoses(
                events = originalOffsets,
                horizonHours = horizonHours,
                pk = input.pk,
                model = model,
            )
            if (microEvents.isEmpty()) return@mapNotNull null
            val priorMicroEvents = microEvents.filter { it.offsetHours < 0.0 }

            val baselinePeaks = computeSingleDosePeaks(
                model = baselineModel,
                pk = baselinePk,
                dominantRoute = doseSetup.dominantRoute,
            )
            if (baselinePeaks.overallPeak <= 0.0 || !baselinePeaks.overallPeak.isFinite()) return@mapNotNull null

            val sampleHours = buildLiveWindowGrid(
                horizonHours = horizonHours,
                tMaxParent = model.tMaxParent,
                metTMaxList = model.mets.map { it.halfLifeHours to (1.0 / it.kfMet) },
                eventsInWindow = inWindowOriginal,
                windowStartEpochSecond = windowStartEpochSecond,
                nowOffsetHours = nowOffsetHours,
                uniformSteps = gridSteps
            )

            val adjustedEval = evaluateLiveTimeline(
                sampleHours = sampleHours,
                microEvents = microEvents,
                model = model,
                parentDdiScale = parentDdiScale,
                nowOffsetHours = nowOffsetHours,
                priorMicroEvents = priorMicroEvents,
            )

            val unadjustedEval = input.unadjustedPk?.let { unadjPk ->
                val unadjMicro = expandEventsToMicroDoses(
                    events = originalOffsets,
                    horizonHours = horizonHours,
                    pk = unadjPk,
                    model = baselineModel,
                )
                if (unadjMicro.isNotEmpty()) {
                    evaluateLiveTimeline(
                        sampleHours = sampleHours,
                        microEvents = unadjMicro,
                        model = baselineModel,
                        parentDdiScale = 1.0,
                        nowOffsetHours = null,
                        priorMicroEvents = emptyList(),
                    )
                } else {
                    null
                }
            }

            val windowMaxTotal = adjustedEval.rawTotal.maxOrNull() ?: 0.0
            var windowMaxOverall = maxOf(
                windowMaxTotal,
                adjustedEval.rawParent.maxOrNull() ?: 0.0,
            )
            for (arr in adjustedEval.rawMets) {
                val mMax = arr.maxOrNull() ?: 0.0
                if (mMax > windowMaxOverall) windowMaxOverall = mMax
            }
            val windowMaxUnadjustedTotal = unadjustedEval?.rawTotal?.maxOrNull() ?: 0.0
            if (windowMaxOverall <= 0.0 || !windowMaxOverall.isFinite()) return@mapNotNull null

            val hasCurrentDose = currentExperienceOriginal.isNotEmpty()
            val peakRelativeToSingleDose = if (baselinePeaks.activePeak > 0.0) {
                (windowMaxTotal / baselinePeaks.activePeak).toFloat()
            } else {
                1f
            }
            // 对于今日未新服药的纯跨日历史物质，仅当当前窗口峰值仍达单次峰值的 >= 2% 时保留展示
            if (!hasCurrentDose &&
                baselinePeaks.overallPeak > 0.0 &&
                (windowMaxTotal / baselinePeaks.overallPeak) < 0.02
            ) {
                return@mapNotNull null
            }

            val referenceScale = maxOf(
                baselinePeaks.overallPeak,
                windowMaxOverall,
                windowMaxUnadjustedTotal,
            )
            if (referenceScale <= 0.0 || !referenceScale.isFinite()) return@mapNotNull null

            val eqMgPerUnitResponse = if (doseSetup.canComputeEquivalentMg && baselinePeaks.activePeak > 0.0) {
                doseSetup.typicalAbsorbedMg / baselinePeaks.activePeak
            } else {
                null
            }

            val parentPoints = sampleHours.indices.map { idx ->
                PkPoint(
                    hoursFromStart = sampleHours[idx].toFloat(),
                    normalizedValue = (adjustedEval.rawParent[idx] / referenceScale).toFloat().coerceIn(0f, 1f)
                )
            }
            val totalPoints = sampleHours.indices.map { idx ->
                val rawT = adjustedEval.rawTotal[idx]
                PkPoint(
                    hoursFromStart = sampleHours[idx].toFloat(),
                    normalizedValue = (rawT / referenceScale).toFloat().coerceIn(0f, 1f),
                    activeEquivalentMg = eqMgPerUnitResponse?.let { (rawT * it).coerceAtLeast(0.0) }
                )
            }
            val unadjustedTotalPoints = if (unadjustedEval != null) {
                sampleHours.indices.map { idx ->
                    val rawU = unadjustedEval.rawTotal[idx]
                    PkPoint(
                        hoursFromStart = sampleHours[idx].toFloat(),
                        normalizedValue = (rawU / referenceScale).toFloat().coerceIn(0f, 1f),
                        activeEquivalentMg = eqMgPerUnitResponse?.let { (rawU * it).coerceAtLeast(0.0) }
                    )
                }
            } else {
                emptyList()
            }

            val metSeriesList = model.mets.mapIndexed { mIdx, met ->
                val points = sampleHours.indices.map { idx ->
                    PkPoint(
                        hoursFromStart = sampleHours[idx].toFloat(),
                        normalizedValue = (adjustedEval.rawMets[mIdx][idx] / referenceScale).toFloat().coerceIn(0f, 1f)
                    )
                }
                val nowMetNorm = adjustedEval.nowMets?.getOrNull(mIdx)?.let {
                    (it / referenceScale).toFloat().coerceIn(0f, 1f)
                }
                LiveMetaboliteSeries(
                    name = met.name,
                    halfLifeHours = met.halfLifeHours,
                    exposureRatio = met.ratio,
                    points = points,
                    currentNormalizedValue = nowMetNorm
                )
            }

            val carryoverBaselineFractionAtStart =
                (adjustedEval.carryoverAtStartTotal / referenceScale).toFloat().coerceIn(0f, 1f)
            val currentRelativeActiveLevel = adjustedEval.nowTotal?.let {
                (it / referenceScale).toFloat().coerceIn(0f, 1f)
            }
            val currentActiveEquivalentMg = if (eqMgPerUnitResponse != null && adjustedEval.nowTotal != null) {
                (adjustedEval.nowTotal * eqMgPerUnitResponse).coerceAtLeast(0.0)
            } else {
                null
            }
            val peakActiveEquivalentMg = if (eqMgPerUnitResponse != null) {
                totalPoints.mapNotNull { it.activeEquivalentMg }.maxOrNull()
            } else {
                null
            }
            val lookbackDaysMax = priorOriginal.maxOfOrNull {
                ceil((-it.offsetHours) / 24.0).toInt().coerceAtLeast(1)
            } ?: 0

            SubstanceLivePkProfile(
                canonicalName = input.canonicalName,
                displayName = input.displayName,
                color = input.color,
                pk = input.pk,
                windowStartEpochSecond = windowStartEpochSecond,
                windowEndEpochSecond = safeEnd,
                totalHorizonHours = horizonHours.toFloat(),
                currentIngestionCount = currentExperienceOriginal.size,
                carryoverIngestionCount = priorOriginal.size,
                lookbackDaysMax = lookbackDaysMax,
                doseEventsInWindow = inWindowOriginal.map { it.event },
                parentPoints = parentPoints,
                metaboliteSeries = metSeriesList,
                totalActivePoints = totalPoints,
                carryoverBaselineFractionAtStart = carryoverBaselineFractionAtStart,
                currentRelativeActiveLevel = currentRelativeActiveLevel,
                windowPeakRelativeToSingleDose = peakRelativeToSingleDose,
                currentActiveEquivalentMg = currentActiveEquivalentMg,
                peakActiveEquivalentMg = peakActiveEquivalentMg,
                unadjustedTotalActivePoints = unadjustedTotalPoints,
                ddiFindings = input.ddiFindings,
            )
        }.sortedWith(
            compareByDescending<SubstanceLivePkProfile> { it.currentIngestionCount > 0 }
                .thenByDescending { profile -> profile.totalActivePoints.maxOfOrNull { it.normalizedValue } ?: 0f }
        )

        return ExperienceLivePkSimulation(
            windowStartEpochSecond = windowStartEpochSecond,
            windowEndEpochSecond = safeEnd,
            currentTimeEpochSecond = currentTimeEpochSecond,
            profiles = profiles,
        )
    }

    private data class DoseWeightSetup(
        val weights: DoubleArray,
        val canComputeEquivalentMg: Boolean,
        val typicalAbsorbedMg: Double,
        val dominantRoute: AdministrationRoute?,
    )

    private fun prepareDoseWeightsAndReference(
        events: List<RealDoseEvent>,
        pk: Pharmacokinetics,
    ): DoseWeightSetup {
        val bioPerEvent = DoubleArray(events.size) { idx ->
            val ev = events[idx]
            ev.bioavailabilityFactor?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
                ?: pk.resolveBioavailabilityFraction(ev.route)
        }
        val mgValues = events.map { toMgOrNull(it.dose, it.units) }
        val allPositiveHaveMg = events.isNotEmpty() && events.indices.all { idx ->
            val mg = mgValues[idx]
            mg != null && mg > 0.0 && mg.isFinite()
        }
        if (allPositiveHaveMg) {
            val sortedIndices = events.indices.sortedBy { mgValues[it]!! }
            val medIdx = sortedIndices[sortedIndices.size / 2]
            val typicalMg = mgValues[medIdx]!!
            val typicalBio = bioPerEvent[medIdx]
            val typicalAbsorbed = (typicalMg * typicalBio).coerceAtLeast(1e-9)
            val weights = DoubleArray(events.size) { idx ->
                val absorbed = mgValues[idx]!! * bioPerEvent[idx]
                (absorbed / typicalAbsorbed).coerceIn(0.001, 1000.0)
            }
            return DoseWeightSetup(
                weights = weights,
                canComputeEquivalentMg = true,
                typicalAbsorbedMg = typicalAbsorbed,
                dominantRoute = events[medIdx].route,
            )
        }

        val distinctNonNullUnits = events
            .mapNotNull { it.units?.trim()?.lowercase(Locale.ROOT)?.takeIf { u -> u.isNotEmpty() } }
            .distinct()
        if (distinctNonNullUnits.size <= 1) {
            val positiveIndices = events.indices.filter { idx ->
                val d = events[idx].dose
                d != null && d > 0.0 && d.isFinite()
            }.sortedBy { events[it].dose!! }
            if (positiveIndices.isNotEmpty()) {
                val medIdx = positiveIndices[positiveIndices.size / 2]
                val typicalDose = events[medIdx].dose!!
                val typicalBio = bioPerEvent[medIdx]
                val weights = DoubleArray(events.size) { idx ->
                    val d = events[idx].dose
                    if (d != null && d > 0.0 && typicalDose > 0.0) {
                        ((d / typicalDose) * (bioPerEvent[idx] / typicalBio)).coerceIn(0.05, 25.0)
                    } else {
                        (bioPerEvent[idx] / typicalBio).coerceIn(0.05, 25.0)
                    }
                }
                return DoseWeightSetup(
                    weights = weights,
                    canComputeEquivalentMg = false,
                    typicalAbsorbedMg = 0.0,
                    dominantRoute = events[medIdx].route,
                )
            }
        }

        val sortedBios = bioPerEvent.sorted()
        val typicalBio = sortedBios[sortedBios.size / 2].coerceAtLeast(0.02)
        val dominantRoute = events.firstNotNullOfOrNull { it.route }
        val weights = DoubleArray(events.size) { idx ->
            (bioPerEvent[idx] / typicalBio).coerceIn(0.05, 25.0)
        }
        return DoseWeightSetup(
            weights = weights,
            canComputeEquivalentMg = false,
            typicalAbsorbedMg = 0.0,
            dominantRoute = dominantRoute,
        )
    }

    private fun expandEventsToMicroDoses(
        events: List<EventWithOffset>,
        horizonHours: Double,
        pk: Pharmacokinetics,
        model: ResolvedPkModel,
    ): List<MicroDoseEvent> {
        val result = ArrayList<MicroDoseEvent>(events.size)
        for (item in events) {
            val ev = item.event
            val tMaxRoute = if (ev.route == null) {
                model.tMaxParent
            } else {
                pk.resolveTMaxHoursForRoute(ev.route)
            }
            val kaRoute = if (ev.route == null || ev.route == AdministrationRoute.ORAL) {
                solveAbsorptionRate(ke = model.keParent, tMax = tMaxRoute)
            } else {
                solveAbsorptionRate(ke = model.keParent, tMax = tMaxRoute)
            }
            val endEpoch = ev.endTimeEpochSecond
            if (endEpoch != null && endEpoch > ev.timeEpochSecond) {
                val durationHours = (endEpoch - ev.timeEpochSecond) / 3600.0
                val steps = ceil(durationHours * 4.0).toInt().coerceIn(12, 48)
                val stepWeight = item.weight / steps.toDouble()
                for (k in 0 until steps) {
                    val microOffset = item.offsetHours + durationHours * ((k + 0.5) / steps.toDouble())
                    if (microOffset <= horizonHours) {
                        result.add(
                            MicroDoseEvent(
                                offsetHours = microOffset,
                                weight = stepWeight,
                                tMaxRoute = tMaxRoute,
                                kaRoute = kaRoute,
                                route = ev.route,
                            )
                        )
                    }
                }
            } else if (item.offsetHours <= horizonHours) {
                result.add(
                    MicroDoseEvent(
                        offsetHours = item.offsetHours,
                        weight = item.weight,
                        tMaxRoute = tMaxRoute,
                        kaRoute = kaRoute,
                        route = ev.route,
                    )
                )
            }
        }
        return result.sortedBy { it.offsetHours }
    }

    private data class LiveTimelineEval(
        val rawParent: DoubleArray,
        val rawMets: Array<DoubleArray>,
        val rawTotal: DoubleArray,
        val nowTotal: Double?,
        val nowMets: DoubleArray?,
        val carryoverAtStartTotal: Double,
    )

    private fun evaluateLiveTimeline(
        sampleHours: List<Double>,
        microEvents: List<MicroDoseEvent>,
        model: ResolvedPkModel,
        parentDdiScale: Double,
        nowOffsetHours: Double?,
        priorMicroEvents: List<MicroDoseEvent>,
    ): LiveTimelineEval {
        val rawParent: DoubleArray
        val nowParent: Double?
        val carryoverParent: Double

        if (model.isZeroOrder) {
            val extra = if (nowOffsetHours != null) listOf(nowOffsetHours) else emptyList()
            val (gridParents, extraParents) = evaluateZeroOrderOnGrid(
                sampleHours = sampleHours,
                microEvents = microEvents,
                model = model,
                extraQueryHours = extra,
            )
            rawParent = gridParents
            nowParent = if (nowOffsetHours != null) extraParents[nowOffsetHours] ?: 0.0 else null
            carryoverParent = if (priorMicroEvents.isNotEmpty()) {
                val (_, priorMap) = evaluateZeroOrderOnGrid(
                    sampleHours = emptyList(),
                    microEvents = priorMicroEvents,
                    model = model,
                    extraQueryHours = listOf(0.0),
                )
                priorMap[0.0] ?: 0.0
            } else {
                0.0
            }
        } else {
            rawParent = DoubleArray(sampleHours.size) { i ->
                evaluateBatemanParentAtHour(sampleHours[i], microEvents, model, parentDdiScale)
            }
            nowParent = nowOffsetHours?.let {
                evaluateBatemanParentAtHour(it, microEvents, model, parentDdiScale)
            }
            carryoverParent = if (priorMicroEvents.isNotEmpty()) {
                evaluateBatemanParentAtHour(0.0, priorMicroEvents, model, parentDdiScale)
            } else {
                0.0
            }
        }

        val rawMets = Array(model.mets.size) { DoubleArray(sampleHours.size) }
        val rawTotal = DoubleArray(sampleHours.size)
        for (i in sampleHours.indices) {
            val t = sampleHours[i]
            var totalVal = if (model.isProdrug) 0.0 else rawParent[i]
            for (mIdx in model.mets.indices) {
                val met = model.mets[mIdx]
                var mVal = 0.0
                for (item in microEvents) {
                    val dt = t - item.offsetHours
                    if (dt <= 0.0) continue
                    mVal += item.weight * met.ratio * unitBateman(dt, met.kfMet, met.keMet)
                }
                rawMets[mIdx][i] = mVal
                totalVal += mVal
            }
            rawTotal[i] = totalVal
        }

        val nowMets: DoubleArray?
        val nowTotal: Double?
        if (nowOffsetHours != null && nowParent != null) {
            val metArr = DoubleArray(model.mets.size)
            var tVal = if (model.isProdrug) 0.0 else nowParent
            for (mIdx in model.mets.indices) {
                val met = model.mets[mIdx]
                var mVal = 0.0
                for (item in microEvents) {
                    val dt = nowOffsetHours - item.offsetHours
                    if (dt <= 0.0) continue
                    mVal += item.weight * met.ratio * unitBateman(dt, met.kfMet, met.keMet)
                }
                metArr[mIdx] = mVal
                tVal += mVal
            }
            nowMets = metArr
            nowTotal = tVal
        } else {
            nowMets = null
            nowTotal = null
        }

        val carryoverAtStartTotal = if (priorMicroEvents.isEmpty()) {
            0.0
        } else {
            var cTotal = if (model.isProdrug) 0.0 else carryoverParent
            for (met in model.mets) {
                var mVal = 0.0
                for (item in priorMicroEvents) {
                    val dt = 0.0 - item.offsetHours
                    if (dt <= 0.0) continue
                    mVal += item.weight * met.ratio * unitBateman(dt, met.kfMet, met.keMet)
                }
                cTotal += mVal
            }
            cTotal
        }

        return LiveTimelineEval(
            rawParent = rawParent,
            rawMets = rawMets,
            rawTotal = rawTotal,
            nowTotal = nowTotal,
            nowMets = nowMets,
            carryoverAtStartTotal = carryoverAtStartTotal,
        )
    }

    private fun evaluateZeroOrderOnGrid(
        sampleHours: List<Double>,
        microEvents: List<MicroDoseEvent>,
        model: ResolvedPkModel,
        extraQueryHours: List<Double>,
    ): Pair<DoubleArray, Map<Double, Double>> {
        val resultSamples = DoubleArray(sampleHours.size)
        val extraResults = HashMap<Double, Double>(extraQueryHours.size)

        val allQueries = sortedSetOf<Double>()
        for (h in sampleHours) allQueries.add(h)
        for (h in extraQueryHours) allQueries.add(h)
        if (allQueries.isEmpty() || microEvents.isEmpty()) {
            return resultSamples to extraResults
        }

        val sortedEvents = microEvents.sortedBy { it.offsetHours }
        val minStart = minOf(allQueries.first(), sortedEvents.first().offsetHours)
        val maxEnd = allQueries.last()

        val vMax = (0.5 + ZERO_ORDER_KM * LN2) / model.parentHl
        val km = ZERO_ORDER_KM

        val queryList = allQueries.toList()
        val queryValues = HashMap<Double, Double>(queryList.size)

        var t = minStart
        var a = 0.0
        var nextEventIdx = 0
        var nextQueryIdx = 0
        val activeAbsorbing = mutableListOf<MicroDoseEvent>()

        while (nextQueryIdx < queryList.size && queryList[nextQueryIdx] <= t + 1e-12) {
            queryValues[queryList[nextQueryIdx]] = 0.0
            nextQueryIdx++
        }

        while (nextQueryIdx < queryList.size && t < maxEnd + 1e-9) {
            while (nextEventIdx < sortedEvents.size && sortedEvents[nextEventIdx].offsetHours <= t + 1e-12) {
                activeAbsorbing.add(sortedEvents[nextEventIdx])
                nextEventIdx++
            }

            // 清理已完成 >99.9999% 吸收的历史微事件
            if (activeAbsorbing.isNotEmpty()) {
                activeAbsorbing.removeAll { ev -> (t - ev.offsetHours) * ev.kaRoute > 22.0 }
            }

            // 若当前池已几乎归零且无活跃吸收，直接快进到下一个事件或下一个查询点
            if (activeAbsorbing.isEmpty() && a <= 1e-10) {
                val nextEvT = if (nextEventIdx < sortedEvents.size) sortedEvents[nextEventIdx].offsetHours else Double.POSITIVE_INFINITY
                val nextQT = queryList[nextQueryIdx]
                val jumpTo = minOf(nextEvT, nextQT, maxEnd)
                if (jumpTo > t + 1e-12) {
                    a = 0.0
                    t = jumpTo
                    while (nextQueryIdx < queryList.size && queryList[nextQueryIdx] <= t + 1e-12) {
                        queryValues[queryList[nextQueryIdx]] = 0.0
                        nextQueryIdx++
                    }
                    continue
                }
            }

            val nextEvT = if (nextEventIdx < sortedEvents.size) sortedEvents[nextEventIdx].offsetHours else Double.POSITIVE_INFINITY
            val nextQT = queryList[nextQueryIdx]
            val targetT = minOf(t + ZERO_ORDER_DT_MAX, nextEvT, nextQT)
            val dt = targetT - t
            if (dt <= 1e-12) {
                if (nextQT <= t + 1e-12) {
                    queryValues[nextQT] = a
                    nextQueryIdx++
                } else {
                    t = targetT + 1e-12
                }
                continue
            }

            var dG = 0.0
            for (ev in activeAbsorbing) {
                val dt0 = (t - ev.offsetHours).coerceAtLeast(0.0)
                val dt1 = (targetT - ev.offsetHours).coerceAtLeast(0.0)
                dG += ev.weight * (exp(-ev.kaRoute * dt0) - exp(-ev.kaRoute * dt1))
            }

            val kBase = vMax / (km + a)
            val aPred = (a + 0.5 * dG) / (1.0 + 0.5 * dt * kBase)
            val kMid = vMax / (km + aPred)
            a = ((a + dG) / (1.0 + dt * kMid)).coerceAtLeast(0.0)
            t = targetT

            while (nextQueryIdx < queryList.size && queryList[nextQueryIdx] <= t + 1e-12) {
                queryValues[queryList[nextQueryIdx]] = a
                nextQueryIdx++
            }
        }

        for (i in sampleHours.indices) {
            resultSamples[i] = queryValues[sampleHours[i]] ?: 0.0
        }
        for (q in extraQueryHours) {
            extraResults[q] = queryValues[q] ?: 0.0
        }
        return resultSamples to extraResults
    }

    private fun evaluateBatemanParentAtHour(
        tHours: Double,
        microEvents: List<MicroDoseEvent>,
        model: ResolvedPkModel,
        parentDdiScale: Double,
    ): Double {
        var pVal = 0.0
        for (item in microEvents) {
            val dt = tHours - item.offsetHours
            if (dt <= 0.0) continue
            pVal += item.weight * parentDdiScale * unitParentResponse(
                dt = dt,
                tMaxRoute = item.tMaxRoute,
                kaRoute = item.kaRoute,
                model = model,
                route = item.route,
            )
        }
        return pVal
    }

    private fun unitParentResponse(
        dt: Double,
        tMaxRoute: Double,
        kaRoute: Double,
        model: ResolvedPkModel,
        route: AdministrationRoute?,
    ): Double {
        if (dt <= 0.0) return 0.0
        return if (model.isExtendedRelease && (route == null || route == AdministrationRoute.ORAL)) {
            val tFast = minOf(tMaxRoute, 1.5)
            val tSlow = maxOf(tMaxRoute * 1.6, 3.0)
            val kaFast = solveAbsorptionRate(ke = model.keParent, tMax = tFast)
            val kaSlow = solveAbsorptionRateAllowingFlipFlop(ke = model.keParent, tMax = tSlow)
            0.3 * unitBateman(dt, kaFast, model.keParent) +
                0.7 * unitBateman(dt, kaSlow, model.keParent)
        } else {
            unitBateman(dt, kaRoute, model.keParent)
        }
    }

    private data class SingleDosePeaks(
        val activePeak: Double,
        val overallPeak: Double,
    )

    private fun computeSingleDosePeaks(
        model: ResolvedPkModel,
        pk: Pharmacokinetics,
        dominantRoute: AdministrationRoute?,
    ): SingleDosePeaks {
        val tMaxRoute = if (dominantRoute == null) {
            model.tMaxParent
        } else {
            pk.resolveTMaxHoursForRoute(dominantRoute)
        }
        val kaRoute = solveAbsorptionRate(ke = model.keParent, tMax = tMaxRoute)
        val tSlowEr = if (model.isExtendedRelease) maxOf(tMaxRoute * 1.6, 3.0) else tMaxRoute

        val singleHorizon = maxOf(model.effHl * 2.5, tSlowEr * 4.0, 24.0)
        val probePoints = sortedSetOf<Double>()
        for (i in 0..80) {
            probePoints.add(singleHorizon * i / 80.0)
        }
        val earlySpan = minOf(singleHorizon, maxOf(tSlowEr * 3.0, 8.0))
        for (i in 1..40) {
            probePoints.add(earlySpan * i / 40.0)
        }
        probePoints.add(tMaxRoute.coerceIn(0.02, singleHorizon))
        if (model.isExtendedRelease) {
            probePoints.add(minOf(tMaxRoute, 1.5).coerceIn(0.02, singleHorizon))
            probePoints.add(tSlowEr.coerceIn(0.02, singleHorizon))
        }
        for (met in model.mets) {
            val estMetPeak = (met.halfLifeHours * 0.25).coerceIn(0.2, singleHorizon)
            probePoints.add(estMetPeak)
        }

        val probeList = probePoints.toList()
        val singleMicro = listOf(
            MicroDoseEvent(
                offsetHours = 0.0,
                weight = 1.0,
                tMaxRoute = tMaxRoute,
                kaRoute = kaRoute,
                route = dominantRoute,
            )
        )

        val parentVals = if (model.isZeroOrder) {
            evaluateZeroOrderOnGrid(
                sampleHours = probeList,
                microEvents = singleMicro,
                model = model,
                extraQueryHours = emptyList(),
            ).first
        } else {
            DoubleArray(probeList.size) { i ->
                evaluateBatemanParentAtHour(probeList[i], singleMicro, model, parentDdiScale = 1.0)
            }
        }

        var activePeak = 0.0
        var overallPeak = 0.0
        for (i in probeList.indices) {
            val t = probeList[i]
            val pVal = parentVals[i]
            if (pVal > overallPeak) overallPeak = pVal
            var totalVal = if (model.isProdrug) 0.0 else pVal
            for (met in model.mets) {
                val mVal = met.ratio * unitBateman(t, met.kfMet, met.keMet)
                if (mVal > overallPeak) overallPeak = mVal
                totalVal += mVal
            }
            if (totalVal > activePeak) activePeak = totalVal
            if (totalVal > overallPeak) overallPeak = totalVal
        }
        return SingleDosePeaks(activePeak = activePeak, overallPeak = overallPeak)
    }

    private fun toMgOrNull(dose: Double?, units: String?): Double? {
        val value = dose?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        val u = units?.trim()?.lowercase(Locale.ROOT) ?: return null
        return when (u) {
            "ug", "µg", "μg", "mcg" -> value * 0.001
            "mg" -> value
            "g" -> value * 1000.0
            else -> null
        }
    }

    private fun buildLiveWindowGrid(
        horizonHours: Double,
        tMaxParent: Double,
        metTMaxList: List<Pair<Double, Double>>,
        eventsInWindow: List<EventWithOffset>,
        windowStartEpochSecond: Long,
        nowOffsetHours: Double?,
        uniformSteps: Int,
    ): List<Double> {
        val points = sortedSetOf<Double>()
        points.add(0.0)
        points.add(horizonHours)
        val steps = uniformSteps.coerceIn(60, 240)
        for (i in 0..steps) {
            points.add(horizonHours * i / steps.toDouble())
        }
        if (nowOffsetHours != null && nowOffsetHours in 0.0..horizonHours) {
            points.add(nowOffsetHours)
        }
        for (ev in eventsInWindow) {
            val offsets = listOf(0.0, 0.05, 0.25, 0.5, 1.0, minOf(ev.tMaxRoute, 3.0), ev.tMaxRoute, maxOf(tMaxParent, ev.tMaxRoute))
            for (delta in offsets) {
                val t = ev.offsetHours + delta
                if (t in 0.0..horizonHours) points.add(t)
            }
            val endEpoch = ev.event.endTimeEpochSecond
            if (endEpoch != null && endEpoch > ev.event.timeEpochSecond) {
                val endOffset = (endEpoch - windowStartEpochSecond) / 3600.0
                val midOffset = 0.5 * (ev.offsetHours + endOffset)
                for (t in listOf(ev.offsetHours, midOffset, endOffset, endOffset + ev.tMaxRoute)) {
                    if (t in 0.0..horizonHours) points.add(t)
                }
            }
            for ((metHl, _) in metTMaxList) {
                val tEst = ev.offsetHours + (metHl * 0.15).coerceIn(1.0, 12.0)
                if (tEst in 0.0..horizonHours) points.add(tEst)
            }
        }
        return points.toList()
    }

    private fun resolveClinicalDosingIntervalHours(pk: Pharmacokinetics, effHl: Double): Double {
        if (pk.pkRole == "DEPOT_FLIP_FLOP" && effHl >= 72.0) {
            val days = round((effHl * 0.7) / 24.0).coerceIn(3.0, 28.0)
            return days * 24.0
        }
        return when {
            effHl >= 12.0 -> 24.0
            effHl >= 5.0 -> 12.0
            else -> 8.0
        }
    }

    private fun solveAbsorptionRate(ke: Double, tMax: Double): Double {
        if (tMax <= 0.0 || ke <= 0.0) return ke * 4.0
        val maxPossibleTMax = 0.96 / ke
        if (tMax >= maxPossibleTMax) {
            return ke * 1.05
        }
        var lo = 1.05
        var hi = 400.0
        repeat(32) {
            val mid = 0.5 * (lo + hi)
            val predictedTMax = ln(mid) / ((mid - 1.0) * ke)
            if (predictedTMax > tMax) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return ke * (0.5 * (lo + hi))
    }

    private fun solveAbsorptionRateAllowingFlipFlop(ke: Double, tMax: Double): Double {
        if (tMax <= 0.0 || ke <= 0.0) return ke * 4.0
        val maxFastTMax = 0.96 / ke
        if (tMax < maxFastTMax) {
            return solveAbsorptionRate(ke = ke, tMax = tMax)
        }
        var lo = 0.005
        var hi = 0.95
        repeat(32) {
            val mid = 0.5 * (lo + hi)
            val predictedTMax = ln(mid) / ((mid - 1.0) * ke)
            if (predictedTMax > tMax) {
                lo = mid
            } else {
                hi = mid
            }
        }
        return ke * (0.5 * (lo + hi))
    }

    private fun unitBateman(t: Double, k1: Double, k2: Double): Double {
        if (t <= 0.0 || k1 <= 0.0 || k2 <= 0.0) return 0.0
        val diff = k1 - k2
        val raw = if (abs(diff) < 1e-6 * maxOf(k1, k2)) {
            k1 * k1 * t * exp(-k1 * t)
        } else {
            (k1 * k2 / diff) * (exp(-k2 * t) - exp(-k1 * t))
        }
        return if (raw.isFinite() && raw > 0.0) raw else 0.0
    }

    private fun buildSampleGrid(
        horizonHours: Double,
        tMaxParent: Double,
        doseTimes: List<Double>,
        mode: PkChartMode,
        isExtendedRelease: Boolean = false,
    ): List<Double> {
        val points = sortedSetOf<Double>()
        points.add(0.0)
        points.add(horizonHours)
        val uniformSteps = if (mode == PkChartMode.SINGLE_DOSE) 110 else 140
        for (i in 0..uniformSteps) {
            points.add(horizonHours * i / uniformSteps.toDouble())
        }
        if (mode == PkChartMode.SINGLE_DOSE) {
            val earlySpan = minOf(horizonHours, maxOf(tMaxParent * 4.0, horizonHours * 0.12))
            for (i in 1..28) {
                points.add(earlySpan * i / 28.0)
            }
            if (tMaxParent < horizonHours) {
                points.add(tMaxParent)
            }
            if (isExtendedRelease) {
                val tSlow = maxOf(tMaxParent * 1.6, 3.0)
                if (tSlow < horizonHours) points.add(tSlow)
            }
        } else {
            val peakOffsets = listOf(0.0, minOf(tMaxParent, 3.0))
            for (dTime in doseTimes) {
                for (offset in peakOffsets) {
                    val t = dTime + offset
                    if (t in 0.0..horizonHours) points.add(t)
                }
            }
        }
        return points.toList()
    }
}
