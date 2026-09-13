package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.data.RomNameNormalizer
import com.rork.rgdsartworkprep.model.GameCandidate
import com.rork.rgdsartworkprep.model.PrepItem
import com.rork.rgdsartworkprep.network.ProviderResult
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.GraphiteHigh
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary
import kotlinx.coroutines.launch

/**
 * Bottom sheet used whenever a ROM has several plausible matches, or none at all.
 * The user can search the provider manually and pick the exact cover to use.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchPickerSheet(
    item: PrepItem,
    onDismiss: () -> Unit,
    onConfirm: (GameCandidate) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val layout = LocalAppLayout.current

    var query by remember(item.id) {
        mutableStateOf(RomNameNormalizer.searchTitle(item.rom.fileName, item.rom.folderChain))
    }
    var candidates by remember(item.id) { mutableStateOf(item.candidates) }
    var selected by remember(item.id) { mutableStateOf(item.candidates.firstOrNull()) }
    var isSearching by remember(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        if (item.candidates.isEmpty()) {
            isSearching = true
            when (val result = AppGraph.scraper.searchCandidates(item.rom, query)) {
                is ProviderResult.Success -> {
                    candidates = result.value
                    selected = result.value.firstOrNull()
                    error = if (result.value.isEmpty()) "No results — try a shorter search." else null
                }
                is ProviderResult.Failure -> error = "Search failed. Check your connection and try again."
            }
            isSearching = false
        }
    }

    val runSearch: () -> Unit = {
        scope.launch {
            isSearching = true
            error = null
            when (val result = AppGraph.scraper.searchCandidates(item.rom, query)) {
                is ProviderResult.Success -> {
                    candidates = result.value
                    selected = result.value.firstOrNull()
                    if (result.value.isEmpty()) error = "No results — try a shorter search."
                }
                is ProviderResult.Failure ->
                    error = "Search failed. Check your connection and try again."
            }
            isSearching = false
        }
        Unit
    }

    val header: @Composable () -> Unit = {
        Text(
            text = item.rom.fileName,
            style = if (layout.isShort) {
                MaterialTheme.typography.titleMedium
            } else {
                MaterialTheme.typography.titleLarge
            },
            color = TextPrimary,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val searchField: @Composable () -> Unit = {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Which game is this?") },
            placeholder = { Text("Search all artwork sources…") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = TextSecondary)
            },
            trailingIcon = {
                if (isSearching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = AnbernicOrange,
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AnbernicOrange,
                unfocusedBorderColor = HairlineBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedLabelColor = TextSecondary,
                unfocusedLabelColor = TextSecondary,
                cursorColor = AnbernicOrange,
            ),
        )
    }
    val searchButton: @Composable () -> Unit = {
        Button(
            onClick = runSearch,
            enabled = query.isNotBlank() && !isSearching,
            modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 6.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = GraphiteHigh,
                contentColor = TextPrimary,
            ),
        ) {
            Text("Search again", maxLines = 1)
        }
    }
    val errorText: @Composable () -> Unit = {
        error?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
    val confirmButton: @Composable () -> Unit = {
        Button(
            onClick = { selected?.let(onConfirm) },
            enabled = selected != null,
            modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AnbernicOrange,
                contentColor = Ink,
            ),
        ) {
            Text("Use selected cover", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    val candidateList: @Composable (Modifier) -> Unit = { listModifier ->
        LazyColumn(
            modifier = listModifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(candidates, key = { it.gameId }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    isSelected = selected?.gameId == candidate.gameId,
                    onSelect = { selected = candidate },
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = GraphiteElevated,
    ) {
        if (layout.isSplit) {
            // Landscape handheld: the sheet is wide but shallow, so search controls sit
            // beside the covers instead of pushing them off the bottom of the screen.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((layout.heightDp * 0.78f).dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    header()
                    searchField()
                    searchButton()
                    errorText()
                    Box(modifier = Modifier.weight(1f))
                    confirmButton()
                }
                candidateList(Modifier.weight(0.55f).fillMaxHeight())
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                header()
                searchField()
                searchButton()
                errorText()
                candidateList(Modifier.fillMaxWidth().heightIn(max = 380.dp))
                confirmButton()
                Box(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: GameCandidate,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = RoundedCornerShape(14.dp),
        color = GraphiteHigh,
        border = BorderStroke(1.dp, if (isSelected) AnbernicOrange else HairlineBorder),
    ) {
        val compact = LocalAppLayout.current.isShort
        Row(
            modifier = Modifier.padding(if (compact) 8.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(if (compact) 44.dp else 60.dp)
                    .height(if (compact) 58.dp else 80.dp)
                    .background(GraphiteElevated, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (candidate.coverUrl != null) {
                    AsyncImage(
                        model = candidate.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Icon(
                        Icons.Rounded.ImageNotSupported,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = candidate.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(candidate.systemName, candidate.region)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Icon(
                imageVector = if (isSelected) Icons.Rounded.RadioButtonChecked
                else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (isSelected) "Selected" else "Not selected",
                tint = if (isSelected) AnbernicOrange else TextSecondary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
