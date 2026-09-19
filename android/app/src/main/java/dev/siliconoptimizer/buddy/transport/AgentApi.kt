package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Mac's agent sessions: the Chat tab's Codex and Pi engines, mirrored.
 *
 * There is one session per engine and it is *the* session — the thread the owner is
 * looking at on the Mac. The phone is a second screen on it, not a second seat: a message
 * sent from here appears in the Mac's transcript, and an approval answered on either side
 * is answered once, for both. So the session id is the engine id, and nothing here keeps
 * a registry of threads.
 *
 * Mirrored from `Sources/SiliconControl/AgentSessionsAPI.swift`; optionality is copied
 * exactly, and `ContractTest` round-trips every one of these against the Mac's export.
 * Every route is full scope: each of them runs commands on the owner's Mac.
 */
object AgentEngines {
    const val CODEX = "codex"
    const val PI = "pi"

    /** How a person says the engine's name. The Mac's own spelling of its error sentences. */
    fun displayName(engine: String): String = when (engine) {
        CODEX -> "Codex"
        PI -> "Pi"
        else -> engine.replaceFirstChar { it.uppercase() }
    }
}

/**
 * One gateway model as a picker needs it: what to send, what to show, and where it runs.
 * `where` is for reading — "This Mac" or a peer's name — and never for routing.
 */
@Serializable
data class AgentModelChoice(
    val id: String,
    val label: String,
    val where: String,
)

/**
 * A session as the Agents list shows it. Listed even when the engine is stopped, because
 * a phone that cannot see a stopped engine cannot offer to start one.
 */
@Serializable
data class AgentSessionSummary(
    /** "codex" or "pi" — and the session id. */
    val engine: String,
    /** `stopped`, `starting`, `running` or `failed`. Rendered, not trusted to stay four. */
    val state: String,
    /** The engine's own id for the thread. Codex mints one; Pi has none to give. */
    val threadID: String? = null,
    /**
     * Which transcript this is. It changes with a new thread, a restart, and every launch
     * of the Mac's app — so rows held under another epoch belong to something gone, and a
     * `?since=` is only a cursor together with the epoch it was read under.
     */
    val epoch: String,
    /** The gateway model the next turn will use — the session's, not one turn's. */
    val model: String,
    val modelChoices: List<AgentModelChoice>,
    /**
     * The folder the engine works in, home-relative (`~/…`). Chosen on the Mac and never
     * by a phone; absent for Codex until the owner has picked one.
     */
    val cwd: String? = null,
    /**
     * Whether a person will be asked before the agent acts: `screened` (the agent asks,
     * and the Mac's guardrail judges each ask before a person sees it), `asked` (the agent
     * asks and a person decides) or `unattended` (nothing asks — Codex under "never ask",
     * Pi whenever the guardrail is off).
     */
    @SerialName("approvals") val approvalMode: String,
    /**
     * Codex's `read-only`, `workspace-write` or `danger-full-access` — the one its current
     * thread started with — or `none` for Pi, whose tools run as the Mac's user.
     */
    val sandbox: String,
    val turnActive: Boolean,
    /** Zero unless the engine is running: a stopped engine is asking nobody anything. */
    val pendingApprovals: Int,
    val itemCount: Int,
    val updatedAt: String,
    /** Why it is `failed`, in the engine's own words. Never set otherwise. */
    val failure: String? = null,
) {
    val isRunning: Boolean get() = state == STATE_RUNNING
    val isStopped: Boolean get() = state == STATE_STOPPED || state == STATE_FAILED

    /** Nothing will ask before the agent acts: the one mode worth a warning. */
    val runsWithoutAsking: Boolean get() = approvalMode == APPROVALS_UNATTENDED

    companion object {
        const val STATE_STOPPED = "stopped"
        const val STATE_STARTING = "starting"
        const val STATE_RUNNING = "running"
        const val STATE_FAILED = "failed"

        const val APPROVALS_SCREENED = "screened"
        const val APPROVALS_ASKED = "asked"
        const val APPROVALS_UNATTENDED = "unattended"

        const val SANDBOX_NONE = "none"
    }
}

/** `GET /agent/sessions`. */
@Serializable
data class AgentSessionList(val sessions: List<AgentSessionSummary>)

/**
 * One row of a transcript, in the one vocabulary both engines are mapped onto: `user`,
 * `assistant`, `reasoning`, `command`, `fileChange`, `tool`, `notice`, `error`.
 *
 * `text` is what the row is about — the prose, the command line, the tool — and `output`
 * is what came back, where the engine keeps the two apart.
 */
@Serializable
data class AgentItem(
    /** The engine's own id: an update lands on the row it is about. */
    val id: String,
    val kind: String,
    val text: String,
    /** At most the last 8,192 characters of what came back. */
    val output: String? = null,
    /**
     * True when `output` is only the end of what the command printed: the Mac keeps the
     * tail of a long log, where the error is, and not the rest.
     */
    val truncated: Boolean? = null,
    /** `running`, `completed`, `failed` or `declined`, where the row has a lifecycle. */
    val status: String? = null,
    /** The model the turn was sent with, on the row that was the sending. */
    val model: String? = null,
    val at: String,
) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val REASONING = "reasoning"
        const val COMMAND = "command"
        const val FILE_CHANGE = "fileChange"
        const val TOOL = "tool"
        const val NOTICE = "notice"
        const val ERROR = "error"

        const val RUNNING = "running"
        const val COMPLETED = "completed"
        const val FAILED = "failed"
        const val DECLINED = "declined"
    }
}

/**
 * What the Mac's guardrail made of a held call before it reached a person — the verdict
 * and the sentence the Mac's own card shows: "Jev: review: destructive".
 */
@Serializable
data class AgentScreening(
    /** `act`, `confirm`, `block` or `unavailable` — and `unavailable` is never a pass. */
    val verdict: String,
    val summary: String,
) {
    companion object {
        const val ACT = "act"
        const val CONFIRM = "confirm"
        const val BLOCK = "block"
        const val UNAVAILABLE = "unavailable"
    }
}

/**
 * A call the agent is holding, waiting for a person. Only what the Mac's guardrail left
 * to a human reaches this list.
 */
@Serializable
data class AgentApproval(
    val id: String,
    /** `command`, `fileChange` or `tool`. */
    val kind: String,
    /** The command line, the paths, or the tool and its arguments: the thing being decided. */
    val summary: String,
    /** Why the engine is asking, when it said. */
    val reason: String? = null,
    /**
     * The guardrail's verdict. An approval reaches the wire only once it has one — a call
     * still being screened is not listed at all, as the Mac shows it with no buttons.
     */
    val screening: AgentScreening,
    val requestedAt: String,
)

/** `GET /agent/sessions/{engine}`: the summary, the transcript, and what is waiting. */
@Serializable
data class AgentSessionDetail(
    val session: AgentSessionSummary,
    /** The whole transcript, or — with `?since=` — what is newer than that. */
    val items: List<AgentItem>,
    /** Everything waiting right now. Always the whole set, never a slice. */
    val approvals: List<AgentApproval>,
    /** The session's watermark as of this answer: the next `?since=`. */
    val seq: Long,
    /** The transcript `seq` belongs to; the other half of the cursor. */
    val epoch: String,
    /**
     * True when `items` replaces what is held — the whole transcript, or its newest rows
     * when a catch-up would not fit — and false for a slice to merge in by id.
     */
    val complete: Boolean,
    /** Rows left out by `limit`: the oldest ones, still on the Mac. */
    val omitted: Int = 0,
)

/** `POST /agent/sessions/{engine}/messages`. */
@Serializable
data class AgentMessageRequest(
    val text: String,
    /**
     * One of the session's `modelChoices`, or absent. It sticks: sending with a model
     * makes it the Mac's own picker choice for this engine.
     */
    val model: String? = null,
)

/** 202: the turn has been handed to the engine, and this is the row it became. */
@Serializable
data class AgentMessageAccepted(val itemID: String)

/** `POST /agent/sessions/{engine}/approvals/{id}`. */
@Serializable
data class AgentApprovalDecision(val decision: String) {
    companion object {
        const val ACCEPT = "accept"
        const val DECLINE = "decline"
    }
}

/** What answering says back: the decision as applied, and the session after it. */
@Serializable
data class AgentApprovalResult(
    val id: String,
    /** `accepted` or `declined`. */
    val decision: String,
    val session: AgentSessionSummary,
)

/**
 * The `agent` frame on `GET /events`.
 *
 * - `reset`: the transcript was replaced — a new thread, a restart. Drop every row and
 *   card held for the engine; `epoch`, `threadID`, `state` and `turnActive` say what
 *   replaced them.
 * - `state`: the session started, stopped or failed, or its thread got its id.
 * - `turn`: a turn began or ended — `turnActive` says which.
 * - `item`: a row appeared or changed — `item` carries it whole, never a delta.
 * - `approval`: a call is waiting or has been answered — `state` says `pending`,
 *   `accepted` or `declined`, whichever side answered it.
 *
 * `seq` is the session's own counter, the same one `?since=` takes, and frames arrive in
 * its order. On connecting, a phone is sent each engine's `state`, `turn` and pending
 * approvals at the current `seq` — never the rows, which it fetches.
 */
@Serializable
data class AgentEvent(
    val engine: String,
    val kind: String,
    /** Non-decreasing on one stream: the next `?since=` is the last one read. */
    val seq: Long,
    /** Which transcript the frame is about. A different one than held means a reload. */
    val epoch: String,
    val threadID: String? = null,
    val item: AgentItem? = null,
    val approval: AgentApproval? = null,
    val turnActive: Boolean? = null,
    val state: String? = null,
) {
    companion object {
        /** The transcript was replaced — a new thread, a restart: drop the rows and cards. */
        const val RESET = "reset"
        const val STATE = "state"
        const val TURN = "turn"
        const val ITEM = "item"
        const val APPROVAL = "approval"

        const val PENDING = "pending"
        const val ACCEPTED = "accepted"
        const val DECLINED = "declined"
    }
}

/**
 * `resync` on `/events`: this phone fell far enough behind that the Mac dropped frames for
 * it rather than wait. Everything since the last frame read is to be fetched again.
 */
@Serializable
data class ResyncEvent(val dropped: Int)
