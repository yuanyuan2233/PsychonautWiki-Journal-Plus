package com.isaakhanimann.journal.data.room

import androidx.room.TypeConverter
import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrenceStatus

class RoutineTypeConverters {
    @TypeConverter
    fun fromRecurrenceType(value: RecurrenceType): String = value.name

    @TypeConverter
    fun toRecurrenceType(value: String): RecurrenceType {
        return runCatching { RecurrenceType.valueOf(value) }.getOrDefault(RecurrenceType.DAILY)
    }

    @TypeConverter
    fun fromOccurrenceStatus(value: RoutineOccurrenceStatus): String = value.name

    @TypeConverter
    fun toOccurrenceStatus(value: String): RoutineOccurrenceStatus {
        return runCatching { RoutineOccurrenceStatus.valueOf(value) }
            .getOrDefault(RoutineOccurrenceStatus.RECORDED_UNCONFIRMED)
    }
}
