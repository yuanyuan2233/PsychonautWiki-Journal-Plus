package com.isaakhanimann.journal.data.substances

import android.content.SharedPreferences
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.substances.classes.Category
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.SubstanceWithCategories
import com.isaakhanimann.journal.data.substances.classes.roa.Bioavailability
import com.isaakhanimann.journal.data.substances.classes.roa.Roa
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import com.isaakhanimann.journal.ui.tabs.settings.JournalExport
import com.isaakhanimann.journal.ui.tabs.settings.PkCustomizationExport
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedSubstanceResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun customSubstance_buildsFirstClassSubstanceWithAllRoutesDoseDurationAndMetabolite() {
        val store = PkCustomizationStore(FakeSharedPreferences())
        val resolver = UnifiedSubstanceResolver(FakeSubstanceRepository(), store)
        val custom = CustomSubstance(
            id = 7,
            name = "Custom Med-X",
            units = "mg",
            description = "Test custom compound",
            halfLifeHours = 12.0,
            tMaxHours = 2.0,
            washoutHours = 60.0,
            bioavailabilityFraction = 0.75,
            pkRole = "EXTENDED_RELEASE",
            commonDose = 20.0,
            activeMetaboliteName = "Nor-Med-X",
            activeMetaboliteHalfLifeHours = 24.0,
            onsetHours = 0.5,
            peakHours = 2.0,
            totalDurationHours = 8.0,
        )

        val resolved = resolver.resolveSubstance(
            substanceName = "Custom Med-X",
            customSubstances = listOf(custom),
        )

        assertNotNull(resolved)
        resolved!!
        assertEquals("Custom Med-X", resolved.name)
        assertFalse(resolved.isApproved)
        assertEquals("Test custom compound", resolved.summary)
        assertEquals(AdministrationRoute.entries.size, resolved.roas.size)

        val pk = resolved.pharmacokinetics
        assertNotNull(pk)
        pk!!
        assertEquals(12.0, pk.halfLifeHours!!, 1e-6)
        assertEquals(2.0, pk.tMaxHours!!, 1e-6)
        assertEquals(60.0, pk.washoutHours!!, 1e-6)
        assertEquals("EXTENDED_RELEASE", pk.pkRole)
        assertEquals("75%", pk.bioavailability)
        assertEquals(1, pk.activeMetabolites.size)
        assertEquals("Nor-Med-X", pk.activeMetabolites.first().name)
        assertEquals(24.0, pk.activeMetabolites.first().halfLifeHours!!, 1e-6)
        assertEquals(24.0, pk.resolvedEffectiveHalfLifeHours!!, 1e-6)

        val oral = resolved.getRoa(AdministrationRoute.ORAL)
        assertNotNull(oral)
        oral!!
        assertNotNull(oral.roaDose)
        assertEquals("mg", oral.roaDose!!.units)
        assertEquals(10.0, oral.roaDose!!.lightMin!!, 1e-6)
        assertEquals(20.0, oral.roaDose!!.commonMin!!, 1e-6)
        assertEquals(30.0, oral.roaDose!!.strongMin!!, 1e-6)
        assertEquals(50.0, oral.roaDose!!.heavyMin!!, 1e-6)

        assertNotNull(oral.roaDuration)
        assertEquals(0.5f, oral.roaDuration!!.onset!!.min!!, 1e-4f)
        assertEquals(2.0f, oral.roaDuration!!.peak!!.min!!, 1e-4f)
        assertEquals(8.0f, oral.roaDuration!!.total!!.min!!, 1e-4f)
        assertNotNull(oral.roaDuration!!.comeup)

        assertNotNull(oral.bioavailability)
        assertEquals(75.0, oral.bioavailability!!.min!!, 1e-6)
        assertEquals(75.0, oral.bioavailability!!.max!!, 1e-6)
    }

    @Test
    fun customSubstance_withOnlyHalfLife_derivesFallbackRoaDuration() {
        val store = PkCustomizationStore(FakeSharedPreferences())
        val resolver = UnifiedSubstanceResolver(FakeSubstanceRepository(), store)
        val custom = CustomSubstance(
            name = "Minimal Custom",
            units = "mg",
            description = "",
            halfLifeHours = 10.0,
        )

        val resolved = resolver.resolveSubstance(
            substanceName = "minimal custom",
            customSubstances = listOf(custom),
        )

        assertNotNull(resolved)
        resolved!!
        val oralDuration = resolved.getRoa(AdministrationRoute.ORAL)?.roaDuration
        val ivDuration = resolved.getRoa(AdministrationRoute.INTRAVENOUS)?.roaDuration
        assertNotNull(oralDuration)
        assertNotNull(ivDuration)
        assertNotNull(oralDuration!!.onset)
        assertNotNull(oralDuration.peak)
        assertNotNull(oralDuration.total)
        assertTrue(ivDuration!!.onset!!.max!! <= oralDuration.onset!!.max!!)
        assertNull(resolved.getRoa(AdministrationRoute.ORAL)?.roaDose)
    }

    @Test
    fun builtInSubstance_appliesPkCustomizationOverridesAndFallbackDuration() {
        val prefs = FakeSharedPreferences()
        val store = PkCustomizationStore(prefs)
        val builtIn = createSampleSubstance(
            name = "Alprazolam",
            pk = Pharmacokinetics(
                halfLife = "11h",
                halfLifeHours = 11.0,
                tMax = "1.5h",
                tMaxHours = 1.5,
                bioavailability = "80-90%",
                pkRole = "PARENT_DOMINANT",
            ),
            roas = listOf(
                Roa(
                    route = AdministrationRoute.ORAL,
                    roaDose = null,
                    roaDuration = null,
                    bioavailability = Bioavailability(min = 80.0, max = 90.0),
                )
            )
        )
        store.saveCustomization(
            SubstancePkCustomization(
                substanceName = "alprazolam",
                customHalfLifeHours = 18.0,
                customTMaxHours = 3.5,
                customWashoutHours = 100.0,
                customPkRole = "EXTENDED_RELEASE",
                customBioavailabilityFraction = 0.6,
            )
        )

        val resolver = UnifiedSubstanceResolver(FakeSubstanceRepository(listOf(builtIn)), store)
        val resolved = resolver.resolveSubstance("Alprazolam")

        assertNotNull(resolved)
        val pk = resolved!!.pharmacokinetics!!
        assertEquals(18.0, pk.halfLifeHours!!, 1e-6)
        assertEquals("18h", pk.halfLife)
        assertEquals(3.5, pk.tMaxHours!!, 1e-6)
        assertEquals("3.5h", pk.tMax)
        assertEquals(100.0, pk.washoutHours!!, 1e-6)
        assertEquals("EXTENDED_RELEASE", pk.pkRole)
        assertEquals("60%", pk.bioavailability)

        val oral = resolved.getRoa(AdministrationRoute.ORAL)!!
        assertEquals(60.0, oral.bioavailability!!.min!!, 1e-6)
        assertEquals(60.0, oral.bioavailability!!.max!!, 1e-6)
        assertNotNull(oral.roaDuration)
    }

    @Test
    fun builtInSubstanceWithoutPk_createsPkAndDurationFromCustomizationOrSchedule() {
        val store = PkCustomizationStore(FakeSharedPreferences())
        val builtInNoPk = createSampleSubstance(
            name = "LegacyMed",
            pk = null,
            roas = listOf(
                Roa(
                    route = AdministrationRoute.ORAL,
                    roaDose = null,
                    roaDuration = null,
                    bioavailability = null,
                )
            )
        )
        val resolver = UnifiedSubstanceResolver(FakeSubstanceRepository(listOf(builtInNoPk)), store)

        val resolved = resolver.resolveSubstance(
            substanceName = "LegacyMed",
            scheduleCustomHalfLifeHours = 14.0,
        )

        assertNotNull(resolved)
        assertNotNull(resolved!!.pharmacokinetics)
        assertEquals(14.0, resolved.pharmacokinetics!!.halfLifeHours!!, 1e-6)
        assertNotNull(resolved.getRoa(AdministrationRoute.ORAL)?.roaDuration)
    }

    @Test
    fun halfLifePrecedence_scheduleBeatsStoreBeatsCustomAndBuiltIn() {
        val store = PkCustomizationStore(FakeSharedPreferences())
        val builtIn = createSampleSubstance(
            name = "Lithium",
            pk = Pharmacokinetics(halfLifeHours = 18.0, tMaxHours = 2.0),
        )
        val custom = CustomSubstance(
            name = "CustomX",
            units = "mg",
            description = "",
            halfLifeHours = 8.0,
        )
        val resolver = UnifiedSubstanceResolver(FakeSubstanceRepository(listOf(builtIn)), store)

        assertEquals(
            18.0,
            resolver.resolvePharmacokinetics(substance = builtIn)?.halfLifeHours!!,
            1e-6,
        )
        assertEquals(
            8.0,
            resolver.resolvePharmacokinetics(substance = null, customSubstance = custom)?.halfLifeHours!!,
            1e-6,
        )

        store.saveCustomization(
            SubstancePkCustomization(substanceName = "Lithium", customHalfLifeHours = 24.0)
        )
        store.saveCustomization(
            SubstancePkCustomization(substanceName = "CustomX", customHalfLifeHours = 16.0)
        )

        assertEquals(
            24.0,
            resolver.resolvePharmacokinetics(substance = builtIn)?.halfLifeHours!!,
            1e-6,
        )
        assertEquals(
            16.0,
            resolver.resolvePharmacokinetics(substance = null, customSubstance = custom)?.halfLifeHours!!,
            1e-6,
        )

        assertEquals(
            36.0,
            resolver.resolvePharmacokinetics(
                substance = builtIn,
                scheduleCustomHalfLifeHours = 36.0,
            )?.halfLifeHours!!,
            1e-6,
        )
        val resolvedCustomWithSchedule = resolver.resolveSubstance(
            substanceName = "CustomX",
            customSubstances = listOf(custom),
            scheduleCustomHalfLifeHours = 30.0,
        )
        assertEquals(30.0, resolvedCustomWithSchedule!!.pharmacokinetics!!.halfLifeHours!!, 1e-6)
    }

    @Test
    fun pkCustomizationStore_persistsUpdatesFlowReplacesAndClears() {
        val prefs = FakeSharedPreferences()
        val store = PkCustomizationStore(prefs)
        assertTrue(store.customizationsFlow.value.isEmpty())

        store.saveCustomization(
            SubstancePkCustomization(
                substanceName = "Fluoxetine",
                customHalfLifeHours = 96.0,
                customBioavailabilityFraction = 0.72,
            )
        )

        assertEquals(96.0, store.getCustomization("fluoxetine")?.customHalfLifeHours!!, 1e-6)
        assertEquals(1, store.customizationsFlow.value.size)

        val reloaded = PkCustomizationStore(prefs)
        assertEquals(0.72, reloaded.getCustomization("Fluoxetine")?.customBioavailabilityFraction!!, 1e-6)

        assertTrue(
            store.replaceAllCustomizations(
                listOf(
                    SubstancePkCustomization(
                        substanceName = "Diazepam",
                        customHalfLifeHours = 48.0,
                        customPkRole = "ACTIVE_METABOLITE",
                    )
                )
            )
        )
        assertNull(store.getCustomization("Fluoxetine"))
        assertEquals(48.0, store.getCustomization("Diazepam")?.customHalfLifeHours!!, 1e-6)

        store.resetToDefault("diazepam")
        assertTrue(store.getAllCustomizations().isEmpty())

        store.saveCustomization(
            SubstancePkCustomization(substanceName = "Caffeine", customHalfLifeHours = 5.0)
        )
        assertTrue(store.clearAllCustomizations())
        assertTrue(store.customizationsFlow.value.isEmpty())
    }

    @Test
    fun customSubstanceAndJournalExport_preserveBackwardCompatibilityWithLegacyJson() {
        val legacyJson = """
            {
              "experiences": [],
              "substanceCompanions": [],
              "customSubstances": [
                {
                  "id": 3,
                  "name": "LegacyCustom",
                  "units": "mg",
                  "description": "Old format without PK fields"
                }
              ],
              "customUnits": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString<JournalExport>(legacyJson)
        assertEquals(1, decoded.customSubstances.size)
        val custom = decoded.customSubstances.first()
        assertEquals("LegacyCustom", custom.name)
        assertNull(custom.halfLifeHours)
        assertNull(custom.bioavailabilityFraction)
        assertNull(custom.totalDurationHours)
        assertTrue(decoded.pkCustomizations.isEmpty())

        val roundTripExport = JournalExport(
            customSubstances = listOf(
                CustomSubstance(
                    id = 4,
                    name = "EnrichedCustom",
                    units = "mg",
                    description = "With PK",
                    halfLifeHours = 6.5,
                    pkRole = "PRODRUG",
                    commonDose = 15.0,
                )
            ),
            pkCustomizations = listOf(
                PkCustomizationExport(
                    substanceName = "Caffeine",
                    customHalfLifeHours = 7.0,
                    customBioavailabilityFraction = 0.99,
                )
            ),
        )
        val encoded = json.encodeToString(roundTripExport)
        val reDecoded = json.decodeFromString<JournalExport>(encoded)
        assertEquals(6.5, reDecoded.customSubstances.first().halfLifeHours!!, 1e-6)
        assertEquals("PRODRUG", reDecoded.customSubstances.first().pkRole)
        assertEquals(1, reDecoded.pkCustomizations.size)
        assertEquals(7.0, reDecoded.pkCustomizations.first().customHalfLifeHours!!, 1e-6)
        assertEquals(0.99, reDecoded.pkCustomizations.first().customBioavailabilityFraction!!, 1e-6)
    }

    private fun createSampleSubstance(
        name: String,
        pk: Pharmacokinetics? = null,
        roas: List<Roa> = emptyList(),
    ): Substance {
        return Substance(
            name = name,
            commonNames = emptyList(),
            url = "",
            isApproved = true,
            tolerance = null,
            crossTolerances = emptyList(),
            addictionPotential = null,
            toxicities = emptyList(),
            categories = emptyList(),
            roas = roas,
            pharmacokinetics = pk,
        )
    }

    private class FakeSubstanceRepository(
        private val substances: List<Substance> = emptyList(),
    ) : SubstanceRepositoryInterface {
        override fun getAllSubstances(): List<Substance> = substances
        override fun getAllSubstancesWithCategories(): List<SubstanceWithCategories> = emptyList()
        override fun getAllCategories(): List<Category> = emptyList()
        override fun getSubstance(substanceName: String): Substance? =
            substances.firstOrNull { it.name.equals(substanceName, ignoreCase = true) }

        override fun getCategory(categoryName: String): Category? = null
        override fun getSubstanceWithCategories(substanceName: String): SubstanceWithCategories? = null
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)

        override fun getString(key: String?, defValue: String?): String? =
            (data[key] as? String) ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (data[key] as? MutableSet<String>)?.toMutableSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (data[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (data[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (data[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private val removals = linkedSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toMutableSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) {
                    removals.add(key)
                    pending.remove(key)
                }
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearAll = true
                pending.clear()
                removals.clear()
            }

            override fun commit(): Boolean {
                if (clearAll) {
                    data.clear()
                }
                for (key in removals) {
                    data.remove(key)
                }
                for ((key, value) in pending) {
                    if (value == null) data.remove(key) else data[key] = value
                }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
