package com.isaakhanimann.journal.data.room.experiences.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import java.time.Instant

@Entity
data class MedicationSchedule(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val substanceName: String,
    val administrationRoute: AdministrationRoute,
    val customUnitId: Int? = null,
    val consumerName: String? = null,
    val isEnabled: Boolean = true,
    val notifyOnTrigger: Boolean = true,
    val recurrenceType: RecurrenceType = RecurrenceType.DAILY,
    val daysOfWeekMask: Int = ALL_DAYS_MASK,
    val intervalDays: Int = 1,
    val intervalMinutes: Int = 480,
    val cycleOnDays: Int = 5,
    val cycleOffDays: Int = 2,
    val timeSlotsJson: String = "[]",
    val anchorEpochMillis: Long = System.currentTimeMillis(),
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null,
    val maxOccurrences: Int? = null,
    val customHalfLifeHours: Double? = null,
    val note: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    fun decodedSlots(): List<ScheduleTimeSlot> = ScheduleSlotCodec.decodeSlots(timeSlotsJson)

    companion object {
        const val ALL_DAYS_MASK: Int = 0b1111111

        fun dayBit(isoDayOfWeek: Int): Int = 1 shl (isoDayOfWeek - 1)

        fun isDaySelected(mask: Int, isoDayOfWeek: Int): Boolean {
            return (mask and dayBit(isoDayOfWeek)) != 0
        }
    }
}
