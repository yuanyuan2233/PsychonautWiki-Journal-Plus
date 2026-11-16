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

package com.isaakhanimann.journal.data.substances.classes

import java.util.Locale

class SubstanceFile(
    val categories: List<Category>,
    val substances: List<Substance>
) {
    val substancesMap: Map<String, Substance> = substances.associateBy { it.name }

    val substancesLowerMap: Map<String, Substance> =
        LinkedHashMap<String, Substance>(substances.size).apply {
            for (substance in substances) {
                putIfAbsent(substance.name.lowercase(Locale.ROOT), substance)
            }
        }

    val substanceByLowerAlias: Map<String, Substance> =
        LinkedHashMap<String, Substance>(substances.size * 4).apply {
            for (substance in substances) {
                putIfAbsent(substance.name.lowercase(Locale.ROOT), substance)
            }
            for (substance in substances) {
                for (alias in substance.commonNames) {
                    val key = alias.trim().lowercase(Locale.ROOT)
                    if (key.isNotEmpty()) {
                        putIfAbsent(key, substance)
                    }
                }
            }
        }

    val categoriesByKey: Map<String, Category> =
        LinkedHashMap<String, Category>(categories.size).apply {
            for (category in categories) {
                putIfAbsent(normalizeCategoryKey(category.name), category)
            }
        }

    val substancesWithCategories: List<SubstanceWithCategories> = substances.map { substance ->
        val matchedCategories = substance.categories.mapNotNull { catName ->
            categoriesByKey[normalizeCategoryKey(catName)]
        }.distinctBy { it.name }
        SubstanceWithCategories(
            substance = substance,
            categories = matchedCategories
        )
    }

    val substancesWithCategoriesMap: Map<String, SubstanceWithCategories> =
        substancesWithCategories.associateBy { it.substance.name }

    companion object {
        internal fun normalizeCategoryKey(name: String): String {
            return name.trim().lowercase(Locale.ROOT).replace('-', ' ')
        }
    }
}