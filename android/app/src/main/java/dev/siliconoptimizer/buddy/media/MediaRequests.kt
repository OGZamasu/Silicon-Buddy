package dev.siliconoptimizer.buddy.media

import dev.siliconoptimizer.buddy.transport.ImageModel
import dev.siliconoptimizer.buddy.transport.ImageRequest
import dev.siliconoptimizer.buddy.transport.MeshModel
import dev.siliconoptimizer.buddy.transport.MeshRequest
import dev.siliconoptimizer.buddy.transport.VideoGenerateRequest
import dev.siliconoptimizer.buddy.transport.VideoModel
import dev.siliconoptimizer.buddy.transport.VideoQueueRequest

/**
 * What the Create tab is allowed to ask for.
 *
 * Pure, and separate from the screen, because these are the rules that decide whether a
 * render is possible at all: a lane that runs five and ten second clips must never be
 * sent eight, "auto" must not appear unless the Mac said it routes media itself, and a
 * variations box must not turn a typo into two hundred clips. A screen can be looked at;
 * these can be tested.
 */

/** Where a clip would be rendered. */
sealed interface VideoLane {
    /** Let the Mac read the prompt and pick. Offered only when Jev routes media. */
    data object Auto : VideoLane

    data class On(val model: VideoModel) : VideoLane

    val id: String? get() = (this as? On)?.model?.id
    val label: String get() = when (this) {
        Auto -> "Auto"
        is On -> model.name
    }
}

object VideoRequest {

    /** The Mac's own queue limits, from `VideoBatchQueue`. */
    const val MAXIMUM_VARIATIONS = 20
    const val MINIMUM_VARIATIONS = 1

    /**
     * The durations the wire contract allows when nothing narrower is known — the
     * Mac's `VideoGenerateRequest.pickerSeconds`. A named lane replaces these with its
     * own `supportedSeconds`, which is the list that actually matters.
     */
    val FALLBACK_SECONDS = listOf(3, 5, 8, 10, 15)

    /**
     * The lanes to offer. Auto first when the Mac advertises it, then every model —
     * unavailable ones included, because "Hailuo H3, no node" is worth seeing and a
     * silently missing row is not.
     */
    fun lanes(models: List<VideoModel>, routesMedia: Boolean): List<VideoLane> =
        buildList {
            if (routesMedia) add(VideoLane.Auto)
            addAll(models.map { VideoLane.On(it) })
        }

    /** Whether this lane can be rendered on right now. */
    fun isAvailable(lane: VideoLane): Boolean = when (lane) {
        VideoLane.Auto -> true
        is VideoLane.On -> lane.model.available
    }

    /** The seconds a lane offers, in order. Never empty, never outside 1–15. */
    fun secondsChoices(lane: VideoLane?): List<Int> {
        val advertised = (lane as? VideoLane.On)?.model?.supportedSeconds.orEmpty()
            .filter { it in VideoGenerateRequest.MINIMUM_SECONDS..VideoGenerateRequest.MAXIMUM_SECONDS }
            .distinct()
            .sorted()
        return advertised.ifEmpty { FALLBACK_SECONDS }
    }

    /**
     * The duration to send. A value the lane does not support becomes the nearest one
     * it does, rather than a 400 from the Mac or, worse, a silently different clip.
     */
    fun seconds(lane: VideoLane?, wanted: Int?): Int {
        val choices = secondsChoices(lane)
        if (wanted == null) return choices.firstOrNull { it == 5 } ?: choices.first()
        return choices.minByOrNull { kotlin.math.abs(it - wanted) } ?: choices.first()
    }

    /** One to twenty, which is what the Mac's queue will accept. */
    fun variations(wanted: Int): Int = wanted.coerceIn(MINIMUM_VARIATIONS, MAXIMUM_VARIATIONS)

    /**
     * The body for `POST /video/queue`, or null when there is nothing to send.
     *
     * On the auto lane the model and the duration are left out on purpose: the Mac is
     * being asked to choose them, and a duration chosen here would be checked against a
     * model this phone has not picked.
     */
    fun enqueue(
        prompt: String,
        lane: VideoLane?,
        title: String? = null,
        seconds: Int? = null,
        variations: Int = 1,
    ): VideoQueueRequest? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        if (lane != null && !isAvailable(lane)) return null
        val auto = lane == null || lane is VideoLane.Auto
        return VideoQueueRequest(
            prompts = listOf(text),
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            variations = variations(variations),
            modelID = lane?.id,
            seconds = if (auto) null else seconds(lane, seconds),
        )
    }

    /** The body for `POST /video/generate`: one clip, waited for. */
    fun generate(prompt: String, lane: VideoLane?, seconds: Int? = null): VideoGenerateRequest? {
        val text = prompt.trim()
        if (text.isEmpty()) return null
        if (lane != null && !isAvailable(lane)) return null
        val auto = lane == null || lane is VideoLane.Auto
        return VideoGenerateRequest(
            prompt = text,
            modelID = lane?.id,
            seconds = if (auto) null else seconds(lane, seconds),
        )
    }
}

object ImageRequestBuilder {

    /** Square sizes the picker offers; the Mac plans whatever it is given. */
    val SIZES = listOf(512, 768, 1024, 1280, 1536)

    const val MINIMUM_STEPS = 1
    const val MAXIMUM_STEPS = 50

    /** The model's own default, which is the number its plan was made with. */
    fun defaultSteps(model: ImageModel?): Int =
        (model?.defaultSteps ?: 8).coerceIn(MINIMUM_STEPS, MAXIMUM_STEPS)

    fun defaultSize(model: ImageModel?): Int =
        model?.recommendation?.width?.takeIf { it in SIZES } ?: 1024

    fun steps(wanted: Int): Int = wanted.coerceIn(MINIMUM_STEPS, MAXIMUM_STEPS)

    /**
     * The body both `/image/plan` and `/image/generate` take. Null when there is no
     * prompt or no model: the Mac would answer 400, and saying so here is quicker.
     */
    fun build(prompt: String, model: ImageModel?, size: Int, steps: Int): ImageRequest? {
        val text = prompt.trim()
        if (text.isEmpty() || model == null) return null
        val side = if (size in SIZES) size else defaultSize(model)
        return ImageRequest(
            prompt = text,
            modelID = model.id,
            width = side,
            height = side,
            steps = steps(steps),
        )
    }
}

object MeshRequestBuilder {

    val TEXTURE_SIZES = listOf(1024, 2048, 4096)

    /**
     * A path on the Mac, because that is all `/mesh/plan` and `/mesh/generate` take.
     * There is no route that accepts an upload, so a picture on this phone cannot be
     * the subject of a mesh until the Mac grows one.
     */
    fun isMacPath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed.startsWith("/") && !trimmed.endsWith("/") && trimmed.length > 1
    }

    fun build(imagePath: String, model: MeshModel?, textureSize: Int): MeshRequest? {
        val path = imagePath.trim()
        if (!isMacPath(path)) return null
        return MeshRequest(
            imagePath = path,
            modelID = model?.id,
            textureSize = if (textureSize in TEXTURE_SIZES) textureSize else TEXTURE_SIZES[1],
        )
    }
}
