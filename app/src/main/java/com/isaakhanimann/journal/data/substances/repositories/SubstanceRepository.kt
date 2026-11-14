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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        val file = getSubstanceFile()
        return file.substances.map { substance ->
            SubstanceWithCategories(
                substance = substance,
                categories = file.categories.filter { category ->
                    substance.categories.contains(category.name)
                }
            )
        }
    }

    override fun getAllCategories(): List<Category> {
        return getSubstanceFile().categories
    }

    override fun getSubstance(substanceName: String): Substance? {
        return getSubstanceFile().substancesMap.getOrDefault(key = substanceName, defaultValue = null)
    }

    override fun getCategory(categoryName: String): Category? {
        return getSubstanceFile().categories.firstOrNull { it.name == categoryName }
    }

    override fun getSubstanceWithCategories(substanceName: String): SubstanceWithCategories? {
        val file = getSubstanceFile()
        val substance = file.substancesMap[substanceName] ?: return null
        return SubstanceWithCategories(
            substance = substance,
            categories = file.categories.filter { substance.categories.contains(it.name) }
        )
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