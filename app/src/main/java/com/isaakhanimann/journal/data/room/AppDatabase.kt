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

package com.isaakhanimann.journal.data.room

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.isaakhanimann.journal.data.room.experiences.ExperienceDao
import com.isaakhanimann.journal.data.room.experiences.RoutineDao
import com.isaakhanimann.journal.data.room.experiences.entities.ColorConverter
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.Experience
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.InstantConverter
import com.isaakhanimann.journal.data.room.experiences.entities.MedicationSchedule
import com.isaakhanimann.journal.data.room.experiences.entities.RoutineOccurrence
import com.isaakhanimann.journal.data.room.experiences.entities.ShulginRating
import com.isaakhanimann.journal.data.room.experiences.entities.SubstanceCompanion
import com.isaakhanimann.journal.data.room.experiences.entities.TimedNote

@TypeConverters(InstantConverter::class, ColorConverter::class, RoutineTypeConverters::class)
@Database(
    version = 10,
    entities = [
        Experience::class,
        Ingestion::class,
        SubstanceCompanion::class,
        CustomSubstance::class,
        ShulginRating::class,
        TimedNote::class,
        CustomUnit::class,
        MedicationSchedule::class,
        RoutineOccurrence::class
    ],
    autoMigrations = [
        AutoMigration (from = 1, to = 2),
        AutoMigration (from = 2, to = 3),
        AutoMigration (from = 3, to = 4),
        AutoMigration (from = 4, to = 5),
        AutoMigration (from = 5, to = 6),
        AutoMigration (from = 6, to = 7),
        AutoMigration (from = 7, to = 8),
        AutoMigration (from = 8, to = 9),
    ]
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun experienceDao(): ExperienceDao
    abstract fun routineDao(): RoutineDao

    companion object {
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM `CustomSubstance` WHERE `id` NOT IN (SELECT MIN(`id`) FROM `CustomSubstance` GROUP BY `name`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_CustomSubstance_name` ON `CustomSubstance` (`name`)"
                )

                db.execSQL(
                    "UPDATE `MedicationSchedule` SET `customUnitId` = NULL WHERE `customUnitId` IS NOT NULL AND `customUnitId` NOT IN (SELECT `id` FROM `CustomUnit`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `MedicationSchedule_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `substanceName` TEXT NOT NULL, `administrationRoute` TEXT NOT NULL, `customUnitId` INTEGER, `consumerName` TEXT, `isEnabled` INTEGER NOT NULL, `notifyOnTrigger` INTEGER NOT NULL, `recurrenceType` TEXT NOT NULL, `daysOfWeekMask` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `intervalMinutes` INTEGER NOT NULL, `cycleOnDays` INTEGER NOT NULL, `cycleOffDays` INTEGER NOT NULL, `timeSlotsJson` TEXT NOT NULL, `anchorEpochMillis` INTEGER NOT NULL, `startEpochDay` INTEGER, `endEpochDay` INTEGER, `maxOccurrences` INTEGER, `customHalfLifeHours` REAL, `note` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`customUnitId`) REFERENCES `CustomUnit`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "INSERT INTO `MedicationSchedule_new` (`id`, `substanceName`, `administrationRoute`, `customUnitId`, `consumerName`, `isEnabled`, `notifyOnTrigger`, `recurrenceType`, `daysOfWeekMask`, `intervalDays`, `intervalMinutes`, `cycleOnDays`, `cycleOffDays`, `timeSlotsJson`, `anchorEpochMillis`, `startEpochDay`, `endEpochDay`, `maxOccurrences`, `customHalfLifeHours`, `note`, `createdAt`, `updatedAt`) SELECT `id`, `substanceName`, `administrationRoute`, `customUnitId`, `consumerName`, `isEnabled`, `notifyOnTrigger`, `recurrenceType`, `daysOfWeekMask`, `intervalDays`, `intervalMinutes`, `cycleOnDays`, `cycleOffDays`, `timeSlotsJson`, `anchorEpochMillis`, `startEpochDay`, `endEpochDay`, `maxOccurrences`, `customHalfLifeHours`, `note`, `createdAt`, `updatedAt` FROM `MedicationSchedule`"
                )
                db.execSQL("DROP TABLE `MedicationSchedule`")
                db.execSQL("ALTER TABLE `MedicationSchedule_new` RENAME TO `MedicationSchedule`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_MedicationSchedule_customUnitId` ON `MedicationSchedule` (`customUnitId`)"
                )

                db.execSQL(
                    "DELETE FROM `Ingestion` WHERE `experienceId` NOT IN (SELECT `id` FROM `Experience`)"
                )
                db.execSQL(
                    "UPDATE `Ingestion` SET `customUnitId` = NULL WHERE `customUnitId` IS NOT NULL AND `customUnitId` NOT IN (SELECT `id` FROM `CustomUnit`)"
                )
                db.execSQL(
                    "UPDATE `Ingestion` SET `routineScheduleId` = NULL WHERE `routineScheduleId` IS NOT NULL AND `routineScheduleId` NOT IN (SELECT `id` FROM `MedicationSchedule`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Ingestion_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `substanceName` TEXT NOT NULL, `time` INTEGER NOT NULL, `endTime` INTEGER, `creationDate` INTEGER, `administrationRoute` TEXT NOT NULL, `dose` REAL, `isDoseAnEstimate` INTEGER NOT NULL, `estimatedDoseStandardDeviation` REAL, `units` TEXT, `experienceId` INTEGER NOT NULL, `notes` TEXT, `stomachFullness` TEXT, `consumerName` TEXT, `customUnitId` INTEGER, `isConfirmed` INTEGER NOT NULL DEFAULT 1, `routineScheduleId` INTEGER, `scheduledEpochMillis` INTEGER, FOREIGN KEY(`experienceId`) REFERENCES `Experience`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`customUnitId`) REFERENCES `CustomUnit`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`routineScheduleId`) REFERENCES `MedicationSchedule`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "INSERT INTO `Ingestion_new` (`id`, `substanceName`, `time`, `endTime`, `creationDate`, `administrationRoute`, `dose`, `isDoseAnEstimate`, `estimatedDoseStandardDeviation`, `units`, `experienceId`, `notes`, `stomachFullness`, `consumerName`, `customUnitId`, `isConfirmed`, `routineScheduleId`, `scheduledEpochMillis`) SELECT `id`, `substanceName`, `time`, `endTime`, `creationDate`, `administrationRoute`, `dose`, `isDoseAnEstimate`, `estimatedDoseStandardDeviation`, `units`, `experienceId`, `notes`, `stomachFullness`, `consumerName`, `customUnitId`, `isConfirmed`, `routineScheduleId`, `scheduledEpochMillis` FROM `Ingestion`"
                )
                db.execSQL("DROP TABLE `Ingestion`")
                db.execSQL("ALTER TABLE `Ingestion_new` RENAME TO `Ingestion`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Ingestion_experienceId` ON `Ingestion` (`experienceId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Ingestion_customUnitId` ON `Ingestion` (`customUnitId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Ingestion_routineScheduleId` ON `Ingestion` (`routineScheduleId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Ingestion_substanceName_time` ON `Ingestion` (`substanceName`, `time`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_Ingestion_time` ON `Ingestion` (`time`)"
                )

                db.execSQL(
                    "DELETE FROM `ShulginRating` WHERE `experienceId` NOT IN (SELECT `id` FROM `Experience`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ShulginRating_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER, `creationDate` INTEGER, `option` TEXT NOT NULL, `experienceId` INTEGER NOT NULL, FOREIGN KEY(`experienceId`) REFERENCES `Experience`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `ShulginRating_new` (`id`, `time`, `creationDate`, `option`, `experienceId`) SELECT `id`, `time`, `creationDate`, `option`, `experienceId` FROM `ShulginRating`"
                )
                db.execSQL("DROP TABLE `ShulginRating`")
                db.execSQL("ALTER TABLE `ShulginRating_new` RENAME TO `ShulginRating`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ShulginRating_experienceId` ON `ShulginRating` (`experienceId`)"
                )

                db.execSQL(
                    "DELETE FROM `TimedNote` WHERE `experienceId` NOT IN (SELECT `id` FROM `Experience`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `TimedNote_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `creationDate` INTEGER NOT NULL, `time` INTEGER NOT NULL, `note` TEXT NOT NULL, `color` TEXT NOT NULL, `experienceId` INTEGER NOT NULL, `isPartOfTimeline` INTEGER NOT NULL, FOREIGN KEY(`experienceId`) REFERENCES `Experience`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "INSERT INTO `TimedNote_new` (`id`, `creationDate`, `time`, `note`, `color`, `experienceId`, `isPartOfTimeline`) SELECT `id`, `creationDate`, `time`, `note`, `color`, `experienceId`, `isPartOfTimeline` FROM `TimedNote`"
                )
                db.execSQL("DROP TABLE `TimedNote`")
                db.execSQL("ALTER TABLE `TimedNote_new` RENAME TO `TimedNote`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_TimedNote_experienceId` ON `TimedNote` (`experienceId`)"
                )

                db.execSQL(
                    "DELETE FROM `RoutineOccurrence` WHERE `scheduleId` NOT IN (SELECT `id` FROM `MedicationSchedule`)"
                )
                db.execSQL(
                    "UPDATE `RoutineOccurrence` SET `ingestionId` = NULL WHERE `ingestionId` IS NOT NULL AND `ingestionId` NOT IN (SELECT `id` FROM `Ingestion`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `RoutineOccurrence_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `scheduleId` INTEGER NOT NULL, `slotId` TEXT NOT NULL, `scheduledEpochMillis` INTEGER NOT NULL, `status` TEXT NOT NULL, `ingestionId` INTEGER, `handledAt` INTEGER NOT NULL, FOREIGN KEY(`scheduleId`) REFERENCES `MedicationSchedule`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`ingestionId`) REFERENCES `Ingestion`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                db.execSQL(
                    "INSERT INTO `RoutineOccurrence_new` (`id`, `scheduleId`, `slotId`, `scheduledEpochMillis`, `status`, `ingestionId`, `handledAt`) SELECT `id`, `scheduleId`, `slotId`, `scheduledEpochMillis`, `status`, `ingestionId`, `handledAt` FROM `RoutineOccurrence`"
                )
                db.execSQL("DROP TABLE `RoutineOccurrence`")
                db.execSQL("ALTER TABLE `RoutineOccurrence_new` RENAME TO `RoutineOccurrence`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_RoutineOccurrence_scheduleId_scheduledEpochMillis_slotId` ON `RoutineOccurrence` (`scheduleId`, `scheduledEpochMillis`, `slotId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_RoutineOccurrence_ingestionId` ON `RoutineOccurrence` (`ingestionId`)"
                )
            }
        }
    }
}