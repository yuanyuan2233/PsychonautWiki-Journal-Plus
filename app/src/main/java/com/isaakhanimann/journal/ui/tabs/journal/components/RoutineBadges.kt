package com.isaakhanimann.journal.ui.tabs.journal.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.isaakhanimann.journal.R
import com.isaakhanimann.journal.ui.tabs.search.SubstanceLocalizationHelper

@Composable
fun UnconfirmedMedicationBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 1
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF4A2C00) else Color(0xFFFFF3CD)
    val contentColor = if (isDark) Color(0xFFFFCA28) else Color(0xFFD97706)
    val borderColor = if (isDark) Color(0xFFFFB300) else Color(0xFFF59E0B)

    Surface(
        modifier = modifier
            .size(22.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (count > 1) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = contentColor
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PriorityHigh,
                    contentDescription = stringResource(R.string.routine_badge_unconfirmed_cd),
                    tint = contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
fun CopyMemoBadge(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isDark) Color(0xFF2E2656) else Color(0xFFEDE9FE)
    val contentColor = if (isDark) Color(0xFFC4B5FD) else Color(0xFF5B21B6)
    val borderColor = if (isDark) Color(0xFF7C3AED) else Color(0xFFA78BFA)

    Surface(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.routine_badge_copy_memo_cd),
                tint = contentColor,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = stringResource(R.string.routine_badge_copy_memo_short),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        }
    }
}

@Composable
fun UnconfirmedDoseActionDialog(
    substanceName: String,
    doseTimeText: String,
    onConfirmTaken: () -> Unit,
    onSkipDelete: () -> Unit,
    onEditRecord: () -> Unit,
    onDismiss: () -> Unit
) {
    val localizedName = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.routine_unconfirmed_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.routine_unconfirmed_dialog_body,
                        localizedName,
                        doseTimeText
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = onConfirmTaken,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.routine_unconfirmed_dialog_confirm))
                    }
                    OutlinedButton(
                        onClick = onSkipDelete,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(text = stringResource(R.string.routine_unconfirmed_dialog_skip))
                    }
                    TextButton(
                        onClick = onEditRecord,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.routine_unconfirmed_dialog_edit))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.routine_common_cancel))
            }
        }
    )
}

@Composable
fun MultiUnconfirmedDoseActionDialog(
    items: List<Triple<Int, String, String>>, // (ingestionId, substanceName, timeText)
    onConfirmTaken: (Int) -> Unit,
    onSkipDelete: (Int) -> Unit,
    onEditRecord: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.routine_unconfirmed_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items.forEach { (ingestionId, substanceName, timeText) ->
                    val localizedName = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceName)
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "$localizedName · $timeText",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                TextButton(onClick = { onConfirmTaken(ingestionId) }) {
                                    Text(text = stringResource(R.string.routine_notification_action_confirm))
                                }
                                TextButton(onClick = { onSkipDelete(ingestionId) }) {
                                    Text(
                                        text = stringResource(R.string.routine_notification_action_skip),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                                TextButton(onClick = { onEditRecord(ingestionId) }) {
                                    Text(text = stringResource(R.string.routine_unconfirmed_dialog_edit))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.routine_common_close))
            }
        }
    )
}

@Composable
fun CopyMemoExplanationDialog(
    experienceTitle: String,
    substanceName: String,
    onOpenExperience: () -> Unit,
    onDismiss: () -> Unit
) {
    val localizedName = SubstanceLocalizationHelper.getSubstanceDisplayName(substanceName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.routine_memo_dialog_title)) },
        text = {
            Text(
                text = stringResource(
                    R.string.routine_memo_dialog_body,
                    experienceTitle,
                    localizedName
                ),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Button(onClick = onOpenExperience) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.routine_memo_dialog_open_exp),
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.routine_common_close))
            }
        }
    )
}
