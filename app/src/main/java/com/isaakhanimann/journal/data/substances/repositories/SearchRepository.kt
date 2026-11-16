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

    private class IndexedSubstance(
        val item: SubstanceWithCategories,
        val categoryKeys: Set<String>,
        val strippedMainLower: String,
        val strippedLocalizedLower: String,
        val strippedAllNamesLower: List<String>,
        val isCommon: Boolean,
    )

    private class SearchIndexCache(
        val sourceList: List<SubstanceWithCategories>,
        val isChinese: Boolean,
        val entries: List<IndexedSubstance>,
        val entriesByName: Map<String, IndexedSubstance>,
    )

    @Volatile
    private var cachedIndex: SearchIndexCache? = null

    companion object {
        private val SEARCH_STRIP_REGEX = Regex("[-\\s_]")
        private fun normalizeCategoryKey(category: String): String =
            category.trim().lowercase(Locale.ROOT).replace(' ', '-')

        private fun stripAndLower(raw: String): String =
            SEARCH_STRIP_REGEX.replace(raw, "").lowercase(Locale.ROOT)
    }

    private fun getOrBuildIndex(): SearchIndexCache {
        val allItems = substanceRepo.getAllSubstancesWithCategories()
        val isZh = SubstanceLocalizationHelper.isChinese()
        val current = cachedIndex
        if (current != null && current.sourceList === allItems && current.isChinese == isZh) {
            return current
        }

        val entries = ArrayList<IndexedSubstance>(allItems.size)
        val byName = HashMap<String, IndexedSubstance>(allItems.size * 2)
        for (item in allItems) {
            val sub = item.substance
            val localized = SubstanceLocalizationHelper.getSubstanceDisplayName(sub.name, sub.commonNames)
            val strippedMain = stripAndLower(sub.name)
            val strippedLocalized = stripAndLower(localized)

            val seenNames = LinkedHashSet<String>(sub.commonNames.size + 2)
            for (alias in sub.commonNames) {
                val stripped = stripAndLower(alias)
                if (stripped.isNotEmpty()) seenNames.add(stripped)
            }
            if (strippedMain.isNotEmpty()) seenNames.add(strippedMain)
            if (strippedLocalized.isNotEmpty()) seenNames.add(strippedLocalized)

            val catKeys = HashSet<String>(sub.categories.size * 2)
            for (cat in sub.categories) {
                catKeys.add(normalizeCategoryKey(cat))
            }

            val indexed = IndexedSubstance(
                item = item,
                categoryKeys = catKeys,
                strippedMainLower = strippedMain,
                strippedLocalizedLower = strippedLocalized,
                strippedAllNamesLower = seenNames.toList(),
                isCommon = item.categories.any { cat -> cat.name == "common" }
            )
            entries.add(indexed)
            byName[sub.name] = indexed
        }

        return SearchIndexCache(
            sourceList = allItems,
            isChinese = isZh,
            entries = entries,
            entriesByName = byName,
        ).also {
            cachedIndex = it
        }
    }

    override fun getMatchingSubstances(
        searchText: String,
        filterCategories: List<String>,
        recentlyUsedSubstanceNamesSorted: List<String>,
    ): List<SubstanceWithCategories> {
        val index = getOrBuildIndex()
        val categoryFiltered = getSubstancesMatchingCategories(index.entries, filterCategories)
        val textFiltered = getSubstancesMatchingSearchText(searchText, categoryFiltered)
        return getSubstancesSorted(
            prefilteredSubstances = textFiltered,
            recentlyUsedSubstanceNamesSorted = recentlyUsedSubstanceNamesSorted,
            entriesByName = index.entriesByName
        )
    }

    private fun getSubstancesMatchingCategories(
        entries: List<IndexedSubstance>,
        filterCategories: List<String>
    ): List<IndexedSubstance> {
        if (filterCategories.isEmpty()) return entries
        val normalizedFilters = filterCategories.map(::normalizeCategoryKey)
        return entries.filter { indexed ->
            normalizedFilters.all { it in indexed.categoryKeys }
        }
    }

    private fun getSubstancesMatchingSearchText(
        searchText: String,
        prefilteredSubstances: List<IndexedSubstance>
    ): List<IndexedSubstance> {
        if (searchText.isBlank()) return prefilteredSubstances
        val searchLower = stripAndLower(searchText.trim())
        if (searchLower.isEmpty()) return prefilteredSubstances

        val mainPrefixMatches = ArrayList<IndexedSubstance>()
        val prefixMatches = ArrayList<IndexedSubstance>()
        val substringMatches = ArrayList<IndexedSubstance>()

        for (indexed in prefilteredSubstances) {
            if (indexed.strippedMainLower.startsWith(searchLower) ||
                indexed.strippedLocalizedLower.startsWith(searchLower)
            ) {
                mainPrefixMatches.add(indexed)
            } else if (indexed.strippedAllNamesLower.any { it.startsWith(searchLower) }) {
                prefixMatches.add(indexed)
            } else if (indexed.strippedAllNamesLower.any { it.contains(searchLower) }) {
                substringMatches.add(indexed)
            }
        }

        if (prefixMatches.isEmpty() && substringMatches.isEmpty()) {
            return mainPrefixMatches
        }
        val combined = ArrayList<IndexedSubstance>(
            mainPrefixMatches.size + prefixMatches.size + substringMatches.size
        )
        combined.addAll(mainPrefixMatches)
        combined.addAll(prefixMatches)
        combined.addAll(substringMatches)
        return combined
    }

    private fun getSubstancesSorted(
        prefilteredSubstances: List<IndexedSubstance>,
        recentlyUsedSubstanceNamesSorted: List<String>,
        entriesByName: Map<String, IndexedSubstance>,
    ): List<SubstanceWithCategories> {
        if (prefilteredSubstances.isEmpty()) return emptyList()
        val prefilteredNames = HashSet<String>(prefilteredSubstances.size * 2)
        for (indexed in prefilteredSubstances) {
            prefilteredNames.add(indexed.item.substance.name)
        }

        val result = ArrayList<SubstanceWithCategories>(prefilteredSubstances.size)
        val emitted = HashSet<String>(prefilteredSubstances.size * 2)

        for (recent in recentlyUsedSubstanceNamesSorted) {
            if (recent in prefilteredNames && emitted.add(recent)) {
                entriesByName[recent]?.let { result.add(it.item) }
            }
        }
        for (indexed in prefilteredSubstances) {
            if (indexed.isCommon && emitted.add(indexed.item.substance.name)) {
                result.add(indexed.item)
            }
        }
        for (indexed in prefilteredSubstances) {
            if (emitted.add(indexed.item.substance.name)) {
                result.add(indexed.item)
            }
        }
        return result
    }
}