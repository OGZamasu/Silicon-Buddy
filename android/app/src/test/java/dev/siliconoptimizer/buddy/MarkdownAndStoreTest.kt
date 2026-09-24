package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.Conversation
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.chat.Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MarkdownTest {

    @Test
    fun `a plain paragraph`() {
        assertEquals(listOf(Markdown.Block.Paragraph("Hello there.")), Markdown.blocks("Hello there."))
    }

    @Test
    fun `blank lines separate paragraphs`() {
        assertEquals(
            listOf(Markdown.Block.Paragraph("One."), Markdown.Block.Paragraph("Two.")),
            Markdown.blocks("One.\n\nTwo."),
        )
    }

    @Test
    fun headings() {
        assertEquals(
            listOf(
                Markdown.Block.Heading(1, "Title"),
                Markdown.Block.Heading(2, "Subtitle"),
                Markdown.Block.Paragraph("Body"),
            ),
            Markdown.blocks("# Title\n## Subtitle\nBody"),
        )
    }

    @Test
    fun `a hash without a space is not a heading`() {
        assertEquals(listOf(Markdown.Block.Paragraph("#hashtag")), Markdown.blocks("#hashtag"))
    }

    @Test
    fun `bullets group into one list`() {
        assertEquals(
            listOf(Markdown.Block.Bullets(listOf("one", "two", "three"))),
            Markdown.blocks("- one\n- two\n* three"),
        )
    }

    @Test
    fun `numbered lists`() {
        assertEquals(
            listOf(Markdown.Block.Numbered(listOf("first", "second"))),
            Markdown.blocks("1. first\n2. second"),
        )
    }

    @Test
    fun `fenced code keeps its indentation and language`() {
        val source = "Try this:\n\n```kotlin\nval x = 1\n    val y = 2\n```\n\nDone."
        assertEquals(
            listOf(
                Markdown.Block.Paragraph("Try this:"),
                Markdown.Block.Code("kotlin", "val x = 1\n    val y = 2"),
                Markdown.Block.Paragraph("Done."),
            ),
            Markdown.blocks(source),
        )
    }

    @Test
    fun `an unclosed fence is still code`() {
        // Exactly what a half-streamed answer looks like.
        assertEquals(
            listOf(Markdown.Block.Code("python", "print(1)")),
            Markdown.blocks("```python\nprint(1)"),
        )
    }

    @Test
    fun `markers inside a code block are not parsed`() {
        assertEquals(
            listOf(Markdown.Block.Code(null, "# not a heading\n- not a bullet")),
            Markdown.blocks("```\n# not a heading\n- not a bullet\n```"),
        )
    }

    @Test
    fun `quotes and rules`() {
        assertEquals(
            listOf(
                Markdown.Block.Quote("quoted"),
                Markdown.Block.Rule,
                Markdown.Block.Paragraph("text"),
            ),
            Markdown.blocks("> quoted\n\n---\n\ntext"),
        )
    }

    @Test
    fun `empty input is no blocks`() {
        assertTrue(Markdown.blocks("").isEmpty())
        assertTrue(Markdown.blocks("\n\n").isEmpty())
    }

    @Test
    fun `inline spans split bold, italic and code`() {
        assertEquals(
            listOf(
                Markdown.Span.Bold("bold"),
                Markdown.Span.Plain(" and "),
                Markdown.Span.Code("code"),
            ),
            Markdown.spans("**bold** and `code`"),
        )
    }

    @Test
    fun `unclosed inline markup stays literal`() {
        assertEquals(listOf(Markdown.Span.Plain("unclosed **bold")), Markdown.spans("unclosed **bold"))
    }

    @Test
    fun `a realistic answer splits into the right blocks`() {
        val answer = "Here's how.\n\n1. Open the file\n2. Change the line\n\n" +
            "```bash\n./gradlew assembleDebug\n```\n\n> Careful: this rebuilds everything."
        val kinds = Markdown.blocks(answer).map { it::class.simpleName }
        assertEquals(listOf("Paragraph", "Numbered", "Code", "Quote"), kinds)
    }
}

class ConversationStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `an empty store has nothing in it`() = runBlocking {
        assertTrue(ConversationStore(folder.newFolder()).all().isEmpty())
    }

    @Test
    fun `saving and reading back`() = runBlocking {
        val directory = folder.newFolder()
        val store = ConversationStore(directory)
        store.save(
            Conversation(
                title = "Kept",
                messages = listOf(
                    ChatMessage(role = ChatMessage.ROLE_USER, content = "hello"),
                    ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "hi"),
                ),
            ),
        )
        val reopened = ConversationStore(directory).all()
        assertEquals(1, reopened.size)
        assertEquals(2, reopened.first().messages.size)
        assertEquals("Kept", reopened.first().title)
    }

    @Test
    fun `saving twice updates rather than duplicates`() = runBlocking {
        val store = ConversationStore(folder.newFolder())
        var conversation = Conversation(title = "One")
        store.save(conversation)
        conversation = conversation.copy(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "added")),
        )
        store.save(conversation)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(1, all.first().messages.size)
    }

    @Test
    fun `the first message becomes the title`() = runBlocking {
        val store = ConversationStore(folder.newFolder())
        val saved = store.save(
            Conversation(
                messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "What is a tensor?")),
            ),
        )
        assertEquals("What is a tensor?", saved.title)
    }

    @Test
    fun `a long first message is truncated into a title`() {
        val conversation = Conversation(
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "a".repeat(100))),
        )
        val titled = conversation.titledFromFirstMessage()
        assertEquals(49, titled.title.length)
        assertTrue(titled.title.endsWith("…"))
    }

    @Test
    fun `an explicit title is kept`() {
        val conversation = Conversation(
            title = "Mine",
            messages = listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "anything")),
        )
        assertEquals("Mine", conversation.titledFromFirstMessage().title)
    }

    @Test
    fun deleting() = runBlocking {
        val store = ConversationStore(folder.newFolder())
        val conversation = Conversation(title = "Temporary")
        store.save(conversation)
        store.delete(conversation.id)
        assertTrue(store.all().isEmpty())
    }

    /**
     * A file this store cannot read is somebody's conversations all the same. Read as "none",
     * the next save used to write the one conversation it had over all of them.
     */
    @Test
    fun `a file that cannot be read is set aside, not written over`() = runBlocking {
        val directory = folder.newFolder()
        val file = File(directory, "conversations.json")
        val torn = """[{"id":"a","title":"Lisbon","messages":[{"role":"user","content":"Three da"""
        file.writeText(torn)
        val store = ConversationStore(directory)
        assertTrue(store.all().isEmpty())

        store.save(Conversation(title = "New"))

        val aside = directory.listFiles().orEmpty().filter { it.name != file.name }
        assertEquals("the unreadable file is kept, beside the new one", 1, aside.size)
        assertEquals(torn, aside.single().readText())
        assertEquals(listOf("New"), store.all().map { it.title })
    }

    @Test
    fun `deleting from a file that cannot be read leaves it as it was`() = runBlocking {
        val directory = folder.newFolder()
        val file = File(directory, "conversations.json")
        file.writeText("not json")

        ConversationStore(directory).delete("a")

        assertEquals("not json", file.readText())
    }

    /**
     * A damaged file, then a burst of saves and deletes from several stores at once. The
     * damaged bytes survive in exactly one file set aside, and what is written is never an
     * empty list.
     */
    @Test
    fun `a damaged file under a burst of saves is set aside once and nothing is lost`() = runBlocking {
        val directory = folder.newFolder()
        val torn = """[{"id":"a","title":"Lisbon","messages":[{"role":"user","content":"Three da"""
        File(directory, "conversations.json").writeText(torn)
        val stores = List(3) { ConversationStore(directory) }
        (1..60).map { index ->
            async(Dispatchers.IO) {
                if (index % 3 == 0) {
                    stores[index % 3].delete("x$index")
                } else {
                    stores[index % 3].save(Conversation(id = "x$index", title = "N$index"))
                }
            }
        }.awaitAll()

        val aside = directory.listFiles().orEmpty().filter { it.name.startsWith("conversations.unreadable-") }
        assertEquals(1, aside.size)
        assertEquals(torn, aside.single().readText())
        assertEquals(40, ConversationStore(directory).all().size)
    }

    /**
     * A file that decodes perfectly well but cannot be read at this moment — an I/O error, too
     * many files open, a file of pictures too large for the memory there is. That says nothing
     * about what is in it, so it is not set aside as though it were damaged: nothing is written,
     * the save says it failed, and the file stays where the store reads it.
     */
    @Test
    fun `a file that cannot be read for a moment is left as it is`() = runBlocking {
        val directory = folder.newFolder()
        val store = ConversationStore(directory)
        listOf("a", "b", "c").forEach { store.save(Conversation(id = it, title = it.uppercase())) }
        val file = File(directory, "conversations.json")
        val before = file.readText()

        assertTrue(file.setReadable(false, false))
        val saving = try {
            runCatching { store.save(Conversation(id = "d", title = "D")) }.exceptionOrNull()
        } finally {
            file.setReadable(true, false)
        }

        assertTrue("the save says it failed, not that it saved: $saving", saving is IOException)
        assertEquals("nothing was written over the file", before, file.readText())
        assertEquals("and nothing was set aside", listOf(file.name), directory.list().orEmpty().toList())
        // Readable again, the same save keeps all of them.
        store.save(Conversation(id = "d", title = "D"))
        assertEquals(setOf("a", "b", "c", "d"), store.all().map { it.id }.toSet())
    }

    @Test
    fun `a delete from a file that cannot be read for a moment writes nothing`() = runBlocking {
        val directory = folder.newFolder()
        val store = ConversationStore(directory)
        listOf("a", "b").forEach { store.save(Conversation(id = it, title = it.uppercase())) }
        val file = File(directory, "conversations.json")
        val before = file.readText()

        assertTrue(file.setReadable(false, false))
        val deleting = try {
            runCatching { store.delete("a") }.exceptionOrNull()
        } finally {
            file.setReadable(true, false)
        }

        assertTrue("the delete says it failed: $deleting", deleting is IOException)
        assertEquals(before, file.readText())
    }

    /**
     * Saves arrive together — Stop saves the reply it cut short while the next question is
     * being saved — and each one is a read, a change and a write. Two of them interleaved
     * used to lose one, whichever store instance made them.
     */
    @Test
    fun `saves made at the same time keep every conversation`() = runBlocking {
        val directory = folder.newFolder()
        val stores = listOf(ConversationStore(directory), ConversationStore(directory))
        (1..48).map { index ->
            async(Dispatchers.IO) {
                stores[index % 2].save(Conversation(id = "c$index", title = "Conversation $index"))
            }
        }.awaitAll()

        assertEquals(48, ConversationStore(directory).all().size)
    }

    /**
     * What is on disk is always a whole file: the old one or the new one, never the first
     * half of the new one. Read while a save was writing, it used to be half, and half a
     * file decodes as nothing at all.
     */
    @Test
    fun `the file on disk is never half written`() = runBlocking {
        val directory = folder.newFolder()
        val file = File(directory, "conversations.json")
        val store = ConversationStore(directory)
        val long = ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "Alfama. ".repeat(40_000))
        var conversation = store.save(Conversation(id = "c", messages = listOf(long)))
        val decoder = Json { ignoreUnknownKeys = true }
        val torn = java.util.concurrent.atomic.AtomicInteger()
        val writing = java.util.concurrent.atomic.AtomicBoolean(true)
        val reader = kotlin.concurrent.thread {
            while (writing.get()) {
                val text = runCatching { file.readText() }.getOrNull() ?: continue
                if (runCatching { decoder.decodeFromString<List<Conversation>>(text) }.isFailure) {
                    torn.incrementAndGet()
                }
            }
        }
        repeat(60) { conversation = store.save(conversation.copy(title = "Take $it")) }
        writing.set(false)
        reader.join()

        assertEquals("reads that found half a file", 0, torn.get())
    }

    @Test
    fun `the count ignores system messages`() {
        val conversation = Conversation(
            messages = listOf(
                ChatMessage(role = ChatMessage.ROLE_SYSTEM, content = "you are helpful"),
                ChatMessage(role = ChatMessage.ROLE_USER, content = "hi"),
                ChatMessage(role = ChatMessage.ROLE_ASSISTANT, content = "hello"),
            ),
        )
        assertEquals(2, conversation.messageCount)
    }

    @Test
    fun `a model that spends its whole budget thinking says so`() {
        assertNotNull(ChatViewModel.truncationNote(content = "", generatedTokens = 2048, limit = 2048))
        assertNull(ChatViewModel.truncationNote(content = "An answer.", generatedTokens = 2048, limit = 2048))
        assertNull(ChatViewModel.truncationNote(content = "", generatedTokens = 12, limit = 2048))
    }
}
