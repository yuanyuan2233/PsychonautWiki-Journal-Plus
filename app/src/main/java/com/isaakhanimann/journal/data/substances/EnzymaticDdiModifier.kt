/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances

import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import com.isaakhanimann.journal.data.substances.classes.Substance
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

enum class CypInteractionEffectType {
    INHIBITION,
    INDUCTION,
}

data class EnzymaticDdiFinding(
    val perpetratorName: String,
    val victimName: String,
    val enzyme: String,
    val effectType: CypInteractionEffectType,
    val halfLifeMultiplier: Double,
    val activeMetaboliteMultiplier: Double,
    val isProdrugActivationBlocked: Boolean = false,
    val summaryZh: String,
    val summaryEn: String,
)

object EnzymaticDdiModifier {

    private enum class Strength(
        val rank: Int,
        val inhibitionHalfLifeMult: Double,
        val inductionHalfLifeMult: Double,
        val blockedProdrugMetMult: Double,
    ) {
        MODERATE(
            rank = 1,
            inhibitionHalfLifeMult = 1.6,
            inductionHalfLifeMult = 0.65,
            blockedProdrugMetMult = 0.5,
        ),
        STRONG(
            rank = 2,
            inhibitionHalfLifeMult = 2.5,
            inductionHalfLifeMult = 0.5,
            blockedProdrugMetMult = 0.3,
        ),
    }

    private data class PerpetratorRule(
        val enzyme: String,
        val effectType: CypInteractionEffectType,
        val strength: Strength,
    )

    private data class ParsedCypProfile(
        val perpetrators: Map<String, PerpetratorRule>,
        val substrateEnzymes: Set<String>,
    )

    private val SUPPORTED_CYP_REGEX = Regex("""CYP(1A2|2B6|2C9|2C19|2D6|3A4)""", RegexOption.IGNORE_CASE)
    private val CYP_2C9_2C19_SHORTHAND_REGEX = Regex("""CYP2C9/2C?19""", RegexOption.IGNORE_CASE)

    private val CH_EN_ALIASES: Map<String, String> = mapOf(
        "氟伏沙明" to "fluvoxamine",
        "兰释" to "fluvoxamine",
        "luvox" to "fluvoxamine",
        "氟西汀" to "fluoxetine",
        "百优解" to "fluoxetine",
        "prozac" to "fluoxetine",
        "帕罗西汀" to "paroxetine",
        "赛乐特" to "paroxetine",
        "paxil" to "paroxetine",
        "安非他酮" to "bupropion",
        "wellbutrin" to "bupropion",
        "度洛西汀" to "duloxetine",
        "欣百达" to "duloxetine",
        "cymbalta" to "duloxetine",
        "舍曲林" to "sertraline",
        "左洛复" to "sertraline",
        "zoloft" to "sertraline",
        "西酞普兰" to "citalopram",
        "喜普妙" to "citalopram",
        "celexa" to "citalopram",
        "艾司西酞普兰" to "escitalopram",
        "来士普" to "escitalopram",
        "lexapro" to "escitalopram",
        "卡马西平" to "carbamazepine",
        "得理多" to "carbamazepine",
        "tegretol" to "carbamazepine",
        "苯巴比妥" to "phenobarbital",
        "鲁米那" to "phenobarbital",
        "苯妥英" to "phenytoin",
        "苯妥英钠" to "phenytoin",
        "大仑丁" to "phenytoin",
        "利福平" to "rifampin",
        "rifampicin" to "rifampin",
        "圣约翰草" to "st. john's wort",
        "贯叶连翘" to "st. john's wort",
        "hypericum perforatum" to "st. john's wort",
        "葡萄柚" to "grapefruit",
        "葡萄柚汁" to "grapefruit",
        "西柚" to "grapefruit",
        "西柚汁" to "grapefruit",
        "grapefruit juice" to "grapefruit",
        "可待因" to "codeine",
        "磷酸可待因" to "codeine",
        "曲马多" to "tramadol",
        "右美沙芬" to "dextromethorphan",
        "dxm" to "dextromethorphan",
        "咖啡因" to "caffeine",
        "阿普唑仑" to "alprazolam",
        "佳静安定" to "alprazolam",
        "xanax" to "alprazolam",
        "地西泮" to "diazepam",
        "安定" to "diazepam",
        "咪达唑仑" to "midazolam",
        "美沙酮" to "methadone",
        "氯氮平" to "clozapine",
        "奥氮平" to "olanzapine",
        "文拉法辛" to "venlafaxine",
        "利培酮" to "risperidone",
        "阿立哌唑" to "aripiprazole",
        "莫达非尼" to "modafinil",
        "阿莫达非尼" to "armodafinil",
        "酮康唑" to "ketoconazole",
        "伊曲康唑" to "itraconazole",
        "伏立康唑" to "voriconazole",
        "克拉霉素" to "clarithromycin",
        "利托那韦" to "ritonavir",
        "环丙沙星" to "ciprofloxacin",
        "大麻二酚" to "cannabidiol",
        "cbd" to "cannabidiol",
        "奎尼丁" to "quinidine",
        "奥美拉唑" to "omeprazole",
        "氟康唑" to "fluconazole",
        "地尔硫卓" to "diltiazem",
        "维拉帕米" to "verapamil",
        "红霉素" to "erythromycin",
        "西咪替丁" to "cimetidine",
        "氯吡格雷" to "clopidogrel",
        "苯海拉明" to "diphenhydramine",
        "茶苯海明" to "dimenhydrinate",
        "特比萘芬" to "terbinafine",
    )

    private val BUILTIN_PERPETRATORS: Map<String, List<PerpetratorRule>> = mapOf(
        // 强效抑制剂
        "fluvoxamine" to listOf(
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INHIBITION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "fluoxetine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "paroxetine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "bupropion" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "quinidine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "terbinafine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "ritonavir" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "cobicistat" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "ketoconazole" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "itraconazole" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "voriconazole" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "posaconazole" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "clarithromycin" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "nefazodone" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "ciprofloxacin" to listOf(
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INHIBITION, Strength.STRONG),
        ),
        "fluconazole" to listOf(
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        // 中效抑制剂
        "duloxetine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "sertraline" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "diphenhydramine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "dimenhydrinate" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "grapefruit" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "erythromycin" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "diltiazem" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "verapamil" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "cimetidine" to listOf(
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "omeprazole" to listOf(
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        "modafinil" to listOf(
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.MODERATE),
        ),
        "armodafinil" to listOf(
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.MODERATE),
        ),
        "cannabidiol" to listOf(
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
            PerpetratorRule("CYP2D6", CypInteractionEffectType.INHIBITION, Strength.MODERATE),
        ),
        // 强效诱导剂
        "carbamazepine" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2B6", CypInteractionEffectType.INDUCTION, Strength.STRONG),
        ),
        "phenobarbital" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2B6", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INDUCTION, Strength.MODERATE),
        ),
        "phenytoin" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INDUCTION, Strength.STRONG),
        ),
        "rifampin" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP1A2", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2B6", CypInteractionEffectType.INDUCTION, Strength.STRONG),
        ),
        "st. john's wort" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C19", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2C9", CypInteractionEffectType.INDUCTION, Strength.MODERATE),
        ),
        "efavirenz" to listOf(
            PerpetratorRule("CYP3A4", CypInteractionEffectType.INDUCTION, Strength.STRONG),
            PerpetratorRule("CYP2B6", CypInteractionEffectType.INDUCTION, Strength.STRONG),
        ),
    )

    private val BUILTIN_SUBSTRATE_CYPS: Map<String, Set<String>> = mapOf(
        "caffeine" to setOf("CYP1A2"),
        "codeine" to setOf("CYP2D6"),
        "tramadol" to setOf("CYP2D6"),
        "dextromethorphan" to setOf("CYP2D6"),
        "alprazolam" to setOf("CYP3A4"),
        "midazolam" to setOf("CYP3A4"),
        "diazepam" to setOf("CYP2C19", "CYP3A4"),
        "clozapine" to setOf("CYP1A2", "CYP3A4"),
        "olanzapine" to setOf("CYP1A2"),
        "melatonin" to setOf("CYP1A2"),
        "tizanidine" to setOf("CYP1A2"),
        "ramelteon" to setOf("CYP1A2"),
        "agomelatine" to setOf("CYP1A2", "CYP2C9"),
        "oxycodone" to setOf("CYP3A4", "CYP2D6"),
        "hydrocodone" to setOf("CYP2D6", "CYP3A4"),
        "methadone" to setOf("CYP2B6", "CYP3A4"),
        "zolpidem" to setOf("CYP3A4", "CYP1A2"),
        "clopidogrel" to setOf("CYP2C19"),
        "propranolol" to setOf("CYP2D6", "CYP1A2"),
        "metoprolol" to setOf("CYP2D6"),
    )

    private val CYP_ACTIVATED_SUBSTRATES: Map<String, Set<String>> = mapOf(
        "codeine" to setOf("CYP2D6"),
        "tramadol" to setOf("CYP2D6"),
        "dextromethorphan" to setOf("CYP2D6"),
        "clopidogrel" to setOf("CYP2C19"),
    )

    fun analyzeCoAdministeredSubstances(substances: List<Substance>): List<EnzymaticDdiFinding> {
        if (substances.size < 2) return emptyList()

        val deduped = linkedMapOf<String, Substance>()
        for (substance in substances) {
            val key = canonicalKey(substance)
            deduped.putIfAbsent(key, substance)
        }
        val uniqueSubstances = deduped.values.toList()
        if (uniqueSubstances.size < 2) return emptyList()

        val parsedProfiles = uniqueSubstances.associateBy({ canonicalKey(it) }, { parseSubstanceCypProfile(it) })
        val findings = mutableListOf<EnzymaticDdiFinding>()

        for (perpetrator in uniqueSubstances) {
            val perpKey = canonicalKey(perpetrator)
            val perpProfile = parsedProfiles[perpKey] ?: continue
            if (perpProfile.perpetrators.isEmpty()) continue

            for (victim in uniqueSubstances) {
                val victimKey = canonicalKey(victim)
                if (perpKey == victimKey || perpetrator.name.equals(victim.name, ignoreCase = true)) {
                    continue
                }
                val victimProfile = parsedProfiles[victimKey] ?: continue
                val victimPk = victim.pharmacokinetics
                if (victimProfile.substrateEnzymes.isEmpty()) continue

                for ((enzyme, rule) in perpProfile.perpetrators) {
                    if (!victimProfile.substrateEnzymes.contains(enzyme)) continue

                    val activatesMetabolite = hasEnzymeActivatedMetabolite(victimKey, victimPk, enzyme)
                    val isProdrugOrCypActivated = victimPk?.pkRole == "PRODRUG" || activatesMetabolite

                    val halfLifeMultiplier: Double
                    val activeMetaboliteMultiplier: Double
                    val isBlocked: Boolean

                    when (rule.effectType) {
                        CypInteractionEffectType.INHIBITION -> {
                            halfLifeMultiplier = rule.strength.inhibitionHalfLifeMult
                            if (isProdrugOrCypActivated) {
                                activeMetaboliteMultiplier = rule.strength.blockedProdrugMetMult
                                isBlocked = true
                            } else {
                                activeMetaboliteMultiplier = 1.0
                                isBlocked = false
                            }
                        }
                        CypInteractionEffectType.INDUCTION -> {
                            halfLifeMultiplier = rule.strength.inductionHalfLifeMult
                            activeMetaboliteMultiplier = if (isProdrugOrCypActivated && rule.strength == Strength.STRONG) {
                                1.3
                            } else {
                                1.0
                            }
                            isBlocked = false
                        }
                    }

                    val (summaryZh, summaryEn) = buildSummaries(
                        perpetratorName = perpetrator.name,
                        perpetratorKey = perpKey,
                        victimName = victim.name,
                        enzyme = enzyme,
                        rule = rule,
                        halfLifeMultiplier = halfLifeMultiplier,
                        activeMetaboliteMultiplier = activeMetaboliteMultiplier,
                        isProdrugActivationBlocked = isBlocked,
                    )

                    findings.add(
                        EnzymaticDdiFinding(
                            perpetratorName = perpetrator.name,
                            victimName = victim.name,
                            enzyme = enzyme,
                            effectType = rule.effectType,
                            halfLifeMultiplier = halfLifeMultiplier,
                            activeMetaboliteMultiplier = activeMetaboliteMultiplier,
                            isProdrugActivationBlocked = isBlocked,
                            summaryZh = summaryZh,
                            summaryEn = summaryEn,
                        )
                    )
                }
            }
        }

        return findings
    }

    fun applyModifiers(
        victimName: String,
        basePk: Pharmacokinetics,
        findings: List<EnzymaticDdiFinding>,
    ): Pharmacokinetics {
        val victimFindings = findings.filter { it.victimName.equals(victimName, ignoreCase = true) }
        if (victimFindings.isEmpty()) return basePk

        val dominantPerEnzyme = victimFindings
            .groupBy { it.enzyme.uppercase(Locale.ROOT) }
            .mapValues { (_, list) ->
                list.maxByOrNull { abs(ln(it.halfLifeMultiplier.coerceIn(0.1, 10.0))) } ?: list.first()
            }

        var combinedHalfLifeMult = 1.0
        for (finding in dominantPerEnzyme.values) {
            combinedHalfLifeMult *= finding.halfLifeMultiplier.coerceIn(0.25, 6.0)
        }
        combinedHalfLifeMult = combinedHalfLifeMult.coerceIn(0.25, 6.0)

        val newHalfLife = basePk.halfLifeHours
            ?.takeIf { it > 0.0 && it.isFinite() }
            ?.let { (it * combinedHalfLifeMult).coerceIn(0.1, 2000.0) }

        val newEffectiveHalfLife = basePk.effectiveHalfLifeHours
            ?.takeIf { it > 0.0 && it.isFinite() }
            ?.let { (it * combinedHalfLifeMult).coerceIn(0.1, 2000.0) }

        val newWashoutHours = basePk.washoutHours
            ?.takeIf { it > 0.0 && it.isFinite() }
            ?.let { baseW ->
                if (basePk.pkRole == "IRREVERSIBLE_PD") {
                    maxOf(baseW, baseW * combinedHalfLifeMult).coerceIn(0.1, 3000.0)
                } else {
                    (baseW * combinedHalfLifeMult).coerceIn(0.1, 3000.0)
                }
            }

        val metaboliteModifiers = dominantPerEnzyme.values.filter {
            it.isProdrugActivationBlocked || abs(it.activeMetaboliteMultiplier - 1.0) > 1e-6
        }

        val adjustedMetabolites = if (metaboliteModifiers.isEmpty() || basePk.activeMetabolites.isEmpty()) {
            basePk.activeMetabolites
        } else {
            basePk.activeMetabolites.map { met ->
                var metMult = 1.0
                for (mod in metaboliteModifiers) {
                    val directMatch = isFormedByEnzyme(met.pathway, mod.enzyme)
                    val prodrugFallback = mod.isProdrugActivationBlocked &&
                        basePk.pkRole == "PRODRUG" &&
                        basePk.activeMetabolites.none { isFormedByEnzyme(it.pathway, mod.enzyme) }
                    if (directMatch || prodrugFallback) {
                        metMult *= mod.activeMetaboliteMultiplier.coerceIn(0.05, 3.0)
                    }
                }
                if (abs(metMult - 1.0) <= 1e-6) {
                    met
                } else {
                    val baseRatio = met.exposureRatio ?: 0.5
                    met.copy(exposureRatio = (baseRatio * metMult).coerceIn(0.01, 25.0))
                }
            }
        }

        return basePk.copy(
            halfLifeHours = newHalfLife,
            effectiveHalfLifeHours = newEffectiveHalfLife,
            washoutHours = newWashoutHours,
            activeMetabolites = adjustedMetabolites,
        )
    }

    private fun parseSubstanceCypProfile(substance: Substance): ParsedCypProfile {
        val perpetrators = linkedMapOf<String, PerpetratorRule>()
        val substrates = linkedSetOf<String>()

        val rawCypEntries = substance.pharmacokinetics?.cypEnzymes.orEmpty()
        for (rawEntry in rawCypEntries) {
            parseSingleCypEntry(rawEntry, perpetrators, substrates)
        }

        val candidateNames = buildSet {
            add(canonicalKey(substance))
            add(substance.name.trim().lowercase(Locale.ROOT))
            for (common in substance.commonNames) {
                val norm = common.trim().lowercase(Locale.ROOT)
                if (norm.isNotEmpty()) {
                    add(norm)
                    CH_EN_ALIASES[norm]?.let { add(it) }
                }
            }
        }

        for (candidate in candidateNames) {
            val rules = BUILTIN_PERPETRATORS[candidate] ?: continue
            for (rule in rules) {
                mergePerpetratorRule(perpetrators, rule)
            }
        }

        if (rawCypEntries.isEmpty()) {
            for (candidate in candidateNames) {
                val fallbackSubstrates = BUILTIN_SUBSTRATE_CYPS[candidate] ?: continue
                substrates.addAll(fallbackSubstrates)
            }
        }

        return ParsedCypProfile(
            perpetrators = perpetrators,
            substrateEnzymes = substrates,
        )
    }

    private fun parseSingleCypEntry(
        rawEntry: String,
        perpetrators: MutableMap<String, PerpetratorRule>,
        substrates: MutableSet<String>,
    ) {
        val trimmed = rawEntry.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase(Locale.ROOT)
        val hasGlobalNegation = (
            lower.contains("不依赖 cyp") ||
                lower.contains("不依赖cyp") ||
                lower.contains("不依赖肝脏") ||
                lower.contains("不经 cyp") ||
                lower.contains("不经cyp") ||
                lower.contains("不经肝脏") ||
                lower.contains("基本不经 cyp") ||
                lower.contains("完全不经") ||
                lower.contains("不抑制/诱导") ||
                lower.contains("not metabolized by cyp") ||
                lower.contains("no cyp")
            ) && !lower.contains("部分经 cyp")
        if (hasGlobalNegation) return

        for (clause in splitTopLevelClauses(trimmed)) {
            val openParen = clause.indexOfFirst { it == '(' || it == '（' }
            val closeParen = clause.indexOfLast { it == ')' || it == '）' }
            if (openParen > 0 && closeParen > openParen) {
                val outside = clause.substring(0, openParen).trim()
                val inside = clause.substring(openParen + 1, closeParen).trim()
                val outsideCyps = extractCypNames(outside)
                val insideCyps = extractCypNames(inside)
                if (outsideCyps.isNotEmpty() && insideCyps.isNotEmpty() && hasPerpetratorKeyword(inside)) {
                    parseClause(outside, perpetrators, substrates)
                    parseClause(inside, perpetrators, substrates)
                    continue
                }
            }
            parseClause(clause, perpetrators, substrates)
        }
    }

    private fun parseClause(
        clause: String,
        perpetrators: MutableMap<String, PerpetratorRule>,
        substrates: MutableSet<String>,
    ) {
        val cyps = extractCypNames(clause)
        if (cyps.isEmpty()) return

        val lower = clause.lowercase(Locale.ROOT)
        if (
            lower.contains("绕开") ||
            lower.contains("bypass") ||
            lower.contains("极少") ||
            lower.contains("极微") ||
            lower.contains("<5%") ||
            lower.contains("< 5%") ||
            lower.contains("<10%") ||
            lower.contains("< 10%")
        ) {
            return
        }

        val isWeak = lower.contains("弱") ||
            lower.contains("微弱") ||
            lower.contains("轻度") ||
            lower.contains("轻微") ||
            lower.contains("weak") ||
            lower.contains("mild") ||
            lower.contains("次要") ||
            lower.contains("minor")

        val isInhibitor = lower.contains("抑制") || lower.contains("inhibit")
        val isInducer = lower.contains("诱导") || lower.contains("induc")
        val explicitlyNoInhibit = lower.contains("不抑制") || lower.contains("non-inhibitor")
        val explicitlyNoInduce = lower.contains("不诱导") || lower.contains("non-inducer")

        if (isInhibitor && !explicitlyNoInhibit) {
            if (!isWeak) {
                val strength = if (
                    lower.contains("强") ||
                    lower.contains("strong") ||
                    lower.contains("potent") ||
                    lower.contains("不可逆") ||
                    lower.contains("自杀")
                ) {
                    Strength.STRONG
                } else {
                    Strength.MODERATE
                }
                for (cyp in cyps) {
                    mergePerpetratorRule(
                        perpetrators,
                        PerpetratorRule(cyp, CypInteractionEffectType.INHIBITION, strength)
                    )
                }
            }
            if (hasExplicitSubstrateMarker(lower)) {
                substrates.addAll(cyps)
            }
            return
        }

        if (isInducer && !explicitlyNoInduce) {
            if (!isWeak) {
                val strength = if (
                    lower.contains("强") ||
                    lower.contains("strong") ||
                    lower.contains("potent")
                ) {
                    Strength.STRONG
                } else {
                    Strength.MODERATE
                }
                for (cyp in cyps) {
                    mergePerpetratorRule(
                        perpetrators,
                        PerpetratorRule(cyp, CypInteractionEffectType.INDUCTION, strength)
                    )
                }
            }
            if (hasExplicitSubstrateMarker(lower)) {
                substrates.addAll(cyps)
            }
            return
        }

        if (!isWeak) {
            substrates.addAll(cyps)
        }
    }

    private fun hasPerpetratorKeyword(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return lower.contains("抑制") || lower.contains("inhibit") || lower.contains("诱导") || lower.contains("induc")
    }

    private fun hasExplicitSubstrateMarker(lowerClause: String): Boolean {
        return lowerClause.contains("同时") ||
            lowerClause.contains("also") ||
            lowerClause.contains("底物") ||
            lowerClause.contains("substrate") ||
            lowerClause.contains("代谢酶") ||
            lowerClause.contains("主代谢") ||
            lowerClause.contains("消除") ||
            lowerClause.contains("自体") ||
            lowerClause.contains("自身")
    }

    private fun extractCypNames(text: String): Set<String> {
        val result = linkedSetOf<String>()
        for (match in SUPPORTED_CYP_REGEX.findAll(text)) {
            result.add("CYP" + match.groupValues[1].uppercase(Locale.ROOT))
        }
        if (CYP_2C9_2C19_SHORTHAND_REGEX.containsMatchIn(text)) {
            result.add("CYP2C9")
            result.add("CYP2C19")
        }
        return result
    }

    private fun splitTopLevelClauses(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        for (ch in text) {
            when (ch) {
                '(', '（' -> {
                    depth++
                    current.append(ch)
                }
                ')', '）' -> {
                    if (depth > 0) depth--
                    current.append(ch)
                }
                ';', '；', ',', '，' -> {
                    if (depth == 0) {
                        val part = current.toString().trim()
                        if (part.isNotEmpty()) out.add(part)
                        current.clear()
                    } else {
                        current.append(ch)
                    }
                }
                else -> current.append(ch)
            }
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }

    private fun mergePerpetratorRule(
        map: MutableMap<String, PerpetratorRule>,
        incoming: PerpetratorRule,
    ) {
        val existing = map[incoming.enzyme]
        if (existing == null || incoming.strength.rank > existing.strength.rank) {
            map[incoming.enzyme] = incoming
        }
    }

    private fun hasEnzymeActivatedMetabolite(
        victimKey: String,
        victimPk: Pharmacokinetics?,
        enzyme: String,
    ): Boolean {
        if (victimPk != null && victimPk.activeMetabolites.any { isFormedByEnzyme(it.pathway, enzyme) }) {
            return true
        }
        return CYP_ACTIVATED_SUBSTRATES[victimKey]?.contains(enzyme) == true &&
            victimPk?.activeMetabolites?.isNotEmpty() == true
    }

    private fun isFormedByEnzyme(pathway: String?, enzyme: String): Boolean {
        val text = pathway?.trim() ?: return false
        if (!text.contains(enzyme, ignoreCase = true)) return false

        val segments = text.split('，', ',', '；', ';')
        var foundFormation = false
        for (seg in segments) {
            if (!seg.contains(enzyme, ignoreCase = true)) continue
            val lowerSeg = seg.lowercase(Locale.ROOT)
            val isClearanceOnly = (lowerSeg.contains("清除") || lowerSeg.contains("cleared")) &&
                !lowerSeg.contains("生成") &&
                !lowerSeg.contains("活化") &&
                !lowerSeg.contains("催化") &&
                !lowerSeg.contains("formed") &&
                !lowerSeg.contains("activated")
            if (!isClearanceOnly) {
                foundFormation = true
            }
        }
        return foundFormation
    }

    private fun canonicalKey(substance: Substance): String {
        val raw = substance.name.trim().lowercase(Locale.ROOT)
        CH_EN_ALIASES[raw]?.let { return it }
        for (alias in substance.commonNames) {
            val norm = alias.trim().lowercase(Locale.ROOT)
            CH_EN_ALIASES[norm]?.let { return it }
        }
        return raw
    }

    private fun buildSummaries(
        perpetratorName: String,
        perpetratorKey: String,
        victimName: String,
        enzyme: String,
        rule: PerpetratorRule,
        halfLifeMultiplier: Double,
        activeMetaboliteMultiplier: Double,
        isProdrugActivationBlocked: Boolean,
    ): Pair<String, String> {
        val strengthZh = if (rule.strength == Strength.STRONG) "强效" else "中效"
        val strengthEn = if (rule.strength == Strength.STRONG) "strong" else "moderate"
        val hlStr = formatMult(halfLifeMultiplier)
        val metStr = formatMult(activeMetaboliteMultiplier)
        val enzymeLabelZh = if (perpetratorKey == "grapefruit" && enzyme == "CYP3A4") "肠道 CYP3A4" else enzyme
        val enzymeLabelEn = if (perpetratorKey == "grapefruit" && enzyme == "CYP3A4") "intestinal CYP3A4" else enzyme

        return when {
            rule.effectType == CypInteractionEffectType.INHIBITION && isProdrugActivationBlocked -> {
                val zh = "$perpetratorName ${strengthZh}抑制 $enzymeLabelZh：阻断 $victimName 生物活化 (活性产物 ×$metStr)，并延缓母体清除 (t½ ×$hlStr)"
                val en = "$perpetratorName ($strengthEn $enzymeLabelEn inhibitor) blocks $victimName bioactivation (active metabolite ×$metStr) and slows clearance (t½ ×$hlStr)"
                zh to en
            }
            rule.effectType == CypInteractionEffectType.INHIBITION -> {
                val zh = "$perpetratorName ${strengthZh}抑制 $enzymeLabelZh：减慢 $victimName 代谢清除，预期半衰期延长至 ${hlStr}×"
                val en = "$perpetratorName ($strengthEn $enzymeLabelEn inhibitor) slows $victimName clearance (estimated t½ ×$hlStr)"
                zh to en
            }
            else -> {
                val zh = "$perpetratorName ${strengthZh}诱导 $enzymeLabelZh：加速 $victimName 代谢清除，预期半衰期缩短至 ${hlStr}×"
                val en = "$perpetratorName ($strengthEn $enzymeLabelEn inducer) accelerates $victimName clearance (estimated t½ ×$hlStr)"
                zh to en
            }
        }
    }

    private fun formatMult(value: Double): String {
        val rounded10 = kotlin.math.round(value * 10.0) / 10.0
        return if (abs(value - rounded10) < 0.02) {
            String.format(Locale.ROOT, "%.1f", rounded10)
        } else {
            String.format(Locale.ROOT, "%.2f", value)
        }
    }
}
