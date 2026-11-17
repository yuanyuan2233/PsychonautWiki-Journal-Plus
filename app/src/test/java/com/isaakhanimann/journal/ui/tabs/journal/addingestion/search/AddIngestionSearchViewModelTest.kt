package com.isaakhanimann.journal.ui.tabs.journal.addingestion.search

import com.isaakhanimann.journal.data.room.experiences.entities.AdaptiveColor
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.room.experiences.entities.CustomUnit
import com.isaakhanimann.journal.data.room.experiences.entities.Ingestion
import com.isaakhanimann.journal.data.room.experiences.entities.SubstanceCompanion
import com.isaakhanimann.journal.data.room.experiences.relations.IngestionWithCompanionAndCustomUnit
import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.ui.tabs.journal.addingestion.search.suggestion.models.Suggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AddIngestionSearchViewModelTest {

    private fun stubSubstance(name: String): Substance = Substance(
        name = name,
        commonNames = emptyList(),
        url = "",
        isApproved = true,
        tolerance = null,
        crossTolerances = emptyList(),
        addictionPotential = null,
        toxicities = emptyList(),
        categories = emptyList(),
        roas = emptyList(),
    )

    private fun record(
        substanceName: String,
        dose: Double?,
        units: String = "mg",
        color: AdaptiveColor = AdaptiveColor.BLUE,
        creationDate: Instant,
        customUnit: CustomUnit? = null,
    ): IngestionWithCompanionAndCustomUnit {
        return IngestionWithCompanionAndCustomUnit(
            ingestion = Ingestion(
                substanceName = substanceName,
                time = creationDate,
                creationDate = creationDate,
                administrationRoute = AdministrationRoute.ORAL,
                dose = dose,
                isDoseAnEstimate = false,
                estimatedDoseStandardDeviation = null,
                units = units,
                experienceId = 1,
                notes = null,
                stomachFullness = null,
                consumerName = null,
                customUnitId = customUnit?.id
            ),
            substanceCompanion = SubstanceCompanion(substanceName = substanceName, color = color),
            customUnit = customUnit
        )
    }

    @Test
    fun `buildSuggestions merges localized and canonical names into unique LazyColumn keys`() {
        val t1 = Instant.parse("2026-11-14T10:00:00Z")
        val t2 = Instant.parse("2026-11-15T10:00:00Z")
        val resolver: (String) -> Substance? = { raw ->
            when (raw.lowercase()) {
                "卡利哌嗪", "cariprazine" -> stubSubstance("Cariprazine")
                else -> null
            }
        }

        val suggestions = AddIngestionSearchViewModel.buildSuggestions(
            ingestions = listOf(
                record("卡利哌嗪", 1.5, color = AdaptiveColor.RED, creationDate = t1),
                record("Cariprazine", 3.0, color = AdaptiveColor.PINK, creationDate = t2),
            ),
            customSubstances = emptyList(),
            resolveSubstance = resolver
        )

        val keys = suggestions.map { it.stableKey }
        assertEquals(keys.distinct().size, keys.size)
        assertEquals(1, suggestions.size)

        val merged = suggestions.single() as Suggestion.PureSubstanceSuggestion
        assertEquals("Cariprazine", merged.substanceName)
        assertEquals("sug_pure_Cariprazine_ORAL", merged.stableKey)
        assertEquals(listOf(3.0, 1.5), merged.dosesAndUnit.map { it.dose })
        assertEquals(t2, merged.sortInstant)
    }

    @Test
    fun `buildSuggestions merges case variants and caps distinct doses at 8`() {
        val base = Instant.parse("2026-11-01T00:00:00Z")
        val ingestions = (1..10).map { idx ->
            val rawName = if (idx % 2 == 0) "amantadine" else "Amantadine"
            record(rawName, dose = idx * 100.0, creationDate = base.plusSeconds(idx.toLong()))
        }

        val suggestions = AddIngestionSearchViewModel.buildSuggestions(
            ingestions = ingestions,
            customSubstances = emptyList(),
            resolveSubstance = { raw ->
                if (raw.equals("Amantadine", ignoreCase = true)) stubSubstance("Amantadine") else null
            }
        )

        assertEquals(1, suggestions.size)
        val merged = suggestions.single() as Suggestion.PureSubstanceSuggestion
        assertEquals("sug_pure_Amantadine_ORAL", merged.stableKey)
        assertEquals(8, merged.dosesAndUnit.size)
    }

    @Test
    fun `buildSuggestions keeps custom substance distinct from canonical pure substance and deduplicates shared custom unit`() {
        val t1 = Instant.parse("2026-11-14T10:00:00Z")
        val t2 = Instant.parse("2026-11-15T10:00:00Z")
        val sharedUnit = CustomUnit(
            id = 42,
            substanceName = "Cariprazine",
            name = "半片",
            administrationRoute = AdministrationRoute.ORAL,
            dose = 0.75,
            estimatedDoseStandardDeviation = null,
            isEstimate = false,
            isArchived = false,
            unit = "片",
            unitPlural = "片",
            originalUnit = "mg",
            note = ""
        )
        val customCariprazine = CustomSubstance(
            id = 1,
            name = "卡利哌嗪",
            units = "mg",
            description = ""
        )

        val suggestions = AddIngestionSearchViewModel.buildSuggestions(
            ingestions = listOf(
                record("卡利哌嗪", 1.5, creationDate = t1),
                record("Cariprazine", 1.5, creationDate = t2),
                record("卡利哌嗪", 2.0, units = "片", creationDate = t1, customUnit = sharedUnit),
                record("Cariprazine", 1.0, units = "片", creationDate = t2, customUnit = sharedUnit),
            ),
            customSubstances = listOf(customCariprazine),
            resolveSubstance = { raw ->
                when (raw.lowercase()) {
                    "卡利哌嗪", "cariprazine" -> stubSubstance("Cariprazine")
                    else -> null
                }
            }
        )

        val keys = suggestions.map { it.stableKey }
        assertEquals(keys.distinct().size, keys.size)
        assertTrue("sug_pure_Cariprazine_ORAL" in keys)
        assertTrue("sug_cs_1_ORAL" in keys)
        assertTrue("sug_cu_42" in keys)
        assertEquals(3, suggestions.size)
    }
}
