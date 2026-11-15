/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances.classes

import com.isaakhanimann.journal.data.substances.classes.roa.DurationRange
import com.isaakhanimann.journal.data.substances.classes.roa.DurationUnits
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDuration

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
     * 当特定给药途径缺少 PsychonautWiki 主观药效时长 (roaDuration) 时，
     * 基于 Tmax 与半衰期生成日记主观时间轴兜底时长（避免体验曲线空白）。
     * 注：真实跨日衰减与活性代谢物蓄积由 ExperiencePkTimelineSection 的 Bateman 卷积负责。
     */
    fun toFallbackRoaDuration(): RoaDuration? {
        val hl = (halfLifeHours?.takeIf { it > 0.0 && it.isFinite() }
            ?: resolvedEffectiveHalfLifeHours?.takeIf { it > 0.0 && it.isFinite() })
            ?.toFloat() ?: return null
        val tMax = (tMaxHours?.takeIf { it > 0.0 && it.isFinite() } ?: 1.5).toFloat()
        val onsetMin = (tMax * 0.15f).coerceIn(0.15f, 0.8f)
        val onsetMax = (tMax * 0.35f).coerceIn(0.3f, 1.5f)
        val comeupMin = (tMax * 0.45f).coerceIn(0.3f, 2.0f)
        val comeupMax = (tMax * 0.9f).coerceIn(0.6f, 4.0f)
        val peakMin = (hl * 0.25f).coerceIn(1.5f, 8.0f)
        val peakMax = (hl * 0.55f).coerceIn(3.0f, 16.0f)
        val offsetMin = (hl * 0.5f).coerceIn(2.0f, 10.0f)
        val offsetMax = (hl * 1.2f).coerceIn(4.0f, 20.0f)
        val totalMin = (onsetMin + comeupMin + peakMin + offsetMin).coerceIn(3.0f, 24.0f)
        val totalMax = (onsetMax + comeupMax + peakMax + offsetMax).coerceIn(totalMin + 1.0f, 36.0f)
        return RoaDuration(
            onset = DurationRange(min = onsetMin, max = onsetMax, units = DurationUnits.HOURS),
            comeup = DurationRange(min = comeupMin, max = comeupMax, units = DurationUnits.HOURS),
            peak = DurationRange(min = peakMin, max = peakMax, units = DurationUnits.HOURS),
            offset = DurationRange(min = offsetMin, max = offsetMax, units = DurationUnits.HOURS),
            total = DurationRange(min = totalMin, max = totalMax, units = DurationUnits.HOURS),
            afterglow = null
        )
    }
}
