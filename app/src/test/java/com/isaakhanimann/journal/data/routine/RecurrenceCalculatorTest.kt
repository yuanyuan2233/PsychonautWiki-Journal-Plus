package com.isaakhanimann.journal.data.routine

import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleSlotCodec
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecurrenceCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun zdt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Instant {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant()
    }

    @Test
    fun dailyMultiSlotRespectsCreationTimeWhenStartDateUnspecified() {
        val created = zdt(2026, 11, 14, 15, 0)
        val schedule = MedicationSchedule(
            id = 1,
            substanceName = "Caffeine",
            administrationRoute = AdministrationRoute.ORAL,
            recurrenceType = RecurrenceType.DAILY,
            timeSlotsJson = ScheduleSlotCodec.encodeSlots(
                listOf(
                    ScheduleTimeSlot(id = "am", hour = 8, minute = 0, dose = 100.0, units = "mg"),
                    ScheduleTimeSlot(id = "pm", hour = 20, minute = 0, dose = 50.0, units = "mg")
                )
            ),
            anchorEpochMillis = created.toEpochMilli(),
            createdAt = created
        )

        val occurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = zdt(2026, 11, 13, 0, 0),
            toInclusive = zdt(2026, 11, 16, 12, 0),
            zoneId = zone
        )

        val expected = listOf(
            zdt(2026, 11, 14, 20, 0),
            zdt(2026, 11, 15, 8, 0),
            zdt(2026, 11, 15, 20, 0),
            zdt(2026, 11, 16, 8, 0)
        )
        assertEquals(expected, occurrences.map { it.scheduledInstant })
        assertEquals(listOf("pm", "am", "pm", "am"), occurrences.map { it.slotId })
    }

    @Test
    fun weeklyScheduleOnlyTriggersSelectedWeekdays() {
        // 2026-11-16 is Monday, 17 Tue, 18 Wed, 19 Thu, 20 Fri, 21 Sat, 22 Sun
        val start = LocalDate.of(2026, 11, 16)
        val mask = MedicationSchedule.dayBit(1) or MedicationSchedule.dayBit(3) or MedicationSchedule.dayBit(5) // Mon, Wed, Fri
        val schedule = MedicationSchedule(
            id = 2,
            substanceName = "Vitamin D",
            administrationRoute = AdministrationRoute.ORAL,
            recurrenceType = RecurrenceType.WEEKLY,
            daysOfWeekMask = mask,
            timeSlotsJson = ScheduleSlotCodec.encodeSlots(
                listOf(ScheduleTimeSlot(id = "s1", hour = 9, minute = 30))
            ),
            startEpochDay = start.toEpochDay(),
            createdAt = zdt(2026, 11, 16, 0, 0)
        )

        val occurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = zdt(2026, 11, 16, 0, 0),
            toInclusive = zdt(2026, 11, 22, 23, 59),
            zoneId = zone
        )

        assertEquals(
            listOf(
                zdt(2026, 11, 16, 9, 30),
                zdt(2026, 11, 18, 9, 30),
                zdt(2026, 11, 20, 9, 30)
            ),
            occurrences.map { it.scheduledInstant }
        )
    }

    @Test
    fun everyNDaysRespectsIntervalAndEndDate() {
        val start = LocalDate.of(2026, 11, 1)
        val end = LocalDate.of(2026, 11, 8)
        val schedule = MedicationSchedule(
            id = 3,
            substanceName = "B12",
            administrationRoute = AdministrationRoute.SUBLINGUAL,
            recurrenceType = RecurrenceType.EVERY_N_DAYS,
            intervalDays = 3,
            timeSlotsJson = ScheduleSlotCodec.encodeSlots(
                listOf(ScheduleTimeSlot(id = "s1", hour = 10, minute = 0))
            ),
            startEpochDay = start.toEpochDay(),
            endEpochDay = end.toEpochDay(),
            createdAt = zdt(2026, 11, 1, 0, 0)
        )

        val occurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = zdt(2026, 11, 1, 0, 0),
            toInclusive = zdt(2026, 11, 15, 0, 0),
            zoneId = zone
        )

        assertEquals(
            listOf(
                zdt(2026, 11, 1, 10, 0),
                zdt(2026, 11, 4, 10, 0),
                zdt(2026, 11, 7, 10, 0)
            ),
            occurrences.map { it.scheduledInstant }
        )
    }

    @Test
    fun everyNMinutesRespectsAnchorAndMaxOccurrences() {
        val anchor = zdt(2026, 11, 14, 8, 15)
        val schedule = MedicationSchedule(
            id = 4,
            substanceName = "Ibuprofen",
            administrationRoute = AdministrationRoute.ORAL,
            recurrenceType = RecurrenceType.EVERY_N_MINUTES,
            intervalMinutes = 360, // every 6 hours
            anchorEpochMillis = anchor.toEpochMilli(),
            maxOccurrences = 4,
            timeSlotsJson = ScheduleSlotCodec.encodeSlots(
                listOf(ScheduleTimeSlot(id = "q6h", hour = 8, minute = 15, dose = 200.0, units = "mg"))
            ),
            createdAt = anchor
        )

        val occurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = zdt(2026, 11, 14, 10, 0),
            toInclusive = zdt(2026, 11, 16, 23, 59),
            zoneId = zone
        )

        assertEquals(
            listOf(
                zdt(2026, 11, 14, 14, 15),
                zdt(2026, 11, 14, 20, 15),
                zdt(2026, 11, 15, 2, 15)
            ),
            occurrences.map { it.scheduledInstant }
        )
        assertNull(
            RecurrenceCalculator.getNextOccurrenceAfter(
                schedule = schedule,
                afterExclusive = zdt(2026, 11, 15, 2, 15),
                zoneId = zone
            )
        )
    }

    @Test
    fun cycleOnOffFollowsOnThenOffPatternAndMaxOccurrences() {
        val start = LocalDate.of(2026, 11, 1)
        val schedule = MedicationSchedule(
            id = 5,
            substanceName = "Creatine",
            administrationRoute = AdministrationRoute.ORAL,
            recurrenceType = RecurrenceType.CYCLE_ON_OFF,
            cycleOnDays = 3,
            cycleOffDays = 2,
            startEpochDay = start.toEpochDay(),
            maxOccurrences = 5,
            timeSlotsJson = ScheduleSlotCodec.encodeSlots(
                listOf(ScheduleTimeSlot(id = "daily", hour = 12, minute = 0))
            ),
            createdAt = zdt(2026, 11, 1, 0, 0)
        )

        val occurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = zdt(2026, 11, 1, 0, 0),
            toInclusive = zdt(2026, 11, 15, 0, 0),
            zoneId = zone
        )

        // ON: Nov 1, 2, 3 | OFF: Nov 4, 5 | ON: Nov 6, 7 (stops at 5 total)
        assertEquals(
            listOf(
                LocalDateTime.of(2026, 11, 1, 12, 0).atZone(zone).toInstant(),
                LocalDateTime.of(2026, 11, 2, 12, 0).atZone(zone).toInstant(),
                LocalDateTime.of(2026, 11, 3, 12, 0).atZone(zone).toInstant(),
                LocalDateTime.of(2026, 11, 6, 12, 0).atZone(zone).toInstant(),
                LocalDateTime.of(2026, 11, 7, 12, 0).atZone(zone).toInstant()
            ),
            occurrences.map { it.scheduledInstant }
        )
    }
}
