package dev.siliconoptimizer.buddy.machines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.SectionCard
import dev.siliconoptimizer.buddy.ui.StatusDot

/**
 * Every machine the Mac can use, including itself.
 *
 * One card per machine, the same shape for all of them, so "what is this, what is it
 * doing, what can it do" reads the same way for the Mac in the study and the PC under
 * the desk.
 */
@Composable
fun MachinesScreen(
    app: AppState,
    model: MachinesViewModel,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp),
    ) {
        if (!app.isPaired) {
            item {
                Text(
                    "Pair with a Mac to see its machines.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        model.error?.let { message ->
            item {
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (model.isLoading && model.machines.isEmpty()) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        items(model.machines, key = { it.name + it.isThisMac }) { machine ->
            MachineCard(machine)
        }
        model.exposure?.let { exposure ->
            item {
                SectionCard("This Mac on the tailnet", Icons.Filled.Hub) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(exposure.listening)
                        Text(
                            when {
                                exposure.listening ->
                                    "Listening on ${exposure.address ?: "its tailnet address"}" +
                                        (exposure.port?.let { ":$it" } ?: "")
                                exposure.requested -> exposure.problem
                                    ?: "Asked to listen, but it is not."
                                else -> "Not listening on the tailnet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    model.polledSecondsAgo?.let {
                        Text(
                            "Peers last polled ${Format.secondsAgo(it)}.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

/** The Mac's word for a lane, as a person would read it. Unknown ones show as sent. */
private fun laneHeading(kind: String): String = when (kind.lowercase()) {
    "llm" -> "Language models"
    "image" -> "Image"
    "video" -> "Video"
    "mesh" -> "3D"
    "audio" -> "Audio"
    else -> kind.replaceFirstChar { it.uppercase() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineCard(machine: Machine) {
    SectionCard(
        title = machine.name,
        icon = if (machine.isThisMac) Icons.Filled.Memory else Icons.Filled.Hub,
        footnote = if (machine.isThisMac) "This Mac" else machine.address,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(machine.reachable)
            Text(
                machine.headline,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        machine.detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (machine.stats.isNotEmpty()) {
            HorizontalDivider()
            machine.stats.forEach { (label, value) ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (machine.lanes.isNotEmpty()) {
            HorizontalDivider()
            machine.lanesByKind.forEach { (kind, lanes) ->
                Text(
                    laneHeading(kind),
                    style = MaterialTheme.typography.labelMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    lanes.forEach { lane ->
                        Pill(
                            text = lane.id + if (lane.ready) "" else " · not ready",
                            filled = lane.ready,
                            tint = if (lane.ready) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                    }
                }
                lanes.mapNotNull { it.detail }.distinct().forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        machine.blindSpot?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}
