package com.rork.rgdsartworkprep.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrangeDim
import com.rork.rgdsartworkprep.ui.theme.GraphiteElevated
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusNeutralDim
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

/**
 * Large stacked action row used on the home screen. On the short landscape handheld
 * screen it tightens to a single line of text with a smaller glyph, while keeping the
 * whole card well above the 44dp touch target.
 */
@Composable
fun ActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    highlighted: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalAppLayout.current
    val compact = layout.isShort

    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = if (compact) 64.dp else 88.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = GraphiteElevated,
            disabledContainerColor = GraphiteElevated,
        ),
        border = BorderStroke(1.dp, if (highlighted) AnbernicOrange else HairlineBorder),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 40.dp else 48.dp)
                    .background(
                        if (highlighted) AnbernicOrange else StatusNeutralDim,
                        RoundedCornerShape(12.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (highlighted) Ink else TextSecondary,
                    modifier = Modifier.size(if (compact) 22.dp else 26.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = if (compact) {
                        MaterialTheme.typography.titleMedium
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    color = if (enabled) TextPrimary else TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = if (highlighted) AnbernicOrange else TextSecondary,
                modifier = Modifier.size(if (compact) 22.dp else 28.dp),
            )
        }
    }
}

/** One big number with a caption, used for the library scan summary. */
@Composable
fun StatTile(value: String, caption: String, valueColor: Color, modifier: Modifier = Modifier) {
    val compact = LocalAppLayout.current.isShort
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = GraphiteElevated,
        border = BorderStroke(1.dp, HairlineBorder),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (compact) 10.dp else 16.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = value,
                style = if (compact) {
                    MaterialTheme.typography.headlineSmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = valueColor,
            )
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** Footer pill summarising the selected library. */
@Composable
fun InfoPill(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary,
    onClick: (() -> Unit)? = null,
) {
    val base = Modifier
        .fillMaxWidth()
        .background(GraphiteElevated, RoundedCornerShape(28.dp))
    val interactive = if (onClick != null) base.clickable(onClick = onClick) else base
    val compact = LocalAppLayout.current.isShort
    Row(
        modifier = interactive
            .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 14.dp)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Centred empty / guidance state. Trims its generous padding on short screens. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val compact = LocalAppLayout.current.isShort
    // Deliberately not scrollable: this is often placed inside a scrolling parent,
    // and the compact padding keeps it within a short landscape viewport.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = if (compact) 16.dp else 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 44.dp else 64.dp)
                .background(GraphiteElevated, RoundedCornerShape(if (compact) 14.dp else 20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(if (compact) 22.dp else 30.dp),
            )
        }
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

/** Inline banner for provider / permission problems. */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier, accent: Color = AnbernicOrange) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = AnbernicOrangeDim,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = accent,
        )
    }
}
