package dev.siliconoptimizer.buddy.agents

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.chat.MarkdownText
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentEngines
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.ui.Pill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One agent session: its transcript, what is waiting for a person, and a composer.
 *
 * The transcript is the Mac's, in the one vocabulary both engines are mapped onto. Prose is
 * prose; a command shows the command and what it printed; a file change shows what it
 * touches; thinking stays folded until asked for. Output is the one thing that can be
 * enormous, so it scrolls inside its own row rather than making the list a mile long.
 */
@Composable
fun SessionScreen(
    model: AgentsViewModel,
    engine: String,
    modifier: Modifier = Modifier,
) {
    val session = model.board.session(engine)
    val summary = session.summary
    val name = AgentNotices.engine(engine)
    val context = LocalContext.current
    val notifier = remember(context) { AgentNotifier(context) }
    val askForNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Opening a session is following it: it is the moment an approval could need this
    // person while the phone is in their pocket, so it is when notifications are asked for.
    LaunchedEffect(engine) {
        model.watch(engine)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifier.isAllowed) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var expanded by rememberSaveable(engine) { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val transcript = session.transcript
    LaunchedEffect(transcript.size, transcript.lastOrNull()?.text?.length, transcript.lastOrNull()?.output?.length) {
        val rows = transcript.size + if (session.omitted > 0) 1 else 0
        if (rows > 0) listState.animateScrollToItem(rows - 1)
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        SessionHeader(session)
        if (summary?.runsWithoutAsking == true) RunsWithoutAsking(engine)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (transcript.isEmpty() && session.omitted == 0) {
                Text(
                    when {
                        !session.loaded -> "Reading the transcript from the Mac…"
                        summary?.isRunning == true ->
                            "Nothing in this thread yet. What you send appears on the Mac too."
                        else -> "$name isn't running on the Mac. Start it to send it something."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (session.omitted > 0) {
                        item(key = "omitted") {
                            Text(
                                if (session.omitted == 1) {
                                    "1 earlier row is on the Mac and not shown here."
                                } else {
                                    "${session.omitted} earlier rows are on the Mac and not shown here."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(transcript, key = { it.id }) { item ->
                        TranscriptRow(
                            item = item,
                            expanded = item.id in expanded,
                            onToggle = {
                                expanded = if (item.id in expanded) expanded - item.id else expanded + item.id
                            },
                        )
                    }
                }
            }
        }

        if (session.resolutions.isNotEmpty()) {
            Resolutions(engine, session.resolutions) { model.dismissResolutions(engine) }
        }
        if (session.approvals.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                session.pending.forEach { approval ->
                    ApprovalCard(
                        engine = engine,
                        approval = approval,
                        answering = model.answering[approval.id],
                        note = session.notes[approval.id],
                        problem = model.cardProblems[approval.id],
                        onAnswer = { accept -> model.answer(engine, approval.id, accept) },
                    )
                }
            }
        }

        HorizontalDivider()
        Composer(model, session)
    }
}

@Composable
private fun SessionHeader(session: AgentSession) {
    val summary = session.summary ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            AgentNotices.state(summary),
            filled = summary.isRunning,
            tint = if (summary.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            listOfNotNull(
                summary.cwd?.let { "in " + folderName(it) },
                AgentNotices.sandbox(summary.sandbox),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (session.turnActive) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    summary.failure?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
    AgentNotices.approvalMode(summary)?.takeIf { !summary.runsWithoutAsking }?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}

/**
 * The Mac says nothing in this session will wait for a person. Said on the session and not
 * dismissable, because it is the one fact that changes what this screen is for.
 */
@Composable
private fun RunsWithoutAsking(engine: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp))
            .padding(10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
        Column {
            Text(
                "Runs without asking",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "The Mac lets ${AgentNotices.engine(engine)} act without approval here, so " +
                    "nothing will wait for you — on this phone or on the Mac.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun TranscriptRow(item: AgentItem, expanded: Boolean, onToggle: () -> Unit) {
    when (item.kind) {
        AgentItem.USER -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(item.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            item.model?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 2.dp, end = 4.dp),
                )
            }
        }
        AgentItem.ASSISTANT -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(14.dp))
                .padding(12.dp),
        ) {
            MarkdownText(item.text)
            if (item.status == AgentItem.RUNNING) Writing()
        }
        AgentItem.REASONING -> Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(vertical = 4.dp)
                    .semantics {
                        contentDescription = if (expanded) "Hide the reasoning" else "Show the reasoning"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.AutoMirrored.Filled.ArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
                Text(
                    if (item.status == AgentItem.RUNNING) "Thinking…" else "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (expanded) {
                Output(item.text, truncated = false, monospace = false)
            }
        }
        AgentItem.COMMAND -> ActionRow(
            icon = null,
            title = "$ ${item.text}",
            monospaceTitle = true,
            status = item.status,
            output = item.output,
            truncated = item.truncated == true,
        )
        AgentItem.FILE_CHANGE -> ActionRow(
            icon = Icons.Filled.Description,
            title = item.text,
            monospaceTitle = true,
            status = item.status,
            output = item.output,
            truncated = item.truncated == true,
        )
        AgentItem.TOOL -> ActionRow(
            icon = Icons.Filled.Build,
            title = item.text,
            monospaceTitle = false,
            status = item.status,
            output = item.output,
            truncated = item.truncated == true,
        )
        AgentItem.ERROR -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
            Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        // A notice, or a kind this build has never heard of: the Mac grows them, and its
        // text is still worth reading.
        else -> Text(
            item.text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun Writing() {
    Text(
        "writing…",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    title: String,
    monospaceTitle: Boolean,
    status: String?,
    output: String?,
    truncated: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                icon?.let {
                    Icon(it, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (monospaceTitle) FontFamily.Monospace else null,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                status?.let { StatusPill(it) }
            }
            output?.takeIf { it.isNotBlank() }?.let { Output(it, truncated, monospace = true) }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    Pill(
        when (status) {
            AgentItem.RUNNING -> "running"
            AgentItem.COMPLETED -> "done"
            AgentItem.FAILED -> "failed"
            AgentItem.DECLINED -> "declined"
            else -> status
        },
        filled = status != AgentItem.COMPLETED,
        tint = when (status) {
            AgentItem.FAILED, AgentItem.DECLINED -> MaterialTheme.colorScheme.error
            AgentItem.RUNNING -> MaterialTheme.colorScheme.primary
            else -> null
        },
    )
}

/**
 * What a command printed. Scrolls inside itself so a build log never pushes the rest of
 * the transcript out of reach — and only the end of a very long one is drawn, because the
 * end is where the answer to "did it work" lives.
 */
@Composable
private fun Output(text: String, truncated: Boolean, monospace: Boolean) {
    val shown = OutputWindow.tail(text)
    Column {
        if (truncated || shown.length < text.length) {
            Text(
                if (truncated) {
                    "The Mac keeps the end of long output; earlier lines are not shown."
                } else {
                    "Showing the last ${OutputWindow.MAX_CHARS / 1000}k characters."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                .verticalScroll(rememberScrollState(Int.MAX_VALUE))
                .padding(8.dp),
        ) {
            Text(
                shown,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            )
        }
    }
}

/** How much of one row's output is drawn. */
object OutputWindow {
    const val MAX_CHARS = 16_000

    fun tail(text: String): String =
        if (text.length <= MAX_CHARS) text else text.takeLast(MAX_CHARS).substringAfter('\n')
}

@Composable
private fun ApprovalCard(
    engine: String,
    approval: AgentApproval,
    answering: String?,
    note: String?,
    problem: String?,
    onAnswer: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                AgentNotices.headline(engine, approval),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
            ) {
                Text(approval.summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            approval.reason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            // What the Mac's guardrail made of it, as the Mac's own card says it.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(
                    AgentNotices.verdict(approval.screening.verdict),
                    filled = true,
                    tint = when (approval.screening.verdict) {
                        AgentScreening.BLOCK, AgentScreening.UNAVAILABLE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onTertiaryContainer
                    },
                )
                Text(
                    approval.screening.summary,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Asked ${clock(approval.requestedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
            )
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { onAnswer(false) }, enabled = answering == null) { Text("Decline") }
                Button(onClick = { onAnswer(true) }, enabled = answering == null) { Text("Accept") }
                if (answering != null) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

@Composable
private fun Resolutions(engine: String, resolutions: List<Resolution>, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            resolutions.take(3).forEach { resolution ->
                Text(
                    AgentNotices.resolution(engine, resolution) + " · " +
                        AgentNotifications.line(resolution.approval.summary, 80),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Composer(model: AgentsViewModel, session: AgentSession) {
    val engine = session.engine
    val summary = session.summary
    val running = summary?.isRunning == true
    val busy = model.busy[engine]
    // Codex takes one turn at a time and says so with a 409; Pi takes a message mid-turn.
    val waitForTurn = session.turnActive && engine == AgentEngines.CODEX
    val refused = model.sendRefused[engine]
    var menu by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        model.problems[engine]?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (!running && summary != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Text(
                    "${AgentNotices.engine(engine)} is ${AgentNotices.state(summary).lowercase()} on the Mac.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                if (summary.isStopped) {
                    OutlinedButton(onClick = { model.start(engine) }, enabled = busy == null) { Text("Start") }
                }
            }
        }
        if (waitForTurn || refused != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Text(
                    refused ?: "${AgentNotices.engine(engine)} is working on this turn. Wait for " +
                        "it to finish, or interrupt it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        model.awaiting[engine]?.let {
            Text(
                "Sent — waiting for the Mac to show it.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }

        // The session's model. It sticks: sending with another one makes it this
        // engine's choice on the Mac as well, which is why it is not labelled "this turn".
        val choices = ModelPicker.choices(summary)
        Box {
            TextButton(onClick = { menu = true }, enabled = choices.isNotEmpty()) {
                Text(
                    "Session model: " + (ModelPicker.selected(summary, model.picked[engine])?.label ?: summary?.model ?: "—"),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(choice.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    choice.where,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            menu = false
                            model.pick(engine, choice.id)
                        },
                    )
                }
            }
        }

        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = model.drafts[engine].orEmpty(),
                onValueChange = { model.drafts[engine] = it },
                placeholder = { Text("Message ${AgentNotices.engine(engine)}") },
                modifier = Modifier.weight(1f),
                maxLines = 6,
                enabled = running,
            )
            if (session.turnActive) {
                IconButton(onClick = { model.interrupt(engine) }, enabled = busy == null) {
                    Icon(Icons.Filled.Stop, contentDescription = "Interrupt this turn")
                }
            }
            IconButton(
                onClick = { model.send(engine) },
                enabled = running && busy == null && !waitForTurn &&
                    model.drafts[engine].orEmpty().isNotBlank(),
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = if (waitForTurn) "Wait for this turn to finish" else "Send",
                )
            }
        }
    }
}

/** "10:12" for today, the date as well otherwise. The Mac's timestamps are ISO-8601. */
internal fun clock(iso: String): String = runCatching {
    val instant = Instant.parse(iso)
    val zone = ZoneId.systemDefault()
    val then = instant.atZone(zone)
    val today = java.time.LocalDate.now(zone)
    if (then.toLocalDate() == today) {
        then.format(DateTimeFormatter.ofPattern("HH:mm"))
    } else {
        then.format(DateTimeFormatter.ofPattern("d MMM, HH:mm"))
    }
}.getOrDefault(iso)
