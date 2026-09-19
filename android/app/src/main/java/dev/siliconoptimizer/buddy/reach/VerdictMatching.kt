package dev.siliconoptimizer.buddy.reach

import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.transport.Verdict

/**
 * Which reply an answer check belongs to.
 *
 * Its own function because the rule is the whole of the behaviour and the view model
 * that used to hold it needs an Android `Application` to exist at all — so the rule
 * could only be tested by writing it out a second time, which tests the copy.
 */
object VerdictMatching {

    /**
     * The index of the message [verdict] is about, or -1 when it is about none of them.
     *
     * A check that names a message id is matched on that id alone. One that names an id
     * this transcript does not have is dropped rather than guessed at: it is about a
     * message this screen does not hold — an older one, or a reply in a conversation
     * reopened since — and stamping it on the newest reply would put the Mac's words
     * under an answer it never read.
     *
     * Only an unnamed check falls back to the newest finished reply. That is right
     * whenever the Mac checks a reply as it finishes, and it is the only thing a
     * transcript this device kept can do, because its ids are its own and the Mac has
     * never seen them.
     */
    fun index(verdict: Verdict, messages: List<ChatMessage>): Int {
        val named = verdict.messageID?.takeIf { it.isNotEmpty() }
        if (named != null) return messages.indexOfFirst { it.id == named }
        return messages.indexOfLast {
            it.role == ChatMessage.ROLE_ASSISTANT && !it.isStreaming && it.content.isNotEmpty()
        }
    }

    /** True when a check belongs to this conversation at all. */
    fun belongs(verdict: Verdict, conversationID: String): Boolean =
        verdict.conversationID.isNullOrEmpty() || verdict.conversationID == conversationID
}
