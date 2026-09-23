package dev.siliconoptimizer.buddy.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
 * Mac's own vocabulary and no more. "Cancel render" is there only on a clip the Mac
 * marks `canCancel` — its node said it can stop that one job — and everywhere else Stop
 * following is all there is: a button that claimed to stop a render a node will finish
 * anyway would be a button that lied.
 */
/** Something destructive, waiting to be meant. */
private data class Pending(val jobID: String?, val action: String)

@Composable
fun QueueList(
    app: AppState,
    model: MediaViewModel,
    notifier: MediaNotifier? = null,
    modifier: Modifier = Modifier,
) {
    // Removing a take and clearing the finished ones both throw away work the Mac
    // will not make again, and a queue is a list of small buttons next to each other.
    var confirming by remember { mutableStateOf<Pending?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

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
                            notifier = notifier,
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
                        confirming = Pending(null, VideoQueueControlRequest.CLEAR_FINISHED)
                    },
                    enabled = app.canControl && model.queue.finished.isNotEmpty(),
                ) { Text("Clear finished") }
            }
            if (confirming?.action == VideoQueueControlRequest.CLEAR_FINISHED) {
                Text(
                    "This takes ${model.queue.finished.size} finished " +
                        (if (model.queue.finished.size == 1) "item" else "items") +
                        " off the Mac's queue. The files it already wrote stay where " +
                        "they are.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        confirming = null
                        model.control(
                            VideoQueueControlRequest.CLEAR_FINISHED,
                            transport = app.transport,
                            notifier = notifier,
                        )
                    }) { Text("Clear them") }
                    TextButton(onClick = { confirming = null }) { Text("Keep them") }
                }
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
                cancelling = job.id in model.cancelling,
                saving = model.saving,
                onSave = { id ->
                    model.save(context, app.transport, id, job.kind, job.title, job.file)
                },
                confirming = confirming?.takeIf { it.jobID == job.id }?.action,
                onAsk = { action ->
                    confirming = if (confirming?.jobID == job.id && confirming?.action == action) {
                        null
                    } else {
                        Pending(job.id, action)
                    }
                },
                onAction = { action, warned ->
                    confirming = null
                    when (action) {
                        // `confirmNewRender` is the Mac asking whether this was meant:
                        // it is true because somebody read the warning and tapped
                        // again, never because the app filled it in for them.
                        VideoQueueControlRequest.RETRY ->
                            model.retry(job.id, warned, app.transport, notifier)
                        VideoQueueControlRequest.CANCEL ->
                            model.cancelRender(job.id, app.transport, notifier)
                        else -> model.control(action, job.id, app.transport, notifier)
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobCard(
    job: MediaJob,
    canControl: Boolean,
    /** A cancel for this clip is on its way to the Mac. */
    cancelling: Boolean,
    saving: String?,
    onSave: (String) -> Unit,
    /** The action this card is currently asking about, if any. */
    confirming: String?,
    onAsk: (String) -> Unit,
    onAction: (action: String, warned: Boolean) -> Unit,
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
        // How a cancel went, from the Mac's record on the clip — or that one is on its way.
        (if (cancelling) CancelState.Sending else job.cancel)?.let { cancel ->
            Text(
                cancel.note,
                style = MaterialTheme.typography.labelSmall,
                color = when (cancel) {
                    CancelState.Unsupported, CancelState.Unknown -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            job.cancelDetail?.takeIf { !cancelling }?.let {
                Text(
                    "The node: $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        (job.file ?: job.outputDirectory.takeIf { job.state == JobState.Done })?.let {
            Text(it, style = MaterialTheme.typography.labelSmall)
        }
        if (job.state == JobState.Done && job.mediaID != null) {
            if (canControl) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onSave(job.mediaID) },
                        enabled = saving == null,
                    ) { Text("Save to Photos") }
                    if (saving == job.mediaID) {
                        Text(
                            "Copying…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Pill("on the Mac — a chat-only device may not pull renders")
            }
        } else if (job.state == JobState.Done) {
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

        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (job.canStopFollowing) {
                TextButton(
                    onClick = { onAction(VideoQueueControlRequest.STOP_FOLLOWING, false) },
                    enabled = canControl,
                ) { Text("Stop following") }
            }
            if (job.canRetry) {
                TextButton(
                    onClick = {
                        // A clip the Mac never saw accepted may render twice. That is
                        // worth reading before it happens.
                        if (job.uncertainSubmission) {
                            onAsk(VideoQueueControlRequest.RETRY)
                        } else {
                            onAction(VideoQueueControlRequest.RETRY, false)
                        }
                    },
                    enabled = canControl,
                ) { Text("Retry") }
            }
            if (job.canRemove) {
                TextButton(
                    onClick = { onAsk(VideoQueueControlRequest.REMOVE) },
                    enabled = canControl,
                ) { Text("Remove") }
            }
            // Not merely disabled for a chat-only device, as the others are: it is not a
            // button that device has.
            if (job.offersCancelRender(canControl)) {
                TextButton(
                    onClick = { onAsk(VideoQueueControlRequest.CANCEL) },
                    enabled = !cancelling,
                ) { Text("Cancel render") }
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

        when (confirming) {
            VideoQueueControlRequest.RETRY -> {
                Text(
                    "The Mac never saw this one accepted. Retrying may render it twice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAction(VideoQueueControlRequest.RETRY, true) }) {
                        Text("Retry anyway")
                    }
                    TextButton(onClick = { onAsk(VideoQueueControlRequest.RETRY) }) {
                        Text("Leave it")
                    }
                }
            }
            // Only while the Mac still offers it: a clip that finished while this was
            // being read has nothing left to cancel.
            VideoQueueControlRequest.CANCEL -> if (job.offersCancelRender(canControl)) {
                Text(
                    "This asks the node to stop the render. The GPU work it has done is " +
                        "thrown away and nothing is published for this take; rendering it " +
                        "again starts from the beginning.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAction(VideoQueueControlRequest.CANCEL, true) }) {
                        Text("Cancel it")
                    }
                    TextButton(onClick = { onAsk(VideoQueueControlRequest.CANCEL) }) {
                        Text("Keep rendering")
                    }
                }
            }
            VideoQueueControlRequest.REMOVE -> {
                Text(
                    if (job.state == JobState.Done) {
                        "This takes the take off the queue. The file it wrote stays on " +
                            "the Mac."
                    } else {
                        "This take will not be rendered."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onAction(VideoQueueControlRequest.REMOVE, true) }) {
                        Text("Remove it")
                    }
                    TextButton(onClick = { onAsk(VideoQueueControlRequest.REMOVE) }) {
                        Text("Keep it")
                    }
                }
            }
            else -> Unit
        }
    }
}
