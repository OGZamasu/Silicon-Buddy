package dev.siliconoptimizer.buddy.modelsui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadFailure
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.EmptyState
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.verdictTint

/** Everything the Mac can run: on its disk, in the catalog, or in the cloud. */
@Composable
fun ModelsScreen(
    app: AppState,
    model: ModelsViewModel,
    events: dev.siliconoptimizer.buddy.EventFeed,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.search,
            onValueChange = { model.search = it },
            placeholder = { Text("Search models") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
            // The border keeps Material's `outline`: the field is white on a near-white
            // page, so the border is the only thing that says there is a field there, and
            // `outlineVariant` is 1.3:1 against it — under the 3:1 a control's edge needs.
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        ) {
            ModelsViewModel.Section.entries.forEachIndexed { index, section ->
                SegmentedButton(
                    selected = model.section == section,
                    onClick = { model.section = section },
                    shape = SegmentedButtonDefaults.itemShape(
                        index, ModelsViewModel.Section.entries.size,
                    ),
                ) { Text(section.label) }
            }
        }

        // Above the list rather than in it, so it is on screen whichever section is.
        model.problem?.let { problem ->
            ProblemBanner(
                problem = problem,
                onRetry = if (problem.retry != null && app.isPaired) {
                    { model.retry(app.transport) }
                } else {
                    null
                },
                onDismiss = model::clearError,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        model.job?.let { job ->
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "${job.kind.replaceFirstChar { it.uppercase() }} ${job.modelID}",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (job.fraction != null) {
                    LinearProgressIndicator(
                        progress = { job.fraction.toFloat() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                Text(
                    job.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (model.section) {
                ModelsViewModel.Section.Installed -> {
                    model.status?.loadedModelID?.let { loaded ->
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        model.status?.loadedModelName ?: loaded,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        model.status?.state.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (app.canControl) {
                                    OutlinedButton(
                                        onClick = { model.unload(app.transport) },
                                        enabled = model.job == null,
                                        colors = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) { Text("Unload") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    model.standingFailure?.let { failure ->
                        item { LastLoadFailed(model.status?.state.orEmpty(), failure) }
                    }
                    // Nothing arrived, which is not the same as an empty disk: saying "Your
                    // model library… explore the Catalog" then would be false.
                    val listMissing = model.installedFailed && model.installed.isEmpty()
                    if (listMissing) {
                        item {
                            ListUnavailable(
                                "The Mac's models didn't load",
                                "This phone couldn't read what is on the Mac's disk. " +
                                    "Nothing there has changed.",
                            ) { model.refresh(app.transport) }
                        }
                    } else {
                        item { SectionHeader("${model.filteredInstalled.size} on disk") }
                    }
                    if (model.filteredInstalled.isEmpty() && !model.isLoading && !listMissing) {
                        item {
                            EmptyState(
                                if (model.search.isNotBlank()) "No matching models" else "Your model library",
                                when {
                                    model.search.isNotBlank() -> "Try another name or quantization."
                                    !app.isPaired -> "Pair with your Mac to see its models and discover what it can run."
                                    else -> "Explore the Catalog to find a model for your Mac."
                                },
                                Icons.Filled.Layers,
                            )
                        }
                    }
                    items(model.filteredInstalled, key = { it.id }) { entry ->
                        InstalledRow(
                            entry = entry,
                            isLoaded = model.isLoaded(entry.id),
                            isBusy = model.job?.modelID == entry.id,
                            canControl = app.canControl,
                            download = events.download(modelID = entry.id),
                            onLoad = { model.load(entry.id, transport = app.transport) },
                        )
                    }
                }

                ModelsViewModel.Section.Catalog -> {
                    if (model.catalogFailed && model.catalog.isEmpty()) {
                        item {
                            ListUnavailable(
                                "The catalog didn't load",
                                "This phone couldn't read the Mac's catalog.",
                            ) { model.refresh(app.transport) }
                        }
                    } else {
                        item { SectionHeader("${model.filteredCatalog.size} in the catalog") }
                    }
                    items(model.filteredCatalog, key = { it.id }) { entry ->
                        CatalogRow(entry, app.canControl) {
                            model.install(entry, transport = app.transport)
                        }
                    }
                }

                ModelsViewModel.Section.Cloud -> {
                    if (model.catalogFailed && model.catalog.isEmpty()) {
                        item {
                            ListUnavailable(
                                "The catalog didn't load",
                                "Cloud models are listed in the Mac's catalog, and this " +
                                    "phone couldn't read it.",
                            ) { model.refresh(app.transport) }
                        }
                    } else if (model.filteredCloud.isEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                                Text("No cloud models yet", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Cloud providers appear here once the Mac's catalog lists " +
                                        "them. Everything the Mac runs locally is under " +
                                        "Installed and Catalog.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(model.filteredCloud, key = { it.id }) { entry ->
                            CatalogRow(entry, app.canControl) {
                                model.install(entry, transport = app.transport)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/**
 * Why the last thing asked of the Mac did not happen: announced as it appears, put away
 * by hand, with the Mac's log behind a tap when it sent one, and Retry only where asking
 * again could help.
 */
@Composable
private fun ProblemBanner(
    problem: ModelsViewModel.Problem,
    onRetry: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (problem.isFault) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val content = if (problem.isFault) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    var showLog by rememberSaveable(problem.detail) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(container, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (problem.isFault) Icons.Filled.ErrorOutline else Icons.Filled.Info,
                contentDescription = null,
                tint = content,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 6.dp, bottom = 6.dp)
                    // One announcement, heading and sentence together, when it appears.
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(problem.title, style = MaterialTheme.typography.titleSmall, color = content)
                Text(problem.message, style = MaterialTheme.typography.bodySmall, color = content)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = content)
            }
        }
        if (problem.detail != null || onRetry != null) {
            Row(modifier = Modifier.padding(start = 22.dp)) {
                problem.detail?.let {
                    LogToggle(showLog, content) { showLog = !showLog }
                }
                onRetry?.let {
                    TextButton(onClick = it) { Text("Retry", color = content) }
                }
            }
        }
        if (showLog) problem.detail?.let { LogText(it, content) }
    }
}

/**
 * The Mac's last load failed, and nothing else on screen is saying so — it failed on the
 * Mac itself, or this phone came back to it later. The sentence first; the runtime's own
 * log only when asked for, and never for a device the Mac does not show its logs to.
 */
@Composable
private fun LastLoadFailed(state: String, failure: LoadFailure) {
    var showLog by rememberSaveable(failure.at) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            when (failure.kind) {
                LoadFailure.Reason.Replaced -> "The last load was replaced"
                LoadFailure.Reason.Cancelled -> "The last load was cancelled"
                else -> "The last load didn't finish"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        failure.facts?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        failure.detail?.let { log ->
            LogToggle(showLog, MaterialTheme.colorScheme.primary) { showLog = !showLog }
            if (showLog) LogText(log, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LogToggle(expanded: Boolean, color: Color, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) { Text(if (expanded) "Hide log" else "Show log", color = color) }
}

/** The runtime's own words, as it wrote them: fixed-width, and selectable to copy. */
@Composable
private fun LogText(log: String, color: Color) {
    SelectionContainer {
        Text(
            log,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = color,
            modifier = Modifier.fillMaxWidth().padding(end = 12.dp, bottom = 8.dp),
        )
    }
}

/** A list that did not arrive, said as that rather than as an empty one. */
@Composable
private fun ListUnavailable(title: String, message: String, onRetry: () -> Unit) {
    Column {
        EmptyState(title, message, Icons.Filled.CloudOff)
        OutlinedButton(onClick = onRetry, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("Try again")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledRow(
    entry: InstalledModel,
    isLoaded: Boolean,
    isBusy: Boolean,
    canControl: Boolean,
    download: dev.siliconoptimizer.buddy.transport.DownloadProgress?,
    onLoad: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(entry.quantization)
                Text(
                    Format.bytes(entry.sizeOnDiskBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.supportsVision) {
                    Pill("Vision", tint = MaterialTheme.colorScheme.primary, filled = true)
                }
                if (isLoaded) Pill("Loaded", tint = MaterialTheme.colorScheme.primary, filled = true)
            }
        }
        when {
            download != null -> Column {
                LinearProgressIndicator(
                    progress = { (download.progress ?: 0.0).toFloat() },
                    modifier = Modifier.width(90.dp),
                )
                Text(
                    Format.bytes(download.bytesReceived),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            isBusy -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            !isLoaded && canControl -> OutlinedButton(onClick = onLoad) { Text("Load") }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogModel, canControl: Boolean, onInstall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.name + if (entry.featured == true) " ★" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.verdict?.let { Pill(it, tint = verdictTint(it), filled = true) }
        }
        Text(
            entry.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Pill(entry.parameters)
            Pill(entry.category)
            entry.recommendation?.let {
                Text(
                    "${Format.bytes(it.downloadBytes)} · " +
                        Format.rate(it.estimatedGenerationTokensPerSecond),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
            }
            if (canControl) {
                TextButton(onClick = onInstall) { Text("Install") }
            }
        }
    }
}
