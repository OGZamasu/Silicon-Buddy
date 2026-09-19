package dev.siliconoptimizer.buddy.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.VideoQueueControlRequest
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.SectionCard

/**
 * The render queue on the Mac.
 *
 * Driven by `GET /video/queue` and the `job` events together: the queue knows what the
 * work is and why it failed, the events know how far along it is. The buttons are the
 * Mac's own vocabulary and no more — there is no "cancel" on a clip a node is already
 * rendering, and calling one of these that does not exist would be a button that lied.
 */
@Composable
fun QueueList(
    app: AppState,
    model: MediaViewModel,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        model.control(
                            if (model.queue.paused) {
                                VideoQueueControlRequest.RESUME
                            } else {
                                VideoQueueControlRequest.PAUSE
                            },
                            transport = app.transport,
                        )
                    },
                    enabled = app.canControl,
                ) {
                    Icon(
                        if (model.queue.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = null,
                    )
                    Text(
                        if (model.queue.paused) "Resume" else "Pause",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        model.control(
                            VideoQueueControlRequest.CLEAR_FINISHED,
                            transport = app.transport,
                        )
                    },
                    enabled = app.canControl && model.queue.finished.isNotEmpty(),
                ) { Text("Clear finished") }
            }
            model.queue.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (model.queue.jobs.isEmpty()) {
            item {
                Text(
                    "Nothing in the queue. Anything the Mac starts — a clip, an image, " +
                        "a mesh — shows up here as it runs.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(model.queue.jobs, key = { it.id }) { job ->
            JobCard(
                job = job,
                canControl = app.canControl,
                confirming = confirming == job.id,
                onConfirm = { confirming = if (confirming == job.id) null else job.id },
                onAction = { action ->
                    confirming = null
                    when (action) {
                        VideoQueueControlRequest.RETRY ->
                            model.retry(job.id, job.uncertainSubmission, app.transport)
                        else -> model.control(action, job.id, app.transport)
                    }
                },
            )
        }
    }
}

@Composable
private fun JobCard(
    job: MediaJob,
    canControl: Boolean,
    confirming: Boolean,
    onConfirm: () -> Unit,
    onAction: (String) -> Unit,
) {
    SectionCard(
        title = job.title,
        icon = Icons.Filled.Movie,
        footnote = listOfNotNull(
            job.scene?.let { "scene $it" },
            job.variation?.let { "take $it" },
        ).joinToString(" · ").ifBlank { job.kind },
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                job.state.label,
                filled = job.state != JobState.Queued,
                tint = when (job.state) {
                    JobState.Failed, JobState.Stopped -> MaterialTheme.colorScheme.error
                    JobState.Done -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (job.isActive) Pill("Running now", filled = true)
            if (job.uncertainSubmission) Pill("Handover unconfirmed", filled = true)
        }
        job.prompt?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (job.state.isRunning) {
            if (job.fraction != null) {
                LinearProgressIndicator(
                    progress = { job.fraction.coerceIn(0.0, 1.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        job.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = if (job.error != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        (job.file ?: job.outputDirectory.takeIf { job.state == JobState.Done })?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
            Pill("on the Mac")
        }

        if (!job.isQueued) {
            Text(
                "Started on the Mac itself — the video queue doesn't hold this one, so " +
                    "there is nothing here to pause or retry.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            return@SectionCard
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (job.canStopFollowing) {
                TextButton(
                    onClick = { onAction(VideoQueueControlRequest.STOP_FOLLOWING) },
                    enabled = canControl,
                ) { Text("Stop following") }
            }
            if (job.canRetry) {
                TextButton(
                    onClick = { if (job.uncertainSubmission) onConfirm() else onAction(VideoQueueControlRequest.RETRY) },
                    enabled = canControl,
                ) { Text("Retry") }
            }
            if (job.canRemove) {
                TextButton(
                    onClick = { onAction(VideoQueueControlRequest.REMOVE) },
                    enabled = canControl,
                ) { Text("Remove") }
            }
        }
        if (job.canStopFollowing) {
            Text(
                "Stopping pauses the queue and lets go of this render. The node may " +
                    "still finish it — the Mac won't claim to have cancelled work on " +
                    "another machine.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (confirming) {
            Text(
                "The Mac never saw this one accepted. Retrying may render it twice.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAction(VideoQueueControlRequest.RETRY) }) {
                    Text("Retry anyway")
                }
                TextButton(onClick = onConfirm) { Text("Leave it") }
            }
        }
    }
}
