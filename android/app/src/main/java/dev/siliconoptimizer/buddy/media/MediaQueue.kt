package dev.siliconoptimizer.buddy.media

import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.VideoQueueItem
import dev.siliconoptimizer.buddy.transport.VideoQueueView

/**
 * The queue, as the phone holds it.
 *
 * Two sources say what is happening and neither is enough on its own. `GET /video/queue`
 * knows the prompts, the settings, the output folder and — the part that matters when
 * something goes wrong — the Mac's own sentence about why an item failed. The `job`
 * events on `/events` know the fraction, and arrive without being asked. So this is a
 * reducer over both: the queue is the truth, the events are the movement, and a live
 * fraction is not thrown away by a poll that happens to land mid-render.
 *
 * Kept pure so the sequences that are awkward to produce against a real Mac — a render
 * that fails, a retry, an event for an item the queue has not listed yet — are tests
 * rather than hopes.
 */

/** What the Mac's status word means here. Its vocabulary, not ours: it grows. */
enum class JobState {
    Queued, Submitting, Rendering, Done, Failed, Stopped, Unknown;

    val isTerminal: Boolean get() = this == Done || this == Failed || this == Stopped
    val isRunning: Boolean get() = this == Submitting || this == Rendering

    val label: String
        get() = when (this) {
            Queued -> "Queued"
            Submitting -> "Handing to the node"
            Rendering -> "Rendering"
            Done -> "Done"
            Failed -> "Failed"
            Stopped -> "Stopped"
            Unknown -> "Working"
        }

    companion object {
        fun of(wire: String): JobState = when (wire.lowercase()) {
            "pending", "queued", "waiting" -> Queued
            "submitting", "submitted" -> Submitting
            "rendering", "running", "generating" -> Rendering
            "completed", "complete", "finished", "done", "succeeded" -> Done
            "failed", "error" -> Failed
            "cancelled", "canceled", "stopped" -> Stopped
            else -> Unknown
        }
    }
}

/** One row of the Queue screen. A clip, an image or a mesh — whatever the Mac is doing. */
data class MediaJob(
    val id: String,
    /** `video`, `image` or `mesh`, as the Mac's `job` event spells it. */
    val kind: String,
    val title: String,
    val state: JobState,
    /** The Mac's own word, kept so an unfamiliar one can still be shown. */
    val statusWord: String,
    val fraction: Double? = null,
    val prompt: String? = null,
    /** Model, duration, size — whatever the queue knows about how it is being made. */
    val settings: String? = null,
    /** Why it failed, in the Mac's words. Only `GET /video/queue` carries this. */
    val error: String? = null,
    val file: String? = null,
    val outputDirectory: String? = null,
    val scene: Int? = null,
    val variation: Int? = null,
    val isActive: Boolean = false,
    /** True for rows that came from `/video/queue` and so can be controlled. */
    val isQueued: Boolean = true,
    /** The Mac is not sure the node took it; retrying may render it twice. */
    val uncertainSubmission: Boolean = false,
) {
    val canRetry: Boolean get() = isQueued && (state == JobState.Failed || state == JobState.Stopped)

    /**
     * Removing needs a state that is positively still — waiting, or over. A word this
     * build has never heard of is not one of those: the Mac may well be in the middle
     * of something, and a button that removes it would be guessing.
     */
    val canRemove: Boolean
        get() = isQueued && (state == JobState.Queued || state.isTerminal)

    val canStopFollowing: Boolean get() = isQueued && isActive && state.isRunning

    /** One line under the title: the reason when there is one, the settings otherwise. */
    val detail: String?
        get() = error ?: settings

    companion object {
        fun of(item: VideoQueueItem, activeID: String?, fraction: Double? = null): MediaJob {
            val settings = buildList {
                add(item.modelID)
                add("${item.seconds}s")
                add(item.resolution)
                item.h3Steps?.let { add("$it steps") }
                if (item.h3Turbo == true) add("turbo")
            }.joinToString(" · ")
            return MediaJob(
                id = item.id,
                kind = "video",
                title = item.title.ifBlank { "Untitled" },
                state = JobState.of(item.status),
                statusWord = item.status,
                fraction = fraction,
                prompt = item.prompt,
                settings = settings,
                error = item.error,
                file = item.file,
                outputDirectory = item.outputDirectory,
                scene = item.scene,
                variation = item.variation,
                isActive = item.id == activeID,
                isQueued = true,
                uncertainSubmission = item.uncertainSubmission,
            )
        }
    }
}

/**
 * Whether a row may move from one state to another.
 *
 * Events arrive late, twice and out of order, and a poll can answer with a snapshot
 * older than the event that overtook it. Both would otherwise walk a finished clip
 * backwards into "rendering" and leave it there for good, because nothing after it
 * would say otherwise. So an ending is final: only being queued again — which is what a
 * retry is — moves a row out of it.
 */
internal fun mayMove(from: JobState?, to: JobState): Boolean = when {
    from == null -> true
    !from.isTerminal -> true
    else -> to == JobState.Queued
}

/** The whole queue: what the Mac is doing, and whether it is doing it. */
data class QueueState(
    val paused: Boolean = false,
    val message: String? = null,
    val activeID: String? = null,
    val jobs: List<MediaJob> = emptyList(),
) {
    val running: List<MediaJob> get() = jobs.filter { !it.state.isTerminal }
    val finished: List<MediaJob> get() = jobs.filter { it.state.isTerminal }

    fun job(id: String): MediaJob? = jobs.firstOrNull { it.id == id }

    /**
     * A fresh `GET /video/queue`.
     *
     * Fractions live only on the event stream, so the ones already known are carried
     * across. Rows the queue does not list are dropped — unless they never came from it:
     * an image or a mesh is a `job` event and nothing else, and a poll of the video
     * queue must not make one disappear.
     */
    fun applying(view: VideoQueueView): QueueState {
        val known = jobs.associateBy { it.id }
        val fromQueue = view.items.map { item ->
            val existing = known[item.id]
            val incoming = JobState.of(item.status)
            val fresh = MediaJob.of(item, view.activeID, existing?.fraction)
            when {
                existing == null -> fresh
                // A snapshot older than what the stream already said. Its prompt, its
                // settings and its reason are still worth having; its state is not.
                !mayMove(existing.state, incoming) -> fresh.copy(
                    state = existing.state,
                    statusWord = existing.statusWord,
                    fraction = existing.fraction,
                    isActive = false,
                )
                incoming == JobState.Queued -> fresh.copy(fraction = null)
                else -> fresh
            }
        }
        val others = jobs.filterNot { it.isQueued }
        val active = fromQueue.firstOrNull { it.id == view.activeID && !it.state.isTerminal }?.id
        return copy(
            paused = view.paused,
            message = view.message,
            activeID = active,
            jobs = fromQueue.map { it.copy(isActive = it.id == active) } + others,
        )
    }

    /**
     * One `job` event.
     *
     * The event carries an id, a kind, a status, a title and sometimes a fraction —
     * and nothing else, so a failure arriving this way has no reason attached to it.
     * The row keeps whatever the queue said last, and a retry clears it: a stale "no
     * node accepted this" under a clip that is rendering again would be a lie.
     */
    fun applying(event: JobProgress): QueueState {
        val state = JobState.of(event.status)
        val existing = job(event.id)
        if (existing != null && !mayMove(existing.state, state)) {
            // Late progress for a clip that has already ended, or the same ending
            // twice. Neither is news, and neither may undo the ending.
            return this
        }
        val updated = when {
            existing != null -> existing.copy(
                state = state,
                statusWord = event.status,
                title = event.title?.takeIf { it.isNotBlank() } ?: existing.title,
                fraction = fractionFor(existing, state, event.fraction),
                // A fresh attempt is not the old failure.
                error = if (state == JobState.Queued || state.isRunning) null else existing.error,
            )
            else -> MediaJob(
                id = event.id,
                kind = event.kind,
                title = event.title?.takeIf { it.isNotBlank() } ?: event.kind.replaceFirstChar {
                    it.uppercase()
                },
                state = state,
                statusWord = event.status,
                fraction = if (state == JobState.Done) 1.0 else event.fraction,
                // Only the video queue lists items; an image or a mesh is this event
                // and nothing more, so it cannot be paused, retried or removed.
                isQueued = false,
            )
        }
        // Only the video queue has an "active" item. An image or a mesh running on the
        // Mac must not take that title from the clip the queue is rendering.
        val active = when {
            !updated.isQueued -> activeID
            state.isRunning -> event.id
            activeID == event.id -> null
            else -> activeID
        }
        val merged = if (existing != null) {
            jobs.map { if (it.id == event.id) updated else it }
        } else {
            jobs + updated
        }
        return copy(
            jobs = merged.map { it.copy(isActive = it.isQueued && it.id == active) },
            activeID = active,
        )
    }

    /**
     * A bar that goes backwards reads as a render starting over. Inside one attempt a
     * fraction only grows; a new attempt — being queued again — starts from nothing.
     */
    private fun fractionFor(existing: MediaJob, state: JobState, reported: Double?): Double? =
        when {
            state == JobState.Done -> 1.0
            state == JobState.Queued -> null
            state.isTerminal -> existing.fraction
            reported == null -> existing.fraction
            existing.fraction == null -> reported
            else -> maxOf(existing.fraction, reported)
        }

    companion object {
        val empty = QueueState()
    }
}
