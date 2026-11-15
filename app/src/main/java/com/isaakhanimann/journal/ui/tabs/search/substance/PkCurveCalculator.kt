/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
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

object PkCurveCalculator {

    private val LN2 = ln(2.0)

    fun simulate(
        pk: Pharmacokinetics,
        parentLabel: String,
        totalLabel: String,
        mode: PkChartMode,
    ): PkSimulationResult? {
        val parentHl = pk.halfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: return null
        val effHl = pk.resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() } ?: parentHl
        val mets = pk.activeMetabolites.filter { (it.halfLifeHours ?: 0.0) > 0.0 && it.halfLifeHours!!.isFinite() }

        val keParent = LN2 / parentHl
        val tMaxParent = (pk.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: (parentHl * 0.2).coerceIn(0.25, 6.0))
        val kaParent = solveAbsorptionRate(ke = keParent, tMax = tMaxParent)

        val metParams = mets.map { met ->
            val metHl = met.halfLifeHours!!
            val keMet = LN2 / metHl
            val kfMet = met.tMaxHours?.takeIf { it > 0.0 && it.isFinite() }?.let { tMaxMet ->
                solveAbsorptionRate(ke = keMet, tMax = tMaxMet)
            } ?: keParent
            val ratio = (met.exposureRatio ?: 0.5).coerceIn(0.05, 25.0)
            Triple(met.name, Triple(kfMet, keMet, ratio), metHl)
        }

        val isProdrug = pk.pkRole == "PRODRUG"

        val dosingIntervalHours: Double?
        val doseTimes: List<Double>
        val horizonHours: Double

        if (mode == PkChartMode.SINGLE_DOSE) {
            dosingIntervalHours = null
            doseTimes = listOf(0.0)
            horizonHours = (effHl * 5.0).coerceAtLeast(6.0)
        } else {
            val tau = resolveClinicalDosingIntervalHours(pk = pk, effHl = effHl)
            dosingIntervalHours = tau
            val targetHours = maxOf(effHl * 4.5, tau * 6.0)
            val numDoses = ceil(targetHours / tau).toInt().coerceIn(5, 60)
            horizonHours = numDoses * tau
            doseTimes = List(numDoses) { idx -> idx * tau }
        }

        val sampleTimes = buildSampleGrid(
            horizonHours = horizonHours,
            tMaxParent = tMaxParent,
            doseTimes = doseTimes,
            mode = mode
        )

        val rawParent = DoubleArray(sampleTimes.size)
        val rawMets = Array(metParams.size) { DoubleArray(sampleTimes.size) }
        val rawTotal = DoubleArray(sampleTimes.size)

        for (i in sampleTimes.indices) {
            val t = sampleTimes[i]
            var pVal = 0.0
            for (dTime in doseTimes) {
                if (t < dTime) break
                pVal += unitBateman(t - dTime, kaParent, keParent)
            }
            rawParent[i] = pVal

            var totalVal = if (isProdrug) 0.0 else pVal
            for (mIdx in metParams.indices) {
                val (kfMet, keMet, ratio) = metParams[mIdx].second
                var mVal = 0.0
                for (dTime in doseTimes) {
                    if (t < dTime) break
                    mVal += ratio * unitBateman(t - dTime, kfMet, keMet)
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
        if (mets.isNotEmpty()) {
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
        metParams.forEachIndexed { mIdx, (name, _, _) ->
            seriesList.add(
                PkCurveSeries(
                    label = name,
                    isParent = false,
                    isMetabolite = true,
                    isTotalActive = false,
                    points = sampleTimes.indices.map { i -> sampleTimes[i] to (rawMets[mIdx][i] / maxVal).coerceIn(0.0, 1.0) }
                )
            )
        }

        val steadyStateEstimate = if (mode == PkChartMode.STEADY_STATE) {
            (effHl * 4.5).coerceAtMost(horizonHours)
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
