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

package com.isaakhanimann.journal.data.substances.repositories

import android.content.Context
import android.util.Log
import com.isaakhanimann.journal.data.substances.classes.Category
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.SubstanceFile
import com.isaakhanimann.journal.data.substances.classes.SubstanceWithCategories
import com.isaakhanimann.journal.data.substances.parse.SubstanceParserInterface
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubstanceRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    substanceParser: SubstanceParserInterface,
) : SubstanceRepositoryInterface {

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val substanceFileDeferred = CompletableDeferred<SubstanceFile>()

    @Volatile
    private var cachedSubstanceFile: SubstanceFile? = null

    init {
        loadScope.launch {
            try {
                val fileContent = getAssetsSubstanceFileContent()
                val parsed = substanceParser.parseSubstanceFile(string = fileContent)
                substanceFileDeferred.complete(parsed)
            } catch (e: CancellationException) {
                substanceFileDeferred.cancel(e)
                throw e
            } catch (e: Exception) {
                logError("Failed to load Substances.json from assets", e)
                substanceFileDeferred.complete(
                    SubstanceFile(
                        categories = emptyList(),
                        substances = emptyList()
                    )
                )
            }
        }
    }

    private fun getAssetsSubstanceFileContent(): String {
        return appContext.assets.open("Substances.json").bufferedReader().use { it.readText() }
    }

    suspend fun awaitLoaded() {
        if (cachedSubstanceFile != null) return
        cachedSubstanceFile = substanceFileDeferred.await()
    }

    private fun getSubstanceFile(): SubstanceFile {
        cachedSubstanceFile?.let { return it }
        return runBlocking {
            substanceFileDeferred.await()
        }.also {
            cachedSubstanceFile = it
        }
    }

    override fun getAllSubstances(): List<Substance> {
        return getSubstanceFile().substances
    }

    override fun getAllSubstancesWithCategories(): List<SubstanceWithCategories> {
        return getSubstanceFile().substancesWithCategories
    }

    override fun getAllCategories(): List<Category> {
        return getSubstanceFile().categories
    }

    override fun getSubstance(substanceName: String): Substance? {
        val file = getSubstanceFile()
        return findSubstance(file, substanceName)
    }

    override fun getCategory(categoryName: String): Category? {
        val normalized = SubstanceFile.normalizeCategoryKey(categoryName)
        return getSubstanceFile().categoriesByKey[normalized]
    }

    override fun getSubstanceWithCategories(substanceName: String): SubstanceWithCategories? {
        val file = getSubstanceFile()
        val substance = findSubstance(file, substanceName) ?: return null
        return file.substancesWithCategoriesMap[substance.name]
    }

    private fun findSubstance(file: SubstanceFile, rawName: String): Substance? {
        file.substancesMap[rawName]?.let { return it }
        val trimmed = rawName.trim()
        if (trimmed.isEmpty()) return null
        file.substancesMap[trimmed]?.let { return it }
        val trimmedLower = trimmed.lowercase(Locale.ROOT)
        file.substancesLowerMap[trimmedLower]?.let { return it }

        val canonicalEn = SubstanceLocalizationHelper.getCanonicalEnglishName(trimmed)
        if (!canonicalEn.isNullOrBlank()) {
            file.substancesMap[canonicalEn]?.let { return it }
            val canonicalLower = canonicalEn.lowercase(Locale.ROOT)
            file.substancesLowerMap[canonicalLower]?.let { return it }
            file.substanceByLowerAlias[canonicalLower]?.let { return it }
        }

        return file.substanceByLowerAlias[trimmedLower]
    }

    private fun logError(message: String, throwable: Throwable) {
        try {
            Log.e(TAG, message, throwable)
        } catch (_: RuntimeException) {
            System.err.println("$TAG: $message (${throwable.message})")
        }
    }

    private companion object {
        const val TAG = "SubstanceRepository"
    }
}