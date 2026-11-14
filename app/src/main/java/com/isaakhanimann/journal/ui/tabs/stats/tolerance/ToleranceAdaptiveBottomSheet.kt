/*
 * Copyright (c) 2026. PsychonautWiki Journal Chinese Maintenance Branch.
 */

package com.isaakhanimann.journal.ui.tabs.stats.tolerance

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper

/**
 * 允许用户自由调整主观耐受体感、覆盖算法并自适应的底部抽屉面板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToleranceAdaptiveBottomSheet(
    substanceName: String,
    currentCustomization: SubstanceToleranceCustomization?,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSaveCustomization: (SubstanceToleranceCustomization) -> Unit,
    onMarkRecoveredToday: () -> Unit,
    onResetToDefault: () -> Unit
) {
    val localizedName = SubstanceLocalizationHelper.getLocalizedName(substanceName)
    var fullDaysText by remember(currentCustomization) {
        mutableStateOf(currentCustomization?.customFullDays?.toString() ?: "")
    }
    var zeroDaysText by remember(currentCustomization) {
        mutableStateOf(currentCustomization?.customZeroDays?.toString() ?: "")
    }
    val validation = remember(fullDaysText, zeroDaysText) {
        validateToleranceInputs(fullDaysText, zeroDaysText)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = localizedName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (localizedName != substanceName) {
                        Text(
                            text = substanceName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.tolerance_sheet_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 一键标记恢复基线
            Button(
                onClick = {
                    onMarkRecoveredToday()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.tolerance_sheet_mark_today))
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 自由修改完全耐受天数
            val fullErrorRes = validation.fullDaysErrorRes
            OutlinedTextField(
                value = fullDaysText,
                onValueChange = { fullDaysText = it },
                label = { Text(stringResource(R.string.tolerance_sheet_full_days_label)) },
                placeholder = { Text(stringResource(R.string.tolerance_sheet_full_days_placeholder)) },
                isError = fullErrorRes != null,
                supportingText = if (fullErrorRes != null) {
                    { Text(text = stringResource(fullErrorRes)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 自由修改耐受归零天数
            val zeroErrorRes = validation.zeroDaysErrorRes
            OutlinedTextField(
                value = zeroDaysText,
                onValueChange = { zeroDaysText = it },
                label = { Text(stringResource(R.string.tolerance_sheet_zero_days_label)) },
                placeholder = { Text(stringResource(R.string.tolerance_sheet_zero_days_placeholder)) },
                isError = zeroErrorRes != null,
                supportingText = if (zeroErrorRes != null) {
                    { Text(text = stringResource(zeroErrorRes)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onResetToDefault()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.tolerance_sheet_reset))
                }

                Button(
                    onClick = {
                        if (!validation.canSave || validation.zeroDays == null) return@Button
                        onSaveCustomization(
                            SubstanceToleranceCustomization(
                                substanceName = substanceName,
                                customFullDays = validation.fullDays,
                                customZeroDays = validation.zeroDays,
                                manualResetInstant = currentCustomization?.manualResetInstant
                            )
                        )
                        onDismiss()
                    },
                    enabled = validation.canSave,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.tolerance_sheet_save))
                }
            }
        }
    }
}

internal const val MAX_CUSTOM_TOLERANCE_DAYS = 3650.0

internal data class ToleranceInputValidation(
    val fullDays: Double?,
    val zeroDays: Double?,
    @StringRes val fullDaysErrorRes: Int?,
    @StringRes val zeroDaysErrorRes: Int?,
    val canSave: Boolean
)

/**
 * 校验自定义耐受天数输入：
 * - fullDays（如填写）与 zeroDays 必须为有限数字、非负且不超过 MAX_CUSTOM_TOLERANCE_DAYS；
 * - 若填写了 fullDays，则必须同时填写 zeroDays 且满足 fullDays <= zeroDays。
 */
internal fun validateToleranceInputs(
    fullDaysText: String,
    zeroDaysText: String
): ToleranceInputValidation {
    val fullTrimmed = fullDaysText.trim()
    val zeroTrimmed = zeroDaysText.trim()

    var fullDaysErrorRes: Int? = null
    var zeroDaysErrorRes: Int? = null

    val fullValue: Double? = if (fullTrimmed.isEmpty()) {
        if (fullDaysText.isNotEmpty()) {
            fullDaysErrorRes = R.string.tolerance_sheet_error_invalid_days
        }
        null
    } else {
        val parsed = fullTrimmed.replace(',', '.').toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in 0.0..MAX_CUSTOM_TOLERANCE_DAYS) {
            fullDaysErrorRes = R.string.tolerance_sheet_error_invalid_days
            null
        } else {
            if (parsed == 0.0) 0.0 else parsed
        }
    }

    val zeroValue: Double? = if (zeroTrimmed.isEmpty()) {
        if (zeroDaysText.isNotEmpty()) {
            zeroDaysErrorRes = R.string.tolerance_sheet_error_invalid_days
        } else if (fullTrimmed.isNotEmpty()) {
            zeroDaysErrorRes = R.string.tolerance_sheet_error_zero_required_with_full
        }
        null
    } else {
        val parsed = zeroTrimmed.replace(',', '.').toDoubleOrNull()
        if (parsed == null || !parsed.isFinite() || parsed !in 0.0..MAX_CUSTOM_TOLERANCE_DAYS) {
            zeroDaysErrorRes = R.string.tolerance_sheet_error_invalid_days
            null
        } else {
            if (parsed == 0.0) 0.0 else parsed
        }
    }

    if (fullDaysErrorRes == null && zeroDaysErrorRes == null && fullValue != null && zeroValue != null && fullValue > zeroValue) {
        fullDaysErrorRes = R.string.tolerance_sheet_error_full_exceeds_zero
    }

    val canSave = fullDaysErrorRes == null && zeroDaysErrorRes == null && zeroValue != null
    return ToleranceInputValidation(
        fullDays = fullValue,
        zeroDays = zeroValue,
        fullDaysErrorRes = fullDaysErrorRes,
        zeroDaysErrorRes = zeroDaysErrorRes,
        canSave = canSave
    )
}
