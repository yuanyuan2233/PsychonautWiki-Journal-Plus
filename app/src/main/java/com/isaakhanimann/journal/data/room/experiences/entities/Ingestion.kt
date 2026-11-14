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

package com.isaakhanimann.journal.data.room.experiences.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import java.time.Instant

@Entity(
    foreignKeys = [
        ForeignKey(
            entity = Experience::class,
            parentColumns = ["id"],
            childColumns = ["experienceId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CustomUnit::class,
            parentColumns = ["id"],
            childColumns = ["customUnitId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = MedicationSchedule::class,
            parentColumns = ["id"],
            childColumns = ["routineScheduleId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["experienceId"]),
        Index(value = ["customUnitId"]),
        Index(value = ["routineScheduleId"]),
        Index(value = ["substanceName", "time"]),
        Index(value = ["time"])
    ]
)
data class Ingestion(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val substanceName: String,
    var time: Instant,
    var endTime: Instant? = null,
    var creationDate: Instant? = Instant.now(),
    val administrationRoute: AdministrationRoute,
    var dose: Double?,
    var isDoseAnEstimate: Boolean,
    var estimatedDoseStandardDeviation: Double?,
    var units: String?,
    var experienceId: Int,
    var notes: String?,
    var stomachFullness: StomachFullness?,
    var consumerName: String?,
    var customUnitId: Int?,
    @ColumnInfo(defaultValue = "1")
    var isConfirmed: Boolean = true,
    var routineScheduleId: Int? = null,
    var scheduledEpochMillis: Long? = null
)
