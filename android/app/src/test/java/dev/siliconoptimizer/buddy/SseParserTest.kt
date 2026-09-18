package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.SseEvent
import dev.siliconoptimizer.buddy.transport.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    private fun events(stream: String): List<SseEvent> {
        val parser = SseParser()
        val found = mutableListOf<SseEvent>()
        stream.split("\n").forEach { line -> parser.consume(line)?.let { found.add(it) } }
        parser.finish()?.let { found.add(it) }
        return found
    }

    @Test
    fun `a named event with data`() {
        assertEquals(listOf(SseEvent("token", "Hello")), events("event: token\ndata: Hello\n\n"))
    }

    @Test
    fun `an unnamed event defaults to message`() {
        val found = events("data: plain\n\n")
        assertEquals("message", found.first().name)
        assertEquals("plain", found.first().data)
    }

    @Test
    fun `multiple data lines join with a newline`() {
        assertEquals("first\nsecond", events("event: token\ndata: first\ndata: second\n\n").first().data)
    }

    @Test
    fun `comments are ignored but noticed`() {
        val parser = SseParser()
        assertNull(parser.consume(": heartbeat"))
        assertTrue(parser.sawComment)
        assertNull(parser.consume("data: after"))
        assertEquals("after", parser.consume("")?.data)
    }

    @Test
    fun `only one leading space is stripped`() {
        assertEquals(" two spaces", events("data:  two spaces\n\n").first().data)
    }

    @Test
    fun `carriage returns are stripped`() {
        assertEquals(
            listOf(SseEvent("token", "windows")),
            events("event: token\r\ndata: windows\r\n\r\n"),
        )
    }

    @Test
    fun `a block without data dispatches nothing`() {
        assertTrue(events("event: token\n\n").isEmpty())
    }

    @Test
    fun `id and retry are carried`() {
        val event = events("id: 7\nretry: 2500\ndata: x\n\n").first()
        assertEquals("7", event.id)
        assertEquals(2500, event.retry)
    }

    @Test
    fun `an event name does not leak into the next event`() {
        val found = events("event: reasoning\ndata: one\n\ndata: two\n\n")
        assertEquals(listOf("reasoning", "message"), found.map { it.name })
    }

    @Test
    fun `chunked delivery splits mid-line`() {
        val parser = SseParser()
        val found = mutableListOf<SseEvent>()
        found += parser.consumeChunk("event: tok")
        found += parser.consumeChunk("en\ndata: Hel")
        found += parser.consumeChunk("lo\n\nevent: finished\ndata: {\"promptTokens\":1}\n\n")
        assertEquals(2, found.size)
        assertEquals(SseEvent("token", "Hello"), found.first())
        assertEquals("finished", found.last().name)
    }

    @Test
    fun `a stream that ends without a blank line still dispatches`() {
        val parser = SseParser()
        parser.consumeChunk("event: token\ndata: cut off")
        assertEquals(SseEvent("token", "cut off"), parser.finish())
    }

    @Test
    fun `unknown fields are ignored`() {
        assertEquals("fine", events("weird: value\ndata: fine\n\n").first().data)
    }

    @Test
    fun `a realistic chat stream`() {
        val stream = """
            : open

            event: reasoning
            data: The user wants a greeting.

            event: token
            data: Hello

            event: token
            data: , world

            event: finished
            data: {"promptTokens":9,"generatedTokens":3,"tokensPerSecond":18.2}


        """.trimIndent()
        val found = events(stream)
        assertEquals(listOf("reasoning", "token", "token", "finished"), found.map { it.name })
        assertEquals("Hello, world", found[1].data + found[2].data)
    }
}
