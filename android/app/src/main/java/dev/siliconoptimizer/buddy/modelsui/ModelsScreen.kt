package dev.siliconoptimizer.buddy.modelsui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.verdictTint

/** Everything the Mac can run: on its disk, in the catalog, or in the cloud. */
@Composable
fun ModelsScreen(
    app: AppState,
    model: ModelsViewModel,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = model.search,
            onValueChange = { model.search = it },
            placeholder = { Text("Search models") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
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

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                OutlinedButton(
                                    onClick = { model.unload(app.transport) },
                                    enabled = model.job == null,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) { Text("Unload") }
                            }
                            HorizontalDivider()
                        }
                    }
                    item { SectionHeader("${model.filteredInstalled.size} on disk") }
                    items(model.filteredInstalled, key = { it.id }) { entry ->
                        InstalledRow(
                            entry = entry,
                            isLoaded = model.isLoaded(entry.id),
                            isBusy = model.job?.modelID == entry.id,
                            onLoad = { model.load(entry.id, transport = app.transport) },
                        )
                        HorizontalDivider()
                    }
                }

                ModelsViewModel.Section.Catalog -> {
                    item { SectionHeader("${model.filteredCatalog.size} in the catalog") }
                    items(model.filteredCatalog, key = { it.id }) { entry ->
                        CatalogRow(entry) { model.install(entry, transport = app.transport) }
                        HorizontalDivider()
                    }
                }

                ModelsViewModel.Section.Cloud -> {
                    if (model.filteredCloud.isEmpty()) {
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
                            CatalogRow(entry) { model.install(entry, transport = app.transport) }
                            HorizontalDivider()
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledRow(
    entry: InstalledModel,
    isLoaded: Boolean,
    isBusy: Boolean,
    onLoad: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
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
                if (isLoaded) Pill("Loaded", tint = Color(0xFF34A853), filled = true)
            }
        }
        when {
            isBusy -> CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            !isLoaded -> OutlinedButton(onClick = onLoad) { Text("Load") }
        }
    }
}

@Composable
private fun CatalogRow(entry: CatalogModel, onInstall: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.name + if (entry.featured == true) " ★" else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
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
            TextButton(onClick = onInstall) { Text("Install") }
        }
    }
}
