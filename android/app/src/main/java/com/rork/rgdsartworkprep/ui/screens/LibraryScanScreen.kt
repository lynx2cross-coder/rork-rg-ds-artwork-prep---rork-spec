package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.data.LocationScanSummary
import com.rork.rgdsartworkprep.data.RomLocationLabel
import com.rork.rgdsartworkprep.model.RomEntry
import com.rork.rgdsartworkprep.ui.components.EmptyState
import com.rork.rgdsartworkprep.ui.components.IgnoreFileMenu
import com.rork.rgdsartworkprep.ui.components.InfoPill
import com.rork.rgdsartworkprep.ui.components.StatTile
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScanScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIgnoredFiles: () -> Unit,
    onStarted: () -> Unit,
) {
    val library by AppGraph.library.state.collectAsStateWithLifecycle()
    val locations by AppGraph.romLocations.locations.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val hasLocations = locations.isNotEmpty()

    var showAll by rememberSaveable { mutableStateOf(false) }
    var systemFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(locations) {
        if (locations.isNotEmpty()) AppGraph.library.refresh()
    }

    val scan = library.scan
    val base: List<RomEntry> = if (showAll) scan.roms else scan.missing
    val visible = base.filter { rom -> systemFilter == null || rom.system?.key == systemFilter }
    val selectionInView = visible.filter { it.id in selectedIds }
    val willRescrape = selectionInView.any { it.hasArtwork }

    val actionLabel = when {
        selectionInView.isNotEmpty() && willRescrape -> "Rescrape Selected (${selectionInView.size})"
        selectionInView.isNotEmpty() -> "Scrape Selected (${selectionInView.size})"
        else -> "Scrape Missing Artwork (${scan.missingArtwork})"
    }
    val actionEnabled = selectionInView.isNotEmpty() || scan.missingArtwork > 0

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Library Scan", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { AppGraph.library.refresh(force = true) }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Rescan library")
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
        bottomBar = {
            // Landscape puts this action at the foot of the left rail instead, so the
            // ROM list keeps the full height of the handheld screen.
            if (hasLocations && scan.total > 0 && !layout.isSplit) {
                Surface(color = Graphite) {
                    ScrapeActionButton(
                        label = actionLabel,
                        enabled = actionEnabled,
                        onClick = {
                            val targets = selectionInView.ifEmpty { scan.missing }
                            if (targets.isEmpty()) return@ScrapeActionButton
                            AppGraph.scraper.start(targets, rescrape = willRescrape)
                            selectedIds = emptySet()
                            onStarted()
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                !hasLocations -> EmptyState(
                    icon = Icons.Rounded.FolderOff,
                    title = "No ROM location yet",
                    description = "Add your ROM folders in Settings → ROM Locations — internal " +
                        "storage and SD card can both be added.",
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                )

                library.isScanning && !library.hasScanned -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(color = AnbernicOrange)
                    Text(
                        text = scanProgressLabel(library.progress, locations.size),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    library.progress?.let { progress ->
                        Text(
                            text = progress.location.label.full,
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                }

                library.error != null -> Column {
                    EmptyState(
                        icon = Icons.Rounded.FolderOff,
                        title = "ROM locations unreadable",
                        description = library.error.orEmpty(),
                        modifier = Modifier.clickable(onClick = onOpenSettings),
                    )
                    LocationSummaries(
                        summaries = scan.locations,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(horizontal = layout.screenPadding),
                    )
                }

                else -> {
                    val startScrape: () -> Unit = {
                        val targets = selectionInView.ifEmpty { scan.missing }
                        if (targets.isNotEmpty()) {
                            AppGraph.scraper.start(targets, rescrape = willRescrape)
                            selectedIds = emptySet()
                            onStarted()
                        }
                    }
                    val stats: @Composable () -> Unit = {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = layout.screenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatTile(
                                value = scan.total.toString(),
                                caption = "games found",
                                valueColor = StatusGreen,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                value = scan.withArtwork.toString(),
                                caption = "artwork present",
                                valueColor = StatusGreen,
                                modifier = Modifier.weight(1f),
                            )
                            StatTile(
                                value = scan.missingArtwork.toString(),
                                caption = "missing",
                                valueColor = StatusRed,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        IgnoredFilesIndicator(
                            count = scan.ignoredCount,
                            onClick = onOpenIgnoredFiles,
                            modifier = Modifier.padding(
                                start = layout.screenPadding,
                                end = layout.screenPadding,
                                top = if (layout.isShort) 8.dp else 10.dp,
                            ),
                        )
                    }
                    val filters: @Composable () -> Unit = {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (layout.isShort) 8.dp else 14.dp),
                            contentPadding = PaddingValues(horizontal = layout.screenPadding),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item {
                                LibraryChip(
                                    label = if (showAll) "All ROMs" else "Missing only",
                                    selected = true,
                                    onClick = { showAll = !showAll },
                                )
                            }
                            item {
                                LibraryChip(
                                    label = "Any system",
                                    selected = systemFilter == null,
                                    onClick = { systemFilter = null },
                                )
                            }
                            items(library.systemsPresent, key = { it.key }) { system ->
                                LibraryChip(
                                    label = system.shortName,
                                    selected = systemFilter == system.key,
                                    onClick = {
                                        systemFilter =
                                            if (systemFilter == system.key) null else system.key
                                    },
                                )
                            }
                        }
                    }
                    val romList: @Composable (Modifier) -> Unit = { listModifier ->
                        if (visible.isEmpty()) {
                            EmptyState(
                                icon = Icons.Rounded.FolderOff,
                                title = if (scan.total == 0) "No ROMs found" else "Nothing missing here",
                                description = if (scan.total == 0) {
                                    "Check that the selected folder contains system folders such as " +
                                        "GBA, SNES, PSX or PSP."
                                } else {
                                    "Every ROM in this filter already has artwork."
                                },
                                modifier = listModifier,
                            )
                        } else {
                            LazyColumn(
                                modifier = listModifier,
                                contentPadding = PaddingValues(
                                    start = layout.screenPadding,
                                    end = layout.screenPadding,
                                    top = 10.dp,
                                    bottom = 8.dp,
                                ),
                            ) {
                                items(visible, key = { it.id }) { rom ->
                                    RomRow(
                                        rom = rom,
                                        showLocation = locations.size > 1,
                                        checked = rom.id in selectedIds,
                                        onToggle = {
                                            selectedIds = if (rom.id in selectedIds) {
                                                selectedIds - rom.id
                                            } else {
                                                selectedIds + rom.id
                                            }
                                        },
                                        onIgnore = {
                                            selectedIds = selectedIds - rom.id
                                            AppGraph.ignoredFiles.add(rom.fileName)
                                        },
                                        modifier = Modifier.animateItem(),
                                    )
                                    HorizontalDivider(color = HairlineBorder)
                                }
                            }
                        }
                    }

                    if (layout.isSplit) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(layout.railFraction)
                                    .fillMaxHeight(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    stats()
                                    filters()
                                }
                                if (scan.total > 0) {
                                    ScrapeActionButton(
                                        label = actionLabel,
                                        enabled = actionEnabled,
                                        onClick = startScrape,
                                    )
                                }
                            }
                            VerticalDivider(color = HairlineBorder)
                            romList(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        stats()
                        filters()
                        romList(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * "N files ignored" under the scan totals, so the ignore list's effect is visible
 * instead of files silently going missing. Hidden when the scan left nothing out.
 * Shows only a count — names stay on the Ignored files screen, which a tap opens.
 */
@Composable
private fun IgnoredFilesIndicator(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (count <= 0) return
    InfoPill(
        icon = Icons.Rounded.VisibilityOff,
        text = ignoredFilesLabel(count, tappable = true),
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * One line per saved location under the scan totals — how many games each held, or
 * why it was not scanned. Shown only when there is more than one location or one
 * could not be read, so a single-folder library looks exactly as it always did.
 */
@Composable
private fun LocationSummaries(
    summaries: List<LocationScanSummary>,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (summaries.size <= 1 && summaries.all { it.scanned }) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            summaries.forEach { summary ->
                val ok = summary.scanned
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (ok) Modifier else Modifier.clickable(onClick = onOpenSettings)),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (ok) StatusGreen else StatusAmber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = summary.location.label.full,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = locationSummaryLabel(summary),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ok) TextSecondary else StatusAmber,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Short status for one location in the scan summary. */
internal fun locationSummaryLabel(summary: LocationScanSummary): String = when {
    summary.failed -> "could not be read"
    !summary.access.isScannable -> "unavailable"
    else -> buildString {
        append(if (summary.romCount == 1) "1 game" else "${summary.romCount} games")
        when (val removed = summary.gamelistEntriesRemoved) {
            0 -> Unit
            1 -> append(" \u00b7 1 deleted game removed from gamelist")
            else -> append(" \u00b7 $removed deleted games removed from gamelist")
        }
    }
}

/**
 * Plain-language label for the ignored-files count, shared by the Library Scan
 * indicator and the hand-picked files notice.
 */
internal fun ignoredFilesLabel(count: Int, tappable: Boolean): String {
    val base = if (count == 1) "1 file ignored" else "$count files ignored"
    return if (tappable) "$base \u00b7 tap to manage" else base
}

/** Primary "start scraping" action, shared by the portrait bottom bar and landscape rail. */
@Composable
private fun ScrapeActionButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val layout = LocalAppLayout.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = layout.screenPadding,
                vertical = if (layout.isShort) 8.dp else 12.dp,
            )
            .height(layout.buttonHeight),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AnbernicOrange,
            contentColor = Ink,
            disabledContainerColor = GraphiteElevated,
            disabledContentColor = TextSecondary,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Graphite,
            labelColor = TextSecondary,
            selectedContainerColor = Graphite,
            selectedLabelColor = AnbernicOrange,
        ),
        border = BorderStroke(1.dp, if (selected) AnbernicOrange else HairlineBorder),
    )
}

@Composable
private fun RomRow(
    rom: RomEntry,
    /** With several saved locations, names the one this file is in, so two same-named games can be told apart. */
    showLocation: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The checkbox already stands 48dp tall, so the row clears the touch target either way.
    val verticalPadding = if (LocalAppLayout.current.isShort) 3.dp else 6.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = AnbernicOrange,
                uncheckedColor = TextSecondary,
                checkmarkColor = Ink,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = rom.fileName,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val where = rom.locationTreeUri?.takeIf { showLocation }?.let { RomLocationLabel.of(it).full }
            if (where != null) {
                Text(
                    text = where,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (rom.hasArtwork) {
                Text(
                    text = "Artwork present",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusGreen,
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = GraphiteElevated,
        ) {
            Text(
                text = rom.system?.shortName ?: rom.extension.uppercase(),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
            )
        }
        IgnoreFileMenu(fileName = rom.fileName, onIgnore = onIgnore)
    }
}
