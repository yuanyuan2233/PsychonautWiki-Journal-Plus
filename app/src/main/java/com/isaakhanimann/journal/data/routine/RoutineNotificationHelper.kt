package com.isaakhanimann.journal.data.routine

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.isaakhanimann.journal.MainActivity
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import com.isaakhanimann.journal.ui.tabs.journal.getLocalizedNameRes
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import com.isaakhanimann.journal.ui.tabs.search.substance.roa.toReadableString
import com.isaakhanimann.journal.ui.tabs.settings.combinations.UserPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoutineNotificationHelper @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPreferences: UserPreferences
) {
    suspend fun getLocalizedContext(): Context {
        val locale = runCatching { userPreferences.appLanguageFlow.first().toLocale() }.getOrNull()
        if (locale == null) return appContext
        val config = Configuration(appContext.resources.configuration).apply {
            setLocale(locale)
        }
        return appContext.createConfigurationContext(config)
    }

    fun hasNotificationPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureNotificationChannel(localizedContext: Context) {
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        val name = localizedContext.getString(R.string.routine_notification_channel_name)
        val desc = localizedContext.getString(R.string.routine_notification_channel_desc)
        if (existing == null) {
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = desc
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(channel)
        } else if (existing.name != name || existing.description != desc) {
            existing.name = name
            existing.description = desc
            manager.createNotificationChannel(existing)
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun showReminderNotification(
        ingestionId: Int,
        experienceId: Int,
        schedule: MedicationSchedule,
        slot: ScheduleTimeSlot,
        scheduledInstant: Instant,
        customUnit: CustomUnit?
    ) {
        val localizedContext = getLocalizedContext()
        ensureNotificationChannel(localizedContext)
        if (!hasNotificationPermission()) return

        val notificationId = notificationIdFor(schedule.id, scheduledInstant.toEpochMilli())
        val substanceDisplayName = SubstanceLocalizationHelper.getSubstanceDisplayName(schedule.substanceName)
        val timeText = TIME_FORMATTER.format(scheduledInstant.atZone(ZoneId.systemDefault()))
        val doseSummary = buildDoseSummary(localizedContext, slot, customUnit, schedule)
        val contentTitle = localizedContext.getString(R.string.routine_notification_title, substanceDisplayName)
        val baseBody = if (slot.dose != null) {
            localizedContext.getString(R.string.routine_notification_body_with_dose, doseSummary, timeText)
        } else {
            localizedContext.getString(R.string.routine_notification_body_no_dose, "$doseSummary · $timeText")
        }
        val slotOrScheduleNote = slot.notes?.takeIf { it.isNotBlank() } ?: schedule.note?.takeIf { it.isNotBlank() }
        val fullBody = if (slotOrScheduleNote != null) "$baseBody · $slotOrScheduleNote" else baseBody

        val openIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_ROUTINE_RECORDS, true)
            putExtra(EXTRA_OPEN_EXPERIENCE_ID, experienceId)
        }
        val openPendingIntent = PendingIntent.getActivity(
            appContext,
            notificationId * 4,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val confirmIntent = Intent(appContext, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_CONFIRM_DOSE
            putExtra(EXTRA_INGESTION_ID, ingestionId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val confirmPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId * 4 + 1,
            confirmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipIntent = Intent(appContext, RoutineAlarmReceiver::class.java).apply {
            action = ACTION_SKIP_DOSE
            putExtra(EXTRA_INGESTION_ID, ingestionId)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
        val skipPendingIntent = PendingIntent.getBroadcast(
            appContext,
            notificationId * 4 + 2,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_medication_reminder)
            .setContentTitle(contentTitle)
            .setContentText(fullBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                0,
                localizedContext.getString(R.string.routine_notification_action_confirm),
                confirmPendingIntent
            )
            .addAction(
                0,
                localizedContext.getString(R.string.routine_notification_action_skip),
                skipPendingIntent
            )

        NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
    }

    fun cancelNotification(scheduleId: Int, scheduledEpochMillis: Long) {
        val notificationId = notificationIdFor(scheduleId, scheduledEpochMillis)
        cancelNotificationById(notificationId)
    }

    fun cancelNotificationById(notificationId: Int) {
        if (notificationId <= 0) return
        NotificationManagerCompat.from(appContext).cancel(notificationId)
    }

    private fun buildDoseSummary(
        localizedContext: Context,
        slot: ScheduleTimeSlot,
        customUnit: CustomUnit?,
        schedule: MedicationSchedule
    ): String {
        val routeText = localizedContext.getString(schedule.administrationRoute.getLocalizedNameRes())
        val dose = slot.dose ?: return routeText
        val unitText = slot.units?.takeIf { it.isNotBlank() }
            ?: customUnit?.let { if (dose == 1.0) it.unit else (it.unitPlural ?: it.unit) }
            ?: ""
        val dosePart = if (slot.estimatedDoseStandardDeviation != null && slot.isDoseAnEstimate) {
            "${dose.toReadableString()}±${slot.estimatedDoseStandardDeviation.toReadableString()} $unitText"
        } else if (slot.isDoseAnEstimate) {
            "~${dose.toReadableString()} $unitText"
        } else {
            "${dose.toReadableString()} $unitText"
        }.trim()
        return "$dosePart · $routeText"
    }

    companion object {
        const val CHANNEL_ID = "routine_medication_reminders"
        const val ACTION_TRIGGER_SCHEDULE = "com.isaakhanimann.journal.routine.ACTION_TRIGGER_SCHEDULE"
        const val ACTION_CONFIRM_DOSE = "com.isaakhanimann.journal.routine.ACTION_CONFIRM_DOSE"
        const val ACTION_SKIP_DOSE = "com.isaakhanimann.journal.routine.ACTION_SKIP_DOSE"
        const val EXTRA_SCHEDULE_ID = "extra_routine_schedule_id"
        const val EXTRA_SLOT_ID = "extra_routine_slot_id"
        const val EXTRA_SCHEDULED_MILLIS = "extra_routine_scheduled_millis"
        const val EXTRA_INGESTION_ID = "extra_routine_ingestion_id"
        const val EXTRA_NOTIFICATION_ID = "extra_routine_notification_id"
        const val EXTRA_OPEN_EXPERIENCE_ID = "extra_routine_open_experience_id"
        const val EXTRA_OPEN_ROUTINE_RECORDS = "extra_routine_open_records"

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun notificationIdFor(scheduleId: Int, scheduledEpochMillis: Long): Int {
            val hash = ((scheduleId * 1_000_003) xor (scheduledEpochMillis / 60_000L).toInt()) and 0x0FFF_FFFF
            return 10_000 + hash
        }
    }
}
