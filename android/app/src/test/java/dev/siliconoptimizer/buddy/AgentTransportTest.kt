package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentMessageRequest
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every agent route, over a real socket, answered with the Mac's own exported bodies.
 *
 * What is checked is what goes out — the method, the path, the body, the query — and that
 * every refusal the Mac documents for these routes arrives as the case a screen can act on:
 * a chat-only device told why, an approval that is gone told apart from a Mac too old to
 * have approvals, and "answered at the Mac first" arriving as its own sentence.
 */
class AgentTransportTest {

    private lateinit var server: LoopbackServer
    private lateinit var client: ControlClient

    @Before
    fun setUp() {
        server = LoopbackServer()
        client = ControlClient(ServerConfig("127.0.0.1", server.port, token = "device-token"))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun fixture(name: String): JsonObject {
        val text = javaClass.classLoader!!.getResourceAsStream("$name.json")!!.bufferedReader().readText()
        return Json.parseToJsonElement(text) as JsonObject
    }

    private fun response(name: String): String = fixture(name).getValue("response").toString()

    private fun error(name: String, status: Int): String =
        (fixture(name).getValue("errors") as JsonObject).getValue(status.toString()).toString()

    private fun sent(path: String): JsonObject =
        Json.parseToJsonElement(server.request(path)!!.body) as JsonObject

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.content

    private suspend fun failure(block: suspend () -> Unit): Throwable? =
        runCatching { block() }.exceptionOrNull()

    // MARK: - Reading

    @Test
    fun `the session list is read and decoded`() = runTest {
        server.reply("/agent/sessions", 200, response("GET__agent_sessions"))
        val list = client.agentSessions()
        assertEquals(listOf("codex", "pi"), list.sessions.map { it.engine })
        assertEquals(1, list.sessions.first().pendingApprovals)
        assertEquals("GET", server.request("/agent/sessions")!!.method)
        assertEquals("Bearer device-token", server.request("/agent/sessions")!!.headers["authorization"])
    }

    @Test
    fun `a session is read whole without since`() = runTest {
        server.reply("/agent/sessions/codex", 200, response("GET__agent_sessions__engine_"))
        val detail = client.agentSession("codex")
        assertTrue(detail.complete)
        assertEquals(41L, detail.seq)
        assertEquals("", server.request("/agent/sessions/codex")!!.query)
    }

    @Test
    fun `a catch-up sends the cursor, both halves of it`() = runTest {
        server.reply("/agent/sessions/codex", 200, response("GET__agent_sessions__engine_"))
        client.agentSession("codex", since = 39, epoch = "4B1D6C3E-2A9F-4E70-8D51-7C6B5A493827")
        assertEquals(
            "since=39&epoch=4B1D6C3E-2A9F-4E70-8D51-7C6B5A493827",
            server.request("/agent/sessions/codex")!!.query,
        )
    }

    /** A sequence number means nothing outside the transcript that issued it. */
    @Test
    fun `half a cursor is no cursor`() = runTest {
        server.reply("/agent/sessions/codex", 200, response("GET__agent_sessions__engine_"))
        client.agentSession("codex", since = 39, epoch = null)
        assertEquals("", server.request("/agent/sessions/codex")!!.query)
    }

    @Test
    fun `a since that is not a number is the Mac's bad request`() = runTest {
        server.reply("/agent/sessions/codex", 400, error("GET__agent_sessions__engine_", 400))
        assertTrue(failure { client.agentSession("codex") } is TransportError.BadRequest)
    }

    // MARK: - The verbs

    @Test
    fun `start, new thread and interrupt are posts that carry a length`() = runTest {
        server.reply("/agent/sessions/codex/start", 200, response("POST__agent_sessions__engine__start"))
        server.reply("/agent/sessions/codex/new", 200, response("POST__agent_sessions__engine__new"))
        server.reply("/agent/sessions/codex/interrupt", 200, response("POST__agent_sessions__engine__interrupt"))
        assertEquals("running", client.startAgent("codex").state)
        assertEquals(0, client.newAgentThread("codex").itemCount)
        assertTrue(client.interruptAgent("codex").turnActive)
        for (verb in listOf("start", "new", "interrupt")) {
            val request = server.request("/agent/sessions/codex/$verb")!!
            assertEquals("POST", request.method)
            assertEquals("every body this app sends has a length", request.body.length.toString(), request.headers["content-length"])
        }
    }

    @Test
    fun `stopping is a delete on the session`() = runTest {
        server.reply("/agent/sessions/pi", 200, response("DELETE__agent_sessions__engine_"))
        val stopped = client.stopAgent("pi")
        assertEquals("stopped", stopped.state)
        assertEquals("DELETE", server.request("/agent/sessions/pi")!!.method)
    }

    @Test
    fun `a message is text and a model, and 202 is success`() = runTest {
        server.reply("/agent/sessions/codex/messages", 202, response("POST__agent_sessions__engine__messages"))
        val accepted = client.sendAgentMessage(
            "codex", AgentMessageRequest("Run the tests and fix whatever the first failure is.", "local/qwen3-coder-30b"),
        )
        assertEquals("7C3E1A50-6B2D-4F19-8E44-0A1B2C3D4E5F", accepted.itemID)
        val body = sent("/agent/sessions/codex/messages")
        assertEquals("Run the tests and fix whatever the first failure is.", body["text"].text())
        assertEquals("local/qwen3-coder-30b", body["model"].text())
    }

    @Test
    fun `a message without a model sends no model field at all`() = runTest {
        server.reply("/agent/sessions/pi/messages", 202, response("POST__agent_sessions__engine__messages"))
        client.sendAgentMessage("pi", AgentMessageRequest("List the notes."))
        assertNull("absent, not null: the Mac would read null as a model", sent("/agent/sessions/pi/messages")["model"])
    }

    @Test
    fun `an answer to an approval posts the decision to that approval`() = runTest {
        val id = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81"
        server.reply("/agent/sessions/codex/approvals/$id", 200, response("POST__agent_sessions__engine__approvals__id_"))
        val result = client.answerAgentApproval("codex", id, AgentApprovalDecision.ACCEPT)
        assertEquals("accepted", result.decision)
        assertEquals(0, result.session.pendingApprovals)
        assertEquals("accept", sent("/agent/sessions/codex/approvals/$id")["decision"].text())
    }

    @Test
    fun `an engine or an id is data, not a path`() = runTest {
        server.reply("/agent/sessions/..%2Fstatus/approvals/a%2Fb", 404, error("POST__agent_sessions__engine__approvals__id_", 404))
        failure { client.answerAgentApproval("../status", "a/b", AgentApprovalDecision.DECLINE) }
        assertTrue(server.request("/agent/sessions/..%2Fstatus/approvals/a%2Fb") != null)
    }

    // MARK: - What the Mac refuses, and how it reaches a screen

    /** A swarm node is refused by name: these routes are for the owner's own devices. */
    @Test
    fun `a swarm token is refused as not allowed, in the Mac's words`() = runTest {
        val refusal = (fixture("GET__agent_sessions").getValue("errorVariants") as JsonObject)
            .getValue("403").let { (it as JsonObject).getValue("swarm") }.toString()
        server.reply("/agent/sessions", 403, refusal)
        val error = failure { client.agentSessions() }
        assertTrue(error is TransportError.Forbidden)
        assertTrue(error!!.message!!.contains("swarm node"))
    }

    @Test
    fun `a card still being screened is a conflict that says so`() = runTest {
        val id = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81"
        val variants = (fixture("POST__agent_sessions__engine__approvals__id_").getValue("errorVariants") as JsonObject)
            .getValue("409") as JsonObject
        server.reply("/agent/sessions/codex/approvals/$id", 409, variants.getValue("still screening").toString())
        val error = failure { client.answerAgentApproval("codex", id, AgentApprovalDecision.ACCEPT) }
        assertTrue(error is TransportError.Conflict)
        assertTrue(error!!.message!!.contains("still screening"))
    }

    @Test
    fun `a Codex turn in progress is a conflict that names the way out`() = runTest {
        val variants = (fixture("POST__agent_sessions__engine__messages").getValue("errorVariants") as JsonObject)
            .getValue("409") as JsonObject
        server.reply("/agent/sessions/codex/messages", 409, variants.getValue("turn in progress").toString())
        val error = failure { client.sendAgentMessage("codex", AgentMessageRequest("and another thing")) }
        assertTrue(error is TransportError.Conflict)
        assertTrue(error!!.message!!.contains("interrupt"))
    }

    @Test
    fun `a chat-only device is told why, on every agent route`() = runTest {
        val refusal = error("GET__agent_sessions", 403)
        for (path in listOf("/agent/sessions", "/agent/sessions/codex", "/agent/sessions/codex/start", "/agent/sessions/codex/messages")) {
            server.reply(path, 403, refusal)
        }
        val calls: List<suspend () -> Unit> = listOf(
            { client.agentSessions() },
            { client.agentSession("codex") },
            { client.startAgent("codex") },
            { client.sendAgentMessage("codex", AgentMessageRequest("hello")) },
        )
        for (call in calls) {
            val error = failure { call() }
            assertTrue("$error", error is TransportError.Forbidden)
            assertTrue(error!!.message!!.contains("chat only"))
        }
    }

    @Test
    fun `a Mac from before agent sessions is a missing route, not an error to show`() = runTest {
        server.reply("/agent/sessions", 404, """{"error":"Unknown endpoint GET /agent/sessions"}""")
        val error = failure { client.agentSessions() }
        assertTrue(error is TransportError.RouteUnavailable)
    }

    /** 404 on an approval is about the approval: answered already, or gone. Not an old Mac. */
    @Test
    fun `an approval that is gone is not found`() = runTest {
        val id = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81"
        server.reply("/agent/sessions/codex/approvals/$id", 404, error("POST__agent_sessions__engine__approvals__id_", 404))
        val error = failure { client.answerAgentApproval("codex", id, AgentApprovalDecision.ACCEPT) }
        assertTrue("$error", error is TransportError.NotFound)
        assertFalse(error is TransportError.RouteUnavailable)
    }

    @Test
    fun `answered at the Mac first is a conflict carrying the Mac's own sentence`() = runTest {
        val id = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81"
        server.reply("/agent/sessions/codex/approvals/$id", 409, error("POST__agent_sessions__engine__approvals__id_", 409))
        val error = failure { client.answerAgentApproval("codex", id, AgentApprovalDecision.DECLINE) }
        assertTrue(error is TransportError.Conflict)
        assertTrue(error!!.message!!.contains("answered at the Mac"))
    }

    @Test
    fun `a decision that is not one is a bad request`() = runTest {
        val id = "5D8B2F01-9A3C-4E67-8B21-0C4D5E6F7A81"
        server.reply("/agent/sessions/codex/approvals/$id", 400, error("POST__agent_sessions__engine__approvals__id_", 400))
        assertTrue(failure { client.answerAgentApproval("codex", id, "maybe") } is TransportError.BadRequest)
    }

    @Test
    fun `a model that is not one of the session's is a bad request naming the list`() = runTest {
        server.reply("/agent/sessions/codex/messages", 400, error("POST__agent_sessions__engine__messages", 400))
        val error = failure { client.sendAgentMessage("codex", AgentMessageRequest("hello", "gpt-5")) }
        assertTrue(error is TransportError.BadRequest)
        assertTrue(error!!.message!!.contains("modelChoices"))
    }

    @Test
    fun `an engine that is not running says how to start it`() = runTest {
        for (verb in listOf("messages", "interrupt", "new")) {
            val name = "POST__agent_sessions__engine__$verb"
            server.reply("/agent/sessions/pi/$verb", 409, error(name, 409))
        }
        assertTrue(failure { client.sendAgentMessage("pi", AgentMessageRequest("hello")) } is TransportError.Conflict)
        assertTrue(failure { client.interruptAgent("pi") } is TransportError.Conflict)
        val error = failure { client.newAgentThread("pi") }
        assertTrue(error is TransportError.Conflict)
        assertTrue(error!!.message!!.contains("/start"))
    }

    /** The one thing a phone may not choose: which folder Codex is trusted in. */
    @Test
    fun `starting Codex before the Mac picked a folder says so`() = runTest {
        server.reply("/agent/sessions/codex/start", 409, error("POST__agent_sessions__engine__start", 409))
        val error = failure { client.startAgent("codex") }
        assertTrue(error is TransportError.Conflict)
        assertTrue(error!!.message!!.contains("working folder"))
    }

    @Test
    fun `an engine the Mac does not run is not found`() = runTest {
        server.reply("/agent/sessions/claude", 404, error("DELETE__agent_sessions__engine_", 404))
        assertTrue(failure { client.stopAgent("claude") } is TransportError.RouteUnavailable)
    }

    // MARK: - The stream

    @Test
    fun `agent frames arrive on the event stream, every kind of them`() = runTest {
        val events = fixture("GET__events")
        val variants = (events.getValue("eventVariants") as JsonObject).getValue("agent") as JsonObject
        val frames = listOf(
            (events.getValue("events") as JsonObject).getValue("agent"),
            variants.getValue("state"),
            variants.getValue("turn"),
            variants.getValue("approval"),
            variants.getValue("approval answered"),
            variants.getValue("reset"),
        )
        val resync = (events.getValue("events") as JsonObject).getValue("resync")
        server.events(
            "/events",
            frames.joinToString("") { "event: agent\ndata: $it\n\n" } +
                "event: resync\ndata: $resync\n\n" +
                // A frame with fields this build has never heard of still parses.
                "event: agent\ndata: {\"engine\":\"pi\",\"kind\":\"item\",\"seq\":50,\"epoch\":\"E\",\"epochs\":[1],\"item\":{\"id\":\"i\",\"kind\":\"notice\",\"text\":\"hi\",\"at\":\"2026-09-19T10:12:38Z\"}}\n\n",
        )
        val all = client.events().take(8).toList()
        val received = all.filterIsInstance<ServerEvent.Agent>().map { it.event }
        assertEquals(
            listOf(AgentEvent.ITEM, AgentEvent.STATE, AgentEvent.TURN, AgentEvent.APPROVAL, AgentEvent.APPROVAL, AgentEvent.RESET, AgentEvent.ITEM),
            received.map { it.kind },
        )
        assertEquals("swift test --filter Lisbon", received[0].item!!.text)
        assertEquals(true, received[0].item!!.truncated)
        assertEquals(39L, received[0].seq)
        assertEquals("running", received[1].state)
        assertEquals(true, received[2].turnActive)
        assertEquals(AgentEvent.PENDING, received[3].state)
        assertEquals(AgentEvent.ACCEPTED, received[4].state)
        assertEquals(received[3].approval!!.id, received[4].approval!!.id)
        assertTrue("a reset names a different transcript", received[5].epoch != received[0].epoch)
        assertEquals("pi", received[6].engine)
        assertEquals(7, (all[6] as ServerEvent.Resync).dropped)
    }

    /**
     * A frame this build cannot read is still a frame the sessions missed. Dropping it
     * quietly let the cursor walk past it; it arrives as a gap instead.
     */
    @Test
    fun `an agent frame that does not decode arrives as a resync`() = runTest {
        server.events(
            "/events",
            "event: agent\ndata: {\"engine\":\"codex\",\"kind\":\"item\"}\n\n" +
                "event: agent\ndata: not json at all\n\n",
        )
        val received = client.events().take(2).toList()
        assertEquals(listOf(ServerEvent.Resync(null), ServerEvent.Resync(null)), received)
    }
}
