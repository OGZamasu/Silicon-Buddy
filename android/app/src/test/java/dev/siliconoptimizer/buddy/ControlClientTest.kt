package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What this app puts on a socket, and what it makes of what comes back.
 *
 * These exist because reverting the SSE blank-line fix left the whole suite green: the
 * transport had no test that spoke HTTP at all, and a parser test cannot notice that
 * nothing reaches the parser.
 */
class ControlClientTest {

    private lateinit var server: LoopbackServer
    private lateinit var client: ControlClient

    @Before
    fun setUp() {
        server = LoopbackServer()
        client = ControlClient(ServerConfig("127.0.0.1", server.port, "device-token"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    // MARK: - The host gate

    @Test
    fun `nothing leaves the app for a host outside the tailnet`() = runBlocking {
        val rogue = ControlClient(ServerConfig("evil.example.com", server.port, "t"))
        val error = runCatching { rogue.status() }.exceptionOrNull()
        assertTrue("Expected a refusal, got $error", error is TransportError.Forbidden)
        assertTrue("Nothing should have left the app", server.requests.isEmpty())
    }

    // MARK: - Pairing against a Mac that has no pairing

    /**
     * An unknown route answers 401 to a caller with no token and 404 only to one with a
     * good one — and `/buddy/pair` is the one call made without a token. A Mac that has
     * the route can never 401 it.
     */
    @Test
    fun `a pre-M0 Mac answers 401 to pairing and that means the route is missing`() = runBlocking {
        server.reply("/buddy/pair", 401, """{"error":"Invalid or missing control token."}""")
        val error = runCatching { client.pair("123456", "Pixel", "android") }.exceptionOrNull()
        assertEquals(TransportError.RouteUnavailable("/buddy/pair"), error)
        assertTrue((error as TransportError).isMissingRoute)
    }

    @Test
    fun `a wrong pairing code is refused without claiming the route is missing`() = runBlocking {
        server.reply("/buddy/pair", 403, """{"error":"That pairing code is not the one on screen."}""")
        val error = runCatching { client.pair("000000", "Pixel", "android") }.exceptionOrNull()
        assertTrue(error is TransportError.Forbidden)
        assertTrue(!(error as TransportError).isMissingRoute)
        assertEquals("That pairing code is not the one on screen.", error.message)
    }

    @Test
    fun `pairing carries the scope the Mac granted`() = runBlocking {
        server.reply(
            "/buddy/pair", 200,
            """{"deviceID":"D1","token":"t","macName":"Mac Studio","port":8788,"scope":"chat"}""",
        )
        val paired = client.pair("123456", "Pixel", "android")
        assertEquals(DeviceScope.Chat, DeviceScope.from(paired.scope))
    }

    // MARK: - What the server refuses

    @Test
    fun `a chat-scope device is told why loading was refused`() = runBlocking {
        server.reply("/load", 403, """{"error":"This device is paired for chat only."}""")
        val error = runCatching { client.load(LoadRequest("x")) }.exceptionOrNull()
        assertEquals(TransportError.Forbidden("This device is paired for chat only."), error)
    }

    @Test
    fun `a conversation that is gone is not a route that is missing`() = runBlocking {
        server.reply("/conversations/C9", 404, """{"error":"No conversation with id C9."}""")
        val error = runCatching { client.conversation("C9") }.exceptionOrNull()
        assertTrue("Expected NotFound, got $error", error is TransportError.NotFound)
        assertTrue(!(error as TransportError).isMissingRoute)
    }

    @Test
    fun `a message to a conversation the Mac has forgotten is not a Mac without conversations`() = runBlocking {
        // The difference matters: read as "this Mac has no /conversations", the app moves
        // every conversation onto the phone and stops syncing.
        server.reply("/conversations/C9/messages", 404, """{"error":"No conversation with id C9."}""")
        val error = runCatching {
            client.sendMessage("C9", ChatMessageWire("user", "Hello")).toList<ChatStreamEvent>()
        }.exceptionOrNull()
        assertTrue("Expected NotFound, got $error", error is TransportError.NotFound)
        assertTrue(!(error as TransportError).isMissingRoute)
    }

    @Test
    fun `a redirect is not followed, and the token does not go with it`() = runBlocking {
        server.reply(
            "/status", 302, """{"error":"moved"}""",
            mapOf("Location" to "http://127.0.0.1:${server.port}/elsewhere"),
        )
        server.reply("/elsewhere", 200, """{"state":"Ready"}""")
        runCatching { client.status() }
        assertNull(
            "a redirect is a request to send the owner's bearer token somewhere else",
            server.request("/elsewhere"),
        )
    }

    // MARK: - What the client sends

    @Test
    fun `the conversation route sends one message, not a transcript`() = runBlocking {
        server.events(
            "/conversations/C1/messages",
            "event: token\ndata: {\"text\":\"Hi\"}\n\n" +
                "event: finished\ndata: {\"promptTokens\":4,\"generatedTokens\":1," +
                "\"tokensPerSecond\":12.5,\"timeToFirstToken\":0.2}\n\n",
        )
        val events = client.sendMessage(
            "C1",
            ChatMessageWire("user", "Hello", listOf("data:image/jpeg;base64,AAA")),
            maxTokens = 512,
        ).toList()

        assertEquals("Hi", events.filterIsInstance<ChatStreamEvent.Token>().joinToString("") { it.text })
        assertEquals(
            0.2,
            events.filterIsInstance<ChatStreamEvent.Finished>().first().metrics.timeToFirstToken,
        )

        val sent = Json.parseToJsonElement(
            server.request("/conversations/C1/messages")!!.body,
        ) as JsonObject
        assertEquals("Hello", (sent["content"] as JsonPrimitive).content)
        assertEquals("512", (sent["maxTokens"] as JsonPrimitive).content)
        assertNull("That is the /chat shape, not this one", sent["messages"])
    }

    @Test
    fun `an id goes into the path encoded`() = runBlocking {
        server.reply("/conversations/a%2Fb", 200, """{"id":"a/b","title":"t","updatedAt":"2026-09-18T09:41:12Z","isGenerating":false,"messages":[]}""")
        val detail = client.conversation("a/b")
        assertEquals("a/b", detail.id)
        assertNotNull(server.request("/conversations/a%2Fb"))
    }

    @Test
    fun `every request carries a content length, never a chunked body`() = runBlocking {
        server.reply("/chat", 200, """{"content":"hi","promptTokens":1,"generatedTokens":1,"tokensPerSecond":1.0}""")
        client.chat(ChatRequest(listOf(ChatMessageWire("user", "hi"))))
        val request = server.request("/chat")!!
        assertNotNull("The Mac answers 411 without one", request.headers["content-length"])
        assertNull(request.headers["transfer-encoding"])
    }

    // MARK: - Framing

    @Test
    fun `the event stream decodes the Mac's field names`() = runBlocking {
        server.events(
            "/events",
            "event: status\ndata: {\"state\":\"running\",\"loadedModelID\":\"qwen3\"," +
                "\"expertStreaming\":false}\n\n" +
                "event: download\ndata: {\"id\":\"qwen3\",\"name\":\"Qwen3\"," +
                "\"bytesReceived\":8589934592,\"bytesExpected\":20401094656," +
                "\"bytesPerSecond\":41943040,\"fraction\":0.42}\n\n" +
                "event: job\ndata: {\"id\":\"J1\",\"kind\":\"video\",\"status\":\"running\"," +
                "\"fraction\":0.33,\"title\":\"Opening shot\"}\n\n" +
                "event: heartbeat\ndata: {\"at\":\"2026-09-18T09:41:00Z\"}\n\n",
        )
        val events = withTimeout(20_000) { client.events().take(4).toList() }
        assertEquals("qwen3", (events[0] as ServerEvent.StatusChanged).status.loadedModelID)
        val download = (events[1] as ServerEvent.Download).progress
        assertEquals("Qwen3", download.name)
        assertEquals(8_589_934_592L, download.bytesReceived)
        assertEquals(0.42, download.progress)
        assertEquals("running", (events[2] as ServerEvent.Job).progress.status)
        assertNotNull((events[3] as ServerEvent.Beat).at)
    }

    /** CRLF is what a strict server sends, and the parser must not keep the CR. */
    @Test
    fun `a CRLF stream is framed the same way`() = runBlocking {
        server.events(
            "/events",
            "event: status\r\ndata: {\"state\":\"running\",\"expertStreaming\":false}\r\n\r\n",
        )
        val events = withTimeout(20_000) { client.events().take(1).toList() }
        assertEquals("running", (events[0] as ServerEvent.StatusChanged).status.state)
    }

    /**
     * The blank line is the only thing that ends an SSE block. This is the test that
     * would have caught reading the stream with a line reader that collapses them.
     */
    @Test
    fun `an event only arrives once its blank line does`() = runBlocking {
        // Two blocks, split so the first arrives without its terminator.
        val first = "event: token\ndata: {\"text\":\"one\"}"
        val rest = "\n\nevent: token\ndata: {\"text\":\"two\"}\n\n"
        server.events("/chat/stream", first + rest, splitAt = first.length)
        val events = withTimeout(20_000) {
            client.chatStream(ChatRequest(listOf(ChatMessageWire("user", "hi")))).toList()
        }
        assertEquals(
            listOf("one", "two"),
            events.filterIsInstance<ChatStreamEvent.Token>().map { it.text },
        )
    }

    /** A character split across two reads is still one character. */
    @Test
    fun `a multibyte character split across reads survives`() = runBlocking {
        val body = "event: token\ndata: {\"text\":\"héllo — ok\"}\n\n".toByteArray(Charsets.UTF_8)
        // Split inside the em dash, which is three bytes in UTF-8.
        val dash = body.indexOfFirst { it == 0xE2.toByte() }
        assertTrue("The fixture should contain a multi-byte character", dash > 0)
        server.eventBytes(
            "/chat/stream",
            listOf(body.copyOfRange(0, dash + 1), body.copyOfRange(dash + 1, body.size)),
        )
        val events = withTimeout(20_000) {
            client.chatStream(ChatRequest(listOf(ChatMessageWire("user", "hi")))).toList()
        }
        assertEquals(
            "héllo — ok",
            events.filterIsInstance<ChatStreamEvent.Token>().joinToString("") { it.text },
        )
    }

    @Test
    fun `a Mac without events says so once rather than retrying`() = runBlocking {
        server.reply("/events", 404, """{"error":"Unknown endpoint"}""")
        val error = runCatching {
            withTimeout(10_000) { client.events().toList() }
        }.exceptionOrNull()
        assertTrue("Expected the missing route, got $error", (error as TransportError).isMissingRoute)
        assertEquals("It must not knock twice", 1, server.requestCount("/events"))
    }

    // MARK: - Reconnection

    @Test
    fun `the backoff policy grows and then stops growing`() {
        assertEquals(0, ControlClient.nextAttempt(0, connectedForMs = 40_000))
        assertEquals(1, ControlClient.nextAttempt(0, connectedForMs = 100))
        assertEquals(2, ControlClient.nextAttempt(1, connectedForMs = 100))
        // A stream that stayed up is what resets it — not an event, which a Mac can
        // send once and then drop.
        assertEquals(0, ControlClient.nextAttempt(5, connectedForMs = 30_000))
        assertEquals(6, ControlClient.nextAttempt(6, connectedForMs = 100))

        assertEquals(0L, ControlClient.reconnectDelayMillis(0))
        assertEquals(1000L, ControlClient.reconnectDelayMillis(1))
        assertEquals(2000L, ControlClient.reconnectDelayMillis(2))
        assertEquals(4000L, ControlClient.reconnectDelayMillis(3))
        assertEquals(30_000L, ControlClient.reconnectDelayMillis(6))
    }

    /**
     * A Mac that sends one event and drops is the case that used to pin the delay at a
     * second forever. The gaps between connections have to grow.
     */
    @Test
    fun `a stream that keeps dropping is retried less and less often`() = runBlocking {
        server.events("/events", "event: heartbeat\ndata: {}\n\n")
        val collector = Thread {
            runBlocking { runCatching { client.events().collect { } } }
        }
        collector.isDaemon = true
        collector.start()
        try {
            assertTrue(
                "The client should have reconnected three times",
                server.awaitRequests("/events", 3, timeoutMs = 20_000),
            )
        } finally {
            @Suppress("DEPRECATION")
            collector.interrupt()
        }
        val gaps = server.gaps("/events")
        assertTrue("Expected at least two gaps, got $gaps", gaps.size >= 2)
        assertTrue("First gap should be about a second, was ${gaps[0]}ms", gaps[0] >= 700)
        assertTrue("Second gap should be longer, was ${gaps[1]}ms", gaps[1] >= gaps[0] + 500)
    }

    /**
     * A drop has to be visible to the caller, not only to the client that heals it.
     *
     * Without this the stream reconnects silently and `EventFeed.isLive` stays true
     * forever after the first event — so Settings says "Streaming from /events" while
     * the phone is in aeroplane mode, which is precisely when someone would look.
     */
    @Test
    fun `a dropped stream is announced with the delay before the next attempt`() = runBlocking {
        server.events("/events", "event: heartbeat\ndata: {}\n\n")
        val events = withTimeout(20_000) { client.events().take(2).toList() }
        assertTrue("First should be the heartbeat", events[0] is ServerEvent.Beat)
        val dropped = events[1] as ServerEvent.Disconnected
        // The stream carried one heartbeat and died well inside STEADY_CONNECTION_MS, so
        // this is the first backoff step rather than a reset.
        assertEquals(1, dropped.attempt)
        assertEquals(ControlClient.reconnectDelayMillis(1), dropped.retryInMillis)
    }

    /** Announcing the drop must not stop the client healing it. */
    @Test
    fun `the stream still reopens after announcing that it dropped`() = runBlocking {
        server.events("/events", "event: heartbeat\ndata: {}\n\n")
        val events = withTimeout(30_000) { client.events().take(4).toList() }
        assertTrue(events[1] is ServerEvent.Disconnected)
        assertTrue("The stream should have delivered again", events[2] is ServerEvent.Beat)
        assertTrue(events[3] is ServerEvent.Disconnected)
        assertEquals(2, (events[3] as ServerEvent.Disconnected).attempt)
    }
}
