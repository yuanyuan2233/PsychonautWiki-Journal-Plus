package com.isaakhanimann.journal.ui.tabs.journal.routine

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import com.isaakhanimann.journal.ui.tabs.search.substance.roa.toReadableString
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object RoutineUiHelpers {
    val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    val DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatInstantDateTime(instant: Instant, zoneId: ZoneId = ZoneId.systemDefault()): String {
        return DATE_TIME_FORMATTER.format(instant.atZone(zoneId))
    }

    fun formatSlotTime(slot: ScheduleTimeSlot): String {
        return "%02d:%02d".format(slot.hour.coerceIn(0, 23), slot.minute.coerceIn(0, 59))
    }

    fun formatEpochDay(epochDay: Long?): String? {
        return epochDay?.let { DATE_FORMATTER.format(LocalDate.ofEpochDay(it)) }
    }

    fun formatDoseText(
        dose: Double?,
        units: String?,
        isEstimate: Boolean,
        sd: Double?,
        customUnit: CustomUnit?
    ): String? {
        if (dose == null) return null
        val unitText = units?.takeIf { it.isNotBlank() }
            ?: customUnit?.let { if (dose == 1.0) it.unit else (it.unitPlural ?: it.unit) }
            ?: ""
        val raw = if (isEstimate && sd != null) {
            "${dose.toReadableString()}±${sd.toReadableString()} $unitText"
        } else if (isEstimate) {
            "~${dose.toReadableString()} $unitText"
        } else {
            "${dose.toReadableString()} $unitText"
        }.trim()
        val calcSuffix = customUnit?.dose?.let { perUnit ->
            val calc = (dose * perUnit).toReadableString()
            " ($calc ${customUnit.originalUnit})"
        }.orEmpty()
        return "$raw$calcSuffix"
    }

    fun formatIngestionDoseText(ingestion: Ingestion, customUnit: CustomUnit?): String? {
        return formatDoseText(
            dose = ingestion.dose,
            units = ingestion.units,
            isEstimate = ingestion.isDoseAnEstimate,
            sd = ingestion.estimatedDoseStandardDeviation,
            customUnit = customUnit
        )
    }

    @Composable
    fun formatRecurrenceSummary(schedule: MedicationSchedule): String {
        val slots = schedule.decodedSlots()
        return when (schedule.recurrenceType) {
            RecurrenceType.DAILY -> stringResource(R.string.routine_summary_daily, slots.size.coerceAtLeast(1))
            RecurrenceType.WEEKLY -> {
                val dayLabels = listOf(
                    1 to stringResource(R.string.routine_weekday_mon),
                    2 to stringResource(R.string.routine_weekday_tue),
                    3 to stringResource(R.string.routine_weekday_wed),
                    4 to stringResource(R.string.routine_weekday_thu),
                    5 to stringResource(R.string.routine_weekday_fri),
                    6 to stringResource(R.string.routine_weekday_sat),
                    7 to stringResource(R.string.routine_weekday_sun)
                ).filter { (iso, _) ->
                    MedicationSchedule.isDaySelected(schedule.daysOfWeekMask, iso)
                }.joinToString("、") { it.second }
                stringResource(R.string.routine_summary_weekly, dayLabels.ifBlank { "-" })
            }
            RecurrenceType.EVERY_N_DAYS -> stringResource(
                R.string.routine_summary_every_n_days,
                schedule.intervalDays.coerceAtLeast(1)
            )
            RecurrenceType.EVERY_N_MINUTES -> {
                val mins = schedule.intervalMinutes.coerceAtLeast(1)
                val h = mins / 60
                val m = mins % 60
                val span = when {
                    h > 0 && m > 0 -> "${h}h ${m}m"
                    h > 0 -> "${h}h"
                    else -> "${m}m"
                }
                stringResource(R.string.routine_summary_every_n_minutes, span)
            }
            RecurrenceType.CYCLE_ON_OFF -> stringResource(
                R.string.routine_summary_cycle,
                schedule.cycleOnDays.coerceAtLeast(1),
                schedule.cycleOffDays.coerceAtLeast(0)
            )
        }
    }
}
