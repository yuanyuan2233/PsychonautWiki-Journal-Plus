/*
 * Copyright (c) 2026. PsychonautWiki Journal Chinese Maintenance Branch.
 */

package com.isaakhanimann.journal.ui.tabs.stats.tolerance

import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.substances.classes.Category
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.SubstanceWithCategories
import com.isaakhanimann.journal.data.substances.classes.Tolerance
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class ToleranceCalculatorTest {

    @Test
    fun testToleranceTimeParser() {
        assertEquals(120.0, ToleranceTimeParser.parseTimeToHours("5 days")!!, 0.01)
        assertEquals(120.0, ToleranceTimeParser.parseTimeToHours("5 天")!!, 0.01)
        assertEquals(720.0, ToleranceTimeParser.parseTimeToHours("1 month")!!, 0.01)
        assertEquals(720.0, ToleranceTimeParser.parseTimeToHours("1 个月")!!, 0.01)
        assertEquals(336.0, ToleranceTimeParser.parseTimeToHours("2 weeks")!!, 0.01)
        assertEquals(336.0, ToleranceTimeParser.parseTimeToHours("2 周")!!, 0.01)
        assertEquals(96.0, ToleranceTimeParser.parseTimeToHours("3-5 天")!!, 0.01)
        
        // 经典物质兜底对照测试
        assertEquals(120.0, ToleranceTimeParser.getZeroToleranceInHours("LSD", null)!!, 0.01)
        assertEquals(72.0, ToleranceTimeParser.getHalfToleranceInHours("Psilocybin", null)!!, 0.01)
        assertEquals(1800.0, ToleranceTimeParser.getZeroToleranceInHours("MDMA", null)!!, 0.01)
        assertEquals(36.0, ToleranceTimeParser.getHalfToleranceInHours("2C-B", null)!!, 0.01)
        assertEquals(72.0, ToleranceTimeParser.getZeroToleranceInHours("2C-B", null)!!, 0.01)
        assertEquals(2.0, ToleranceTimeParser.getZeroToleranceInHours("Changa", null)!!, 0.01)

        // 回归：4-AcO-DMT 不得因包含 "DMT" 子串而误命中 DMT 的 2 小时归零期
        assertNull(ToleranceTimeParser.getZeroToleranceInHours("4-AcO-DMT", null))
        assertEquals(120.0, ToleranceTimeParser.getZeroToleranceInHours("lsd", null)!!, 0.01)

        // 回归："within 30 minutes" 不得被词首外的字母 'h' 误判为小时，逗号小数与连字符正常解析
        assertEquals(0.5, ToleranceTimeParser.parseTimeToHours("within 30 minutes")!!, 0.001)
        assertEquals(2.0, ToleranceTimeParser.parseTimeToHours("within 2 hours")!!, 0.001)
        assertEquals(24.0, ToleranceTimeParser.parseTimeToHours("24h")!!, 0.001)
        assertEquals(36.0, ToleranceTimeParser.parseTimeToHours("1,5 天")!!, 0.001)
    }

    @Test
    fun testSubtractIntervals() {
        val now = Instant.now()
        val t0 = now
        val t1 = now.plus(1, ChronoUnit.HOURS)
        val t2 = now.plus(2, ChronoUnit.HOURS)
        val t3 = now.plus(3, ChronoUnit.HOURS)
        val t4 = now.plus(4, ChronoUnit.HOURS)

        // 目标区间 [t0, t4]，扣减 [t1, t3] -> 应该分裂为 [t0, t1] 和 [t3, t4]
        val target = listOf(DateInterval(t0, t4))
        val subtracting = listOf(DateInterval(t1, t3))

        val result = ToleranceCalculator.subtractIntervals(target, subtracting)
        assertEquals(2, result.size)
        assertEquals(t0, result[0].start)
        assertEquals(t1, result[0].end)
        assertEquals(t3, result[1].start)
        assertEquals(t4, result[1].end)
    }

    @Test
    fun testMergeDateIntervals() {
        val now = Instant.now()
        val t0 = now
        val t1 = now.plus(1, ChronoUnit.HOURS)
        val t2 = now.plus(2, ChronoUnit.HOURS)
        val t3 = now.plus(3, ChronoUnit.HOURS)

        // [t0, t2] 与 [t1, t3] 有交叠，合并后应为 [t0, t3]
        val intervals = listOf(
            DateInterval(t0, t2),
            DateInterval(t1, t3)
        )
        val merged = ToleranceCalculator.mergeDateIntervals(intervals)
        assertEquals(1, merged.size)
        assertEquals(t0, merged[0].start)
        assertEquals(t3, merged[0].end)
    }

    private fun createPsilocybinRepo(): SubstanceRepositoryInterface = object : SubstanceRepositoryInterface {
        override fun getAllSubstances(): List<Substance> = emptyList()
        override fun getAllSubstancesWithCategories(): List<SubstanceWithCategories> = emptyList()
        override fun getAllCategories(): List<Category> = emptyList()
        override fun getCategory(categoryName: String): Category? = null
        override fun getSubstanceWithCategories(substanceName: String): SubstanceWithCategories? = null
        override fun getSubstance(substanceName: String): Substance? {
            if (substanceName == "Psilocybin") {
                return Substance(
                    name = "Psilocybin",
                    commonNames = listOf("裸盖菇素"),
                    url = "https://psychonautwiki.org/wiki/Psilocybin",
                    isApproved = true,
                    tolerance = Tolerance(
                        full = "almost immediately after ingestion",
                        half = "3 days",
                        zero = "7 days"
                    ),
                    crossTolerances = listOf("psychedelics"),
                    addictionPotential = "not habit-forming",
                    toxicities = emptyList(),
                    categories = listOf("psychedelic"),
                    roas = emptyList()
                )
            }
            return null
        }
    }

    @Test
    fun testGetToleranceWindowsEndToEnd() {
        val now = Instant.now()
        val fakeRepo = createPsilocybinRepo()

        val ingestions = listOf(
            SubstanceAndDay("Psilocybin", now.minus(5, ChronoUnit.DAYS))
        )

        val windows = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = ingestions,
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo
        )

        // 应该产生 FULL 和 HALF 两个窗口
        assertTrue("Windows should not be empty", windows.isNotEmpty())
        val fullWindow = windows.find { it.toleranceType == ToleranceType.FULL }
        val halfWindow = windows.find { it.toleranceType == ToleranceType.HALF }

        assertNotNull("Should have Full tolerance window", fullWindow)
        assertNotNull("Should have Half tolerance window", halfWindow)
        assertEquals(fullWindow!!.end, halfWindow!!.start)
    }

    @Test
    fun testExtremeCustomDaysAreClampedWithoutOverflow() {
        val baseTime = Instant.parse("2026-01-01T12:00:00Z")
        val fakeRepo = createPsilocybinRepo()
        val maxSeconds = ToleranceCalculator.MAX_TOLERANCE_SECONDS

        // 1. 极大有限天数（1e20 / Double.MAX_VALUE 级别）必须被安全钳制到 3650 天，不抛 DateTimeException
        val extremeBoth = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("CustomX", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "CustomX" to SubstanceToleranceCustomization(
                    substanceName = "CustomX",
                    customFullDays = 1e20,
                    customZeroDays = 1e308
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(1, extremeBoth.size)
        assertEquals(ToleranceType.FULL, extremeBoth[0].toleranceType)
        assertEquals(baseTime.plusSeconds(maxSeconds), extremeBoth[0].end)

        // 2. fullDays 正常、zeroDays 极大 -> HALF 终点钳制在 3650 天
        val extremeZeroOnly = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("CustomY", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "CustomY" to SubstanceToleranceCustomization(
                    substanceName = "CustomY",
                    customFullDays = 5.0,
                    customZeroDays = 1e20
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        val fullY = extremeZeroOnly.first { it.toleranceType == ToleranceType.FULL }
        val halfY = extremeZeroOnly.first { it.toleranceType == ToleranceType.HALF }
        assertEquals(baseTime.plus(5, ChronoUnit.DAYS), fullY.end)
        assertEquals(fullY.end, halfY.start)
        assertEquals(baseTime.plusSeconds(maxSeconds), halfY.end)

        // 3. NaN / Infinity / 负数 / 0 值应安全退化为空窗口，不崩溃
        val nonFiniteWindows = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(
                SubstanceAndDay("BadNaN", baseTime),
                SubstanceAndDay("BadNeg", baseTime)
            ),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "BadNaN" to SubstanceToleranceCustomization("BadNaN", Double.NaN, Double.POSITIVE_INFINITY),
                "BadNeg" to SubstanceToleranceCustomization("BadNeg", -5.0, 0.0)
            ),
            zoneId = ZoneOffset.UTC
        )
        assertTrue(nonFiniteWindows.isEmpty())

        // 4. 仅设置更小的 customZeroDays（2 天 < 基准 half 3 天）或直接传入 full > zero，不得超过 zeroDays
        val shrunkZeroWindows = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    customFullDays = null,
                    customZeroDays = 2.0
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(1, shrunkZeroWindows.size)
        assertEquals(ToleranceType.FULL, shrunkZeroWindows[0].toleranceType)
        assertEquals(baseTime.plus(2, ChronoUnit.DAYS), shrunkZeroWindows[0].end)

        val invertedWindows = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    customFullDays = 10.0,
                    customZeroDays = 4.0
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(1, invertedWindows.size)
        assertEquals(baseTime.plus(4, ChronoUnit.DAYS), invertedWindows[0].end)
    }

    @Test
    fun testManualResetInstantBoundaries() {
        val baseTime = Instant.parse("2026-01-01T12:00:00Z")
        val fakeRepo = createPsilocybinRepo()

        // 1. manualReset 落在 FULL 期内部（+1 天 < 3 天）-> 仅保留截断后的 FULL，无 HALF
        val resetInFull = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    manualResetInstant = baseTime.plus(1, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(1, resetInFull.size)
        assertEquals(ToleranceType.FULL, resetInFull[0].toleranceType)
        assertEquals(baseTime.plus(1, ChronoUnit.DAYS), resetInFull[0].end)
        assertTrue(resetInFull[0].isUserCustomized)

        // 2. manualReset 恰好等于 fullEnd（+3 天）-> FULL 保留 3 天，HALF 被截断
        val resetAtFullEnd = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    manualResetInstant = baseTime.plus(3, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(1, resetAtFullEnd.size)
        assertEquals(ToleranceType.FULL, resetAtFullEnd[0].toleranceType)
        assertEquals(baseTime.plus(3, ChronoUnit.DAYS), resetAtFullEnd[0].end)

        // 3. manualReset 落在 HALF 期内部（+5 天）且同时保留 customFullDays/customZeroDays
        val resetInHalfWithCustomDays = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    customFullDays = 2.0,
                    customZeroDays = 6.0,
                    manualResetInstant = baseTime.plus(4, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(2, resetInHalfWithCustomDays.size)
        val fullCustom = resetInHalfWithCustomDays.first { it.toleranceType == ToleranceType.FULL }
        val halfCustom = resetInHalfWithCustomDays.first { it.toleranceType == ToleranceType.HALF }
        assertEquals(baseTime.plus(2, ChronoUnit.DAYS), fullCustom.end)
        assertEquals(baseTime.plus(4, ChronoUnit.DAYS), halfCustom.end)

        // 4. manualReset 早于或等于摄入时刻（-2 天 / 0 天）-> 不截断后续新摄入
        val resetBeforeIngestion = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    manualResetInstant = baseTime.minus(2, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(2, resetBeforeIngestion.size)
        assertEquals(baseTime.plus(7, ChronoUnit.DAYS), resetBeforeIngestion.maxOf { it.end })

        // 5. manualReset 晚于自然归零时间（+10 天 >= +7 天）-> 保持原自然归零时间
        val resetAfterZero = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(SubstanceAndDay("Psilocybin", baseTime)),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    manualResetInstant = baseTime.plus(10, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        assertEquals(2, resetAfterZero.size)
        assertEquals(baseTime.plus(7, ChronoUnit.DAYS), resetAfterZero.maxOf { it.end })

        // 6. 两次相隔较远的摄入：较早摄入被 manualReset 截断，较晚新摄入不受旧重置时刻影响
        val t1 = baseTime
        val t2 = baseTime.plus(10, ChronoUnit.DAYS)
        val multiWindows = ToleranceCalculator.getToleranceWindows(
            substanceAndDays = listOf(
                SubstanceAndDay("Psilocybin", t1),
                SubstanceAndDay("Psilocybin", t2)
            ),
            substanceCompanions = emptyList(),
            substanceRepo = fakeRepo,
            customizations = mapOf(
                "Psilocybin" to SubstanceToleranceCustomization(
                    substanceName = "Psilocybin",
                    manualResetInstant = baseTime.plus(2, ChronoUnit.DAYS)
                )
            ),
            zoneId = ZoneOffset.UTC
        )
        val fullIntervals = multiWindows.filter { it.toleranceType == ToleranceType.FULL }.sortedBy { it.start }
        assertEquals(2, fullIntervals.size)
        assertEquals(t1.plus(2, ChronoUnit.DAYS), fullIntervals[0].end)
        assertEquals(t2.plus(3, ChronoUnit.DAYS), fullIntervals[1].end)
        val halfIntervals = multiWindows.filter { it.toleranceType == ToleranceType.HALF }
        assertEquals(1, halfIntervals.size)
        assertEquals(t2.plus(7, ChronoUnit.DAYS), halfIntervals[0].end)
    }

    @Test
    fun testValidateToleranceInputsAndRawRatio() {
        // 表单校验测试
        val emptyBoth = validateToleranceInputs("", "")
        assertFalse(emptyBoth.canSave)
        assertNull(emptyBoth.fullDaysErrorRes)
        assertNull(emptyBoth.zeroDaysErrorRes)

        val validZeroOnly = validateToleranceInputs("", "5.0")
        assertTrue(validZeroOnly.canSave)
        assertNull(validZeroOnly.fullDays)
        assertEquals(5.0, validZeroOnly.zeroDays!!, 0.001)

        val validBounds = validateToleranceInputs("0", "3650")
        assertTrue(validBounds.canSave)
        assertEquals(0.0, validBounds.fullDays!!, 0.001)
        assertEquals(3650.0, validBounds.zeroDays!!, 0.001)

        val negativeZeroNormalized = validateToleranceInputs("-0.0", "1.0")
        assertTrue(negativeZeroNormalized.canSave)
        assertEquals(0L, java.lang.Double.doubleToRawLongBits(negativeZeroNormalized.fullDays!!))

        val fullWithoutZero = validateToleranceInputs("2.0", "")
        assertFalse(fullWithoutZero.canSave)
        assertEquals(R.string.tolerance_sheet_error_zero_required_with_full, fullWithoutZero.zeroDaysErrorRes)

        val fullGreaterThanZero = validateToleranceInputs("6.0", "5.0")
        assertFalse(fullGreaterThanZero.canSave)
        assertEquals(R.string.tolerance_sheet_error_full_exceeds_zero, fullGreaterThanZero.fullDaysErrorRes)

        listOf("1e20", "NaN", "Infinity", "-1", "3650.1", "abc", " ").forEach { bad ->
            val badFull = validateToleranceInputs(bad, "5.0")
            assertFalse("Expected invalid fullDays for '$bad'", badFull.canSave)
            assertEquals(R.string.tolerance_sheet_error_invalid_days, badFull.fullDaysErrorRes)

            val badZero = validateToleranceInputs("", bad)
            assertFalse("Expected invalid zeroDays for '$bad'", badZero.canSave)
            assertEquals(R.string.tolerance_sheet_error_invalid_days, badZero.zeroDaysErrorRes)
        }

        // 未夹断线性比例 rawRatio 测试
        val rangeStart = Instant.parse("2026-01-01T00:00:00Z")
        val totalSeconds = 40L * 86400L // e.g. 30天历史 + 10天未来
        assertEquals(0f, computeToleranceRawRatio(rangeStart, rangeStart, totalSeconds), 0.0001f)
        assertEquals(0.5f, computeToleranceRawRatio(rangeStart.plus(20, ChronoUnit.DAYS), rangeStart, totalSeconds), 0.0001f)
        assertEquals(1f, computeToleranceRawRatio(rangeStart.plus(40, ChronoUnit.DAYS), rangeStart, totalSeconds), 0.0001f)
        assertTrue(computeToleranceRawRatio(rangeStart.minus(5, ChronoUnit.DAYS), rangeStart, totalSeconds) < 0f)
        assertTrue(computeToleranceRawRatio(rangeStart.plus(75, ChronoUnit.DAYS), rangeStart, totalSeconds) > 1f)
    }
}
