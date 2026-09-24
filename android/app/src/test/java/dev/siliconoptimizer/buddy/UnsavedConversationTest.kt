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

    private fun awaitAnswer(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("never: $what")
            Thread.sleep(10)
        }
    }

    private fun sendUnsaved() {
        assertTrue(file.setReadable(false, false))
        model.draft = "Three days in Lisbon?"
        model.send(Answering())
        awaitAnswer("the chat said the answer was not saved") { model.error != null && !model.isSending }
        assertEquals(ChatViewModel.NOT_SAVED, model.error)
        file.setReadable(true, false)
    }

    /**
     * A turn the phone could not write down lives only in memory, and Android may end the
     * process at any time after the app leaves the screen — with the turn in it. So leaving
     * the screen tries the save again, and a save that works takes the message away.
     */
    @Test
    fun `a turn the phone could not save is saved when the app leaves the screen`() {
        sendUnsaved()
        val id = model.current!!.id

        model.saveUnsaved()
        awaitAnswer("the turn reached the file") { file.readText().contains("Start in Alfama.") }

        val stored = runBlocking { ConversationStore(directory).conversation(id) }
        assertEquals(listOf("Three days in Lisbon?", "Start in Alfama."), stored?.messages?.map { it.content })
        awaitAnswer("the message went") { model.error == null }
    }

    /**
     * A conversation owed a save, deleted before the app leaves the screen. What is owed is
     * the owner's words as they were — and the owner has since thrown them away: the retry
     * as the app leaves must not write them back.
     */
    @Test
    fun `a deleted conversation is not written back by the retry`() {
        sendUnsaved()
        val id = model.current!!.id
        model.delete(id)
        awaitAnswer("the delete took it off the screen") { model.current == null }

        model.saveUnsaved()
        Thread.sleep(1_000)

        val stored = runBlocking { ConversationStore(directory).conversation(id) }
        assertEquals("the retry wrote the deleted conversation back", null, stored?.id)
        assertTrue("the retry put the deleted conversation back on the list", model.conversations.none { it.id == id })
    }
}
