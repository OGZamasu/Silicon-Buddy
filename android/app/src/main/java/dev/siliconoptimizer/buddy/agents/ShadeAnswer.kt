package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** A button pressed in the shade, once the intent's extras have been checked. */
data class ShadeAnswer(val engine: String, val id: String, val decision: String) {

    companion object {
        /** The longest approval id taken from an intent. */
        const val MAX_ID = 128

        /**
         * How long a locked phone is given to catch up with its own unlock before the press
         * is refused: the prompt a Decline raises can hand the press over a moment before
         * Android has recorded that the owner answered it.
         */
        const val LOCK_RECHECK_MS = 1_000L

        /** With the recheck, inside the ten seconds a receiver holding `goAsync` is given. */
        const val ANSWER_TIMEOUT_MS = 8_000L

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
 * Whether the phone is locked, from `KeyguardManager.isDeviceLocked`: null when there was no
 * keyguard to ask, which is treated as locked — a press nobody can vouch for sends nothing.
 */
fun lockedNow(isDeviceLocked: Boolean?): Boolean = isDeviceLocked != false

/**
 * Sends a press from the shade to the Mac, once, and says what came of it.
 *
 * Only Decline is ever sent from here. Accept belongs in the app, under the command it
 * allows; one arriving anyway — from a notification an earlier build posted — is answered
 * with where to accept it, and nothing goes to the Mac.
 *
 * A locked phone sends nothing: `setAuthenticationRequired` is enforced by the system's own
 * UI, and a notification listener can send the same intent without it. [isDeviceLocked] is
 * asked again after [recheckMs] before the press is refused, for the phone whose unlock
 * has not been recorded yet. The answer is recorded as this phone's before it is sent — the
 * Mac's frame may beat the reply — and forgotten again if it did not get through.
 * [connect] is only called for a press that will be sent, and answers null when no Mac is
 * paired: a locked phone does not so much as read the token.
 */
suspend fun answerFromShade(
    answer: ShadeAnswer,
    isDeviceLocked: () -> Boolean?,
    connect: () -> ControlTransport?,
    recheckMs: Long = ShadeAnswer.LOCK_RECHECK_MS,
    timeoutMs: Long = ShadeAnswer.ANSWER_TIMEOUT_MS,
): ShadeReply {
    if (answer.decision != AgentApprovalDecision.DECLINE) return ShadeReply(ApprovalReplies.ACCEPT_IN_APP)
    if (lockedNow(isDeviceLocked())) {
        delay(recheckMs)
        if (lockedNow(isDeviceLocked())) return ShadeReply(ApprovalReplies.LOCKED, keepApproval = true)
    }
    val transport = connect() ?: return ShadeReply(ApprovalReplies.NO_MAC)
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
