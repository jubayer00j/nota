package app.nota.feature.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nota.data.db.FolderEntity
import app.nota.ui.theme.NotaSpacing

/**
 * Compact folder + tag metadata row shown directly under the editor
 * title (Phase 1.5 fix 1 — the selectors used to sit at the very bottom
 * of the editor, below the body and attachments). Stateless: the owning
 * screen keeps its existing folderId / tagsCsv state and save semantics;
 * this row only presents and edits them as chips.
 *
 * Tags are edited as a list here; the caller converts to/from its CSV
 * form, so persistence (including the plain editor's tags-loaded gate)
 * is untouched.
 */
@Composable
fun NoteMetaRow(
    folders: List<FolderEntity>,
    folderId: String?,
    onFolderSelected: (String?) -> Unit,
    tags: List<String>,
    onTagsChanged: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }

    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(NotaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AssistChip(
                onClick = { folderMenuExpanded = true },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                trailingIcon = {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                label = {
                    Text(
                        folders.firstOrNull { it.id == folderId }?.name ?: "No folder",
                    )
                },
            )
            DropdownMenu(
                expanded = folderMenuExpanded,
                onDismissRequest = { folderMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("No folder") },
                    onClick = { onFolderSelected(null); folderMenuExpanded = false },
                )
                folders.forEach { folder ->
                    DropdownMenuItem(
                        text = { Text(folder.name) },
                        onClick = {
                            onFolderSelected(folder.id)
                            folderMenuExpanded = false
                        },
                    )
                }
            }
        }
        tags.forEach { tag ->
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(tag) },
                trailingIcon = {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Remove tag $tag",
                        modifier = Modifier.size(16.dp)
                            .clickable { onTagsChanged(tags - tag) },
                    )
                },
            )
        }
        AssistChip(
            onClick = { tagDraft = ""; showTagDialog = true },
            leadingIcon = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = { Text("Tag") },
        )
    }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text("Add tag") },
            text = {
                OutlinedTextField(
                    value = tagDraft,
                    onValueChange = { tagDraft = it },
                    label = { Text("Tag — commas add several") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val additions = tagDraft.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .filter { new -> tags.none { it.equals(new, ignoreCase = true) } }
                    if (additions.isNotEmpty()) onTagsChanged(tags + additions)
                    showTagDialog = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text("Cancel") }
            },
        )
    }
}

/** Splits the editors' comma-separated tag state into display tags. */
fun parseTagsCsv(tagsCsv: String): List<String> =
    tagsCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
