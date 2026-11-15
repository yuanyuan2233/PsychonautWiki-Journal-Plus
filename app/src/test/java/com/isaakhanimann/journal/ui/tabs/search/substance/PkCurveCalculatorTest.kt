/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.ui.tabs.search.substance

import com.isaakhanimann.journal.data.substances.classes.ActiveMetabolite
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
