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

package com.isaakhanimann.journal.data.substances.classes.roa

import kotlin.math.floor
import kotlin.math.roundToInt

data class RoaDose(
    val units: String,
    val lightMin: Double?,
    val commonMin: Double?,
    val strongMin: Double?,
    val heavyMin: Double?,
) {
    fun getDoseClass(ingestionDose: Double?, ingestionUnits: String? = units): DoseClass? {
        val normalizedDose = normalizeDose(ingestionDose, ingestionUnits) ?: return null
        return when {
            heavyMin != null && normalizedDose >= heavyMin -> DoseClass.HEAVY
            strongMin != null && normalizedDose >= strongMin -> DoseClass.STRONG
            commonMin != null && normalizedDose >= commonMin -> DoseClass.COMMON
            lightMin != null && normalizedDose >= lightMin -> DoseClass.LIGHT
            hasAnyThreshold -> DoseClass.THRESHOLD
            else -> null
        }
    }

    fun getNumDots(ingestionDose: Double?, ingestionUnits: String? = units): Int? {
        val normalizedDose = normalizeDose(ingestionDose, ingestionUnits) ?: return null
        return when {
            heavyMin != null && normalizedDose >= heavyMin -> {
                if (heavyMin > 0.0) {
                    val timesHeavy = floor(normalizedDose / heavyMin).roundToInt()
                    val rest = normalizedDose.rem(heavyMin)
                    (timesHeavy * 4) + getNumDotsUpTo4(dose = rest)
                } else {
                    4
                }
            }
            strongMin != null && normalizedDose >= strongMin -> 3
            commonMin != null && normalizedDose >= commonMin -> 2
            lightMin != null && normalizedDose >= lightMin -> 1
            hasAnyThreshold -> 0
            else -> null
        }
    }

    private fun getNumDotsUpTo4(dose: Double): Int {
        return when {
            heavyMin != null && dose >= heavyMin ->
                if (heavyMin > 0.0) floor(dose / heavyMin).roundToInt().coerceAtMost(4) else 4
            strongMin != null && dose >= strongMin -> 3
            commonMin != null && dose >= commonMin -> 2
            lightMin != null && dose >= lightMin -> 1
            else -> 0
        }
    }

    private val hasAnyThreshold: Boolean
        get() = lightMin != null || commonMin != null || strongMin != null || heavyMin != null

    private fun normalizeDose(ingestionDose: Double?, ingestionUnits: String?): Double? {
        if (ingestionDose == null || !ingestionDose.isFinite() || ingestionDose < 0.0) return null
        val targetUnits = units.trim()
        val sourceUnits = ingestionUnits?.trim() ?: return null
        if (sourceUnits == targetUnits) return ingestionDose
        val sourceMg = toMilligramsOrNull(ingestionDose, sourceUnits) ?: return null
        return fromMilligramsOrNull(sourceMg, targetUnits)
    }

    private fun toMilligramsOrNull(value: Double, unit: String): Double? {
        return when (unit) {
            "µg", "\u03bcg", "ug", "mcg" -> value / 1000.0
            "mg" -> value
            "g" -> value * 1000.0
            else -> null
        }
    }

    private fun fromMilligramsOrNull(mgValue: Double, targetUnit: String): Double? {
        return when (targetUnit) {
            "µg", "\u03bcg", "ug", "mcg" -> mgValue * 1000.0
            "mg" -> mgValue
            "g" -> mgValue / 1000.0
            else -> null
        }
    }

    val shouldUseVolumetricDosing: Boolean
        get() {
            if (units == "µg") return true
            return if (units == "mg") {
                val sample = commonMin ?: strongMin
                sample != null && sample < 15
            } else {
                false
            }
        }

    val averageCommonDose: Double? get() {
        return if (commonMin != null && strongMin != null) {
            (commonMin + strongMin) / 2
        } else {
            null
        }
    }

    fun getStrengthRelativeToCommonDose(dose: Double): Double? {
        return averageCommonDose?.let {
            if (it > 0) {
                dose/it
            } else {
                null
            }
        }
    }
}