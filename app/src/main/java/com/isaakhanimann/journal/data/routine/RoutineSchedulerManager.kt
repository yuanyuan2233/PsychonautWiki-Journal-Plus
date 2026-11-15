package com.isaakhanimann.journal.data.routine

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.room.experiences.entities.AdaptiveColor
import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrence
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrenceStatus
import com.isaakhanimann.journal.data.room.experiences.entities.SubstanceCompanion
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestions
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepository
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutineSchedulerManager @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val routineDao: RoutineDao,
    private val substanceRepository: SubstanceRepository,
    private val userPreferences: UserPreferences,
    private val notificationHelper: RoutineNotificationHelper
) {
    private val syncMutex = Mutex()
    private val alarmManager: AlarmManager?
        get() = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        val manager = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    suspend fun reconcileAndRescheduleAll(now: Instant = Instant.now()) {
        syncMutex.withLock {
            reconcileAndRescheduleAllLocked(now)
        }
    }

    /**
     * Runs [block] exclusively under [syncMutex] so lifecycle reconciliation or alarm triggers
     * cannot interleave with full-database import, export snapshots, or deletion.
     *
     * Note: [syncMutex] is non-reentrant; callers must not invoke public [RoutineSchedulerManager]
     * methods inside [block].
     */
    suspend fun <T> runWithSyncLock(
        cancelAlarmsFirst: Boolean = false,
        reconcileAfter: Boolean = false,
        block: suspend () -> T
    ): T {
        return syncMutex.withLock {
            if (cancelAlarmsFirst) {
                cancelAllAlarmsLocked()
            }
            try {
                val result = block()
                if (reconcileAfter) {
                    reconcileAndRescheduleAllLocked()
                }
                result
            } catch (e: Throwable) {
                if (reconcileAfter) {
                    runCatching { reconcileAndRescheduleAllLocked() }
                }
                throw e
            }
        }
    }

    private suspend fun reconcileAndRescheduleAllLocked(now: Instant = Instant.now()) {
        val localizedContext = notificationHelper.getLocalizedContext()
        notificationHelper.ensureNotificationChannel(localizedContext)
        val allSchedules = routineDao.getAllSchedulesSorted()
        val catchUpDays = userPreferences.routineCatchUpDaysFlow.first()
        val zoneId = ZoneId.systemDefault()
        for (schedule in allSchedules) {
            if (schedule.isEnabled) {
                reconcileSingleScheduleLocked(
                    schedule = schedule,
                    now = now,
                    catchUpDays = catchUpDays,
                    notifyLiveTargetMillis = null,
                    localizedContext = localizedContext,
                    zoneId = zoneId
                )
            } else {
                cancelAlarmForSchedule(schedule.id)
            }
        }
    }

    suspend fun handleAlarmTrigger(
        scheduleId: Int,
        slotId: String,
        scheduledEpochMillis: Long
    ) {
        syncMutex.withLock {
            val localizedContext = notificationHelper.getLocalizedContext()
            notificationHelper.ensureNotificationChannel(localizedContext)
            val schedule = routineDao.getSchedule(scheduleId)
            if (schedule == null || !schedule.isEnabled) {
                cancelAlarmForSchedule(scheduleId)
                return
            }
            val now = maxOf(Instant.now(), Instant.ofEpochMilli(scheduledEpochMillis))
            val catchUpDays = userPreferences.routineCatchUpDaysFlow.first()
            reconcileSingleScheduleLocked(
                schedule = schedule,
                now = now,
                catchUpDays = catchUpDays,
                notifyLiveTargetMillis = scheduledEpochMillis,
                notifyLiveSlotId = slotId,
                localizedContext = localizedContext,
                zoneId = ZoneId.systemDefault()
            )
        }
    }

    suspend fun saveScheduleAndReschedule(schedule: MedicationSchedule): Int {
        return syncMutex.withLock {
            val savedId = if (schedule.id == 0) {
                routineDao.insertSchedule(schedule).toInt()
            } else {
                routineDao.updateSchedule(schedule)
                schedule.id
            }
            val persisted = routineDao.getSchedule(savedId)
            if (persisted != null && persisted.isEnabled) {
                val localizedContext = notificationHelper.getLocalizedContext()
                notificationHelper.ensureNotificationChannel(localizedContext)
                val catchUpDays = userPreferences.routineCatchUpDaysFlow.first()
                reconcileSingleScheduleLocked(
                    schedule = persisted,
                    now = Instant.now(),
                    catchUpDays = catchUpDays,
                    notifyLiveTargetMillis = null,
                    localizedContext = localizedContext,
                    zoneId = ZoneId.systemDefault()
                )
            } else {
                cancelAlarmForSchedule(savedId)
            }
            savedId
        }
    }

    suspend fun setScheduleEnabled(scheduleId: Int, isEnabled: Boolean) {
        syncMutex.withLock {
            routineDao.setScheduleEnabled(scheduleId, isEnabled, Instant.now())
            val schedule = routineDao.getSchedule(scheduleId)
            if (schedule != null && isEnabled) {
                val localizedContext = notificationHelper.getLocalizedContext()
                val catchUpDays = userPreferences.routineCatchUpDaysFlow.first()
                reconcileSingleScheduleLocked(
                    schedule = schedule,
                    now = Instant.now(),
                    catchUpDays = catchUpDays,
                    notifyLiveTargetMillis = null,
                    localizedContext = localizedContext,
                    zoneId = ZoneId.systemDefault()
                )
            } else {
                cancelAlarmForSchedule(scheduleId)
            }
        }
    }

    suspend fun deleteSchedule(schedule: MedicationSchedule) {
        syncMutex.withLock {
            cancelAlarmForSchedule(schedule.id)
            routineDao.deleteOccurrencesForSchedule(schedule.id)
            routineDao.deleteSchedule(schedule)
        }
    }

    suspend fun confirmDose(ingestionId: Int, notificationId: Int = 0) {
        syncMutex.withLock {
            val ingestion = routineDao.getIngestionById(ingestionId)
            val occ = routineDao.getOccurrenceByIngestionId(ingestionId)
            if (ingestion != null) {
                routineDao.setIngestionConfirmed(ingestionId, true)
                val scheduleId = ingestion.routineScheduleId
                val scheduledMillis = ingestion.scheduledEpochMillis
                if (scheduleId != null && scheduledMillis != null) {
                    notificationHelper.cancelNotification(scheduleId, scheduledMillis, occ?.slotId.orEmpty())
                }
            }
            if (occ != null) {
                routineDao.updateOccurrenceStatus(
                    id = occ.id,
                    status = RoutineOccurrenceStatus.CONFIRMED,
                    ingestionId = ingestionId,
                    handledAt = Instant.now()
                )
            }
            notificationHelper.cancelNotificationById(notificationId)
        }
    }

    suspend fun markDoseUnconfirmed(ingestionId: Int) {
        syncMutex.withLock {
            routineDao.setIngestionConfirmed(ingestionId, false)
            val occ = routineDao.getOccurrenceByIngestionId(ingestionId)
            if (occ != null) {
                routineDao.updateOccurrenceStatus(
                    id = occ.id,
                    status = RoutineOccurrenceStatus.RECORDED_UNCONFIRMED,
                    ingestionId = ingestionId,
                    handledAt = Instant.now()
                )
            }
        }
    }

    suspend fun skipOrDeleteRoutineIngestion(ingestionId: Int, notificationId: Int = 0) {
        syncMutex.withLock {
            val ingestion = routineDao.getIngestionById(ingestionId)
            val occ = routineDao.getOccurrenceByIngestionId(ingestionId)
            if (occ != null) {
                routineDao.updateOccurrenceStatus(
                    id = occ.id,
                    status = RoutineOccurrenceStatus.SKIPPED,
                    ingestionId = null,
                    handledAt = Instant.now()
                )
            }
            if (ingestion != null) {
                routineDao.deleteIngestionById(ingestionId)
                cleanupExperienceIfEmptyRoutineContainerLocked(ingestion.experienceId)
                val scheduleId = ingestion.routineScheduleId
                val scheduledMillis = ingestion.scheduledEpochMillis
                if (scheduleId != null && scheduledMillis != null) {
                    notificationHelper.cancelNotification(scheduleId, scheduledMillis, occ?.slotId.orEmpty())
                }
            }
            notificationHelper.cancelNotificationById(notificationId)
        }
    }

    suspend fun onExperienceDeleted(experienceId: Int) {
        syncMutex.withLock {
            val routineIngestions = routineDao.getRoutineIngestionsByExperienceId(experienceId)
            val now = Instant.now()
            for (ing in routineIngestions) {
                val occ = routineDao.getOccurrenceByIngestionId(ing.id)
                routineDao.markOccurrenceByIngestionId(
                    ingestionId = ing.id,
                    status = RoutineOccurrenceStatus.SKIPPED,
                    handledAt = now
                )
                val scheduleId = ing.routineScheduleId
                val scheduledMillis = ing.scheduledEpochMillis
                if (scheduleId != null && scheduledMillis != null) {
                    notificationHelper.cancelNotification(scheduleId, scheduledMillis, occ?.slotId.orEmpty())
                }
            }
        }
    }

    suspend fun confirmAllUnconfirmed() {
        syncMutex.withLock {
            val unconfirmed = routineDao.getUnconfirmedRoutineIngestions()
            val now = Instant.now()
            for (ing in unconfirmed) {
                routineDao.setIngestionConfirmed(ing.id, true)
                val occ = routineDao.getOccurrenceByIngestionId(ing.id)
                if (occ != null) {
                    routineDao.updateOccurrenceStatus(
                        id = occ.id,
                        status = RoutineOccurrenceStatus.CONFIRMED,
                        ingestionId = ing.id,
                        handledAt = now
                    )
                }
                val scheduleId = ing.routineScheduleId
                val scheduledMillis = ing.scheduledEpochMillis
                if (scheduleId != null && scheduledMillis != null) {
                    notificationHelper.cancelNotification(scheduleId, scheduledMillis, occ?.slotId.orEmpty())
                }
            }
        }
    }

    suspend fun syncRoutineContainersWithExistingExperiences() {
        syncMutex.withLock {
            attachRoutineContainerDosesToNonRoutineExperiencesLocked(ZoneId.systemDefault())
        }
    }

    suspend fun cancelAllAlarms() {
        syncMutex.withLock {
            cancelAllAlarmsLocked()
        }
    }

    private suspend fun cancelAllAlarmsLocked() {
        val allSchedules = routineDao.getAllSchedulesSorted()
        for (schedule in allSchedules) {
            cancelAlarmForSchedule(schedule.id)
        }
    }

    suspend fun getSmartWindowHoursForIngestion(ingestion: Ingestion): Double? {
        val schedule = ingestion.routineScheduleId?.let { routineDao.getSchedule(it) }
        val substance = substanceRepository.getSubstance(ingestion.substanceName)
        return SmartExperienceSelector.resolveSmartWindowHours(
            customHalfLifeHours = schedule?.customHalfLifeHours,
            substance = substance,
            route = ingestion.administrationRoute
        )
    }

    private suspend fun reconcileSingleScheduleLocked(
        schedule: MedicationSchedule,
        now: Instant,
        catchUpDays: Int,
        notifyLiveTargetMillis: Long?,
        notifyLiveSlotId: String? = null,
        localizedContext: Context,
        zoneId: ZoneId
    ) {
        val windowStart = now.minus(Duration.ofDays(catchUpDays.coerceIn(1, 30).toLong()))
        val dueOccurrences = RecurrenceCalculator.getOccurrencesInRange(
            schedule = schedule,
            fromInclusive = windowStart,
            toInclusive = now,
            zoneId = zoneId
        )

        for (occ in dueOccurrences) {
            val isLiveTarget = notifyLiveTargetMillis != null &&
                occ.scheduledEpochMillis == notifyLiveTargetMillis &&
                (notifyLiveSlotId == null || occ.slotId == notifyLiveSlotId)
            val shouldPopup = schedule.notifyOnTrigger && isLiveTarget
            processOccurrenceIdempotentlyLocked(
                schedule = schedule,
                occ = occ,
                showNotification = shouldPopup,
                localizedContext = localizedContext,
                zoneId = zoneId
            )
        }

        val next = RecurrenceCalculator.getNextOccurrenceAfter(
            schedule = schedule,
            afterExclusive = now,
            zoneId = zoneId
        )
        if (next != null) {
            scheduleAlarmForOccurrence(schedule.id, next)
        } else {
            cancelAlarmForSchedule(schedule.id)
        }
    }

    private suspend fun processOccurrenceIdempotentlyLocked(
        schedule: MedicationSchedule,
        occ: ScheduledOccurrence,
        showNotification: Boolean,
        localizedContext: Context,
        zoneId: ZoneId
    ) {
        val existing = routineDao.getOccurrenceByKey(
            scheduleId = schedule.id,
            scheduledEpochMillis = occ.scheduledEpochMillis,
            slotId = occ.slotId
        )
        if (existing != null) return

        val substance = substanceRepository.getSubstance(schedule.substanceName)
        val windowHours = SmartExperienceSelector.resolveSmartWindowHours(
            customHalfLifeHours = schedule.customHalfLifeHours,
            substance = substance,
            route = schedule.administrationRoute
        )
        val allExperiences = routineDao.getAllExperiencesWithIngestionsSorted()
        val targetExperienceId = selectOrCreateExperienceLocked(
            doseTime = occ.scheduledInstant,
            windowHours = windowHours,
            allExperiences = allExperiences,
            localizedContext = localizedContext,
            zoneId = zoneId
        )

        ensureSubstanceCompanionExistsLocked(schedule.substanceName)
        val customUnit = schedule.customUnitId?.let { routineDao.getCustomUnitById(it) }
        val resolvedUnits = occ.slot.units?.takeIf { it.isNotBlank() }
            ?: customUnit?.unit
        val combinedNote = listOfNotNull(
            occ.slot.notes?.takeIf { it.isNotBlank() },
            schedule.note?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" · ").takeIf { it.isNotBlank() }

        val newIngestion = Ingestion(
            substanceName = schedule.substanceName,
            time = occ.scheduledInstant,
            endTime = null,
            creationDate = Instant.now(),
            administrationRoute = schedule.administrationRoute,
            dose = occ.slot.dose,
            isDoseAnEstimate = occ.slot.isDoseAnEstimate,
            estimatedDoseStandardDeviation = occ.slot.estimatedDoseStandardDeviation,
            units = resolvedUnits,
            experienceId = targetExperienceId,
            notes = combinedNote,
            stomachFullness = null,
            consumerName = schedule.consumerName?.takeIf { it.isNotBlank() },
            customUnitId = customUnit?.id,
            isConfirmed = false,
            routineScheduleId = schedule.id,
            scheduledEpochMillis = occ.scheduledEpochMillis
        )
        val ingestionId = routineDao.insertIngestion(newIngestion).toInt()
        val insertedRowId = routineDao.insertOccurrence(
            RoutineOccurrence(
                scheduleId = schedule.id,
                slotId = occ.slotId,
                scheduledEpochMillis = occ.scheduledEpochMillis,
                status = RoutineOccurrenceStatus.RECORDED_UNCONFIRMED,
                ingestionId = ingestionId,
                handledAt = Instant.now()
            )
        )
        if (insertedRowId == -1L) {
            routineDao.deleteIngestionById(ingestionId)
            cleanupExperienceIfEmptyRoutineContainerLocked(targetExperienceId)
            return
        }

        if (showNotification) {
            notificationHelper.showReminderNotification(
                ingestionId = ingestionId,
                experienceId = targetExperienceId,
                schedule = schedule,
                slot = occ.slot,
                scheduledInstant = occ.scheduledInstant,
                customUnit = customUnit
            )
        }
    }

    private suspend fun attachRoutineContainerDosesToNonRoutineExperiencesLocked(zoneId: ZoneId) {
        val allExperiences = routineDao.getAllExperiencesWithIngestionsSorted()
        val nonRoutine = allExperiences.filter { !it.experience.isRoutineOnly }
        if (nonRoutine.isEmpty()) return

        val routineContainers = allExperiences.filter { it.experience.isRoutineOnly }
        for (container in routineContainers) {
            for (ing in container.ingestions) {
                val scheduleId = ing.routineScheduleId ?: continue
                val schedule = routineDao.getSchedule(scheduleId)
                val substance = substanceRepository.getSubstance(ing.substanceName)
                val windowHours = SmartExperienceSelector.resolveSmartWindowHours(
                    customHalfLifeHours = schedule?.customHalfLifeHours,
                    substance = substance,
                    route = ing.administrationRoute
                )
                val target = SmartExperienceSelector.selectTargetNonRoutineExperience(
                    doseTime = ing.time,
                    windowHours = windowHours,
                    experiences = nonRoutine,
                    zoneId = zoneId
                )
                if (target != null && target.experience.id != container.experience.id) {
                    routineDao.moveIngestionToExperience(ing.id, target.experience.id)
                }
            }
            cleanupExperienceIfEmptyRoutineContainerLocked(container.experience.id)
        }
    }

    private suspend fun selectOrCreateExperienceLocked(
        doseTime: Instant,
        windowHours: Double?,
        allExperiences: List<ExperienceWithIngestions>,
        localizedContext: Context,
        zoneId: ZoneId
    ): Int {
        SmartExperienceSelector.selectTargetNonRoutineExperience(
            doseTime = doseTime,
            windowHours = windowHours,
            experiences = allExperiences,
            zoneId = zoneId
        )?.let { return it.experience.id }

        SmartExperienceSelector.selectSameDayRoutineOnlyExperience(
            doseTime = doseTime,
            experiences = allExperiences,
            zoneId = zoneId
        )?.let { return it.experience.id }

        val dateText = DATE_FORMATTER.format(doseTime.atZone(zoneId).toLocalDate())
        val container = Experience(
            id = 0,
            title = localizedContext.getString(R.string.routine_day_container_title, dateText),
            text = "",
            creationDate = doseTime,
            sortDate = doseTime,
            isFavorite = false,
            location = null,
            isRoutineOnly = true
        )
        return routineDao.insertExperience(container).toInt()
    }

    private suspend fun cleanupExperienceIfEmptyRoutineContainerLocked(experienceId: Int) {
        val exp = routineDao.getExperienceById(experienceId) ?: return
        if (!exp.isRoutineOnly) return
        val remainingIngestions = routineDao.getIngestionsByExperienceId(experienceId)
        if (remainingIngestions.isNotEmpty()) return
        val ratings = routineDao.getRatingsCountForExperience(experienceId)
        val notes = routineDao.getTimedNotesCountForExperience(experienceId)
        if (ratings == 0 && notes == 0 && exp.text.isBlank()) {
            routineDao.deleteExperienceById(experienceId)
        }
    }

    private suspend fun ensureSubstanceCompanionExistsLocked(substanceName: String) {
        val existing = routineDao.getAllSubstanceCompanions()
        if (existing.any { it.substanceName == substanceName }) return
        val usedColors = existing.map { it.color.adaptiveColor }.toSet()
        val preferredColor = AdaptiveColor.entries
            .firstOrNull { it.isPreferred && it !in usedColors }
            ?: AdaptiveColor.entries.firstOrNull { it !in usedColors }
            ?: AdaptiveColor.entries.first()
        routineDao.insertSubstanceCompanion(
            SubstanceCompanion(substanceName = substanceName, color = preferredColor)
        )
    }

    private fun scheduleAlarmForOccurrence(scheduleId: Int, occ: ScheduledOccurrence) {
        val manager = alarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(
            scheduleId = scheduleId,
            slotId = occ.slotId,
            scheduledEpochMillis = occ.scheduledEpochMillis,
            flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val triggerAtMillis = occ.scheduledEpochMillis
        if (canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } catch (_: SecurityException) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarmForSchedule(scheduleId: Int) {
        val manager = alarmManager ?: return
        val pendingIntent = buildAlarmPendingIntent(
            scheduleId = scheduleId,
            slotId = "",
            scheduledEpochMillis = 0L,
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        manager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun buildAlarmPendingIntent(
        scheduleId: Int,
        slotId: String,
        scheduledEpochMillis: Long,
        flags: Int
    ): PendingIntent? {
        val intent = Intent(appContext, RoutineAlarmReceiver::class.java).apply {
            action = RoutineNotificationHelper.ACTION_TRIGGER_SCHEDULE
            putExtra(RoutineNotificationHelper.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(RoutineNotificationHelper.EXTRA_SLOT_ID, slotId)
            putExtra(RoutineNotificationHelper.EXTRA_SCHEDULED_MILLIS, scheduledEpochMillis)
        }
        return PendingIntent.getBroadcast(
            appContext,
            ALARM_REQUEST_CODE_BASE + scheduleId,
            intent,
            flags
        )
    }

    companion object {
        private const val ALARM_REQUEST_CODE_BASE = 50_000
        private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
