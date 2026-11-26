package com.ritim.ritimcraw.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.ClipNameRules

/**
 * The one rename dialog (Library selection action + editor overflow).
 * Prefilled with [currentName] MINUS its extension, all selected on
 * open (owner request 2026-11-26: the extension is the SYSTEM's, never
 * the user's to retype) — the field edits the DISPLAY NAME only. The
 * extension rides under the field as a read-only chip (the small pill
 * the list rows' format badge established), so the user SEES what will
 * be re-appended but cannot touch it; a name with no extension shows no
 * chip. [onRename] gets the trimmed NAME WITH the extension
 * re-appended (the repository contract is unchanged — the record's
 * displayName keeps its full form), and returns false when the
 * repository rejected it (the inline error then shows; the dialog
 * stays).
 */
@Composable
fun RenameClipDialog(
    currentName: String,
    onRename: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    // The extension is derived ONCE from the incoming name — immutable
    // for the dialog's lifetime (the user's edits cannot change it).
    val extension = remember(currentName) { ClipNameRules.extensionOf(currentName) }
    val namePart = remember(currentName) { ClipNameRules.namePart(currentName) }
    var field by remember(namePart) {
        mutableStateOf(TextFieldValue(namePart, TextRange(0, namePart.length)))
    }
    var rejected by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Validation sees ONLY the name part (owner request 2026-11-26):
    // the extension is outside the edit, so Unchanged compares
    // name-to-name and TooLong counts the name the user actually types.
    val result = ClipNameRules.validateName(field.text, namePart)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_clip_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = field,
                    onValueChange = {
                        rejected = false
                        field = it.copy(text = ClipNameRules.clampName(it.text))
                    },
                    label = { Text(stringResource(R.string.rename_clip_label)) },
                    singleLine = true,
                    isError = rejected,
                    supportingText = if (rejected) {
                        { Text(stringResource(R.string.rename_clip_error)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (extension.isNotEmpty()) {
                    // The read-only extension chip (the format badge's
                    // shape language, neutral colors — it is information,
                    // not an action): bottom-end under the field, 4dp
                    // clear of it.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ".$extension",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ok = result as? ClipNameRules.Result.Ok ?: return@TextButton
                    // The stored name = the edited name part + the
                    // preserved extension (the repository keeps its
                    // whole-name contract; the record never loses the
                    // extension the file still wears).
                    val stored = if (extension.isEmpty()) ok.name else ok.name + "." + extension
                    if (onRename(stored)) onDismiss() else rejected = true
                },
                enabled = result is ClipNameRules.Result.Ok,
            ) { Text(stringResource(R.string.rename_clip_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rename_clip_cancel))
            }
        },
    )
}
