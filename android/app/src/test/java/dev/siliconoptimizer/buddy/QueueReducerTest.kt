package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.JobState
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
    ) = JobProgress(id = id, kind = kind, status = status, fraction = fraction, title = title)

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
}
