package dev.siliconoptimizer.buddy.ondevice

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.llama.LlamaRuntime
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.DownloadProgress
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → On this phone: which models the Mac can send, which are here, and getting one.
 *
 * Nothing starts without the consent dialog — name, size, licence and free space, and Wi-Fi
 * unless the owner ticks "use mobile data" for this one download.
 */
class PhoneModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = ModelStore(application)
    private val settings = OnDeviceSettings(application)

    /** What the Mac offers, when it could be asked. */
    var offered by androidx.compose.runtime.mutableStateOf<List<PhoneModel>>(emptyList())
        private set
    var installed by androidx.compose.runtime.mutableStateOf<List<InstalledPhoneModel>>(emptyList())
        private set

    /** Why the Mac's list is not here: chat scope, an old Mac, a Mac out of reach. */
    var problem by androidx.compose.runtime.mutableStateOf<String?>(null)
        private set
    var loading by androidx.compose.runtime.mutableStateOf(false)
        private set
    var availability by androidx.compose.runtime.mutableStateOf<LlamaRuntime.Availability?>(null)
        private set
    var freeBytes by androidx.compose.runtime.mutableStateOf<Long?>(null)
        private set

    /** What the phone has free to *run* one, beside what each model says it needs. */
    var availableMemoryBytes by androidx.compose.runtime.mutableStateOf<Long?>(null)
        private set

    /**
     * Part-finished downloads, by model id: bytes already on the phone with no model to
     * show for them. Reserved in full on disk, so an owner who never sees them sees a
     * gigabyte of "Silicon Buddy" in Android's storage screen and nothing here.
     */
    var partials by androidx.compose.runtime.mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** Models with a download the system still has in hand, which will carry on by itself. */
    var scheduled by androidx.compose.runtime.mutableStateOf<Set<String>>(emptySet())
        private set
    var preferredID by androidx.compose.runtime.mutableStateOf(settings.preferredModelID)
        private set

    /** The model the consent dialog is asking about. */
    var consent by androidx.compose.runtime.mutableStateOf<PhoneModel?>(null)

    /** The model the delete confirmation is asking about. */
    var deleting by androidx.compose.runtime.mutableStateOf<String?>(null)

    private var lastAsk = 0L

    /**
     * The Mac has answered something — the event stream, or the dashboard's polling.
     *
     * Only worth acting on when the last ask got nothing: this section's list comes from the
     * Mac, and a Mac that was out of reach when Settings was opened leaves it saying so for
     * as long as the screen stays open. The floor is because the news arrives every few
     * seconds while the dashboard polls.
     */
    fun macIsBack(transport: ControlTransport?, canControl: Boolean, now: Long = System.currentTimeMillis()) {
        if (problem == null || loading) return
        if (now - lastAsk < ASK_FLOOR_MILLIS) return
        lastAsk = now
        refresh(transport, canControl)
    }

    fun refresh(transport: ControlTransport?, canControl: Boolean) {
        viewModelScope.launch {
            loading = true
            installed = withContext(Dispatchers.IO) { store.installed() }
            freeBytes = withContext(Dispatchers.IO) { runCatching { AndroidSpace(getApplication()).allocatableBytes(store.directory) }.getOrNull() }
            availableMemoryBytes = OnDeviceEngine.get(getApplication()).memory().first
            preferredID = settings.preferredModelID
            problem = null
            when {
                transport == null -> {
                    offered = emptyList()
                    problem = "Pair with your Mac to get a model for this phone. The model only ever comes from your Mac."
                }
                // Full scope only on the Mac's side, so a chat-only phone does not even ask.
                !canControl -> {
                    offered = emptyList()
                    problem = OnDeviceNotices.CHAT_SCOPE_SETTINGS
                }
                else -> try {
                    offered = transport.phoneModels().models
                } catch (error: TransportError) {
                    problem = when (error) {
                        is TransportError.RouteUnavailable ->
                            "Your Mac doesn't serve models for this phone yet. Update Silicon Optimizer on it."
                        is TransportError.Forbidden -> OnDeviceNotices.chatScope(error.message)
                        is TransportError.Unreachable, is TransportError.AppNotRunning, is TransportError.TimedOut ->
                            "Your Mac isn't reachable, so it can't send a model right now. " +
                                "Models already on this phone still work."
                        else -> error.message
                    }
                }
            }
            readPartials()
            loading = false
        }
    }

    /**
     * llama.cpp itself, loaded only when this section is on screen.
     *
     * Asking what the phone can run means loading the library and every CPU variant beside
     * it to pick one. That is a few tens of milliseconds and a few megabytes, and it used to
     * happen at every launch for a screen most launches never open.
     */
    fun probeRuntime() {
        if (availability != null) return
        viewModelScope.launch { availability = OnDeviceEngine.get(getApplication()).availability() }
    }

    /** What is half-here, and what the system is still working on. */
    private suspend fun readPartials() {
        val known = (offered + installed.map { it.model }).distinctBy { it.id }
        partials = withContext(Dispatchers.IO) {
            known.filter { model -> installed.none { it.id == model.id } }
                .associate { it.id to store.receivedBytes(it.sha256) }
                .filterValues { it > 0 }
        }
        scheduled = known.map { it.id }.filter { ModelDownloads.isScheduled(getApplication(), it) }.toSet()
    }

    /** A `download` frame for one of the phone's models: the Mac's side, live. */
    fun apply(frame: DownloadProgress) {
        val id = frame.phoneModelID ?: return
        offered = offered.map { model ->
            if (model.id != id) return@map model
            val onMac = when {
                frame.error != null -> model.onMac.copy(state = "failed", stage = null, reason = frame.error)
                (frame.progress ?: 0.0) >= 1.0 -> model.onMac.copy(state = "ready", stage = null, fraction = null, reason = null, failure = null)
                else -> model.onMac.copy(state = "downloading", stage = frame.stage, fraction = frame.progress)
            }
            model.copy(onMac = onMac)
        }
    }

    fun download(context: Context, model: PhoneModel, useMobileData: Boolean) {
        consent = null
        ModelDownloads.start(context, model, useMobileData)
    }

    /** Throws away a part-finished download without touching anything else. */
    fun discardPartial(context: Context, model: PhoneModel) {
        viewModelScope.launch {
            ModelDownloads.cancel(context, model.id)
            withContext(Dispatchers.IO) { store.discardPartial(model.sha256) }
            partials = partials - model.id
            freeBytes = withContext(Dispatchers.IO) { runCatching { AndroidSpace(getApplication()).allocatableBytes(store.directory) }.getOrNull() }
        }
    }

    fun cancel(context: Context, id: String) = ModelDownloads.cancel(context, id)

    /** Unloads it if it is loaded, then deletes the file, any partial and its record. */
    fun delete(context: Context, id: String) {
        deleting = null
        viewModelScope.launch {
            val engine = OnDeviceEngine.get(context)
            if (engine.loadedModel?.id == id) engine.unload("deleted")
            ModelDownloads.cancel(context, id)
            val sha = installed.firstOrNull { it.id == id }?.model?.sha256 ?: offered.firstOrNull { it.id == id }?.sha256
            withContext(Dispatchers.IO) { store.deleteEverything(id, sha) }
            installed = withContext(Dispatchers.IO) { store.installed() }
            if (settings.preferredModelID == id) {
                settings.preferredModelID = FallbackPolicy.choose(installed)?.id
                preferredID = settings.preferredModelID
            }
        }
    }

    fun prefer(id: String) {
        settings.preferredModelID = id
        preferredID = id
    }

    private companion object {
        const val ASK_FLOOR_MILLIS = 15_000L
    }

    fun installedNow() {
        viewModelScope.launch {
            installed = withContext(Dispatchers.IO) { store.installed() }
            // A finished download makes itself the preferred model when there was none.
            preferredID = settings.preferredModelID
            readPartials()
        }
    }
}

@Composable
fun PhoneModelsSection(
    model: PhoneModelsViewModel,
    macFrames: Map<String, DownloadProgress>,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val downloads by ModelDownloads.states.collectAsState()
    val engineState by OnDeviceEngine.get(context).state.collectAsState()

    // Opening this section reads what is actually on the phone: llama.cpp for what it can
    // run, and the store for what is here — including a download that stopped half-way,
    // which is otherwise a gigabyte the owner can see only in Android's storage screen.
    LaunchedEffect(Unit) {
        model.probeRuntime()
        model.installedNow()
    }
    LaunchedEffect(macFrames.values.toList()) { macFrames.values.forEach { model.apply(it) } }
    // A download that finished while this was open: the list of what is here changes.
    LaunchedEffect(downloads.values.count { it is DownloadState.Done }) { model.installedNow() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On this phone", style = MaterialTheme.typography.titleMedium)
        Text(
            "When your Mac is out of reach, a small model on this phone can answer instead — " +
                "only when you tap \"Answer on this phone\", and every answer says it came from " +
                "the phone. It comes from your Mac over your tailnet, never from the internet " +
                "directly, and those conversations stay on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (val runtime = model.availability) {
            is LlamaRuntime.Availability.Unavailable -> Text(
                runtime.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            is LlamaRuntime.Availability.Ready -> Text(
                "Runs on the CPU: ${backendSummary(runtime.backends)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            null -> Unit
        }
        engineLine(engineState)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }

        val rows = (model.offered.map { it.id } + model.installed.map { it.id }).distinct()
        if (rows.isEmpty() && !model.loading) {
            Text(
                model.problem ?: "Your Mac has no models for this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            model.problem?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        rows.forEach { id ->
            val offered = model.offered.firstOrNull { it.id == id }
            val here = model.installed.firstOrNull { it.id == id }
            PhoneModelRow(
                offered = offered,
                installed = here,
                download = downloads[id],
                partialBytes = model.partials[id]?.takeIf { id !in model.scheduled },
                availableMemoryBytes = model.availableMemoryBytes,
                preferred = model.preferredID == id || (model.preferredID == null && here != null && model.installed.size == 1),
                canDownload = model.availability !is LlamaRuntime.Availability.Unavailable,
                onDownload = { offered?.let { model.consent = it } },
                onCancel = { model.cancel(context, id) },
                onDelete = { model.deleting = id },
                onDiscardPartial = { offered?.let { model.discardPartial(context, it) } },
                onPrefer = { model.prefer(id) },
            )
        }
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }

    model.consent?.let { asking ->
        ConsentDialog(
            model = asking,
            freeBytes = model.freeBytes,
            alreadyHere = model.partials[asking.id] ?: 0,
            availableMemoryBytes = model.availableMemoryBytes,
            onDismiss = { model.consent = null },
            onDownload = { mobile -> model.download(context, asking, mobile) },
        )
    }
    model.deleting?.let { id ->
        val label = model.installed.firstOrNull { it.id == id }?.label ?: model.offered.firstOrNull { it.id == id }?.label ?: id
        AlertDialog(
            onDismissRequest = { model.deleting = null },
            title = { Text("Delete $label from this phone?") },
            text = {
                Text(
                    "It is unloaded and its file removed, with any part-finished download. Your " +
                        "Mac keeps its copy, so you can get it again while the Mac is reachable. " +
                        "Conversations answered on this phone stay.",
                )
            },
            confirmButton = { TextButton(onClick = { model.delete(context, id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { model.deleting = null }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun PhoneModelRow(
    offered: PhoneModel?,
    installed: InstalledPhoneModel?,
    download: DownloadState?,
    /** Bytes of a stopped download sitting on the phone with nothing to show for them. */
    partialBytes: Long?,
    availableMemoryBytes: Long?,
    preferred: Boolean,
    canDownload: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDiscardPartial: () -> Unit,
    onPrefer: () -> Unit,
) {
    val entry = offered ?: installed?.model ?: return
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(entry.label, style = MaterialTheme.typography.bodyLarge)
        Text(
            OnDeviceNotices.summary(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OnDeviceNotices.expectation(entry)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        // What it needs to run, beside what this phone has free right now — the number the
        // refusal will be about, before it is refused.
        Text(
            PartialDownload.memoryLine(entry.recommended.minFreeMemoryBytes, availableMemoryBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        val stopped = PartialDownload.line(partialBytes, entry.sizeBytes)
            .takeIf { download?.isActive != true && installed == null }
        val status = when {
            download != null && download.isActive -> download.line
            download is DownloadState.Failed -> download.message
            installed != null -> "On this phone" + if (preferred) " · answers when your Mac can't" else ""
            stopped != null -> stopped
            offered?.onMac?.isDownloading == true -> "Your Mac is fetching it" +
                (offered.onMac.fraction?.let { " · ${Format.percent(it)}" } ?: "")
            offered?.onMac?.isFailed == true -> offered.onMac.reason ?: "Your Mac couldn't fetch it."
            else -> "Not on this phone"
        }
        Text(
            status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (download is DownloadState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { contentDescription = "${entry.label}: $status" },
        )
        download?.progress?.takeIf { download.isActive }?.let {
            LinearProgressIndicator(progress = { it.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                download?.isActive == true -> OutlinedButton(onClick = onCancel) { Text("Cancel") }
                installed == null && offered != null && canDownload -> OutlinedButton(onClick = onDownload) {
                    Text(
                        when {
                            stopped != null -> "Resume…"
                            download is DownloadState.Failed -> "Try again…"
                            else -> "Download…"
                        },
                    )
                }
                else -> Unit
            }
            if (installed != null && !preferred) TextButton(onClick = onPrefer) { Text("Use this one") }
            // Deleting a model and throwing away a part-finished download are different
            // things; the second is the one that is otherwise invisible.
            if (installed != null || (download is DownloadState.Failed && stopped == null)) {
                TextButton(onClick = onDelete) { Text("Delete") }
            }
            if (stopped != null) TextButton(onClick = onDiscardPartial) { Text("Delete") }
        }
    }
}

/**
 * Asked before anything moves: what it is, how big, whose licence, how much room the phone
 * has — and Wi-Fi only unless the owner says otherwise for this download.
 */
@Composable
private fun ConsentDialog(
    model: PhoneModel,
    freeBytes: Long?,
    alreadyHere: Long,
    availableMemoryBytes: Long?,
    onDismiss: () -> Unit,
    onDownload: (useMobileData: Boolean) -> Unit,
) {
    var mobile by remember { mutableStateOf(false) }
    val remaining = (model.sizeBytes - alreadyHere).coerceAtLeast(0)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Get ${model.label} for this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Size: ${Format.bytes(model.sizeBytes)}")
                if (alreadyHere > 0) {
                    Text("${Format.bytes(alreadyHere)} of it is already here, so ${Format.bytes(remaining)} to come.")
                }
                Text("Licence: ${model.licence}")
                Text("Free on this phone: ${freeBytes?.let { Format.bytes(it) } ?: "unknown"}")
                Text(PartialDownload.memoryLine(model.recommended.minFreeMemoryBytes, availableMemoryBytes))
                Text(
                    "From your Mac, which fetches it from ${model.source.repo} and checks it; " +
                        "this phone checks it again before using it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (model.slowerOnPhone) {
                    Text(
                        "Larger and slower on a phone than the recommended model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (freeBytes != null && freeBytes < remaining) {
                    Text(
                        "There isn't room for it yet: free ${Format.bytes(remaining - freeBytes)} first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = mobile, onCheckedChange = { mobile = it })
                    Text(
                        "Use mobile data too. Otherwise it waits for Wi-Fi.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDownload(mobile) },
                enabled = freeBytes == null || freeBytes >= remaining,
            ) {
                Text(
                    when {
                        alreadyHere > 0 -> if (mobile) "Resume" else "Resume on Wi-Fi"
                        mobile -> "Download"
                        else -> "Download on Wi-Fi"
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

/**
 * The two sentences a row says about a model that is not simply here and working: what is
 * half-downloaded, and what it would need to run.
 *
 * Plain functions, because they are the part worth testing: the rest of the row is Compose.
 */
object PartialDownload {

    /** "38% of 1.2 GB is already here — paused", or null when nothing is part-finished. */
    fun line(received: Long?, total: Long): String? {
        if (received == null || received <= 0 || total <= 0) return null
        val percent = Math.round(received * 100.0 / total).coerceIn(0, 100)
        return "$percent% of ${Format.bytes(total)} is already here — paused"
    }

    /** "Needs 3.1 GB of free memory to run · 1.6 GB free now". */
    fun memoryLine(needed: Long, availableNow: Long?): String =
        "Needs ${Format.bytes(needed)} of free memory to run" +
            (availableNow?.let { " · ${Format.bytes(it)} free now" } ?: "")
}

/** "libggml-cpu-android_armv8.6_1.so [NEON,…]" → "ARMv8.6 (i8mm, dot product)". */
fun backendSummary(backends: String): String {
    val variant = Regex("android_armv([0-9.]+)_").find(backends)?.groupValues?.get(1)
    val features = Regex("\\[([^]]*)]").find(backends)?.groupValues?.get(1)?.split(",").orEmpty()
    val named = listOfNotNull(
        "i8mm".takeIf { features.any { it.startsWith("MATMUL_INT8") } },
        "dot product".takeIf { features.any { it.startsWith("DOTPROD") } },
        "SVE".takeIf { features.any { it == "SVE" } },
        "KleidiAI".takeIf { features.any { it.startsWith("KLEIDIAI") } },
    )
    return when {
        variant != null -> "ARMv$variant" + if (named.isNotEmpty()) " (${named.joinToString(", ")})" else ""
        else -> backends.substringBefore(';')
    }
}

private fun engineLine(state: OnDeviceEngine.State): String? = when (state) {
    OnDeviceEngine.State.Unloaded -> null
    is OnDeviceEngine.State.Loading -> "Loading ${state.model.label}…"
    is OnDeviceEngine.State.Loaded -> "${state.model.label} is loaded (${state.threads.generate} threads)"
    is OnDeviceEngine.State.Answering -> "${state.model.label} is answering (${state.threads.generate} threads)"
}
