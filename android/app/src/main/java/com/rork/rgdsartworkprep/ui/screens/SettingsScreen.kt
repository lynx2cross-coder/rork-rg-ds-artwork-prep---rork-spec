package com.rork.rgdsartworkprep.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.data.CrashLog
import com.rork.rgdsartworkprep.model.ProviderId
import com.rork.rgdsartworkprep.network.ScreenScraperClient
import com.rork.rgdsartworkprep.provider.TheGamesDbProvider
import com.rork.rgdsartworkprep.ui.components.InfoPill
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.layout.StackedPane
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<ScreenScraperClient.CredentialCheck?>(null) }
    val diagnostic by AppGraph.screenScraper.lastDiagnostic.collectAsStateWithLifecycle()
    val hasheousDiagnostic by AppGraph.hasheous.lastDiagnostic.collectAsStateWithLifecycle()
    val tgdbDiagnostic by AppGraph.theGamesDb.lastDiagnostic.collectAsStateWithLifecycle()
    val tgdbAllowance by AppGraph.theGamesDb.remainingAllowance.collectAsStateWithLifecycle()
    var tgdbKey by remember(settings.theGamesDbApiKey) {
        mutableStateOf(settings.theGamesDbApiKey)
    }
    var isCheckingKey by remember { mutableStateOf(false) }
    var keyCheck by remember { mutableStateOf<TheGamesDbProvider.KeyCheck?>(null) }
    var lastError by remember { mutableStateOf(CrashLog.last(context)) }

    var devId by remember(settings.devId) { mutableStateOf(settings.devId) }
    var devPassword by remember(settings.devPassword) { mutableStateOf(settings.devPassword) }
    var userId by remember(settings.userId) { mutableStateOf(settings.userId) }
    var userPassword by remember(settings.userPassword) { mutableStateOf(settings.userPassword) }
    var savedNotice by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(AppGraph.matchCache.size) }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        AppGraph.saf.persistTreePermission(uri)
        AppGraph.settings.setLibrary(uri.toString(), AppGraph.saf.describeTree(uri))
        AppGraph.library.clear()
        AppGraph.library.refresh(force = true)
    }

    val treeUri = settings.libraryTreeUri?.let(Uri::parse)
    val writable = treeUri != null && AppGraph.saf.hasWriteAccess(treeUri)

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
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
        val librarySection: @Composable () -> Unit = {
            SectionTitle("ROM library")
            InfoPill(
                icon = Icons.Rounded.Folder,
                text = settings.libraryLabel ?: "No folder selected",
                tint = if (settings.libraryLabel != null) StatusGreen else AnbernicOrange,
            )
            Text(
                text = if (writable) {
                    "Read and write access granted. Artwork is saved straight into the library."
                } else if (treeUri != null) {
                    "Read-only access. Artwork will go to the ready-to-copy export folder instead."
                } else {
                    "Pick the folder that holds your system folders, for example Download/Roms."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Button(
                onClick = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnbernicOrange,
                    contentColor = Ink,
                ),
            ) {
                Text(
                    text = if (settings.libraryTreeUri == null) "Select ROM Library" else "Change folder",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        val sourcesSection: @Composable () -> Unit = {
            SectionTitle("Artwork sources")
            Text(
                text = "Sources are tried in order. Hasheous needs no account and covers most " +
                    "cartridge games, so artwork works without signing up for anything.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            ToggleRow(
                title = "Hasheous (no account needed)",
                description = "Identifies ROMs by checksum and provides the cover. " +
                    "Artwork via ${ProviderId.Hasheous.attribution}.",
                checked = settings.useHasheous,
                onCheckedChange = { AppGraph.settings.setUseHasheous(it) },
            )
            ToggleRow(
                title = "Libretro thumbnails (no account needed)",
                description = "Covers only, matched by game name \u2014 the archive RetroArch " +
                    "itself uses. Tried last, to find a picture for a game another source " +
                    "recognised but had no artwork for.",
                checked = settings.useLibretroThumbnails,
                onCheckedChange = { AppGraph.settings.setUseLibretroThumbnails(it) },
            )
            Text(
                text = if (settings.hasScreenScraperCredentials) {
                    "ScreenScraper is set up and will be tried for anything Hasheous cannot " +
                        "identify \u2014 including disc-based games, which are matched by name."
                } else {
                    "ScreenScraper is optional. Add credentials below to also cover disc-based " +
                        "games and anything Hasheous does not recognise."
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            // Shown only after something actually went wrong, so a healthy setup stays
            // uncluttered. It carries the real status and the service's own words.
            hasheousDiagnostic?.let { detail ->
                ConnectionDiagnosticCard(
                    report = detail.asReport(),
                    onShare = { shareReport(context, detail.asReport()) },
                )
            }
        }
        val theGamesDbSection: @Composable () -> Unit = {
            SectionTitle("TheGamesDB (optional)")
            Text(
                text = "Not required. A free API key from thegamesdb.net adds a second " +
                    "checksum lookup and a name search, which is what covers disc-based " +
                    "games. The key has a monthly request allowance, shown once it is used.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            CredentialField("API key", tgdbKey, isSecret = true) {
                tgdbKey = it
                keyCheck = null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AppGraph.settings.setTheGamesDbApiKey(tgdbKey)
                        savedNotice = true
                    },
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GraphiteElevated,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text("Save key", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // A rejected key looks exactly like a database miss during a scan, so
                // it is worth one deliberate call to tell them apart up front.
                OutlinedButton(
                    onClick = {
                        AppGraph.settings.setTheGamesDbApiKey(tgdbKey)
                        savedNotice = true
                        isCheckingKey = true
                        keyCheck = null
                        scope.launch {
                            keyCheck = AppGraph.theGamesDb.checkKey(tgdbKey)
                            isCheckingKey = false
                        }
                    },
                    enabled = !isCheckingKey && tgdbKey.isNotBlank(),
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AnbernicOrange),
                ) {
                    Text(
                        text = if (isCheckingKey) "Testing\u2026" else "Test key",
                        color = AnbernicOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (val result = keyCheck) {
                is TheGamesDbProvider.KeyCheck.Ok -> Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusGreen,
                )
                is TheGamesDbProvider.KeyCheck.Problem -> {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusRed,
                    )
                    tgdbDiagnostic?.let { detail ->
                        ConnectionDiagnosticCard(
                            report = detail.asReport(),
                            onShare = { shareReport(context, detail.asReport()) },
                        )
                    }
                }
                null -> tgdbAllowance?.let { remaining ->
                    Text(
                        text = "$remaining requests left this month.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remaining > 0) TextSecondary else StatusRed,
                    )
                }
            }
        }
        val credentialsSection: @Composable () -> Unit = {
            SectionTitle("ScreenScraper (optional)")
            Text(
                text = "Not required. This app ships with no credentials \u2014 register at " +
                    "screenscraper.fr and enter your own developer keys; adding your member " +
                    "account raises the daily quota.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            CredentialField("Developer ID", devId) { devId = it; checkResult = null }
            CredentialField("Developer password", devPassword, isSecret = true) {
                devPassword = it
                checkResult = null
            }
            CredentialField("Member username (optional)", userId) { userId = it; checkResult = null }
            CredentialField("Member password (optional)", userPassword, isSecret = true) {
                userPassword = it
                checkResult = null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        AppGraph.settings.setCredentials(devId, devPassword, userId, userPassword)
                        savedNotice = true
                    },
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GraphiteElevated,
                        contentColor = TextPrimary,
                    ),
                ) {
                    Text(
                        text = if (savedNotice) "Saved" else "Save",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Checking here means a wrong password is found in one call, instead of
                // failing halfway through a batch of hundreds of ROMs.
                OutlinedButton(
                    onClick = {
                        AppGraph.settings.setCredentials(devId, devPassword, userId, userPassword)
                        savedNotice = true
                        isChecking = true
                        checkResult = null
                        scope.launch {
                            checkResult = AppGraph.screenScraper.verifyCredentials(AppGraph.settings.current)
                            isChecking = false
                        }
                    },
                    enabled = !isChecking && devId.isNotBlank() && devPassword.isNotBlank(),
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, AnbernicOrange),
                ) {
                    Text(
                        text = if (isChecking) "Testing…" else "Test connection",
                        color = AnbernicOrange,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            when (val result = checkResult) {
                is ScreenScraperClient.CredentialCheck.Ok -> Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusGreen,
                )
                is ScreenScraperClient.CredentialCheck.Problem -> {
                    Text(
                        text = result.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusRed,
                    )
                    // ScreenScraper's own wording cannot tell a wrong password from a
                    // credential type mix-up, so the app supplies what to try next.
                    result.hint?.let { hint ->
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    diagnostic?.let { detail ->
                        ConnectionDiagnosticCard(
                            report = detail.asReport(),
                            onShare = { shareReport(context, detail.asReport()) },
                        )
                    }
                }
                null -> Unit
            }
        }
        val regionSection: @Composable () -> Unit = {
            SectionTitle("Preferred cover region")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScreenScraperClient.regionOptions.forEach { (code, label) ->
                    FilterChip(
                        selected = settings.preferredRegion == code,
                        onClick = { AppGraph.settings.setPreferredRegion(code) },
                        label = { Text(label) },
                        shape = RoundedCornerShape(20.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Graphite,
                            labelColor = TextSecondary,
                            selectedContainerColor = Graphite,
                            selectedLabelColor = AnbernicOrange,
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (settings.preferredRegion == code) AnbernicOrange else HairlineBorder,
                        ),
                    )
                }
            }
        }
        val outputSection: @Composable () -> Unit = {
            SectionTitle("Game list")
            ToggleRow(
                title = "Generate gamelist.xml",
                description = "After each run, write a standard EmulationStation gamelist.xml into " +
                    "every system folder with the game name, description, developer, genre, players, " +
                    "release date and cover path. Existing entries and favourites are merged, never lost.",
                checked = settings.generateGamelist,
                onCheckedChange = { AppGraph.settings.setGenerateGamelist(it) },
            )

            SectionTitle("Export fallback")
            ToggleRow(
                title = "Always export instead of writing",
                description = "Covers and game lists go to ${AppGraph.saf.exportRootDisplayPath()} " +
                    "so you can copy them with the Files app.",
                checked = settings.forceExportFallback,
                onCheckedChange = { AppGraph.settings.setForceExportFallback(it) },
            )
        }
        val maintenanceSection: @Composable () -> Unit = {
            SectionTitle("Remembered matches")
            Text(
                text = "$cacheSize ROM identities are mapped to a game, so they are never searched twice.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            OutlinedButton(
                onClick = {
                    AppGraph.matchCache.clear()
                    cacheSize = 0
                },
                enabled = cacheSize > 0,
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 4.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineBorder),
            ) {
                Text("Clear remembered matches", color = TextSecondary)
            }

            SectionTitle("Diagnostics")
            Text(
                text = "Records how long each game and each source took, so a slow or failed " +
                    "search can be explained — and shared — without needing a computer.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            OutlinedButton(
                onClick = onOpenDiagnostics,
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 4.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineBorder),
            ) {
                Text("Open diagnostics", color = TextSecondary)
            }

            SectionTitle("Safety")
            Text(
                text = "This app only reads your ROMs and writes cover images. It never renames, " +
                    "moves, deletes or modifies ROMs, saves, emulators or firmware, and it never " +
                    "needs root.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )

            // Only appears when something actually went wrong, so it can be reported
            // instead of described.
            lastError?.let { report ->
                SectionTitle("Last error")
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GraphiteElevated,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = report,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { shareReport(context, report) },
                        modifier = Modifier.weight(1f).height(layout.buttonHeight - 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, HairlineBorder),
                    ) {
                        Text("Share report", color = TextSecondary, maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = {
                            CrashLog.clear(context)
                            lastError = null
                        },
                        modifier = Modifier.weight(1f).height(layout.buttonHeight - 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, HairlineBorder),
                    ) {
                        Text("Dismiss", color = TextSecondary, maxLines = 1)
                    }
                }
            }
        }

        if (layout.isSplit) {
            // Landscape: setup on the left, output and housekeeping on the right, each
            // column scrolling on its own so nothing needs a long reach down the page.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = layout.screenPadding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    librarySection()
                    sourcesSection()
                    theGamesDbSection()
                    credentialsSection()
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    regionSection()
                    outputSection()
                    maintenanceSection()
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else {
            StackedPane(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalPadding = 16.dp,
            ) {
                librarySection()
                sourcesSection()
                theGamesDbSection()
                credentialsSection()
                regionSection()
                outputSection()
                maintenanceSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The verbatim exchange with ScreenScraper, shown when a connection test fails.
 *
 * ScreenScraper replies to a missing parameter with the same sentence it uses for a
 * wrong password, so the status code and the fields that were actually sent are the
 * only way to tell the two apart. Credential values never appear here — only their
 * names and lengths — so the report is safe to share as-is.
 */
@Composable
private fun ConnectionDiagnosticCard(report: String, onShare: () -> Unit) {
    val layout = LocalAppLayout.current
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Diagnostic details",
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
            )
            Text(
                text = report,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
            )
            Text(
                text = "No passwords are included — only field names and their lengths.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
            OutlinedButton(
                onClick = onShare,
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 4.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineBorder),
            ) {
                Text("Share diagnostic", color = TextSecondary, maxLines = 1)
            }
        }
    }
}

/** Sends the recorded failure out as plain text so it can be reported. */
private fun shareReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_SUBJECT,
            "${context.getString(R.string.app_name)} \u2014 error report",
        )
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "Share error report"))
}

/** Shared with the Diagnostics screen so both settings surfaces look identical. */
@Composable
internal fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val layout = LocalAppLayout.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(layout.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                Text(description, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink,
                    checkedTrackColor = AnbernicOrange,
                    uncheckedTrackColor = Graphite,
                    uncheckedBorderColor = HairlineBorder,
                ),
            )
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.padding(top = if (LocalAppLayout.current.isShort) 8.dp else 14.dp),
        style = MaterialTheme.typography.labelMedium,
        color = AnbernicOrange,
    )
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    isSecret: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
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
