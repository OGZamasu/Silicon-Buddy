package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentEngines

/**
 * The approvals this phone has answered, wherever it answered them — the session screen, or
 * a notification's button while the app was in the background.
 *
 * Recorded *before* the answer is sent, because the Mac's frame saying the card was answered
 * can arrive before the reply to this phone's own request, and a card this phone answered
 * must not come down reading "Accepted on the Mac". Process-wide rather than on a screen,
 * because the notification's receiver and the Agents tab are different parts of the same
 * process. In memory and bounded: this is a record of the last few minutes, not a history.
 */
object AgentAnswers {

    /** How many answers are remembered. */
    const val REMEMBERED = 64

    private val answers = LinkedHashMap<String, String>()

    /** "accept" and "accepted" are the same decision; the Mac says the latter. */
    fun decided(decision: String): String = when (decision) {
        "accept", "accepted" -> "accepted"
        "decline", "declined" -> "declined"
        else -> decision
    }

    @Synchronized
    fun note(engine: String, id: String, decision: String) {
        answers.remove(key(engine, id))
        answers[key(engine, id)] = decided(decision)
        while (answers.size > REMEMBERED) answers.remove(answers.keys.first())
    }

    /** This phone's answers in one engine, by approval id. */
    @Synchronized
    fun of(engine: String): Map<String, String> {
        val prefix = "$engine:"
        return answers.filterKeys { it.startsWith(prefix) }.mapKeys { it.key.removePrefix(prefix) }
    }

    /** The answer did not get through: it is not this phone's after all. */
    @Synchronized
    fun forget(engine: String, id: String) {
        answers.remove(key(engine, id))
    }

    @Synchronized
    fun forget() = answers.clear()

    private fun key(engine: String, id: String) = "$engine:$id"
}

/** The engines this build knows. Anything else in an intent is refused, not trusted. */
fun knownEngine(engine: String?): String? =
    engine?.takeIf { it == AgentEngines.CODEX || it == AgentEngines.PI }
