package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Accept or Decline pressed in the shade, once the intent's extras have been checked. */
data class ShadeAnswer(val engine: String, val id: String, val decision: String) {

    companion object {
        /** The longest approval id taken from an intent. */
        const val MAX_ID = 128

        /** Inside the ten seconds a receiver holding `goAsync` is given. */
        const val ANSWER_TIMEOUT_MS = 9_000L

        /** Null for anything this build did not put on a button. */
        fun from(engine: String?, id: String?, decision: String?): ShadeAnswer? {
            val known = knownEngine(engine) ?: return null
            val approval = id?.takeIf { it.isNotBlank() && it.length <= MAX_ID } ?: return null
            val decided = decision
                ?.takeIf { it == AgentApprovalDecision.ACCEPT || it == AgentApprovalDecision.DECLINE }
                ?: return null
            return ShadeAnswer(known, approval, decided)
        }
    }
}

/**
 * What the shade says after a press: one of [ApprovalReplies]' fixed sentences, or null for
 * a card that is simply gone — its notification comes down and nothing is said.
 * [keepApproval] leaves the approval's own notification, buttons and all, for later.
 */
data class ShadeReply(val text: String?, val keepApproval: Boolean = false)

/**
 * Sends a press from the shade to the Mac, once, and says what came of it.
 *
 * A locked phone sends nothing: `setAuthenticationRequired` is enforced by the system's own
 * UI, and a notification listener can send the same intent without it. The answer is
 * recorded as this phone's before it is sent — the Mac's frame may beat the reply — and
 * forgotten again if it did not get through. [transport] is null when no Mac is paired.
 */
suspend fun answerFromShade(
    answer: ShadeAnswer,
    locked: Boolean,
    transport: ControlTransport?,
    timeoutMs: Long = ShadeAnswer.ANSWER_TIMEOUT_MS,
): ShadeReply {
    if (locked) return ShadeReply(ApprovalReplies.LOCKED, keepApproval = true)
    if (transport == null) return ShadeReply(ApprovalReplies.NO_MAC)
    AgentAnswers.note(answer.engine, answer.id, answer.decision)
    return try {
        val result = withTimeout(timeoutMs) {
            transport.answerAgentApproval(answer.engine, answer.id, answer.decision)
        }
        ShadeReply(ApprovalReplies.forDecision(result.decision))
    } catch (error: TransportError) {
        AgentAnswers.forget(answer.engine, answer.id)
        ShadeReply(ApprovalReplies.forError(error))
    } catch (timeout: TimeoutCancellationException) {
        AgentAnswers.forget(answer.engine, answer.id)
        ShadeReply(ApprovalReplies.TOO_SLOW)
    }
}
