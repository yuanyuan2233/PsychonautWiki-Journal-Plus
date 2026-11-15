/*
 * Copyright (c) 2026. PsychonautWiki Journal Contributors.
 */

package com.isaakhanimann.journal.data.substances

import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.data.substances.classes.ActiveMetabolite
import com.isaakhanimann.journal.data.substances.classes.Pharmacokinetics
import com.isaakhanimann.journal.data.substances.classes.Substance
import com.isaakhanimann.journal.data.substances.classes.roa.Bioavailability
import com.isaakhanimann.journal.data.substances.classes.roa.DurationRange
import com.isaakhanimann.journal.data.substances.classes.roa.DurationUnits
import com.isaakhanimann.journal.data.substances.classes.roa.Roa
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDose
import com.isaakhanimann.journal.data.substances.classes.roa.RoaDuration
import com.isaakhanimann.journal.data.substances.repositories.SubstanceRepositoryInterface
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

/**
 * 统一物质解析器：
 * 1. 将内置药典物质与个体化 [PkCustomizationStore] / 日程自定义半衰期合并；
 * 2. 将 [CustomSubstance] 提升为包含完整 PK/ROA/RoaDose/RoaDuration 的一等公民 [Substance] 对象。
 */
@Singleton
class UnifiedSubstanceResolver @Inject constructor(
    private val substanceRepo: SubstanceRepositoryInterface,
    private val pkCustomizationStore: PkCustomizationStore,
) {

    fun resolveSubstance(
        substanceName: String,
        customSubstances: Collection<CustomSubstance> = emptyList(),
        scheduleCustomHalfLifeHours: Double? = null,
    ): Substance? {
        val trimmed = substanceName.trim()
        if (trimmed.isEmpty()) return null

        val builtIn = substanceRepo.getSubstance(trimmed)
        if (builtIn != null) {
            val mergedPk = resolvePharmacokinetics(
                substance = builtIn,
                customSubstance = null,
                scheduleCustomHalfLifeHours = scheduleCustomHalfLifeHours,
            )
            val customBioFraction = resolveCustomBioavailabilityFraction(builtIn.name, trimmed)
            val updatedRoas = applyRoaOverrides(
                roas = builtIn.roas,
                mergedPk = mergedPk,
                customBioFraction = customBioFraction,
                forceFallbackDuration = false,
            )
            return builtIn.copy(
                pharmacokinetics = mergedPk,
                roas = updatedRoas,
            )
        }

        val custom = findCustomSubstance(trimmed, customSubstances) ?: return null
        val synthetic = toSyntheticSubstance(custom)
        val mergedPk = resolvePharmacokinetics(
            substance = synthetic,
            customSubstance = custom,
            scheduleCustomHalfLifeHours = scheduleCustomHalfLifeHours,
        )
        val customBioFraction = resolveCustomBioavailabilityFraction(custom.name, trimmed)
        val hasExplicitDuration = hasExplicitCustomDuration(custom)
        val updatedRoas = applyRoaOverrides(
            roas = synthetic.roas,
            mergedPk = mergedPk,
            customBioFraction = customBioFraction,
            forceFallbackDuration = !hasExplicitDuration,
        )
        return synthetic.copy(
            pharmacokinetics = mergedPk,
            roas = updatedRoas,
        )
    }

    private fun resolveCustomBioavailabilityFraction(
        primaryName: String,
        fallbackQueryName: String,
    ): Double? {
        val customization = pkCustomizationStore.getCustomization(primaryName)
            ?: pkCustomizationStore.getCustomization(fallbackQueryName)
        return customization?.customBioavailabilityFraction
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
    }

    private fun applyRoaOverrides(
        roas: List<Roa>,
        mergedPk: Pharmacokinetics?,
        customBioFraction: Double?,
        forceFallbackDuration: Boolean,
    ): List<Roa> {
        return roas.map { roa ->
            val resolvedDuration = when {
                forceFallbackDuration && mergedPk != null -> mergedPk.toFallbackRoaDuration(roa.route)
                roa.roaDuration == null && mergedPk != null -> mergedPk.toFallbackRoaDuration(roa.route)
                else -> roa.roaDuration
            }
            val resolvedBioavailability = if (customBioFraction != null) {
                val percent = customBioFraction * 100.0
                Bioavailability(min = percent, max = percent)
            } else {
                roa.bioavailability
            }
            if (resolvedDuration === roa.roaDuration && resolvedBioavailability === roa.bioavailability) {
                roa
            } else {
                roa.copy(
                    roaDuration = resolvedDuration,
                    bioavailability = resolvedBioavailability,
                )
            }
        }
    }

    fun resolvePharmacokinetics(
        substance: Substance?,
        customSubstance: CustomSubstance? = null,
        scheduleCustomHalfLifeHours: Double? = null,
    ): Pharmacokinetics? {
        val customPk = customSubstance?.toPharmacokineticsOrNull()
        val basePk = mergeCustomIntoSubstancePk(substance?.pharmacokinetics, customPk)

        val customization = substance?.name?.let { pkCustomizationStore.getCustomization(it) }
            ?: customSubstance?.name?.let { pkCustomizationStore.getCustomization(it) }
        val scheduleHl = scheduleCustomHalfLifeHours?.takeIf { it.isFinite() && it > 0.0 }

        if (basePk == null && customization == null && scheduleHl == null) {
            return null
        }

        val storeHl = customization?.customHalfLifeHours?.takeIf { it.isFinite() && it > 0.0 }
        val storeTMax = customization?.customTMaxHours?.takeIf { it.isFinite() && it > 0.0 }
        val storeWashout = customization?.customWashoutHours?.takeIf { it.isFinite() && it > 0.0 }
        val storePkRole = customization?.customPkRole?.trim()?.takeIf { it.isNotEmpty() }
        val storeBio = customization?.customBioavailabilityFraction?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }

        val seed = basePk ?: Pharmacokinetics()
        val hlOverride = scheduleHl ?: storeHl
        val finalHalfLifeHours = hlOverride ?: seed.halfLifeHours
        val finalHalfLifeText = if (hlOverride != null) {
            formatHoursString(hlOverride)
        } else {
            seed.halfLife
        }

        val finalTMaxHours = storeTMax ?: seed.tMaxHours
        val finalTMaxText = if (storeTMax != null) {
            formatHoursString(storeTMax)
        } else {
            seed.tMax
        }

        val finalPkRole = storePkRole ?: seed.pkRole
        val finalBioavailability = if (storeBio != null) {
            formatBioavailabilityPercent(storeBio)
        } else {
            seed.bioavailability
        }

        val finalEffectiveHalfLifeHours = if (hlOverride != null) {
            null
        } else {
            seed.effectiveHalfLifeHours
        }

        val finalWashoutHours = when {
            storeWashout != null -> storeWashout
            hlOverride != null && finalPkRole != "IRREVERSIBLE_PD" -> null
            else -> seed.washoutHours
        }

        val finalWashoutNote = if (storeWashout != null) null else seed.washoutNote

        return seed.copy(
            halfLife = finalHalfLifeText,
            halfLifeHours = finalHalfLifeHours,
            tMax = finalTMaxText,
            tMaxHours = finalTMaxHours,
            bioavailability = finalBioavailability,
            pkRole = finalPkRole,
            effectiveHalfLifeHours = finalEffectiveHalfLifeHours,
            washoutHours = finalWashoutHours,
            washoutNote = finalWashoutNote,
        )
    }

    fun toSyntheticSubstance(custom: CustomSubstance): Substance {
        val cleanName = custom.name.trim().ifEmpty { custom.name }
        val pk = custom.toPharmacokineticsOrNull()
        val explicitDuration = buildExplicitRoaDuration(custom)
        val roaDose = buildSyntheticRoaDose(custom)
        val customBio = custom.bioavailabilityFraction
            ?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
            ?.let { fraction ->
                val percent = fraction * 100.0
                Bioavailability(min = percent, max = percent)
            }

        val roas = AdministrationRoute.entries.map { route ->
            val duration = explicitDuration ?: pk?.toFallbackRoaDuration(route)
            Roa(
                route = route,
                roaDose = roaDose,
                roaDuration = duration,
                bioavailability = customBio,
            )
        }

        return Substance(
            name = cleanName,
            commonNames = emptyList(),
            url = "",
            isApproved = false,
            tolerance = null,
            crossTolerances = emptyList(),
            addictionPotential = null,
            toxicities = emptyList(),
            categories = emptyList(),
            summary = custom.description.trim().takeIf { it.isNotEmpty() },
            effectsSummary = null,
            dosageRemark = null,
            generalRisks = null,
            longtermRisks = null,
            saferUse = emptyList(),
            interactions = null,
            roas = roas,
            pharmacokinetics = pk,
        )
    }

    private fun findCustomSubstance(
        name: String,
        customSubstances: Collection<CustomSubstance>,
    ): CustomSubstance? {
        return customSubstances.firstOrNull { it.name.trim() == name }
            ?: customSubstances.firstOrNull { it.name.trim().equals(name, ignoreCase = true) }
    }

    private fun CustomSubstance.toPharmacokineticsOrNull(): Pharmacokinetics? {
        val hl = halfLifeHours?.takeIf { it.isFinite() && it > 0.0 }
        val tMax = tMaxHours?.takeIf { it.isFinite() && it > 0.0 }
        val washout = washoutHours?.takeIf { it.isFinite() && it > 0.0 }
        val bioFraction = bioavailabilityFraction?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 }
        val role = pkRole?.trim()?.takeIf { it.isNotEmpty() }
        val metName = activeMetaboliteName?.trim()?.takeIf { it.isNotEmpty() }
        val metHl = activeMetaboliteHalfLifeHours?.takeIf { it.isFinite() && it > 0.0 }

        val metabolites = if (metName != null && metHl != null) {
            listOf(
                ActiveMetabolite(
                    name = metName,
                    halfLife = formatHoursString(metHl),
                    halfLifeHours = metHl,
                    tMaxHours = tMax,
                    exposureRatio = if (role == "PRODRUG") 1.0 else null,
                )
            )
        } else {
            emptyList()
        }

        if (hl == null && tMax == null && washout == null && bioFraction == null && role == null && metabolites.isEmpty()) {
            return null
        }

        return Pharmacokinetics(
            halfLife = hl?.let(::formatHoursString),
            halfLifeHours = hl,
            tMax = tMax?.let(::formatHoursString),
            tMaxHours = tMax,
            bioavailability = bioFraction?.let(::formatBioavailabilityPercent),
            pkRole = role,
            washoutHours = washout,
            activeMetabolites = metabolites,
        )
    }

    private fun mergeCustomIntoSubstancePk(
        substancePk: Pharmacokinetics?,
        customPk: Pharmacokinetics?,
    ): Pharmacokinetics? {
        if (substancePk == null) return customPk
        if (customPk == null) return substancePk
        return substancePk.copy(
            halfLife = customPk.halfLife ?: substancePk.halfLife,
            halfLifeHours = customPk.halfLifeHours ?: substancePk.halfLifeHours,
            tMax = customPk.tMax ?: substancePk.tMax,
            tMaxHours = customPk.tMaxHours ?: substancePk.tMaxHours,
            bioavailability = customPk.bioavailability ?: substancePk.bioavailability,
            pkRole = customPk.pkRole ?: substancePk.pkRole,
            washoutHours = customPk.washoutHours ?: substancePk.washoutHours,
            activeMetabolites = if (customPk.activeMetabolites.isNotEmpty()) {
                customPk.activeMetabolites
            } else {
                substancePk.activeMetabolites
            },
        )
    }

    private fun buildSyntheticRoaDose(custom: CustomSubstance): RoaDose? {
        val common = custom.commonDose?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val units = custom.units.trim().ifEmpty { "mg" }
        return RoaDose(
            units = units,
            lightMin = common * 0.5,
            commonMin = common * 1.0,
            strongMin = common * 1.5,
            heavyMin = common * 2.5,
        )
    }

    private fun hasExplicitCustomDuration(custom: CustomSubstance): Boolean {
        return (custom.onsetHours?.takeIf { it.isFinite() && it > 0.0 } != null) ||
            (custom.peakHours?.takeIf { it.isFinite() && it > 0.0 } != null) ||
            (custom.totalDurationHours?.takeIf { it.isFinite() && it > 0.0 } != null)
    }

    private fun buildExplicitRoaDuration(custom: CustomSubstance): RoaDuration? {
        val onset = custom.onsetHours?.takeIf { it.isFinite() && it > 0.0 }
        val peak = custom.peakHours?.takeIf { it.isFinite() && it > 0.0 }
        val total = custom.totalDurationHours?.takeIf { it.isFinite() && it > 0.0 }
        if (onset == null && peak == null && total == null) return null

        val onsetRange = onset?.let(::hoursDurationRange)
        val peakRange = peak?.let(::hoursDurationRange)
        val totalRange = total?.let(::hoursDurationRange)
        val comeupRange = if (onset != null && peak != null) {
            hoursDurationRange((onset * 0.5).coerceIn(0.05, 1.0))
        } else {
            null
        }

        return RoaDuration(
            onset = onsetRange,
            comeup = comeupRange,
            peak = peakRange,
            offset = null,
            total = totalRange,
            afterglow = null,
        )
    }

    private fun hoursDurationRange(hours: Double): DurationRange {
        val value = hours.toFloat()
        return DurationRange(min = value, max = value, units = DurationUnits.HOURS)
    }

    private fun formatHoursString(hours: Double): String {
        return "${formatCleanNumber(hours)}h"
    }

    private fun formatBioavailabilityPercent(fraction: Double): String {
        return "${formatCleanNumber(fraction * 100.0)}%"
    }

    private fun formatCleanNumber(value: Double): String {
        val rounded = round(value * 100.0) / 100.0
        val raw = if (rounded % 1.0 == 0.0) {
            rounded.toLong().toString()
        } else {
            String.format(Locale.US, "%.2f", rounded)
                .trimEnd('0')
                .trimEnd('.')
        }
        return raw
    }
}
