package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.DownloadProgress
import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The consumer half of a dropped stream.
 *
 * `ControlClient` heals the connection by itself, which is right and which is exactly
 * what made the original bug invisible: `isLive` was set by the first event and never
 * cleared, so Settings read "Streaming from /events" through an entire outage. These are
 * about what a screen is allowed to say.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventFeedTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var feed: EventFeed

    /** A stream the test writes, so drops can be produced exactly. */
    private class Scripted(private val events: List<ServerEvent>) : HangingTransport() {
        override fun events(): Flow<ServerEvent> = flow {
            events.forEach { emit(it) }
            // Then hang, the way a live stream waits rather than completing.
            kotlinx.coroutines.awaitCancellation()
        }
    }

    private val status = Status(state = "running", loadedModelID = "qwen3")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        feed = EventFeed()
    }

    @After
    fun tearDown() {
        feed.stop()
        Dispatchers.resetMain()
    }

    @Test
    fun `a live stream is live`() = runTest {
        feed.start(Scripted(listOf(ServerEvent.StatusChanged(status))))

        assertTrue(feed.isLive)
        assertNull(feed.retryAt)
        assertEquals(0, feed.reconnectAttempt)
        assertNotNull(feed.lastEvent)
    }

    /** The bug: a drop after a good event has to take the stream off "live". */
    @Test
    fun `a drop stops the stream being live`() = runTest {
        feed.start(
            Scripted(
                listOf(
                    ServerEvent.StatusChanged(status),
                    ServerEvent.Disconnected(attempt = 2, retryInMillis = 2_000, summary = "unreachable"),
                ),
            ),
        )

        assertFalse("Settings would have said 'Streaming from /events'", feed.isLive)
        assertEquals(2, feed.reconnectAttempt)
        assertNotNull(feed.retryAt)
    }

    /**
     * And the last-event clock is cleared with it. Leaving the old timestamp makes a
     * stream that died ten minutes ago read as one that was alive a moment ago.
     */
    @Test
    fun `a drop clears the last event time`() = runTest {
        feed.start(
            Scripted(
                listOf(
                    ServerEvent.StatusChanged(status),
                    ServerEvent.Disconnected(1, 1_000, "timed out"),
                ),
            ),
        )

        assertNull(feed.lastEvent)
    }

    /** Coming back cancels the countdown rather than leaving it on screen. */
    @Test
    fun `an event after a drop puts the stream back`() = runTest {
        feed.start(
            Scripted(
                listOf(
                    ServerEvent.Disconnected(3, 4_000, "unreachable"),
                    ServerEvent.StatusChanged(status),
                ),
            ),
        )

        assertTrue(feed.isLive)
        assertNull("The countdown should be gone", feed.retryAt)
        assertNull(feed.lastDropSummary)
        assertEquals(0, feed.reconnectAttempt)
        assertEquals("qwen3", feed.status?.loadedModelID)
    }

    /**
     * The countdown is computed from a moment, not stored as a duration: read a second
     * later, a stored "4000ms" is a second wrong.
     */
    @Test
    fun `the countdown counts down`() = runTest {
        feed.start(Scripted(listOf(ServerEvent.Disconnected(3, 4_000, "unreachable"))))
        val at = feed.retryAt!!

        assertEquals(4L, feed.secondsUntilRetry(at - 4_000))
        assertEquals(2L, feed.secondsUntilRetry(at - 2_000))
        assertEquals(1L, feed.secondsUntilRetry(at - 1))
        // Never negative: an overdue attempt reads as "now", not as "-3s".
        assertEquals(0L, feed.secondsUntilRetry(at))
        assertEquals(0L, feed.secondsUntilRetry(at + 10_000))
    }

    /** Nothing that reaches a screen may carry the Mac's address. See `LogRedactionTest`. */
    @Test
    fun `the drop summary is a tag, not a message`() = runTest {
        feed.start(Scripted(listOf(ServerEvent.Disconnected(1, 1_000, "unreachable"))))

        assertEquals("unreachable", feed.lastDropSummary)
        assertFalse(feed.lastDropSummary.orEmpty().contains("100."))
    }

    /** Stopping puts everything back, so a re-pair does not inherit the last Mac's outage. */
    @Test
    fun `stopping clears the reconnect state`() = runTest {
        feed.start(Scripted(listOf(ServerEvent.Disconnected(5, 16_000, "unreachable"))))
        assertNotNull(feed.retryAt)

        feed.stop()

        assertNull(feed.retryAt)
        assertNull(feed.lastDropSummary)
        assertEquals(0, feed.reconnectAttempt)
        assertFalse(feed.isLive)
    }

    // MARK: - Another Mac

    /** Mac A, part way through a download, a phone model and a render. */
    private val macA = listOf(
        ServerEvent.StatusChanged(status),
        ServerEvent.Download(DownloadProgress(id = "qwen3-8b", name = "Qwen3 8B", bytesReceived = 40, bytesExpected = 100)),
        ServerEvent.Download(DownloadProgress(id = "ondevice:smollm2-135m-q8_0", name = "SmolLM2", bytesReceived = 10, bytesExpected = 100)),
        ServerEvent.Job(JobProgress(id = "r1", kind = "image", status = "running")),
    )

    /**
     * Pairing with another Mac. What the last one was running, fetching and rendering is not
     * what this one is: left in place, its download hid the Load button on the new Mac's row
     * for the same model, and its render sat under "Happening now" with nothing behind it.
     */
    @Test
    fun `a new pairing starts with nothing the last Mac said`() = runTest {
        feed.paired(1)
        feed.start(Scripted(macA))
        assertEquals("qwen3", feed.status?.loadedModelID)
        assertNotNull(feed.download("qwen3-8b"))
        assertEquals(1, feed.phoneModels.size)
        assertEquals(1, feed.jobs.size)

        feed.paired(2)
        feed.start(Scripted(emptyList()))

        assertNull(feed.status)
        assertTrue(feed.downloads.isEmpty())
        assertTrue(feed.phoneModels.isEmpty())
        assertTrue(feed.jobs.isEmpty())
        assertNull(feed.download("qwen3-8b"))
    }

    /**
     * Forget. The pairing changes at once and the feed hears of it a moment later, and in
     * between the widget, the tile and the launcher shortcut were written again with the
     * forgotten Mac's model. Asked for this pairing's status, the feed has none until the
     * stream for it has said so.
     */
    @Test
    fun `a forgotten Mac's status is not this pairing's`() = runTest {
        feed.paired(1)
        feed.start(Scripted(macA))
        assertEquals("qwen3", feed.statusFor(1)?.loadedModelID)

        assertNull("the next pairing is not told the last Mac's model", feed.statusFor(2))

        feed.paired(2)
        feed.start(null)
        assertNull(feed.status)
        assertNull(feed.statusFor(2))
    }

    /** The same pairing met again — the screen composed afresh — loses nothing. */
    @Test
    fun `the same pairing keeps what it heard`() = runTest {
        feed.paired(1)
        feed.start(Scripted(macA))

        feed.paired(1)

        assertEquals("qwen3", feed.statusFor(1)?.loadedModelID)
        assertNotNull(feed.download("qwen3-8b"))
        assertEquals(1, feed.jobs.size)
    }

    // MARK: - Which model a download is

    /**
     * The Models list asks for each installed model's download, and shows it in place of the
     * model's Load button. The Mac downloading the Q8 of a model is not the Q4 on disk: matched
     * by base name, the Q8's progress hid the Q4's Load button until the Q8 arrived.
     */
    @Test
    fun `a download stands in only for its own quantization`() = runTest {
        feed.start(
            Scripted(
                listOf(
                    ServerEvent.Download(DownloadProgress(id = "qwen3-8b@Q8_0", name = "Qwen3 8B", bytesReceived = 40, bytesExpected = 100)),
                    ServerEvent.Download(DownloadProgress(id = "qwen3-coder-30b", name = "Qwen3-Coder 30B A3B", bytesReceived = 40, bytesExpected = 100)),
                ),
            ),
        )

        assertNull("the Q8 download stood in for the Q4's Load button", feed.download("qwen3-8b@Q4_K_M"))
        assertEquals("qwen3-8b@Q8_0", feed.download("qwen3-8b@Q8_0")?.id)
        assertEquals("the same model, spelled without its quantization", "qwen3-8b@Q8_0", feed.download("qwen3-8b")?.id)
        assertEquals(
            "a download the Mac names without its quantization is the model's",
            "qwen3-coder-30b",
            feed.download("qwen3-coder-30b@Q4_K_M")?.id,
        )
        assertNull(feed.download("qwen3"))
    }

    // MARK: - The agent sessions' path

    private fun frame(seq: Long) = ServerEvent.Agent(
        AgentEvent(engine = "codex", kind = AgentEvent.TURN, seq = seq, epoch = "E1", turnActive = true),
    )

    /** A stream the test writes, counting how many times it was opened. */
    private class Counting(private val events: List<ServerEvent>) : HangingTransport() {
        var opened = 0
        override fun events(): Flow<ServerEvent> = flow {
            opened++
            events.forEach { emit(it) }
            kotlinx.coroutines.awaitCancellation()
        }
    }

    /**
     * The sessions hear a break in the same order as the frames around it: a frame after a
     * drop or a resync is not taken to follow on from the one before it.
     */
    @Test
    fun `agent frames are handed on in order, with each break where it happened`() = runTest(dispatcher) {
        val heard = mutableListOf<AgentFeed>()
        backgroundScope.launch { feed.agentEvents.toList(heard) }
        feed.start(
            Scripted(
                listOf(
                    frame(1),
                    ServerEvent.Disconnected(1, 1_000, "unreachable"),
                    frame(2),
                    ServerEvent.Resync(7),
                    frame(3),
                ),
            ),
        )
        assertEquals(
            listOf(
                AgentFeed.Broken, // a fresh start follows on from nothing
                AgentFeed.Frame((frame(1)).event),
                AgentFeed.Broken,
                AgentFeed.Frame((frame(2)).event),
                AgentFeed.Broken,
                AgentFeed.Frame((frame(3)).event),
            ),
            heard,
        )
        assertEquals("the rest of the app hears a resync too", 1, feed.resyncs)
    }

    /** Off screen the stream closes, and comes back once — not once per caller. */
    @Test
    fun `pausing closes the stream and resuming opens it exactly once`() = runTest(dispatcher) {
        val mac = Counting(listOf(frame(1)))
        feed.start(mac)
        assertEquals(1, mac.opened)
        assertTrue(feed.isLive)

        feed.pause()
        assertFalse(feed.isLive)
        assertTrue(feed.resume())
        assertEquals(2, mac.opened)
        assertFalse("nothing left to resume", feed.resume())
        assertEquals(2, mac.opened)
    }

    /** A feed started afresh while paused — a new Mac — is not opened a second time. */
    @Test
    fun `a stream started afresh is not reopened by a stale pause`() = runTest(dispatcher) {
        val first = Counting(emptyList())
        val second = Counting(emptyList())
        feed.start(first)
        feed.pause()
        feed.start(second)
        assertFalse(feed.resume())
        assertEquals(1, first.opened)
        assertEquals(1, second.opened)
    }

    @Test
    fun `pausing a feed that never started does nothing`() = runTest(dispatcher) {
        feed.pause()
        assertFalse(feed.resume())
    }

    /** A stream that throws instead of emitting: a Mac without the route, or one that refused. */
    private class Refusing(private val error: dev.siliconoptimizer.buddy.transport.TransportError) : HangingTransport() {
        override fun events(): Flow<ServerEvent> = flow { throw error }
    }

    /** 401 on the stream: the Mac no longer knows this phone, and asking instead cannot help. */
    @Test
    fun `a refused token stops the stream without falling back to polling`() = runTest {
        feed.start(Refusing(dev.siliconoptimizer.buddy.transport.TransportError.Unauthorized))
        assertTrue(feed.unauthorized)
        assertFalse("polling a Mac that refuses this phone is refused too, every few seconds", feed.mustPoll)
        assertFalse(feed.isLive)
        // Paired again: a new start is a new chance.
        feed.start(Scripted(listOf(ServerEvent.StatusChanged(status))))
        assertFalse(feed.unauthorized)
    }

    @Test
    fun `a Mac without the stream is polled instead`() = runTest {
        feed.start(Refusing(dev.siliconoptimizer.buddy.transport.TransportError.RouteUnavailable("/events")))
        assertTrue(feed.mustPoll)
        assertFalse(feed.unauthorized)
    }
}
