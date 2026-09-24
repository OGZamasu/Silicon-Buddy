package dev.siliconoptimizer.buddy

import android.app.Application
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A question asked while the last one is still being answered: push-to-talk mid-answer, or
 * Return pressed again.
 *
 * The send in flight is cancelled and the new one starts. The cancelled one used to take its
 * cancellation for an ending and run the rest of its own ending afterwards — which by then
 * was the new send's: "Stopped." on the new reply, the composer open and the screen allowed
 * to sleep while the new answer was still arriving, and an answer to a spoken question that
 * was never read out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendReplacedTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var model: ChatViewModel

    /** A Mac that has started every answer it was asked for and is still writing it. */
    private class Writing : HangingTransport() {
        var asked = 0

        override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            asked++
            emit(ChatStreamEvent.Token("Answer $asked"))
            awaitCancellation()
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        model = ChatViewModel(Application(), ConversationStore(folder.newFolder()))
    }

    @After
    fun tearDown() {
        model.stopForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun `a question asked mid-answer keeps its own reply going`() = runTest {
        val mac = Writing()
        model.draft = "Three days in Lisbon?"
        model.send(mac)
        model.draft = "And in Porto?"
        model.send(mac)

        val replies = model.current!!.messages.filter { it.role == ChatMessage.ROLE_ASSISTANT }
        assertEquals(2, replies.size)
        val (first, second) = replies
        assertTrue("the new question is still being answered", model.isSending)
        assertNotNull(model.sendingSince)
        assertTrue("the new reply is still arriving", second.isStreaming)
        assertNull("the new reply was marked as stopped", second.failure)
        assertEquals("Answer 2", second.content)
        assertFalse("the reply it replaced is over, not left spinning", first.isStreaming)
        assertEquals("Answer 1", first.content)
    }

    @Test
    fun `Stop still stops the answer in flight`() = runTest {
        model.draft = "Three days in Lisbon?"
        model.send(Writing())

        model.cancel()

        val reply = model.current!!.messages.last()
        assertFalse(model.isSending)
        assertFalse(reply.isStreaming)
        assertEquals("Answer 1", reply.content)
    }
}
