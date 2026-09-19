package dev.siliconoptimizer.buddy.media

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.ImagePlan
import dev.siliconoptimizer.buddy.transport.MeshPlan
import dev.siliconoptimizer.buddy.ui.Format
import dev.siliconoptimizer.buddy.ui.Pill
import dev.siliconoptimizer.buddy.ui.SectionCard
import dev.siliconoptimizer.buddy.ui.verdictTint

/**
 * Create: a clip, a picture or a mesh, made on the Mac.
 *
 * Every render here happens on the owner's machines and lands on the owner's disk. The
 * control API has no route that serves a finished file, so this screen says where a
 * result is rather than pretending it can show it — and a "save to Photos" button that
 * could not work is not drawn at all.
 */
@Composable
fun CreateScreen(
    app: AppState,
    model: MediaViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notifier = remember(context) { MediaNotifier(context) }
    val askForNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    // Asked for at the moment it starts to matter — the first render — rather than at
    // launch, where a person has no idea what they are being asked about.
    fun ensureNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifier.isAllowed) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(model.tab, app.connectionGeneration) {
        if (model.tab == MediaViewModel.Tab.Queue) {
            model.startFollowing(app.transport)
        } else {
            model.stopFollowing()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = model.tab.ordinal) {
            MediaViewModel.Tab.entries.forEach { entry ->
                Tab(
                    selected = model.tab == entry,
                    onClick = { model.tab = entry },
                    text = { Text(entry.label) },
                )
            }
        }

        if (!app.canControl) {
            Text(
                app.scope.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }

        model.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }
        model.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        }

        when (model.tab) {
            MediaViewModel.Tab.Video -> VideoForm(app, model, ::ensureNotifications)
            MediaViewModel.Tab.Image -> ImageForm(app, model, ::ensureNotifications)
            MediaViewModel.Tab.Mesh -> MeshForm(app, model, ::ensureNotifications)
            MediaViewModel.Tab.Queue -> QueueList(app, model)
        }
    }
}

// MARK: - Video

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VideoForm(app: AppState, model: MediaViewModel, ensureNotifications: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = model.videoPrompt,
                onValueChange = { model.videoPrompt = it },
                label = { Text("Prompt") },
                minLines = 3,
                enabled = app.canControl,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "The Mac's video routes take a prompt and nothing else — there is no " +
                    "negative prompt in the control API.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item {
            OutlinedTextField(
                value = model.videoTitle,
                onValueChange = { model.videoTitle = it },
                label = { Text("Batch name (optional)") },
                singleLine = true,
                enabled = app.canControl,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SectionCard("Where it renders", Icons.Filled.Movie) {
                if (model.videoModels.isEmpty()) {
                    Text(
                        "This Mac lists no video models. A clip needs a node that " +
                            "advertises one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.lanes.forEach { lane ->
                        FilterChip(
                            selected = model.lane?.id == lane.id,
                            onClick = { model.choose(lane) },
                            enabled = VideoRequest.isAvailable(lane),
                            label = { Text(lane.label) },
                        )
                    }
                }
                (model.lane as? VideoLane.On)?.model?.let { chosen ->
                    Text(
                        listOfNotNull(
                            chosen.summary,
                            chosen.node?.let { "on $it" },
                            chosen.typicalDuration.takeIf { it.isNotBlank() }
                                ?.let { "about $it" },
                            if (chosen.supportsImageInput) "takes a still" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!chosen.available) {
                        Text(
                            "No node is advertising this model right now.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                model.autoNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        if (model.lane !is VideoLane.Auto) {
            item {
                SectionCard("Length", Icons.Filled.Movie) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        model.secondsChoices.forEach { seconds ->
                            FilterChip(
                                selected = VideoRequest.seconds(model.lane, model.videoSeconds) == seconds,
                                onClick = { model.videoSeconds = seconds },
                                enabled = app.canControl,
                                label = { Text("${seconds}s") },
                            )
                        }
                    }
                    Text(
                        "Only the lengths this lane advertises.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        item {
            SectionCard("Takes", Icons.Filled.Movie) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 2, 3, 4, 6, 8).forEach { count ->
                        FilterChip(
                            selected = model.videoVariations == count,
                            onClick = { model.videoVariations = VideoRequest.variations(count) },
                            enabled = app.canControl,
                            label = { Text("$count") },
                        )
                    }
                }
                Text(
                    "How many variations of the prompt to render. The Mac's queue takes " +
                        "at most ${VideoRequest.MAXIMUM_VARIATIONS}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        ensureNotifications()
                        model.enqueueVideo(app.transport)
                    },
                    enabled = app.canControl && model.videoPrompt.isNotBlank(),
                ) { Text("Add to queue") }
                OutlinedButton(
                    onClick = {
                        ensureNotifications()
                        model.videoWork()?.let { MediaJobService.start(context, it) }
                    },
                    enabled = app.canControl && model.videoPrompt.isNotBlank(),
                ) { Text("Render one now") }
            }
            Text(
                "The queue is the one to use: the Mac keeps rendering whether this phone " +
                    "is awake or not. \"Render one now\" holds a request open for the " +
                    "whole render, behind an ongoing notification.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        item { Outcomes("video") }
    }
}

// MARK: - Image

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageForm(app: AppState, model: MediaViewModel, ensureNotifications: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            OutlinedTextField(
                value = model.imagePrompt,
                onValueChange = { model.imagePrompt = it },
                label = { Text("Prompt") },
                minLines = 3,
                enabled = app.canControl,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            SectionCard("Model", Icons.Filled.Image) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.imageModels.forEach { entry ->
                        FilterChip(
                            selected = model.imageModel?.id == entry.id,
                            onClick = { model.choose(entry) },
                            label = { Text(entry.name) },
                        )
                    }
                }
                model.imageModel?.let { chosen ->
                    Text(
                        listOfNotNull(
                            chosen.summary,
                            chosen.parameters,
                            "${chosen.defaultSteps} steps by default",
                            if (chosen.isGated) "gated" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard("Size and steps", Icons.Filled.Image) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ImageRequestBuilder.SIZES.forEach { size ->
                        FilterChip(
                            selected = model.imageSize == size,
                            onClick = { model.imageSize = size },
                            enabled = app.canControl,
                            label = { Text("${size}²") },
                        )
                    }
                }
                OutlinedTextField(
                    value = model.imageSteps.toString(),
                    onValueChange = {
                        model.imageSteps = ImageRequestBuilder.steps(it.toIntOrNull() ?: model.imageSteps)
                    },
                    label = { Text("Steps") },
                    singleLine = true,
                    enabled = app.canControl,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.planImage(app.transport) },
                    enabled = app.canControl && model.imagePrompt.isNotBlank(),
                ) { Text("Plan the memory") }
                Button(
                    onClick = {
                        ensureNotifications()
                        model.imageWork()?.let { MediaJobService.start(context, it) }
                    },
                    enabled = app.canControl && model.imagePrompt.isNotBlank() &&
                        model.imageModel != null,
                ) { Text("Generate") }
            }
            if (model.isPlanningImage) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        model.imagePlan?.let { plan -> item { ImagePlanCard(plan) } }
        item { Outcomes("image") }
    }
}

@Composable
private fun ImagePlanCard(plan: ImagePlan) {
    SectionCard("What it would need", Icons.Filled.Image, footnote = plan.verdict) {
        Text(
            "${Format.bytes(plan.peakBytes)} at its peak (${plan.peakPhase}), of " +
                "${Format.bytes(plan.budgetBytes)} a model may have.",
            style = MaterialTheme.typography.bodyMedium,
            color = verdictTint(plan.verdict),
        )
        Text(
            "${plan.width}×${plan.height} · ${plan.steps} steps · ${plan.quantization}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.phases.forEach { phase ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    listOfNotNull(phase.name, phase.detail.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.bytes(phase.residentBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        plan.suggestions.forEach {
            Text(
                "${it.title} — ${it.detail}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        plan.notes.forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

// MARK: - Mesh

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MeshForm(app: AppState, model: MediaViewModel, ensureNotifications: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        model.pickedPhotoName = uri?.lastPathSegment
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
    ) {
        item {
            SectionCard("The picture", Icons.Filled.ViewInAr) {
                Text(
                    "`/mesh/plan` and `/mesh/generate` take a path on the Mac's own disk. " +
                        "The control API has no route that accepts an upload, so a photo " +
                        "on this phone cannot be the subject of a mesh yet — name a file " +
                        "the Mac already has.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = model.meshImagePath,
                    onValueChange = { model.meshImagePath = it },
                    label = { Text("Path on the Mac") },
                    placeholder = { Text("/Users/you/Pictures/kettle.png") },
                    singleLine = true,
                    isError = model.meshImagePath.isNotBlank() &&
                        !MeshRequestBuilder.isMacPath(model.meshImagePath),
                    enabled = app.canControl,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                ) { Text("Pick a photo here") }
                model.pickedPhotoName?.let {
                    Text(
                        "Picked $it on this phone. It stays here: there is nowhere on the " +
                            "Mac to send it.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        item {
            SectionCard("Model", Icons.Filled.ViewInAr) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    model.meshModels.forEach { entry ->
                        FilterChip(
                            selected = model.meshModel?.id == entry.id,
                            onClick = { model.choose(entry) },
                            enabled = entry.isInstalled,
                            label = { Text(entry.name) },
                        )
                    }
                }
                model.meshModel?.let { chosen ->
                    Text(
                        listOfNotNull(
                            chosen.summary,
                            chosen.outputs,
                            "about ${chosen.typicalDuration}",
                            "peaks at ${Format.bytes(chosen.peakBytes)}",
                            chosen.installDetail.takeIf { !chosen.isInstalled },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MeshRequestBuilder.TEXTURE_SIZES.forEach { size ->
                        FilterChip(
                            selected = model.meshTextureSize == size,
                            onClick = { model.meshTextureSize = size },
                            enabled = app.canControl,
                            label = { Text("${size}px texture") },
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { model.planMesh(app.transport) },
                    enabled = app.canControl && MeshRequestBuilder.isMacPath(model.meshImagePath),
                ) { Text("Plan it") }
                Button(
                    onClick = {
                        ensureNotifications()
                        model.meshWork()?.let { MediaJobService.start(context, it) }
                    },
                    enabled = app.canControl && MeshRequestBuilder.isMacPath(model.meshImagePath),
                ) { Text("Generate") }
            }
            if (model.isPlanningMesh) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        model.meshPlan?.let { plan -> item { MeshPlanCard(plan) } }
        item { Outcomes("mesh") }
    }
}

@Composable
private fun MeshPlanCard(plan: MeshPlan) {
    SectionCard("What it would need", Icons.Filled.ViewInAr, footnote = plan.verdict) {
        Text(
            "${Format.bytes(plan.peakBytes)} at its peak (${plan.peakPhase}), of " +
                "${Format.bytes(plan.budgetBytes)}.",
            style = MaterialTheme.typography.bodyMedium,
            color = verdictTint(plan.verdict),
        )
        Text(
            if (plan.isRemote) "${plan.model} — on the node" else "${plan.model} — on this Mac",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        plan.phases.forEach { phase ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    listOfNotNull(phase.name, phase.detail.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Format.bytes(phase.residentBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        plan.notes.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// MARK: - What came back

/**
 * Results, which are paths.
 *
 * Nothing on the control API serves the file itself — the Mac's own media route is on
 * its loopback gateway, with a token a phone never sees — so this says where it is on
 * the Mac and stops there. A "Save to Photos" button would need a route that does not
 * exist.
 */
@Composable
private fun Outcomes(kind: String) {
    val outcomes by MediaJobCenter.outcomes.collectAsState()
    val running by MediaJobCenter.running.collectAsState()
    val mine = outcomes.filter { it.kind == kind }
    val working = running.filter { it.kind == kind }
    if (mine.isEmpty() && working.isEmpty()) return

    SectionCard(
        "On the Mac",
        when (kind) {
            "video" -> Icons.Filled.Movie
            "mesh" -> Icons.Filled.ViewInAr
            else -> Icons.Filled.Image
        },
    ) {
        working.forEach {
            Text(
                "Rendering — ${it.summary}",
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        mine.forEach { outcome ->
            HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    outcome.headline,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (outcome.failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                outcome.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            outcome.path?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
                Pill("on the Mac")
            }
            outcome.warning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
