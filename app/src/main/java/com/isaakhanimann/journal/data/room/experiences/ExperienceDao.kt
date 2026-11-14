/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
 * This file is part of PsychonautWiki Journal.
 *
 * PsychonautWiki Journal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * PsychonautWiki Journal is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PsychonautWiki Journal.  If not, see https://www.gnu.org/licenses/gpl-3.0.en.html.
 */

package com.isaakhanimann.journal.data.room.experiences

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.Location
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrence
import com.isaakhanimann.journal.data.room.experiences.entities.ScheduleSlotCodec
import com.isaakhanimann.journal.data.room.experiences.entities.ShulginRating
import com.isaakhanimann.journal.data.room.experiences.entities.SubstanceCompanion
import com.isaakhanimann.journal.data.room.experiences.entities.TimedNote
import com.isaakhanimann.journal.data.room.experiences.relations.CustomUnitWithIngestions
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestions
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestionsAndCompanions
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestionsCompanionsAndRatings
import com.isaakhanimann.journal.data.room.experiences.relations.ExperienceWithIngestionsTimedNotesAndRatings
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanion
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanionAndCustomUnit
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithExperienceAndCustomUnit
import com.isaakhanimann.journal.ui.tabs.settings.CustomUnitSerializable
import com.isaakhanimann.journal.ui.tabs.settings.JournalExport
import com.isaakhanimann.journal.ui.tabs.settings.MedicationScheduleSerializable
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import kotlin.math.sqrt

@Dao
interface ExperienceDao {

    @Query("SELECT * FROM experience ORDER BY creationDate DESC")
    fun getSortedExperiencesFlow(): Flow<List<Experience>>

    @Query("SELECT * FROM ingestion ORDER BY time DESC")
    fun getIngestionsSortedDescendingFlow(): Flow<List<Ingestion>>

    @Query(
        "SELECT * FROM ingestion as i" +
                " WHERE i.time >= :instant" +
                " AND NOT EXISTS (" +
                "   SELECT 1 FROM ingestion as i2" +
                "   WHERE i2.substanceName = i.substanceName" +
                "   AND (i2.time > i.time OR (i2.time = i.time AND i2.id > i.id))" +
                " )" +
                " ORDER BY i.time DESC"
    )
    suspend fun getLatestIngestionOfEverySubstanceSinceDate(instant: Instant): List<Ingestion>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate")
    suspend fun getAllExperiencesWithIngestionsTimedNotesAndRatingsSorted(): List<ExperienceWithIngestionsTimedNotesAndRatings>

    @Query("SELECT * FROM customunit ORDER BY creationDate")
    suspend fun getAllCustomUnitsSorted(): List<CustomUnit>

    @Query("SELECT * FROM customsubstance")
    suspend fun getAllCustomSubstances(): List<CustomSubstance>

    @Query("SELECT * FROM substancecompanion")
    suspend fun getAllSubstanceCompanions(): List<SubstanceCompanion>

    @Transaction
    @Query("SELECT * FROM experience WHERE sortDate >= :fromInstant AND sortDate < :toInstant ORDER BY sortDate DESC")
    suspend fun getSortedExperiencesWithIngestionsWithSortDateBetween(fromInstant: Instant, toInstant: Instant): List<ExperienceWithIngestions>

    @Query("SELECT substanceName FROM ingestion ORDER BY time DESC LIMIT :limit")
    fun getSortedLastUsedSubstanceNamesFlow(limit: Int): Flow<List<String>>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate DESC")
    fun getSortedExperiencesWithIngestionsAndCompanionsFlow(): Flow<List<ExperienceWithIngestionsAndCompanions>>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate DESC")
    fun getSortedExperienceWithIngestionsCompanionsAndRatingsFlow(): Flow<List<ExperienceWithIngestionsCompanionsAndRatings>>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate DESC LIMIT :limit")
    fun getSortedExperiencesWithIngestionsAndCompanionsFlow(limit: Int): Flow<List<ExperienceWithIngestionsAndCompanions>>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate DESC")
    fun getSortedExperiencesWithIngestionsFlow(): Flow<List<ExperienceWithIngestions>>

    @Transaction
    @Query("SELECT * FROM experience ORDER BY sortDate DESC")
    fun getSortedExperiencesWithIngestionsAndCustomUnitsFlow(): Flow<List<ExperienceWithIngestionsAndCompanions>>

    @Transaction
    @Query("SELECT * FROM ingestion ORDER BY time DESC")
    fun getSortedIngestionsWithSubstanceCompanionsFlow(): Flow<List<IngestionWithCompanionAndCustomUnit>>

    @Transaction
    @Query("SELECT * FROM ingestion ORDER BY creationDate DESC LIMIT :limit")
    fun getSortedIngestionsWithSubstanceCompanionsFlow(limit: Int): Flow<List<IngestionWithCompanionAndCustomUnit>>

    @Query("SELECT * FROM ingestion ORDER BY time DESC LIMIT :limit")
    fun getSortedIngestions(limit: Int): Flow<List<Ingestion>>

    @Query("SELECT * FROM ingestion ORDER BY time DESC")
    fun getSortedIngestionsFlow(): Flow<List<Ingestion>>

    @Query("SELECT * FROM ingestion WHERE substanceName = :substanceName ORDER BY time DESC LIMIT :limit")
    fun getSortedIngestionsFlow(substanceName: String, limit: Int): Flow<List<Ingestion>>

    @Query("SELECT * FROM customsubstance")
    fun getCustomSubstancesFlow(): Flow<List<CustomSubstance>>

    @Query("SELECT * FROM customsubstance WHERE id = :id")
    fun getCustomSubstanceFlow(id: Int): Flow<CustomSubstance?>

    @Query("SELECT * FROM customsubstance WHERE name = :name")
    suspend fun getCustomSubstance(name: String): CustomSubstance?

    @Query("SELECT * FROM ingestion WHERE substanceName = :substanceName ORDER BY time DESC")
    fun getSortedIngestionsFlow(substanceName: String): Flow<List<Ingestion>>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE substanceName = :substanceName ORDER BY time DESC")
    fun getSortedIngestionsWithExperienceAndCustomUnitFlow(substanceName: String): Flow<List<IngestionWithExperienceAndCustomUnit>>

    @Query("SELECT * FROM experience WHERE id =:id")
    suspend fun getExperience(id: Int): Experience?

    @Transaction
    @Query("SELECT * FROM experience WHERE id =:id")
    suspend fun getExperienceWithIngestionsCompanionsAndRatings(id: Int): ExperienceWithIngestionsCompanionsAndRatings?

    @Transaction
    @Query("SELECT * FROM ingestion WHERE experienceId =:experienceId")
    suspend fun getIngestionsWithCompanions(experienceId: Int): List<IngestionWithCompanionAndCustomUnit>

    @Query("SELECT * FROM shulginrating WHERE id =:id")
    suspend fun getRating(id: Int): ShulginRating?

    @Query("SELECT * FROM timednote WHERE id =:id")
    suspend fun getTimedNote(id: Int): TimedNote?

    @Query("SELECT * FROM customunit WHERE id =:id")
    suspend fun getCustomUnit(id: Int): CustomUnit?

    @Transaction
    @Query("SELECT * FROM customunit WHERE id =:id")
    suspend fun getCustomUnitWithIngestions(id: Int): CustomUnitWithIngestions?

    @Query("SELECT * FROM experience WHERE id =:id")
    fun getExperienceFlow(id: Int): Flow<Experience?>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE id =:id")
    fun getIngestionWithExperienceFlow(id: Int): Flow<IngestionWithExperienceAndCustomUnit?>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE time >= :fromInstant AND time < :toInstant")
    fun getIngestionWithExperiencesFlow(
        fromInstant: Instant,
        toInstant: Instant
    ): Flow<List<IngestionWithExperienceAndCustomUnit>>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE time >= :fromInstant AND time < :toInstant")
    suspend fun getIngestionsWithCompanions(
        fromInstant: Instant,
        toInstant: Instant
    ): List<IngestionWithCompanion>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE id =:id")
    fun getIngestionFlow(id: Int): Flow<IngestionWithCompanionAndCustomUnit?>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE id =:id")
    fun getIngestionWithCompanionFlow(id: Int): Flow<IngestionWithCompanionAndCustomUnit?>

    @Transaction
    @Query("UPDATE ingestion SET units = 'mg', dose = dose * 1000, estimatedDoseStandardDeviation = CASE WHEN estimatedDoseStandardDeviation IS NOT NULL THEN estimatedDoseStandardDeviation * 1000 ELSE NULL END WHERE substanceName = 'Benzydamine' AND units = 'g'")
    suspend fun migrateBenzydamine()


    @Transaction
    suspend fun migrateCannabisAndMushroomUnits() {
        migrateCannabisIngestionUnits()
        migrateMushroomsIngestionUnits()
        migrateCannabisCustomUnits()
        migrateMushroomsCustomUnits()
    }

    @Query("UPDATE ingestion SET units = 'mg THC' WHERE substanceName = 'Cannabis' AND units = 'mg'")
    suspend fun migrateCannabisIngestionUnits()

    @Query("UPDATE ingestion SET units = 'mg Psilocybin' WHERE substanceName = 'Psilocybin mushrooms' AND units = 'mg'")
    suspend fun migrateMushroomsIngestionUnits()

    @Query("UPDATE customunit SET originalUnit = 'mg THC' WHERE substanceName = 'Cannabis' AND originalUnit = 'mg'")
    suspend fun migrateCannabisCustomUnits()

    @Query("UPDATE customunit SET originalUnit = 'mg Psilocybin' WHERE substanceName = 'Psilocybin mushrooms' AND originalUnit = 'mg'")
    suspend fun migrateMushroomsCustomUnits()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(experience: Experience): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(experience: Experience)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(ingestion: Ingestion)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(rating: ShulginRating)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(customUnit: CustomUnit)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(timedNote: TimedNote)

    @Delete
    suspend fun delete(rating: ShulginRating)

    @Delete
    suspend fun delete(timedNote: TimedNote)

    @Transaction
    @Query(
        "DELETE FROM substancecompanion WHERE substanceName NOT IN (" +
                "SELECT substanceName FROM ingestion " +
                "UNION SELECT substanceName FROM customunit " +
                "UNION SELECT substanceName FROM medicationschedule" +
                ")"
    )
    suspend fun deleteUnusedSubstanceCompanions()

    @Delete
    suspend fun delete(ingestion: Ingestion)

    @Delete
    suspend fun delete(customUnit: CustomUnit)

    @Query("SELECT * FROM ingestion WHERE customUnitId = :customUnitId")
    suspend fun getIngestionsForCustomUnit(customUnitId: Int): List<Ingestion>

    @Transaction
    suspend fun deleteCustomUnitAndFlattenIngestions(customUnit: CustomUnit) {
        getIngestionsForCustomUnit(customUnit.id).forEach { ingestion ->
            update(flattenIngestionForDeletedUnit(ingestion = ingestion, customUnit = customUnit))
        }
        delete(customUnit)
    }

    @Transaction
    suspend fun deleteEverything() {
        deleteAllRoutineOccurrences()
        deleteAllIngestions()
        deleteAllTimedNotes()
        deleteAllRatings()
        deleteAllExperiences()
        deleteAllMedicationSchedules()
        deleteAllCustomUnits()
        deleteAllSubstanceCompanions()
        deleteAllCustomSubstances()
    }

    @Transaction
    suspend fun deleteEverythingOfExperience(experienceId: Int) {
        deleteIngestions(experienceId)
        deleteTimedNotes(experienceId)
        deleteRatings(experienceId)
        deleteExperience(experienceId)
    }

    @Transaction
    suspend fun replaceEverything(journalExport: JournalExport) {
        deleteEverything()
        val customUnitIdMap = mutableMapOf<Int, Int>()
        val seenOldCustomUnitIds = mutableSetOf<Int>()
        val deferredCustomUnits = mutableListOf<CustomUnitSerializable>()
        journalExport.customUnits.forEach { unitSerializable ->
            if (unitSerializable.id > 0 && seenOldCustomUnitIds.add(unitSerializable.id)) {
                val insertedRowId = insertCustomUnitOrAbort(
                    unitSerializable.toCustomUnit(idOverride = unitSerializable.id)
                )
                val newId = insertedRowId.toInt()
                if (newId > 0) {
                    customUnitIdMap[unitSerializable.id] = newId
                }
            } else {
                deferredCustomUnits.add(unitSerializable)
            }
        }
        deferredCustomUnits.forEach { unitSerializable ->
            val insertedRowId = insertCustomUnitOrAbort(
                unitSerializable.toCustomUnit(idOverride = 0)
            )
            val newId = insertedRowId.toInt()
            if (unitSerializable.id > 0 && newId > 0) {
                customUnitIdMap.putIfAbsent(unitSerializable.id, newId)
            }
        }

        val scheduleIdMap = mutableMapOf<Int, Int>()
        val insertedScheduleIds = mutableSetOf<Int>()
        val seenOldScheduleIds = mutableSetOf<Int>()
        val deferredSchedules = mutableListOf<MedicationScheduleSerializable>()
        journalExport.medicationSchedules.forEach { scheduleSerializable ->
            val mappedCustomUnitId = scheduleSerializable.customUnitId?.let { customUnitIdMap[it] }
            if (scheduleSerializable.id > 0 && seenOldScheduleIds.add(scheduleSerializable.id)) {
                val insertedRowId = insertMedicationScheduleOrAbort(
                    scheduleSerializable.toMedicationSchedule(
                        idOverride = scheduleSerializable.id,
                        customUnitIdOverride = mappedCustomUnitId
                    )
                )
                val newId = insertedRowId.toInt()
                if (newId > 0) {
                    scheduleIdMap[scheduleSerializable.id] = newId
                    insertedScheduleIds.add(newId)
                }
            } else {
                deferredSchedules.add(scheduleSerializable)
            }
        }
        deferredSchedules.forEach { scheduleSerializable ->
            val mappedCustomUnitId = scheduleSerializable.customUnitId?.let { customUnitIdMap[it] }
            val insertedRowId = insertMedicationScheduleOrAbort(
                scheduleSerializable.toMedicationSchedule(
                    idOverride = 0,
                    customUnitIdOverride = mappedCustomUnitId
                )
            )
            val newId = insertedRowId.toInt()
            if (newId > 0) {
                insertedScheduleIds.add(newId)
                if (scheduleSerializable.id > 0) {
                    scheduleIdMap.putIfAbsent(scheduleSerializable.id, newId)
                }
            }
        }

        val importedIngestionIdsByKey = mutableMapOf<Pair<Int, Long>, ArrayDeque<Int>>()
        journalExport.experiences.forEachIndexed { indexExperience, experienceSerializable ->
            val experienceID = indexExperience + 1
            val newExperience = Experience(
                id = experienceID,
                title = experienceSerializable.title,
                text = experienceSerializable.text,
                creationDate = experienceSerializable.creationDate,
                sortDate = experienceSerializable.sortDate,
                isFavorite = experienceSerializable.isFavorite,
                location = if (experienceSerializable.location != null) {
                    Location(
                        name = experienceSerializable.location.name,
                        longitude = experienceSerializable.location.longitude,
                        latitude = experienceSerializable.location.latitude
                    )
                } else {
                    null
                },
                isRoutineOnly = experienceSerializable.isRoutineOnly
            )
            insert(newExperience)
            experienceSerializable.ingestions.forEach { ingestionSerializable ->
                val mappedCustomUnitId = ingestionSerializable.customUnitId?.let { oldUnitId ->
                    customUnitIdMap[oldUnitId]
                }
                val mappedScheduleId = ingestionSerializable.routineScheduleId?.let { oldScheduleId ->
                    scheduleIdMap[oldScheduleId]?.takeIf { it in insertedScheduleIds }
                }
                val newIngestion = Ingestion(
                    substanceName = ingestionSerializable.substanceName,
                    time = ingestionSerializable.time,
                    endTime = ingestionSerializable.endTime,
                    creationDate = ingestionSerializable.creationDate,
                    administrationRoute = ingestionSerializable.administrationRoute,
                    dose = ingestionSerializable.dose,
                    isDoseAnEstimate = ingestionSerializable.isDoseAnEstimate,
                    estimatedDoseStandardDeviation = ingestionSerializable.estimatedDoseStandardDeviation,
                    units = ingestionSerializable.units,
                    experienceId = experienceID,
                    notes = ingestionSerializable.notes,
                    stomachFullness = ingestionSerializable.stomachFullness,
                    consumerName = ingestionSerializable.consumerName,
                    customUnitId = mappedCustomUnitId,
                    isConfirmed = ingestionSerializable.isConfirmed,
                    routineScheduleId = mappedScheduleId,
                    scheduledEpochMillis = ingestionSerializable.scheduledEpochMillis
                )
                val insertedIngestionId = insertIngestionReturningId(newIngestion).toInt()
                val scheduledMillis = ingestionSerializable.scheduledEpochMillis
                if (mappedScheduleId != null && scheduledMillis != null && insertedIngestionId > 0) {
                    importedIngestionIdsByKey
                        .getOrPut(mappedScheduleId to scheduledMillis) { ArrayDeque() }
                        .addLast(insertedIngestionId)
                }
            }
            experienceSerializable.timedNotes.forEach { timedNoteSerializable ->
                val newTimedNote = TimedNote(
                    time = timedNoteSerializable.time,
                    creationDate = timedNoteSerializable.creationDate,
                    experienceId = experienceID,
                    isPartOfTimeline = timedNoteSerializable.isPartOfTimeline,
                    color = timedNoteSerializable.color,
                    note = timedNoteSerializable.note
                )
                insert(newTimedNote)
            }
            experienceSerializable.ratings.forEach { ratingSerializable ->
                val newRating = ShulginRating(
                    time = ratingSerializable.time,
                    creationDate = ratingSerializable.creationDate,
                    option = ratingSerializable.option,
                    experienceId = experienceID
                )
                insert(newRating)
            }
        }
        journalExport.substanceCompanions.forEach { insert(it) }
        journalExport.customSubstances.forEach { insert(it) }
        journalExport.routineOccurrences.forEach { occSerializable ->
            val mappedScheduleId = scheduleIdMap[occSerializable.scheduleId]
                ?.takeIf { it in insertedScheduleIds }
                ?: return@forEach
            val remappedIngestionId = importedIngestionIdsByKey[
                mappedScheduleId to occSerializable.scheduledEpochMillis
            ]?.removeFirstOrNull()
            insert(
                RoutineOccurrence(
                    scheduleId = mappedScheduleId,
                    slotId = occSerializable.slotId,
                    scheduledEpochMillis = occSerializable.scheduledEpochMillis,
                    status = occSerializable.status,
                    ingestionId = remappedIngestionId,
                    handledAt = occSerializable.handledAt
                )
            )
        }
    }


    @Transaction
    @Query("DELETE FROM timedNote WHERE experienceId = :experienceId")
    suspend fun deleteTimedNotes(experienceId: Int)

    @Transaction
    @Query("DELETE FROM ingestion")
    suspend fun deleteAllIngestions()

    @Transaction
    @Query("DELETE FROM timedNote")
    suspend fun deleteAllTimedNotes()

    @Transaction
    @Query("DELETE FROM ingestion WHERE experienceId = :experienceId")
    suspend fun deleteIngestions(experienceId: Int)

    @Transaction
    @Query("DELETE FROM shulginrating WHERE experienceId = :experienceId")
    suspend fun deleteRatings(experienceId: Int)

    @Transaction
    @Query("DELETE FROM experience WHERE id = :experienceId")
    suspend fun deleteExperience(experienceId: Int)

    @Transaction
    @Query("DELETE FROM shulginrating")
    suspend fun deleteAllRatings()

    @Transaction
    @Query("DELETE FROM customunit")
    suspend fun deleteAllCustomUnits()

    @Transaction
    @Query("DELETE FROM experience")
    suspend fun deleteAllExperiences()

    @Transaction
    @Query("DELETE FROM substancecompanion")
    suspend fun deleteAllSubstanceCompanions()

    @Transaction
    @Query("DELETE FROM customsubstance")
    suspend fun deleteAllCustomSubstances()

    @Transaction
    @Query("DELETE FROM medicationschedule")
    suspend fun deleteAllMedicationSchedules()

    @Transaction
    @Query("DELETE FROM routineoccurrence")
    suspend fun deleteAllRoutineOccurrences()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(ingestion: Ingestion)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngestionReturningId(ingestion: Ingestion): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: MedicationSchedule): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMedicationScheduleOrAbort(schedule: MedicationSchedule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(occurrence: RoutineOccurrence): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rating: ShulginRating)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customUnit: CustomUnit): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCustomUnitOrAbort(customUnit: CustomUnit): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(timedNote: TimedNote)

    @Transaction
    suspend fun insertIngestionExperienceAndCompanion(
        ingestion: Ingestion,
        experience: Experience,
        substanceCompanion: SubstanceCompanion
    ) {
        // 先插入父表 Experience 再插入子表 Ingestion，满足 Room 外键约束
        val insertedExperienceId = insert(experience).toInt()
        val resolvedIngestion = if (ingestion.experienceId <= 0) {
            ingestion.copy(experienceId = insertedExperienceId)
        } else {
            ingestion
        }
        insert(resolvedIngestion)
        insert(substanceCompanion)
    }

    @Transaction
    suspend fun insertIngestionAndCompanion(
        ingestion: Ingestion,
        substanceCompanion: SubstanceCompanion
    ) {
        insert(ingestion)
        insert(substanceCompanion)
    }

    @Transaction
    @Query("SELECT * FROM experience WHERE id = :experienceId")
    fun getExperienceWithIngestionsAndCompanionsFlow(experienceId: Int): Flow<ExperienceWithIngestionsAndCompanions?>

    @Transaction
    @Query("SELECT * FROM ingestion WHERE experienceId = :experienceId")
    fun getIngestionsWithCompanionsFlow(experienceId: Int): Flow<List<IngestionWithCompanionAndCustomUnit>>

    @Query("SELECT * FROM shulginrating WHERE experienceId = :experienceId")
    fun getRatingsFlow(experienceId: Int): Flow<List<ShulginRating>>

    @Query("SELECT * FROM timednote WHERE experienceId = :experienceId ORDER BY time")
    fun getTimedNotesFlowSorted(experienceId: Int): Flow<List<TimedNote>>

    @Query("SELECT * FROM ingestion WHERE substanceName = :substanceName ORDER BY time DESC LIMIT 1")
    suspend fun getLastIngestion(substanceName: String): Ingestion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(substanceCompanion: SubstanceCompanion)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customSubstance: CustomSubstance): Long

    @Delete
    suspend fun delete(customSubstance: CustomSubstance)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(customSubstance: CustomSubstance)

    @Query("UPDATE ingestion SET substanceName = :newName WHERE substanceName = :oldName")
    suspend fun updateIngestionSubstanceName(oldName: String, newName: String)

    @Query("UPDATE customunit SET substanceName = :newName WHERE substanceName = :oldName")
    suspend fun updateCustomUnitSubstanceName(oldName: String, newName: String)

    @Query("UPDATE medicationschedule SET substanceName = :newName WHERE substanceName = :oldName")
    suspend fun updateScheduleSubstanceName(oldName: String, newName: String)

    @Query("INSERT OR IGNORE INTO substancecompanion (substanceName, color) SELECT :newName, color FROM substancecompanion WHERE substanceName = :oldName")
    suspend fun copySubstanceCompanion(oldName: String, newName: String)

    @Query("DELETE FROM substancecompanion WHERE substanceName = :substanceName")
    suspend fun deleteSubstanceCompanionByName(substanceName: String)

    @Transaction
    suspend fun renameCustomSubstanceAndReferences(customSubstance: CustomSubstance, oldName: String) {
        val cleanName = customSubstance.name.trim()
        val updated = customSubstance.copy(name = cleanName)
        if (oldName.isNotBlank() && oldName != cleanName) {
            updateIngestionSubstanceName(oldName = oldName, newName = cleanName)
            updateCustomUnitSubstanceName(oldName = oldName, newName = cleanName)
            updateScheduleSubstanceName(oldName = oldName, newName = cleanName)
            copySubstanceCompanion(oldName = oldName, newName = cleanName)
            deleteSubstanceCompanionByName(substanceName = oldName)
        }
        update(updated)
    }

    @Delete
    suspend fun delete(substanceCompanion: SubstanceCompanion)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(substanceCompanion: SubstanceCompanion)

    @Query("SELECT * FROM substancecompanion WHERE substanceName =:substanceName")
    fun getSubstanceCompanionFlow(substanceName: String): Flow<SubstanceCompanion?>

    @Query("SELECT * FROM substancecompanion")
    fun getAllSubstanceCompanionsFlow(): Flow<List<SubstanceCompanion>>

    @Query("SELECT * FROM timednote")
    fun getAllTimedNotesFlow(): Flow<List<TimedNote>>

    @Query("SELECT * FROM customunit WHERE isArchived = :isArchived ORDER BY creationDate DESC")
    fun getSortedCustomUnitsFlow(isArchived: Boolean): Flow<List<CustomUnit>>

    @Query("SELECT * FROM customunit WHERE isArchived = :isArchived AND substanceName = :substanceName ORDER BY creationDate DESC")
    fun getSortedCustomUnitsFlowBasedOnName(substanceName: String, isArchived: Boolean): Flow<List<CustomUnit>>

    @Query("SELECT * FROM customunit ORDER BY creationDate DESC")
    fun getAllCustomUnitsFlow(): Flow<List<CustomUnit>>

    @Query("SELECT * FROM timednote WHERE experienceId =:experienceId")
    suspend fun getTimedNotes(experienceId: Int): List<TimedNote>
}

private fun CustomUnitSerializable.toCustomUnit(idOverride: Int): CustomUnit {
    return CustomUnit(
        id = idOverride,
        substanceName = substanceName,
        name = name,
        creationDate = creationDate,
        administrationRoute = administrationRoute,
        dose = dose,
        estimatedDoseStandardDeviation = estimatedDoseStandardDeviation,
        isEstimate = isEstimate,
        isArchived = isArchived,
        unit = unit,
        unitPlural = unitPlural,
        originalUnit = originalUnit,
        note = note
    )
}

private fun MedicationScheduleSerializable.toMedicationSchedule(
    idOverride: Int,
    customUnitIdOverride: Int?
): MedicationSchedule {
    return MedicationSchedule(
        id = idOverride,
        substanceName = substanceName,
        administrationRoute = administrationRoute,
        customUnitId = customUnitIdOverride,
        consumerName = consumerName,
        isEnabled = isEnabled,
        notifyOnTrigger = notifyOnTrigger,
        recurrenceType = recurrenceType,
        daysOfWeekMask = daysOfWeekMask,
        intervalDays = intervalDays,
        intervalMinutes = intervalMinutes,
        cycleOnDays = cycleOnDays,
        cycleOffDays = cycleOffDays,
        timeSlotsJson = ScheduleSlotCodec.encodeSlots(timeSlots),
        anchorEpochMillis = anchorEpochMillis,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        maxOccurrences = maxOccurrences,
        customHalfLifeHours = customHalfLifeHours,
        note = note,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

private fun flattenIngestionForDeletedUnit(ingestion: Ingestion, customUnit: CustomUnit): Ingestion {
    val dosePerUnit = customUnit.dose
    val originalDose = ingestion.dose
    return if (dosePerUnit != null && originalDose != null) {
        val sdX = if (ingestion.isDoseAnEstimate) (ingestion.estimatedDoseStandardDeviation ?: 0.0) else 0.0
        val sdY = if (customUnit.isEstimate) (customUnit.estimatedDoseStandardDeviation ?: 0.0) else 0.0
        val productVariance = (sdX * sdX + originalDose * originalDose) *
            (sdY * sdY + dosePerUnit * dosePerUnit) -
            (originalDose * originalDose * dosePerUnit * dosePerUnit)
        val newSd = if (productVariance > 0.0000001) sqrt(productVariance) else null
        ingestion.copy(
            dose = originalDose * dosePerUnit,
            isDoseAnEstimate = ingestion.isDoseAnEstimate || customUnit.isEstimate,
            estimatedDoseStandardDeviation = newSd,
            units = customUnit.originalUnit,
            customUnitId = null
        )
    } else {
        ingestion.copy(
            units = ingestion.units ?: customUnit.unit,
            customUnitId = null
        )
    }
}
