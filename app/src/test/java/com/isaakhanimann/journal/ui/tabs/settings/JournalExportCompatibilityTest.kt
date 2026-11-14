package com.isaakhanimann.journal.ui.tabs.settings

import com.isaakhanimann.journal.data.room.experiences.entities.RecurrenceType
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrenceStatus
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleTimeSlot
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JournalExportCompatibilityTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyBackupWithoutRoutineFieldsDeserializesWithSafeDefaults() {
        val legacyJson = """
            {
              "experiences": [
                {
                  "title": "Evening Test",
                  "text": "Notes",
                  "creationDate": 1700000000000,
                  "sortDate": 1700000000000,
                  "isFavorite": false,
                  "ingestions": [
                    {
                      "substanceName": "Caffeine",
                      "time": 1700000000000,
                      "endTime": null,
                      "administrationRoute": "ORAL",
                      "dose": 100.0,
                      "isDoseAnEstimate": false,
                      "units": "mg"
                    }
                  ]
                }
              ],
              "substanceCompanions": [],
              "customSubstances": [],
              "customUnits": []
            }
        """.trimIndent()

        val export = json.decodeFromString<JournalExport>(legacyJson)
        assertEquals(1, export.experiences.size)
        val exp = export.experiences.first()
        assertFalse(exp.isRoutineOnly)
        val ing = exp.ingestions.first()
        assertTrue(ing.isConfirmed)
        assertNull(ing.routineScheduleId)
        assertNull(ing.scheduledEpochMillis)
        assertTrue(export.medicationSchedules.isEmpty())
        assertTrue(export.routineOccurrences.isEmpty())
        assertFalse(export.isEmpty())
    }

    @Test
    fun routineExportRoundTripPreservesSchedulesOccurrencesAndFlags() {
        val t0 = Instant.ofEpochMilli(1_760_000_000_000L)
        val export = JournalExport(
            experiences = listOf(
                ExperienceSerializable(
                    title = "Routine Day",
                    text = "",
                    creationDate = t0,
                    sortDate = t0,
                    isRoutineOnly = true,
                    ingestions = listOf(
                        IngestionSerializable(
                            substanceName = "Melatonin",
                            time = t0,
                            endTime = null,
                            creationDate = t0,
                            administrationRoute = AdministrationRoute.ORAL,
                            dose = 3.0,
                            isDoseAnEstimate = false,
                            units = "mg",
                            isConfirmed = false,
                            routineScheduleId = 7,
                            scheduledEpochMillis = 1_760_000_000_000L
                        )
                    )
                )
            ),
            medicationSchedules = listOf(
                MedicationScheduleSerializable(
                    id = 7,
                    substanceName = "Melatonin",
                    administrationRoute = AdministrationRoute.ORAL,
                    notifyOnTrigger = true,
                    recurrenceType = RecurrenceType.DAILY,
                    timeSlots = listOf(
                        ScheduleTimeSlot(
                            id = "night",
                            hour = 22,
                            minute = 30,
                            dose = 3.0,
                            units = "mg",
                            notes = "Before sleep"
                        )
                    ),
                    anchorEpochMillis = 1_760_000_000_000L,
                    customHalfLifeHours = 12.0,
                    createdAt = t0,
                    updatedAt = t0
                )
            ),
            routineOccurrences = listOf(
                RoutineOccurrenceSerializable(
                    scheduleId = 7,
                    slotId = "night",
                    scheduledEpochMillis = 1_760_000_000_000L,
                    status = RoutineOccurrenceStatus.RECORDED_UNCONFIRMED,
                    ingestionId = 42,
                    handledAt = t0
                )
            )
        )

        val encoded = Json.encodeToString(export)
        val decoded = json.decodeFromString<JournalExport>(encoded)

        assertTrue(decoded.experiences.first().isRoutineOnly)
        assertFalse(decoded.experiences.first().ingestions.first().isConfirmed)
        assertEquals(7, decoded.experiences.first().ingestions.first().routineScheduleId)
        assertEquals(1, decoded.medicationSchedules.size)
        assertEquals(22, decoded.medicationSchedules.first().timeSlots.first().hour)
        assertEquals(
            RoutineOccurrenceStatus.RECORDED_UNCONFIRMED,
            decoded.routineOccurrences.first().status
        )
    }
}
