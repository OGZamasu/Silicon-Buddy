package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The media routes: what this app sends to start an image, a video or a mesh, and what
 * comes back.
 *
 * The reading half of these — `ImageModel`, `MeshModel`, `VideoModel`, `VideoQueueView`
 * — has been mirrored since M1 in `ControlApi.kt`; this file is the writing half, which
 * M3 needs. The source of truth is the same: Silicon Optimizer's
 * `Sources/SiliconControl/ControlAPI.swift` and `VideoQueueAPI.swift`. The fixtures in
 * `contract/` show one example body per route rather than every field a route accepts,
 * so the optional fields here are the Mac's own, spelled the way it decodes them —
 * `h3_turbo` and the rest keep their snake_case on the wire.
 *
 * Nothing here sends bytes. Every route that takes a picture — image-to-video, a mesh's
 * conditioning image, an image revision — takes a *path on the Mac*, and the control
 * API has no route that accepts an upload. See `docs/PLAN.md` for what that costs the
 * phone.
 */

// MARK: - Video

/**
 * `POST /video/queue`: work saved on the Mac, rendered without holding a connection.
 *
 * One prompt per scene, `variations` takes of each. The Mac numbers the batch and
 * decides when to run it; this is the route the phone should use for anything longer
 * than a moment, because `POST /video/generate` holds the socket open for minutes.
 */
@Serializable
data class VideoQueueRequest(
    val prompts: List<String>,
    val title: String? = null,
    val variations: Int? = null,
    val modelID: String? = null,
    val seconds: Int? = null,
    val resolution: String? = null,
    val seed: Long? = null,
    @SerialName("h3_turbo") val h3Turbo: Boolean? = null,
    @SerialName("h3_steps") val h3Steps: Int? = null,
)

/**
 * `POST /video/queue/control`.
 *
 * The Mac's vocabulary, which is narrower than "cancel": `pause`, `resume`, `retry`,
 * `remove`, `stop_following`, `clear_finished`. There is no cancel, and
 * `stop_following` is why — a clip already handed to a node keeps rendering there, and
 * the Mac will not claim otherwise. Three of these need an item id.
 */
@Serializable
data class VideoQueueControlRequest(
    val action: String,
    val id: String? = null,
    /** `retry` on an item that may already have produced a file, sent deliberately. */
    val confirmNewRender: Boolean? = null,
) {
    companion object {
        const val PAUSE = "pause"
        const val RESUME = "resume"
        const val RETRY = "retry"
        const val REMOVE = "remove"
        const val STOP_FOLLOWING = "stop_following"
        const val CLEAR_FINISHED = "clear_finished"

        /** The actions that mean nothing without an item. */
        val NEEDS_ID = setOf(RETRY, REMOVE, STOP_FOLLOWING)
    }
}

/** `POST /video/generate`: one clip, waited for. At most eight of these at once. */
@Serializable
data class VideoGenerateRequest(
    val prompt: String,
    val modelID: String? = null,
    val seconds: Int? = null,
    val resolution: String? = null,
    /** A still to animate — a path on the Mac, which a phone has no way to write. */
    val imagePath: String? = null,
    @SerialName("h3_chain_prompts") val h3ChainPrompts: List<String>? = null,
    val seed: Long? = null,
    @SerialName("h3_turbo") val h3Turbo: Boolean? = null,
    @SerialName("h3_steps") val h3Steps: Int? = null,
) {
    companion object {
        const val MINIMUM_SECONDS = 1
        const val MAXIMUM_SECONDS = 15
    }
}

/** What a finished clip is: a file on the Mac, and how long it took. */
@Serializable
data class VideoResponse(
    val file: String,
    val node: String,
    val model: String,
    val elapsedSeconds: Double,
)

// MARK: - Image

/**
 * `POST /image/plan` and `POST /image/generate` take the same body: the plan is the
 * generate request answered with memory instead of pixels.
 */
@Serializable
data class ImageRequest(
    val prompt: String,
    val modelID: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val steps: Int? = null,
    val quantization: String? = null,
    val seed: Long? = null,
    /** True keeps the prompt on this Mac even when a node is paired. */
    val localOnly: Boolean? = null,
    /** A picture to start from — again, a path on the Mac. */
    val initImagePath: String? = null,
    val initImageInfluence: Double? = null,
)

@Serializable
data class ImageResponse(
    val path: String,
    val elapsedSeconds: Double,
    val peakMemoryBytes: Long? = null,
    val predictedPeakBytes: Long,
    val model: String,
    /** Set when the plan said this would not comfortably fit and it was run anyway. */
    val warning: String? = null,
)

// MARK: - Mesh

/** `POST /mesh/plan` and `POST /mesh/generate`, which share a body as the image ones do. */
@Serializable
data class MeshRequest(
    /**
     * The conditioning image, as a path on the Mac. The phone cannot put one there:
     * there is no upload route, so this is typed by the owner or picked from what the
     * Mac already has.
     */
    val imagePath: String,
    val modelID: String? = null,
    val pipelineType: String? = null,
    val textureSize: Int? = null,
    val steps: Int? = null,
    val quantize: Int? = null,
    val octree: Int? = null,
    val vertexBudget: Int? = null,
    val seed: Long? = null,
)

/** What a mesh would cost, phase by phase, here or on the node. */
@Serializable
data class MeshPlan(
    val model: String,
    val peakBytes: Long,
    val peakPhase: String,
    val budgetBytes: Long,
    val verdict: String,
    /** True when the node would run it, which changes what the numbers mean. */
    val isRemote: Boolean,
    val phases: List<ImagePhase>,
    val suggestions: List<Suggestion>,
    val notes: List<String>,
)

@Serializable
data class MeshResponse(
    val glbPath: String? = null,
    val objPath: String? = null,
    val elapsedSeconds: Double,
    val model: String,
    val warning: String? = null,
)

// MARK: - Jev

/**
 * One thing Jev can be asked to do, and whether it is switched on.
 *
 * Mirrored for one field: `mediaRouting`. When that feature is available, the Mac can
 * read a prompt and pick the model and settings itself, which is what the "Auto" lane
 * in the Create tab is. When it is not, there is no auto to offer, and a picker that
 * offered it anyway would be a button that quietly did something else.
 */
@Serializable
data class JevFeature(
    val id: String,
    val displayName: String,
    val summary: String,
    val built: Boolean,
    val available: Boolean,
    val enabled: Boolean,
    val calls: Int,
    val estimatedUSD: Double,
    val inputTokens: Long,
)

/** `GET /jev`: how the TypeSafe lane is set up, and what it has cost. */
@Serializable
data class JevView(
    val enabled: Boolean,
    val keySet: Boolean,
    val model: String,
    val availableModels: List<String>,
    val models: Map<String, Int>,
    val calls: Int,
    val inputTokens: Long,
    val estimatedUSD: Double,
    val monthlyUSD: Map<String, Double>,
    val month: String,
    val monthlyBudgetUSD: Double,
    val budgetRemainingUSD: Double,
    val cacheMinutes: Int,
    val composerAutoRoute: Boolean,
    val automaticUncensoredLaneInEffect: Boolean,
    val ledgerWriteFailed: Boolean,
    val maxStateBytes: Long,
    val features: List<JevFeature>,
) {
    private val mediaRouting: JevFeature? get() = features.firstOrNull { it.id == MEDIA_ROUTING }

    /** True when the Mac has the media router at all — built, keyed and available. */
    val advertisesMediaRouting: Boolean get() = mediaRouting?.available == true

    /**
     * Whether the Mac would actually route a media request itself. Available but
     * switched off is not auto: the picker would be offering something that quietly
     * did something else.
     */
    val routesMedia: Boolean
        get() = enabled && mediaRouting?.let { it.available && it.enabled } == true

    companion object {
        const val MEDIA_ROUTING = "mediaRouting"
    }
}
