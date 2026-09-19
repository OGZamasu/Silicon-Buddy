package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
}
