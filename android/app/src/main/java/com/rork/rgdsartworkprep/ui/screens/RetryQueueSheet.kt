package com.rork.rgdsartworkprep.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rork.rgdsartworkprep.domain.queue.JobState
import com.rork.rgdsartworkprep.domain.queue.QueueJob
import com.rork.rgdsartworkprep.model.SystemCatalog
import com.rork.rgdsartworkprep.ui.layout.LocalAppLayout
import com.rork.rgdsartworkprep.ui.theme.AnbernicOrange
import com.rork.rgdsartworkprep.ui.theme.Graphite
import com.rork.rgdsartworkprep.ui.theme.HairlineBorder
import com.rork.rgdsartworkprep.ui.theme.Ink
import com.rork.rgdsartworkprep.ui.theme.StatusAmber
import com.rork.rgdsartworkprep.ui.theme.StatusRed
import com.rork.rgdsartworkprep.ui.theme.TextPrimary
import com.rork.rgdsartworkprep.ui.theme.TextSecondary

/**
 * The games that were set aside, and why.
 *
 * Deferred work is deliberately shown apart from permanent outcomes: a game waiting
 * on a busy server is not the same as one no database has, and collapsing the two is
 * what made a brief network wobble look like a missing game.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetryQueueSheet(
    jobs: List<QueueJob>,
    isRunning: Boolean,
    onRetryAll: () -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val layout = LocalAppLayout.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Graphite,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Retry queue",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
            )
            Text(
                text = "These games hit a temporary problem, so the scan moved on rather " +
                    "than waiting for them.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            items(jobs, key = { it.id }) { job ->
                RetryQueueRow(job)
                HorizontalDivider(color = HairlineBorder)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onRetryAll,
                enabled = !isRunning && jobs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AnbernicOrange,
                    contentColor = Ink,
                ),
            ) {
                Text(if (isRunning) "Scan still running…" else "Retry queued items")
            }
            OutlinedButton(
                onClick = onClearQueue,
                enabled = jobs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(layout.buttonHeight - 4.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, HairlineBorder),
            ) {
                Text("Clear queue", color = TextSecondary)
            }
            Text(
                text = "Clearing the queue leaves saved artwork untouched — it only stops " +
                    "these games being tried again.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

@Composable
private fun RetryQueueRow(job: QueueJob) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = job.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = systemLabel(job),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = job.deferReason?.label ?: "Waiting",
                style = MaterialTheme.typography.labelMedium,
                // Amber while it will still be tried again, red once it has given up:
                // the difference decides whether the user needs to do anything.
                color = if (job.state == JobState.Failed) StatusRed else StatusAmber,
            )
            Text(
                text = attemptLabel(job.attempts),
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
    }
}

private fun systemLabel(job: QueueJob): String =
    job.systemKey?.let { SystemCatalog.byKey(it)?.displayName ?: it } ?: "Unknown system"

private fun attemptLabel(attempts: Int): String =
    if (attempts == 1) "1 attempt" else "$attempts attempts"
