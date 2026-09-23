package dev.siliconoptimizer.buddy

import android.app.Application
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.transport.ConversationDetail
import dev.siliconoptimizer.buddy.transport.ConversationSummary
import dev.siliconoptimizer.buddy.transport.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Coming back from a process death with the conversation intact.
 *
 * The open conversation's id is saved across process death, which is the fix for landing
 * on the dashboard after One UI kills a backgrounded app. It introduced a subtler
 * failure: the id comes back *before* the Mac has been asked whether it stores
 * conversations at all. `usesRemoteConversations` is false at that moment because
 * nothing is known yet, which is indistinguishable from a Mac that keeps none — so the
 * remote branch is skipped, the local store has nothing under that id, and `open` used
 * to fall through to `Conversation(id = id)`: the right title over an empty transcript,
 * which reads as the Mac having lost the conversation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestoreAfterDeathTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()

    /** A Mac that keeps conversations, and says so when asked. */
    private class RemoteMac : HangingTransport() {
        var conversationCalls = 0

        override suspend fun conversations(): List<ConversationSummary> = listOf(
            ConversationSummary("c-1", "Three days in Lisbon", "2026-09-19T00:00:00Z", 2),
        )

        override suspend fun conversation(id: String): ConversationDetail {
            conversationCalls++
            return ConversationDetail(
                id = id,
                title = "Three days in Lisbon",
                updatedAt = "2026-09-19T00:00:00Z",
                messages = listOf(
                    StoredMessage(
                        id = "m-1", role = "user",
                        content = "Three days in Lisbon — what would you do?",
                        createdAt = "2026-09-19T00:00:00Z",
                    ),
                    StoredMessage(
                        id = "m-2", role = "assistant", content = "Start in Alfama early.",
                        createdAt = "2026-09-19T00:00:01Z",
                    ),
                ),
            )
        }
    }

    private lateinit var model: ChatViewModel
    private lateinit var mac: RemoteMac

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mac = RemoteMac()
        model = ChatViewModel(Application(), ConversationStore(folder.newFolder()))
    }

    @After
    fun tearDown() {
        // What `open` and `loadConversations` launched may still be reading the stores on
        // Dispatchers.IO; see `stopForTest`.
        model.stopForTest()
        Dispatchers.resetMain()
    }

    /**
     * The bug itself. A restored id, handed back before anything has been asked, must not
     * become an empty transcript — so opening it waits for the Mac to say what it keeps,
     * and then opens the real thing.
     */
    @Test
    fun `an id restored before the Mac has been asked waits for the answer rather than inventing a transcript`() = runTest {
        assertTrue("precondition", !model.askedAboutConversations)

        model.open("c-1", mac)

        assertEquals("c-1", awaitCurrentId())
        assertEquals("the Mac's own transcript, not an empty one", 2, model.current?.messages?.size)
        assertEquals("Start in Alfama early.", model.current?.messages?.last()?.content)
        assertTrue(model.askedAboutConversations)
        assertEquals(1, mac.conversationCalls)
    }

    /**
     * And when the Mac cannot be asked at all, nothing is invented: the conversation is
     * probably on it, and an empty transcript under its id reads as the Mac having lost it.
     */
    @Test
    fun `a Mac that cannot be reached leaves a restored id unopened`() = runTest {
        val unreachable = object : HangingTransport() {
            override suspend fun conversations(): List<ConversationSummary> =
                throw dev.siliconoptimizer.buddy.transport.TransportError.Unreachable("100.64.0.9")
        }

        model.open("c-1", unreachable)

        assertNull("An empty conversation was fabricated", awaitCurrentId(timeoutMs = 500))
        assertTrue("and the question stays open for when it is back", !model.askedAboutConversations)
    }

    /** And once the answer is in, the same id opens the real thing. */
    @Test
    fun `the same id opens the real conversation once the Mac has answered`() = runTest {
        model.loadConversations(mac)
        assertTrue("The Mac should have been asked", model.askedAboutConversations)
        assertTrue(model.usesRemoteConversations)

        model.open("c-1", mac)

        val current = model.current
        assertEquals("c-1", current?.id)
        assertEquals("Three days in Lisbon", current?.title)
        assertEquals(
            "The transcript should have come from the Mac",
            2,
            current?.messages?.size,
        )
        assertEquals("Start in Alfama early.", current?.messages?.last()?.content)
    }

    /**
     * The guard is about waiting for a Mac, so with no Mac there is nothing to wait for.
     *
     * Getting this wrong is worse than the bug it fixes: unpaired, nothing ever sets
     * `askedAboutConversations`, so a guard that only looked at that flag would make
     * every local conversation permanently unopenable. It did, until this test.
     */
    @Test
    fun `with no Mac a local conversation opens straight away`() = runTest {
        assertTrue("precondition", !model.askedAboutConversations)

        model.newConversation()
        val id = model.current?.id
        assertTrue("A local conversation should have been made", id != null)

        // Drop it, the way a process death would, and open it again by id alone.
        model.macChanged()
        assertNull(model.current)
        model.open(id!!, null)

        // The local store reads on `Dispatchers.IO`, which is a real thread pool that
        // `runTest` has no say over, so this waits for the value rather than assuming
        // the launch has landed.
        assertEquals("The local conversation should have opened", id, awaitCurrentId())
    }

    /** Polls briefly for the open conversation; null if it never arrives. */
    private fun awaitCurrentId(timeoutMs: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            model.current?.id?.let { return it }
            Thread.sleep(10)
        }
        return model.current?.id
    }
}
