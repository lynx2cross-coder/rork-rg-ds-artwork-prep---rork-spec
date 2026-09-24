package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.data.EntryCheck
import com.rork.rgdsartworkprep.data.IgnoredFilePresets
import com.rork.rgdsartworkprep.ui.components.EmptyState
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

/**
 * Settings -> Ignored files: the only place an ignored filename is ever shown.
 *
 * Removing an entry takes effect from the next scan. It does not bring the file back
 * into a run already on screen, because that run never contained it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IgnoredFilesScreen(onBack: () -> Unit) {
    val ignored by AppGraph.ignoredFiles.ignored.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current

    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var presetNote by remember { mutableStateOf<String?>(null) }
    val missingPreset = IgnoredFilePresets.missingFrom(ignored)

    val submit: () -> Unit = {
        when (val check = ignored.check(input)) {
            is EntryCheck.Valid -> {
                AppGraph.ignoredFiles.add(check.fileName)
                input = ""
                error = null
            }
            EntryCheck.Blank -> error = "Type a filename, for example boot9.bin"
            EntryCheck.IncludesFolder -> error = "Enter just the filename, without any folder"
            EntryCheck.HasWildcard ->
                error = "Wildcards are not supported \u2014 enter one exact filename"
            is EntryCheck.AlreadyIgnored -> error = "\u201c${check.existing}\u201d is already ignored"
        }
    }

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Ignored files", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Graphite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(
                start = layout.screenPadding,
                end = layout.screenPadding,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = "Files listed here are skipped by every scan as if they were not in " +
                        "your library \u2014 they are never counted, identified or looked up. " +
                        "Names must match exactly; capital letters do not matter.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            error = null
                        },
                        label = { Text("Filename") },
                        placeholder = { Text("boot9.bin") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AnbernicOrange,
                            unfocusedBorderColor = HairlineBorder,
                            errorBorderColor = StatusRed,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = TextSecondary,
                            unfocusedLabelColor = TextSecondary,
                            errorLabelColor = StatusRed,
                            errorSupportingTextColor = StatusRed,
                            cursorColor = AnbernicOrange,
                        ),
                    )
                    Button(
                        onClick = submit,
                        enabled = input.isNotBlank(),
                        modifier = Modifier.padding(top = 8.dp).height(layout.buttonHeight),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AnbernicOrange,
                            contentColor = Ink,
                            disabledContainerColor = GraphiteElevated,
                            disabledContentColor = TextSecondary,
                        ),
                    ) {
                        Text("Add", maxLines = 1)
                    }
                }
            }
            item {
                ThreeDsPresetCard(
                    missing = missingPreset,
                    note = presetNote,
                    onAdd = {
                        val added = AppGraph.ignoredFiles.addAll(IgnoredFilePresets.threeDsSystemFiles)
                        val skipped = IgnoredFilePresets.threeDsSystemFiles.size - added.size
                        presetNote = when {
                            added.isEmpty() -> "All four were already on your list."
                            skipped == 0 -> "Added ${added.size} files."
                            else -> "Added ${added.size} \u2014 $skipped already on your list."
                        }
                    },
                )
            }
            if (ignored.isEmpty) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.VisibilityOff,
                        title = "No ignored files",
                        description = "Filenames you ignore will appear here. Use \u201cIgnore this " +
                            "file\u201d on any scan result, or add a name above.",
                    )
                }
            } else {
                item {
                    Text(
                        text = if (ignored.size == 1) "1 file ignored" else "${ignored.size} files ignored",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                }
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = GraphiteElevated,
                        border = BorderStroke(1.dp, HairlineBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            ignored.names.forEachIndexed { index, name ->
                                if (index > 0) HorizontalDivider(color = HairlineBorder)
                                IgnoredFileRow(
                                    fileName = name,
                                    onRemove = { AppGraph.ignoredFiles.remove(name) },
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = "Removing a name lets that file appear again from the next scan.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}

/**
 * The optional one-tap preset. It only ever adds ordinary entries to the user's own
 * list; nothing is added until the button is pressed, and each name stays removable.
 */
@Composable
private fun ThreeDsPresetCard(missing: List<String>, note: String?, onAdd: () -> Unit) {
    val layout = LocalAppLayout.current
    val allPresent = missing.isEmpty()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Common 3DS system files",
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
            )
            Text(
                text = "If your 3DS folder also holds emulator support files, they can be picked " +
                    "up as games. This adds exactly these names to your list \u2014 nothing else, " +
                    "and each can be removed on its own:",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Text(
                text = IgnoredFilePresets.threeDsSystemFiles.joinToString("  \u00b7  "),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
            )
            OutlinedButton(
                onClick = onAdd,
                enabled = !allPresent,
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 4.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (allPresent) HairlineBorder else AnbernicOrange),
            ) {
                Text(
                    text = when {
                        allPresent -> "All 3DS system files are on your list"
                        missing.size == IgnoredFilePresets.threeDsSystemFiles.size ->
                            "Add common 3DS system files"
                        else -> "Add the ${missing.size} not yet listed"
                    },
                    color = if (allPresent) TextSecondary else AnbernicOrange,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            note?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun IgnoredFileRow(fileName: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = fileName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Stop ignoring $fileName",
                tint = TextSecondary,
            )
        }
    }
}
