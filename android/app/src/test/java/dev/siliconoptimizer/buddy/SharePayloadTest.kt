package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.chat.SendLimits
import dev.siliconoptimizer.buddy.reach.SharePayload
import dev.siliconoptimizer.buddy.reach.ShareNormaliser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the share target does with whatever another app handed it. */
class SharePayloadTest {

    /** A data URL whose decoded size is about [bytes]. */
    private fun picture(bytes: Int): SharePayload.Item.Picture =
        SharePayload.Item.Picture("data:image/jpeg;base64," + "A".repeat(bytes * 4 / 3))

    // MARK: - The prefilled question

    @Test
    fun `text alone asks for a summary`() {
        val draft = ShareNormaliser.draft(SharePayload(listOf(SharePayload.Item.Text("An article."))))
        assertEquals("Summarise this", draft.prompt)
        assertEquals("An article.", draft.quoted)
        assertTrue(draft.images.isEmpty())
    }

    @Test
    fun `a link alone asks for a summary and names the site`() {
        val draft = ShareNormaliser.draft(
            SharePayload(listOf(SharePayload.Item.Link("https://example.com/piece"))),
        )
        assertEquals("Summarise this", draft.prompt)
        assertEquals("https://example.com/piece", draft.quoted)
        assertEquals("Shared: example.com", draft.summary)
    }

    @Test
    fun `a picture alone asks what it is`() {
        val draft = ShareNormaliser.draft(SharePayload(listOf(picture(900))))
        assertEquals("What is this?", draft.prompt)
        assertEquals(1, draft.images.size)
        assertTrue(draft.quoted.isEmpty())
    }

    @Test
    fun `a picture with text asks about both`() {
        val draft = ShareNormaliser.draft(
            SharePayload(listOf(picture(100), SharePayload.Item.Text("The caption"))),
        )
        assertEquals("What is this, and what does the text say about it?", draft.prompt)
        assertEquals("The caption", draft.quoted)
    }

    // MARK: - Tidying up what arrived

    /**
     * Chrome sends the page's URL as a link and as text. Quoting it twice would waste
     * the model's context on a duplicate.
     */
    @Test
    fun `a link that also arrived as text is quoted once`() {
        val draft = ShareNormaliser.draft(
            SharePayload(
                listOf(
                    SharePayload.Item.Link("https://example.com/a"),
                    SharePayload.Item.Text("https://example.com/a"),
                ),
            ),
        )
        assertEquals("https://example.com/a", draft.quoted)
    }

    @Test
    fun `empty and whitespace text is dropped`() {
        val draft = ShareNormaliser.draft(SharePayload(listOf(SharePayload.Item.Text("   \n  "))))
        assertTrue(draft.isEmpty)
        assertEquals("Nothing to send", draft.summary)
        assertEquals("", draft.prompt)
    }

    // MARK: - The Mac's caps

    @Test
    fun `more pictures than the Mac takes are dropped and said so`() {
        val draft = ShareNormaliser.draft(SharePayload(List(12) { picture(100) }))
        assertEquals(SendLimits.MAX_ATTACHMENTS, draft.images.size)
        assertNotNull(draft.note)
        assertTrue(draft.note!!.contains("8"))
    }

    @Test
    fun `a picture over the size cap is dropped rather than refused on arrival`() {
        val draft = ShareNormaliser.draft(
            SharePayload(listOf(picture(SendLimits.MAX_IMAGE_BYTES + 50_000), picture(500))),
        )
        assertEquals(1, draft.images.size)
        assertEquals("One picture was too large to send even after shrinking.", draft.note)
    }

    /**
     * Eight pictures each inside the per-image cap can still be three times the body
     * the Mac accepts. Select several in Photos and share is exactly that shape.
     */
    @Test
    fun `pictures are dropped until the whole message fits`() {
        val draft = ShareNormaliser.draft(SharePayload(List(8) { picture(1_400_000) }))
        assertTrue(draft.images.size < 8)
        assertTrue(draft.note!!.contains("4 MB"))
        assertNull(SendLimits.problem(draft.images, draft.message))
    }

    @Test
    fun `a whole web page is cut to something a model can read`() {
        val long = "word ".repeat(5_000)
        val draft = ShareNormaliser.draft(SharePayload(listOf(SharePayload.Item.Text(long))))
        assertEquals(ShareNormaliser.MAX_QUOTED_CHARACTERS, draft.quoted.length)
        assertTrue(draft.note!!.contains("cut"))
    }

    // MARK: - The message that actually goes

    @Test
    fun `the message is the question then the material`() {
        val draft = ShareNormaliser
            .draft(SharePayload(listOf(SharePayload.Item.Text("Body"))))
            .copy(prompt = "In one line")
        assertEquals("In one line\n\nBody", draft.message)
    }

    @Test
    fun `a picture-only message is just the question`() {
        val draft = ShareNormaliser.draft(SharePayload(listOf(picture(10))))
        assertEquals("What is this?", draft.message)
    }

    @Test
    fun `the header names what arrived`() {
        val draft = ShareNormaliser.draft(
            SharePayload(
                listOf(
                    picture(10),
                    SharePayload.Item.Link("https://example.com/x"),
                    SharePayload.Item.Text("note"),
                ),
            ),
        )
        assertEquals("Shared: a picture, example.com and some text", draft.summary)
    }

    @Test
    fun `the host is read without android's Uri, which a unit test does not have`() {
        assertEquals("example.com", ShareNormaliser.hostOf("https://example.com/a?b=c"))
        assertEquals("example.com", ShareNormaliser.hostOf("http://user@example.com"))
        assertNull(ShareNormaliser.hostOf(""))
    }
}
