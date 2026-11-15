/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances.classes

import com.isaakhanimann.journal.data.substances.AdministrationRoute
import com.isaakhanimann.journal.data.substances.classes.roa.DurationRange
import com.isaakhanimann.journal.data.substances.classes.roa.DurationUnits
import com.isaakhanimann.journal.data.substances.classes.roa.Roa
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDuration
import java.util.Locale

data class ActiveMetabolite(
    val name: String,
    val pathway: String? = null,
    val halfLife: String? = null,
    val halfLifeHours: Double? = null,
    val tMaxHours: Double? = null,
    val exposureRatio: Double? = null,
    val relativeExposure: String? = null,
    val pharmacologicalRole: String? = null,
)

data class ReceptorTarget(
    val target: String,
    val action: String,
    val affinityTier: String? = null,
    val affinityWeight: Float = 0.7f,
    val dominantActor: String? = null,
)

data class Pharmacokinetics(
    val halfLife: String? = null,
    val halfLifeHours: Double? = null,
    val tMax: String? = null,
    val tMaxHours: Double? = null,
    val bioavailability: String? = null,
    val cypEnzymes: List<String> = emptyList(),
    val clearance: String? = null,
    val brandName: String? = null,
    val indications: List<String> = emptyList(),
    val contraindications: List<String> = emptyList(),
    val pkRole: String? = null,
    val effectiveHalfLifeHours: Double? = null,
    val washoutHours: Double? = null,
    val washoutNote: String? = null,
    val mechanismSummary: String? = null,
    val activeMetabolites: List<ActiveMetabolite> = emptyList(),
    val receptorTargets: List<ReceptorTarget> = emptyList(),
) {
    /**
     * 总活性组分 (Total Active Moiety) 等效终末半衰期：
     * 优先取显式标定的 effectiveHalfLifeHours，否则回退至母体与全部活性代谢物中的最长半衰期。
     */
    val resolvedEffectiveHalfLifeHours: Double?
        get() = effectiveHalfLifeHours?.takeIf { it > 0.0 }
            ?: listOfNotNull(
                halfLifeHours?.takeIf { it > 0.0 },
                activeMetabolites.mapNotNull { it.halfLifeHours?.takeIf { h -> h > 0.0 } }.maxOrNull()
            ).maxOrNull()

    /**
     * 临床安全洗脱期 / 生物效应恢复期（小时）：
     * 优先取显式洗脱期（覆盖不可逆抑制与超长代谢物），否则按 5× 总活性半衰期推算。
     */
    val resolvedWashoutHours: Double?
        get() = washoutHours?.takeIf { it > 0.0 }
            ?: resolvedEffectiveHalfLifeHours?.let { it * 5.0 }

    /**
     * 结合给药途径与途径主观起效/爬坡时间 (onset + comeup)，解析该途径的达峰时间 Tmax（小时）。
     */
    fun resolveTMaxHoursForRoute(
        route: AdministrationRoute?,
        roa: Roa? = null,
    ): Double {
        val baseTMax = tMaxHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
                ?.let { (it * 0.2).coerceIn(0.25, 6.0) }
            ?: 1.5

        val isExtendedReleaseOral = pkRole == "EXTENDED_RELEASE" &&
            (route == null || route == AdministrationRoute.ORAL)
        if (isExtendedReleaseOral) {
            return maxOf(baseTMax, 3.5)
        }

        val durationRefHours = run {
            val dur = roa?.roaDuration
            if (dur != null && (dur.onset != null || dur.comeup != null)) {
                val onsetSec = dur.onset?.interpolateAtValueInSeconds(0.5f)?.toDouble() ?: 0.0
                val comeupSec = dur.comeup?.interpolateAtValueInSeconds(0.5f)?.toDouble() ?: 0.0
                val sumHours = (onsetSec + comeupSec) / 3600.0
                sumHours.takeIf { it > 0.0 && it.isFinite() }
            } else {
                null
            }
        }

        if (pkRole == "DEPOT_FLIP_FLOP") {
            return when (route) {
                AdministrationRoute.INTRAMUSCULAR,
                AdministrationRoute.SUBCUTANEOUS -> durationRefHours ?: baseTMax
                AdministrationRoute.INTRAVENOUS -> durationRefHours?.coerceIn(0.03, 0.15) ?: 0.05
                AdministrationRoute.ORAL -> durationRefHours ?: minOf(baseTMax, 2.0)
                else -> durationRefHours ?: resolveNonDepotRouteTMax(route, baseTMax)
            }
        }

        return when (route) {
            null -> baseTMax
            AdministrationRoute.ORAL ->
                tMaxHours?.takeIf { it > 0.0 && it.isFinite() } ?: durationRefHours ?: baseTMax
            AdministrationRoute.INTRAVENOUS ->
                durationRefHours?.coerceIn(0.02, 0.15) ?: 0.05
            AdministrationRoute.SMOKED,
            AdministrationRoute.INHALED ->
                durationRefHours?.coerceIn(0.04, 0.5) ?: 0.1
            AdministrationRoute.INSUFFLATED ->
                durationRefHours?.coerceIn(0.1, 1.0) ?: (baseTMax * 0.35).coerceIn(0.25, 0.5)
            AdministrationRoute.SUBLINGUAL,
            AdministrationRoute.BUCCAL ->
                durationRefHours?.coerceIn(0.15, 1.2) ?: (baseTMax * 0.45).coerceIn(0.25, 0.5)
            AdministrationRoute.INTRAMUSCULAR ->
                durationRefHours?.coerceIn(0.25, 2.5) ?: (baseTMax * 0.55).coerceIn(0.5, 1.0)
            AdministrationRoute.SUBCUTANEOUS ->
                durationRefHours?.coerceIn(0.3, 4.0) ?: (baseTMax * 0.7).coerceIn(0.5, 1.5)
            AdministrationRoute.RECTAL ->
                durationRefHours?.coerceIn(0.2, 2.5) ?: (baseTMax * 0.6).coerceIn(0.25, 2.0)
            AdministrationRoute.TRANSDERMAL ->
                durationRefHours?.coerceIn(2.0, 24.0) ?: (baseTMax * 4.0).coerceIn(6.0, 24.0)
        }
    }

    private fun resolveNonDepotRouteTMax(route: AdministrationRoute?, baseTMax: Double): Double {
        return when (route) {
            null, AdministrationRoute.ORAL -> baseTMax
            AdministrationRoute.INTRAVENOUS -> 0.05
            AdministrationRoute.SMOKED, AdministrationRoute.INHALED -> 0.1
            AdministrationRoute.INSUFFLATED -> (baseTMax * 0.35).coerceIn(0.25, 0.5)
            AdministrationRoute.SUBLINGUAL, AdministrationRoute.BUCCAL -> (baseTMax * 0.45).coerceIn(0.25, 0.5)
            AdministrationRoute.INTRAMUSCULAR -> (baseTMax * 0.55).coerceIn(0.5, 1.0)
            AdministrationRoute.SUBCUTANEOUS -> (baseTMax * 0.7).coerceIn(0.5, 1.5)
            AdministrationRoute.RECTAL -> (baseTMax * 0.6).coerceIn(0.25, 2.0)
            AdministrationRoute.TRANSDERMAL -> (baseTMax * 4.0).coerceIn(6.0, 24.0)
        }
    }

    /**
     * 解析指定给药途径的有效系统生物利用度分数 F ∈ [0.02, 1.0]。
     */
    fun resolveBioavailabilityFraction(
        route: AdministrationRoute?,
        roa: Roa? = null,
        customFraction: Double? = null,
    ): Double {
        customFraction?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }?.let { return it }

        roa?.bioavailability?.let { bio ->
            val minVal = bio.min?.takeIf { it.isFinite() && it >= 0.0 }
            val maxVal = bio.max?.takeIf { it.isFinite() && it > 0.0 }
            val avg = when {
                minVal != null && maxVal != null -> (minVal + maxVal) / 2.0
                maxVal != null -> maxVal
                minVal != null && minVal > 0.0 -> minVal
                else -> null
            }
            if (avg != null) {
                return (avg / 100.0).coerceIn(0.02, 1.0)
            }
        }

        if (route == AdministrationRoute.INTRAVENOUS) {
            return 1.0
        }

        parseTopLevelBioavailability(bioavailability, route)?.let { return it }

        return when (route) {
            AdministrationRoute.INTRAVENOUS -> 1.0
            AdministrationRoute.INTRAMUSCULAR,
            AdministrationRoute.SUBCUTANEOUS -> 0.9
            AdministrationRoute.INHALED,
            AdministrationRoute.SMOKED -> 0.7
            AdministrationRoute.INSUFFLATED,
            AdministrationRoute.RECTAL,
            AdministrationRoute.SUBLINGUAL,
            AdministrationRoute.BUCCAL -> 0.65
            AdministrationRoute.ORAL,
            AdministrationRoute.TRANSDERMAL,
            null -> 0.55
        }
    }

    /**
     * 当特定给药途径缺少 PsychonautWiki 主观药效时长 (roaDuration) 时，
     * 基于途径缩放后的 Tmax 与半衰期生成日记主观时间轴兜底时长（避免体验曲线空白）。
     * 注：真实跨日衰减与活性代谢物蓄积由 ExperiencePkTimelineSection 的 Bateman 卷积负责。
     */
    fun toFallbackRoaDuration(route: AdministrationRoute? = null): RoaDuration? {
        val hl = (halfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() })
            ?.toFloat() ?: return null
        val tMax = resolveTMaxHoursForRoute(route).toFloat()
        val fastRoute = route != null && tMax < 0.75f
        val onsetMin = (tMax * 0.15f).coerceIn(if (fastRoute) 0.01f else 0.15f, 0.8f)
        val onsetMax = (tMax * 0.35f).coerceIn(if (fastRoute) 0.03f else 0.3f, 1.5f)
        val comeupMin = (tMax * 0.45f).coerceIn(if (fastRoute) 0.03f else 0.3f, 2.0f)
        val comeupMax = (tMax * 0.9f).coerceIn(if (fastRoute) 0.08f else 0.6f, 4.0f)
        val peakMin = (hl * 0.25f).coerceIn(if (fastRoute) 0.5f else 1.5f, 8.0f)
        val peakMax = (hl * 0.55f).coerceIn(if (fastRoute) 1.0f else 3.0f, 16.0f)
        val offsetMin = (hl * 0.5f).coerceIn(if (fastRoute) 0.75f else 2.0f, 10.0f)
        val offsetMax = (hl * 1.2f).coerceIn(if (fastRoute) 1.5f else 4.0f, 20.0f)
        val totalMin = (onsetMin + comeupMin + peakMin + offsetMin).coerceIn(if (fastRoute) 1.2f else 3.0f, 24.0f)
        val totalMax = (onsetMax + comeupMax + peakMax + offsetMax).coerceIn(totalMin + 0.5f, 36.0f)
        return RoaDuration(
            onset = DurationRange(min = onsetMin, max = onsetMax, units = DurationUnits.HOURS),
            comeup = DurationRange(min = comeupMin, max = comeupMax, units = DurationUnits.HOURS),
            peak = DurationRange(min = peakMin, max = peakMax, units = DurationUnits.HOURS),
            offset = DurationRange(min = offsetMin, max = offsetMax, units = DurationUnits.HOURS),
            total = DurationRange(min = totalMin, max = totalMax, units = DurationUnits.HOURS),
            afterglow = null
        )
    }

    private companion object {
        private val BIO_RANGE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%?\s*[-–~]\s*(\d+(?:\.\d+)?)\s*%""")
        private val BIO_SINGLE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*%""")

        private val ROUTE_KEYWORDS: Map<AdministrationRoute, List<String>> = mapOf(
            AdministrationRoute.ORAL to listOf("口服", "oral"),
            AdministrationRoute.SUBLINGUAL to listOf("舌下", "sublingual"),
            AdministrationRoute.BUCCAL to listOf("颊", "口腔黏膜", "口腔粘膜", "buccal"),
            AdministrationRoute.INSUFFLATED to listOf("鼻吸", "鼻腔", "鼻用", "snort", "insufflat"),
            AdministrationRoute.RECTAL to listOf("直肠", "塞肛", "肛门", "rectal"),
            AdministrationRoute.TRANSDERMAL to listOf("透皮", "经皮", "贴剂", "transdermal"),
            AdministrationRoute.SUBCUTANEOUS to listOf("皮下", "subcutaneous"),
            AdministrationRoute.INTRAMUSCULAR to listOf("肌注", "肌内", "intramuscular"),
            AdministrationRoute.INTRAVENOUS to listOf("静脉", "intravenous"),
            AdministrationRoute.SMOKED to listOf("抽吸", "烟吸", "吸食", "雾化", "smok", "vapor"),
            AdministrationRoute.INHALED to listOf("吸入", "inhal"),
        )

        private fun parseTopLevelBioavailability(raw: String?, route: AdministrationRoute?): Double? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val lower = text.lowercase(Locale.ROOT)

            if (route != null) {
                val keywords = ROUTE_KEYWORDS[route].orEmpty()
                val clauses = text.split(';', '；')
                for (clause in clauses) {
                    val lowerClause = clause.lowercase(Locale.ROOT)
                    val idx = keywords.mapNotNull { kw ->
                        lowerClause.indexOf(kw).takeIf { it >= 0 }
                    }.minOrNull()
                    if (idx != null) {
                        val parsed = parseFirstPercentOrRange(clause.substring(idx))
                        if (parsed != null) return parsed
                    }
                }

                // 若原文只注明了其他具体途径（如仅写“口服 36%”），不将其套用到无关途径（如肌注/鼻吸）
                val mentionsAnyOtherRoute = ROUTE_KEYWORDS.entries.any { (otherRoute, kws) ->
                    otherRoute != route && kws.any { kw -> lower.contains(kw) }
                }
                if (mentionsAnyOtherRoute) {
                    return null
                }
            }

            return parseFirstPercentOrRange(text)
        }

        private fun parseFirstPercentOrRange(text: String): Double? {
            val rangeMatch = BIO_RANGE_REGEX.find(text)
            val singleMatch = BIO_SINGLE_REGEX.find(text)

            val useRange = when {
                rangeMatch != null && singleMatch != null -> rangeMatch.range.first <= singleMatch.range.first
                rangeMatch != null -> true
                else -> false
            }

            val percent = if (useRange && rangeMatch != null) {
                val minVal = rangeMatch.groupValues[1].toDoubleOrNull()
                val maxVal = rangeMatch.groupValues[2].toDoubleOrNull()
                if (minVal != null && maxVal != null) (minVal + maxVal) / 2.0 else null
            } else {
                singleMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            } ?: return null

            if (!percent.isFinite() || percent <= 0.0) return null
            return (percent / 100.0).coerceIn(0.02, 1.0)
        }
    }
}
