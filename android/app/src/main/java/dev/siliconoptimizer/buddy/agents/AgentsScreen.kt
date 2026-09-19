package dev.siliconoptimizer.buddy.agents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.AgentEngines
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import dev.siliconoptimizer.buddy.ui.NotificationsOffNotice
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.SectionCard

/**
 * The Agents tab: one card per engine the Mac runs.
 *
 * These are the Codex and Pi sessions in the Mac's own Chat tab — the same thread, not a
 * copy — so the card says what that session is doing right now, what it will answer with,
 * and whether anything is waiting for a person. Starting one is harmless and immediate;
 * stopping one or starting a new thread throws away something on the Mac, and asks first.
 */
@Composable
fun AgentsScreen(
    app: AppState,
    model: AgentsViewModel,
    onOpen: (String) -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<Confirm?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        // An agent waiting for an answer is the one thing in this app that needs a person
        // *now*. If notifications are off it waits in silence, so this says so.
        item { NotificationsOffNotice("an agent waiting for you") }
        model.unavailable?.let { reason ->
            item {
                Text(
                    reason,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                // 401 is the one refusal the person can do something about from here.
                if (model.unpaired) {
                    Button(onClick = onPair, modifier = Modifier.padding(top = 8.dp)) { Text("Pair again") }
                }
            }
        }
        if (model.board.engines.isEmpty() && model.unavailable == null) {
            item {
                Text(
                    if (model.isLoading) "Asking the Mac about its agents…" else app.reachability.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(model.board.all, key = { it.engine }) { session ->
            EngineCard(
                session = session,
                busy = model.busy[session.engine],
                problem = model.problems[session.engine],
                onOpen = { onOpen(session.engine) },
                onStart = { model.start(session.engine) },
                onStop = { confirming = Confirm.Stop(session.engine) },
                onNewThread = { confirming = Confirm.NewThread(session.engine) },
            )
        }
        item {
            Text(
                "These are the Codex and Pi sessions in the Mac's Chat tab — the same " +
                    "thread, not a copy. What you send here appears there, and an approval " +
                    "answered on either side is answered for both.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    confirming?.let { confirm ->
        ConfirmDialog(
            confirm = confirm,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                when (confirm) {
                    is Confirm.Stop -> model.stop(confirm.engine)
                    is Confirm.NewThread -> model.newThread(confirm.engine)
                }
            },
        )
    }
}

/** Something that throws work away on the Mac, waiting to be meant. */
sealed interface Confirm {
    val engine: String

    data class Stop(override val engine: String) : Confirm
    data class NewThread(override val engine: String) : Confirm
}

@Composable
fun ConfirmDialog(confirm: Confirm, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val name = AgentNotices.engine(confirm.engine)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (confirm) {
                    is Confirm.Stop -> "Stop $name?"
                    is Confirm.NewThread -> "Start a new $name thread?"
                },
            )
        },
        text = {
            Text(
                when (confirm) {
                    is Confirm.Stop ->
                        "$name stops on the Mac. A turn in flight ends with it, and anything " +
                            "it is waiting to ask is dropped."
                    is Confirm.NewThread ->
                        "The Mac's transcript clears with it — on the Mac as well as here. " +
                            "The work $name has already done stays on disk."
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    when (confirm) {
                        is Confirm.Stop -> "Stop $name"
                        is Confirm.NewThread -> "New thread"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    when (confirm) {
                        is Confirm.Stop -> "Keep it running"
                        is Confirm.NewThread -> "Keep this thread"
                    },
                )
            }
        },
    )
}

@Composable
private fun EngineCard(
    session: AgentSession,
    busy: String?,
    problem: String?,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNewThread: () -> Unit,
) {
    val summary = session.summary
    val name = AgentNotices.engine(session.engine)
    SectionCard(
        title = name,
        icon = if (session.engine == AgentEngines.CODEX) Icons.Filled.Terminal else Icons.Filled.SmartToy,
        footnote = summary?.cwd?.let { "in " + folderName(it) },
        modifier = Modifier.clickable(onClick = onOpen),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(
                AgentNotices.state(summary),
                filled = summary?.isRunning == true || summary?.state == AgentSessionSummary.STATE_FAILED,
                tint = when (summary?.state) {
                    AgentSessionSummary.STATE_FAILED -> MaterialTheme.colorScheme.error
                    AgentSessionSummary.STATE_RUNNING -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            val waiting = session.pendingCount
            if (waiting > 0) {
                Pill(
                    if (waiting == 1) "1 approval waiting" else "$waiting approvals waiting",
                    filled = true,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        summary?.let {
            val chosen = ModelPicker.selected(it, null)
            Text(
                chosen?.let { choice -> "${choice.label} · ${choice.where}" } ?: it.model,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (session.turnActive) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        latestLine(session)?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (summary != null && summary.cwd == null && session.engine == AgentEngines.CODEX) {
            Text(
                "The Mac hasn't picked a folder for Codex yet — that is chosen on the Mac.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (summary?.runsWithoutAsking == true) {
            Text(
                "Runs without asking",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        summary?.failure?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        problem?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onOpen) { Text("Open") }
            when {
                busy != null -> Text(
                    busy,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                summary == null -> Unit
                summary.isStopped -> OutlinedButton(onClick = onStart) { Text("Start") }
                else -> {
                    if (summary.isRunning) {
                        TextButton(onClick = onNewThread) { Text("New thread") }
                    }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

/** The newest thing the agent said or did, as one line for the card. */
internal fun latestLine(session: AgentSession): String? =
    session.transcript.lastOrNull { it.kind != AgentItem.REASONING }?.let { item ->
        val text = item.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        when (item.kind) {
            AgentItem.USER -> "You: $text"
            AgentItem.COMMAND -> "$ $text"
            else -> text
        }
    }

internal fun folderName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }
