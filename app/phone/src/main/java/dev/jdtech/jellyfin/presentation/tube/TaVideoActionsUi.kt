package dev.jdtech.jellyfin.presentation.tube

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.film.presentation.tube.TaVideoAction
import dev.jdtech.jellyfin.presentation.theme.spacings

/** Buttons to reindex, download again or delete videos. They only ask; the dialog confirms. */
@Composable
fun TaVideoActionButtons(
    onAction: (TaVideoAction) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        modifier = modifier,
    ) {
        OutlinedButton(
            onClick = { onAction(TaVideoAction.REINDEX) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(CoreR.string.ta_action_reindex), maxLines = 1)
        }
        OutlinedButton(
            onClick = { onAction(TaVideoAction.REDOWNLOAD) },
            enabled = enabled,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(CoreR.string.ta_action_redownload), maxLines = 1)
        }
        OutlinedButton(
            onClick = { onAction(TaVideoAction.DELETE) },
            enabled = enabled,
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border =
                BorderStroke(
                    1.dp,
                    if (enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outlineVariant,
                ),
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(CoreR.string.ta_action_delete), maxLines = 1)
        }
    }
}

/**
 * Asks whether to go ahead with [action] on [count] videos. Deleting can also put them on the
 * ignore list, so they aren't downloaded again.
 */
@Composable
fun TaVideoActionDialog(
    action: TaVideoAction,
    count: Int,
    onConfirm: (ignore: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var ignore by rememberSaveable { mutableStateOf(false) }
    val title =
        when (action) {
            TaVideoAction.REINDEX -> CoreR.plurals.ta_confirm_reindex
            TaVideoAction.REDOWNLOAD -> CoreR.plurals.ta_confirm_redownload
            TaVideoAction.DELETE -> CoreR.plurals.ta_confirm_delete
        }
    val text =
        when (action) {
            TaVideoAction.REINDEX -> CoreR.string.ta_confirm_reindex_text
            TaVideoAction.REDOWNLOAD -> CoreR.string.ta_confirm_redownload_text
            TaVideoAction.DELETE -> CoreR.string.ta_confirm_delete_text
        }
    val confirm =
        when (action) {
            TaVideoAction.REINDEX -> CoreR.string.ta_action_reindex
            TaVideoAction.REDOWNLOAD -> CoreR.string.ta_action_redownload
            TaVideoAction.DELETE -> CoreR.string.ta_action_delete
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(title, count, count)) },
        text = {
            Column {
                Text(stringResource(text))
                if (action == TaVideoAction.DELETE) {
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { ignore = !ignore },
                    ) {
                        Checkbox(checked = ignore, onCheckedChange = { ignore = it })
                        Column {
                            Text(stringResource(CoreR.string.ta_delete_ignore))
                            Text(
                                text = stringResource(CoreR.string.ta_delete_ignore_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(ignore) },
                colors =
                    if (action == TaVideoAction.DELETE) {
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreR.string.cancel)) }
        },
    )
}

/**
 * A heading that starts a part of a server page, such as the channel's settings, set apart from the
 * rows above it by a line and larger, bolder text.
 */
@Composable
fun TaSectionHeading(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = MaterialTheme.spacings.medium)) {
        HorizontalDivider()
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier =
                Modifier.padding(
                    top = MaterialTheme.spacings.medium,
                    bottom = MaterialTheme.spacings.small,
                ),
        )
    }
}
