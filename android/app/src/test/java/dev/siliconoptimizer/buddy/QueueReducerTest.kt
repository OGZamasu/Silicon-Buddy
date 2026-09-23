package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.CancelState
import dev.siliconoptimizer.buddy.media.JobState
import dev.siliconoptimizer.buddy.media.cancelNeedsTheQueue
import dev.siliconoptimizer.buddy.media.QueueState
import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.VideoQueueItem
import dev.siliconoptimizer.buddy.transport.VideoQueueView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue, over a sequence of events.
 *
 * A render that fails and is retried is the case worth having tests for: it is minutes
 * of waiting to produce by hand, it happens on somebody else's machine, and the phone's
 * job is to keep saying something true throughout — including not leaving last attempt's
 * reason under a clip that is now running again.
 */
class QueueReducerTest {

    private fun item(
        id: String = "9C2F-0001",
        status: String = "pending",
        error: String? = null,
        file: String? = null,
        uncertain: Boolean = false,
    ) = VideoQueueItem(
        id = id, batchID = "9C2F", title = "Lisbon",
        prompt = "A tram climbing Alfama at dawn", scene = 1, variation = 1, seed = 424242,
        modelID = "hailuo-h3", seconds = 5, resolution = "768P", h3Turbo = false,
        h3Steps = 30, status = status, nodeJobID = "job-1187", file = file,
        outputDirectory = "/Users/you/Movies/Silicon/Lisbon", error = error,
        uncertainSubmission = uncertain,
    )

    private fun view(vararg items: VideoQueueItem, active: String? = null, paused: Boolean = false) =
        VideoQueueView(paused = paused, activeID = active, message = null, items = items.toList())

    private fun job(
        id: String = "9C2F-0001",
        status: String,
        fraction: Double? = null,
        kind: String = "video",
        title: String? = "Lisbon",
        stage: String? = null,
        reason: String? = null,
        mediaID: String? = null,
    ) = JobProgress(
        id = id, kind = kind, status = status, fraction = fraction, title = title,
        stage = stage, reason = reason, mediaID = mediaID,
    )

    // MARK: - The words the Mac uses

    @Test
    fun `the Mac's status words all mean something here`() {
        assertEquals(JobState.Queued, JobState.of("pending"))
        assertEquals(JobState.Submitting, JobState.of("submitting"))
        assertEquals(JobState.Rendering, JobState.of("rendering"))
        // The image and mesh lanes say "running" where the video queue says "rendering".
        assertEquals(JobState.Rendering, JobState.of("running"))
        assertEquals(JobState.Done, JobState.of("completed"))
        assertEquals(JobState.Failed, JobState.of("failed"))
        assertEquals(JobState.Stopped, JobState.of("cancelled"))
        // A word this build has never seen is work, not an error.
        assertEquals(JobState.Unknown, JobState.of("reticulating"))
        assertFalse(JobState.Unknown.isTerminal)
    }

    // MARK: - A render, start to finish

    @Test
    fun `progress events move one clip from queued to done`() {
        var state = QueueState.empty.applying(view(item(), active = "9C2F-0001"))
        assertEquals(1, state.jobs.size)
        assertEquals(JobState.Queued, state.jobs.first().state)
        assertTrue(state.jobs.first().isActive)

        state = state.applying(job(status = "submitting"))
        assertEquals(JobState.Submitting, state.job("9C2F-0001")!!.state)

        state = state.applying(job(status = "rendering", fraction = 0.33))
        assertEquals(0.33, state.job("9C2F-0001")!!.fraction!!, 0.0001)
        assertEquals("9C2F-0001", state.activeID)

        state = state.applying(job(status = "completed"))
        val done = state.job("9C2F-0001")!!
        assertEquals(JobState.Done, done.state)
        assertEquals("A finished render is a full bar", 1.0, done.fraction!!, 0.0001)
        assertNull("Nothing is running any more", state.activeID)
        assertFalse(done.isActive)
        assertTrue(state.running.isEmpty())
        assertEquals(1, state.finished.size)
    }

    @Test
    fun `a poll keeps the fraction the events reported`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "rendering", fraction = 0.5))
        state = state.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertEquals(
            "A poll that landed mid-render must not blank the bar",
            0.5, state.job("9C2F-0001")!!.fraction!!, 0.0001,
        )
    }

    // MARK: - Failure, and the reason for it

    @Test
    fun `a failure keeps the Mac's own reason, which only the queue carries`() {
        var state = QueueState.empty.applying(
            view(item(status = "failed", error = "No node accepted this clip."), active = null),
        )
        assertEquals(JobState.Failed, state.job("9C2F-0001")!!.state)
        assertEquals("No node accepted this clip.", state.job("9C2F-0001")!!.detail)

        // The event says failed and nothing else; the reason must survive it.
        state = state.applying(job(status = "failed"))
        assertEquals("No node accepted this clip.", state.job("9C2F-0001")!!.error)
        assertTrue(state.job("9C2F-0001")!!.canRetry)
    }

    @Test
    fun `a retry clears the last attempt's reason`() {
        var state = QueueState.empty.applying(
            view(item(status = "failed", error = "The node ran out of memory.")),
        )
        state = state.applying(job(status = "pending"))
        val retried = state.job("9C2F-0001")!!
        assertEquals(JobState.Queued, retried.state)
        assertNull("Last time's failure is not this time's", retried.error)
        assertEquals("hailuo-h3 · 5s · 768P · 30 steps", retried.detail)
    }

    @Test
    fun `a stopped clip can be retried but not removed while it runs`() {
        val running = QueueState.empty
            .applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertFalse(running.job("9C2F-0001")!!.canRemove)
        assertTrue(running.job("9C2F-0001")!!.canStopFollowing)

        val stopped = running.applying(job(status = "cancelled"))
        assertEquals(JobState.Stopped, stopped.job("9C2F-0001")!!.state)
        assertTrue(stopped.job("9C2F-0001")!!.canRetry)
        assertTrue(stopped.job("9C2F-0001")!!.canRemove)
        assertFalse(stopped.job("9C2F-0001")!!.canStopFollowing)
    }

    @Test
    fun `an unconfirmed handover is carried into the row, so a retry can be warned about`() {
        val state = QueueState.empty.applying(view(item(status = "failed", uncertain = true)))
        assertTrue(state.job("9C2F-0001")!!.uncertainSubmission)
    }

    // MARK: - Jobs that are not in the video queue

    @Test
    fun `an image job arrives on the stream alone and is not treated as queued work`() {
        var state = QueueState.empty.applying(view(item()))
        state = state.applying(job(id = "image", kind = "image", status = "running", title = "FLUX.2 klein", fraction = 0.2))
        val image = state.job("image")!!
        assertEquals("image", image.kind)
        assertFalse("Nothing on /video/queue/control can touch it", image.isQueued)
        assertFalse(image.canRetry)
        assertFalse(image.canRemove)
        assertEquals(2, state.jobs.size)

        // A poll of the video queue knows nothing about it, and must not erase it.
        state = state.applying(view(item()))
        assertNotNull("An image job survives a video queue poll", state.job("image"))
    }

    @Test
    fun `a clip the Mac has dropped from its queue leaves the screen`() {
        var state = QueueState.empty.applying(view(item(), item(id = "9C2F-0002")))
        assertEquals(2, state.jobs.size)
        state = state.applying(view(item(id = "9C2F-0002")))
        assertEquals(1, state.jobs.size)
        assertNull(state.job("9C2F-0001"))
    }

    @Test
    fun `pausing is the queue's state, not a job's`() {
        val state = QueueState.empty.applying(view(item(), paused = true))
        assertTrue(state.paused)
        assertEquals(JobState.Queued, state.job("9C2F-0001")!!.state)
    }

    @Test
    fun `a finished clip says where it is on the Mac`() {
        val state = QueueState.empty.applying(
            view(item(status = "completed", file = "/Users/you/Movies/Silicon/Lisbon/scene-001.mp4")),
        )
        assertEquals(
            "/Users/you/Movies/Silicon/Lisbon/scene-001.mp4",
            state.job("9C2F-0001")!!.file,
        )
    }

    // MARK: - Events that arrive late, twice, or in the wrong order

    /**
     * A poll answers with the queue as it was when the request left. If the stream
     * overtook it, applying that answer would walk a finished clip back into
     * "rendering" — and nothing after it would ever say otherwise.
     */
    @Test
    fun `a stale poll cannot undo an ending`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "completed"))
        assertEquals(JobState.Done, state.job("9C2F-0001")!!.state)

        state = state.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertEquals(JobState.Done, state.job("9C2F-0001")!!.state)
        assertNull("And nothing is running because of it", state.activeID)
        assertFalse(state.job("9C2F-0001")!!.isActive)
    }

    @Test
    fun `a stale poll still brings what only the queue knows`() {
        var state = QueueState.empty.applying(view(item(status = "rendering")))
        state = state.applying(job(status = "failed"))
        // The reason arrives on the next poll, which is "older" in status terms.
        state = state.applying(view(item(status = "rendering", error = "The node ran out of memory.")))
        val row = state.job("9C2F-0001")!!
        assertEquals(JobState.Failed, row.state)
        assertEquals("The node ran out of memory.", row.error)
    }

    @Test
    fun `progress that arrives after the ending is ignored`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "completed"))
        state = state.applying(job(status = "rendering", fraction = 0.4))
        val row = state.job("9C2F-0001")!!
        assertEquals(JobState.Done, row.state)
        assertEquals(1.0, row.fraction!!, 0.0001)
    }

    @Test
    fun `the same event twice changes nothing`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "completed"))
        val once = state
        state = state.applying(job(status = "completed"))
        assertEquals(once, state)
    }

    @Test
    fun `a fraction only grows inside one attempt`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "rendering", fraction = 0.6))
        // An older event overtaken by a newer one: the bar does not go backwards.
        state = state.applying(job(status = "rendering", fraction = 0.2))
        assertEquals(0.6, state.job("9C2F-0001")!!.fraction!!, 0.0001)

        // A retry is a new attempt, and starts from nothing.
        state = state.applying(job(status = "pending"))
        assertNull(state.job("9C2F-0001")!!.fraction)
        state = state.applying(job(status = "rendering", fraction = 0.1))
        assertEquals(0.1, state.job("9C2F-0001")!!.fraction!!, 0.0001)
    }

    @Test
    fun `an ending is left only by being queued again`() {
        val done = QueueState.empty
            .applying(view(item(status = "completed")))
        for (word in listOf("rendering", "submitting", "running")) {
            assertEquals(
                "\"$word\" after an ending is a late event, not a new attempt",
                JobState.Done, done.applying(job(status = word)).job("9C2F-0001")!!.state,
            )
        }
        assertEquals(JobState.Queued, done.applying(job(status = "pending")).job("9C2F-0001")!!.state)
    }

    // MARK: - Whose turn it is

    @Test
    fun `an image on the Mac cannot take the queue's turn`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertEquals("9C2F-0001", state.activeID)

        state = state.applying(job(id = "image", kind = "image", status = "running", fraction = 0.3, title = "FLUX.2 klein"))
        assertEquals(
            "The clip is still the one the queue is rendering",
            "9C2F-0001", state.activeID,
        )
        assertTrue(state.job("9C2F-0001")!!.isActive)
        assertFalse(state.job("image")!!.isActive)

        state = state.applying(job(id = "image", kind = "image", status = "completed", title = "FLUX.2 klein"))
        assertEquals("9C2F-0001", state.activeID)
    }

    @Test
    fun `a finished clip is not the active one, whatever the queue says`() {
        val state = QueueState.empty
            .applying(view(item(status = "completed"), active = "9C2F-0001"))
        assertNull(state.activeID)
        assertFalse(state.job("9C2F-0001")!!.isActive)
    }

    // MARK: - What may be done to a row

    @Test
    fun `a word this build does not know is never a remove button`() {
        val unknown = QueueState.empty.applying(view(item(status = "reticulating")))
        val row = unknown.job("9C2F-0001")!!
        assertEquals(JobState.Unknown, row.state)
        assertFalse("The Mac may be in the middle of something", row.canRemove)
        assertFalse(row.canRetry)
    }

    @Test
    fun `waiting and finished takes may be removed`() {
        assertTrue(QueueState.empty.applying(view(item(status = "pending"))).job("9C2F-0001")!!.canRemove)
        assertTrue(QueueState.empty.applying(view(item(status = "completed"))).job("9C2F-0001")!!.canRemove)
        assertTrue(QueueState.empty.applying(view(item(status = "failed"))).job("9C2F-0001")!!.canRemove)
        assertFalse(QueueState.empty.applying(view(item(status = "rendering"))).job("9C2F-0001")!!.canRemove)
        assertFalse(QueueState.empty.applying(view(item(status = "submitting"))).job("9C2F-0001")!!.canRemove)
    }

    /**
     * The stream is open before the queue is asked, so the first word about a clip is
     * usually a `job` event — with no way to tell it apart from an image the Mac
     * started on its own. When the queue names it, it is the same clip: one row.
     */
    @Test
    fun `a clip heard about on the stream first is not the queue's clip twice`() {
        var state = QueueState.empty.applying(job(status = "rendering", fraction = 0.1))
        assertEquals(1, state.jobs.size)
        assertFalse("Nothing has listed it, so nothing can be done to it", state.jobs.first().isQueued)

        state = state.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertEquals("One clip, one row", 1, state.jobs.size)
        val row = state.job("9C2F-0001")!!
        assertTrue("And now it is the queue's, with buttons", row.isQueued)
        assertEquals("A tram climbing Alfama at dawn", row.prompt)
        assertEquals(0.1, row.fraction!!, 0.0001)

        // An image the Mac started itself has nothing to do with the video queue and
        // stays where it is.
        state = state.applying(job(id = "image", kind = "image", status = "running", title = "FLUX.2 klein"))
        state = state.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertEquals(2, state.jobs.size)
        assertNotNull(state.job("image"))
    }

    // MARK: - What the stream carries now

    /**
     * The `job` event grew a stage and a reason, which is what let the poll beside it
     * go: the two things the stream could not say are the two things a person needs
     * while a render is running and when it breaks.
     */
    @Test
    fun `the stream alone can say what is happening and what went wrong`() {
        var state = QueueState.empty.applying(view(item(status = "pending")))
        state = state.applying(
            job(status = "rendering", fraction = 0.6, stage = "video-denoise 18/30"),
        )
        val running = state.job("9C2F-0001")!!
        assertEquals("video-denoise 18/30", running.stage)
        assertEquals("video-denoise 18/30", running.detail)

        state = state.applying(
            job(status = "failed", reason = "silicon-node ran out of VRAM at the decode stage."),
        )
        val failed = state.job("9C2F-0001")!!
        assertEquals("silicon-node ran out of VRAM at the decode stage.", failed.error)
        assertEquals("silicon-node ran out of VRAM at the decode stage.", failed.detail)
        assertNull("a finished render is not doing anything", failed.stage)
    }

    @Test
    fun `the event that says a render is done carries the id that fetches it`() {
        var state = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        state = state.applying(job(status = "completed", mediaID = "bWVkaWE"))
        assertEquals("bWVkaWE", state.job("9C2F-0001")!!.mediaID)
    }

    @Test
    fun `a queue read brings the ids for everything it lists`() {
        val state = QueueState.empty.applying(
            view(item(status = "completed").copy(mediaID = "bWVkaWE", thumbnailMediaID = "cG9zdGVy")),
        )
        val row = state.job("9C2F-0001")!!
        assertEquals("bWVkaWE", row.mediaID)
        assertEquals("cG9zdGVy", row.thumbnailMediaID)
    }

    @Test
    fun `what a batch asked to keep out of the shot is part of how it is made`() {
        val state = QueueState.empty.applying(
            view(item(status = "pending").copy(negativePrompt = "blurry, watermark")),
        )
        assertTrue(state.job("9C2F-0001")!!.detail!!.contains("without: blurry, watermark"))
    }

    // MARK: - Cancel render, where the Mac offers it

    private fun cancellable(status: String = "rendering", cancelState: String? = null, detail: String? = null) =
        item(status = status).copy(canCancel = true, cancelState = cancelState, cancelDetail = detail)

    @Test
    fun `Cancel render is offered only where the Mac marks the clip and never to a chat-only phone`() {
        val state = QueueState.empty.applying(
            view(cancellable(), item(id = "9C2F-0002", status = "rendering"), active = "9C2F-0001"),
        )
        assertTrue(state.job("9C2F-0001")!!.offersCancelRender(canControl = true))
        assertFalse("a chat-only pairing is not shown it", state.job("9C2F-0001")!!.offersCancelRender(canControl = false))
        assertFalse("no canCancel, no button", state.job("9C2F-0002")!!.offersCancelRender(canControl = true))
        // Stop following is still there, beside it.
        assertTrue(state.job("9C2F-0001")!!.canStopFollowing)
    }

    @Test
    fun `the Mac's cancel words all mean something, and a new one promises nothing`() {
        assertNull(CancelState.of(null))
        assertEquals(CancelState.Sending, CancelState.of("sending"))
        assertEquals(CancelState.Requested, CancelState.of("requested"))
        assertEquals(CancelState.Confirmed, CancelState.of("confirmed"))
        assertEquals(CancelState.Completed, CancelState.of("completed"))
        assertEquals(CancelState.Failed, CancelState.of("failed"))
        assertEquals(CancelState.Unsupported, CancelState.of("unsupported"))
        assertEquals(CancelState.Unknown, CancelState.of("unknown"))
        assertEquals(CancelState.Unknown, CancelState.of("reticulating"))
        assertTrue(CancelState.Requested.isPending)
        assertFalse(CancelState.Confirmed.isPending)
    }

    @Test
    fun `each outcome of a cancel is said on the clip, with the node's own words`() {
        val requested = QueueState.empty.applying(view(cancellable(cancelState = "requested")))
            .job("9C2F-0001")!!
        assertEquals(CancelState.Requested, requested.cancel)
        assertTrue(requested.cancel!!.note.startsWith("Cancel requested"))

        val unsupported = QueueState.empty.applying(
            view(item(status = "rendering").copy(cancelState = "unsupported", cancelDetail = "Phosphene is rendering it.")),
        ).job("9C2F-0001")!!
        assertEquals(CancelState.Unsupported, unsupported.cancel)
        assertTrue(unsupported.cancel!!.note.contains("keeps rendering"))
        assertEquals("Phosphene is rendering it.", unsupported.cancelDetail)
        assertFalse("the Mac said no more cancelling", unsupported.canCancelRender)

        val confirmed = QueueState.empty.applying(
            view(item(status = "cancelled").copy(cancelState = "confirmed", cancelDetail = "Cancelled; the renderer was stopped.")),
        ).job("9C2F-0001")!!
        assertEquals(JobState.Stopped, confirmed.state)
        assertTrue(confirmed.cancel!!.note.startsWith("Cancelled on the node"))
    }

    @Test
    fun `an event that ends the clip takes Cancel render away before the queue is read`() {
        val rendering = QueueState.empty.applying(view(cancellable(), active = "9C2F-0001"))
        assertFalse(rendering.applying(job(status = "completed")).job("9C2F-0001")!!.canCancelRender)
        assertFalse(rendering.applying(job(status = "pending")).job("9C2F-0001")!!.canCancelRender)
        val cancelled = rendering.applying(job(status = "cancelled")).job("9C2F-0001")!!
        assertFalse(cancelled.canCancelRender)
        assertEquals("a cancelled status is a confirmed cancel", CancelState.Confirmed, cancelled.cancel)
        // A failure is not one of those: a clip the Mac stopped following still has its
        // node's receipt, and only the Mac knows whether it can still be stopped.
        assertTrue(rendering.applying(job(status = "failed")).job("9C2F-0001")!!.canCancelRender)
    }

    @Test
    fun `a queue read older than the stream cannot bring Cancel render back to a finished clip`() {
        var state = QueueState.empty.applying(view(cancellable(), active = "9C2F-0001"))
        state = state.applying(job(status = "completed"))
        state = state.applying(view(cancellable(), active = "9C2F-0001"))
        assertEquals(JobState.Done, state.job("9C2F-0001")!!.state)
        assertFalse(state.job("9C2F-0001")!!.canCancelRender)
    }

    @Test
    fun `the Mac's answer about a clip may take it out of an ending, and a plain read may not`() {
        val failed = QueueState.empty.applying(view(cancellable(status = "failed")))
        val following = view(item(status = "rendering").copy(cancelState = "requested"), active = "9C2F-0001")
        assertEquals(
            "a read of the queue cannot walk a failure back",
            JobState.Failed, failed.applying(following).job("9C2F-0001")!!.state,
        )
        val answered = failed.applying(following, answering = "9C2F-0001").job("9C2F-0001")!!
        assertEquals("the answer to this phone's own cancel can", JobState.Rendering, answered.state)
        assertEquals(CancelState.Requested, answered.cancel)
        assertEquals(
            "and only for the clip it answers",
            JobState.Failed,
            failed.applying(following, answering = "9C2F-0002").job("9C2F-0001")!!.state,
        )
    }

    @Test
    fun `the queue is read again when a clip's cancel answer may have changed`() {
        val queued = QueueState.empty.applying(view(item(status = "submitting"), active = "9C2F-0001"))
        val started = queued.applying(job(status = "rendering", fraction = 0.1))
        assertTrue(
            "the node just took it, and may offer to stop it",
            cancelNeedsTheQueue(queued.job("9C2F-0001"), started.job("9C2F-0001")),
        )
        val further = started.applying(job(status = "rendering", fraction = 0.2))
        assertFalse(
            "progress within one render changes nothing about cancelling",
            cancelNeedsTheQueue(started.job("9C2F-0001"), further.job("9C2F-0001")),
        )
        val failed = further.applying(job(status = "failed"))
        assertTrue(cancelNeedsTheQueue(further.job("9C2F-0001"), failed.job("9C2F-0001")))

        val asked = QueueState.empty.applying(view(item(status = "rendering").copy(cancelState = "requested"), active = "9C2F-0001"))
        val finished = asked.applying(job(status = "completed"))
        assertTrue(
            "a cancel in flight met the end of the render: the Mac has recorded which won",
            cancelNeedsTheQueue(asked.job("9C2F-0001"), finished.job("9C2F-0001")),
        )
        val plain = QueueState.empty.applying(view(item(status = "rendering"), active = "9C2F-0001"))
        assertFalse(cancelNeedsTheQueue(plain.job("9C2F-0001"), plain.applying(job(status = "completed")).job("9C2F-0001")))

        // An image is not the video queue's, and nothing there can be cancelled.
        val image = QueueState.empty.applying(job(id = "image", kind = "image", status = "pending"))
        val drawing = image.applying(job(id = "image", kind = "image", status = "running"))
        assertFalse(cancelNeedsTheQueue(image.job("image"), drawing.job("image")))
    }
}
