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
) {
    val hasLoadedModel: Boolean get() = loadedModelID != null
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

@Serializable
data class SwarmPeer(
    val name: String,
    val baseURL: String,
    val reachable: Boolean,
    val error: String? = null,
    val capabilities: List<SwarmCapability>,
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

/** `GET /health`, the one unauthenticated route. */
@Serializable
data class Health(val status: String, val version: String)

/** `POST /install` and `POST /unload` both answer `{"status": "…"}`. */
@Serializable
data class StatusMessage(val status: String)
