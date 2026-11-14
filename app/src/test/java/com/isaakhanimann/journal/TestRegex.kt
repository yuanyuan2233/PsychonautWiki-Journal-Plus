package com.isaakhanimann.journal

import com.isaakhanimann.journal.data.substances.classes.Category
import com.isaakhanimann.journal.data.substances.classes.InteractionType
import com.isaakhanimann.journal.data.substances.classes.Interactions
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.SubstanceWithCategories
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import com.isaakhanimann.journal.ui.tabs.journal.addingestion.interactions.InteractionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TestRegex {

    private fun testSubstance(
        name: String,
        categories: List<String> = emptyList(),
        dangerous: List<String> = emptyList(),
        unsafe: List<String> = emptyList(),
        uncertain: List<String> = emptyList(),
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
            categories = categories,
            interactions = Interactions(
                dangerous = dangerous,
                unsafe = unsafe,
                uncertain = uncertain,
            ),
            roas = emptyList(),
        )
    }

    private fun createChecker(vararg substances: Substance): InteractionChecker {
        val entries = substances.map { substance ->
            SubstanceWithCategories(substance = substance, categories = emptyList())
        }
        val fakeRepository = object : SubstanceRepositoryInterface {
            override fun getAllSubstances(): List<Substance> = entries.map { it.substance }

            override fun getAllSubstancesWithCategories(): List<SubstanceWithCategories> = entries

            override fun getAllCategories(): List<Category> = emptyList()

            override fun getSubstance(substanceName: String): Substance? =
                entries.firstOrNull { it.substance.name == substanceName }?.substance

            override fun getCategory(categoryName: String): Category? = null

            override fun getSubstanceWithCategories(substanceName: String): SubstanceWithCategories? =
                entries.firstOrNull { it.substance.name == substanceName }
        }
        return InteractionChecker(fakeRepository)
    }

    @Test
    fun wildcardMatchingMatchesChemicalFamiliesWithoutBreakingNormalWords() {
        val checker = createChecker(
            testSubstance(
                name = "Lithium",
                dangerous = listOf("5-MeO-xxT", "2C-x", "2C-T-x", "25x-NBOMe", "25x-NBOH", "DOx"),
                unsafe = listOf("Methoxetamine", "Dextromethorphan", "Oxycodone", "Xanax")
            ),
            testSubstance(name = "5-MeO-DALT"),
            testSubstance(name = "5-MeO-DMT"),
            testSubstance(name = "5-MeO-DiPT"),
            testSubstance(name = "5-MeO-EiPT"),
            testSubstance(name = "2C-B"),
            testSubstance(name = "2C-T-7"),
            testSubstance(name = "25I-NBOMe"),
            testSubstance(name = "25B-NBOH"),
            testSubstance(name = "DOB"),
            testSubstance(name = "DOM"),
            testSubstance(name = "Doxepin", categories = listOf("tca")),
            testSubstance(name = "Methoxetamine"),
            testSubstance(name = "Methoetamine"),
            testSubstance(name = "Metho123etamine"),
            testSubstance(name = "Detromethorphan"),
            testSubstance(name = "Oycodone"),
            testSubstance(name = "Xanax"),
            testSubstance(name = "Xanab"),
        )

        listOf(
            "5-MeO-DALT",
            "5-MeO-DMT",
            "5-MeO-DiPT",
            "5-MeO-EiPT",
            "2C-B",
            "2C-T-7",
            "25I-NBOMe",
            "25B-NBOH",
            "DOB",
            "DOM"
        ).forEach { target ->
            val interaction = checker.getInteractionBetween("Lithium", target)
            assertNotNull("Expected wildcard match for $target", interaction)
            assertEquals(InteractionType.DANGEROUS, interaction?.interactionType)
        }

        // DOx should NOT falsely match unrelated tricyclic antidepressant Doxepin
        assertNull(checker.getInteractionBetween("Lithium", "Doxepin"))

        // Literal 'x' inside normal substance names must not act as a wildcard
        assertEquals(
            InteractionType.UNSAFE,
            checker.getInteractionBetween("Lithium", "Methoxetamine")?.interactionType
        )
        assertEquals(
            InteractionType.UNSAFE,
            checker.getInteractionBetween("Lithium", "Xanax")?.interactionType
        )
        assertNull(checker.getInteractionBetween("Lithium", "Methoetamine"))
        assertNull(checker.getInteractionBetween("Lithium", "Metho123etamine"))
        assertNull(checker.getInteractionBetween("Lithium", "Detromethorphan"))
        assertNull(checker.getInteractionBetween("Lithium", "Oycodone"))
        assertNull(checker.getInteractionBetween("Lithium", "Xanab"))
    }

    @Test
    fun directAndClassMatchingHandlesCaseInsensitivityChineseAndTcaVsDepressant() {
        val checker = createChecker(
            testSubstance(
                name = "Tramadol",
                dangerous = listOf(
                    "单胺氧化酶抑制剂 (MAOI)",
                    "三环类抗抑郁药 (TCAs)",
                    "SSRIs / SNRIs"
                ),
                unsafe = listOf("mdma")
            ),
            testSubstance(name = "MDMA"),
            testSubstance(name = "Moclobemide", categories = listOf("maoi")),
            testSubstance(name = "Amitriptyline", categories = listOf("tca")),
            testSubstance(name = "Diazepam", categories = listOf("depressant", "benzodiazepine")),
        )

        // Case-insensitive direct match ("mdma" vs "MDMA")
        assertEquals(
            InteractionType.UNSAFE,
            checker.getInteractionBetween("Tramadol", "MDMA")?.interactionType
        )

        // Chinese + parenthetical class matches
        assertEquals(
            InteractionType.DANGEROUS,
            checker.getInteractionBetween("Tramadol", "Moclobemide")?.interactionType
        )
        assertEquals(
            InteractionType.DANGEROUS,
            checker.getInteractionBetween("Tramadol", "Amitriptyline")?.interactionType
        )

        // Depressant category should NOT falsely match "Tricyclic antidepressants"
        assertNull(checker.getInteractionBetween("Tramadol", "Diazepam"))
    }

    @Test
    fun matchesExtraInteractionHandlesLocalizedAndParentheticalEntries() {
        val checker = createChecker()

        assertTrue(
            checker.matchesExtraInteraction(
                listOf("单胺氧化酶抑制剂 (MAOI)"),
                "MAOI"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("选择性5-羟色胺再摄取抑制剂 (SSRI) (如氟西汀)"),
                "SSRIs"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("SSRIs / SNRIs (如氟西汀、度洛西汀)"),
                "SSRIs"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("SSRIs / SNRIs (如氟西汀、度洛西汀)"),
                "SNRIs"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("5-羟色胺与去甲肾上腺素再摄取抑制剂 (SNRIs)"),
                "SNRIs"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("复方口服避孕药 (Combined oral contraceptives)"),
                "Hormonal birth control"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("三环类抗抑郁药 (TCAs)"),
                "Tricyclic antidepressants"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("咖啡因 (Caffeine)"),
                "Caffeine"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("碳酸锂"),
                "Lithium"
            )
        )
        // Parenthetical warning mentioning lithium toxicity must NOT falsely match Lithium option
        assertFalse(
            checker.matchesExtraInteraction(
                listOf("ACEI / ARB 降压药 (合用可能显著升高血锂浓度，增加锂中毒风险)"),
                "Lithium"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("酒精 (乙醇)"),
                "Alcohol"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("西柚汁 (葡萄柚汁)"),
                "Grapefruit"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("5-羟色氨酸 (5-HTP)"),
                "5-Hydroxytryptophan"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("碳青霉烯类抗生素"),
                "Antibiotics"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("镇静性抗组胺药"),
                "Antihistamine"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("大麻 (Cannabis)"),
                "Cannabis"
            )
        )
        assertTrue(
            checker.matchesExtraInteraction(
                listOf("尼古丁"),
                "Nicotine"
            )
        )
        assertFalse(checker.matchesExtraInteraction(null, "MAOI"))
    }
}
