package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.CancelState
import dev.siliconoptimizer.buddy.media.JobAnnouncer
import dev.siliconoptimizer.buddy.media.JobNotifications
import dev.siliconoptimizer.buddy.media.JobState
import dev.siliconoptimizer.buddy.media.MediaJob
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
 * What a finished render is worth saying, and how often.
 *
 * Two sources tell the phone the same news — the `job` events and the queue poll — and
 * which of them gets there first is a race that depends on when a timer last fired. So
 * the rule being tested is: once, whichever arrived first, and again only after the
 * clip has been through something that is not an ending.
 */
class NotificationTest {

    private fun row(
        state: JobState,
        kind: String = "video",
        id: String = "9C2F-0001",
        error: String? = null,
    ) = MediaJob(
        id = id, kind = kind, title = "Lisbon", state = state,
        statusWord = state.name.lowercase(), error = error,
        file = if (state == JobState.Done) "/Users/you/Movies/Silicon/Lisbon/scene-001.mp4" else null,
    )

    private fun item(status: String, error: String? = null) = VideoQueueItem(
        id = "9C2F-0001", batchID = "9C2F", title = "Lisbon",
        prompt = "A tram climbing Alfama at dawn", scene = 1, variation = 1, seed = 1,
        modelID = "hailuo-h3", seconds = 5, resolution = "768P", h3Turbo = false,
        h3Steps = 30, status = status, nodeJobID = null,
        file = if (status == "completed") "/Users/you/Movies/Silicon/Lisbon/scene-001.mp4" else null,
        outputDirectory = "/Users/you/Movies/Silicon/Lisbon", error = error,
        uncertainSubmission = false,
    )

    private fun view(status: String, error: String? = null) =
        VideoQueueView(paused = false, activeID = null, message = null, items = listOf(item(status, error)))

    // MARK: - What a transition is worth

    @Test
    fun `a render that finishes is worth a notification`() {
        val notice = JobNotifications.transition(row(JobState.Rendering), row(JobState.Done))
        assertNotNull(notice)
        assertEquals("Your clip is ready", notice!!.title)
        assertTrue(notice.body.contains("Lisbon"))
        assertFalse(notice.isFailure)
        assertNull(JobNotifications.transition(row(JobState.Done), row(JobState.Done)))
    }

    /**
     * A notification is drawn on a locked screen, in a room with other people in it.
     * What was rendered is worth saying; where it is on somebody's disk is not.
     */
    @Test
    fun `a notification never carries a path from the Mac`() {
        val done = JobNotifications.transition(row(JobState.Rendering), row(JobState.Done))!!
        assertFalse(done.body.contains("/Users/"))

        val failed = JobNotifications.transition(
            row(JobState.Rendering),
            row(JobState.Failed, error = "Could not write /Users/you/Movies/Silicon/a.mp4: disk full."),
        )!!
        assertFalse(failed.body.contains("/Users/"))
        assertTrue("The Mac's sentence survives", failed.body.contains("disk full"))
        assertTrue(failed.body.contains("a file on the Mac"))
    }

    @Test
    fun `a failure carries the Mac's reason into the notification`() {
        val notice = JobNotifications.transition(
            row(JobState.Rendering),
            row(JobState.Failed, error = "No node accepted this clip."),
        )
        assertEquals("That clip failed", notice!!.title)
        assertEquals("No node accepted this clip.", notice.body)
        assertTrue(notice.isFailure)
    }

    @Test
    fun `work still running is never a notification`() {
        assertNull(JobNotifications.transition(null, row(JobState.Queued)))
        assertNull(JobNotifications.transition(row(JobState.Queued), row(JobState.Rendering)))
    }

    @Test
    fun `a job first seen already finished still gets said`() {
        // The phone was asleep while the Mac worked; the first thing it sees is "done".
        assertNotNull(JobNotifications.transition(null, row(JobState.Done)))
    }

    @Test
    fun `stopping says what the Mac will not claim`() {
        val notice = JobNotifications.transition(row(JobState.Rendering), row(JobState.Stopped))
        assertTrue(notice!!.body.contains("node may still finish it"))
    }

    @Test
    fun `a cancel the node confirmed is said as one, not as a render that may still finish`() {
        val cancelled = row(JobState.Stopped).copy(statusWord = "cancelled", cancel = CancelState.Confirmed)
        val notice = JobNotifications.transition(row(JobState.Rendering), cancelled)!!
        assertEquals("That clip was cancelled", notice.title)
        assertFalse(notice.body.contains("may still finish"))
        assertFalse("somebody asked for it", notice.isFailure)
    }

    @Test
    fun `each kind is named the way a person would name it`() {
        assertEquals("clip", JobNotifications.noun("video"))
        assertEquals("image", JobNotifications.noun("image"))
        assertEquals("mesh", JobNotifications.noun("mesh"))
        assertEquals("job", JobNotifications.noun(""))
        assertEquals(
            "Your image is ready",
            JobNotifications.transition(null, row(JobState.Done, kind = "image"))!!.title,
        )
    }

    // MARK: - Once, whichever source noticed

    /**
     * A queue holds finished clips for a long time. Opening the app must not fire a
     * notification for every one of them: those endings happened while this app was
     * somewhere else, and were said at the time or not at all.
     */
    @Test
    fun `the first reading of a Mac is history, not news`() {
        val announcer = JobAnnouncer()
        val opening = QueueState.empty.applying(view("completed"))
        announcer.prime(opening)
        assertTrue(announcer.notices(QueueState.empty, opening).isEmpty())

        // What happens after that is news, though.
        val retried = opening.applying(view("pending"))
        announcer.notices(opening, retried)
        val done = retried.applying(view("completed"))
        assertEquals(1, announcer.notices(retried, done).size)
    }

    @Test
    fun `nothing is said before a queue has been read`() {
        val announcer = JobAnnouncer()
        assertFalse(announcer.isPrimed)
        val done = QueueState.empty.applying(view("completed"))
        assertTrue(announcer.notices(QueueState.empty, done).isEmpty())
        assertFalse("Being asked is not being primed", announcer.isPrimed)
    }

    /**
     * The storm.
     *
     * A `job` event can beat the first `GET /video/queue` — the stream is already open
     * when the screen asks. Priming on whatever arrived first meant priming on a queue
     * of one, and then the first real snapshot, with everything the Mac has rendered
     * this week in it, was news: one notification per clip.
     */
    @Test
    fun `an event arriving before the first queue read does not make history into news`() {
        val announcer = JobAnnouncer()
        var state = QueueState.empty.applying(
            JobProgress(id = "9C2F-0009", kind = "video", status = "rendering", title = "Now"),
        )
        // Not primed: the stream alone never primes.
        assertTrue(announcer.notices(QueueState.empty, state).isEmpty())
        assertFalse(announcer.isPrimed)

        val history = VideoQueueView(
            paused = false, activeID = null, message = null,
            items = (1..8).map { item("completed").copy(id = "OLD-000$it") },
        )
        state = state.applying(history)
        announcer.prime(state)
        assertTrue(
            "Eight finished clips from last week are not eight notifications",
            announcer.notices(QueueState.empty, state).isEmpty(),
        )

        // And the clip that really is running still rings when it ends.
        val ending = state.applying(
            JobProgress(id = "9C2F-0009", kind = "video", status = "completed", title = "Now"),
        )
        assertEquals(1, announcer.notices(state, ending).size)
    }

    /**
     * A render this phone is holding a request open for is reported on `/events` too.
     * The service that is waiting on it does the telling, or it rings twice.
     */
    @Test
    fun `work the service is waiting on is left to the service`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        val running = QueueState.empty.applying(
            JobProgress(id = "image", kind = "image", status = "running", title = "FLUX.2 klein"),
        )
        announcer.notices(QueueState.empty, running)
        val done = running.applying(
            JobProgress(id = "image", kind = "image", status = "completed", title = "FLUX.2 klein"),
        )
        assertTrue(
            announcer.notices(running, done, handledElsewhere = { it.kind == "image" }).isEmpty(),
        )
    }

    @Test
    fun `a clip that finishes is announced once, however the news arrived`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        var state = QueueState.empty.applying(view("rendering"))
        assertTrue(announcer.notices(QueueState.empty, state).isEmpty())

        // The poll gets there first this time.
        val polled = state.applying(view("completed"))
        assertEquals(1, announcer.notices(state, polled).size)
        state = polled

        // Then the event says the same thing. Saying it twice would be the bug.
        val streamed = state.applying(
            JobProgress(id = "9C2F-0001", kind = "video", status = "completed", title = "Lisbon"),
        )
        assertTrue(announcer.notices(state, streamed).isEmpty())
    }

    @Test
    fun `the event getting there first is the same story`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        var state = QueueState.empty.applying(view("rendering"))
        announcer.notices(QueueState.empty, state)

        val streamed = state.applying(
            JobProgress(id = "9C2F-0001", kind = "video", status = "completed", title = "Lisbon"),
        )
        assertEquals(1, announcer.notices(state, streamed).size)
        state = streamed

        val polled = state.applying(view("completed"))
        assertTrue("The poll that follows is not news", announcer.notices(state, polled).isEmpty())
    }

    @Test
    fun `a retried clip is announced again when it ends again`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        var state = QueueState.empty.applying(view("failed", "The node ran out of memory."))
        assertEquals(1, announcer.notices(QueueState.empty, state).size)

        val retried = state.applying(view("pending"))
        assertTrue(announcer.notices(state, retried).isEmpty())
        state = retried

        val failedAgain = state.applying(view("failed", "The node ran out of memory."))
        assertEquals(
            "The second failure is news as much as the first",
            1, announcer.notices(state, failedAgain).size,
        )
    }

    @Test
    fun `a clip that goes from failed to done is announced for the ending it reached`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        var state = QueueState.empty.applying(view("failed", "No node accepted this clip."))
        assertTrue(announcer.notices(QueueState.empty, state).first().isFailure)

        state = state.applying(view("pending"))
        announcer.notices(QueueState.empty, state)
        val done = state.applying(view("completed"))
        val notice = announcer.notices(state, done).single()
        assertFalse(notice.isFailure)
        assertEquals("Your clip is ready", notice.title)
    }

    @Test
    fun `an image render announces itself too, though the video queue knows nothing about it`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        val running = QueueState.empty.applying(
            JobProgress(id = "image", kind = "image", status = "running", title = "FLUX.2 klein"),
        )
        assertTrue(announcer.notices(QueueState.empty, running).isEmpty())
        val done = running.applying(
            JobProgress(id = "image", kind = "image", status = "completed", title = "FLUX.2 klein"),
        )
        assertEquals("Your image is ready", announcer.notices(running, done).single().title)
    }

    // MARK: - Renders first seen already over

    private fun render(status: String, id: String = "image-6F1C2A40-0000-4000-8000-000000000001") =
        JobProgress(id = id, kind = "image", status = status, title = "FLUX.2 klein")

    /**
     * The Mac says how its last image and mesh renders ended to every phone that opens
     * `/events` — a cold start, or the app coming back to the screen. An ending that is the
     * first this phone hears of a render happened while it was not looking, and was said at
     * the time or not at all: announcing it rang "Your image is ready" for a picture made an
     * hour ago, on every start.
     */
    @Test
    fun `a render first seen already over is history, not news`() {
        for (primedFirst in listOf(true, false)) {
            val announcer = JobAnnouncer()
            if (primedFirst) announcer.prime(QueueState.empty)
            val opening = QueueState.empty.applying(render("completed"))
            val said = announcer.notices(QueueState.empty, opening)
            if (!primedFirst) announcer.prime(opening)
            assertTrue("primed first: $primedFirst", said.isEmpty())
            // Heard again in the next opening: still nothing.
            assertTrue(announcer.notices(opening, opening.applying(render("completed"))).isEmpty())
        }
    }

    /** One the phone watched start is still news when it ends, the same as ever. */
    @Test
    fun `a render seen running is announced when it ends`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        val running = QueueState.empty.applying(render("running"))
        announcer.notices(QueueState.empty, running)
        val done = running.applying(render("completed"))
        assertEquals("Your image is ready", announcer.notices(running, done).single().title)
    }

    /**
     * A render this phone asked for, seen running while the service held its request. The
     * service says when it is done. The stream's word for the same ending can come much
     * later — the app back on the screen after a minute or more — and it is still the
     * service's render, not a second piece of news.
     */
    @Test
    fun `a render the service held is left to it, however late its ending arrives`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        val running = QueueState.empty.applying(render("running"))
        announcer.notices(QueueState.empty, running, handledElsewhere = { true })

        // The service's claim has lapsed by the time the ending is heard.
        val done = running.applying(render("completed"))
        assertTrue(announcer.notices(running, done, handledElsewhere = { false }).isEmpty())
    }

    @Test
    fun `forgetting a Mac forgets what was said about its work`() {
        val announcer = JobAnnouncer()
        announcer.prime(QueueState.empty)
        val done = QueueState.empty.applying(view("completed"))
        assertEquals(1, announcer.notices(QueueState.empty, done).size)

        // A re-pair starts the story again: nothing is said until the new Mac's queue
        // has been read, and what is in it then is that Mac's history.
        announcer.forget()
        assertFalse(announcer.isPrimed)
        assertTrue(announcer.notices(QueueState.empty, done).isEmpty())
        announcer.prime(done)
        val queuedAgain = done.applying(view("pending"))
        announcer.notices(done, queuedAgain)
        val again = queuedAgain.applying(view("completed"))
        assertEquals(1, announcer.notices(queuedAgain, again).size)
    }
}
