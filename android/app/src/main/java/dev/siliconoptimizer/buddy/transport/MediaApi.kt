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
    /** One negative prompt for the batch: the same model and settings run all of it. */
    val negativePrompt: String? = null,
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
    /** What to keep out of the shot, on a lane that says it reads one. */
    val negativePrompt: String? = null,
    /**
     * A still to animate, named the way a device may name one: an id from
     * `POST /uploads`, or a `mediaID` this Mac published with an earlier result.
     */
    val uploadID: String? = null,
    val mediaID: String? = null,
    /**
     * A path on the Mac. Mirrored because the Mac still accepts one from its own
     * tools; never sent from here — a request from a paired device that names a path
     * is refused, and rightly: a device that could name a file could name any file.
     */
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

/**
 * How long a synchronous render may take, from the Mac's own `VideoGenerationBudget`.
 *
 * The Mac allows an accepted node job twelve hours, which is not a number a phone can
 * hold a socket for. What a client can bound is the two things that mean something has
 * gone wrong rather than slow: silence, and a total wait after which the queue is the
 * better place to look. Both are the Mac's constants rather than invented ones.
 */
object RenderBudget {
    const val NODE_REQUEST_SECONDS = 120
    const val NETWORK_RESOURCE_SECONDS = 600
    const val DOWNLOAD_SECONDS = 600
    const val RESPONSE_OVERHEAD_SECONDS = 60

    /** Nothing at all from the Mac for this long is a broken connection. */
    const val IDLE_SECONDS = NETWORK_RESOURCE_SECONDS

    /** The whole wait a phone will hold before pointing at the queue instead. */
    const val TOTAL_SECONDS = NODE_REQUEST_SECONDS + NETWORK_RESOURCE_SECONDS +
        DOWNLOAD_SECONDS + RESPONSE_OVERHEAD_SECONDS
}

/** What a finished clip is: a file on the Mac, and the ids that fetch it. */
@Serializable
data class VideoResponse(
    val file: String,
    val node: String,
    val model: String,
    val elapsedSeconds: Double,
    /** `GET /media/{id}` — the clip itself, for a device with full control. */
    val mediaID: String? = null,
    val mediaURL: String? = null,
    /** A poster frame, which a chat-scope device may fetch as well. */
    val thumbnailMediaID: String? = null,
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
    /** A picture to start from, named without naming a path. */
    val uploadID: String? = null,
    val mediaID: String? = null,
    /** The Mac's own spelling of the same thing. Never sent from a device. */
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
    /** The picture itself, at `GET /media/{id}`. */
    val mediaID: String? = null,
    val mediaURL: String? = null,
)

// MARK: - Mesh

/** `POST /mesh/plan` and `POST /mesh/generate`, which share a body as the image ones do. */
@Serializable
data class MeshRequest(
    /**
     * The conditioning image as a path on the Mac. Optional since `POST /uploads`
     * landed, and never sent from here: a device names a picture by the id it was
     * given, not by where it sits on somebody's disk.
     */
    val imagePath: String? = null,
    /** A picture this device sent, or one this Mac published with an earlier result. */
    val uploadID: String? = null,
    val mediaID: String? = null,
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
    /** The GLB, at `GET /media/{id}`; the OBJ has an id of its own. */
    val mediaID: String? = null,
    val mediaURL: String? = null,
    val objMediaID: String? = null,
)

// MARK: - Uploads and media

/**
 * `POST /uploads`: how a device names a picture without naming a path.
 *
 * The Mac reads the type off the bytes rather than believing the request, keeps the
 * file for seven days, and answers with the two ids a render can start from. Full
 * control only — an upload spends the owner's disk.
 */
@Serializable
data class UploadResponse(
    val uploadID: String,
    val mediaID: String,
    val bytes: Long,
    /** What the Mac read off the bytes, which is not always what was claimed. */
    val contentType: String,
    /** Relative, like every media link this server hands out: `/media/<id>`. */
    val mediaURL: String,
    /** When the Mac will sweep it, so a phone can say "until" rather than find a 404. */
    val expiresAt: String,
)

object Uploads {
    /** This route's own ceiling. Every other route a device can reach keeps 4 MiB. */
    const val MAXIMUM_BYTES = 24L * 1024 * 1024

    /** What the Mac will keep. Anything else is a 415, decided from the bytes. */
    val ACCEPTED_TYPES = listOf(
        "image/png", "image/jpeg", "image/gif", "image/webp",
        "video/mp4", "video/quicktime", "video/webm",
    )
}

/** One peer, asked now rather than remembered: `GET /swarm/peers/{name}/status`. */
@Serializable
data class PeerGguf(
    val running: Boolean,
    /** The GGUF file being served, when one is. */
    val model: String? = null,
    /** The LoRA riding on it — the one thing `GET /swarm` cannot carry. */
    val adapter: String? = null,
    /** Which build is serving it: "stock" or "prism". */
    val engine: String? = null,
    val contextLength: Int? = null,
    val uptimeSeconds: Double? = null,
    /** On the node's disk, serving or not. */
    val installedModels: List<String> = emptyList(),
    val adapters: List<String> = emptyList(),
)

@Serializable
data class PeerNodeStatus(
    val name: String,
    val baseURL: String,
    val reachable: Boolean,
    val error: String? = null,
    val platform: String? = null,
    /** "NVIDIA GeForce RTX 3090 Ti" on a CUDA node, the chip on a Mac. */
    val hardware: String? = null,
    val totalMemoryGB: Double? = null,
    val usedMemoryGB: Double? = null,
    val headroomGB: Double? = null,
    val gpuUtilization: Double? = null,
    val queueDepth: Int? = null,
    val capabilities: List<SwarmCapability> = emptyList(),
    val gguf: PeerGguf? = null,
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
    val displayName: String = "",
    val summary: String = "",
    val built: Boolean = false,
    val available: Boolean = false,
    val enabled: Boolean = false,
    val calls: Int = 0,
    val estimatedUSD: Double = 0.0,
    val inputTokens: Long = 0,
)

/** `GET /jev`: how the TypeSafe lane is set up, and what it has cost. */
@Serializable
data class JevView(
    val enabled: Boolean = false,
    val keySet: Boolean = false,
    val model: String = "",
    val availableModels: List<String> = emptyList(),
    val models: Map<String, Int> = emptyMap(),
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val estimatedUSD: Double = 0.0,
    val monthlyUSD: Map<String, Double> = emptyMap(),
    val month: String = "",
    val monthlyBudgetUSD: Double = 0.0,
    val budgetRemainingUSD: Double = 0.0,
    val cacheMinutes: Int = 0,
    val composerAutoRoute: Boolean = false,
    val automaticUncensoredLaneInEffect: Boolean = false,
    val ledgerWriteFailed: Boolean = false,
    val maxStateBytes: Long = 0,
    val features: List<JevFeature> = emptyList(),
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
