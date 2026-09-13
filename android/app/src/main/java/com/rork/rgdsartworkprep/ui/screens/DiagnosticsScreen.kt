package com.rork.rgdsartworkprep.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.R
import com.rork.rgdsartworkprep.domain.diagnostics.DiagnosticExporter
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

/**
 * Where a user can turn on detailed recording and send the result back.
 *
 * The whole point is that this works with nothing but the handheld: no ADB, no
 * developer options, no computer. The report is built in memory and handed to
 * Android's own share sheet as plain text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val settings by AppGraph.settings.settings.collectAsStateWithLifecycle()
    val state by AppGraph.scraper.state.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val context = LocalContext.current

    var preview by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Graphite,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.titleLarge) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = layout.screenPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle("Detailed scan diagnostics")
            ToggleRow(
                title = "Record provider timing",
                description = "Records detailed scan and provider timing information to help " +
                    "diagnose slow or failed artwork searches. Kept inside the app — nothing " +
                    "is sent anywhere unless you share it.",
                checked = settings.detailedDiagnostics,
                onCheckedChange = { enabled ->
                    AppGraph.settings.setDetailedDiagnostics(enabled)
                    AppGraph.scanDiagnostics.isDetailedEnabled = enabled
                },
            )

            SectionTitle("Privacy")
            ToggleRow(
                title = "Include ROM filenames in report",
                description = "Off by default. With this off your games are listed as " +
                    "\u201cGame 1\u201d, \u201cGame 2\u201d and so on — the timings are still there.",
                checked = settings.includeFileNamesInReport,
                onCheckedChange = { AppGraph.settings.setIncludeFileNamesInReport(it) },
            )

            SectionTitle("Report")
            Text(
                text = "The report covers the most recent scan: how long each game took, " +
                    "every source tried, and why anything was queued for retry.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (state.items.isEmpty()) {
                Text(
                    text = "No scan has run yet, so there is nothing to report.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AnbernicOrange,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val report = buildReport(context)
                        preview = report
                        shareDiagnosticReport(context, report)
                    },
                    enabled = state.items.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AnbernicOrange,
                        contentColor = Ink,
                    ),
                ) {
                    Text("Export report", maxLines = 1)
                }
                OutlinedButton(
                    onClick = { preview = buildReport(context) },
                    enabled = state.items.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(layout.buttonHeight),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, HairlineBorder),
                ) {
                    Text("Preview", color = TextSecondary, maxLines = 1)
                }
            }
            Text(
                text = "Never included: ROM contents, artwork, passwords, API keys or " +
                    "Android system logs.",
                style = MaterialTheme.typography.labelSmall,
                color = StatusGreen,
            )

            preview?.let { report ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = GraphiteElevated,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = report,
                        modifier = Modifier.padding(12.dp).heightIn(max = 360.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun buildReport(context: Context): String =
    DiagnosticExporter(context, AppGraph.scanDiagnostics)
        .export(AppGraph.scraper.state.value, AppGraph.settings.current)

/** Hands the report to Android's share sheet — no computer or ADB involved. */
private fun shareDiagnosticReport(context: Context, report: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(
            Intent.EXTRA_SUBJECT,
            "${context.getString(R.string.app_name)} \u2014 scan diagnostic report",
        )
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(Intent.createChooser(intent, "Share diagnostic report"))
}
