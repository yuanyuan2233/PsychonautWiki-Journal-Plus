package com.isaakhanimann.journal.data.routine

import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

data class ScheduledOccurrence(
    val scheduleId: Int,
    val slotId: String,
    val scheduledEpochMillis: Long,
    val scheduledInstant: Instant,
    val slot: ScheduleTimeSlot
)

object RecurrenceCalculator {

    private const val MAX_LOOKAHEAD_YEARS = 5L
    private const val MAX_ITERATION_OCCURRENCES = 20_000

    fun getOccurrencesInRange(
        schedule: MedicationSchedule,
        fromInclusive: Instant,
        toInclusive: Instant,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<ScheduledOccurrence> {
        if (fromInclusive.isAfter(toInclusive)) return emptyList()
        return collectOccurrences(
            schedule = schedule,
            rangeStartInclusive = fromInclusive,
            rangeEndInclusive = toInclusive,
            stopAfterFirst = false,
            zoneId = zoneId
        )
    }

    fun getNextOccurrenceAfter(
        schedule: MedicationSchedule,
        afterExclusive: Instant,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ScheduledOccurrence? {
        val horizonEnd = afterExclusive.atZone(zoneId).plusYears(MAX_LOOKAHEAD_YEARS).toInstant()
        return collectOccurrences(
            schedule = schedule,
            rangeStartInclusive = afterExclusive.plusMillis(1),
            rangeEndInclusive = horizonEnd,
            stopAfterFirst = true,
            zoneId = zoneId
        ).firstOrNull()
    }

    private fun collectOccurrences(
        schedule: MedicationSchedule,
        rangeStartInclusive: Instant,
        rangeEndInclusive: Instant,
        stopAfterFirst: Boolean,
        zoneId: ZoneId
    ): List<ScheduledOccurrence> {
        val slots = schedule.decodedSlots()
            .sortedWith(compareBy<ScheduleTimeSlot> { it.hour }.thenBy { it.minute }.thenBy { it.id })
        if (slots.isEmpty()) return emptyList()

        val maxOccurrences = schedule.maxOccurrences
        if (maxOccurrences != null && maxOccurrences <= 0) return emptyList()

        if (schedule.recurrenceType == RecurrenceType.EVERY_N_MINUTES) {
            return collectIntervalMinuteOccurrences(
                schedule = schedule,
                slot = slots.first(),
                rangeStartInclusive = rangeStartInclusive,
                rangeEndInclusive = rangeEndInclusive,
                stopAfterFirst = stopAfterFirst,
                zoneId = zoneId
            )
        }

        if (schedule.recurrenceType == RecurrenceType.WEEKLY &&
            (schedule.daysOfWeekMask and MedicationSchedule.ALL_DAYS_MASK) == 0
        ) {
            return emptyList()
        }

        val createdDate = schedule.createdAt.atZone(zoneId).toLocalDate()
        val startDate = schedule.startEpochDay?.let(LocalDate::ofEpochDay) ?: createdDate
        val endDate = schedule.endEpochDay?.let(LocalDate::ofEpochDay)

        val lowerTimeBound = if (schedule.startEpochDay == null) {
            schedule.createdAt.truncatedTo(ChronoUnit.MINUTES)
        } else {
            startDate.atStartOfDay(zoneId).toInstant()
        }

        val effectiveEndInstant = if (endDate != null) {
            val endOfDay = endDate.plusDays(1).atStartOfDay(zoneId).toInstant().minusMillis(1)
            if (endOfDay.isBefore(rangeEndInclusive)) endOfDay else rangeEndInclusive
        } else {
            rangeEndInclusive
        }

        if (effectiveEndInstant.isBefore(rangeStartInclusive) || effectiveEndInstant.isBefore(lowerTimeBound)) {
            return emptyList()
        }

        val scanStartDate = if (maxOccurrences != null) {
            startDate
        } else {
            val windowStartDate = rangeStartInclusive.atZone(zoneId).toLocalDate().minusDays(1)
            if (windowStartDate.isAfter(startDate)) windowStartDate else startDate
        }
        val scanEndDate = effectiveEndInstant.atZone(zoneId).toLocalDate().plusDays(1)
        if (scanStartDate.isAfter(scanEndDate)) return emptyList()

        val result = mutableListOf<ScheduledOccurrence>()
        var emittedTotal = 0
        var date = scanStartDate
        while (!date.isAfter(scanEndDate)) {
            if (endDate != null && date.isAfter(endDate)) break
            if (isDateActive(schedule, startDate, date)) {
                for (slot in slots) {
                    val occInstant = ZonedDateTime.of(
                        date,
                        LocalTime.of(slot.hour.coerceIn(0, 23), slot.minute.coerceIn(0, 59)),
                        zoneId
                    ).toInstant()
                    if (occInstant.isBefore(lowerTimeBound)) continue
                    if (occInstant.isAfter(effectiveEndInstant)) continue

                    emittedTotal++
                    if (maxOccurrences != null && emittedTotal > maxOccurrences) {
                        return result
                    }
                    if (emittedTotal > MAX_ITERATION_OCCURRENCES) {
                        return result
                    }

                    if (!occInstant.isBefore(rangeStartInclusive) && !occInstant.isAfter(effectiveEndInstant)) {
                        result.add(
                            ScheduledOccurrence(
                                scheduleId = schedule.id,
                                slotId = slot.id,
                                scheduledEpochMillis = occInstant.toEpochMilli(),
                                scheduledInstant = occInstant,
                                slot = slot
                            )
                        )
                        if (stopAfterFirst) return result
                    }
                }
            }
            date = date.plusDays(1)
        }
        return result
    }

    private fun collectIntervalMinuteOccurrences(
        schedule: MedicationSchedule,
        slot: ScheduleTimeSlot,
        rangeStartInclusive: Instant,
        rangeEndInclusive: Instant,
        stopAfterFirst: Boolean,
        zoneId: ZoneId
    ): List<ScheduledOccurrence> {
        val intervalMillis = schedule.intervalMinutes.coerceAtLeast(1).toLong() * 60_000L
        val anchorMillis = schedule.anchorEpochMillis
        val startDate = schedule.startEpochDay?.let(LocalDate::ofEpochDay)
        val endDate = schedule.endEpochDay?.let(LocalDate::ofEpochDay)

        val lowerBoundMillis = maxOf(
            anchorMillis,
            startDate?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        )
        val upperBoundMillis = minOf(
            rangeEndInclusive.toEpochMilli(),
            endDate?.plusDays(1)?.atStartOfDay(zoneId)?.toInstant()?.toEpochMilli()?.minus(1L)
                ?: Long.MAX_VALUE
        )
        if (upperBoundMillis < rangeStartInclusive.toEpochMilli() || upperBoundMillis < lowerBoundMillis) {
            return emptyList()
        }

        val firstStepIndex = if (lowerBoundMillis > anchorMillis) {
            val diff = lowerBoundMillis - anchorMillis
            (diff + intervalMillis - 1) / intervalMillis
        } else {
            0L
        }

        val startTargetMillis = maxOf(lowerBoundMillis, rangeStartInclusive.toEpochMilli())
        val jumpIndex = if (startTargetMillis > anchorMillis) {
            maxOf(firstStepIndex, (startTargetMillis - anchorMillis) / intervalMillis)
        } else {
            firstStepIndex
        }

        val maxOccurrences = schedule.maxOccurrences
        val result = mutableListOf<ScheduledOccurrence>()
        var k = jumpIndex
        while (true) {
            val emittedIndex = (k - firstStepIndex) + 1
            if (maxOccurrences != null && emittedIndex > maxOccurrences.toLong()) break
            if (emittedIndex > MAX_ITERATION_OCCURRENCES.toLong()) break

            val occMillis = anchorMillis + k * intervalMillis
            if (occMillis > upperBoundMillis) break
            if (occMillis >= rangeStartInclusive.toEpochMilli()) {
                val occInstant = Instant.ofEpochMilli(occMillis)
                result.add(
                    ScheduledOccurrence(
                        scheduleId = schedule.id,
                        slotId = slot.id,
                        scheduledEpochMillis = occMillis,
                        scheduledInstant = occInstant,
                        slot = slot
                    )
                )
                if (stopAfterFirst) return result
            }
            k++
        }
        return result
    }

    internal fun isDateActive(
        schedule: MedicationSchedule,
        startDate: LocalDate,
        date: LocalDate
    ): Boolean {
        val daysSinceStart = ChronoUnit.DAYS.between(startDate, date)
        if (daysSinceStart < 0L) return false
        return when (schedule.recurrenceType) {
            RecurrenceType.DAILY -> true
            RecurrenceType.WEEKLY -> MedicationSchedule.isDaySelected(
                schedule.daysOfWeekMask,
                date.dayOfWeek.value
            )
            RecurrenceType.EVERY_N_DAYS -> {
                val interval = schedule.intervalDays.coerceAtLeast(1).toLong()
                daysSinceStart % interval == 0L
            }
            RecurrenceType.CYCLE_ON_OFF -> {
                val onDays = schedule.cycleOnDays.coerceAtLeast(1).toLong()
                val offDays = schedule.cycleOffDays.coerceAtLeast(0).toLong()
                val cycleLength = onDays + offDays
                (daysSinceStart % cycleLength) < onDays
            }
            RecurrenceType.EVERY_N_MINUTES -> true
        }
    }
}
