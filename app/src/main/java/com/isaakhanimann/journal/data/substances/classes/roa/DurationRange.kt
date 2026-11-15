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

data class DurationRange(
    val min: Float?,
    val max: Float?,
    val units: DurationUnits?
) {
    val text: String
        get() {
            val unitSuffix = units?.shortText ?: ""
            return when {
                min != null && max != null -> {
                    val minStr = formatNumber(min)
                    val maxStr = formatNumber(max)
                    if (minStr == maxStr) {
                        "$minStr$unitSuffix"
                    } else {
                        "$minStr-$maxStr$unitSuffix"
                    }
                }
                min != null -> "${formatNumber(min)}+$unitSuffix"
                max != null -> "≤${formatNumber(max)}$unitSuffix"
                else -> ""
            }
        }

    val minInSec: Float? = if (units != null) min?.times(units.inSecondsMultiplier) else null
    val maxInSec: Float? = if (units != null) max?.times(units.inSecondsMultiplier) else null

    fun interpolateAtValueInSeconds(value: Float): Float? {
        if (units == null) return null
        val clamped = value.coerceIn(0f, 1f)
        val raw = when {
            min != null && max != null -> min + (max - min) * clamped
            min != null -> min
            max != null -> max
            else -> return null
        }
        return raw * units.inSecondsMultiplier
    }

    private fun formatNumber(number: Float): String {
        return number.toString().removeSuffix(".0")
    }
}