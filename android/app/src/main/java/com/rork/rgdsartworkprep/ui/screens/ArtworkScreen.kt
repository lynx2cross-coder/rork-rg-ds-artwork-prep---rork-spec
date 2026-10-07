package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
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
import androidx.compose.material.icons.rounded.Refresh
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
import com.rork.rgdsartworkprep.data.ArtworkRefresh
import com.rork.rgdsartworkprep.data.RomLocationLabel
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
    val locations by AppGraph.romLocations.locations.collectAsStateWithLifecycle()
    val scrape by AppGraph.scraper.state.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val exportedCount = remember(library.scan.scannedAtMillis) { AppGraph.saf.exportedFileCount() }

    LaunchedEffect(locations) {
        if (locations.isNotEmpty()) AppGraph.library.refresh()
    }
    // A preparation run that finished after the last scan may have written covers into
    // any saved location; rescan so every one of them is shown, not only the ones
    // that existed when the library was first scanned.
    LaunchedEffect(scrape.scanEndedAtWallMillis, library.scan.scannedAtMillis, library.isScanning) {
        if (
            ArtworkRefresh.needsRescan(
                lastRunEndedAtMillis = scrape.scanEndedAtWallMillis,
                scannedAtMillis = library.scan.scannedAtMillis,
                hasScanned = library.hasScanned,
                isScanning = library.isScanning,
            )
        ) {
            AppGraph.library.refresh(force = true)
        }
    }

    val prepared = library.scan.prepared
    // Not persisted: the gallery opens on every platform each time.
    var selectedPlatform by rememberSaveable { mutableStateOf<String?>(null) }
    val platformChips = remember(prepared) { ArtworkPlatforms.chips(prepared) { it.system } }
    val activePlatform = ArtworkPlatforms.effectiveSelection(selectedPlatform, platformChips)
    val shown = remember(prepared, activePlatform) {
        ArtworkPlatforms.filter(prepared, activePlatform) { it.system }
    }

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
                actions = {
                    if (locations.isNotEmpty()) {
                        IconButton(onClick = { AppGraph.library.refresh(force = true) }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Rescan all ROM locations")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Graphite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary,
                    actionIconContentColor = TextSecondary,
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

            if (prepared.isEmpty() && library.isScanning) {
                Text(
                    text = scanProgressLabel(library.progress, locations.size),
                    modifier = Modifier.padding(horizontal = layout.screenPadding + 4.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            } else if (prepared.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.ImageNotSupported,
                    title = "No prepared artwork yet",
                    description = "Covers you prepare are saved as Imgs/<rom name>.jpg " +
                        "and appear here after the next library scan.",
                )
            } else {
                // One platform needs no filter, so the gallery stays as it was.
                if (platformChips.size > 1) {
                    PlatformFilterRow(
                        chips = platformChips,
                        allCount = prepared.size,
                        selected = activePlatform,
                        onSelect = { selectedPlatform = it },
                    )
                }
                Text(
                    text = coversInPlaceLabel(prepared.size, locations.size, library.isScanning),
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
                    // Keyed by the location-aware id, so the same game in two locations
                    // is two tiles, each showing the cover that sits in its own folder.
                    items(shown, key = { it.id }) { rom ->
                        ArtworkTile(rom, showLocation = locations.size > 1)
                    }
                }
            }
        }
    }
}

/** One chip per platform with covers, scrolling sideways when they outgrow the screen. */
@Composable
private fun PlatformFilterRow(
    chips: List<ArtworkPlatforms.Chip>,
    allCount: Int,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val layout = LocalAppLayout.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = if (layout.isShort) 4.dp else 8.dp),
        contentPadding = PaddingValues(horizontal = layout.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            PlatformChip(label = "All", count = allCount, selected = selected == null) { onSelect(null) }
        }
        rowItems(chips, key = { it.key }) { chip ->
            PlatformChip(label = chip.label, count = chip.count, selected = selected == chip.key) {
                onSelect(if (selected == chip.key) null else chip.key)
            }
        }
    }
}

@Composable
private fun PlatformChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label \u00b7 $count") },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Graphite,
            labelColor = TextSecondary,
            selectedContainerColor = Graphite,
            selectedLabelColor = AnbernicOrange,
        ),
        border = BorderStroke(1.dp, if (selected) AnbernicOrange else HairlineBorder),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label, $count covers" + if (selected) ", selected" else ""
        },
    )
}

@Composable
private fun ArtworkTile(rom: RomEntry, showLocation: Boolean) {
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
        val system = rom.system?.shortName ?: rom.extension.uppercase()
        val where = rom.locationTreeUri?.takeIf { showLocation }?.let { RomLocationLabel.of(it).volume }
        Text(
            text = if (where != null) "$system \u00b7 $where" else system,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
