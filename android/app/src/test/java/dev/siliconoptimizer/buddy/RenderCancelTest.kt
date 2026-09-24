package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.media.CancelState
import dev.siliconoptimizer.buddy.media.JobState
import dev.siliconoptimizer.buddy.media.MediaViewModel
import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.VideoQueueControlRequest
import dev.siliconoptimizer.buddy.transport.VideoQueueItem
import dev.siliconoptimizer.buddy.transport.VideoQueueView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * "Cancel render" on the Create tab's queue, from the view model down.
 *
 * The Mac answers a cancel only once the clip's node has answered it, so the part worth
 * pinning is what the phone does around that wait — one request, for that clip, not two —
 * and that what it shows afterwards is the Mac's record of how the cancel went rather than
 * a guess. And that the button can appear at all: a `job` event never says whether a clip
 * can be cancelled, so a clip that starts rendering while the phone watches has to be
 * read from the queue once more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderCancelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var model: MediaViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = MediaViewModel()
    }

    @After
    fun tearDown() {
        model.stopForTest()
        Dispatchers.resetMain()
    }

    private fun item(
        status: String = "rendering",
        canCancel: Boolean? = true,
        cancelState: String? = null,
        cancelDetail: String? = null,
    ) = VideoQueueItem(
        id = CLIP, batchID = "9C2F", title = "Lisbon", prompt = "Laundry lines over the Alfama steps",
        scene = 5, variation = 1, seed = 424246, modelID = "ltx2-distilled", seconds = 5,
        resolution = "720p", status = status, nodeJobID = "job-1191",
        outputDirectory = "/Users/you/Movies/Silicon/Lisbon", uncertainSubmission = false,
        canCancel = canCancel, cancelState = cancelState, cancelDetail = cancelDetail,
    )

    private fun view(item: VideoQueueItem, message: String? = null) =
        VideoQueueView(paused = false, activeID = CLIP, message = message, items = listOf(item))

    /**
     * A Mac with one clip in its queue. It answers a verb when the test says so, and can
     * hold a read on the wire — answered with the queue as it was when the read arrived.
     */
    private class QueueMac(var queue: VideoQueueView) : HangingTransport() {
        val sent = mutableListOf<VideoQueueControlRequest>()
        var reads = 0
        var answer = CompletableDeferred<VideoQueueView>()
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun videoQueue(): VideoQueueView {
            reads++
            val snapshot = queue
            gate?.let {
                it.await()
                gate = null
            }
            return snapshot
        }

        override suspend fun controlVideoQueue(request: VideoQueueControlRequest): VideoQueueView {
            sent += request
            return answer.await()
        }
    }

    private fun opened(mac: QueueMac) {
        model.startFollowing(mac, live = true)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test
    fun `one cancel for that clip, and the clip then says the node is stopping it`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        assertTrue(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))

        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        assertEquals(setOf(CLIP), model.cancelling)
        // A second tap while the node is being asked sends nothing more.
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        assertEquals(listOf(VideoQueueControlRequest(VideoQueueControlRequest.CANCEL, CLIP)), mac.sent)

        val stopping = "The node is stopping this render. The queue follows it until the node confirms."
        mac.answer.complete(view(item(canCancel = false, cancelState = "requested"), message = stopping))
        advanceUntilIdle()
        assertTrue(model.cancelling.isEmpty())
        val clip = model.queue.job(CLIP)!!
        assertEquals(CancelState.Requested, clip.cancel)
        assertFalse("asked once is enough: the Mac no longer offers it", clip.offersCancelRender(canControl = true))
        assertEquals("the Mac's own sentence about it", stopping, model.queue.message)
        assertNull(model.error)
    }

    @Test
    fun `a node that cannot stop it says so, and the render carries on`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        mac.answer.complete(
            view(item(canCancel = true, cancelState = "unsupported", cancelDetail = "Another job shares that renderer.")),
        )
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        val clip = model.queue.job(CLIP)!!
        assertEquals(CancelState.Unsupported, clip.cancel)
        assertEquals("Another job shares that renderer.", clip.cancelDetail)
        assertEquals(JobState.Rendering, clip.state)
        assertTrue(clip.cancel!!.note.contains("keeps rendering"))
    }

    @Test
    fun `a confirmed cancel ends the clip, and nothing about it is left to cancel`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        mac.answer.complete(
            view(item(status = "cancelled", canCancel = false, cancelState = "confirmed", cancelDetail = "Cancelled; the renderer was stopped.")),
        )
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        val clip = model.queue.job(CLIP)!!
        assertEquals(JobState.Stopped, clip.state)
        assertEquals(CancelState.Confirmed, clip.cancel)
        assertFalse(clip.canCancelRender)
        assertTrue("a cancelled take can be rendered again", clip.canRetry)
    }

    @Test
    fun `a refused cancel is shown, and the queue is read again for what the Mac kept`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        val refusal = "This clip's node does not offer to cancel its render. Use stop_following: " +
            "the app stops waiting and keeps the receipt, but the node may still finish the render."
        mac.answer.completeExceptionally(TransportError.BadRequest(refusal))
        mac.queue = view(item(canCancel = false))
        val readsBefore = mac.reads
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        assertEquals(refusal, model.error)
        assertTrue(model.cancelling.isEmpty())
        assertEquals(readsBefore + 1, mac.reads)
        assertFalse(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))
    }

    @Test
    fun `a clip that starts rendering while the phone watches is read again, and can then be cancelled`() =
        runTest(dispatcher) {
            val mac = QueueMac(view(item(status = "submitting", canCancel = false)))
            opened(mac)
            assertFalse(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))
            assertEquals(1, mac.reads)

            // The node took the job: the Mac now offers to cancel it, and says so only
            // in the queue.
            mac.queue = view(item(status = "rendering", canCancel = true))
            model.apply(progress("running", 0.05), transport = mac)
            advanceUntilIdle()
            assertEquals(2, mac.reads)
            assertTrue(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))

            // Progress inside one render asks nothing.
            model.apply(progress("running", 0.4), transport = mac)
            advanceUntilIdle()
            assertEquals(2, mac.reads)

            // Failing a moment later is its own reason to read, and is not dropped for
            // coming inside the last read's three seconds.
            mac.queue = view(item(status = "failed", canCancel = false))
            model.apply(progress("failed", null), transport = mac)
            advanceUntilIdle()
            assertEquals(3, mac.reads)
            assertFalse(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))
        }

    @Test
    fun `cancelling a clip the Mac stopped following follows it again`() = runTest(dispatcher) {
        // Stop following left it failed on the Mac with the node's receipt, and the node
        // still offers to stop it.
        val mac = QueueMac(view(item(status = "failed", canCancel = true)))
        opened(mac)
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)
        assertTrue(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))

        val following = view(item(status = "rendering", canCancel = false, cancelState = "requested"))
        mac.answer.complete(following)
        mac.queue = following
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        val clip = model.queue.job(CLIP)!!
        assertEquals("the Mac follows the same job again, and so does the phone", JobState.Rendering, clip.state)
        assertEquals(CancelState.Requested, clip.cancel)
    }

    @Test
    fun `an event that overtook the answer is caught by one more read of the queue`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        model.cancelRender(CLIP, mac)
        advanceUntilIdle()
        // The node confirmed at once, and the stream said so before the Mac's answer,
        // written a moment earlier, reached the phone.
        model.apply(progress("cancelled", null), transport = mac)
        assertEquals(JobState.Stopped, model.queue.job(CLIP)!!.state)
        val readsBefore = mac.reads
        mac.queue = view(item(status = "cancelled", canCancel = false, cancelState = "confirmed"))
        mac.answer.complete(view(item(status = "rendering", canCancel = false, cancelState = "requested")))
        advanceUntilIdle()
        assertEquals(readsBefore + 1, mac.reads)
        val clip = model.queue.job(CLIP)!!
        assertEquals(JobState.Stopped, clip.state)
        assertEquals(CancelState.Confirmed, clip.cancel)
    }

    @Test
    fun `a failure that lands while a read is on the wire still gets a read of its own`() = runTest(dispatcher) {
        val mac = QueueMac(view(item(status = "submitting", canCancel = false)))
        opened(mac)
        assertEquals(1, mac.reads)

        // The node took it: the queue is read again, and that read is held on the wire.
        mac.queue = view(item(status = "rendering", canCancel = true))
        mac.gate = CompletableDeferred()
        model.apply(progress("running", 0.05), transport = mac)
        advanceUntilIdle()
        assertEquals(2, mac.reads)

        // The node fails it meanwhile, and the Mac stops offering to cancel it. The read
        // on the wire was answered before that, with canCancel still true.
        model.apply(progress("failed", null), transport = mac)
        mac.queue = view(item(status = "failed", canCancel = false))
        mac.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("the failure's reason to read was not dropped", 3, mac.reads)
        assertFalse(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))
    }

    /**
     * Stop following, through the screen's own verb. The Mac answers the moment it has let
     * go — the clip still rendering — and the failure that follows reaches the phone first
     * on the stream. The older answer must not walk the failure back.
     */
    @Test
    fun `an answer to stop following that an event overtook leaves the failure standing`() = runTest(dispatcher) {
        val mac = QueueMac(view(item(canCancel = false)))
        opened(mac)
        model.control(VideoQueueControlRequest.STOP_FOLLOWING, CLIP, mac)
        advanceUntilIdle()

        mac.queue = view(item(status = "failed", canCancel = false), message = null)
        model.apply(progress("failed", null), transport = mac)
        advanceUntilIdle()
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)
        val readsBefore = mac.reads

        mac.gate = CompletableDeferred()
        mac.answer.complete(view(item(canCancel = false)))
        runCurrent()
        assertEquals("the older answer did not walk it back", JobState.Failed, model.queue.job(CLIP)!!.state)
        assertFalse("nor bring Stop following back", model.queue.job(CLIP)!!.canStopFollowing)
        mac.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals("and the queue was read to settle it", readsBefore + 1, mac.reads)
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)
    }

    // MARK: - A re-pair while a verb is out

    @Test
    fun `a cancel answered after a re-pair does not land on the new Mac's queue`() = runTest(dispatcher) {
        val old = QueueMac(view(item()))
        opened(old)
        model.cancelRender(CLIP, old)
        advanceUntilIdle()
        assertEquals(setOf(CLIP), model.cancelling)

        // Paired with another Mac while the old one's node decides.
        model.reset()
        val other = QueueMac(view(item(status = "pending", canCancel = false).copy(id = "4D00-0001")))
        opened(other)
        old.answer.complete(view(item(status = "cancelled", canCancel = false, cancelState = "confirmed")))
        advanceUntilIdle()
        assertEquals("the new Mac's queue stands", listOf("4D00-0001"), model.queue.jobs.map { it.id })
        assertTrue(model.cancelling.isEmpty())
        assertNull(model.error)

        // A refusal from the old Mac is not said on the new one either.
        model.control(VideoQueueControlRequest.PAUSE, transport = old.also { it.answer = CompletableDeferred() })
        advanceUntilIdle()
        model.reset()
        opened(other)
        old.answer.completeExceptionally(TransportError.BadRequest("That queue item no longer exists."))
        advanceUntilIdle()
        assertNull(model.error)
    }

    // MARK: - A clip reconnected from somewhere else

    @Test
    fun `a clip reconnected from the Mac's own window renders again here, once the queue says so`() = runTest(dispatcher) {
        // Stopped following on the Mac: failed, with its node's receipt.
        val mac = QueueMac(view(item(status = "failed", canCancel = true)))
        opened(mac)
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)

        // Reconnected on the Mac: the stream says rendering. Not taken on its word…
        mac.queue = view(item(status = "rendering", canCancel = true))
        model.apply(progress("running", 0.3), transport = mac)
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)
        // …but on the read that leaves after it.
        advanceUntilIdle()
        assertEquals(JobState.Rendering, model.queue.job(CLIP)!!.state)

        // And when it finishes, it is done — not stuck as failed.
        model.apply(progress("completed", 1.0), transport = mac)
        assertEquals(JobState.Done, model.queue.job(CLIP)!!.state)
    }

    @Test
    fun `a failed clip that finishes is done, even with no reconnect seen`() = runTest(dispatcher) {
        val mac = QueueMac(view(item(status = "failed", canCancel = false)))
        opened(mac)
        model.apply(progress("completed", 1.0), transport = mac)
        assertEquals(JobState.Done, model.queue.job(CLIP)!!.state)
    }

    @Test
    fun `a reconnect that fails again while the read is out stays failed`() = runTest(dispatcher) {
        val mac = QueueMac(view(item(status = "failed", canCancel = false)))
        opened(mac)
        // Rendering again — and the read that would confirm it is held on the wire, answered
        // with the queue as it was when it arrived.
        mac.queue = view(item(status = "rendering", canCancel = false))
        mac.gate = CompletableDeferred()
        model.apply(progress("running", 0.1), transport = mac)
        advanceUntilIdle()
        // It failed again before that answer came back.
        model.apply(progress("failed", null), transport = mac)
        mac.gate!!.complete(Unit)
        mac.queue = view(item(status = "failed", canCancel = false))
        runCurrent()
        assertEquals("the older answer does not reopen it", JobState.Failed, model.queue.job(CLIP)!!.state)
        advanceUntilIdle()
        assertEquals(JobState.Failed, model.queue.job(CLIP)!!.state)
    }

    @Test
    fun `a Mac from before cancel offers it on nothing`() = runTest(dispatcher) {
        val mac = QueueMac(view(item(canCancel = null)))
        opened(mac)
        assertFalse(model.queue.job(CLIP)!!.offersCancelRender(canControl = true))
        assertNull(model.queue.job(CLIP)!!.cancel)
    }

    private fun progress(status: String, fraction: Double?) = JobProgress(
        id = CLIP, kind = "video", status = status, fraction = fraction, title = "Lisbon",
    )

    // MARK: - Clear finished

    private fun image(status: String) = JobProgress(
        id = "image-6F1C2A40-0000-4000-8000-000000000001", kind = "image", status = status, title = "FLUX.2 klein",
    )

    /**
     * An image that has finished is a row on this phone only. Clear finished takes it away
     * — and, with no finished clip in the video queue, does not ask the Mac to clear
     * anything. A chat-only phone may clear its own rows as well; the Mac's are not its to ask.
     */
    @Test
    fun `Clear finished takes finished renders off this phone and asks the Mac only about clips`() = runTest(dispatcher) {
        val mac = QueueMac(view(item()))
        opened(mac)
        model.apply(image("running"))
        model.apply(image("completed"))
        assertEquals(2, model.queue.jobs.size)

        model.clearFinished(mac, canControl = false)
        advanceUntilIdle()
        assertEquals(listOf(CLIP), model.queue.jobs.map { it.id })
        assertTrue("nothing asked of the Mac", mac.sent.isEmpty())

        // With a finished clip in the queue, the Mac is asked to clear it, as before.
        mac.queue = view(item(status = "completed"))
        model.startFollowing(mac, live = true)
        advanceUntilIdle()
        model.clearFinished(mac)
        advanceUntilIdle()
        assertEquals(listOf(VideoQueueControlRequest(VideoQueueControlRequest.CLEAR_FINISHED)), mac.sent)
        mac.answer.complete(VideoQueueView(paused = false, activeID = null, message = null, items = emptyList()))
        advanceUntilIdle()
        assertTrue(model.queue.jobs.isEmpty())
    }

    private companion object {
        const val CLIP = "9C2F-0005"
    }
}
