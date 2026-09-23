package dev.siliconoptimizer.buddy.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.MeterRow
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.SectionCard
import dev.siliconoptimizer.buddy.ui.Stat
import dev.siliconoptimizer.buddy.ui.StatRow
import dev.siliconoptimizer.buddy.ui.StatusDot
import dev.siliconoptimizer.buddy.ui.WelcomeCard

/** The first screen: what the Mac is doing right now. */
@Composable
fun DashboardScreen(
    app: AppState,
    model: DashboardViewModel,
    events: dev.siliconoptimizer.buddy.EventFeed,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 12.dp, bottom = 24.dp),
    ) {
        if (!app.isPaired) {
            item { WelcomeCard(onPair) }
            return@LazyColumn
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("WORKSPACE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Your Mac, at a glance.", style = MaterialTheme.typography.headlineLarge)
            }
        }
        item { ConnectionCard(app, onPair) }

        if (events.downloads.isNotEmpty() || events.jobs.isNotEmpty()) {
            item { ActivityCard(events) }
        }
        item { LoadedModelCard(model) }
        item { MetricsCard(model) }
        item { MachineCard(model) }
        item { SwarmCard(model) }
        model.error?.let { error ->
            item {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(app: AppState, onPair: () -> Unit) {
    SectionCard("Connection", Icons.Filled.Wifi) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(ok = app.reachability.isReady)
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    if (app.isPaired) app.macDisplayName else "No Mac paired",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    app.reachability.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                app.config?.let {
                    Text(
                        it.displayAddress,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            if (!app.isPaired) {
                Button(onClick = onPair) { Text("Pair") }
            }
        }
    }
}

/** What the Mac is busy with, straight off `GET /events`. */
@Composable
private fun ActivityCard(events: dev.siliconoptimizer.buddy.EventFeed) {
    SectionCard("Happening now", Icons.Filled.Speed) {
        events.downloads.values.sortedBy { it.id }.forEach { download ->
            MeterRow(
                label = download.name,
                detail = download.progress?.let { Format.percent(it) }
                    ?: Format.bytes(download.bytesReceived),
                fraction = download.progress ?: 0.0,
            )
        }
        events.jobs.values.sortedBy { it.id }.forEach { job ->
            MeterRow(
                label = job.title ?: job.kind,
                detail = job.status,
                fraction = job.fraction ?: 0.0,
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun LoadedModelCard(model: DashboardViewModel) {
    SectionCard("Loaded model", Icons.Filled.Memory) {
        Text(model.loadedModelTitle, style = MaterialTheme.typography.headlineSmall)
        Text(
            model.loadedModelDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.status?.lastGenerationTokensPerSecond?.let {
            Pill(Format.rate(it), tint = MaterialTheme.colorScheme.primary, filled = true)
        }
    }
}

@Composable
private fun MetricsCard(model: DashboardViewModel) {
    SectionCard("Live metrics", Icons.Filled.Speed) {
        val metrics = model.metrics
        if (metrics == null) {
            Text("No reading yet.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        MeterRow(
            label = "Memory",
            detail = "${Format.bytes(metrics.memoryUsedBytes)} of " +
                Format.bytes(metrics.memoryTotalBytes),
            fraction = metrics.memoryFraction,
        )
        MeterRow(
            label = "GPU",
            detail = Format.percent(metrics.gpuUtilization),
            fraction = metrics.gpuUtilization,
            tint = MaterialTheme.colorScheme.tertiary,
        )
        MeterRow(
            label = "CPU",
            detail = Format.percent(metrics.cpuUtilization),
            fraction = metrics.cpuUtilization,
            tint = MaterialTheme.colorScheme.secondary,
        )
        StatRow {
            Stat("Pressure", metrics.memoryPressure)
            Stat("Swap", Format.bytes(metrics.swapUsedBytes))
            Stat("Wired", Format.bytes(metrics.memoryWiredBytes))
        }
    }
}

@Composable
private fun MachineCard(model: DashboardViewModel) {
    SectionCard("This Mac", Icons.Filled.Computer) {
        val profile = model.profile
        if (profile == null) {
            Text("No profile yet.", style = MaterialTheme.typography.bodySmall)
            return@SectionCard
        }
        Text(profile.chip, style = MaterialTheme.typography.titleMedium)
        StatRow {
            Stat("GPU cores", "${profile.gpuCores}")
            Stat("CPU", "${profile.performanceCores}P + ${profile.efficiencyCores}E")
            Stat("Neural", "${profile.neuralEngineCores}")
        }
        StatRow {
            Stat("Memory", Format.bytes(profile.totalMemoryBytes))
            Stat("Budget", Format.bytes(profile.modelBudgetBytes))
            Stat("Disk free", Format.bytes(profile.diskFreeBytes))
        }
        model.node?.let { node ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    node.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Pill(
                    if (node.metrics.queueDepth == 0) "Idle" else "${node.metrics.queueDepth} queued",
                    tint = if (node.metrics.queueDepth == 0) Color(0xFF34A853) else Color(0xFFE8A33D),
                    filled = true,
                )
                Text(
                    "  ${Format.gigabytes(node.metrics.headroomGB)} free",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Capabilities(node.capabilities.map { it.id to it.ready })
        }
    }
}

@Composable
private fun SwarmCard(model: DashboardViewModel) {
    SectionCard(
        "Swarm",
        Icons.Filled.Hub,
        footnote = model.swarm?.let { Format.secondsAgo(it.polledSecondsAgo) },
    ) {
        val peers = model.swarm?.peers.orEmpty()
        if (peers.isEmpty()) {
            Text(
                "No peers. The node is offline or not configured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        peers.forEach { peer ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(ok = peer.reachable)
                Text(
                    peer.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text(
                    peer.baseURL,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            peer.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Capabilities(peer.capabilities.map { it.id to it.ready })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Capabilities(capabilities: List<Pair<String, Boolean>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        capabilities.forEach { (id, ready) ->
            Pill(
                id,
                tint = if (ready) Color(0xFF34A853) else MaterialTheme.colorScheme.onSurfaceVariant,
                filled = ready,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}
