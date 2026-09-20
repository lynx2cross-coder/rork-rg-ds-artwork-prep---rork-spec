package com.rork.rgdsartworkprep.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryAddCheck
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import androidx.compose.ui.res.stringResource
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.data.RomNameNormalizer
import com.rork.rgdsartworkprep.domain.ScrapeState
import com.rork.rgdsartworkprep.model.PrepItem
import com.rork.rgdsartworkprep.model.PrepStatus
import com.rork.rgdsartworkprep.model.StatusTone
import com.rork.rgdsartworkprep.model.isRetryable
import com.rork.rgdsartworkprep.model.label
import com.rork.rgdsartworkprep.model.opensManualSearch
import com.rork.rgdsartworkprep.model.retryLabel
import com.rork.rgdsartworkprep.model.tone
import com.rork.rgdsartworkprep.network.QuotaLevel
import com.rork.rgdsartworkprep.network.QuotaSnapshot
import com.rork.rgdsartworkprep.ui.components.EmptyState
import com.rork.rgdsartworkprep.ui.components.NoticeBanner
import com.rork.rgdsartworkprep.ui.components.StatusChip
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.layout.StackedPane
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.GraphiteHigh
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.StatusNeutralDim
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepareScreen(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickFromLibrary: () -> Unit,
) {
    val context = LocalContext.current
    // Read in composable scope, not inside the click handler: a value pulled from
    // LocalContext at click time is not invalidated when the configuration changes,
    // so a shared summary could carry the app name from the previous locale.
    val appName = stringResource(R.string.app_name)
    val state by AppGraph.scraper.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val quota by AppGraph.screenScraper.quota.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current

    var pickerItem by remember { mutableStateOf<PrepItem?>(null) }
    var showRetryQueue by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<PrepStatus?>(null) }
    var sort by remember { mutableStateOf(PrepSort.Name) }
    var searchQuery by remember { mutableStateOf("") }

    val filterCounts = remember(state.items) {
        FILTERABLE_STATUSES.associateWith { status -> state.items.count { it.status == status } }
    }
    // A filter would silently hide everything once its games are fixed or a new run starts.
    LaunchedEffect(filterCounts) {
        if (statusFilter != null && (filterCounts[statusFilter] ?: 0) == 0) statusFilter = null
    }
    // A stale search must not survive into a fresh batch.
    LaunchedEffect(state.items) {
        if (state.items.isEmpty()) searchQuery = ""
    }
    val visibleItems = remember(state.items, statusFilter, sort, searchQuery) {
        val filtered =
            statusFilter?.let { status -> state.items.filter { it.status == status } } ?: state.items
        val searched = if (searchQuery.isBlank()) {
            filtered
        } else {
            val needle = searchQuery.trim()
            filtered.filter { sortName(it).contains(needle, ignoreCase = true) }
        }
        when (sort) {
            PrepSort.Name -> searched.sortedWith(compareBy { sortName(it).lowercase() })
            // Unresolved games have no release year yet — park them at the end.
            PrepSort.ReleaseYear -> searched.sortedBy { it.releaseYear ?: Int.MAX_VALUE }
            PrepSort.Size -> searched.sortedByDescending { it.rom.sizeBytes }
        }
    }
    // Retry only acts on what the user can actually see, so a filtered view never
    // silently requeues games that are scrolled out of the filter.
    val retryableVisibleIds = remember(visibleItems) {
        visibleItems.filter { it.status.isRetryable }.map { it.id }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val treeUri = settings.libraryTreeUri?.let(Uri::parse)
        val roms = uris.mapNotNull { AppGraph.saf.romFromPickedDocument(it, treeUri) }
        if (roms.isNotEmpty()) AppGraph.scraper.start(roms, rescrape = false)
    }

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Prepare New Games", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                shareScanSummary(
                                    context,
                                    buildScanSummary(state, appName),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "Share scan summary",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Graphite,
                    titleContentColor = TextPrimary,
                    navigationIconContentColor = TextPrimary,
                ),
            )
        },
        bottomBar = {
            // In the landscape split the actions live at the foot of the controls rail,
            // so the results list keeps the full height of the handheld screen.
            if (state.items.isNotEmpty() && !layout.isSplit) {
                PrepareBottomBar(
                    retryCount = retryableVisibleIds.size,
                    retryLabel = statusFilter?.takeIf { it.isRetryable }?.retryLabel ?: "failed",
                    isRunning = state.isRunning,
                    onRetry = { AppGraph.scraper.retryFailed(retryableVisibleIds) },
                    onCancel = { AppGraph.scraper.cancel() },
                    onDone = {
                        AppGraph.library.refresh(force = true)
                        onBack()
                    },
                )
            }
        },
    ) { innerPadding ->
        if (state.items.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                PrepareIntro(
                    hasProvider = settings.hasAnyProvider,
                    quota = quota,
                    onChooseFiles = { filePicker.launch(arrayOf("*/*")) },
                    onPickFromLibrary = onPickFromLibrary,
                    onOpenSettings = onOpenSettings,
                )
            }
        } else if (layout.isSplit) {
            // Landscape handheld: scrolling controls rail on the left, results list keeps
            // the full height of the screen on the right.
            Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
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
                        PrepareControls(
                            state = state,
                            quota = quota,
                            filterCounts = filterCounts,
                            statusFilter = statusFilter,
                            onStatusFilter = { statusFilter = it },
                            sort = sort,
                            onSort = { sort = it },
                            searchQuery = searchQuery,
                            onSearchQuery = { searchQuery = it },
                            onOpenRetryQueue = { showRetryQueue = true },
                        )
                    }
                    PrepareBottomBar(
                        retryCount = retryableVisibleIds.size,
                        retryLabel = statusFilter?.takeIf { it.isRetryable }?.retryLabel ?: "failed",
                        isRunning = state.isRunning,
                        onRetry = { AppGraph.scraper.retryFailed(retryableVisibleIds) },
                        onCancel = { AppGraph.scraper.cancel() },
                        onDone = {
                            AppGraph.library.refresh(force = true)
                            onBack()
                        },
                    )
                }
                VerticalDivider(color = HairlineBorder)
                PrepareResults(
                    items = visibleItems,
                    searchQuery = searchQuery,
                    onPick = { pickerItem = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                PrepareControls(
                    state = state,
                    quota = quota,
                    filterCounts = filterCounts,
                    statusFilter = statusFilter,
                    onStatusFilter = { statusFilter = it },
                    sort = sort,
                    onSort = { sort = it },
                    searchQuery = searchQuery,
                    onSearchQuery = { searchQuery = it },
                    onOpenRetryQueue = { showRetryQueue = true },
                )
                PrepareResults(
                    items = visibleItems,
                    searchQuery = searchQuery,
                    onPick = { pickerItem = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pickerItem?.let { item ->
        MatchPickerSheet(
            item = item,
            onDismiss = { pickerItem = null },
            onConfirm = { candidate ->
                AppGraph.scraper.applyCandidate(item.id, candidate)
                pickerItem = null
            },
        )
    }

    if (showRetryQueue) {
        RetryQueueSheet(
            jobs = state.retryQueue,
            isRunning = state.isRunning,
            onRetryAll = {
                AppGraph.scraper.retryFailed(state.retryQueue.map { it.id })
                showRetryQueue = false
            },
            onClearQueue = {
                AppGraph.scraper.clearRetryQueue()
                showRetryQueue = false
            },
            onDismiss = { showRetryQueue = false },
        )
    }
}

/**
 * Everything above the results list: run stats, progress, notices, filters, sort and
 * search. Stacked in portrait, and the scrolling left rail of the landscape split.
 */
@Composable
private fun PrepareControls(
    state: ScrapeState,
    quota: QuotaSnapshot?,
    filterCounts: Map<PrepStatus, Int>,
    statusFilter: PrepStatus?,
    onStatusFilter: (PrepStatus?) -> Unit,
    sort: PrepSort,
    onSort: (PrepSort) -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    onOpenRetryQueue: () -> Unit,
) {
    val layout = LocalAppLayout.current
    val pad = layout.screenPadding

    RunSummaryCard(
        startedAtMs = state.runStartedAtMs,
        endedAtMs = state.runEndedAtMs,
        isRunning = state.isRunning,
        matchedCount = state.savedCount,
        totalCount = state.total,
    )
    Spacer(Modifier.height(8.dp))
    ProgressHeader(
        processed = state.processed,
        total = state.total,
        isRunning = state.isRunning,
        currentLine = state.currentLine,
        savedCount = state.savedCount,
        skippedCount = state.skippedCount,
        attentionCount = state.needsAttentionCount,
        failedCount = state.failedCount,
    )
    // Deferred work is surfaced on its own, above the filters: a game waiting on a
    // busy server is not a failure, and burying it among failures is what made a
    // passing network problem look like a missing game.
    RetryQueueCard(
        queuedCount = state.queuedForRetryCount,
        isRunning = state.isRunning,
        onOpen = onOpenRetryQueue,
        modifier = Modifier.padding(horizontal = pad, vertical = 8.dp),
    )
    // Shown above the run banner: knowing the allowance is nearly gone explains why a
    // batch is about to stop, before it does.
    QuotaWarningCard(
        quota = quota,
        pendingCount = (state.total - state.processed).coerceAtLeast(0),
        modifier = Modifier.padding(horizontal = pad, vertical = 8.dp),
    )
    state.banner?.let { banner ->
        NoticeBanner(
            text = banner,
            modifier = Modifier.padding(horizontal = pad, vertical = 8.dp),
        )
    }
    state.gamelistNote?.let { note ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = pad, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = StatusGreen,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = StatusGreen,
            )
        }
    }
    StatusFilterRow(
        counts = filterCounts,
        allCount = state.items.size,
        selected = statusFilter,
        onSelect = onStatusFilter,
    )
    SortMenuRow(sort = sort, onSelect = onSort)
    SearchField(
        query = searchQuery,
        onQueryChange = onSearchQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = pad)
            .padding(bottom = 8.dp),
    )
}

/** The prepared-games list, or the reason it is empty. */
@Composable
private fun PrepareResults(
    items: List<PrepItem>,
    searchQuery: String,
    onPick: (PrepItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pad = LocalAppLayout.current.screenPadding

    if (items.isEmpty()) {
        val searchActive = searchQuery.isNotBlank()
        EmptyState(
            icon = Icons.Rounded.FilterAltOff,
            title = if (searchActive) "No title matches" else "Nothing in this filter",
            description = if (searchActive) {
                "No game in the current view is called \u201c" +
                    searchQuery.trim() + "\u201d. Try a shorter search, " +
                    "or clear the filter chips."
            } else {
                "No games are in that category right now. " +
                    "Tap \u201cAll\u201d to see the whole batch again."
            },
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = pad, end = pad, top = 8.dp, bottom = 16.dp,
        ),
    ) {
        items(items, key = { it.id }) { item ->
            PrepRow(
                item = item,
                onClick = { if (item.status.opensManualSearch) onPick(item) },
            )
            HorizontalDivider(color = HairlineBorder)
        }
    }
}

@Composable
private fun PrepareIntro(
    hasProvider: Boolean,
    quota: QuotaSnapshot?,
    onChooseFiles: () -> Unit,
    onPickFromLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val layout = LocalAppLayout.current

    val credentialNotice: @Composable () -> Unit = {
        // Only reachable when every source has been switched off by hand — artwork
        // itself no longer depends on the user having an account anywhere.
        if (!hasProvider) {
            NoticeBanner(
                text = "No artwork source is switched on. Turn Hasheous back on in Settings, " +
                    "or add your ScreenScraper credentials.",
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AnbernicOrange),
            ) {
                Text("Open Settings", color = AnbernicOrange)
            }
        }
        // Worth knowing before picking hundreds of ROMs, not after.
        QuotaWarningCard(quota = quota, pendingCount = 0)
    }
    val sourceCards: @Composable () -> Unit = {
        PickSourceCard(
            icon = Icons.Rounded.FolderOpen,
            title = "Choose ROM files…",
            description = "Pick one or more files from anywhere on the device.",
            onClick = onChooseFiles,
        )
        PickSourceCard(
            icon = Icons.Rounded.LibraryAddCheck,
            title = "Pick from my library",
            description = "Select ROMs from the folder you configured.",
            onClick = onPickFromLibrary,
        )
    }

    if (layout.isSplit) {
        // Landscape: the two ways in sit on the left, guidance on the right, so the
        // whole start screen fits without scrolling on the handheld.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layout.screenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Choose which ROMs to prepare",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                )
                credentialNotice()
                sourceCards()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = "Nothing prepared yet",
                    description = "Covers are saved as Imgs/<rom name>.jpg next to each ROM, " +
                        "and gamelist.xml is written alongside them when that option is on. " +
                        "Your ROM files are never renamed or modified.",
                )
            }
        }
    } else {
        StackedPane(modifier = Modifier.fillMaxWidth(), horizontalPadding = 16.dp) {
            Spacer(Modifier.height(4.dp))
            credentialNotice()
            Text(
                text = "Choose which ROMs to prepare",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
            sourceCards()
            EmptyState(
                icon = Icons.Rounded.PhotoLibrary,
                title = "Nothing prepared yet",
                description = "Covers are saved as Imgs/<rom name>.jpg next to each ROM, and " +
                    "gamelist.xml is written alongside them when that option is on. " +
                    "Your ROM files are never renamed or modified.",
            )
        }
    }
}

/**
 * Warns that today's ScreenScraper allowance is nearly gone.
 *
 * Stays invisible while there is plenty left, so it only ever appears when it means
 * something. The meter fills with the more urgent of the two allowances, and when a
 * batch is running it says outright whether the remaining lookups can finish it.
 */
@Composable
private fun QuotaWarningCard(
    quota: QuotaSnapshot?,
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    if (quota == null || quota.level == QuotaLevel.Normal) return

    val accent = when (quota.level) {
        QuotaLevel.Warning -> StatusAmber
        else -> StatusRed
    }
    val headline = when (quota.level) {
        QuotaLevel.Exhausted -> "Daily ScreenScraper limit reached"
        QuotaLevel.Critical -> "ScreenScraper quota almost gone"
        else -> "ScreenScraper quota running low"
    }
    val meter by animateFloatAsState(
        targetValue = quota.worstFraction,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "quotaMeter",
    )
    val usageLine = if (quota.isNotFoundLimitLeading) {
        "${formatCount(quota.notFoundUsed ?: 0)} of ${formatCount(quota.notFoundMax ?: 0)} " +
            "unrecognised-ROM lookups used today"
    } else {
        "${formatCount(quota.used)} of ${formatCount(quota.max)} daily lookups used"
    }
    val advice = when {
        quota.level == QuotaLevel.Exhausted ->
            "Artwork can be prepared again when the allowance resets tomorrow. " +
                "Anything unfinished stays in the list and can be retried then."
        pendingCount > quota.remaining ->
            "$pendingCount games are still queued but only ${formatCount(quota.remaining)} " +
                "lookups remain today — the rest can be retried tomorrow."
        else ->
            "${formatCount(quota.remaining)} lookups left today. " +
                "Large batches may not finish in one go."
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Timer,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${(quota.worstFraction * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = accent,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(StatusNeutralDim)
                    .clearAndSetSemantics { contentDescription = usageLine },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(meter)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(accent),
                )
            }
            Text(
                text = usageLine,
                style = MaterialTheme.typography.bodySmall,
                color = TextPrimary,
            )
            Text(
                text = advice,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}

/**
 * The "difficult searches are queued, not stuck" card.
 *
 * Shown only when something is actually queued. While the scan is running it is
 * reassurance — the queue is moving and these are waiting their turn; once it stops
 * it becomes the way in to retrying them.
 */
@Composable
private fun RetryQueueCard(
    queuedCount: Int,
    isRunning: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (queuedCount == 0) return
    val word = if (queuedCount == 1) "search" else "searches"

    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, StatusAmber.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Timer,
                contentDescription = null,
                tint = StatusAmber,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$queuedCount difficult $word queued for retry",
                    style = MaterialTheme.typography.titleSmall,
                    color = StatusAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (isRunning) {
                        "The scan is carrying on with the other games."
                    } else {
                        "Tap to see why, and to try them again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Thousands separators keep five-figure allowances readable at a glance. */
private fun formatCount(value: Int): String = String.format(Locale.getDefault(), "%,d", value)

@Composable
private fun PickSourceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val compact = LocalAppLayout.current.isShort
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = AnbernicOrange,
                modifier = Modifier.size(if (compact) 22.dp else 26.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Batch summary card: one large completion percentage over a bar that is segmented by
 * outcome, so a glance shows both how far the run has come and how it is going.
 */
/**
 * Compact stat row above the progress card: how long the scan has taken (live while
 * running) and what share of the batch ended with saved artwork.
 */
@Composable
private fun RunSummaryCard(
    startedAtMs: Long?,
    endedAtMs: Long?,
    isRunning: Boolean,
    matchedCount: Int,
    totalCount: Int,
) {
    if (startedAtMs == null) return

    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (true) {
                delay(1000)
                now = SystemClock.elapsedRealtime()
            }
        }
    }
    val elapsedMs = ((endedAtMs ?: now) - startedAtMs).coerceAtLeast(0L)
    val matchedPct = if (totalCount == 0) 0 else (matchedCount * 100f / totalCount).roundToInt()
    val layout = LocalAppLayout.current

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = layout.screenPadding),
        shape = RoundedCornerShape(16.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = if (layout.isShort) 9.dp else 14.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SummaryStat(
                icon = Icons.Rounded.Timer,
                label = "Scan duration",
                value = formatDuration(elapsedMs),
                accent = AnbernicOrange,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(if (layout.isShort) 28.dp else 34.dp)
                    .background(HairlineBorder),
            )
            SummaryStat(
                icon = Icons.Rounded.CheckCircle,
                label = "Matched artwork",
                value = "$matchedPct%",
                accent = StatusGreen,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryStat(
    icon: ImageVector,
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val compact = LocalAppLayout.current.isShort
    Row(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "$label: $value" }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 30.dp else 38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(if (compact) 16.dp else 20.dp),
            )
        }
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = value,
                style = if (compact) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
            )
        }
    }
}

/**
 * Plain-text recap of a scan: outcome counts per category plus how long the run took.
 * Zero-count categories besides missing/errors are omitted to keep it short.
 */
private fun buildScanSummary(state: ScrapeState, appName: String): String {
    val missing = state.items.count { it.status == PrepStatus.NotFound }
    val errors = state.items.count { it.status == PrepStatus.ApiError }
    val toChoose = state.items.count { it.status == PrepStatus.ChooseArtwork }
    val duration = state.runStartedAtMs?.let { start ->
        val end = state.runEndedAtMs ?: SystemClock.elapsedRealtime()
        formatDuration((end - start).coerceAtLeast(0L))
    }

    return buildString {
        appendLine("$appName — scan summary")
        appendLine()
        appendLine("Games in batch: ${state.total}")
        appendLine("Successfully matched: ${state.savedCount}")
        // Counted apart from the rest of the skips: "already present" was accurate when
        // it was the only reason a game was passed over, but a switched-off system is a
        // setting the reader owns, and folding it in would report work as done that was
        // never attempted.
        val alreadyPresent = state.skippedCount - state.systemDisabledCount
        if (alreadyPresent > 0) appendLine("Artwork already present: $alreadyPresent")
        if (state.systemDisabledCount > 0) {
            appendLine("Skipped \u2014 system switched off: ${state.systemDisabledCount}")
        }
        appendLine("Missing artwork: $missing")
        appendLine("Errors: $errors")
        // Listed apart from the two above: these are neither missing nor broken, they
        // are simply waiting for someone to pick.
        if (toChoose > 0) appendLine("Awaiting your choice: $toChoose")
        if (state.needsAttentionCount > 0) {
            appendLine("Need manual match: ${state.needsAttentionCount}")
        }
        duration?.let { appendLine("Scan duration: $it") }
    }.trimEnd()
}

/** Opens the system share sheet with the summary as plain text. */
private fun shareScanSummary(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share scan summary"))
}

/** `m:ss`, or `h:mm:ss` for runs longer than an hour. */
private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProgressHeader(
    processed: Int,
    total: Int,
    isRunning: Boolean,
    currentLine: String?,
    savedCount: Int,
    skippedCount: Int,
    attentionCount: Int,
    failedCount: Int,
) {
    val fraction = if (total == 0) 0f else (processed.toFloat() / total).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "prepare-progress",
    )
    // The number is driven by the same animation as the bar so they never disagree.
    val percent = (animatedFraction * 100f).roundToInt()
    val layout = LocalAppLayout.current
    val compact = layout.isShort

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = layout.screenPadding),
        shape = RoundedCornerShape(16.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier.padding(layout.cardPadding),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "$percent",
                            style = if (compact) {
                                MaterialTheme.typography.headlineMedium
                            } else {
                                MaterialTheme.typography.displaySmall
                            },
                            fontWeight = FontWeight.SemiBold,
                            color = if (isRunning) AnbernicOrange else StatusGreen,
                        )
                        Text(
                            text = "%",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(start = 2.dp, bottom = if (compact) 3.dp else 6.dp),
                        )
                    }
                    Text(
                        text = if (isRunning) "Preparing artwork" else "Batch complete",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "$processed / $total",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                )
            }

            BatchProgressBar(
                total = total,
                savedCount = savedCount,
                skippedCount = skippedCount,
                attentionCount = attentionCount,
                failedCount = failedCount,
                isRunning = isRunning,
                percent = percent,
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LegendDot(savedCount, "saved", StatusGreen)
                LegendDot(skippedCount, "skipped", TextSecondary)
                LegendDot(attentionCount, "needs you", StatusAmber)
                LegendDot(failedCount, "failed", StatusRed)
            }

            Text(
                text = currentLine ?: "Tap any amber or red row to fix it by hand.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BatchProgressBar(
    total: Int,
    savedCount: Int,
    skippedCount: Int,
    attentionCount: Int,
    failedCount: Int,
    isRunning: Boolean,
    percent: Int,
    modifier: Modifier = Modifier,
) {
    fun share(count: Int): Float = if (total == 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)

    val spec = tween<Float>(durationMillis = 450, easing = FastOutSlowInEasing)
    val saved by animateFloatAsState(share(savedCount), spec, label = "seg-saved")
    val skipped by animateFloatAsState(share(skippedCount), spec, label = "seg-skipped")
    val attention by animateFloatAsState(share(attentionCount), spec, label = "seg-attention")
    val failed by animateFloatAsState(share(failedCount), spec, label = "seg-failed")

    // A soft breathing tip marks where work is happening right now.
    val transition = rememberInfiniteTransition(label = "prepare-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "prepare-pulse-alpha",
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .clearAndSetSemantics { contentDescription = "$percent percent complete" },
    ) {
        drawRect(color = StatusNeutralDim)

        val gap = 2.dp.toPx()
        val segments = listOf(
            saved to StatusGreen,
            skipped to GraphiteHigh,
            attention to StatusAmber,
            failed to StatusRed,
        )
        var x = 0f
        segments.forEach { (value, color) ->
            val width = size.width * value
            if (width <= 0f) return@forEach
            drawRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(min(width, size.width - x), size.height),
            )
            x += width
            // Hairline notch keeps neighbouring segments readable as separate blocks.
            if (x < size.width) {
                drawRect(
                    color = GraphiteElevated,
                    topLeft = Offset(x - gap / 2f, 0f),
                    size = Size(gap, size.height),
                )
            }
        }

        if (isRunning && x < size.width) {
            drawRect(
                color = AnbernicOrange.copy(alpha = pulse),
                topLeft = Offset(x, 0f),
                size = Size(min(size.width * 0.07f, size.width - x), size.height),
            )
        }
    }
}

@Composable
private fun LegendDot(count: Int, label: String, color: Color) {
    if (count == 0) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape),
        )
        Text(
            text = "$count $label",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
        )
    }
}

/** Statuses worth filtering by after a run: the ones a user acts on or double-checks. */
private val FILTERABLE_STATUSES = listOf(
    PrepStatus.ChooseArtwork,
    PrepStatus.NotFound,
    PrepStatus.ApiError,
    PrepStatus.AlreadyExists,
)

private fun accentFor(status: PrepStatus): Color = when (status.tone) {
    StatusTone.Failure -> StatusRed
    StatusTone.Attention -> StatusAmber
    StatusTone.Neutral -> TextSecondary
    else -> AnbernicOrange
}

/**
 * Horizontal filter chips over the results list. Categories with no games are hidden
 * so a clean run does not show a row of dead chips.
 */
@Composable
private fun StatusFilterRow(
    counts: Map<PrepStatus, Int>,
    allCount: Int,
    selected: PrepStatus?,
    onSelect: (PrepStatus?) -> Unit,
) {
    val available = FILTERABLE_STATUSES.filter { (counts[it] ?: 0) > 0 }
    if (available.isEmpty()) return

    val layout = LocalAppLayout.current
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = if (layout.isShort) 8.dp else 12.dp),
        contentPadding = PaddingValues(horizontal = layout.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            PrepFilterChip(
                label = "All",
                count = allCount,
                accent = AnbernicOrange,
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(available, key = { it.name }) { status ->
            PrepFilterChip(
                label = status.label,
                count = counts[status] ?: 0,
                accent = accentFor(status),
                selected = selected == status,
                onClick = { onSelect(if (selected == status) null else status) },
            )
        }
    }
}

@Composable
private fun PrepFilterChip(
    label: String,
    count: Int,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text("$label · $count") },
        shape = RoundedCornerShape(20.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Graphite,
            labelColor = TextSecondary,
            selectedContainerColor = Graphite,
            selectedLabelColor = accent,
        ),
        border = BorderStroke(1.dp, if (selected) accent else HairlineBorder),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label, $count games" + if (selected) ", selected" else ""
        },
    )
}

/**
 * Title search over the currently filtered and sorted list. Matches on the same
 * display name the list sorts by, so what you type matches what you see.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = {
            Text(
                text = "Search by title",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = GraphiteElevated,
            unfocusedContainerColor = GraphiteElevated,
            focusedBorderColor = AnbernicOrange,
            unfocusedBorderColor = HairlineBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            cursorColor = AnbernicOrange,
        ),
    )
}

/** How the prepared-games list is ordered. */
private enum class PrepSort(val label: String) {
    Name("Game name"),
    ReleaseYear("Release year"),
    Size("File size"),
}

/** Display name used for alphabetical sorting — resolved title, else the best search guess. */
private fun sortName(item: PrepItem): String =
    item.resolvedTitle
        ?: RomNameNormalizer.searchTitle(item.rom.fileName, item.rom.folderChain)

@Composable
private fun SortMenuRow(sort: PrepSort, onSelect: (PrepSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val layout = LocalAppLayout.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = layout.screenPadding - 12.dp),
    ) {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(
                imageVector = Icons.Rounded.Sort,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = sort.label,
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier = Modifier.padding(start = 6.dp),
            )
            Icon(
                imageVector = Icons.Rounded.ArrowDropDown,
                contentDescription = null,
                tint = TextSecondary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = GraphiteElevated,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            PrepSort.entries.forEach { option ->
                val isSelected = option == sort
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (isSelected) TextPrimary else TextSecondary,
                        )
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = AnbernicOrange,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PrepRow(item: PrepItem, onClick: () -> Unit) {
    val interactive = item.status.opensManualSearch
    // Row stays a comfortable target on the handheld even with the tighter padding.
    val verticalPadding = if (LocalAppLayout.current.isShort) 11.dp else 14.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (interactive) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.rom.fileName,
                style = MaterialTheme.typography.bodyLarge,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.resolvedTitle ?: item.message ?: item.rom.system?.displayName ?: "Unknown system",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        StatusChip(status = item.status)
    }
}

@Composable
private fun PrepareBottomBar(
    retryCount: Int,
    retryLabel: String,
    isRunning: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onDone: () -> Unit,
) {
    val layout = LocalAppLayout.current
    val buttonHeight = layout.buttonHeight

    Surface(color = Graphite) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = layout.screenPadding,
                    vertical = if (layout.isShort) 8.dp else 12.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text("Stop", color = TextSecondary, maxLines = 1)
                }
            } else if (retryCount > 0) {
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f).height(buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AnbernicOrange),
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(
                        text = "Retry ${retryLabel.lowercase()} ($retryCount)",
                        color = AnbernicOrange,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Button(
                onClick = onDone,
                modifier = Modifier.weight(1f).height(buttonHeight),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnbernicOrange,
                    contentColor = Ink,
                ),
            ) {
                Text("Done", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        Spacer(Modifier.background(Graphite))
    }
}
