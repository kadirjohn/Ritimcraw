package com.ritim.ritimcraw.ui.library

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.ClipNameRules

/**
 * The one rename dialog (Library selection action + editor overflow).
 * Prefilled with [currentName], all selected on open, extension kept in
 * the text. [onRename] gets the trimmed name and returns false when the
 * repository rejected it (the inline error then shows; the dialog stays).
 */
@Composable
fun RenameClipDialog(
    currentName: String,
    onRename: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var field by remember {
        mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length)))
    }
    var rejected by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val result = ClipNameRules.validate(field.text, currentName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_clip_title)) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    rejected = false
                    field = it.copy(text = ClipNameRules.clamp(it.text))
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
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val ok = result as? ClipNameRules.Result.Ok ?: return@TextButton
                    if (onRename(ok.name)) onDismiss() else rejected = true
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
