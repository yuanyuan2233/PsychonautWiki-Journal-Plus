/*
 * Copyright (c) 2023. Isaak Hanimann.
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

package com.isaakhanimann.journal.data.substances.repositories

import com.isaakhanimann.journal.data.substances.classes.SubstanceWithCategories
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    val substanceRepo: SubstanceRepository
) : SearchRepositoryInterface {

    companion object {
        private val SEARCH_STRIP_REGEX = Regex("[-\\s_]")
        private fun normalizeCategoryKey(category: String): String =
            category.trim().lowercase(Locale.ROOT).replace(' ', '-')
    }

    override fun getMatchingSubstances(
        searchText: String,
        filterCategories: List<String>,
        recentlyUsedSubstanceNamesSorted: List<String>,
    ): List<SubstanceWithCategories> {
        val substancesMatchingCategories = getSubstancesMatchingCategories(filterCategories)
        val substancesFilteredWithText = getSubstancesMatchingSearchText(searchText, prefilteredSubstances = substancesMatchingCategories)
        return getSubstancesSorted(prefilteredSubstances = substancesFilteredWithText, recentlyUsedSubstanceNamesSorted = recentlyUsedSubstanceNamesSorted)
    }

    private fun getSubstancesMatchingCategories(filterCategories: List<String>): List<SubstanceWithCategories> {
        if (filterCategories.isEmpty()) return substanceRepo.getAllSubstancesWithCategories()
        val normalizedFilters = filterCategories.map(::normalizeCategoryKey)
        return substanceRepo.getAllSubstancesWithCategories().filter { substanceWithCategories ->
            val substanceCategories = substanceWithCategories.substance.categories.map(::normalizeCategoryKey).toSet()
            normalizedFilters.all { it in substanceCategories }
        }
    }

    private fun getSubstancesMatchingSearchText(searchText: String, prefilteredSubstances: List<SubstanceWithCategories>): List<SubstanceWithCategories> {
        if (searchText.isBlank()) return prefilteredSubstances
        val searchString = SEARCH_STRIP_REGEX.replace(searchText.trim(), "")
        if (searchString.isEmpty()) return prefilteredSubstances

        val mainPrefixMatches = prefilteredSubstances.filter { substanceWithCategories ->
            val localized = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceWithCategories.substance.name)
            SEARCH_STRIP_REGEX.replace(substanceWithCategories.substance.name, "").startsWith(
                prefix = searchString, ignoreCase = true
            ) || SEARCH_STRIP_REGEX.replace(localized, "").startsWith(
                prefix = searchString, ignoreCase = true
            )
        }
        val prefixMatches = prefilteredSubstances.filter { substanceWithCategories ->
            val localized = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceWithCategories.substance.name)
            val allNames = substanceWithCategories.substance.commonNames + substanceWithCategories.substance.name + localized
            allNames.any { name ->
                SEARCH_STRIP_REGEX.replace(name, "").startsWith(
                    prefix = searchString, ignoreCase = true
                )
            }
        }
        val matches = prefilteredSubstances.filter { substanceWithCategories ->
            val localized = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceWithCategories.substance.name)
            val allNames = substanceWithCategories.substance.commonNames + substanceWithCategories.substance.name + localized
            allNames.any { name ->
                SEARCH_STRIP_REGEX.replace(name, "").contains(
                    other = searchString, ignoreCase = true
                )
            }
        }
        return (mainPrefixMatches + prefixMatches + matches).distinctBy { it.substance.name }
    }

    private fun getSubstancesSorted(
        prefilteredSubstances: List<SubstanceWithCategories>,
        recentlyUsedSubstanceNamesSorted: List<String>
    ): List<SubstanceWithCategories> {
        val recentNames = recentlyUsedSubstanceNamesSorted.distinct()
        val recentlyUsedMatches =
            recentNames.filter { recent -> prefilteredSubstances.any { it.substance.name == recent } }
                .mapNotNull {
                    substanceRepo.getSubstanceWithCategories(
                        substanceName = it
                    )
                }
        val commonSubstanceMatches =
            prefilteredSubstances.filter { sub -> sub.categories.any { cat -> cat.name == "common" } }
        return (recentlyUsedMatches + commonSubstanceMatches + prefilteredSubstances).distinctBy { it.substance.name }
    }
}