package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.ServerEvent
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * An event name this build has never heard of must be skipped, not fatal.
 *
 * The Mac grows them — `verdict` arrived with Jev's answer checking, and more will —
 * and a phone that treats one as an error breaks on the upgrade rather than on the
 * downgrade. The `else -> Unit` arms that do this were unexercised: the critic mutated
 * them to fail and the whole suite still passed, which is what this file exists for.
 */
class UnknownStreamEventTest {

    private lateinit var server: LoopbackServer
    private lateinit var client: ControlClient

    @Before
    fun setUp() {
        server = LoopbackServer()
        client = ControlClient(ServerConfig("127.0.0.1", server.port, token = "device-token"))
    }

    @After
    fun tearDown() = server.close()

    private val request = ChatRequest(listOf(ChatMessageWire("user", "hello")))

    /**
     * Two strangers in the middle of an answer: the `verdict` the Mac really sends, and
     * one nobody has invented yet.
     */
    @Test
    fun `the chat stream skips names it does not know`() = runBlocking {
        server.events(
            "/chat/stream",
            """
            event: token
            data: {"text":"A "}

            event: verdict
            data: {"verdict":"annotate","reasons":["a reason"]}

            event: weather
            data: {"outlook":"fine"}

            event: token
            data: {"text":"tailnet."}

            event: finished
            data: {"promptTokens":4,"generatedTokens":2,"tokensPerSecond":9.5}


            """.trimIndent(),
        )

        val events = withTimeout(20_000) { client.chatStream(request).toList() }
        val text = events.filterIsInstance<ChatStreamEvent.Token>().joinToString("") { it.text }
        assertEquals("A tailnet.", text)
        assertTrue(
            "the answer still ended",
            events.any { it is ChatStreamEvent.Finished },
        )
        assertTrue(
            "nothing turned into a failure",
            events.none { it is ChatStreamEvent.Failed },
        )
    }

    /** The same, on the conversation route, which is the one the app prefers. */
    @Test
    fun `the conversation stream skips names it does not know`() = runBlocking {
        server.events(
            "/conversations/C1/messages",
            """
            event: token
            data: {"text":"Yes"}

            event: somethingNew
            data: {"whatever":1}

            event: finished
            data: {"promptTokens":1,"generatedTokens":1,"tokensPerSecond":1}


            """.trimIndent(),
        )

        val events = withTimeout(20_000) {
            client.sendMessage("C1", ChatMessageWire("user", "?"), 64).toList()
        }
        assertEquals(
            "Yes",
            events.filterIsInstance<ChatStreamEvent.Token>().joinToString("") { it.text },
        )
    }

    /** `GET /events` has the same rule and a different `else` arm. */
    @Test
    fun `the event stream skips names it does not know`() = runBlocking {
        server.events(
            "/events",
            """
            event: aurora
            data: {"colour":"green"}

            event: status
            data: {"state":"Ready","expertStreaming":false}

            event: heartbeat
            data: {}


            """.trimIndent(),
        )

        val events = withTimeout(20_000) { client.events().take(2).toList() }
        assertTrue(
            "the status after the stranger still arrived",
            events.any { it is ServerEvent.StatusChanged },
        )
    }

    /**
     * And the one that is not a stranger any more, so the two rules are visibly
     * different: `verdict` is decoded on `/events`, and skipped mid-answer.
     */
    @Test
    fun `the event stream decodes a verdict`() = runBlocking {
        server.events(
            "/events",
            """
            event: verdict
            data: {"conversationID":"C1","messageID":"m4","verdict":"escalate","reasons":["r"]}

            event: heartbeat
            data: {}


            """.trimIndent(),
        )

        val events = withTimeout(20_000) { client.events().take(2).toList() }
        val checked = events.filterIsInstance<ServerEvent.Checked>().single().verdict
        assertEquals("m4", checked.messageID)
        assertEquals("escalate", checked.verdict)
    }
}
