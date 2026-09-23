package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Mac app's control API, mirrored field for field.
 *
 * The source of truth is Silicon Optimizer's `Sources/SiliconControl/ControlAPI.swift`.
 * The fixtures in `contract/` are real responses from a running Mac, and
 * `ContractTest` fails the build if a field goes missing on this side. Optionality is
 * copied exactly: anything the Mac may omit is nullable with a default here.
 */
@Serializable
data class Profile(
    val chip: String,
    val generation: String,
    val totalMemoryBytes: Long,
    val modelBudgetBytes: Long,
    val performanceCores: Int,
    val efficiencyCores: Int,
    val gpuCores: Int,
    val neuralEngineCores: Int,
    val memoryBandwidthGBps: Double,
    val diskFreeBytes: Long,
)

@Serializable
data class Metrics(
    val memoryUsedBytes: Long,
    val memoryWiredBytes: Long,
    val memoryTotalBytes: Long,
    val swapUsedBytes: Long,
    val gpuUtilization: Double,
    val cpuUtilization: Double,
    val memoryPressure: String,
) {
    val memoryFraction: Double
        get() = if (memoryTotalBytes > 0) memoryUsedBytes.toDouble() / memoryTotalBytes else 0.0
}

@Serializable
data class Status(
    val state: String,
    val loadedModelID: String? = null,
    val loadedModelName: String? = null,
    val contextLength: Int? = null,
    val expertStreaming: Boolean = false,
    val lastGenerationTokensPerSecond: Double? = null,
    val activity: String? = null,
    /**
     * Why the last load failed, when it did and the Mac knows. Absent while a model is
     * loading or loaded, and on a Mac from before it existed. [state] is still the line to
     * show; this is what lies behind it.
     */
    val failure: LoadFailure? = null,
    /**
     * Loads this Mac stopped before they finished — an unload part-way, or another load
     * started meanwhile. Newest first, at most four, one per model; absent when there are
     * none, and on a Mac from before it said so. A new load of a model clears its entry, so
     * one for the model this phone has just asked for is that load's ending.
     */
    val interruptedLoads: List<LoadInterruption>? = null,
) {
    val hasLoadedModel: Boolean get() = loadedModelID != null

    /** How the Mac stopped a load of [modelID], when it did. */
    fun interruption(modelID: String): LoadInterruption? =
        interruptedLoads?.firstOrNull { sameModel(it.modelID, modelID) }

    companion object {
        /** An installed id carries its quantization ("model@Q4_K_M"); a status may use either. */
        fun sameModel(a: String, b: String): Boolean =
            a == b || a.startsWith("$b@") || b.startsWith("$a@")
    }
}

/** A load the Mac stopped before it finished. Not a fault: somebody changed their mind. */
@Serializable
data class LoadInterruption(
    /** As `loadedModelID` spells it. */
    val modelID: String,
    /** `cancelled` for an unload, `replaced` for another load. Kept as text: it may grow. */
    val reason: String,
    /** The model whose load took over, when it was replaced. */
    val replacedBy: String? = null,
    /** ISO 8601, in the Mac's own offset. */
    val at: String,
) {
    enum class Kind { Cancelled, Replaced }

    /** The Mac's rule: a reason this app does not know reads as `cancelled`. */
    val kind: Kind get() = if (reason == "replaced") Kind.Replaced else Kind.Cancelled
}

/**
 * The facts behind a failed load. The sentence is not here: it is [Status.state], and the
 * Mac sends it once so the two cannot disagree.
 */
@Serializable
data class LoadFailure(
    /** A [Reason], as the Mac spells it. Kept as text: the Mac may add reasons. */
    val reason: String,
    /**
     * The runtime's own log tail — at most 20 lines, and never the line to show first.
     * Absent for a device paired for chat, which the Mac does not show its logs to.
     */
    val detail: String? = null,
    /** `llama.cpp`, `MLX`, `llama.cpp (PrismML)`. */
    val runtime: String? = null,
    val exitStatus: Int? = null,
    /** 9 on a Mac is nearly always the system taking the model's memory back. */
    val signal: Int? = null,
    /** True only when another load ended this one: not a fault, a change of plan. */
    val wasReplaced: Boolean = false,
    /** ISO 8601, in the Mac's own offset. */
    val at: String,
    /**
     * Whose load it was, as `loadedModelID` spells it. Absent from an older Mac. A failure
     * naming another model is somebody else's load, not the one being followed.
     */
    val modelID: String? = null,
) {
    enum class Reason(val wire: String) {
        Exited("exited"), Killed("killed"), Replaced("replaced"), Cancelled("cancelled"),
        TimedOut("timedOut"), LaunchFailed("launchFailed"), NotInstalled("notInstalled");

        companion object {
            /** The Mac's rule: a reason this app does not know yet reads as `exited`. */
            fun of(wire: String): Reason = entries.firstOrNull { it.wire == wire } ?: Exited
        }
    }

    val kind: Reason get() = if (wasReplaced) Reason.Replaced else Reason.of(reason)

    /** "llama.cpp · signal 9": which runtime, and how it ended, when the Mac said. */
    val facts: String?
        get() = listOfNotNull(
            runtime?.takeIf { it.isNotBlank() },
            signal?.let { "signal $it" } ?: exitStatus?.let { "exit status $it" },
        ).joinToString(" · ").ifEmpty { null }
}

@Serializable
data class InstalledModel(
    val id: String,
    val name: String,
    val quantization: String,
    val sizeOnDiskBytes: Long,
    val isLoaded: Boolean,
    val supportsVision: Boolean,
)

@Serializable
data class Suggestion(
    val title: String,
    val detail: String,
    val savingBytes: Long,
    val cost: String,
)

@Serializable
data class Plan(
    val verdict: String,
    val residentBytes: Long,
    val budgetBytes: Long,
    val weightsBytes: Long,
    val expertsBytes: Long,
    val kvCacheBytes: Long,
    /**
     * A hybrid model's fixed linear-attention state, already counted in [residentBytes].
     * Absent for a model whose every block keeps a KV cache, and on an older Mac.
     */
    val recurrentStateBytes: Long? = null,
    val computeBytes: Long,
    val streamedFromDiskBytes: Long,
    val suggestions: List<Suggestion>,
    val notes: List<String>,
)

@Serializable
data class Recommendation(
    val quantization: String,
    val contextLength: Int,
    val expertSlots: Int? = null,
    val estimatedGenerationTokensPerSecond: Double,
    val estimatedPromptTokensPerSecond: Double,
    val downloadBytes: Long,
    val plan: Plan,
    val rationale: String,
)

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val summary: String,
    val category: String,
    val parameters: String,
    val activeParameters: String? = null,
    val isMoE: Boolean,
    val capabilities: List<String>,
    val rating: Int,
    val maxContext: Int,
    val quantizations: List<String>,
    val recommendation: Recommendation? = null,
    val featured: Boolean? = null,
    val runtimeNote: String? = null,
) {
    val verdict: String? get() = recommendation?.plan?.verdict

    /** The Mac has no cloud entries yet; this is where they will be recognised. */
    val isCloud: Boolean
        get() = category.equals("cloud", ignoreCase = true) ||
            listOf("openai:", "anthropic:", "google:", "xai:", "groq:", "cloud:")
                .any { id.lowercase().startsWith(it) }

    fun matches(query: String): Boolean {
        val needle = query.lowercase()
        return name.lowercase().contains(needle) ||
            id.lowercase().contains(needle) ||
            author.lowercase().contains(needle) ||
            summary.lowercase().contains(needle) ||
            category.lowercase().contains(needle) ||
            capabilities.any { it.lowercase().contains(needle) }
    }
}

@Serializable
data class PlanRequest(
    val modelID: String,
    val quantization: String? = null,
    val contextLength: Int? = null,
    val kvCachePrecision: String? = null,
    val flashAttention: Boolean? = null,
    val expertSlots: Int? = null,
)

@Serializable
data class LoadRequest(
    val modelID: String,
    val quantization: String? = null,
    val contextLength: Int? = null,
    val expertSlots: Int? = null,
    val directory: String? = null,
)

@Serializable
data class ChatMessageWire(
    val role: String,
    val content: String,
    /** Base64 `data:` URLs. Only meaningful for vision models. */
    val images: List<String> = emptyList(),
)

@Serializable
data class ChatRequest(
    val messages: List<ChatMessageWire>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

@Serializable
data class ChatResponse(
    val content: String,
    val reasoning: String? = null,
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokensPerSecond: Double,
    /**
     * What the Mac's answer checking made of this reply, when it checked it. On the
     * buffered route it comes back with the answer rather than afterwards.
     */
    val verification: Verdict? = null,
)

@Serializable
data class ImagePhase(
    val name: String,
    val detail: String,
    val residentBytes: Long,
)

@Serializable
data class ImagePlan(
    val width: Int,
    val height: Int,
    val steps: Int,
    val quantization: String,
    val peakBytes: Long,
    val peakPhase: String,
    val budgetBytes: Long,
    val verdict: String,
    val phases: List<ImagePhase>,
    val suggestions: List<Suggestion>,
    val notes: List<String>,
)

@Serializable
data class ImageModel(
    val id: String,
    val name: String,
    val author: String,
    val license: String,
    val summary: String,
    val parameters: String,
    val blocks: Int,
    val defaultSteps: Int,
    val isGated: Boolean,
    val recommendation: ImagePlan? = null,
)

@Serializable
data class MeshModel(
    val id: String,
    val name: String,
    val author: String,
    val summary: String,
    val outputs: String,
    val typicalDuration: String,
    val peakBytes: Long,
    val weightsBytes: Long,
    val isInstalled: Boolean,
    val installDetail: String,
)

@Serializable
data class VideoModel(
    val id: String,
    val name: String,
    val summary: String,
    val typicalDuration: String,
    val supportsImageInput: Boolean,
    val supportedSeconds: List<Int>,
    val available: Boolean,
    val node: String? = null,
    val supportedParameters: List<String>? = null,
    /** The sizes this lane renders. Absent on a node that does not say. */
    val supportedResolutions: List<String>? = null,
    /** Whether this lane reads a negative prompt at all. */
    val supportsNegativePrompt: Boolean? = null,
)

@Serializable
data class VideoQueueItem(
    val id: String,
    val batchID: String,
    val title: String,
    val prompt: String,
    val scene: Int,
    val variation: Int,
    val seed: Long? = null,
    val modelID: String,
    val seconds: Int,
    val resolution: String,
    val h3Turbo: Boolean? = null,
    // The queue view spells these in camelCase; only VideoGenerateRequest uses the
    // snake_case form, and this is not that type.
    val h3Steps: Int? = null,
    val status: String,
    val nodeJobID: String? = null,
    val file: String? = null,
    val outputDirectory: String,
    val error: String? = null,
    val uncertainSubmission: Boolean,
    /** What to keep out of the shot, when the batch asked for anything. */
    val negativePrompt: String? = null,
    /** The Mac's own note about this take — why it chose what it chose. */
    val detail: String? = null,
    /** The finished clip, at `GET /media/{id}`: full control only. */
    val mediaID: String? = null,
    val mediaURL: String? = null,
    /** Its poster frame, which a chat-scope device may fetch too. */
    val thumbnailMediaID: String? = null,
    /**
     * Set once somebody asked the clip's node to cancel its render: `sending`,
     * `requested`, `confirmed`, `completed` (it finished first), `failed`, `unsupported`
     * or `unknown`. Absent on an older Mac, and on every clip nobody tried to cancel.
     */
    val cancelState: String? = null,
    /** The node's own words about that cancel, when it gave any. */
    val cancelDetail: String? = null,
    /**
     * Whether `cancel` applies to this clip now — the Mac's answer, per item, and the only
     * one this app goes by: its node advertises job cancellation for the lane, and the
     * render may still be running. Absent on a Mac that has no `cancel` verb at all.
     */
    val canCancel: Boolean? = null,
)

@Serializable
data class VideoQueueView(
    val paused: Boolean,
    val activeID: String? = null,
    val message: String? = null,
    val items: List<VideoQueueItem>,
)

@Serializable
data class SwarmCapability(
    val id: String,
    val kind: String,
    val ready: Boolean,
)

/**
 * One peer as the Mac's last poll saw it.
 *
 * Everything past the name, the address and whether it answered is optional, because a
 * poll is a memory: a node that was busy when the Mac asked says less than one that was
 * idle, and a node the Mac has not reached says almost nothing. `GET /swarm/peers/{name}/status`
 * is the same machine asked now, and the only place its adapter appears.
 */
@Serializable
data class SwarmPeer(
    val name: String,
    val baseURL: String,
    val reachable: Boolean,
    val error: String? = null,
    val capabilities: List<SwarmCapability>,
    val platform: String? = null,
    /** "NVIDIA GeForce RTX 3090 Ti" on a CUDA node, the chip on a Mac. */
    val hardware: String? = null,
    val totalMemoryGB: Double? = null,
    val usedMemoryGB: Double? = null,
    val headroomGB: Double? = null,
    val gpuUtilization: Double? = null,
    /** What is holding the GPU, when the node says. */
    val gpuConsumer: String? = null,
    val queueDepth: Int? = null,
    /** The GGUF it is serving, without the adapter riding on it. */
    val loadedModel: String? = null,
    val modelContextLength: Int? = null,
    val modelEngine: String? = null,
    /** Which kinds of work it will take, by the Mac's own reckoning. */
    val lanes: Map<String, Boolean>? = null,
)

@Serializable
data class SwarmView(
    val peers: List<SwarmPeer>,
    /**
     * Whether this Mac is listening on its tailnet address, and where. The phone is on
     * the other end of exactly that socket, so "requested but not listening" is the
     * difference between a Mac that is off and one that is broken.
     */
    val exposure: TailnetExposure? = null,
    val polledSecondsAgo: Double? = null,
)

@Serializable
data class TailnetExposure(
    val requested: Boolean,
    val listening: Boolean,
    val address: String? = null,
    val port: Int? = null,
    val problem: String? = null,
)

/** `GET /v1/node` — snake_case on the wire, the shape silicon-node speaks too. */
@Serializable
data class MacProfile(
    val chip: String,
    @SerialName("memory_gb") val memoryGB: Double,
    @SerialName("bandwidth_gbps") val bandwidthGBps: Double,
    @SerialName("gpu_cores") val gpuCores: Int,
)

@Serializable
data class NodeCapability(
    val id: String,
    val kind: String,
    val ready: Boolean,
    @SerialName("peak_gb") val peakGB: Double? = null,
    @SerialName("typical_seconds") val typicalSeconds: Double? = null,
    val detail: String,
)

@Serializable
data class NodeMetrics(
    @SerialName("queue_depth") val queueDepth: Int,
    @SerialName("headroom_gb") val headroomGB: Double,
    @SerialName("gpu_util_pct") val gpuUtilPct: Int,
    @SerialName("memory_used_pct") val memoryUsedPct: Int,
)

@Serializable
data class NodeAdvertisement(
    val name: String,
    val platform: String,
    val profile: MacProfile,
    val capabilities: List<NodeCapability>,
    val metrics: NodeMetrics,
)

@Serializable
data class ErrorResponse(val error: String)

/**
 * `GET /health`, the one unauthenticated route.
 *
 * `appVersion` and `appBuild` are the Mac app's own CFBundleShortVersionString and
 * CFBundleVersion. A Mac from before them sends only `version`, which it wrote as a
 * literal "0.1.0" whatever it was (OGZamasu/silicon-optimizer#81); a newer one repeats
 * `appVersion` there for the clients that read nothing else.
 */
@Serializable
data class Health(
    val status: String,
    val version: String? = null,
    val appVersion: String? = null,
    val appBuild: String? = null,
) {
    /**
     * What the phone calls the Mac's version: "0.5.0 (157)" from a Mac that says both,
     * the old `version` from one that says neither, null from one that says nothing.
     * Only a named app version brings its build along — a build number beside the old
     * literal would dress it up as something it isn't.
     */
    val appVersionLabel: String?
        get() {
            appVersion?.trim()?.takeIf { it.isNotEmpty() }?.let { named ->
                val build = appBuild?.trim()?.takeIf { it.isNotEmpty() && it != named }
                return if (build != null) "$named ($build)" else named
            }
            return version?.trim()?.takeIf { it.isNotEmpty() }
        }
}

/** `POST /install` and `POST /unload` both answer `{"status": "…"}`. */
@Serializable
data class StatusMessage(val status: String)
