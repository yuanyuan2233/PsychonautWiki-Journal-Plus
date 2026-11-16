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
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
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

    @Test
    fun parseBundledSubstancesAsset_includesDualPkMetabolitesAndReceptors() {
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
        val byName = result.substances.associateBy { it.name }

        val cariprazine = byName["Cariprazine"]
            ?: throw AssertionError("Cariprazine must exist in bundled catalog")
        val cariPk = cariprazine.pharmacokinetics!!
        org.junit.Assert.assertEquals("ACTIVE_METABOLITE", cariPk.pkRole)
        org.junit.Assert.assertEquals(72.0, cariPk.halfLifeHours!!, 0.01)
        org.junit.Assert.assertEquals(336.0, cariPk.resolvedEffectiveHalfLifeHours!!, 0.01)
        org.junit.Assert.assertEquals(1008.0, cariPk.resolvedWashoutHours!!, 0.01)
        assertTrue(cariPk.activeMetabolites.any { it.name.contains("DCAR") && !it.name.contains("DDCAR") })
        assertTrue(cariPk.activeMetabolites.any { it.name.contains("DDCAR") && it.halfLifeHours == 336.0 })
        assertTrue(cariPk.receptorTargets.any { it.target.contains("D3") && (it.dominantActor ?: "").contains("DDCAR") })
        assertTrue(cariprazine.roas.isNotEmpty())

        val brex = byName["Brexpiprazole"] ?: throw AssertionError("Brexpiprazole missing")
        org.junit.Assert.assertEquals("PARENT_DOMINANT", brex.pharmacokinetics?.pkRole)
        org.junit.Assert.assertEquals(455.0, brex.pharmacokinetics?.resolvedWashoutHours!!, 0.01)

        val desven = byName["Desvenlafaxine"] ?: throw AssertionError("Desvenlafaxine missing")
        org.junit.Assert.assertEquals("PARENT_DOMINANT", desven.pharmacokinetics?.pkRole)
        org.junit.Assert.assertEquals(72.0, desven.pharmacokinetics?.resolvedWashoutHours!!, 0.01)

        val fluoxetine = byName["Fluoxetine"]?.pharmacokinetics!!
        org.junit.Assert.assertEquals("ACTIVE_METABOLITE", fluoxetine.pkRole)
        org.junit.Assert.assertEquals(72.0, fluoxetine.halfLifeHours!!, 0.01)
        org.junit.Assert.assertEquals(216.0, fluoxetine.resolvedEffectiveHalfLifeHours!!, 0.01)
        org.junit.Assert.assertEquals(840.0, fluoxetine.resolvedWashoutHours!!, 0.01)
        assertTrue(fluoxetine.activeMetabolites.any { it.name.contains("Norfluoxetine") })

        val tranyl = byName["Tranylcypromine"]?.pharmacokinetics!!
        org.junit.Assert.assertEquals("IRREVERSIBLE_PD", tranyl.pkRole)
        org.junit.Assert.assertEquals(2.5, tranyl.halfLifeHours!!, 0.01)
        org.junit.Assert.assertEquals(336.0, tranyl.resolvedWashoutHours!!, 0.01)

        val codeine = byName["Codeine"]?.pharmacokinetics!!
        org.junit.Assert.assertEquals("PRODRUG", codeine.pkRole)
        assertTrue(codeine.activeMetabolites.any { it.name.contains("Morphine") })

        val tramadol = byName["Tramadol"]?.pharmacokinetics!!
        org.junit.Assert.assertEquals("ACTIVE_METABOLITE", tramadol.pkRole)
        assertTrue(tramadol.activeMetabolites.any { it.name.contains("O-DSMT") })

        val upgradedWithRoles = result.substances.count { it.pharmacokinetics?.pkRole != null }
        assertTrue("Expected at least 55 upgraded PK profiles, got $upgradedWithRoles", upgradedWithRoles >= 55)
    }

    @Test
    fun chineseSubstanceNamesAndFallbackRoaDurationResolveCorrectly() {
        assumeTrue(
            "Requires real org.json implementation",
            isRealOrgJsonAvailable()
        )
        val candidates = listOf(
            java.io.File("src/main/assets/Substances.json"),
            java.io.File("app/src/main/assets/Substances.json")
        )
        val assetFile = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Substances.json not found")
        val result = SubstanceParser().parseSubstanceFile(assetFile.readText(Charsets.UTF_8))

        val chineseSamples = listOf(
            "卡利哌嗪" to "Cariprazine",
            "奥卡西平" to "Oxcarbazepine",
            "帕利哌酮" to "Paliperidone",
            "丙戊酸钠" to "Sodium valproate"
        )
        for ((zhName, expectedEn) in chineseSamples) {
            val canonical = SubstanceLocalizationHelper.getCanonicalEnglishName(zhName)
            assertEquals("Canonical English mismatch for $zhName", expectedEn, canonical)
            val matched = result.substances.firstOrNull { s ->
                s.name.equals(canonical, ignoreCase = true) ||
                    s.commonNames.any { it.equals(zhName, ignoreCase = true) }
            }
            assertTrue("Expected bundled substance for $zhName", matched != null)
            val fallbackDuration = matched!!.pharmacokinetics?.toFallbackRoaDuration()
            assertTrue("Expected non-null fallback RoaDuration for $zhName", fallbackDuration?.total?.maxInSec != null)
        }
    }

    @Test
    fun parseBundledSubstancesAsset_includesPhase5EnrichedAndNewClinicalDrugs() {
        assumeTrue(
            "Requires real org.json implementation",
            isRealOrgJsonAvailable()
        )
        val candidates = listOf(
            java.io.File("src/main/assets/Substances.json"),
            java.io.File("app/src/main/assets/Substances.json")
        )
        val assetFile = candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("Substances.json not found")
        val result = SubstanceParser().parseSubstanceFile(assetFile.readText(Charsets.UTF_8))

        assertTrue(
            "Expected at least 474 parsed substances, got ${result.substances.size}",
            result.substances.size >= 474
        )
        val withPkCount = result.substances.count { it.pharmacokinetics != null }
        assertTrue(
            "Expected at least 229 substances with pharmacokinetics, got $withPkCount",
            withPkCount >= 229
        )

        fun findSub(name: String) = result.substances.firstOrNull { it.name.equals(name, ignoreCase = true) }

        val enrichedExpected = listOf(
            "Alcohol" to "ZERO_ORDER",
            "Dextromethorphan" to "ACTIVE_METABOLITE",
            "Ketamine" to "ACTIVE_METABOLITE",
            "Caffeine" to "ACTIVE_METABOLITE",
            "Heroin" to "PRODRUG",
            "Adrafinil" to "PRODRUG",
            "Carisoprodol" to "PRODRUG",
            "Phenibut" to "PARENT_DOMINANT"
        )
        for ((name, expectedRole) in enrichedExpected) {
            val sub = findSub(name)
            assertTrue("Expected enriched substance $name", sub != null)
            val pk = sub!!.pharmacokinetics
            assertTrue("Expected non-null pharmacokinetics for $name", pk != null)
            assertEquals("Unexpected pkRole for $name", expectedRole, pk!!.pkRole)
            assertTrue("Expected positive halfLifeHours for $name", (pk.halfLifeHours ?: 0.0) > 0.0)
            assertTrue("Expected positive tMaxHours for $name", (pk.tMaxHours ?: 0.0) > 0.0)
        }

        val metaboliteChecks = listOf(
            "Dextromethorphan" to "Dextrorphan",
            "Ketamine" to "Norketamine",
            "Caffeine" to "Paraxanthine",
            "Heroin" to "Morphine",
            "Adrafinil" to "Modafinil",
            "Carisoprodol" to "Meprobamate",
            "Ramelteon" to "M-II",
            "Naltrexone" to "Naltrexol",
            "Levodopa" to "Dopamine",
            "Propranolol" to "4-Hydroxypropranolol",
            "Esketamine" to "S-Norketamine",
            "Lidocaine" to "MEGX",
            "Thiopental" to "Pentobarbital"
        )
        for ((parentName, metaboliteKeyword) in metaboliteChecks) {
            val pk = findSub(parentName)?.pharmacokinetics
            assertTrue("Expected pharmacokinetics for $parentName", pk != null)
            assertTrue(
                "Expected active metabolite containing '$metaboliteKeyword' on $parentName",
                pk!!.activeMetabolites.any { it.name.contains(metaboliteKeyword, ignoreCase = true) }
            )
        }

        val newClinicalDrugs = listOf(
            "Propranolol" to "普萘洛尔",
            "Metoprolol" to "美托洛尔",
            "Lemborexant" to "莱博雷生",
            "Suvorexant" to "苏沃雷生",
            "Ramelteon" to "雷美替胺",
            "Milnacipran" to "米那普仑",
            "Cetirizine" to "西替利嗪",
            "Naltrexone" to "纳曲酮",
            "Pramipexole" to "普拉克索",
            "Levodopa" to "左旋多巴",
            "Dexmedetomidine" to "右美托咪定",
            "Propofol" to "丙泊酚",
            "Esketamine" to "艾司氯胺酮",
            "Remifentanil" to "瑞芬太尼",
            "Alfentanil" to "阿芬太尼",
            "Sevoflurane" to "七氟烷",
            "Isoflurane" to "异氟烷",
            "Lidocaine" to "利多卡因",
            "Ropivacaine" to "罗哌卡因",
            "Thiopental" to "硫喷妥钠"
        )
        for ((enName, zhName) in newClinicalDrugs) {
            assertEquals(
                "Canonical lookup mismatch for $zhName",
                enName,
                SubstanceLocalizationHelper.getCanonicalEnglishName(zhName)
            )
            val sub = findSub(enName)
            assertTrue("Expected new clinical drug $enName", sub != null)
            assertTrue("Expected non-empty categories for $enName", sub!!.categories.isNotEmpty())
            assertTrue("Expected non-empty roas for $enName", sub.roas.isNotEmpty())
            assertTrue(
                "Expected at least one ROA with dose and duration for $enName",
                sub.roas.any { it.roaDose != null && it.roaDuration != null }
            )
            val pk = sub.pharmacokinetics
            assertTrue("Expected pharmacokinetics for $enName", pk != null)
            assertTrue("Expected positive halfLifeHours for $enName", (pk!!.halfLifeHours ?: 0.0) > 0.0)
            assertTrue("Expected positive tMaxHours for $enName", (pk.tMaxHours ?: 0.0) > 0.0)
            assertTrue("Expected non-empty cypEnzymes for $enName", pk.cypEnzymes.isNotEmpty())
            assertTrue("Expected non-empty receptorTargets for $enName", pk.receptorTargets.isNotEmpty())
            assertTrue(
                "Expected non-null fallback RoaDuration for $enName",
                pk.toFallbackRoaDuration()?.total?.maxInSec != null
            )
        }

        val intentionallyOmittedNoHumanPk = listOf(
            "Ethylphenidate",
            "4F-MPH",
            "Isopropylphenidate",
            "1cP-MiPLA",
            "3-Me-PCPy",
            "25N-NBOMe",
            "MXiPr",
            "RGPU-95",
            "Omberacetam",
            "Meclofenoxate",
            "Alpha-GPC",
            "Poppers",
            "Fenethylline"
        )
        for (name in intentionallyOmittedNoHumanPk) {
            val sub = findSub(name)
            assertTrue("Expected substance $name to remain in Substances.json", sub != null)
            assertNull("Expected null pharmacokinetics for unverified substance $name", sub?.pharmacokinetics)
        }
    }
}