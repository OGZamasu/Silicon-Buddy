package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentEngines
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary

/**
 * The words the Agents screens and their notifications share, so a card and the
 * notification about it never describe the same call two different ways.
 */
object AgentNotices {

    /** What a 409 on an approval most often means, when the Mac's sentence is missing. */
    const val ANSWERED_ON_THE_MAC = "Answered on the Mac."

    /** The one line a chat-only device is told about the Agents tab it cannot see. */
    const val CHAT_SCOPE =
        "Agent sessions need full control: every one of them runs commands on the Mac."

    fun engine(engine: String): String = AgentEngines.displayName(engine)

    /** "wants to run a command" — the thing being asked, as a person would put it. */
    fun asking(kind: String): String = when (kind) {
        "command" -> "wants to run a command"
        "fileChange" -> "wants to change files"
        "tool" -> "wants to use a tool"
        else -> "is waiting for you"
    }

    /** "Codex wants to run a command". */
    fun headline(engine: String, approval: AgentApproval): String =
        "${engine(engine)} ${asking(approval.kind)}"

    /** A session's state, in words. The Mac's own word when it is one this build does not know. */
    fun state(summary: AgentSessionSummary?): String = when (summary?.state) {
        null -> "Not asked yet"
        AgentSessionSummary.STATE_RUNNING -> if (summary.turnActive) "Working" else "Ready"
        AgentSessionSummary.STATE_STARTING -> "Starting"
        AgentSessionSummary.STATE_STOPPED -> "Stopped"
        AgentSessionSummary.STATE_FAILED -> "Failed"
        else -> summary.state.replaceFirstChar { it.uppercase() }
    }

    /** The sandbox the thread runs in, as a label; Pi's `none` said plainly. */
    fun sandbox(sandbox: String): String = when (sandbox) {
        AgentSessionSummary.SANDBOX_NONE -> "no sandbox"
        else -> "sandbox: $sandbox"
    }

    /** The guardrail's verdict word, as the pill on a card. */
    fun verdict(verdict: String): String = when (verdict) {
        "act" -> "Screened: safe"
        "confirm" -> "Screened: asks you"
        "block" -> "Screened: would block"
        "unavailable" -> "Not screened"
        else -> "Screened: $verdict"
    }

    /**
     * What the Mac says about asking, as a line on the session. Only `unattended` is a
     * warning; the others are said plainly, and a word this build does not know gets no
     * line at all rather than a guess.
     */
    fun approvalMode(summary: AgentSessionSummary?): String? = when (summary?.approvalMode) {
        AgentSessionSummary.APPROVALS_UNATTENDED ->
            "Runs without asking. The Mac is set so that ${engine(summary.engine)} acts " +
                "without approval — nothing will wait for you here."
        AgentSessionSummary.APPROVALS_SCREENED ->
            "The Mac's guardrail answers what it is sure about and asks you the rest."
        // Also what the Mac says when its guardrail is on but cannot judge — no key, or
        // the month's budget spent — because then every call reaches a person unscreened.
        AgentSessionSummary.APPROVALS_ASKED ->
            "${engine(summary.engine)} asks before it acts, and you decide — nothing screens " +
                "its calls first."
        else -> null
    }

    /** How a card came down, for the line that replaces it. */
    fun resolution(engine: String, resolution: Resolution): String {
        val who = if (resolution.byThisPhone) "on this phone" else "on the Mac"
        return when (resolution.decision) {
            "accepted", "accept" -> "Accepted $who"
            "declined", "decline" -> "Declined $who"
            else -> resolution.note?.let { "No longer waiting — $it" }
                ?: "No longer waiting — answered while this phone wasn't listening"
        }
    }
}
