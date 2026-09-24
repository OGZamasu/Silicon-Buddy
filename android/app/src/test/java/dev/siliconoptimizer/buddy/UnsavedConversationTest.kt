package dev.siliconoptimizer.buddy

import android.app.Application
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.Conversation
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatRequest
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The phone could not write the conversation down: the file is there, and could not be read
 * at that moment. The store writes nothing over it and says so, and the chat says so too —
 * the answer stays on screen, and nothing about it takes the app down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsavedConversationTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var model: ChatViewModel

    /** A Mac with no `/conversations`, so the phone keeps the transcript. */
    private class Answering : HangingTransport() {
        override fun chatStream(request: ChatRequest): Flow<ChatStreamEvent> = flow {
            emit(ChatStreamEvent.Token("Start in Alfama."))
            emit(ChatStreamEvent.Finished(ChatMetrics(3, 3, 30.0)))
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        directory = folder.newFolder()
        file = File(directory, "conversations.json")
        val store = ConversationStore(directory)
        runBlocking { store.save(Conversation(id = "kept", title = "Kept")) }
        model = ChatViewModel(Application(), store)
    }

    @After
    fun tearDown() {
        file.setReadable(true, false)
        model.stopForTest()
        Dispatchers.resetMain()
    }

    @Test
    fun `an answer the phone cannot save is kept on screen and said`() {
        val before = file.readText()
        assertTrue(file.setReadable(false, false))

        model.draft = "Three days in Lisbon?"
        model.send(Answering())
        val deadline = System.currentTimeMillis() + 10_000
        while (model.error == null || model.isSending) {
            if (System.currentTimeMillis() > deadline) fail("the chat never said the answer was not saved")
            Thread.sleep(10)
        }

        assertEquals(ChatViewModel.NOT_SAVED, model.error)
        assertEquals("Start in Alfama.", model.current?.messages?.last()?.content)
        file.setReadable(true, false)
        assertEquals("nothing was written over the file", before, file.readText())
        assertEquals(listOf(file.name), directory.list().orEmpty().toList())
    }
}
