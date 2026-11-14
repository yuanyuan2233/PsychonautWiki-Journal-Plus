package com.isaakhanimann.journal.data.room.experiences.entities

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class RecurrenceType {
    DAILY,
    WEEKLY,
    EVERY_N_DAYS,
    EVERY_N_MINUTES,
    CYCLE_ON_OFF
}

@Serializable
enum class RoutineOccurrenceStatus {
    RECORDED_UNCONFIRMED,
    CONFIRMED,
    SKIPPED
}

@Serializable
data class ScheduleTimeSlot(
    val id: String,
    val hour: Int,
    val minute: Int,
    val dose: Double? = null,
    val units: String? = null,
    val isDoseAnEstimate: Boolean = false,
    val estimatedDoseStandardDeviation: Double? = null,
    val notes: String? = null
)

object ScheduleSlotCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeSlots(slots: List<ScheduleTimeSlot>): String = json.encodeToString(slots)

    fun decodeSlots(raw: String): List<ScheduleTimeSlot> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ScheduleTimeSlot>>(raw)
        }.getOrDefault(emptyList())
    }
}
