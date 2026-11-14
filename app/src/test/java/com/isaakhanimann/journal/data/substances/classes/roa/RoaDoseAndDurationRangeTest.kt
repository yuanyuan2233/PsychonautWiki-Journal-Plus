package com.isaakhanimann.journal.data.substances.classes.roa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoaDoseAndDurationRangeTest {

    @Test
    fun roaDose_classifiesConvertsAndGuardsEdgeCases() {
        val roaDose = RoaDose(
            units = "mg",
            lightMin = 10.0,
            commonMin = 20.0,
            strongMin = 40.0,
            heavyMin = 80.0
        )

        assertEquals(DoseClass.THRESHOLD, roaDose.getDoseClass(5.0))
        assertEquals(DoseClass.LIGHT, roaDose.getDoseClass(10.0))
        assertEquals(DoseClass.COMMON, roaDose.getDoseClass(25.0))
        assertEquals(DoseClass.STRONG, roaDose.getDoseClass(40.0))
        assertEquals(DoseClass.HEAVY, roaDose.getDoseClass(100.0))

        // 单位换算：0.05 g = 50 mg -> STRONG; 5000 µg = 5 mg -> THRESHOLD
        assertEquals(DoseClass.STRONG, roaDose.getDoseClass(0.05, "g"))
        assertEquals(DoseClass.THRESHOLD, roaDose.getDoseClass(5000.0, "µg"))

        // 异常输入：负数、NaN、null 均安全返回 null
        assertNull(roaDose.getDoseClass(-1.0))
        assertNull(roaDose.getDoseClass(Double.NaN))
        assertNull(roaDose.getDoseClass(Double.POSITIVE_INFINITY))
        assertNull(roaDose.getDoseClass(null))

        // heavyMin == 0 回归防线：不得除以零或无限循环
        val zeroHeavy = RoaDose("mg", 0.0, 0.0, 0.0, 0.0)
        assertEquals(4, zeroHeavy.getNumDots(10.0))

        // 容量法微量给药判断
        assertTrue(RoaDose("µg", 25.0, 50.0, 100.0, 200.0).shouldUseVolumetricDosing)
        assertTrue(RoaDose("mg", 5.0, 10.0, 20.0, 30.0).shouldUseVolumetricDosing)
        assertFalse(RoaDose("mg", 10.0, 20.0, 40.0, 80.0).shouldUseVolumetricDosing)
    }

    @Test
    fun durationRange_formatsAndInterpolatesSafely() {
        val range = DurationRange(min = 2.0f, max = 4.0f, units = DurationUnits.HOURS)
        assertEquals("2-4h", range.text)
        assertEquals(7200f, range.interpolateAtValueInSeconds(-1f)!!, 0.1f)
        assertEquals(10800f, range.interpolateAtValueInSeconds(0.5f)!!, 0.1f)
        assertEquals(14400f, range.interpolateAtValueInSeconds(2f)!!, 0.1f)

        val sameMinMax = DurationRange(min = 30.0f, max = 30.0f, units = DurationUnits.MINUTES)
        assertEquals("30m", sameMinMax.text)

        val minOnly = DurationRange(min = 3.0f, max = null, units = DurationUnits.HOURS)
        assertEquals("3+h", minOnly.text)

        val maxOnly = DurationRange(min = null, max = 60.0f, units = DurationUnits.SECONDS)
        assertEquals("≤60s", maxOnly.text)

        val noUnits = DurationRange(min = 1.0f, max = 2.0f, units = null)
        assertNull(noUnits.interpolateAtValueInSeconds(0.5f))
    }
}
