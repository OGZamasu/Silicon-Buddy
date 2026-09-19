package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary

/**
 * Which models a session may be sent with, and which one a send carries.
 *
 * The Mac is strict about this, and rightly: `model` must be one of the session's
 * `modelChoices`, and anything else is a 400 rather than a turn quietly answered by a
 * different model than the one on screen. So the picker offers exactly that list — never
 * a model this phone remembers from somewhere else, never the Mac's loaded model because
 * it happens to be loaded — and a choice that has fallen off the list is not sent.
 *
 * The choice sticks on the Mac: a send with a model makes it that engine's picker choice
 * there too. So what the picker shows is the session's model, not a one-turn override.
 */
object ModelPicker {

    /** What the picker may offer: the session's own list, once each, in the Mac's order. */
    fun choices(summary: AgentSessionSummary?): List<AgentModelChoice> =
        summary?.modelChoices.orEmpty().distinctBy { it.id }

    /**
     * What the picker shows as selected: the one the person picked, while it is still on
     * the list, or else the session's own model when that is on the list.
     */
    fun selected(summary: AgentSessionSummary?, picked: String?): AgentModelChoice? {
        val offered = choices(summary)
        return offered.firstOrNull { it.id == picked }
            ?: offered.firstOrNull { it.id == summary?.model }
    }

    /**
     * What goes in the `model` field of a send: the picked model when it is on the list
     * and is not already the session's, or nothing — the Mac then uses the session's own,
     * which is the one on screen.
     */
    fun modelToSend(summary: AgentSessionSummary?, picked: String?): String? {
        if (picked == null || picked == summary?.model) return null
        return picked.takeIf { id -> choices(summary).any { it.id == id } }
    }

    /**
     * The line under the composer. When the session's model is not one it could be sent
     * with — the Mac lost a peer, say — that is worth saying rather than pretending.
     */
    fun caption(summary: AgentSessionSummary?, picked: String?): String? {
        summary ?: return null
        val chosen = selected(summary, picked)
        return when {
            chosen != null -> "${chosen.label} · ${chosen.where}"
            summary.model.isNotBlank() -> "${summary.model} · not on this Mac's list right now"
            else -> null
        }
    }
}
