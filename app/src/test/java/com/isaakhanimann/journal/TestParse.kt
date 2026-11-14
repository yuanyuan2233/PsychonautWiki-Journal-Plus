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

package com.isaakhanimann.journal

import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.parse.SubstanceParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale

class TestParse {

    private fun isRealOrgJsonAvailable(): Boolean {
        return try {
            JSONObject("""{"ok":true}""").getBoolean("ok")
        } catch (_: RuntimeException) {
            false
        }
    }

    @Test
    fun noCrash() {
        val substances = SubstanceParser().parseSubstanceFile(string = "error")
        assertTrue(substances.categories.isEmpty())
        assertTrue(substances.substances.isEmpty())
    }

    @Test
    fun noCrashOnEmptyString() {
        val substances = SubstanceParser().parseSubstanceFile(string = "")
        assertTrue(substances.categories.isEmpty())
        assertTrue(substances.substances.isEmpty())
    }

    @Test
    fun noCrashExtract() {
        val result = SubstanceParser().extractSubstanceString(string = "error")
        assertTrue(result == null)
    }

    @Test
    fun mapAdministrationRoute_handlesTurkishLocaleAndInvalidRoutes() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals(AdministrationRoute.INSUFFLATED, SubstanceParser.mapAdministrationRoute("insufflated"))
            assertEquals(AdministrationRoute.INTRAMUSCULAR, SubstanceParser.mapAdministrationRoute("intramuscular"))
            assertEquals(AdministrationRoute.INTRAVENOUS, SubstanceParser.mapAdministrationRoute("intravenous"))
            assertEquals(AdministrationRoute.INHALED, SubstanceParser.mapAdministrationRoute("inhaled"))
            assertEquals(AdministrationRoute.ORAL, SubstanceParser.mapAdministrationRoute(" oral "))
            assertNull(SubstanceParser.mapAdministrationRoute("unknown"))
            assertNull(SubstanceParser.mapAdministrationRoute("  "))
            assertNull(SubstanceParser.mapAdministrationRoute(null))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun testExtractSubstancesString() {
        assumeTrue(
            "Requires real org.json implementation (absent on JVM unit test classpath)",
            isRealOrgJsonAvailable()
        )
        val text = """
{
  "data": {
    "substances": [
      {
        "name": "Armodafinil",
        "roas": [
          {
            "name": "oral"
          }
        ]
      }
    ]
  }
}"""
        val result = SubstanceParser().extractSubstanceString(string = text)
        assertTrue(result == "[{\"name\":\"Armodafinil\",\"roas\":[{\"name\":\"oral\"}]}]")
    }

    @Test
    fun parseSubstanceFile_skipsCorruptEntriesAndPreservesValidOnes() {
        assumeTrue(
            "Requires real org.json implementation (absent on JVM unit test classpath)",
            isRealOrgJsonAvailable()
        )
        val json = """
{
  "categories": [
    { "name": "psychedelic", "description": "Valid category" },
    { "name": "", "description": "Blank name should be skipped" },
    "not-an-object"
  ],
  "substances": [
    {
      "name": "ValidDrug",
      "url": "https://example.org/ValidDrug",
      "roas": [
        {
          "name": "insufflated",
          "dose": { "units": "mg", "lightMin": 5.0, "commonMin": 15.0 }
        },
        {
          "name": "oral",
          "dose": { "commonMin": 10.0 }
        }
      ]
    },
    {
      "url": "https://example.org/MissingName"
    },
    {
      "name": "MissingUrl",
      "url": ""
    },
    "corrupt-item",
    {
      "name": "SecondValid",
      "url": "https://example.org/SecondValid",
      "roas": []
    }
  ]
}"""
        val result = SubstanceParser().parseSubstanceFile(json)
        assertEquals(1, result.categories.size)
        assertEquals("psychedelic", result.categories.first().name)
        assertEquals(2, result.substances.size)
        assertEquals("ValidDrug", result.substances[0].name)
        assertEquals(2, result.substances[0].roas.size)
        assertEquals("mg", result.substances[0].roas[0].roaDose?.units)
        assertNull(result.substances[0].roas[1].roaDose)
        assertEquals("SecondValid", result.substances[1].name)
    }

    @Test
    fun parseSubstanceFile_handlesMissingTopLevelArrays() {
        assumeTrue(
            "Requires real org.json implementation (absent on JVM unit test classpath)",
            isRealOrgJsonAvailable()
        )
        val result = SubstanceParser().parseSubstanceFile("""{"substances":[{"name":"OnlyDrug","url":"https://example.org/OnlyDrug"}]}""")
        assertTrue(result.categories.isEmpty())
        assertEquals(1, result.substances.size)
        assertEquals("OnlyDrug", result.substances.first().name)
    }

    @Test
    fun parseSubstanceFile_preservesRoutesUnderTurkishLocale() {
        assumeTrue(
            "Requires real org.json implementation (absent on JVM unit test classpath)",
            isRealOrgJsonAvailable()
        )
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            val json = """
{
  "categories": [],
  "substances": [
    {
      "name": "LocaleCheck",
      "url": "https://example.org/LocaleCheck",
      "roas": [
        { "name": "insufflated" },
        { "name": "intramuscular" },
        { "name": "intravenous" },
        { "name": "inhaled" }
      ]
    }
  ]
}"""
            val result = SubstanceParser().parseSubstanceFile(json)
            assertEquals(1, result.substances.size)
            assertEquals(
                listOf(
                    AdministrationRoute.INSUFFLATED,
                    AdministrationRoute.INTRAMUSCULAR,
                    AdministrationRoute.INTRAVENOUS,
                    AdministrationRoute.INHALED
                ),
                result.substances.first().roas.map { it.route }
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun parseBundledSubstancesAsset_succeedsWithNonEmptyCatalog() {
        assumeTrue(
            "Requires real org.json implementation",
            isRealOrgJsonAvailable()
        )
        val candidates = listOf(
            java.io.File("src/main/assets/Substances.json"),
            java.io.File("app/src/main/assets/Substances.json")
        )
        val assetFile = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Substances.json not found from ${java.io.File(".").absolutePath}")
        val result = SubstanceParser().parseSubstanceFile(assetFile.readText(Charsets.UTF_8))
        assertTrue("Expected non-empty categories", result.categories.isNotEmpty())
        assertTrue("Expected large substance catalog", result.substances.size > 100)
        assertTrue("LSD should exist in catalog", result.substances.any { it.name == "LSD" })
    }
}