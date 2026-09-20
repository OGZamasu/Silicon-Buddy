package dev.siliconoptimizer.buddy

import android.app.Application
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.transport.ChatMessageWire
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ConversationDetail
import dev.siliconoptimizer.buddy.transport.ConversationSummary
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/**
 * "+" on a Mac that keeps the conversations.
 *
 * A conversation the Mac keeps has an id the Mac chose. "+" that makes one here instead
 * hands the composer an id no Mac has ever heard of, and the first send goes to
 * `/conversations/{that id}/messages`, which is a 404 by construction. The transcript
 * then lives on the phone alone: it is not in the Mac's list, it is not on the Mac's
 * other devices, and nothing on the Mac can carry on from it.
 *
 * Worse before the conversation route learned to tell a missing *conversation* from a
 * missing *route*: one 404 read as "this Mac keeps no conversations at all" and turned
 * the syncing off wholesale, for every conversation, for the rest of the session. That
 * half is fixed; the test below holds it fixed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewConversationOnMacTest {

    @get:Rule val folder = TemporaryFolder()

    /**
     * A Mac that keeps conversations, and answers 404 about any id it did not hand out —
     * the way the real one does, and the way `ControlClient` reports it: a 404 from
     * `/conversations/{id}/messages` is about the conversation, not the route.
     */
    private open class KeepingMac : HangingTransport() {
        /** The ids this Mac has heard of. Anything else is a 404. */
        val known = CopyOnWriteArraySet(listOf("mac-1"))
        val calls = CopyOnWriteArrayList<String>()
        var created = 0
            private set

        /** Held closed to stand for the round trip `/conversations` really takes. */
        var listing: CompletableDeferred<Unit>? = null

        override suspend fun conversations(): List<ConversationSummary> {
            calls += "conversations"
            listing?.await()
            return known.sorted().map { ConversationSummary(it, "On the Mac", WHEN, 2) }
        }

        override suspend fun createConversation(title: String?): ConversationSummary {
            calls += "createConversation"
            created++
            val id = "mac-new-$created"
            known += id
            return ConversationSummary(id, title ?: "New conversation", WHEN, 0)
        }

        override suspend fun conversation(id: String): ConversationDetail {
            calls += "conversation:$id"
            if (id !in known) throw notThere()
            return ConversationDetail(id, "On the Mac", WHEN, messages = emptyList())
        }

        override fun sendMessage(
            conversationID: String,
            message: ChatMessageWire,
            maxTokens: Int?,
        ): Flow<ChatStreamEvent> = flow {
            calls += "sendMessage:$conversationID"
            if (conversationID !in known) throw notThere()
            emit(ChatStreamEvent.Token("From the Mac."))
            emit(ChatStreamEvent.Finished(ChatMetrics(3, 3, 30.0)))
        }

        override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            calls += "chatStream"
            emit(ChatStreamEvent.Token("From the Mac, conversation-less."))
            emit(ChatStreamEvent.Finished(ChatMetrics(3, 5, 30.0)))
        }

        /** What `ControlClient` turns that route's 404 into. */
        private fun notThere() =
            TransportError.NotFound("That conversation isn't on your Mac any more.")
    }

    private lateinit var mac: KeepingMac
    private lateinit var chat: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mac = KeepingMac()
        chat = ChatViewModel(Application(), ConversationStore(folder.newFolder()))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // MARK: - The bug

    /**
     * The report, exactly: "+" called with no transport, on a Mac that keeps
     * conversations and is answering. The conversation belongs on the Mac.
     */
    @Test
    fun `a new conversation on a Mac that keeps them is made on the Mac`() = runTest {
        chat.loadConversations(mac)
        assertTrue("precondition: this Mac keeps conversations", chat.usesRemoteConversations)

        chat.newConversation()

        val id = awaitCurrentId()
        assertNotNull("No conversation was opened", id)
        assertTrue(
            "The conversation was made on the phone under an id the Mac has never heard " +
                "of: $id, against known ${mac.known}",
            id in mac.known,
        )
        assertEquals("The Mac should have been asked to make it", 1, mac.created)
    }

    /**
     * And the send that follows goes to that conversation, so the Mac keeps the
     * transcript. Before the fix this was a 404 on the first message.
     */
    @Test
    fun `the first message in a new conversation reaches the Mac's conversation route`() = runTest {
        chat.loadConversations(mac)
        chat.newConversation()
        val id = awaitCurrentId()

        chat.draft = "Three days in Lisbon — what would you do?"
        chat.send(mac)
        awaitQuiet()

        assertEquals(
            "The message did not go to the Mac's conversation",
            listOf("sendMessage:$id"),
            mac.calls.filter { it.startsWith("sendMessage") },
        )
        assertTrue(
            "and it should not have fallen back to conversation-less streaming",
            "chatStream" !in mac.calls,
        )
        assertEquals("From the Mac.", chat.current?.messages?.last()?.content)
    }

    /**
     * The second half of the report: one unknown id must never be read as "this Mac
     * keeps no conversations". Here the id is unknown for the reason it is usually
     * unknown — the conversation was deleted on the Mac since the list was read — and
     * the answer still arrives, by the other route, with the syncing left on.
     */
    @Test
    fun `a conversation the Mac has forgotten does not turn the syncing off`() = runTest {
        chat.loadConversations(mac)
        chat.open("mac-1", mac)
        assertEquals("mac-1", awaitCurrentId())

        // Deleted on the Mac, from another device, between reading the list and sending.
        mac.known -= "mac-1"

        chat.draft = "Still there?"
        chat.send(mac)
        awaitQuiet()

        assertTrue(
            "One missing conversation turned off syncing for all of them",
            chat.usesRemoteConversations,
        )
        assertTrue("The Mac's conversation route was never tried", "sendMessage:mac-1" in mac.calls)
        assertTrue("and no answer was got any other way", "chatStream" in mac.calls)
        assertEquals("From the Mac, conversation-less.", chat.current?.messages?.last()?.content)
    }

    /**
     * And neither does a conversation route that answers and then says nothing.
     *
     * The only two ways that route can disappoint are a 404 about the conversation and a
     * stream that closes without an event. Both used to read as "this Mac keeps no
     * conversations"; neither is. The reply comes from the other route and the Mac keeps
     * its list.
     */
    @Test
    fun `a conversation route that sends nothing does not turn the syncing off`() = runTest {
        val silent = object : KeepingMac() {
            override fun sendMessage(
                conversationID: String,
                message: ChatMessageWire,
                maxTokens: Int?,
            ): Flow<ChatStreamEvent> = flow { calls += "sendMessage:$conversationID" }
        }
        chat.loadConversations(silent)
        chat.open("mac-1", silent)
        assertEquals("mac-1", awaitCurrentId())

        chat.draft = "Anything there?"
        chat.send(silent)
        awaitQuiet()

        assertTrue(
            "A silent conversation route turned off syncing for every conversation",
            chat.usesRemoteConversations,
        )
        assertTrue("sendMessage:mac-1" in silent.calls)
        assertEquals("From the Mac, conversation-less.", chat.current?.messages?.last()?.content)
    }

    // MARK: - The cold start

    /**
     * "+" tapped in the first second, before `/conversations` has answered.
     *
     * `usesRemoteConversations` is false then because nothing is known yet — which is
     * indistinguishable from a Mac that keeps none. Deciding on it makes a phone-only
     * conversation on a Mac that would have kept it, and the only sign is that it is
     * missing from every other device later.
     */
    @Test
    fun `a new conversation waits for the Mac to say whether it keeps them`() = runTest {
        mac.listing = CompletableDeferred()
        chat.loadConversations(mac)
        assertFalse("precondition: nothing is known yet", chat.askedAboutConversations)

        chat.newConversation(mac)
        // The Mac answers what it keeps a moment later, as it does on a cold start.
        mac.listing?.complete(Unit)

        val id = awaitCurrentId()
        assertNotNull("No conversation was opened", id)
        assertTrue(
            "Made on the phone before the Mac had answered: $id, against known ${mac.known}",
            id in mac.known,
        )
    }

    // MARK: - The cases that must stay local

    /** A Mac with no `/conversations` at all: the phone keeps them, as it always did. */
    @Test
    fun `a Mac that keeps no conversations still gets a local one`() = runTest {
        val old = object : KeepingMac() {
            override suspend fun conversations(): List<ConversationSummary> =
                throw TransportError.RouteUnavailable("/conversations")
        }
        chat.loadConversations(old)
        assertTrue(chat.askedAboutConversations)
        assertFalse(chat.usesRemoteConversations)

        chat.newConversation(old)

        assertNotNull("Nothing was opened", awaitCurrentId())
        assertEquals("This Mac has no route to make one on", 0, old.created)
    }

    /** And with no Mac at all there is nothing to wait for, and waiting would hang. */
    @Test
    fun `with no Mac a new conversation opens straight away`() = runTest {
        chat.newConversation(null)

        assertNotNull("Nothing was opened", chat.current?.id)
        assertFalse(chat.usesRemoteConversations)
    }

    /**
     * A Mac that keeps conversations but cannot be reached right now. One here, so the
     * composer opens — and the phone's own model can be offered into it.
     */
    @Test
    fun `a Mac that is out of reach gets a local conversation rather than none`() = runTest {
        chat.loadConversations(mac)
        val away = object : KeepingMac() {
            override suspend fun createConversation(title: String?): ConversationSummary =
                throw TransportError.Unreachable("100.64.0.9")
        }

        chat.newConversation(away)

        val id = awaitCurrentId()
        assertNotNull("Nothing was opened, so there is nowhere to type", id)
        assertFalse("It cannot have come from a Mac that never answered", id in away.known)
    }

    // MARK: - Waiting

    /** Polls briefly for the open conversation; null if it never arrives. */
    private fun awaitCurrentId(timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            chat.current?.id?.let { return it }
            Thread.sleep(10)
        }
        return chat.current?.id
    }

    /** Polls until the send has finished; the store writes on a real thread pool. */
    private fun awaitQuiet(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && chat.isSending) Thread.sleep(10)
        Thread.sleep(50)
    }

    private companion object {
        const val WHEN = "2026-09-19T00:00:00Z"
    }
}
