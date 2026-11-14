package com.isaakhanimann.journal.data.room.experiences.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    indices = [
        Index(value = ["scheduleId", "scheduledEpochMillis", "slotId"], unique = true),
        Index(value = ["ingestionId"])
    ]
)
data class RoutineOccurrence(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val scheduleId: Int,
    val slotId: String,
    val scheduledEpochMillis: Long,
    val status: RoutineOccurrenceStatus,
    val ingestionId: Int? = null,
    val handledAt: Instant = Instant.now()
)
