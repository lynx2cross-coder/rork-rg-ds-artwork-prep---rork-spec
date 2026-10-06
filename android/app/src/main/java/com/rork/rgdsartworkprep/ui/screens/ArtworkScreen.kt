package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.rork.rgdsartworkprep.data.LocationScanSummary
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
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
    // What each location's last walk actually saw, so a cover that is on the card but
    // not shown here says why, on the device, instead of leaving it to guesswork.
    val sources = remember(library.scan) {
        library.scan.locations.map { summary ->
            val shown = prepared.count { it.locationTreeUri == summary.location.treeUri }
            summary to ArtworkSourceNotes.of(summary, shown)
        }
    }
    val showSources = library.hasScanned && (sources.size > 1 || sources.any { it.second.hasProblems })

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
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState(
                        icon = Icons.Rounded.ImageNotSupported,
                        title = "No prepared artwork yet",
                        description = "Covers you prepare are saved as Imgs/<rom name>.jpg " +
                            "and appear here after the next library scan.",
                    )
                    if (showSources) {
                        CoverSourcesCard(
                            sources = sources,
                            modifier = Modifier.padding(horizontal = layout.screenPadding, vertical = 6.dp),
                        )
                    }
                }
            } else {
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
                    if (showSources) {
                        item(key = "cover-sources", span = { GridItemSpan(maxLineSpan) }) {
                            CoverSourcesCard(sources = sources)
                        }
                    }
                    // Keyed by the location-aware id, so the same game in two locations
                    // is two tiles, each showing the cover that sits in its own folder.
                    items(prepared, key = { it.id }) { rom ->
                        ArtworkTile(rom, showLocation = locations.size > 1)
                    }
                }
            }
        }
    }
}

/**
 * One row per saved location: how many games and covers it gave this screen, and,
 * when some of its covers cannot be shown, the reason the walk found. Starts open
 * when there is something to explain, folded to one line per location otherwise.
 */
@Composable
private fun CoverSourcesCard(
    sources: List<Pair<LocationScanSummary, ArtworkSourceNotes.Entry>>,
    modifier: Modifier = Modifier,
) {
    val anyProblems = sources.any { it.second.hasProblems }
    var expanded by rememberSaveable { mutableStateOf(anyProblems) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, if (anyProblems) StatusAmber.copy(alpha = 0.45f) else HairlineBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.animateContentSize().padding(vertical = 4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Where covers come from",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }
            sources.forEach { (summary, entry) ->
                val label = summary.location.label
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = if (label.volume == "SD card") Icons.Rounded.SdCard else Icons.Rounded.Smartphone,
                        contentDescription = null,
                        tint = if (entry.hasProblems) StatusAmber else TextSecondary,
                        modifier = Modifier.size(18.dp).padding(top = 1.dp),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${label.full} \u00b7 ${entry.headline}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            maxLines = if (expanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (expanded) {
                            entry.notes.forEach { note ->
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusAmber,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
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
