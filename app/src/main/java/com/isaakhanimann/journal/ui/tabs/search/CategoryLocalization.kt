/*
 * Copyright (c) 2024. Isaak Hanimann.
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

package com.isaakhanimann.journal.ui.tabs.search

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.isaakhanimann.journal.R
import java.util.Locale

private val EXTRA_CATEGORY_ZH_NAMES = mapOf(
    "hrt" to "激素替代疗法 (HRT)",
    "estrogen" to "雌激素",
    "antiandrogen" to "抗雄激素",
    "progestin" to "孕激素",
    "androgen" to "雄激素",
    "ssri" to "SSRI 类抗抑郁药",
    "snri" to "SNRI 类抗抑郁药",
    "mood-stabilizer" to "心境稳定剂",
    "mood stabilizer" to "心境稳定剂",
    "adhd" to "ADHD 治疗药物",
    "analgesic" to "镇痛药",
    "anticonvulsant" to "抗惊厥药",
    "antidiarrheal" to "止泻药",
    "antidote" to "解毒剂",
    "antiepileptic" to "抗癫痫药",
    "antihistamine" to "抗组胺药",
    "antihypertensive" to "降压药",
    "antiparkinsonian" to "抗帕金森病药",
    "antiviral" to "抗病毒药",
    "anxiolytic" to "抗焦虑药",
    "atypical antipsychotic" to "非典型抗精神病药",
    "typical antipsychotic" to "典型抗精神病药",
    "gnrh agonist" to "GnRH 激动剂",
    "hormone" to "激素类药物",
    "maoi" to "单胺氧化酶抑制剂 (MAOI)",
    "melatonergic" to "褪黑素能药物",
    "multimodal" to "多模式抗抑郁药",
    "nassa" to "NaSSA 类抗抑郁药",
    "ndri" to "NDRI 类药物",
    "sari" to "SARI 类药物",
    "sedative" to "镇静剂",
    "tca" to "三环类抗抑郁药 (TCA)",
    "wakefulness" to "促觉醒剂",
    "z-drug" to "Z 类催眠药"
)

@StringRes
fun getCategoryStringRes(categoryName: String): Int? {
    return when (categoryName.lowercase(Locale.ROOT)) {
        "common" -> R.string.drugs_category_common
        "psychedelic" -> R.string.drugs_category_psychedelic
        "stimulant" -> R.string.drugs_category_stimulant
        "entactogen" -> R.string.drugs_category_entactogen
        "depressant" -> R.string.drugs_category_depressant
        "opioid" -> R.string.drugs_category_opioid
        "habit-forming" -> R.string.drugs_category_habit_forming
        "research-chemical" -> R.string.drugs_category_research_chemical
        "tentative" -> R.string.drugs_category_tentative
        "dissociative" -> R.string.drugs_category_dissociative
        "benzodiazepine" -> R.string.drugs_category_benzodiazepine
        "cannabinoid" -> R.string.drugs_category_cannabinoid
        "nootropic" -> R.string.drugs_category_nootropic
        "deliriant" -> R.string.drugs_category_deliriant
        "barbiturate" -> R.string.drugs_category_barbiturate
        "eugeroic" -> R.string.drugs_category_eugeroic
        "hallucinogen" -> R.string.drugs_category_hallucinogen
        "oneirogen" -> R.string.drugs_category_oneirogen
        "antipsychotic" -> R.string.drugs_category_antipsychotic
        "antidepressant" -> R.string.drugs_category_antidepressant
        "hypnotic" -> R.string.drugs_category_hypnotic
        "custom" -> R.string.drugs_category_custom
        else -> null
    }
}

@Composable
fun getCategoryDisplayName(categoryName: String): String {
    val resId = getCategoryStringRes(categoryName)
    if (resId != null) {
        return stringResource(resId)
    }
    val locales = LocalConfiguration.current.locales
    val isZh = !locales.isEmpty && locales[0].language.startsWith("zh")
    return if (isZh) {
        EXTRA_CATEGORY_ZH_NAMES[categoryName.lowercase(Locale.ROOT)] ?: categoryName
    } else {
        categoryName
    }
}
