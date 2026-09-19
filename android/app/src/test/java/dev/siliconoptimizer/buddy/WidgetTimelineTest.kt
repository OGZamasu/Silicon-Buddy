package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.reach.AskResult
import dev.siliconoptimizer.buddy.reach.BuddySnapshot
import dev.siliconoptimizer.buddy.reach.BuddyLink
import dev.siliconoptimizer.buddy.reach.QuickPrompt
import dev.siliconoptimizer.buddy.reach.WidgetTimeline
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The widget's content, against a real socket.
 *
 * A widget is the one part of this app that draws when nothing else is running, so the
 * thing worth testing is what it shows when the Mac does not answer — and that it never
 * throws, because a widget has nowhere to put an exception.
 */
class WidgetTimelineTest {

    private lateinit var server: LoopbackServer
    private lateinit var client: ControlClient

    private val status = """
        {"state":"Ready","loadedModelID":"qwen3-4b-mlx@MLX-4bit","loadedModelName":"Qwen3 4B (MLX)","contextLength":65536,"expertStreaming":false,"lastGenerationTokensPerSecond":68.4}
    """.trimIndent()

    @Before
    fun setUp() {
        server = LoopbackServer()
        client = ControlClient(ServerConfig("127.0.0.1", server.port, token = "device-token"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    // MARK: - Drawing what the Mac says

    @Test
    fun `an entry names the model the Mac has loaded`() = runBlocking {
        server.reply("/status", 200, status)
        val entry = WidgetTimeline.entry(client, stored = null, quickPrompt = QuickPrompt.DEFAULT)
        assertTrue(entry.isPaired)
        assertNull(entry.problem)
        assertEquals("Qwen3 4B (MLX)", entry.headline)
        assertEquals("qwen3-4b-mlx@MLX-4bit", entry.snapshot?.loadedModelID)
    }

    @Test
    fun `the last answer survives a refresh`() = runBlocking {
        server.reply("/status", 200, status)
        val stored = BuddySnapshot(lastQuestion = "Hello?", lastAnswer = "Hello yourself.")
        val entry = WidgetTimeline.entry(client, stored, QuickPrompt.DEFAULT)
        assertEquals("Hello yourself.", entry.body(90))
        assertEquals("Qwen3 4B (MLX)", entry.headline)
    }

    // MARK: - Drawing when it does not

    /**
     * The case a widget spends most of its life in: the Mac is asleep, or the phone is
     * off the tailnet. The last snapshot is still the best thing anybody knows.
     */
    @Test
    fun `a Mac that does not answer keeps the last snapshot and says why`() = runBlocking {
        server.reply("/status", 503, """{"error":"busy"}""")
        val stored = BuddySnapshot(loadedModelName = "Gemma 3 12B", lastAnswer = "Earlier.")
        val entry = WidgetTimeline.entry(client, stored, QuickPrompt.DEFAULT)
        assertTrue(entry.isPaired)
        assertEquals("Gemma 3 12B", entry.headline)
        assertEquals("Your Mac isn't reachable right now.", entry.problem)
    }

    @Test
    fun `a revoked device is told it is no longer paired`() = runBlocking {
        server.reply("/status", 401, """{"error":"Invalid or missing control token."}""")
        val entry = WidgetTimeline.entry(client, null, QuickPrompt.DEFAULT)
        assertEquals("This device is no longer paired with your Mac.", entry.problem)
    }

    @Test
    fun `no Mac at all asks to pair rather than showing an error`() = runBlocking {
        val entry = WidgetTimeline.entry(null, null, QuickPrompt.DEFAULT)
        assertFalse(entry.isPaired)
        assertEquals("Not paired", entry.headline)
        assertEquals("Open Silicon Buddy to pair with your Mac.", entry.problem)
    }

    // MARK: - The button

    @Test
    fun `the quick prompt asks once with no history`() = runBlocking {
        server.reply(
            "/chat", 200,
            """{"content":"Take a walk.","promptTokens":9,"generatedTokens":4,"tokensPerSecond":30.0}""",
        )
        val result = WidgetTimeline.ask("What should I do next?", client)
        assertEquals(AskResult.Answer("Take a walk."), result)

        val sent = server.requests.first { it.path == "/chat" }
        // One message, no history: a widget has no transcript behind it.
        assertTrue(sent.body.contains("What should I do next?"))
        assertEquals(1, Regex("\"role\"").findAll(sent.body).count())
    }

    @Test
    fun `a button press against an unreachable Mac shows a sentence rather than nothing`() = runBlocking {
        server.reply("/chat", 500, """{"error":"boom"}""")
        val result = WidgetTimeline.ask("Anything?", client)
        assertTrue(result is AskResult.Problem)
        assertTrue(result.text.isNotEmpty())
    }

    @Test
    fun `a button press with no Mac says so`() = runBlocking {
        assertEquals(AskResult.Problem("Not paired with a Mac."), WidgetTimeline.ask("Anything?", null))
    }

    // MARK: - A widget has seconds, not minutes

    /**
     * The transport's own ceilings are sized for a person watching a model think. A
     * Glance worker is killed long before that, and a killed worker leaves the last
     * content on screen — a button that looks like it does nothing. So the widget puts
     * its own deadline on the call.
     *
     * `runTest`'s virtual clock is what makes this instant rather than twenty seconds.
     */
    @Test
    fun `the button gives up rather than overrunning the widget's budget`() = runTest {
        val result = WidgetTimeline.ask("Anything?", HangingTransport())
        assertEquals(AskResult.Problem(WidgetTimeline.TOO_SLOW), result)
    }

    /** The same for the content refresh, which runs on every redraw. */
    @Test
    fun `a refresh gives up too and keeps the last snapshot`() = runTest {
        val stored = BuddySnapshot(loadedModelName = "Gemma 3 12B")
        val entry = WidgetTimeline.entry(HangingTransport(), stored, QuickPrompt.DEFAULT)
        assertEquals("Gemma 3 12B", entry.headline)
        assertEquals(WidgetTimeline.TOO_SLOW, entry.problem)
    }

    // MARK: - Trimming for a small surface

    @Test
    fun `an answer is cut on a word boundary`() {
        assertEquals("the quick…", BuddySnapshot.trim("the quick brown fox jumps", 12))
    }

    @Test
    fun `a short answer is left alone`() {
        assertEquals("short", BuddySnapshot.trim("short", 40))
    }

    @Test
    fun `newlines are flattened so a widget does not show one word per line`() {
        assertEquals("a b c", BuddySnapshot.trim("a\nb\nc", 40))
    }

    // MARK: - The links the widget and the tile fire

    @Test
    fun `the widget's tap opens an empty composer`() {
        assertEquals(
            BuddyLink.Compose(null),
            BuddyLink.parse("siliconbuddy", "ask", null, null),
        )
    }

    @Test
    fun `a composer link may carry text to type in`() {
        assertEquals(
            BuddyLink.Compose("what is this?"),
            BuddyLink.parse("siliconbuddy", "ask", "what is this?", null),
        )
    }

    /**
     * A URL can be fired by anything on the phone. The most one may do is fill the box
     * in, and a megabyte of it would be a denial of service dressed as a shortcut.
     */
    @Test
    fun `composed text is cut to something somebody meant to send`() {
        val link = BuddyLink.parse("siliconbuddy", "ask", "x".repeat(10_000), null)
        assertEquals(BuddyLink.MAX_COMPOSED_CHARACTERS, (link as BuddyLink.Compose).text!!.length)
    }

    @Test
    fun `another app's scheme is not ours`() {
        assertNull(BuddyLink.parse("shortcuts", "run", null, null))
    }

    @Test
    fun `an unknown action is not guessed at`() {
        assertNull(BuddyLink.parse("siliconbuddy", "delete", null, null))
    }

    @Test
    fun `a conversation link needs an id`() {
        assertNull(BuddyLink.parse("siliconbuddy", "conversation", null, null))
        assertEquals(
            BuddyLink.Conversation("abc"),
            BuddyLink.parse("siliconbuddy", "conversation", null, "abc"),
        )
    }
}
