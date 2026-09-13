package com.rork.rgdsartworkprep.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.rork.rgdsartworkprep.model.PrepStatus
import com.rork.rgdsartworkprep.model.StatusTone
import com.rork.rgdsartworkprep.model.label
import com.rork.rgdsartworkprep.model.tone
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrangeDim
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
import com.rork.rgdsartworkprep.ui.theme.StatusAmberDim
import com.rork.rgdsartworkprep.ui.theme.StatusGreen
import com.rork.rgdsartworkprep.ui.theme.StatusGreenDim
import com.rork.rgdsartworkprep.ui.theme.StatusNeutralDim
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.StatusRedDim
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

private data class ChipStyle(val icon: ImageVector, val content: Color, val container: Color)

/**
 * Colour comes from the status' [tone] so that the palette is decided in one place and
 * can be asserted in a unit test; the icon stays per-status because it carries the
 * specific meaning the colour only categorises.
 */
private fun styleFor(status: PrepStatus): ChipStyle {
    val content: Color
    val container: Color
    when (status.tone) {
        StatusTone.Neutral -> { content = TextSecondary; container = StatusNeutralDim }
        StatusTone.Progress -> { content = AnbernicOrange; container = AnbernicOrangeDim }
        StatusTone.Positive -> { content = StatusGreen; container = StatusGreenDim }
        StatusTone.Attention -> { content = StatusAmber; container = StatusAmberDim }
        StatusTone.Failure -> { content = StatusRed; container = StatusRedDim }
    }
    return ChipStyle(iconFor(status), content, container)
}

private fun iconFor(status: PrepStatus): ImageVector = when (status) {
    PrepStatus.Pending -> Icons.Rounded.HourglassEmpty
    PrepStatus.Working -> Icons.Rounded.Sync
    PrepStatus.Downloaded -> Icons.Rounded.CheckCircle
    PrepStatus.Exported -> Icons.Rounded.CloudDownload
    PrepStatus.AlreadyExists -> Icons.Rounded.RemoveCircleOutline
    PrepStatus.MultipleMatches -> Icons.Rounded.WarningAmber
    // An invitation to act, not a warning: this row is waiting on a tap.
    PrepStatus.ChooseArtwork -> Icons.Rounded.TouchApp
    PrepStatus.NotFound -> Icons.Rounded.Cancel
    PrepStatus.ApiError -> Icons.Rounded.ErrorOutline
    PrepStatus.Unsupported -> Icons.Rounded.HelpOutline
}

/** Compact colour-coded status pill used in every preparation list row. */
@Composable
fun StatusChip(status: PrepStatus, modifier: Modifier = Modifier) {
    val style = styleFor(status)
    val text = status.label
    Row(
        modifier = modifier
            .background(style.container, RoundedCornerShape(10.dp))
            .padding(PaddingValues(horizontal = 10.dp, vertical = 7.dp))
            .clearAndSetSemantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = null,
            tint = style.content,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            color = style.content,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        )
    }
}
