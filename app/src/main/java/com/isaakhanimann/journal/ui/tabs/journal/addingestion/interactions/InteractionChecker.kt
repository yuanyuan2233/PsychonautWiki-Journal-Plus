/*
 * Copyright (c) 2022-2023. Isaak Hanimann.
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

package com.isaakhanimann.journal.ui.tabs.journal.addingestion.interactions

import com.isaakhanimann.journal.data.substances.EnzymaticDdiFinding
import com.isaakhanimann.journal.data.substances.EnzymaticDdiModifier
import com.isaakhanimann.journal.data.substances.classes.InteractionType
import com.isaakhanimann.journal.data.substances.classes.Interactions
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class InteractionChecker @Inject constructor(
    private val substanceRepo: SubstanceRepositoryInterface,
) {
    fun getInteractionBetween(aName: String, bName: String): Interaction? {
        return getInteractionBetween(
            aName = aName,
            bName = bName,
            additionalSubstances = emptyList()
        )
    }

    fun getInteractionBetween(
        aName: String,
        bName: String,
        additionalSubstances: Collection<Substance>,
    ): Interaction? {
        if (aName.equals(bName, ignoreCase = true)) {
            return null
        }
        val interactionFromAToB = getInteractionFromAToB(aName, bName, additionalSubstances)
        val interactionFromBToA = getInteractionFromAToB(bName, aName, additionalSubstances)
        if (interactionFromAToB != null && interactionFromBToA != null) {
            val isAtoB = interactionFromAToB.dangerCount >= interactionFromBToA.dangerCount
            val interactionType = if (isAtoB) interactionFromAToB else interactionFromBToA
            return Interaction(
                aName = aName,
                bName = bName,
                interactionType = interactionType,
            )
        } else if (interactionFromAToB != null) {
            return Interaction(
                aName = aName,
                bName = bName,
                interactionType = interactionFromAToB,
            )
        } else if (interactionFromBToA != null) {
            return Interaction(
                aName = aName,
                bName = bName,
                interactionType = interactionFromBToA,
            )
        } else {
            return null
        }
    }

    fun getEnzymaticDdiBetween(
        targetSubstance: Substance,
        coAdministeredSubstances: Collection<Substance>,
    ): List<EnzymaticDdiFinding> {
        val uniqueSubstances = linkedMapOf<String, Substance>()
        uniqueSubstances[targetSubstance.name.lowercase(Locale.ROOT)] = targetSubstance
        coAdministeredSubstances.forEach { candidate ->
            val key = candidate.name.lowercase(Locale.ROOT)
            if (!uniqueSubstances.containsKey(key)) {
                uniqueSubstances[key] = candidate
            }
        }
        if (uniqueSubstances.size < 2) {
            return emptyList()
        }
        return EnzymaticDdiModifier.analyzeCoAdministeredSubstances(uniqueSubstances.values.toList())
            .filter { finding ->
                finding.victimName.equals(targetSubstance.name, ignoreCase = true) ||
                    finding.perpetratorName.equals(targetSubstance.name, ignoreCase = true)
            }
    }

    private fun resolveSubstanceForCheck(
        name: String,
        additionalSubstances: Collection<Substance>,
    ): Substance? {
        return additionalSubstances.firstOrNull { candidate ->
            candidate.name.equals(name, ignoreCase = true) ||
                candidate.commonNames.any { it.equals(name, ignoreCase = true) }
        } ?: substanceRepo.getSubstance(name)
    }

    private fun getInteractionFromAToB(
        aName: String,
        bName: String,
        additionalSubstances: Collection<Substance>,
    ): InteractionType? {
        val substanceA = resolveSubstanceForCheck(aName, additionalSubstances)
        if (substanceA != null) {
            val directInteraction = getDirectInteraction(interactions = substanceA.interactions, substanceName = bName)
            if (directInteraction != null) {
                return directInteraction
            }
            val wildcardInteraction = getWildcardInteraction(interactions = substanceA.interactions, substanceName = bName)
            if (wildcardInteraction != null) {
                return wildcardInteraction
            }
            val substanceB = resolveSubstanceForCheck(bName, additionalSubstances)
            if (substanceB != null) {
                val classInteraction = getClassInteraction(interactions = substanceA.interactions, categories = substanceB.categories)
                if (classInteraction != null) {
                    return classInteraction
                }
            }
        }
        return null
    }

    private fun getDirectInteraction(interactions: Interactions?, substanceName: String): InteractionType? {
        if (interactions == null) {
            return null
        }
        return if (isDirectMatch(interactions.dangerous, substanceName)) {
            InteractionType.DANGEROUS
        } else if (isDirectMatch(interactions.unsafe, substanceName)) {
            InteractionType.UNSAFE
        } else if (isDirectMatch(interactions.uncertain, substanceName)) {
            InteractionType.UNCERTAIN
        } else {
            null
        }
    }

    private fun getWildcardInteraction(interactions: Interactions?, substanceName: String): InteractionType? {
        if (interactions == null) {
            return null
        }
        return if (isWildcardMatch(interactions.dangerous, substanceName)) {
            InteractionType.DANGEROUS
        } else if (isWildcardMatch(interactions.unsafe, substanceName)) {
            InteractionType.UNSAFE
        } else if (isWildcardMatch(interactions.uncertain, substanceName)) {
            InteractionType.UNCERTAIN
        } else {
            null
        }
    }

    private fun getClassInteraction(interactions: Interactions?, categories: List<String>): InteractionType? {
        if (interactions == null) {
            return null
        }
        return if (isClassMatch(interactions.dangerous, categories)) {
            InteractionType.DANGEROUS
        } else if (isClassMatch(interactions.unsafe, categories)) {
            InteractionType.UNSAFE
        } else if (isClassMatch(interactions.uncertain, categories)) {
            InteractionType.UNCERTAIN
        } else {
            null
        }
    }

    /**
     * 检查来自 CombinationSettingsStorage 的额外风险项（如 "MAOI"、"SSRIs"、"Hormonal birth control" 等）
     * 是否命中物质交互列表（支持中英文、括号临床说明、斜杠拆分与常见缩写归一化）。
     */
    fun matchesExtraInteraction(interactions: List<String>?, extraInteraction: String): Boolean {
        if (interactions.isNullOrEmpty() || extraInteraction.isBlank()) {
            return false
        }
        return isDirectMatch(interactions = interactions, substanceName = extraInteraction)
    }

    private fun isClassMatch(interactions: List<String>, categories: List<String>): Boolean {
        val extendedInteractions = extendAndCleanInteractions(interactions)
        return categories.any { categoryName ->
            val trimmedCategory = categoryName.trim()
            if (trimmedCategory.isEmpty()) return@any false
            val boundaryRegex = Regex(
                pattern = "(?<![A-Za-z])${Regex.escape(trimmedCategory)}",
                option = RegexOption.IGNORE_CASE
            )
            extendedInteractions.any { interactionName ->
                // 避免 category="depressant"/"antidepressant" 与三环类抗抑郁药发生误匹配，但保留 category="tca" 命中 "TCAs"
                if (isTricyclicTerm(interactionName) && !trimmedCategory.equals("tca", ignoreCase = true)) {
                    false
                } else {
                    boundaryRegex.containsMatchIn(interactionName)
                }
            }
        }
    }

    private fun isWildcardMatch(interactions: List<String>, substanceName: String): Boolean {
        val extendedInteractions = extendAndCleanInteractions(interactions)
        val targetVariants = expandTermVariants(substanceName)
        return extendedInteractions.any { interaction ->
            val regex = buildWildcardRegex(interaction) ?: return@any false
            targetVariants.any { target -> regex.matches(target) }
        }
    }

    private fun isDirectMatch(interactions: List<String>, substanceName: String): Boolean {
        val extendedInteractions = extendAndCleanInteractions(interactions)
        val targetVariants = expandTermVariants(substanceName)
        return extendedInteractions.any { interaction ->
            targetVariants.any { target -> interaction.equals(target, ignoreCase = true) }
        }
    }

    private fun extendAndCleanInteractions(interactions: List<String>): List<String> {
        return interactions.flatMap { rawName ->
            val resolvedList = mutableListOf<String>()
            addExpandedTermVariants(rawName, resolvedList)
            // 去除括号中的临床说明或化学注释
            val clean = rawName.replace(PAREN_REGEX, "").trim()
            if (clean != rawName && clean.isNotEmpty()) {
                addExpandedTermVariants(clean, resolvedList)
            }
            // 针对 "/"、"、"、"|" 拆分多物质条目
            val parts = clean.split(SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size > 1) {
                for (part in parts) {
                    addExpandedTermVariants(part, resolvedList)
                }
            }
            resolvedList
        }.flatMap { name ->
            when {
                name.equals("Substituted amphetamines", ignoreCase = true) || name == "取代苯丙胺类" -> {
                    substitutedAmphetamines
                }
                name.equals("Serotonin releasers", ignoreCase = true) || name == "5-羟色胺释放剂" -> {
                    serotoninReleasers
                }
                else -> {
                    listOf(name)
                }
            }
        }.distinct()
    }

    private fun expandTermVariants(term: String): List<String> {
        val variants = mutableListOf<String>()
        addExpandedTermVariants(term, variants)
        val clean = term.replace(PAREN_REGEX, "").trim()
        if (clean.isNotEmpty() && clean != term) {
            addExpandedTermVariants(clean, variants)
        }
        return variants.distinct()
    }

    private fun addExpandedTermVariants(term: String, out: MutableList<String>) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        out.add(trimmed)

        val canonical = SubstanceLocalizationHelper.getCanonicalEnglishName(trimmed)
        if (!canonical.isNullOrBlank()) {
            out.add(canonical)
            CANONICAL_ALIAS_MAP[canonical.lowercase(Locale.ROOT)]?.let { out.addAll(it) }
        }

        val lower = trimmed.lowercase(Locale.ROOT)
        CANONICAL_ALIAS_MAP[lower]?.let { out.addAll(it) }

        when {
            trimmed.endsWith("抗生素") -> out.add("Antibiotics")
            trimmed.endsWith("抗组胺药") || trimmed.endsWith("抗组胺药物") -> out.add("Antihistamine")
            trimmed.contains("避孕药") -> out.add("Hormonal birth control")
        }
    }

    private fun isTricyclicTerm(term: String): Boolean {
        val cleaned = term.replace(PAREN_REGEX, "").trim()
        val lower = cleaned.lowercase(Locale.ROOT)
        return lower == "tricyclic antidepressants" ||
            lower == "trycyclic antidepressants" ||
            lower == "tca" ||
            lower == "tcas" ||
            cleaned == "三环类抗抑郁药"
    }

    private fun buildWildcardRegex(interaction: String): Regex? {
        val matches = WILDCARD_TOKEN_REGEX.findAll(interaction).toList()
        if (matches.isEmpty()) return null
        val patternBuilder = StringBuilder()
        var lastIndex = 0
        for (match in matches) {
            if (match.range.first > lastIndex) {
                patternBuilder.append(Regex.escape(interaction.substring(lastIndex, match.range.first)))
            }
            val token = match.value
            val replacement = if (token == "x" && match.range.first >= 2 &&
                interaction.substring(0, match.range.first).all { it in 'A'..'Z' } &&
                match.range.last == interaction.lastIndex
            ) {
                // DOx -> DO + 1~2 位取代基字符（匹配 DOB/DOC/DOI/DOM/DOET，不误伤 Doxepin）
                "\\S{1,2}"
            } else {
                "\\S+"
            }
            patternBuilder.append(replacement)
            lastIndex = match.range.last + 1
        }
        if (lastIndex < interaction.length) {
            patternBuilder.append(Regex.escape(interaction.substring(lastIndex)))
        }
        return Regex(pattern = patternBuilder.toString(), option = RegexOption.IGNORE_CASE)
    }

    companion object {
        private val PAREN_REGEX = Regex("[\\(（][^\\)）]*[\\)）]")
        private val SPLIT_REGEX = Regex("[/、|]")
        private val WILDCARD_TOKEN_REGEX = Regex(
            "(?i:x{2,})|(?<=-)[xX](?=$|-)|(?<=\\d)[xX](?=$|-)|(?<=[A-Z]{2})x$"
        )

        private val CANONICAL_ALIAS_MAP: Map<String, List<String>> = mapOf(
            "alcohol" to listOf("Alcohol"),
            "酒精" to listOf("Alcohol"),
            "乙醇" to listOf("Alcohol"),
            "酒精 (乙醇)" to listOf("Alcohol"),

            "caffeine" to listOf("Caffeine"),
            "咖啡因" to listOf("Caffeine"),

            "cannabis" to listOf("Cannabis"),
            "大麻" to listOf("Cannabis"),

            "grapefruit" to listOf("Grapefruit"),
            "grapefruit juice" to listOf("Grapefruit"),
            "西柚" to listOf("Grapefruit"),
            "葡萄柚" to listOf("Grapefruit"),
            "西柚汁" to listOf("Grapefruit"),
            "葡萄柚汁" to listOf("Grapefruit"),
            "西柚 / 葡萄柚" to listOf("Grapefruit"),
            "西柚汁 (葡萄柚汁)" to listOf("Grapefruit"),

            "hormonal birth control" to listOf("Hormonal birth control"),
            "oral contraceptives" to listOf("Hormonal birth control"),
            "combined oral contraceptives" to listOf("Hormonal birth control"),
            "hormonal contraceptives" to listOf("Hormonal birth control"),
            "steroidal contraceptives" to listOf("Hormonal birth control"),
            "激素类避孕药" to listOf("Hormonal birth control"),
            "口服避孕药" to listOf("Hormonal birth control"),
            "复方口服避孕药" to listOf("Hormonal birth control"),
            "口服复方避孕药" to listOf("Hormonal birth control"),
            "大剂量联合口服避孕药" to listOf("Hormonal birth control"),

            "nicotine" to listOf("Nicotine"),
            "尼古丁" to listOf("Nicotine"),

            "lithium" to listOf("Lithium"),
            "li" to listOf("Lithium", "Li"),
            "碳酸锂" to listOf("Lithium"),
            "锂" to listOf("Lithium"),
            "锂盐" to listOf("Lithium"),

            "maoi" to listOf("MAOI", "MAOIs"),
            "maois" to listOf("MAOI", "MAOIs"),
            "单胺氧化酶抑制剂" to listOf("MAOI", "MAOIs"),

            "ssri" to listOf("SSRIs", "SSRI"),
            "ssris" to listOf("SSRIs", "SSRI"),
            "选择性5-羟色胺再摄取抑制剂" to listOf("SSRIs", "SSRI"),

            "snri" to listOf("SNRIs", "SNRI"),
            "snris" to listOf("SNRIs", "SNRI"),
            "5-羟色胺与去甲肾上腺素再摄取抑制剂" to listOf("SNRIs", "SNRI"),

            "5-hydroxytryptophan" to listOf("5-Hydroxytryptophan", "5-HTP"),
            "5-htp" to listOf("5-Hydroxytryptophan", "5-HTP"),
            "5-羟色氨酸" to listOf("5-Hydroxytryptophan", "5-HTP"),

            "tricyclic antidepressants" to listOf("Tricyclic antidepressants", "TCAs"),
            "trycyclic antidepressants" to listOf("Tricyclic antidepressants", "TCAs"),
            "tca" to listOf("Tricyclic antidepressants", "TCAs"),
            "tcas" to listOf("Tricyclic antidepressants", "TCAs"),
            "三环类抗抑郁药" to listOf("Tricyclic antidepressants", "TCAs"),

            "antibiotic" to listOf("Antibiotics"),
            "antibiotics" to listOf("Antibiotics"),
            "抗生素" to listOf("Antibiotics"),
            "碳青霉烯类抗生素" to listOf("Antibiotics"),
            "耳毒性抗生素" to listOf("Antibiotics"),

            "antihistamine" to listOf("Antihistamine", "Antihistamines"),
            "antihistamines" to listOf("Antihistamine", "Antihistamines"),
            "sedative antihistamines" to listOf("Antihistamine", "Antihistamines"),
            "抗组胺药" to listOf("Antihistamine", "Antihistamines"),
            "抗组胺药物" to listOf("Antihistamine", "Antihistamines"),
            "镇静性抗组胺药" to listOf("Antihistamine", "Antihistamines"),
        )
    }

    private val serotoninReleasers = listOf(
        "MDMA",
        "MDA",
        "Mephedrone"
    )

    private val substitutedAmphetamines = listOf(
        "Amphetamine",
        "Methamphetamine",
        "Ethylamphetamine",
        "Propylamphetamine",
        "Isopropylamphetamine",
        "Bromo-DragonFLY",
        "Lisdexamfetamine",
        "Clobenzorex",
        "Dimethylamphetamine",
        "Selegiline",
        "Benzphetamine",
        "Ortetamine",
        "3-Methylamphetamine",
        "4-Methylamphetamine",
        "4-MMA",
        "Xylopropamine",
        "ß-methylamphetamine",
        "3-phenylmethamphetamine",
        "2-FA",
        "2-FMA",
        "2-FEA",
        "3-FA",
        "3-FMA",
        "3-FEA",
        "Fenfluramine",
        "Norfenfluramine",
        "4-FA",
        "4-FMA",
        "4-CA",
        "4-BA",
        "4-IA",
        "DCA",
        "4-HA",
        "4-HMA",
        "3,4-DHA",
        "OMA",
        "3-MA",
        "MMMA",
        "MMA",
        "PMA",
        "PMMA",
        "PMEA",
        "4-ETA",
        "TMA-2",
        "TMA-6",
        "4-MTA",
        "5-API",
        "Cathine",
        "Phenmetrazine",
        "3-FPM",
        "Prolintane"
    )

}

data class Interaction(
    val aName: String,
    val bName: String,
    val interactionType: InteractionType,
)