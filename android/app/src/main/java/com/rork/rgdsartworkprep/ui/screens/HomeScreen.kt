package com.rork.rgdsartworkprep.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.ui.components.ActionCard
import com.rork.rgdsartworkprep.ui.components.InfoPill
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.layout.StackedPane
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

@Composable
fun HomeScreen(
    onPrepare: () -> Unit,
    onScanLibrary: () -> Unit,
    onArtwork: () -> Unit,
    onSettings: () -> Unit,
) {
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val library by AppGraph.library.state.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current

    LaunchedEffect(settings.libraryTreeUri) {
        if (settings.libraryTreeUri != null) AppGraph.library.refresh()
    }

    val hasLibrary = settings.libraryTreeUri != null
    val summary = when {
        !hasLibrary -> "No ROM library selected — open Settings to choose one"
        library.isScanning -> "Scanning ${settings.libraryLabel ?: "library"}…"
        library.error != null -> library.error.orEmpty()
        library.hasScanned -> buildString {
            append(settings.libraryLabel ?: "Selected folder")
            append(" • ${library.scan.total} ROMs")
            append(" • ${library.scan.missingArtwork} missing artwork")
        }
        else -> settings.libraryLabel ?: "Selected folder"
    }

    Scaffold { innerPadding ->
        if (layout.isSplit) {
            // Landscape handheld: masthead and library status on the left, the four
            // destinations stacked on the right where the thumb naturally rests.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = layout.screenPadding, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                        )
                    }
                    InfoPill(
                        icon = Icons.Rounded.Folder,
                        text = summary,
                        tint = if (hasLibrary) TextSecondary else AnbernicOrange,
                        onClick = if (hasLibrary) null else onSettings,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HomeDestinations(onPrepare, onScanLibrary, onArtwork, onSettings)
                }
            }
        } else {
            StackedPane(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                horizontalPadding = 20.dp,
                spacing = 14.dp,
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    // Wraps rather than clips if the name ever grows or the user has
                    // a large display size set.
                    style = MaterialTheme.typography.displaySmall,
                    color = TextPrimary,
                )
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )

                HomeDestinations(onPrepare, onScanLibrary, onArtwork, onSettings)

                Spacer(Modifier.height(10.dp))
                InfoPill(
                    icon = Icons.Rounded.Folder,
                    text = summary,
                    tint = if (hasLibrary) TextSecondary else AnbernicOrange,
                    onClick = if (hasLibrary) null else onSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun HomeDestinations(
    onPrepare: () -> Unit,
    onScanLibrary: () -> Unit,
    onArtwork: () -> Unit,
    onSettings: () -> Unit,
) {
    ActionCard(
        icon = Icons.Rounded.CreateNewFolder,
        title = "Prepare New Games",
        description = "Select new ROMs and prepare artwork.",
        highlighted = true,
        onClick = onPrepare,
    )
    ActionCard(
        icon = Icons.Rounded.Search,
        title = "Scan Existing Library",
        description = "Find ROMs missing artwork.",
        highlighted = false,
        onClick = onScanLibrary,
    )
    ActionCard(
        icon = Icons.Rounded.Image,
        title = "Artwork",
        description = "View prepared artwork.",
        highlighted = false,
        onClick = onArtwork,
    )
    ActionCard(
        icon = Icons.Rounded.Settings,
        title = "Settings",
        description = "Configure ROM folder and credentials.",
        highlighted = false,
        onClick = onSettings,
    )
}
