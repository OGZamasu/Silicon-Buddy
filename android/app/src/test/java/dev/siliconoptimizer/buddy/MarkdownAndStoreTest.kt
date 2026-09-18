package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.chat.ChatViewModel
import dev.siliconoptimizer.buddy.chat.Conversation
import dev.siliconoptimizer.buddy.chat.ConversationStore
import dev.siliconoptimizer.buddy.chat.Markdown
import kotlinx.coroutines.runBlocking
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
