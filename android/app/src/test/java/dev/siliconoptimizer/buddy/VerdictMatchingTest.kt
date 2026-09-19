package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.reach.VerdictMatching
import dev.siliconoptimizer.buddy.transport.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which reply an answer check is allowed to decorate. */
class VerdictMatchingTest {

    private val transcript = listOf(
        ChatMessage(id = "m1", role = ChatMessage.ROLE_USER, content = "first question"),
        ChatMessage(id = "m2", role = ChatMessage.ROLE_ASSISTANT, content = "first answer"),
        ChatMessage(id = "m3", role = ChatMessage.ROLE_USER, content = "second question"),
        ChatMessage(id = "m4", role = ChatMessage.ROLE_ASSISTANT, content = "second answer"),
    )

    private fun verdict(messageID: String? = null, conversationID: String? = null) =
        Verdict(
            conversationID = conversationID,
            messageID = messageID,
            verdict = "annotate",
        )

    @Test
    fun `a named message id lands on exactly that message`() {
        assertEquals(1, VerdictMatching.index(verdict(messageID = "m2"), transcript))
    }

    /**
     * The one that matters. A check naming a message this screen does not hold used to
     * be stamped on the newest reply instead, which puts the Mac's words under an
     * answer it never read.
     */
    @Test
    fun `a named message id that matches nothing is dropped, not moved`() {
        assertEquals(-1, VerdictMatching.index(verdict(messageID = "somewhere-else"), transcript))
    }

    @Test
    fun `an unnamed check falls back to the newest finished reply`() {
        assertEquals(3, VerdictMatching.index(verdict(), transcript))
    }

    @Test
    fun `an empty message id counts as unnamed`() {
        assertEquals(3, VerdictMatching.index(verdict(messageID = ""), transcript))
    }

    @Test
    fun `a reply still streaming is not the newest finished one`() {
        val streaming = transcript + ChatMessage(
            id = "m5", role = ChatMessage.ROLE_ASSISTANT, content = "", isStreaming = true,
        )
        assertEquals(3, VerdictMatching.index(verdict(), streaming))
    }

    @Test
    fun `nothing to decorate is not a match`() {
        assertEquals(-1, VerdictMatching.index(verdict(), emptyList()))
    }

    @Test
    fun `a check for another conversation does not belong here`() {
        assertFalse(VerdictMatching.belongs(verdict(conversationID = "C9"), "C1"))
        assertTrue(VerdictMatching.belongs(verdict(conversationID = "C1"), "C1"))
        assertTrue(VerdictMatching.belongs(verdict(), "C1"))
    }
}
