package com.rork.rgdsartworkprep.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A vertically scrolling single-column pane — the portrait counterpart to the
 * side-by-side split.
 *
 * On phones and the handheld this is exactly a scrolling [Column]: the width cap is
 * unspecified, so nothing is constrained and the layout is unchanged. On a tablet the
 * content is capped and centred, because a full-bleed 1200dp-wide button looks broken
 * rather than generous.
 */
@Composable
fun StackedPane(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
    spacing: Dp = 12.dp,
    maxWidth: Dp = LocalAppLayout.current.contentMaxWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = maxWidth)
                .padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}
