package com.isaakhanimann.journal.data.routine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RoutineAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var schedulerManager: RoutineSchedulerManager

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                when (action) {
                    RoutineNotificationHelper.ACTION_TRIGGER_SCHEDULE -> {
                        val scheduleId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_SCHEDULE_ID, -1)
                        val slotId = intent.getStringExtra(RoutineNotificationHelper.EXTRA_SLOT_ID).orEmpty()
                        val scheduledMillis = intent.getLongExtra(RoutineNotificationHelper.EXTRA_SCHEDULED_MILLIS, -1L)
                        if (scheduleId > 0 && scheduledMillis > 0L) {
                            schedulerManager.handleAlarmTrigger(
                                scheduleId = scheduleId,
                                slotId = slotId,
                                scheduledEpochMillis = scheduledMillis
                            )
                        }
                    }

                    RoutineNotificationHelper.ACTION_CONFIRM_DOSE -> {
                        val ingestionId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_INGESTION_ID, -1)
                        val notificationId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
                        if (ingestionId > 0) {
                            schedulerManager.confirmDose(ingestionId, notificationId)
                        }
                    }

                    RoutineNotificationHelper.ACTION_SKIP_DOSE -> {
                        val ingestionId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_INGESTION_ID, -1)
                        val notificationId = intent.getIntExtra(RoutineNotificationHelper.EXTRA_NOTIFICATION_ID, 0)
                        if (ingestionId > 0) {
                            schedulerManager.skipOrDeleteRoutineIngestion(ingestionId, notificationId)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
