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

package com.isaakhanimann.journal.ui.tabs.search.custom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.data.room.experiences.entities.CustomSubstance
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper
import com.isaakhanimann.journal.ui.theme.horizontalPadding
import java.math.BigDecimal

@Stable
class CustomSubstancePkFormState {
    var halfLifeText by mutableStateOf("")
    var tMaxText by mutableStateOf("")
    var washoutText by mutableStateOf("")
    var bioavailabilityPercentText by mutableStateOf("")
    var pkRole by mutableStateOf<String?>(null)
    var commonDoseText by mutableStateOf("")
    var activeMetaboliteName by mutableStateOf("")
    var activeMetaboliteHalfLifeText by mutableStateOf("")
    var onsetText by mutableStateOf("")
    var peakText by mutableStateOf("")
    var totalDurationText by mutableStateOf("")

    val isHalfLifeValid get() = isOptionalPositiveDecimalValid(halfLifeText)
    val isTMaxValid get() = isOptionalPositiveDecimalValid(tMaxText)
    val isWashoutValid get() = isOptionalPositiveDecimalValid(washoutText)
    val isBioavailabilityValid get() = isOptionalBioavailabilityPercentValid(bioavailabilityPercentText)
    val isCommonDoseValid get() = isOptionalPositiveDecimalValid(commonDoseText)
    val isActiveMetaboliteHalfLifeValid get() = isOptionalPositiveDecimalValid(activeMetaboliteHalfLifeText)
    val isOnsetValid get() = isOptionalPositiveDecimalValid(onsetText)
    val isPeakValid get() = isOptionalPositiveDecimalValid(peakText)
    val isTotalDurationValid get() = isOptionalPositiveDecimalValid(totalDurationText)

    val isValid: Boolean
        get() = isHalfLifeValid &&
            isTMaxValid &&
            isWashoutValid &&
            isBioavailabilityValid &&
            isCommonDoseValid &&
            isActiveMetaboliteHalfLifeValid &&
            isOnsetValid &&
            isPeakValid &&
            isTotalDurationValid

    val hasAnyData: Boolean
        get() = halfLifeText.isNotBlank() ||
            tMaxText.isNotBlank() ||
            washoutText.isNotBlank() ||
            bioavailabilityPercentText.isNotBlank() ||
            !pkRole.isNullOrBlank() ||
            commonDoseText.isNotBlank() ||
            activeMetaboliteName.isNotBlank() ||
            activeMetaboliteHalfLifeText.isNotBlank() ||
            onsetText.isNotBlank() ||
            peakText.isNotBlank() ||
            totalDurationText.isNotBlank()

    fun populateFrom(customSubstance: CustomSubstance) {
        halfLifeText = formatOptionalDecimal(customSubstance.halfLifeHours)
        tMaxText = formatOptionalDecimal(customSubstance.tMaxHours)
        washoutText = formatOptionalDecimal(customSubstance.washoutHours)
        bioavailabilityPercentText = formatBioavailabilityPercentFromFraction(
            customSubstance.bioavailabilityFraction
        )
        pkRole = customSubstance.pkRole?.trim()?.takeIf { it.isNotEmpty() }
        commonDoseText = formatOptionalDecimal(customSubstance.commonDose)
        activeMetaboliteName = customSubstance.activeMetaboliteName.orEmpty()
        activeMetaboliteHalfLifeText = formatOptionalDecimal(
            customSubstance.activeMetaboliteHalfLifeHours
        )
        onsetText = formatOptionalDecimal(customSubstance.onsetHours)
        peakText = formatOptionalDecimal(customSubstance.peakHours)
        totalDurationText = formatOptionalDecimal(customSubstance.totalDurationHours)
    }

    fun buildCustomSubstance(
        id: Int = 0,
        name: String,
        units: String,
        description: String,
    ): CustomSubstance {
        return CustomSubstance(
            id = id,
            name = name,
            units = units,
            description = description,
            halfLifeHours = parseOptionalPositiveDouble(halfLifeText),
            tMaxHours = parseOptionalPositiveDouble(tMaxText),
            washoutHours = parseOptionalPositiveDouble(washoutText),
            bioavailabilityFraction = parseOptionalBioavailabilityFraction(
                bioavailabilityPercentText
            ),
            pkRole = pkRole?.trim()?.takeIf { it.isNotEmpty() },
            commonDose = parseOptionalPositiveDouble(commonDoseText),
            activeMetaboliteName = activeMetaboliteName.trim().takeIf { it.isNotEmpty() },
            activeMetaboliteHalfLifeHours = parseOptionalPositiveDouble(
                activeMetaboliteHalfLifeText
            ),
            onsetHours = parseOptionalPositiveDouble(onsetText),
            peakHours = parseOptionalPositiveDouble(peakText),
            totalDurationHours = parseOptionalPositiveDouble(totalDurationText),
        )
    }
}

internal fun parseOptionalPositiveDouble(raw: String): Double? {
    val normalized = raw.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    return normalized.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

internal fun isOptionalPositiveDecimalValid(raw: String): Boolean {
    return raw.isBlank() || parseOptionalPositiveDouble(raw) != null
}

internal fun parseOptionalBioavailabilityPercent(raw: String): Double? {
    return parseOptionalPositiveDouble(raw)?.takeIf { it <= 100.0 }
}

internal fun parseOptionalBioavailabilityFraction(raw: String): Double? {
    val pct = parseOptionalBioavailabilityPercent(raw) ?: return null
    return (pct / 100.0).coerceIn(0.0, 1.0)
}

internal fun isOptionalBioavailabilityPercentValid(raw: String): Boolean {
    return raw.isBlank() || parseOptionalBioavailabilityPercent(raw) != null
}

internal fun formatOptionalDecimal(value: Double?): String {
    val positive = value?.takeIf { it.isFinite() && it > 0.0 } ?: return ""
    return BigDecimal.valueOf(positive).stripTrailingZeros().toPlainString()
}

internal fun formatBioavailabilityPercentFromFraction(fraction: Double?): String {
    val validFraction = fraction?.takeIf { it.isFinite() && it > 0.0 && it <= 1.0 } ?: return ""
    return BigDecimal.valueOf(validFraction)
        .multiply(BigDecimal(100))
        .stripTrailingZeros()
        .toPlainString()
}

private data class PkRoleChipOption(
    val roleValue: String?,
    val zhLabel: String,
    val enLabel: String,
)

private val CUSTOM_PK_ROLE_OPTIONS = listOf(
    PkRoleChipOption(roleValue = null, zhLabel = "标准", enLabel = "Standard"),
    PkRoleChipOption(roleValue = "EXTENDED_RELEASE", zhLabel = "缓释", enLabel = "Extended"),
    PkRoleChipOption(roleValue = "PRODRUG", zhLabel = "前药", enLabel = "Prodrug"),
    PkRoleChipOption(roleValue = "DEPOT_FLIP_FLOP", zhLabel = "储库", enLabel = "Depot"),
    PkRoleChipOption(roleValue = "ZERO_ORDER", zhLabel = "零级", enLabel = "Zero-Order"),
)

@Preview
@Composable
fun AddOrEditCustomSubstanceContentPreview() {
    AddOrEditCustomSubstanceContent(
        name = "Medication",
        units = "mg",
        description = "My medication has a very long description to see how the text fits into the text field, to make sure it looks good.",
        onNameChange = {},
        onUnitsChange = {},
        onDescriptionChange = {},
        padding = PaddingValues(0.dp)
    )
}

@Composable
fun AddOrEditCustomSubstanceContent(
    padding: PaddingValues,
    name: String,
    onNameChange: (String) -> Unit,
    units: String,
    onUnitsChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    pkState: CustomSubstancePkFormState = remember { CustomSubstancePkFormState() },
) {
    Column(
        modifier = Modifier
            .padding(padding)
            .padding(horizontal = horizontalPadding)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(5.dp))
        val focusManager = LocalFocusManager.current
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text(stringResource(R.string.drugs_custom_name)) },
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Words
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = units,
            onValueChange = onUnitsChange,
            label = { Text(stringResource(R.string.drugs_custom_units)) },
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedButton(onClick = { onUnitsChange("µg") }) {
                Text(text = "µg")
            }
            OutlinedButton(onClick = { onUnitsChange("mg") }) {
                Text(text = "mg")
            }
            OutlinedButton(onClick = { onUnitsChange("g") }) {
                Text(text = "g")
            }
            OutlinedButton(onClick = { onUnitsChange("mL") }) {
                Text(text = "mL")
            }
        }
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text(stringResource(R.string.drugs_custom_description)) },
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                capitalization = KeyboardCapitalization.Sentences
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        CustomSubstancePkSection(
            units = units,
            pkState = pkState,
            onDoneKeyboardAction = { focusManager.clearFocus() }
        )
        Spacer(modifier = Modifier.height(84.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomSubstancePkSection(
    units: String,
    pkState: CustomSubstancePkFormState,
    onDoneKeyboardAction: () -> Unit,
) {
    val isZh = SubstanceLocalizationHelper.isChinese()
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: pkState.hasAnyData

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { userExpanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isZh) "药代动力学与时程参数（可选）" else "Pharmacokinetics & Duration (Optional)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isZh) {
                            "配置半衰期、剂型角色、常用剂量或时程后将自动参与血药曲线与 DDI 分析"
                        } else {
                            "Used for PK concentration curves, DDI engine & dose thresholds"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) {
                        if (isZh) "收起" else "Collapse"
                    } else {
                        if (isZh) "展开" else "Expand"
                    }
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OptionalDecimalField(
                            value = pkState.halfLifeText,
                            onValueChange = { pkState.halfLifeText = it },
                            label = if (isZh) "半衰期 t½ (h)" else "Half-life t½ (h)",
                            isError = !pkState.isHalfLifeValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                        OptionalDecimalField(
                            value = pkState.tMaxText,
                            onValueChange = { pkState.tMaxText = it },
                            label = if (isZh) "达峰时间 Tmax (h)" else "Tmax (h)",
                            isError = !pkState.isTMaxValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OptionalDecimalField(
                            value = pkState.washoutText,
                            onValueChange = { pkState.washoutText = it },
                            label = if (isZh) "洗脱期 (h)" else "Washout (h)",
                            isError = !pkState.isWashoutValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                        OptionalDecimalField(
                            value = pkState.bioavailabilityPercentText,
                            onValueChange = { pkState.bioavailabilityPercentText = it },
                            label = if (isZh) "生物利用度 (%)" else "Bioavailability (%)",
                            isError = !pkState.isBioavailabilityValid,
                            errorMessage = if (isZh) "请输入 0~100 之间的数值" else "Enter 0 < value <= 100",
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text(
                        text = if (isZh) "剂型动力学角色" else "Formulation / PK Role",
                        style = MaterialTheme.typography.labelLarge
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        CUSTOM_PK_ROLE_OPTIONS.forEach { option ->
                            val selected = if (option.roleValue == null) {
                                pkState.pkRole == null || pkState.pkRole == "PARENT_DOMINANT"
                            } else {
                                pkState.pkRole == option.roleValue
                            }
                            FilterChip(
                                selected = selected,
                                onClick = { pkState.pkRole = option.roleValue },
                                label = { Text(if (isZh) option.zhLabel else option.enLabel) }
                            )
                        }
                    }

                    val trimmedUnits = units.trim()
                    val doseLabel = if (trimmedUnits.isNotEmpty()) {
                        if (isZh) "常用单次剂量 ($trimmedUnits)" else "Common Dose ($trimmedUnits)"
                    } else {
                        if (isZh) "常用单次剂量" else "Common Dose"
                    }
                    OptionalDecimalField(
                        value = pkState.commonDoseText,
                        onValueChange = { pkState.commonDoseText = it },
                        label = doseLabel,
                        isError = !pkState.isCommonDoseValid,
                        errorMessage = positiveNumberErrorText(isZh),
                        onDone = onDoneKeyboardAction,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
                    Text(
                        text = if (isZh) "活性代谢物（可选）" else "Active Metabolite (Optional)",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = pkState.activeMetaboliteName,
                            onValueChange = { pkState.activeMetaboliteName = it },
                            label = {
                                Text(if (isZh) "代谢物名称" else "Metabolite Name")
                            },
                            keyboardActions = KeyboardActions(onDone = { onDoneKeyboardAction() }),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                capitalization = KeyboardCapitalization.Words
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OptionalDecimalField(
                            value = pkState.activeMetaboliteHalfLifeText,
                            onValueChange = { pkState.activeMetaboliteHalfLifeText = it },
                            label = if (isZh) "代谢物半衰期 (h)" else "Metabolite t½ (h)",
                            isError = !pkState.isActiveMetaboliteHalfLifeValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(top = 2.dp))
                    Text(
                        text = if (isZh) {
                            "主观时程参考（可选，留空时可由半衰期自动推导）"
                        } else {
                            "Duration Timeline (Optional, auto-derived from t½ if empty)"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OptionalDecimalField(
                            value = pkState.onsetText,
                            onValueChange = { pkState.onsetText = it },
                            label = if (isZh) "起效 (h)" else "Onset (h)",
                            isError = !pkState.isOnsetValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                        OptionalDecimalField(
                            value = pkState.peakText,
                            onValueChange = { pkState.peakText = it },
                            label = if (isZh) "峰值 (h)" else "Peak (h)",
                            isError = !pkState.isPeakValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                        OptionalDecimalField(
                            value = pkState.totalDurationText,
                            onValueChange = { pkState.totalDurationText = it },
                            label = if (isZh) "总时长 (h)" else "Total (h)",
                            isError = !pkState.isTotalDurationValid,
                            errorMessage = positiveNumberErrorText(isZh),
                            onDone = onDoneKeyboardAction,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionalDecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    errorMessage: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = isError,
        supportingText = if (isError) {
            { Text(errorMessage) }
        } else {
            null
        },
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier
    )
}

private fun positiveNumberErrorText(isZh: Boolean): String {
    return if (isZh) "请输入大于 0 的数字" else "Enter number > 0"
}