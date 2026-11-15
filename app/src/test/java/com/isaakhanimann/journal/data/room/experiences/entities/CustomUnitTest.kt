package com.isaakhanimann.journal.data.room.experiences.entities

import com.isaakhanimann.journal.data.substances.AdministrationRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class CustomUnitTest {

    private fun unitWith(unit: String, unitPlural: String? = null) = CustomUnit(
        substanceName = "Caffeine",
        name = "Test",
        administrationRoute = AdministrationRoute.ORAL,
        dose = 50.0,
        estimatedDoseStandardDeviation = null,
        isEstimate = false,
        isArchived = false,
        unit = unit,
        unitPlural = unitPlural,
        originalUnit = "mg",
        note = ""
    )

    @Test
    fun getPluralizableUnit_handlesCjkMetricAndEnglishUnits() {
        assertEquals("片", unitWith("片").getPluralizableUnit().plural)
        assertEquals("粒", unitWith("粒").getPluralizableUnit().plural)
        assertEquals("µg", unitWith("µg").getPluralizableUnit().plural)
        assertEquals("mL", unitWith("mL").getPluralizableUnit().plural)
        assertEquals("mg", unitWith("mg").getPluralizableUnit().plural)
        assertEquals("g", unitWith("g").getPluralizableUnit().plural)
        assertEquals("IU", unitWith("IU").getPluralizableUnit().plural)
        assertEquals("pills", unitWith("pill").getPluralizableUnit().plural)
        assertEquals("pills", unitWith("pills").getPluralizableUnit().plural)
        assertEquals("caps", unitWith("cap", unitPlural = "caps").getPluralizableUnit().plural)
    }
}
