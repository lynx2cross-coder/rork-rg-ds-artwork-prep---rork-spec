package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ImageNotSupported
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.model.RomEntry
import com.rork.rgdsartworkprep.ui.components.EmptyState
import com.rork.rgdsartworkprep.ui.components.InfoPill
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtworkScreen(onBack: () -> Unit) {
    val library by AppGraph.library.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val exportedCount = remember(library.scan.scannedAtMillis) { AppGraph.saf.exportedFileCount() }

    LaunchedEffect(settings.libraryTreeUri) {
        if (settings.libraryTreeUri != null) AppGraph.library.refresh()
    }

    val prepared = library.scan.prepared

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Artwork", style = MaterialTheme.typography.titleLarge) },
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
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (exportedCount > 0) {
                InfoPill(
                    icon = Icons.Rounded.Inventory2,
                    text = "$exportedCount file(s) waiting in ${AppGraph.saf.exportRootDisplayPath()}",
                    modifier = Modifier.padding(horizontal = layout.screenPadding),
                )
            }

            if (prepared.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ImageNotSupported,
                    title = "No prepared artwork yet",
                    description = "Covers you prepare are saved as Imgs/<rom name>.jpg " +
                        "and appear here after the next library scan.",
                )
            } else {
                Text(
                    text = "${prepared.size} covers in place",
                    modifier = Modifier.padding(
                        horizontal = layout.screenPadding + 4.dp,
                        vertical = if (layout.isShort) 6.dp else 10.dp,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                // Landscape fits more, smaller covers per row so the shelf still scrolls
                // in whole rows on the short handheld screen.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = layout.artworkTileMinWidth),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = layout.screenPadding,
                        vertical = 6.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(prepared, key = { it.id }) { rom -> ArtworkTile(rom) }
                }
            }
        }
    }
}

@Composable
private fun ArtworkTile(rom: RomEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(10.dp))
                .background(GraphiteElevated),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = rom.artworkUri,
                contentDescription = rom.baseName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = rom.baseName,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            maxLines = if (LocalAppLayout.current.isShort) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
        )
        Text(
            text = rom.system?.shortName ?: rom.extension.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}
