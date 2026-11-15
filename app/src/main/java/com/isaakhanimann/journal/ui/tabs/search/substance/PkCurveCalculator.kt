/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import com.isaakhanimann.journal.data.room.experiences.entities.AppColor
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
)

data class RealDoseEvent(
    val substanceName: String,
    val timeEpochSecond: Long,
    val dose: Double?,
    val units: String? = null,
    val isFromCurrentExperience: Boolean,
    val isConfirmed: Boolean = true,
)

data class LivePkSubstanceInput(
    val canonicalName: String,
    val displayName: String,
    val color: AppColor,
    val pk: Pharmacokinetics,
    val doseEvents: List<RealDoseEvent>,
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
)

data class ExperienceLivePkSimulation(
    val windowStartEpochSecond: Long,
    val windowEndEpochSecond: Long,
    val currentTimeEpochSecond: Long?,
    val profiles: List<SubstanceLivePkProfile>,
)

object PkCurveCalculator {

    private val LN2 = ln(2.0)

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
        val mets: List<MetParam>,
    )

    private data class WeightedDoseOffset(
        val event: RealDoseEvent,
        val offsetHours: Double,
        val weight: Double,
    )

    private fun resolveModel(pk: Pharmacokinetics): ResolvedPkModel? {
        val parentHl = pk.halfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: return null
        val effHl = pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() } ?: parentHl
        val keParent = LN2 / parentHl
        val tMaxParent = (pk.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: (parentHl * 0.2).coerceIn(0.25, 6.0))
        val kaParent = solveAbsorptionRate(ke = keParent, tMax = tMaxParent)
        val mets = pk.activeMetabolites
            .filter { (it.halfLifeHours ?: 0.0) > 0.0 && it.halfLifeHours!!.isFinite() }
            .map { met ->
                val metHl = met.halfLifeHours!!
                val keMet = LN2 / metHl
                val kfMet = met.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }?.let { tMaxMet ->
                    solveAbsorptionRate(ke = keMet, tMax = tMaxMet)
                } ?: keParent
                val ratio = (met.exposureRatio ?: 0.5).coerceIn(0.05, 25.0)
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
            horizonHours = (model.effHl * 5.0).coerceAtLeast(6.0)
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
            mode = mode
        )

        val rawParent = DoubleArray(sampleTimes.size)
        val rawMets = Array(model.mets.size) { DoubleArray(sampleTimes.size) }
        val rawTotal = DoubleArray(sampleTimes.size)

        for (i in sampleTimes.indices) {
            val t = sampleTimes[i]
            var pVal = 0.0
            for (dTime in doseTimes) {
                if (t < dTime) break
                pVal += unitBateman(t - dTime, model.kaParent, model.keParent)
            }
            rawParent[i] = pVal

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
     * 基于真实给药时间戳与过去 42 天历史给药记录的多剂次 Bateman 卷积：
     * 在当前日记时间窗 [windowStartEpochSecond, windowEndEpochSecond] 内重建母体、活性代谢物与总活性暴露曲线。
     */
    fun simulateExperienceLivePk(
        substances: List<LivePkSubstanceInput>,
        windowStartEpochSecond: Long,
        windowEndEpochSecond: Long,
        currentTimeEpochSecond: Long?,
        gridSteps: Int = 140,
    ): ExperienceLivePkSimulation {
        val safeEnd = windowEndEpochSecond.coerceAtLeast(windowStartEpochSecond + 3600L)
        val horizonHours = (safeEnd - windowStartEpochSecond) / 3600.0
        val nowOffsetHours = currentTimeEpochSecond
            ?.takeIf { it in windowStartEpochSecond..safeEnd }
            ?.let { (it - windowStartEpochSecond) / 3600.0 }

        val profiles = substances.mapNotNull { input ->
            val model = resolveModel(input.pk) ?: return@mapNotNull null
            if (input.doseEvents.isEmpty()) return@mapNotNull null

            val weightedEvents = computeDoseWeights(input.doseEvents)
                .map { (event, weight) ->
                    WeightedDoseOffset(
                        event = event,
                        offsetHours = (event.timeEpochSecond - windowStartEpochSecond) / 3600.0,
                        weight = weight
                    )
                }
                .filter { it.offsetHours <= horizonHours }
                .sortedBy { it.offsetHours }
            if (weightedEvents.isEmpty()) return@mapNotNull null

            val priorEvents = weightedEvents.filter { it.offsetHours < 0.0 }
            val inWindowEvents = weightedEvents.filter { it.offsetHours in 0.0..horizonHours }
            val currentExperienceEvents = weightedEvents.filter { it.event.isFromCurrentExperience }

            val singleDosePeakTotal = computeSingleDoseTotalPeak(model)
            val sampleHours = buildLiveWindowGrid(
                horizonHours = horizonHours,
                tMaxParent = model.tMaxParent,
                metTMaxList = model.mets.map { it.halfLifeHours to (1.0 / it.kfMet) },
                eventsInWindow = inWindowEvents,
                nowOffsetHours = nowOffsetHours,
                uniformSteps = gridSteps
            )

            val rawParent = DoubleArray(sampleHours.size)
            val rawMets = Array(model.mets.size) { DoubleArray(sampleHours.size) }
            val rawTotal = DoubleArray(sampleHours.size)

            var windowMaxTotal = 0.0
            var windowMaxOverall = 0.0
            for (i in sampleHours.indices) {
                val eval = evaluateAtHour(sampleHours[i], weightedEvents, model)
                rawParent[i] = eval.parent
                rawTotal[i] = eval.total
                if (eval.total > windowMaxTotal) windowMaxTotal = eval.total
                if (eval.total > windowMaxOverall) windowMaxOverall = eval.total
                if (eval.parent > windowMaxOverall) windowMaxOverall = eval.parent
                for (mIdx in eval.mets.indices) {
                    val mv = eval.mets[mIdx]
                    rawMets[mIdx][i] = mv
                    if (mv > windowMaxOverall) windowMaxOverall = mv
                }
            }

            if (windowMaxOverall <= 0.0 || !windowMaxOverall.isFinite()) return@mapNotNull null
            val hasCurrentDose = currentExperienceEvents.isNotEmpty()
            val peakRelativeToSingleDose = if (singleDosePeakTotal > 0.0) {
                (windowMaxTotal / singleDosePeakTotal).toFloat()
            } else {
                1f
            }
            // 对于今日未新服药的纯跨日历史物质，仅当当前窗口峰值仍达单次峰值的 >= 2% 时保留展示
            if (!hasCurrentDose && singleDosePeakTotal > 0.0 && (windowMaxTotal / singleDosePeakTotal) < 0.02) {
                return@mapNotNull null
            }

            val referenceScale = maxOf(singleDosePeakTotal, windowMaxOverall)
            if (referenceScale <= 0.0 || !referenceScale.isFinite()) return@mapNotNull null

            val parentPoints = sampleHours.indices.map { idx ->
                PkPoint(
                    hoursFromStart = sampleHours[idx].toFloat(),
                    normalizedValue = (rawParent[idx] / referenceScale).toFloat().coerceIn(0f, 1f)
                )
            }
            val totalPoints = sampleHours.indices.map { idx ->
                PkPoint(
                    hoursFromStart = sampleHours[idx].toFloat(),
                    normalizedValue = (rawTotal[idx] / referenceScale).toFloat().coerceIn(0f, 1f)
                )
            }

            val nowEval = nowOffsetHours?.let { evaluateAtHour(it, weightedEvents, model) }
            val metSeriesList = model.mets.mapIndexed { mIdx, met ->
                val points = sampleHours.indices.map { idx ->
                    PkPoint(
                        hoursFromStart = sampleHours[idx].toFloat(),
                        normalizedValue = (rawMets[mIdx][idx] / referenceScale).toFloat().coerceIn(0f, 1f)
                    )
                }
                val nowMetNorm = nowEval?.let {
                    (it.mets[mIdx] / referenceScale).toFloat().coerceIn(0f, 1f)
                }
                LiveMetaboliteSeries(
                    name = met.name,
                    halfLifeHours = met.halfLifeHours,
                    exposureRatio = met.ratio,
                    points = points,
                    currentNormalizedValue = nowMetNorm
                )
            }

            val carryoverAtStartTotal = if (priorEvents.isNotEmpty()) {
                evaluateAtHour(0.0, priorEvents, model).total
            } else {
                0.0
            }
            val carryoverBaselineFractionAtStart =
                (carryoverAtStartTotal / referenceScale).toFloat().coerceIn(0f, 1f)
            val currentRelativeActiveLevel = nowEval?.let {
                (it.total / referenceScale).toFloat().coerceIn(0f, 1f)
            }
            val lookbackDaysMax = priorEvents.maxOfOrNull {
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
                currentIngestionCount = currentExperienceEvents.size,
                carryoverIngestionCount = priorEvents.size,
                lookbackDaysMax = lookbackDaysMax,
                doseEventsInWindow = inWindowEvents.map { it.event },
                parentPoints = parentPoints,
                metaboliteSeries = metSeriesList,
                totalActivePoints = totalPoints,
                carryoverBaselineFractionAtStart = carryoverBaselineFractionAtStart,
                currentRelativeActiveLevel = currentRelativeActiveLevel,
                windowPeakRelativeToSingleDose = peakRelativeToSingleDose,
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

    private data class HourEval(
        val parent: Double,
        val mets: DoubleArray,
        val total: Double,
    )

    private fun evaluateAtHour(
        tHours: Double,
        events: List<WeightedDoseOffset>,
        model: ResolvedPkModel,
    ): HourEval {
        var pVal = 0.0
        for (item in events) {
            val dt = tHours - item.offsetHours
            if (dt <= 0.0) continue
            pVal += item.weight * unitBateman(dt, model.kaParent, model.keParent)
        }
        var totalVal = if (model.isProdrug) 0.0 else pVal
        val metVals = DoubleArray(model.mets.size)
        for (mIdx in model.mets.indices) {
            val met = model.mets[mIdx]
            var mVal = 0.0
            for (item in events) {
                val dt = tHours - item.offsetHours
                if (dt <= 0.0) continue
                mVal += item.weight * met.ratio * unitBateman(dt, met.kfMet, met.keMet)
            }
            metVals[mIdx] = mVal
            totalVal += mVal
        }
        return HourEval(parent = pVal, mets = metVals, total = totalVal)
    }

    private fun computeSingleDoseTotalPeak(model: ResolvedPkModel): Double {
        val singleHorizon = maxOf(model.effHl * 2.5, model.tMaxParent * 4.0, 24.0)
        val probePoints = sortedSetOf<Double>()
        for (i in 0..80) {
            probePoints.add(singleHorizon * i / 80.0)
        }
        val earlySpan = minOf(singleHorizon, maxOf(model.tMaxParent * 3.0, 8.0))
        for (i in 1..30) {
            probePoints.add(earlySpan * i / 30.0)
        }
        probePoints.add(model.tMaxParent.coerceIn(0.05, singleHorizon))
        val singleList = listOf(
            WeightedDoseOffset(
                event = RealDoseEvent(substanceName = "", timeEpochSecond = 0L, dose = 1.0, isFromCurrentExperience = true),
                offsetHours = 0.0,
                weight = 1.0
            )
        )
        var peak = 0.0
        for (t in probePoints) {
            val eval = evaluateAtHour(t, singleList, model)
            if (eval.total > peak) peak = eval.total
            if (eval.parent > peak) peak = eval.parent
            for (mv in eval.mets) if (mv > peak) peak = mv
        }
        return peak
    }

    private fun computeDoseWeights(events: List<RealDoseEvent>): List<Pair<RealDoseEvent, Double>> {
        val mgValues = events.map { toMgOrNull(it.dose, it.units) }
        val allPositiveHaveMg = events.indices.all { idx ->
            val d = events[idx].dose
            d == null || d <= 0.0 || mgValues[idx] != null
        }
        if (allPositiveHaveMg) {
            val positives = mgValues.filterNotNull().filter { it > 0.0 && it.isFinite() }.sorted()
            if (positives.isNotEmpty()) {
                val typical = positives[positives.size / 2]
                return events.indices.map { idx ->
                    val mg = mgValues[idx]
                    val w = if (mg != null && typical > 0.0) (mg / typical).coerceIn(0.05, 25.0) else 1.0
                    events[idx] to w
                }
            }
        }
        val distinctNonNullUnits = events.mapNotNull { it.units?.trim()?.lowercase(Locale.ROOT)?.takeIf { u -> u.isNotEmpty() } }.distinct()
        if (distinctNonNullUnits.size <= 1) {
            val positives = events.mapNotNull { it.dose?.takeIf { d -> d > 0.0 && d.isFinite() } }.sorted()
            if (positives.isNotEmpty()) {
                val typical = positives[positives.size / 2]
                return events.map { event ->
                    val d = event.dose
                    val w = if (d != null && d > 0.0 && typical > 0.0) (d / typical).coerceIn(0.05, 25.0) else 1.0
                    event to w
                }
            }
        }
        return events.map { it to 1.0 }
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
        eventsInWindow: List<WeightedDoseOffset>,
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
        val offsets = listOf(0.0, 0.25, 0.5, 1.0, minOf(tMaxParent, 3.0), tMaxParent)
        for (ev in eventsInWindow) {
            for (delta in offsets) {
                val t = ev.offsetHours + delta
                if (t in 0.0..horizonHours) points.add(t)
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
