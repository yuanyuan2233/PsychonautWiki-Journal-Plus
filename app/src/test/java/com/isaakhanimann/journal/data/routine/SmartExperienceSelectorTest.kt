package com.isaakhanimann.journal.data.routine

import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestions
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class SmartExperienceSelectorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()
    }

    private fun expWithIng(
        id: Int,
        sortDate: Instant,
        isRoutineOnly: Boolean = false,
        ingestionTimes: List<Instant> = listOf(sortDate)
    ): ExperienceWithIngestions {
        return ExperienceWithIngestions(
            experience = Experience(
                id = id,
                title = "Exp $id",
                text = "",
                creationDate = sortDate,
                sortDate = sortDate,
                isFavorite = false,
                location = null,
                isRoutineOnly = isRoutineOnly
            ),
            ingestions = ingestionTimes.mapIndexed { idx, t ->
                Ingestion(
                    id = id * 100 + idx,
                    substanceName = "Test",
                    time = t,
                    administrationRoute = AdministrationRoute.ORAL,
                    dose = 10.0,
                    isDoseAnEstimate = false,
                    estimatedDoseStandardDeviation = null,
                    units = "mg",
                    experienceId = id,
                    notes = null,
                    stomachFullness = null,
                    consumerName = null,
                    customUnitId = null
                )
            }
        )
    }

    @Test
    fun windowResolutionPrioritizesOverrideThenHalfLifeThenRoaAndClamps() {
        assertEquals(48.0, SmartExperienceSelector.resolveSmartWindowHours(72.0, 20.0, 6.0)!!, 0.01)
        assertEquals(24.0, SmartExperienceSelector.resolveSmartWindowHours(null, 24.0, 6.0)!!, 0.01)
        assertEquals(12.0, SmartExperienceSelector.resolveSmartWindowHours(null, 4.0, 8.0)!!, 0.01)
        assertEquals(16.0, SmartExperienceSelector.resolveSmartWindowHours(null, null, 16.0)!!, 0.01)
        assertNull(SmartExperienceSelector.resolveSmartWindowHours(null, null, null))
    }

    @Test
    fun missingPkFallsBackStrictlyToSameCalendarDay() {
        val yesterdayExp = expWithIng(id = 1, sortDate = zdt(2026, 11, 13, 23, 0))
        val todayExp = expWithIng(id = 2, sortDate = zdt(2026, 11, 14, 20, 0))
        val doseAt1Am = zdt(2026, 11, 14, 1, 0)

        // With unknown PK (null window), Nov 14 01:00 must match Nov 14's experience, NOT Nov 13 23:00
        val matched = SmartExperienceSelector.selectTargetNonRoutineExperience(
            doseTime = doseAt1Am,
            windowHours = null,
            experiences = listOf(yesterdayExp, todayExp),
            zoneId = zone
        )
        assertEquals(2, matched?.experience?.id)

        // When no experience exists on Nov 14 and PK is unknown, return null (stays routine-only)
        val noMatch = SmartExperienceSelector.selectTargetNonRoutineExperience(
            doseTime = doseAt1Am,
            windowHours = null,
            experiences = listOf(yesterdayExp),
            zoneId = zone
        )
        assertNull(noMatch)
    }

    @Test
    fun longHalfLifeMatchesAcrossDayBoundaryWhenWithinWindow() {
        val fluoxetineRoutineTime = zdt(2026, 11, 14, 8, 0) // 36h window
        val nextDayExp = expWithIng(id = 10, sortDate = zdt(2026, 11, 15, 14, 0)) // 30h later

        val matched = SmartExperienceSelector.selectTargetNonRoutineExperience(
            doseTime = fluoxetineRoutineTime,
            windowHours = 36.0,
            experiences = listOf(nextDayExp),
            zoneId = zone
        )
        assertEquals(10, matched?.experience?.id)
    }

    @Test
    fun routineOnlyExperiencesAreNeverSelectedAsNonRoutineTargets() {
        val routineExp = expWithIng(id = 5, sortDate = zdt(2026, 11, 14, 8, 0), isRoutineOnly = true)
        val doseTime = zdt(2026, 11, 14, 12, 0)

        assertNull(
            SmartExperienceSelector.selectTargetNonRoutineExperience(
                doseTime = doseTime,
                windowHours = 24.0,
                experiences = listOf(routineExp),
                zoneId = zone
            )
        )
        assertEquals(
            5,
            SmartExperienceSelector.selectSameDayRoutineOnlyExperience(
                doseTime = doseTime,
                experiences = listOf(routineExp),
                zoneId = zone
            )?.experience?.id
        )
    }

    @Test
    fun activeResidualInteractionCheckCoversLongHalfLifeAcrossDays() {
        val routineYesterday = zdt(2026, 11, 13, 9, 0)
        val expTodayStart = zdt(2026, 11, 14, 18, 0) // 33h later
        val expTodayEnd = zdt(2026, 11, 14, 22, 0)

        assertTrue(
            SmartExperienceSelector.isRoutineDoseActiveForInteractions(
                routineTime = routineYesterday,
                experienceStartTime = expTodayStart,
                experienceEndTime = expTodayEnd,
                windowHours = 36.0
            )
        )
        assertFalse(
            SmartExperienceSelector.isRoutineDoseActiveForInteractions(
                routineTime = routineYesterday,
                experienceStartTime = expTodayStart,
                experienceEndTime = expTodayEnd,
                windowHours = 12.0
            )
        )
    }

    @Test
    fun resolveInteractionWashoutHoursSupportsLongMetaboliteAndIrreversibleWashouts() {
        org.junit.Assert.assertEquals(
            840.0,
            SmartExperienceSelector.resolveInteractionWashoutHours(
                customHalfLifeHours = null,
                substanceWashoutHours = 840.0,
                substanceEffectiveHalfLifeHours = 216.0,
                roaTotalMaxHours = 24.0
            ),
            0.001
        )
        org.junit.Assert.assertEquals(
            336.0,
            SmartExperienceSelector.resolveInteractionWashoutHours(
                customHalfLifeHours = null,
                substanceWashoutHours = 336.0,
                substanceEffectiveHalfLifeHours = 2.5,
                roaTotalMaxHours = 8.0
            ),
            0.001
        )
        org.junit.Assert.assertEquals(
            48.0,
            SmartExperienceSelector.resolveSmartWindowHours(
                customHalfLifeHours = null,
                substanceHalfLifeHours = 336.0,
                roaTotalMaxHours = 24.0
            )!!,
            0.001
        )
        org.junit.Assert.assertEquals(
            1008.0,
            SmartExperienceSelector.resolveInteractionWashoutHours(
                customHalfLifeHours = null,
                substanceWashoutHours = 2000.0,
                substanceEffectiveHalfLifeHours = 720.0,
                roaTotalMaxHours = null
            ),
            0.001
        )
        org.junit.Assert.assertEquals(
            12.0,
            SmartExperienceSelector.resolveInteractionWashoutHours(
                customHalfLifeHours = 1.0,
                substanceWashoutHours = 4.0,
                substanceEffectiveHalfLifeHours = 1.0,
                roaTotalMaxHours = 2.0
            ),
            0.001
        )
        org.junit.Assert.assertEquals(
            360.0,
            SmartExperienceSelector.resolveInteractionWashoutHours(
                customHalfLifeHours = 72.0,
                substanceWashoutHours = 100.0,
                substanceEffectiveHalfLifeHours = 20.0,
                roaTotalMaxHours = null
            ),
            0.001
        )
    }

    @Test
    fun activeResidualInteractionCheckCoversFourteenAndThirtyDayWashouts() {
        val routineDose = zdt(2026, 10, 10, 9, 0)
        val twoWeeksLaterStart = zdt(2026, 10, 23, 12, 0) // ~315h later
        val twoWeeksLaterEnd = zdt(2026, 10, 23, 18, 0)

        assertTrue(
            SmartExperienceSelector.isRoutineDoseActiveForInteractions(
                routineTime = routineDose,
                experienceStartTime = twoWeeksLaterStart,
                experienceEndTime = twoWeeksLaterEnd,
                windowHours = 336.0
            )
        )

        val fortyDaysLaterStart = zdt(2026, 11, 19, 12, 0) // 40d = 960h later
        val fortyDaysLaterEnd = zdt(2026, 11, 19, 16, 0)
        assertTrue(
            SmartExperienceSelector.isRoutineDoseActiveForInteractions(
                routineTime = routineDose,
                experienceStartTime = fortyDaysLaterStart,
                experienceEndTime = fortyDaysLaterEnd,
                windowHours = 1008.0
            )
        )
        assertFalse(
            SmartExperienceSelector.isRoutineDoseActiveForInteractions(
                routineTime = routineDose,
                experienceStartTime = fortyDaysLaterStart,
                experienceEndTime = fortyDaysLaterEnd,
                windowHours = 840.0
            )
        )
    }
}
