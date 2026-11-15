/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import com.isaakhanimann.journal.data.room.experiences.entities.AppColor
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.CypInteractionEffectType
import com.isaakhanimann.journal.data.substances.EnzymaticDdiModifier
import com.isaakhanimann.journal.data.substances.classes.ActiveMetabolite
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.roa.Bioavailability
import com.isaakhanimann.journal.data.substances.classes.roa.DurationRange
import com.isaakhanimann.journal.data.substances.classes.roa.DurationUnits
import com.isaakhanimann.journal.data.substances.classes.roa.Roa
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PkCurveCalculatorTest {

    private fun cariprazinePk() = Pharmacokinetics(
        halfLifeHours = 72.0,
        tMaxHours = 4.5,
        pkRole = "ACTIVE_METABOLITE",
        effectiveHalfLifeHours = 336.0,
        washoutHours = 1008.0,
        activeMetabolites = listOf(
            ActiveMetabolite(
                name = "DCAR",
                halfLifeHours = 32.0,
                tMaxHours = 6.0,
                exposureRatio = 0.35
            ),
            ActiveMetabolite(
                name = "DDCAR",
                halfLifeHours = 336.0,
                tMaxHours = 48.0,
                exposureRatio = 3.0
            )
        )
    )

    @Test
    fun cariprazineSingleDoseAndSteadyState_reflectDdcArAccumulationAndFiniteNormalizedPoints() {
        val pk = cariprazinePk()

        val single = PkCurveCalculator.simulate(
            pk = pk,
            parentLabel = "Parent",
            totalLabel = "Total",
            mode = PkChartMode.SINGLE_DOSE
        )
        assertNotNull(single)
        single!!
        assertNull(single.dosingIntervalHours)
        assertEquals(336.0 * 5.0, single.totalHorizonHours, 0.01)
        assertEquals(4, single.series.size)

        val steady = PkCurveCalculator.simulate(
            pk = pk,
            parentLabel = "Parent",
            totalLabel = "Total",
            mode = PkChartMode.STEADY_STATE
        )
        assertNotNull(steady)
        steady!!
        assertEquals(24.0, steady.dosingIntervalHours!!, 0.01)
        assertNotNull(steady.steadyStateHoursEstimate)

        val parentSeries = steady.series.first { it.isParent }
        val ddcarSeries = steady.series.first { it.label == "DDCAR" }
        assertTrue(
            "Expected DDCAR steady-state peak (${ddcarSeries.points.last().second}) > parent peak (${parentSeries.points.last().second})",
            ddcarSeries.points.last().second > parentSeries.points.last().second
        )
        for (series in steady.series) {
            for ((t, y) in series.points) {
                assertTrue("t must be finite", t.isFinite() && t >= 0.0)
                assertTrue("y must be normalized in [0, 1]", y.isFinite() && y in 0.0..1.0)
            }
        }
    }

    @Test
    fun prodrugTotalSeriesExcludesInactiveParentContribution() {
        val codeinePk = Pharmacokinetics(
            halfLifeHours = 3.0,
            tMaxHours = 1.2,
            pkRole = "PRODRUG",
            effectiveHalfLifeHours = 3.5,
            activeMetabolites = listOf(
                ActiveMetabolite(
                    name = "Morphine",
                    halfLifeHours = 3.0,
                    tMaxHours = 1.2,
                    exposureRatio = 0.25
                )
            )
        )

        val sim = PkCurveCalculator.simulate(
            pk = codeinePk,
            parentLabel = "Codeine",
            totalLabel = "Total Active",
            mode = PkChartMode.SINGLE_DOSE
        )!!

        val total = sim.series.first { it.isTotalActive }
        val met = sim.series.first { it.label == "Morphine" }
        val parent = sim.series.first { it.isParent }

        val totalMax = total.points.maxOf { it.second }
        val metMax = met.points.maxOf { it.second }
        val parentMax = parent.points.maxOf { it.second }

        assertEquals("For PRODRUG, total active peak must match active metabolite rather than summing parent", metMax, totalMax, 1e-6)
        assertTrue("Parent peak (normalized to 1.0) exceeds metabolite (0.25) peak", parentMax > metMax)
    }

    @Test
    fun depotFlipFlopUsesDepotIntervalAndRemainsFiniteWhenMetaboliteTMaxOmitted() {
        val depotPk = Pharmacokinetics(
            halfLifeHours = 144.0,
            tMaxHours = 84.0,
            pkRole = "DEPOT_FLIP_FLOP",
            effectiveHalfLifeHours = 144.0,
            activeMetabolites = listOf(
                ActiveMetabolite(
                    name = "17β-Estradiol",
                    halfLifeHours = 14.0,
                    tMaxHours = null,
                    exposureRatio = 1.2
                )
            )
        )

        val sim = PkCurveCalculator.simulate(
            pk = depotPk,
            parentLabel = "Depot",
            totalLabel = "Total",
            mode = PkChartMode.STEADY_STATE
        )!!
        assertTrue("Depot dosing interval should span multiple days", (sim.dosingIntervalHours ?: 0.0) >= 72.0)
        assertTrue(sim.series.all { s -> s.points.all { (t, y) -> t.isFinite() && y.isFinite() && y in 0.0..1.0 } })
    }

    @Test
    fun missingHalfLifeReturnsNull() {
        val emptyPk = Pharmacokinetics(halfLife = "Unknown")
        assertNull(
            PkCurveCalculator.simulate(
                pk = emptyPk,
                parentLabel = "P",
                totalLabel = "T",
                mode = PkChartMode.SINGLE_DOSE
            )
        )
    }

    @Test
    fun simulateExperienceLivePk_accumulates14DaysCariprazineAndFiltersNegligibleCarryover() {
        val anchorEpoch = 1_760_000_000L
        val winStart = anchorEpoch - 3600L
        val winEnd = anchorEpoch + 24L * 3600L
        val nowEpoch = anchorEpoch + 6L * 3600L

        val cariEvents = (14 downTo 1).map { daysAgo ->
            RealDoseEvent(
                substanceName = "卡利哌嗪",
                timeEpochSecond = anchorEpoch - daysAgo * 86400L,
                dose = if (daysAgo % 2 == 0) 1500.0 else 1.5,
                units = if (daysAgo % 2 == 0) "ug" else "mg",
                isFromCurrentExperience = false
            )
        } + RealDoseEvent(
            substanceName = "卡利哌嗪",
            timeEpochSecond = anchorEpoch,
            dose = 1.5,
            units = "mg",
            isFromCurrentExperience = true
        )

        val shortHalfLifePk = Pharmacokinetics(
            halfLifeHours = 4.0,
            tMaxHours = 1.0,
            pkRole = "PARENT_DOMINANT"
        )
        val tinyResidualEvents = listOf(
            RealDoseEvent(
                substanceName = "Alprazolam",
                timeEpochSecond = anchorEpoch - 5L * 86400L, // 30x half-life ago -> 0% left
                dose = 0.5,
                units = "mg",
                isFromCurrentExperience = false
            )
        )

        val valproatePk = Pharmacokinetics(
            halfLifeHours = 15.0,
            tMaxHours = 2.0,
            pkRole = "PARENT_DOMINANT"
        )
        val recentCarryoverEvents = listOf(
            RealDoseEvent(
                substanceName = "丙戊酸钠",
                timeEpochSecond = anchorEpoch - 12L * 3600L,
                dose = 500.0,
                units = "mg",
                isFromCurrentExperience = false
            )
        )

        val sim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "Cariprazine",
                    displayName = "卡利哌嗪",
                    color = AppColor.BLUE,
                    pk = cariprazinePk(),
                    doseEvents = cariEvents
                ),
                LivePkSubstanceInput(
                    canonicalName = "Alprazolam",
                    displayName = "阿普唑仑",
                    color = AppColor.ORANGE,
                    pk = shortHalfLifePk,
                    doseEvents = tinyResidualEvents
                ),
                LivePkSubstanceInput(
                    canonicalName = "Sodium valproate",
                    displayName = "丙戊酸钠",
                    color = AppColor.GREEN,
                    pk = valproatePk,
                    doseEvents = recentCarryoverEvents
                )
            ),
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = nowEpoch
        )

        // Alprazolam from 5 days ago (<2% peak) is excluded; Cariprazine + Valproate remain
        assertEquals(2, sim.profiles.size)
        val cari = sim.profiles.first { it.canonicalName == "Cariprazine" }
        assertEquals(1, cari.currentIngestionCount)
        assertEquals(14, cari.carryoverIngestionCount)
        assertTrue(cari.lookbackDaysMax >= 14)
        assertTrue(
            "Carryover baseline at window start should be substantial after 14d dosing, got ${cari.carryoverBaselineFractionAtStart}",
            cari.carryoverBaselineFractionAtStart > 0.5f
        )
        assertTrue(
            "Accumulation factor vs single dose should exceed 3x after 14d DDCAR buildup, got ${cari.windowPeakRelativeToSingleDose}",
            cari.windowPeakRelativeToSingleDose > 3.0f
        )
        assertNotNull(cari.currentRelativeActiveLevel)
        assertTrue(cari.currentRelativeActiveLevel!! > 0.5f)
        val ddcar = cari.metaboliteSeries.first { it.name == "DDCAR" }
        assertTrue(
            "DDCAR level at start (${ddcar.points.first().normalizedValue}) must exceed Parent level (${cari.parentPoints.first().normalizedValue})",
            ddcar.points.first().normalizedValue > cari.parentPoints.first().normalizedValue
        )

        val valproate = sim.profiles.first { it.canonicalName == "Sodium valproate" }
        assertEquals(0, valproate.currentIngestionCount)
        assertEquals(1, valproate.carryoverIngestionCount)
    }

    @Test
    fun continuousIntervalDosing_broadensPeakAndConservesTotalAuc() {
        val anchorEpoch = 1_760_000_000L
        val winStart = anchorEpoch
        val winEnd = anchorEpoch + 48L * 3600L
        val pk = Pharmacokinetics(
            halfLifeHours = 4.0,
            tMaxHours = 0.5,
            pkRole = "PARENT_DOMINANT",
        )

        val bolusSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "TestDrug",
                    displayName = "TestDrug",
                    color = AppColor.BLUE,
                    pk = pk,
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "TestDrug",
                            timeEpochSecond = anchorEpoch,
                            dose = 100.0,
                            units = "mg",
                            isFromCurrentExperience = true,
                            route = AdministrationRoute.ORAL,
                            bioavailabilityFactor = 1.0,
                        )
                    )
                )
            ),
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = null,
            gridSteps = 200,
        )

        val intervalSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "TestDrug",
                    displayName = "TestDrug",
                    color = AppColor.BLUE,
                    pk = pk,
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "TestDrug",
                            timeEpochSecond = anchorEpoch,
                            endTimeEpochSecond = anchorEpoch + 4L * 3600L,
                            dose = 100.0,
                            units = "mg",
                            isFromCurrentExperience = true,
                            route = AdministrationRoute.ORAL,
                            bioavailabilityFactor = 1.0,
                        )
                    )
                )
            ),
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = null,
            gridSteps = 200,
        )

        val bolusProfile = bolusSim.profiles.single()
        val intervalProfile = intervalSim.profiles.single()

        val bolusPeakMg = bolusProfile.peakActiveEquivalentMg!!
        val intervalPeakMg = intervalProfile.peakActiveEquivalentMg!!
        assertTrue(
            "Interval dosing peak ($intervalPeakMg mg) should be lower than bolus peak ($bolusPeakMg mg)",
            intervalPeakMg < bolusPeakMg * 0.88
        )

        val bolusPeakHour = bolusProfile.totalActivePoints.maxByOrNull { it.activeEquivalentMg ?: 0.0 }!!.hoursFromStart
        val intervalPeakHour = intervalProfile.totalActivePoints.maxByOrNull { it.activeEquivalentMg ?: 0.0 }!!.hoursFromStart
        assertTrue(
            "Interval dosing peak ($intervalPeakHour h) should occur later than bolus peak ($bolusPeakHour h)",
            intervalPeakHour > bolusPeakHour + 1.0f
        )

        fun trapezoidalAuc(points: List<PkPoint>): Double {
            var auc = 0.0
            for (i in 0 until points.lastIndex) {
                val dt = (points[i + 1].hoursFromStart - points[i].hoursFromStart).toDouble()
                val y0 = points[i].activeEquivalentMg ?: 0.0
                val y1 = points[i + 1].activeEquivalentMg ?: 0.0
                auc += 0.5 * (y0 + y1) * dt
            }
            return auc
        }

        val bolusAuc = trapezoidalAuc(bolusProfile.totalActivePoints)
        val intervalAuc = trapezoidalAuc(intervalProfile.totalActivePoints)
        assertEquals(
            "Total AUC over 12 half-lives should be conserved within 5% between bolus and 4h infusion",
            bolusAuc,
            intervalAuc,
            bolusAuc * 0.05
        )
    }

    @Test
    fun zeroOrderAlcoholMode_exhibitsQuasiLinearDecayAtHighConcentration() {
        val alcoholPk = Pharmacokinetics(
            halfLifeHours = 3.0,
            tMaxHours = 0.75,
            pkRole = "ZERO_ORDER",
        )
        val sim = PkCurveCalculator.simulate(
            pk = alcoholPk,
            parentLabel = "Alcohol",
            totalLabel = "Total",
            mode = PkChartMode.SINGLE_DOSE,
        )!!
        val pts = sim.series.first { it.isParent }.points
        for ((t, y) in pts) {
            assertTrue("t must be finite", t.isFinite() && t >= 0.0)
            assertTrue("y must be in [0, 1]", y.isFinite() && y in 0.0..1.0)
        }
        val peakIdx = pts.indices.maxByOrNull { pts[it].second }!!
        val peakTime = pts[peakIdx].first
        assertTrue("Expected Alcohol peak around 0.75h, got $peakTime", peakTime in 0.4..1.3)
        for (i in peakIdx until pts.lastIndex) {
            assertTrue(
                "Zero-order decay tail must be non-increasing at t=${pts[i].first}",
                pts[i + 1].second <= pts[i].second + 1e-6
            )
        }

        // 在高浓度下降区 [1.5h, 4.5h] 检验斜率近似恒定（准线性下降，而非一阶指数大幅弯曲）
        fun valueAt(targetT: Double): Double = pts.minByOrNull { abs(it.first - targetT) }!!.second
        val y15 = valueAt(1.5)
        val y30 = valueAt(3.0)
        val y45 = valueAt(4.5)
        val slope1 = (y30 - y15) / 1.5
        val slope2 = (y45 - y30) / 1.5
        assertTrue("First segment slope must be negative ($slope1)", slope1 < -0.05)
        val slopeRatio = slope2 / slope1
        assertTrue(
            "Zero-order high-conc slope ratio ($slopeRatio) should stay close to linear (>= 0.80)",
            slopeRatio >= 0.80
        )

        // 同时验证 canonicalName == "Alcohol" 即使未标 pkRole 也自动走零级模型
        val anchorEpoch = 1_760_000_000L
        val liveSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "Alcohol",
                    displayName = "酒精",
                    color = AppColor.ORANGE,
                    pk = Pharmacokinetics(halfLifeHours = 3.0, tMaxHours = 0.75),
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "Alcohol",
                            timeEpochSecond = anchorEpoch,
                            dose = 14.0,
                            units = "g",
                            isFromCurrentExperience = true,
                            bioavailabilityFactor = 0.9,
                        )
                    )
                )
            ),
            windowStartEpochSecond = anchorEpoch,
            windowEndEpochSecond = anchorEpoch + 12L * 3600L,
            currentTimeEpochSecond = anchorEpoch + 3L * 3600L,
        )
        val liveProfile = liveSim.profiles.single()
        assertNotNull(liveProfile.peakActiveEquivalentMg)
        assertEquals(14000.0 * 0.9, liveProfile.peakActiveEquivalentMg!!, 400.0)
    }

    @Test
    fun extendedReleaseMode_producesDelayedBroaderPlateauThanImmediateRelease() {
        val irPk = Pharmacokinetics(
            halfLifeHours = 6.0,
            tMaxHours = 1.5,
            pkRole = "PARENT_DOMINANT",
        )
        val erPk = Pharmacokinetics(
            halfLifeHours = 6.0,
            tMaxHours = 4.0,
            pkRole = "EXTENDED_RELEASE",
        )

        val irSim = PkCurveCalculator.simulate(irPk, "IR", "Total", PkChartMode.SINGLE_DOSE)!!
        val erSim = PkCurveCalculator.simulate(erPk, "ER", "Total", PkChartMode.SINGLE_DOSE)!!

        val irPts = irSim.series.first { it.isParent }.points
        val erPts = erSim.series.first { it.isParent }.points

        val irPeakT = irPts.maxByOrNull { it.second }!!.first
        val erPeakT = erPts.maxByOrNull { it.second }!!.first
        assertTrue(
            "ER peak time ($erPeakT h) should lag IR peak time ($irPeakT h)",
            erPeakT > irPeakT + 1.5
        )

        fun plateauWidthHours(pts: List<Pair<Double, Double>>, threshold: Double): Double {
            val high = pts.filter { it.second >= threshold }
            if (high.size < 2) return 0.0
            return high.last().first - high.first().first
        }

        val irPlateau = plateauWidthHours(irPts, 0.85)
        val erPlateau = plateauWidthHours(erPts, 0.85)
        assertTrue(
            "ER flat-top >=85% plateau width ($erPlateau h) should be >2x IR ($irPlateau h)",
            erPlateau > irPlateau * 2.0
        )
    }

    @Test
    fun includeCarryoverFalse_excludesPriorExperienceEventsWhileKeepingCurrent() {
        val anchorEpoch = 1_760_000_000L
        val winStart = anchorEpoch - 3600L
        val winEnd = anchorEpoch + 24L * 3600L

        val cariEvents = (5 downTo 1).map { daysAgo ->
            RealDoseEvent(
                substanceName = "Cariprazine",
                timeEpochSecond = anchorEpoch - daysAgo * 86400L,
                dose = 1.5,
                units = "mg",
                isFromCurrentExperience = false,
            )
        } + RealDoseEvent(
            substanceName = "Cariprazine",
            timeEpochSecond = anchorEpoch,
            dose = 1.5,
            units = "mg",
            isFromCurrentExperience = true,
        )

        val valproateEvents = listOf(
            RealDoseEvent(
                substanceName = "Sodium valproate",
                timeEpochSecond = anchorEpoch - 12L * 3600L,
                dose = 500.0,
                units = "mg",
                isFromCurrentExperience = false,
            )
        )

        val inputs = listOf(
            LivePkSubstanceInput(
                canonicalName = "Cariprazine",
                displayName = "卡利哌嗪",
                color = AppColor.BLUE,
                pk = cariprazinePk(),
                doseEvents = cariEvents,
            ),
            LivePkSubstanceInput(
                canonicalName = "Sodium valproate",
                displayName = "丙戊酸钠",
                color = AppColor.GREEN,
                pk = Pharmacokinetics(halfLifeHours = 15.0, tMaxHours = 2.0),
                doseEvents = valproateEvents,
            ),
        )

        val withCarryover = PkCurveCalculator.simulateExperienceLivePk(
            substances = inputs,
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = anchorEpoch + 4L * 3600L,
            includeCarryover = true,
        )
        assertEquals(2, withCarryover.profiles.size)
        val cariWith = withCarryover.profiles.first { it.canonicalName == "Cariprazine" }
        assertEquals(5, cariWith.carryoverIngestionCount)
        assertTrue(cariWith.carryoverBaselineFractionAtStart > 0.3f)

        val withoutCarryover = PkCurveCalculator.simulateExperienceLivePk(
            substances = inputs,
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = anchorEpoch + 4L * 3600L,
            includeCarryover = false,
        )
        assertEquals(1, withoutCarryover.profiles.size)
        val cariOnlyCurrent = withoutCarryover.profiles.single()
        assertEquals("Cariprazine", cariOnlyCurrent.canonicalName)
        assertEquals(1, cariOnlyCurrent.currentIngestionCount)
        assertEquals(0, cariOnlyCurrent.carryoverIngestionCount)
        assertEquals(0f, cariOnlyCurrent.carryoverBaselineFractionAtStart, 1e-6f)
    }

    @Test
    fun activeEquivalentMg_reflectsBioavailabilityAndRouteSpecificAbsorption() {
        val anchorEpoch = 1_760_000_000L
        val winStart = anchorEpoch
        val winEnd = anchorEpoch + 24L * 3600L
        val pk = Pharmacokinetics(
            halfLifeHours = 8.0,
            tMaxHours = 2.0,
            bioavailability = "60%",
        )

        val oralSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "Morphine",
                    displayName = "Morphine",
                    color = AppColor.BLUE,
                    pk = pk,
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "Morphine",
                            timeEpochSecond = anchorEpoch,
                            dose = 100.0,
                            units = "mg",
                            isFromCurrentExperience = true,
                            route = AdministrationRoute.ORAL,
                            bioavailabilityFactor = 0.6,
                        )
                    )
                )
            ),
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = anchorEpoch + 2L * 3600L,
        )
        val oralProfile = oralSim.profiles.single()
        assertEquals(60.0, oralProfile.peakActiveEquivalentMg!!, 1.0)
        assertEquals(60.0, oralProfile.currentActiveEquivalentMg!!, 1.0)

        val ivSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "Morphine",
                    displayName = "Morphine",
                    color = AppColor.RED,
                    pk = pk,
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "Morphine",
                            timeEpochSecond = anchorEpoch,
                            dose = 100.0,
                            units = "mg",
                            isFromCurrentExperience = true,
                            route = AdministrationRoute.INTRAVENOUS,
                            bioavailabilityFactor = 1.0,
                        )
                    )
                )
            ),
            windowStartEpochSecond = winStart,
            windowEndEpochSecond = winEnd,
            currentTimeEpochSecond = anchorEpoch + 180L,
        )
        val ivProfile = ivSim.profiles.single()
        assertEquals(100.0, ivProfile.peakActiveEquivalentMg!!, 1.0)
        val ivPeakHour = ivProfile.totalActivePoints.maxByOrNull { it.normalizedValue }!!.hoursFromStart
        val oralPeakHour = oralProfile.totalActivePoints.maxByOrNull { it.normalizedValue }!!.hoursFromStart
        assertTrue("IV peak ($ivPeakHour h) must precede ORAL peak ($oralPeakHour h)", ivPeakHour < 0.2f && oralPeakHour >= 1.5f)
    }

    @Test
    fun pharmacokineticsHelpers_resolveRouteTMaxAndBioavailabilityCorrectly() {
        val pk = Pharmacokinetics(
            halfLifeHours = 10.0,
            tMaxHours = 2.0,
            bioavailability = "口服 30%；肌注 90%",
        )
        assertEquals(0.05, pk.resolveTMaxHoursForRoute(AdministrationRoute.INTRAVENOUS), 0.01)
        assertEquals(0.10, pk.resolveTMaxHoursForRoute(AdministrationRoute.SMOKED), 0.02)
        assertTrue(pk.resolveTMaxHoursForRoute(AdministrationRoute.TRANSDERMAL) >= 6.0)
        assertEquals(2.0, pk.resolveTMaxHoursForRoute(AdministrationRoute.ORAL), 0.01)

        val roaWithDuration = Roa(
            route = AdministrationRoute.INSUFFLATED,
            roaDose = null,
            roaDuration = RoaDuration(
                onset = DurationRange(min = 5f, max = 15f, units = DurationUnits.MINUTES),
                comeup = DurationRange(min = 10f, max = 20f, units = DurationUnits.MINUTES),
                peak = null,
                offset = null,
                total = null,
                afterglow = null,
            ),
            bioavailability = Bioavailability(min = 60.0, max = 80.0),
        )
        assertEquals(25.0 / 60.0, pk.resolveTMaxHoursForRoute(AdministrationRoute.INSUFFLATED, roaWithDuration), 0.02)
        assertEquals(0.70, pk.resolveBioavailabilityFraction(AdministrationRoute.INSUFFLATED, roaWithDuration), 0.01)
        assertEquals(0.42, pk.resolveBioavailabilityFraction(AdministrationRoute.INSUFFLATED, roaWithDuration, customFraction = 0.42), 0.001)
        assertEquals(1.0, pk.resolveBioavailabilityFraction(AdministrationRoute.INTRAVENOUS), 0.001)
        assertEquals(0.30, pk.resolveBioavailabilityFraction(AdministrationRoute.ORAL), 0.01)
        assertEquals(0.90, pk.resolveBioavailabilityFraction(AdministrationRoute.INTRAMUSCULAR), 0.01)
        assertNotNull(pk.toFallbackRoaDuration(AdministrationRoute.INTRAVENOUS))
        assertNotNull(pk.toFallbackRoaDuration())
    }

    @Test
    fun enzymaticDdiModifier_detectsInhibitionInductionProdrugBlockadeAndBaselineComparison() {
        val fluoxetine = makeSubstance(
            name = "Fluoxetine",
            pk = Pharmacokinetics(
                halfLifeHours = 72.0,
                tMaxHours = 7.0,
                cypEnzymes = listOf("CYP2D6(强强抑制)", "CYP2C19", "CYP3A4", "CYP2C9"),
            ),
        )
        val codeine = makeSubstance(
            name = "Codeine",
            pk = Pharmacokinetics(
                halfLifeHours = 3.0,
                tMaxHours = 1.2,
                pkRole = "PRODRUG",
                effectiveHalfLifeHours = 3.5,
                washoutHours = 24.0,
                cypEnzymes = listOf("CYP2D6 (将约 5%-10% 可待因 O-脱甲基活化为吗啡；关键限速酶)", "UGT2B7 (将 70%-80% 转化为 C6G)"),
                activeMetabolites = listOf(
                    ActiveMetabolite(
                        name = "Morphine",
                        pathway = "经 CYP2D6 O-脱甲基生成",
                        halfLifeHours = 3.0,
                        tMaxHours = 1.2,
                        exposureRatio = 0.25,
                    ),
                    ActiveMetabolite(
                        name = "C6G",
                        pathway = "经 UGT2B7 直接葡糖醛酸化生成",
                        halfLifeHours = 4.0,
                        exposureRatio = 1.2,
                    ),
                ),
            ),
        )
        val dxm = makeSubstance(
            name = "Dextromethorphan",
            pk = Pharmacokinetics(
                halfLifeHours = 4.0,
                tMaxHours = 2.0,
                pkRole = "ACTIVE_METABOLITE",
                cypEnzymes = listOf("CYP2D6", "CYP3A4"),
                activeMetabolites = listOf(
                    ActiveMetabolite(
                        name = "Dextrorphan",
                        pathway = "经 CYP2D6 O-脱甲基生成",
                        halfLifeHours = 4.0,
                        exposureRatio = 1.0,
                    )
                ),
            ),
        )

        // 1. Fluoxetine + Codeine 前药阻滞
        val codeineFindings = EnzymaticDdiModifier.analyzeCoAdministeredSubstances(listOf(fluoxetine, codeine))
        val cyp2d6Codeine = codeineFindings.firstOrNull {
            it.perpetratorName == "Fluoxetine" && it.victimName == "Codeine" && it.enzyme == "CYP2D6"
        }
        assertNotNull(cyp2d6Codeine)
        cyp2d6Codeine!!
        assertEquals(CypInteractionEffectType.INHIBITION, cyp2d6Codeine.effectType)
        assertEquals(2.5, cyp2d6Codeine.halfLifeMultiplier, 0.01)
        assertEquals(0.3, cyp2d6Codeine.activeMetaboliteMultiplier, 0.01)
        assertTrue(cyp2d6Codeine.isProdrugActivationBlocked)

        val modifiedCodeinePk = EnzymaticDdiModifier.applyModifiers("Codeine", codeine.pharmacokinetics!!, codeineFindings)
        assertEquals(7.5, modifiedCodeinePk.halfLifeHours!!, 0.01)
        assertEquals(0.075, modifiedCodeinePk.activeMetabolites.first { it.name == "Morphine" }.exposureRatio!!, 0.001)
        assertEquals(1.2, modifiedCodeinePk.activeMetabolites.first { it.name == "C6G" }.exposureRatio!!, 0.001)

        // 2. Fluoxetine + Dextromethorphan
        val dxmFindings = EnzymaticDdiModifier.analyzeCoAdministeredSubstances(listOf(fluoxetine, dxm))
        val cyp2d6Dxm = dxmFindings.firstOrNull {
            it.perpetratorName == "Fluoxetine" && it.victimName == "Dextromethorphan" && it.enzyme == "CYP2D6"
        }
        assertNotNull(cyp2d6Dxm)
        assertTrue(cyp2d6Dxm!!.isProdrugActivationBlocked)
        assertEquals(0.3, cyp2d6Dxm.activeMetaboliteMultiplier, 0.01)

        // 3. Fluvoxamine + Caffeine (CYP1A2 抑制)
        val fluvoxamine = makeSubstance(
            name = "Fluvoxamine",
            pk = Pharmacokinetics(halfLifeHours = 18.0, tMaxHours = 5.0, cypEnzymes = listOf("CYP1A2(强效抑制剂)")),
        )
        val caffeine = makeSubstance(
            name = "Caffeine",
            pk = Pharmacokinetics(halfLifeHours = 5.0, tMaxHours = 1.0, cypEnzymes = listOf("CYP1A2 (90%)")),
        )
        val cafFindings = EnzymaticDdiModifier.analyzeCoAdministeredSubstances(listOf(fluvoxamine, caffeine))
        val cafFinding = cafFindings.single { it.victimName == "Caffeine" }
        assertEquals("CYP1A2", cafFinding.enzyme)
        assertEquals(CypInteractionEffectType.INHIBITION, cafFinding.effectType)
        assertEquals(2.5, cafFinding.halfLifeMultiplier, 0.01)
        val modifiedCafPk = EnzymaticDdiModifier.applyModifiers("Caffeine", caffeine.pharmacokinetics!!, cafFindings)
        assertEquals(12.5, modifiedCafPk.halfLifeHours!!, 0.01)

        // 4. Carbamazepine + Alprazolam (CYP3A4 诱导)
        val carbamazepine = makeSubstance(
            name = "Carbamazepine",
            pk = Pharmacokinetics(halfLifeHours = 15.0, cypEnzymes = listOf("自身强效诱导剂 (诱导 CYP3A4, CYP1A2, CYP2C9, UGT)")),
        )
        val alprazolam = makeSubstance(
            name = "Alprazolam",
            pk = Pharmacokinetics(halfLifeHours = 11.2, tMaxHours = 1.5, cypEnzymes = listOf("CYP3A4 (主要代谢酶)")),
        )
        val alpFindings = EnzymaticDdiModifier.analyzeCoAdministeredSubstances(listOf(carbamazepine, alprazolam))
        val alpFinding = alpFindings.firstOrNull { it.victimName == "Alprazolam" && it.enzyme == "CYP3A4" }
        assertNotNull(alpFinding)
        assertEquals(CypInteractionEffectType.INDUCTION, alpFinding!!.effectType)
        assertEquals(0.5, alpFinding.halfLifeMultiplier, 0.01)
        val modifiedAlpPk = EnzymaticDdiModifier.applyModifiers("Alprazolam", alprazolam.pharmacokinetics!!, alpFindings)
        assertEquals(5.6, modifiedAlpPk.halfLifeHours!!, 0.01)

        // 5. Citalopram / Escitalopram 弱抑应忽略
        val citalopram = makeSubstance(
            name = "Citalopram",
            pk = Pharmacokinetics(halfLifeHours = 35.0, cypEnzymes = listOf("CYP2C19", "CYP3A4", "CYP2D6")),
        )
        val citFindings = EnzymaticDdiModifier.analyzeCoAdministeredSubstances(listOf(citalopram, codeine))
        assertFalse(citFindings.any { it.victimName == "Codeine" })

        // 6. 联用对照曲线 unadjustedTotalActivePoints 对比验证
        val anchorEpoch = 1_760_000_000L
        val ddiLiveSim = PkCurveCalculator.simulateExperienceLivePk(
            substances = listOf(
                LivePkSubstanceInput(
                    canonicalName = "Caffeine",
                    displayName = "咖啡因",
                    color = AppColor.BROWN,
                    pk = modifiedCafPk,
                    unadjustedPk = caffeine.pharmacokinetics,
                    ddiFindings = cafFindings.filter { it.victimName == "Caffeine" },
                    doseEvents = listOf(
                        RealDoseEvent(
                            substanceName = "Caffeine",
                            timeEpochSecond = anchorEpoch,
                            dose = 100.0,
                            units = "mg",
                            isFromCurrentExperience = true,
                            bioavailabilityFactor = 0.99,
                        )
                    )
                )
            ),
            windowStartEpochSecond = anchorEpoch,
            windowEndEpochSecond = anchorEpoch + 24L * 3600L,
            currentTimeEpochSecond = anchorEpoch + 12L * 3600L,
        )
        val cafProfile = ddiLiveSim.profiles.single()
        assertEquals(cafProfile.totalActivePoints.size, cafProfile.unadjustedTotalActivePoints.size)
        val adjAt12h = cafProfile.totalActivePoints.minByOrNull { abs(it.hoursFromStart - 12f) }!!.normalizedValue
        val baseAt12h = cafProfile.unadjustedTotalActivePoints.minByOrNull { abs(it.hoursFromStart - 12f) }!!.normalizedValue
        assertTrue(
            "Inhibited Caffeine at 12h ($adjAt12h) must substantially exceed unadjusted baseline ($baseAt12h)",
            adjAt12h > baseAt12h * 2.0f
        )
    }

    private fun makeSubstance(
        name: String,
        pk: Pharmacokinetics?,
        commonNames: List<String> = emptyList(),
        roas: List<Roa> = emptyList(),
    ) = Substance(
        name = name,
        commonNames = commonNames,
        url = "https://example.org/$name",
        isApproved = true,
        tolerance = null,
        crossTolerances = emptyList(),
        addictionPotential = null,
        toxicities = emptyList(),
        categories = emptyList(),
        roas = roas,
        pharmacokinetics = pk,
    )
}
