package dev.siliconoptimizer.buddy.agents

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.AgentEngines
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentMessageRequest
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Agents tab: the Mac's Codex and Pi sessions, seen and driven from here.
 *
 * What is *in* a session is [AgentSession]'s business — a pure reducer over what the Mac
 * answers and what it streams. What is left here is the asking: when to read a session
 * again, which verb is in flight, what the person has typed, and which sessions they have
 * opened on this phone — because a session somebody is following is one whose approvals
 * are worth a notification when the phone goes back in their pocket.
 */
class AgentsViewModel : ViewModel() {

    var board by mutableStateOf(AgentBoard.empty)
        private set

    var isLoading by mutableStateOf(false)
        private set

    /**
     * Why there is nothing to show at all: a Mac from before agent sessions, a device that
     * was only ever allowed to chat, a Mac that is not answering. The Mac's own sentence
     * where it gave one.
     */
    var unavailable by mutableStateOf<String?>(null)
        private set

    /**
     * The Mac answered 401: it no longer knows this phone. Nothing here can mend that — no
     * read is retried, no card can be answered — and the screens offer to pair again.
     */
    var unpaired by mutableStateOf(false)
        private set

    /** The verb in flight on an engine — "Starting…" — so its buttons wait. */
    val busy = mutableStateMapOf<String, String>()

    /** The Mac's sentence about the last verb that failed, per engine. */
    val problems = mutableStateMapOf<String, String>()

    /** What has been typed into each session's composer. */
    val drafts = mutableStateMapOf<String, String>()

    /** The model picked for a session, until the Mac's own summary says it took. */
    val picked = mutableStateMapOf<String, String>()

    /** Approval ids with an answer on its way. */
    val answering = mutableStateMapOf<String, String>()

    /** A failure answering one card that is not the Mac's to explain: unreachable, say. */
    val cardProblems = mutableStateMapOf<String, String>()

    /**
     * Codex will not take a message while its turn is running, and says so with a 409.
     * Held per engine so the composer can offer the two things that do work: wait, or
     * interrupt.
     */
    val sendRefused = mutableStateMapOf<String, String>()

    /** The row a send became, until it shows up in the transcript. */
    val awaiting = mutableStateMapOf<String, String>()

    /**
     * The sessions this phone has opened since the app started. "Watching" is the phone
     * following a session, which is what makes an approval in it worth ringing about once
     * the app is in the background — and nothing else is.
     */
    var watching by mutableStateOf<Set<String>>(emptySet())
        private set

    private var transport: ControlTransport? = null
    private val inFlight = mutableSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()
    private val retries = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null

    /**
     * The Mac refused this phone the agent routes (403). Like a 401, no retry changes that —
     * the scope was decided at pairing — so reads stop and the tab says why.
     */
    private var refused = false

    // MARK: - Reading the Mac

    /**
     * Bumped by [reset]. A session screen still showing afterwards — a phone paired again
     * while it was open — registers itself again, since what was watched went with the reset.
     */
    var resets by mutableStateOf(0)
        private set

    /** A different Mac, or none: everything on screen belonged to the last one. */
    fun reset() {
        resets++
        refreshJob?.cancel()
        board = AgentBoard.empty
        unavailable = null
        unpaired = false
        refused = false
        retries.values.forEach { it.cancel() }
        retries.clear()
        busy.clear()
        problems.clear()
        drafts.clear()
        picked.clear()
        answering.clear()
        cardProblems.clear()
        sendRefused.clear()
        awaiting.clear()
        watching = emptySet()
        inFlight.clear()
        retryAfter.clear()
        transport = null
    }

    /**
     * Reads the engines, then each session whole. Two engines, so two reads — and each
     * brings its approvals by id, which is what a card and a notification need.
     */
    fun refresh(transport: ControlTransport?) {
        this.transport = transport
        transport ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            isLoading = true
            try {
                val list = transport.agentSessions()
                unavailable = null
                board = board.applying(list)
                list.sessions.forEach { summary ->
                    board = board.updating(summary.engine) {
                        it.needing(if (it.loaded) Sync.CatchUp else Sync.Reload)
                    }
                }
                schedule()
            } catch (error: TransportError) {
                if (error is TransportError.Unauthorized) {
                    markUnpaired()
                } else {
                    unavailable = when (error) {
                        is TransportError.RouteUnavailable ->
                            "This Mac doesn't have agent sessions yet. Update Silicon Optimizer " +
                                "on the Mac, then pull to refresh."
                        else -> error.message
                    }
                }
            } finally {
                isLoading = false
            }
        }
    }

    /** One `agent` frame from `/events`. */
    fun apply(event: AgentEvent) {
        val engine = event.engine
        // What this phone answered from a notification's button is this phone's answer.
        board = board.updating(engine) { it.remembering(AgentAnswers.of(engine)) }.applying(event)
        val session = board.session(event.engine)
        awaiting[event.engine]?.let { id -> if (session.item(id) != null) awaiting.remove(event.engine) }
        if (event.kind == AgentEvent.TURN && event.turnActive == false) {
            sendRefused.remove(event.engine)
        }
        schedule()
    }

    /**
     * The stream broke — dropped, or the Mac dropped frames for this phone. Heard in order
     * with the frames, so each session knows which frames no longer follow on from what it
     * holds, and asks for the gap.
     */
    fun streamBroken() {
        board = board.streamBroken()
        schedule()
    }

    /**
     * The Mac no longer knows this phone. Said once, and nothing is asked of it again until
     * the phone is paired again — which is a new Mac as far as this model is concerned.
     * Called from outside too: a 401 on any route is the same news.
     */
    fun markUnpaired() {
        unpaired = true
        unavailable = UNPAIRED
    }

    /**
     * Starts the reads that sessions have asked for, one at a time per engine. A session
     * asking again while its read is out gets another afterwards — see [AgentSession.needs].
     */
    private fun schedule() {
        val transport = transport ?: return
        if (unpaired || refused) return
        val now = System.currentTimeMillis()
        for (session in board.all) {
            val engine = session.engine
            if (session.needs == Sync.None || engine in inFlight) continue
            val waitUntil = retryAfter[engine] ?: 0L
            if (now < waitUntil) {
                // One wait per engine, however many frames ask meanwhile; when it is over
                // the read goes out rather than looking at the clock again.
                if (retries[engine]?.isActive != true) {
                    retries[engine] = viewModelScope.launch {
                        delay(waitUntil - now)
                        retryAfter.remove(engine)
                        retries.remove(engine)
                        schedule()
                    }
                }
                continue
            }
            // Read before `syncing` clears what was asked for: a reload has no cursor.
            val cursor = session.cursor
            val asked = session.needs
            board = board.updating(engine) { it.syncing() }
            inFlight += engine
            viewModelScope.launch {
                try {
                    val detail = transport.agentSession(engine, cursor?.since, cursor?.epoch)
                    board = board.updating(engine) {
                        it.remembering(AgentAnswers.of(engine)).applying(detail)
                    }
                    retryAfter.remove(engine)
                    awaiting[engine]?.let { id ->
                        if (board.session(engine).item(id) != null) awaiting.remove(engine)
                    }
                    // What the Mac now says the session's model is has caught up with the
                    // pick, so the pick is no longer news.
                    picked[engine]?.let { if (detail.session.model == it) picked.remove(engine) }
                } catch (error: TransportError) {
                    when (error) {
                        // No retry mends a Mac that has forgotten this phone, or one that
                        // paired it for chat only.
                        is TransportError.Unauthorized -> markUnpaired()
                        is TransportError.Forbidden -> {
                            refused = true
                            unavailable = error.message
                        }
                        else -> {
                            // Asked again, but not at once: the next frame would otherwise
                            // knock on a Mac that has just said no, as fast as frames arrive.
                            retryAfter[engine] = System.currentTimeMillis() + RETRY_MS
                            board = board.updating(engine) { it.needing(asked) }
                        }
                    }
                } finally {
                    inFlight -= engine
                    schedule()
                }
            }
        }
    }

    // MARK: - Watching

    /** The session screen for [engine] is open on this phone. */
    fun watch(engine: String) {
        watching = watching + engine
    }

    /**
     * The sessions worth following once the app is in the background: ones this phone has
     * opened, with a turn running in them right now.
     */
    val watchedTurns: List<String>
        get() = watching.filter { board.session(it).turnActive }

    // MARK: - The verbs

    fun start(engine: String) = verb(engine, "Starting…", Sync.CatchUp) { it.startAgent(engine) }

    /** Stops the sidecar. The screen asks first. */
    fun stop(engine: String) = verb(engine, "Stopping…", Sync.CatchUp) { it.stopAgent(engine) }

    /** A fresh thread; the Mac's transcript clears with it. The screen asks first. */
    fun newThread(engine: String) =
        verb(engine, "Starting a new thread…", Sync.Reload) { it.newAgentThread(engine) }

    fun interrupt(engine: String) =
        verb(engine, "Interrupting…", Sync.CatchUp) { it.interruptAgent(engine) }

    private fun verb(
        engine: String,
        label: String,
        then: Sync,
        call: suspend (ControlTransport) -> AgentSessionSummary,
    ) {
        val transport = transport ?: return
        if (busy.containsKey(engine) || unpaired) return
        busy[engine] = label
        problems.remove(engine)
        viewModelScope.launch {
            try {
                val summary = call(transport)
                board = board.updating(engine) { it.summarized(summary).needing(then) }
                sendRefused.remove(engine)
            } catch (error: TransportError) {
                if (error is TransportError.Unauthorized) markUnpaired()
                // A 409 here is the Mac explaining — Codex has no folder yet, the engine
                // is not running — and its sentence is the whole of what to say.
                else problems[engine] = error.message ?: "The Mac could not do that."
            } finally {
                busy.remove(engine)
                schedule()
            }
        }
    }

    fun pick(engine: String, model: String) {
        picked[engine] = model
    }

    /**
     * Sends what has been typed. 202 is success: the turn has been handed to the engine,
     * and the row it became arrives on `/events` — or, if the stream is quiet, from a read
     * a moment later.
     */
    fun send(engine: String) {
        val transport = transport ?: return
        val text = drafts[engine].orEmpty().trim()
        if (text.isEmpty() || busy.containsKey(engine) || unpaired) return
        val summary = board.session(engine).summary
        val model = ModelPicker.modelToSend(summary, picked[engine])
        busy[engine] = "Sending…"
        problems.remove(engine)
        sendRefused.remove(engine)
        viewModelScope.launch {
            try {
                val accepted = transport.sendAgentMessage(engine, AgentMessageRequest(text, model))
                drafts[engine] = ""
                if (board.session(engine).item(accepted.itemID) == null) {
                    awaiting[engine] = accepted.itemID
                    // The frame is ten readings a second away. If it has not come, the
                    // stream is down or dropped it — ask rather than wait.
                    launch {
                        delay(AWAIT_ROW_MS)
                        if (awaiting[engine] == accepted.itemID) {
                            board = board.updating(engine) { it.needing(Sync.CatchUp) }
                            schedule()
                        }
                    }
                }
            } catch (error: TransportError.Conflict) {
                // Codex takes one turn at a time; Pi takes a message mid-turn. Whichever
                // engine said no, it said why.
                if (board.session(engine).turnActive) {
                    sendRefused[engine] = error.message ?: "The agent is still working."
                } else {
                    problems[engine] = error.message ?: "The Mac would not take that."
                }
            } catch (error: TransportError) {
                if (error is TransportError.Unauthorized) {
                    markUnpaired()
                    return@launch
                }
                problems[engine] = error.message ?: "The Mac would not take that."
                if (error is TransportError.BadRequest) {
                    // Most likely a model that has fallen off the list since it was picked.
                    picked.remove(engine)
                    board = board.updating(engine) { it.needing(Sync.CatchUp) }
                }
            } finally {
                busy.remove(engine)
                schedule()
            }
        }
    }

    /**
     * Accepts or declines a held call. 409 is the Mac saying it was answered there first
     * — or cannot be answered right now — and its sentence goes on the card; 404 is a card
     * with nothing behind it any more, and it goes quietly.
     */
    fun answer(engine: String, id: String, accept: Boolean) {
        val transport = transport ?: return
        if (answering.containsKey(id) || unpaired) return
        val decision = if (accept) AgentApprovalDecision.ACCEPT else AgentApprovalDecision.DECLINE
        answering[id] = decision
        cardProblems.remove(id)
        // Recorded before it is sent: the Mac's frame saying the card was answered can beat
        // the reply to this request, and the card must still come down as this phone's. The
        // same record a notification's button writes, which every frame and read consults.
        AgentAnswers.note(engine, id, decision)
        viewModelScope.launch {
            try {
                val result = transport.answerAgentApproval(engine, id, decision)
                board = board.updating(engine) { it.answered(id, result.decision, result.session) }
            } catch (error: TransportError) {
                // Not this phone's answer after all, whatever comes of the card.
                AgentAnswers.forget(engine, id)
                board = board.updating(engine) { it.unanswering(id) }
                when (error) {
                    is TransportError.NotFound -> board = board.updating(engine) { it.approvalGone(id) }
                    is TransportError.Conflict -> board = board.updating(engine) {
                        it.approvalConflict(id, error.message ?: AgentNotices.ANSWERED_ON_THE_MAC)
                    }
                    is TransportError.Unauthorized -> markUnpaired()
                    else -> cardProblems[id] = error.message ?: "The Mac did not answer."
                }
            } finally {
                answering.remove(id)
                schedule()
            }
        }
    }

    /** Per engine, the approval a notification's Review asked to see first. */
    private val focused = mutableStateMapOf<String, String>()

    /** Review on a notification: [id]'s card is the one in front while it waits. */
    fun focus(engine: String, id: String) {
        focused[engine] = id
    }

    /**
     * The card the session screen shows: the one a notification's Review asked for while it
     * is still waiting, and otherwise the one that has waited longest.
     */
    fun shownApproval(engine: String): AgentApproval? {
        val pending = board.session(engine).pending
        return pending.firstOrNull { it.id == focused[engine] } ?: pending.firstOrNull()
    }

    /**
     * A press on Accept or Decline passed through another app's window — the shape of a
     * tapjacking attempt. Nothing is sent; the card says why.
     */
    fun refuseObscured(id: String) {
        cardProblems[id] = OBSCURED
    }

    fun dismissResolutions(engine: String) {
        board = board.updating(engine) { it.dismissingResolutions() }
    }

    fun clearProblem(engine: String) {
        problems.remove(engine)
    }

    companion object {
        /** How long a send waits for its row before asking the Mac for it. */
        const val AWAIT_ROW_MS = 1_500L

        /** How long a session waits after a failed read before asking again. */
        const val RETRY_MS = 5_000L

        /** The engines the Mac documents, for display before it has been asked. */
        val KNOWN_ENGINES = listOf(AgentEngines.CODEX, AgentEngines.PI)

        const val OBSCURED = "Something was drawn over Silicon Buddy when you tapped, so " +
            "nothing was sent. Close whatever is on top, then answer."

        const val UNPAIRED = "This phone is no longer paired with the Mac, so its agents are " +
            "out of reach. Pair it again to follow them."
    }
}
