/*
 * Copyright (c) 2022. Isaak Hanimann.
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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Entity(
    indices = [
        Index(value = ["name"], unique = true)
    ]
)
@Serializable
data class CustomSubstance(
    @Transient
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    var units: String,
    var description: String,
    val halfLifeHours: Double? = null,
    val tMaxHours: Double? = null,
    val washoutHours: Double? = null,
    val bioavailabilityFraction: Double? = null,
    val pkRole: String? = null,
    val commonDose: Double? = null,
    val activeMetaboliteName: String? = null,
    val activeMetaboliteHalfLifeHours: Double? = null,
    val onsetHours: Double? = null,
    val peakHours: Double? = null,
    val totalDurationHours: Double? = null,
)