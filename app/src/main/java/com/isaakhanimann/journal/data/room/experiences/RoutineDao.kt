package com.isaakhanimann.journal.data.room.experiences

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrence
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrenceStatus
import com.isaakhanimann.journal.data.room.experiences.entities.SubstanceCompanion
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestions
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanionAndCustomUnit
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface RoutineDao {

    @Query("SELECT * FROM MedicationSchedule ORDER BY isEnabled DESC, id DESC")
    fun getAllSchedulesFlow(): Flow<List<MedicationSchedule>>

    @Query("SELECT * FROM MedicationSchedule ORDER BY id ASC")
    suspend fun getAllSchedulesSorted(): List<MedicationSchedule>

    @Query("SELECT * FROM MedicationSchedule WHERE isEnabled = 1 ORDER BY id ASC")
    suspend fun getEnabledSchedules(): List<MedicationSchedule>

    @Query("SELECT * FROM MedicationSchedule WHERE id = :id LIMIT 1")
    suspend fun getSchedule(id: Int): MedicationSchedule?

    @Query("SELECT * FROM MedicationSchedule WHERE id = :id LIMIT 1")
    fun getScheduleFlow(id: Int): Flow<MedicationSchedule?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: MedicationSchedule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedules(schedules: List<MedicationSchedule>)

    @Update
    suspend fun updateSchedule(schedule: MedicationSchedule)

    @Delete
    suspend fun deleteSchedule(schedule: MedicationSchedule)

    @Query("UPDATE MedicationSchedule SET isEnabled = :isEnabled, updatedAt = :updatedAt WHERE id = :scheduleId")
    suspend fun setScheduleEnabled(scheduleId: Int, isEnabled: Boolean, updatedAt: Instant)

    @Query("SELECT * FROM RoutineOccurrence ORDER BY scheduledEpochMillis ASC")
    suspend fun getAllOccurrencesSorted(): List<RoutineOccurrence>

    @Query("SELECT * FROM RoutineOccurrence WHERE scheduleId = :scheduleId ORDER BY scheduledEpochMillis ASC")
    suspend fun getOccurrencesForSchedule(scheduleId: Int): List<RoutineOccurrence>

    @Query(
        "SELECT * FROM RoutineOccurrence WHERE scheduleId = :scheduleId " +
            "AND scheduledEpochMillis = :scheduledEpochMillis AND slotId = :slotId LIMIT 1"
    )
    suspend fun getOccurrenceByKey(
        scheduleId: Int,
        scheduledEpochMillis: Long,
        slotId: String
    ): RoutineOccurrence?

    @Query("SELECT * FROM RoutineOccurrence WHERE ingestionId = :ingestionId LIMIT 1")
    suspend fun getOccurrenceByIngestionId(ingestionId: Int): RoutineOccurrence?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOccurrence(occurrence: RoutineOccurrence): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOccurrences(occurrences: List<RoutineOccurrence>)

    @Query(
        "UPDATE RoutineOccurrence SET status = :status, ingestionId = :ingestionId, handledAt = :handledAt WHERE id = :id"
    )
    suspend fun updateOccurrenceStatus(
        id: Int,
        status: RoutineOccurrenceStatus,
        ingestionId: Int?,
        handledAt: Instant
    )

    @Query("DELETE FROM RoutineOccurrence WHERE scheduleId = :scheduleId")
    suspend fun deleteOccurrencesForSchedule(scheduleId: Int)

    @Transaction
    @Query("SELECT * FROM Ingestion WHERE routineScheduleId IS NOT NULL ORDER BY time DESC")
    fun getRoutineIngestionsWithDetailsFlow(): Flow<List<IngestionWithCompanionAndCustomUnit>>

    @Query("SELECT COUNT(*) FROM Ingestion WHERE routineScheduleId IS NOT NULL AND isConfirmed = 0")
    fun getUnconfirmedRoutineCountFlow(): Flow<Int>

    @Query("SELECT * FROM Ingestion WHERE routineScheduleId IS NOT NULL AND isConfirmed = 0")
    suspend fun getUnconfirmedRoutineIngestions(): List<Ingestion>

    @Query("SELECT * FROM Ingestion WHERE experienceId = :experienceId AND routineScheduleId IS NOT NULL")
    suspend fun getRoutineIngestionsByExperienceId(experienceId: Int): List<Ingestion>

    @Query(
        "UPDATE RoutineOccurrence SET status = :status, ingestionId = NULL, handledAt = :handledAt " +
            "WHERE ingestionId = :ingestionId"
    )
    suspend fun markOccurrenceByIngestionId(
        ingestionId: Int,
        status: RoutineOccurrenceStatus,
        handledAt: Instant
    )

    @Query("SELECT * FROM Ingestion WHERE id = :id LIMIT 1")
    suspend fun getIngestionById(id: Int): Ingestion?

    @Query(
        "SELECT * FROM Ingestion WHERE routineScheduleId IS NOT NULL " +
            "AND time >= :fromTime AND time <= :toTime ORDER BY time DESC"
    )
    suspend fun getRoutineIngestionsBetween(fromTime: Instant, toTime: Instant): List<Ingestion>

    @Query("SELECT * FROM Ingestion WHERE experienceId = :experienceId")
    suspend fun getIngestionsByExperienceId(experienceId: Int): List<Ingestion>

    @Query("UPDATE Ingestion SET isConfirmed = :isConfirmed WHERE id = :ingestionId")
    suspend fun setIngestionConfirmed(ingestionId: Int, isConfirmed: Boolean)

    @Query("UPDATE Ingestion SET experienceId = :targetExperienceId WHERE id = :ingestionId")
    suspend fun moveIngestionToExperience(ingestionId: Int, targetExperienceId: Int)

    @Query("DELETE FROM Ingestion WHERE id = :ingestionId")
    suspend fun deleteIngestionById(ingestionId: Int)

    @Query("SELECT * FROM Experience ORDER BY sortDate DESC")
    fun getAllExperiencesSimpleFlow(): Flow<List<Experience>>

    @Transaction
    @Query("SELECT * FROM Experience ORDER BY sortDate DESC")
    suspend fun getAllExperiencesWithIngestionsSorted(): List<ExperienceWithIngestions>

    @Query("SELECT * FROM Experience WHERE id = :id LIMIT 1")
    suspend fun getExperienceById(id: Int): Experience?

    @Transaction
    @Query("SELECT * FROM Experience WHERE id = :id LIMIT 1")
    suspend fun getExperienceWithIngestionsById(id: Int): ExperienceWithIngestions?

    @Query("SELECT COUNT(*) FROM ShulginRating WHERE experienceId = :experienceId")
    suspend fun getRatingsCountForExperience(experienceId: Int): Int

    @Query("SELECT COUNT(*) FROM TimedNote WHERE experienceId = :experienceId")
    suspend fun getTimedNotesCountForExperience(experienceId: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExperience(experience: Experience): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngestion(ingestion: Ingestion): Long

    @Query("DELETE FROM Experience WHERE id = :experienceId")
    suspend fun deleteExperienceById(experienceId: Int)

    @Query("UPDATE Experience SET isRoutineOnly = :isRoutineOnly WHERE id = :experienceId")
    suspend fun setExperienceRoutineOnly(experienceId: Int, isRoutineOnly: Boolean)

    @Query("SELECT * FROM SubstanceCompanion")
    suspend fun getAllSubstanceCompanions(): List<SubstanceCompanion>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubstanceCompanion(companion: SubstanceCompanion)

    @Query("SELECT * FROM CustomUnit WHERE id = :id LIMIT 1")
    suspend fun getCustomUnitById(id: Int): CustomUnit?

    @Query("DELETE FROM MedicationSchedule")
    suspend fun deleteAllSchedules()

    @Query("DELETE FROM RoutineOccurrence")
    suspend fun deleteAllOccurrences()

    @Transaction
    suspend fun deleteAllRoutineData() {
        deleteAllOccurrences()
        deleteAllSchedules()
    }
}
