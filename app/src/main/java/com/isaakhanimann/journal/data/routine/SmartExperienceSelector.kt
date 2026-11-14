package com.isaakhanimann.journal.data.routine

import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestions
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

object SmartExperienceSelector {

    const val MIN_WINDOW_HOURS = 12.0
    const val MAX_WINDOW_HOURS = 48.0
    const val MAX_INTERACTION_LOOKBACK_HOURS = 48L

    fun resolveSmartWindowHours(
        customHalfLifeHours: Double?,
        substance: Substance?,
        route: AdministrationRoute
    ): Double? {
        val roaDurationHours = substance
            ?.getRoa(route)
            ?.roaDuration
            ?.total
            ?.maxInSec
            ?.let { it.toDouble() / 3600.0 }
        return resolveSmartWindowHours(
            customHalfLifeHours = customHalfLifeHours,
            substanceHalfLifeHours = substance?.pharmacokinetics?.halfLifeHours,
            roaTotalMaxHours = roaDurationHours
        )
    }

    fun resolveSmartWindowHours(
        customHalfLifeHours: Double?,
        substanceHalfLifeHours: Double?,
        roaTotalMaxHours: Double?
    ): Double? {
        val raw = customHalfLifeHours?.takeIf { it > 0.0 }
            ?: substanceHalfLifeHours?.takeIf { it > 0.0 }
            ?: roaTotalMaxHours?.takeIf { it > 0.0 }
            ?: return null
        return raw.coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)
    }

    fun selectTargetNonRoutineExperience(
        doseTime: Instant,
        windowHours: Double?,
        experiences: List<ExperienceWithIngestions>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExperienceWithIngestions? {
        val doseDate = doseTime.atZone(zoneId).toLocalDate()
        val windowMillis = windowHours?.let { (it * 3_600_000.0).toLong() }
        val tailMillis = windowHours?.let { (minOf(MIN_WINDOW_HOURS, it) * 3_600_000.0).toLong() }

        val candidates = experiences
            .asSequence()
            .filter { !it.experience.isRoutineOnly }
            .mapNotNull { expWithIng ->
                val expDate = expWithIng.experience.sortDate.atZone(zoneId).toLocalDate()
                val sameCalendarDay = expDate == doseDate
                val expStart = expWithIng.ingestions.minOfOrNull { it.time } ?: expWithIng.experience.sortDate
                val expEnd = (expWithIng.ingestions.maxOfOrNull { it.endTime ?: it.time }
                    ?: expWithIng.experience.sortDate).let { end ->
                    if (end.isBefore(expStart)) expStart else end
                }

                val matches = if (windowMillis == null || tailMillis == null) {
                    sameCalendarDay
                } else {
                    sameCalendarDay || (
                        !doseTime.isBefore(expStart.minusMillis(windowMillis)) &&
                            !doseTime.isAfter(expEnd.plusMillis(tailMillis))
                        )
                }
                if (!matches) {
                    null
                } else {
                    Triple(
                        expWithIng,
                        if (sameCalendarDay) 0 else 1,
                        abs(Duration.between(expStart, doseTime).toMillis())
                    )
                }
            }
            .toList()

        return candidates.minWithOrNull(
            compareBy<Triple<ExperienceWithIngestions, Int, Long>> { it.second }
                .thenBy { it.third }
                .thenByDescending { it.first.experience.id }
        )?.first
    }

    fun selectSameDayRoutineOnlyExperience(
        doseTime: Instant,
        experiences: List<ExperienceWithIngestions>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ExperienceWithIngestions? {
        val doseDate = doseTime.atZone(zoneId).toLocalDate()
        return experiences
            .filter {
                it.experience.isRoutineOnly &&
                    it.experience.sortDate.atZone(zoneId).toLocalDate() == doseDate
            }
            .minByOrNull { abs(Duration.between(it.experience.sortDate, doseTime).toMillis()) }
    }

    fun isRoutineDoseActiveForInteractions(
        routineTime: Instant,
        experienceStartTime: Instant,
        experienceEndTime: Instant,
        windowHours: Double?
    ): Boolean {
        val effectiveHours = (windowHours ?: MIN_WINDOW_HOURS).coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)
        val routineActiveEnd = routineTime.plusMillis((effectiveHours * 3_600_000.0).toLong())
        val minExperienceEnd = experienceStartTime.plusMillis((MIN_WINDOW_HOURS * 3_600_000.0).toLong())
        val effectiveExpEnd = if (experienceEndTime.isAfter(minExperienceEnd)) experienceEndTime else minExperienceEnd
        return !routineTime.isAfter(effectiveExpEnd) && !routineActiveEnd.isBefore(experienceStartTime)
    }
}
