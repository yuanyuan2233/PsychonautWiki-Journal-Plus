/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances.classes

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
}
