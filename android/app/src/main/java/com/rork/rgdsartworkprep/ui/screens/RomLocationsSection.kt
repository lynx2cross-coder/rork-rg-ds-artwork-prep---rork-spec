package com.rork.rgdsartworkprep.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rork.rgdsartworkprep.AppGraph
import com.rork.rgdsartworkprep.data.AddLocationResult
import com.rork.rgdsartworkprep.data.LocationAccess
import com.rork.rgdsartworkprep.data.RomLocation
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

/**
 * Settings -> ROM Locations: every saved ROM root folder, whether each can be reached,
 * and the one way to add another.
 *
 * Adding never replaces: the picker's folder is appended to the list. Removing only
 * forgets the folder inside the app \u2014 nothing in it is touched.
 */
@Composable
internal fun RomLocationsSection() {
    val locations by AppGraph.romLocations.locations.collectAsStateWithLifecycle()
    val library by AppGraph.library.state.collectAsStateWithLifecycle()
    val layout = LocalAppLayout.current
    val haptics = LocalHapticFeedback.current

    var statuses by remember { mutableStateOf<Map<String, LocationAccess>>(emptyMap()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<RomLocation?>(null) }

    // Re-checked whenever the list changes and after every library scan, so a card
    // put back in or a permission granted again shows up without leaving the screen.
    LaunchedEffect(locations, library.scan.scannedAtMillis) {
        statuses = locations.associate { it.key to AppGraph.saf.locationAccess(it) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // The grant is persisted for exactly this folder. Internal storage and an SD
        // card each need their own; one never stands in for another.
        val granted = AppGraph.saf.persistTreePermission(uri)
        val label = com.rork.rgdsartworkprep.data.RomLocationLabel.of(uri.toString()).full
        notice = when (val result = AppGraph.romLocations.add(uri.toString())) {
            is AddLocationResult.Added -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                AppGraph.library.refresh(force = true)
                if (granted) null else "Android did not grant lasting access to $label. It may need to be added again after a restart."
            }
            is AddLocationResult.AlreadySaved -> {
                val wasUnavailable = statuses[result.existing.key]?.isScannable == false
                if (wasUnavailable && granted) {
                    AppGraph.library.refresh(force = true)
                    "$label is already in your ROM Locations \u2014 its folder access has been restored."
                } else {
                    "$label is already in your ROM Locations."
                }
            }
            AddLocationResult.Invalid -> "That is not a folder ROM Art Prep can scan. Pick a folder instead."
        }
    }

    SectionTitle("ROM Locations")
    Text(
        text = "Every folder here is scanned. Add your internal storage and SD card folders " +
            "separately \u2014 each keeps its own access, and artwork is always saved next to " +
            "the ROM it belongs to.",
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
    )

    if (locations.isEmpty()) {
        Text(
            text = "No ROM location yet. Add the folder that holds your system folders, " +
                "for example /Roms.",
            style = MaterialTheme.typography.bodySmall,
            color = AnbernicOrange,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            locations.forEach { location ->
                RomLocationRow(
                    location = location,
                    access = statuses[location.key],
                    onRemove = { pendingRemoval = location },
                )
            }
        }
    }

    notice?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall, color = AnbernicOrange)
    }

    Button(
        onClick = {
            notice = null
            folderPicker.launch(null)
        },
        modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AnbernicOrange, contentColor = Ink),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = "Add ROM Location",
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }

    pendingRemoval?.let { location ->
        val label = location.label.full
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            containerColor = GraphiteElevated,
            title = { Text("Remove this location?", color = TextPrimary) },
            text = {
                Text(
                    text = "$label will no longer be scanned. Nothing in the folder is deleted " +
                        "or changed \u2014 your ROMs, artwork and gamelist.xml stay exactly where " +
                        "they are, and your other locations are not affected. You can add it " +
                        "again at any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRemoval = null
                        if (AppGraph.romLocations.remove(location.treeUri)) {
                            AppGraph.saf.releaseTreePermission(Uri.parse(location.treeUri))
                            AppGraph.library.refresh(force = true)
                            notice = null
                        }
                    },
                ) { Text("Remove", color = AnbernicOrange) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel", color = TextSecondary) }
            },
        )
    }
}

@Composable
private fun RomLocationRow(location: RomLocation, access: LocationAccess?, onRemove: () -> Unit) {
    val label = location.label
    val usable = access?.isScannable ?: true
    val accent = when {
        access == null -> TextSecondary
        usable -> StatusGreen
        else -> StatusAmber
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, if (usable) HairlineBorder else StatusAmber.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = if (label.volume == "SD card") Icons.Rounded.SdCard else Icons.Rounded.Smartphone,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = label.volume,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                )
                Text(
                    text = label.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (access != null) {
                        Icon(
                            imageVector = if (usable) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Text(
                        text = access?.description ?: "Checking\u2026",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                    )
                }
                if (access != null && !usable) {
                    Text(
                        text = "Remove it, or add the same folder again to restore access.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove ${label.full} from ROM Locations",
                    tint = TextSecondary,
                )
            }
        }
    }
}
