package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionList
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary

/**
 * One of the Mac's agent sessions, as this phone holds it.
 *
 * Two sources say what is in it, and neither is enough alone. `GET /agent/sessions/{engine}`
 * is the truth at one moment — the whole transcript, or with `?since=&epoch=` what changed
 * after a cursor, plus every approval waiting. The `agent` frames on `/events` are the
 * movement: a row whole whenever it changes, an approval when it starts or stops waiting, a
 * turn beginning or ending, a transcript replaced. So this is a reducer over both, and it is
 * pure, because the orders that are awkward to produce against a real Mac — a fetch older
 * than the frames that overtook it, a resolution before the request it resolves, the same
 * row twice, a Mac that relaunched — are the ones that matter, and they are tests here.
 *
 * The rules, all of them about not letting old news undo new:
 *
 * - **A transcript has an epoch.** It changes with a new thread, a restart and every launch
 *   of the Mac's app. Rows held under another epoch belong to something that is gone: a
 *   `reset` frame drops them, and a frame or an answer from an unexpected epoch means the
 *   whole transcript is read again.
 * - **A row is keyed by its id** and remembers the sequence it is known at. A frame older
 *   than what is held is dropped and one as new replaces it — the same frame twice is
 *   harmless.
 * - **An answer is a snapshot at its `seq`.** Rows the stream brought that are newer than it
 *   are kept over it. `complete` replaces the transcript, a slice merges into it.
 * - **An approval id is minted once.** Once it has stopped waiting — answered here, at the
 *   Mac, or gone with its engine — it is settled, and no snapshot taken before that can put
 *   its card back.
 * - **The cursor is how far the rows are known to be complete** ([rowsSeq]), not the highest
 *   number seen. A fetch answered at 30 and a stream that opened at 41 leave 31 to 41 in
 *   neither — the stream's opening carries no rows — so the cursor stays at 30 until a
 *   catch-up fills the gap. Frames move it only while this stream has been unbroken since
 *   the cursor.
 */
data class AgentSession(
    val engine: String,
    val summary: AgentSessionSummary? = null,
    /** The transcript, oldest first. */
    val items: List<TrackedItem> = emptyList(),
    /** What is waiting for a person, oldest first. */
    val approvals: List<TrackedApproval> = emptyList(),
    /** Approval ids that have stopped waiting. Never pending again. */
    val settled: Set<String> = emptySet(),
    /** How the last few cards came down, newest first — so a card never just vanishes. */
    val resolutions: List<Resolution> = emptyList(),
    /** Ids this phone answered, and how, so their frames read as this phone's. */
    val answeredHere: Map<String, String> = emptyMap(),
    /** A sentence the Mac left on a card: a 409 saying it was answered first, say. */
    val notes: Map<String, String> = emptyMap(),
    /** Which transcript the rows belong to, once known. */
    val epoch: String? = null,
    /** The highest sequence applied from anywhere. */
    val seq: Long = 0,
    /** How far the rows are known to be complete: the next `?since=`. */
    val rowsSeq: Long = 0,
    /**
     * The sequence the current stream opened at for this engine, or null while it has not
     * been heard from since it (re)connected. Frames after it are gap-free.
     */
    val streamFrom: Long? = null,
    /** The sequences the state and the turn were last set at, so an old frame cannot undo them. */
    val stateSeq: Long = 0,
    val turnSeq: Long = 0,
    /** True once a whole transcript has been read. Until then a slice means nothing. */
    val loaded: Boolean = false,
    /** Rows the Mac left out of the last whole answer: the oldest ones, still on the Mac. */
    val omitted: Int = 0,
    /** What this session needs from the Mac next, if anything. */
    val needs: Sync = Sync.None,
) {
    val transcript: List<AgentItem> get() = items.map { it.item }
    val pending: List<AgentApproval> get() = approvals.map { it.approval }
    val state: String get() = summary?.state ?: AgentSessionSummary.STATE_STOPPED
    val turnActive: Boolean get() = summary?.turnActive ?: false

    /**
     * How many approvals are waiting. The list once a detail has been read — the summary's
     * count is a moment older than any frame — and the summary's count before that, or
     * whichever is larger, because a frame can announce one the count has not caught.
     */
    val pendingCount: Int
        get() = if (loaded) approvals.size else maxOf(approvals.size, summary?.pendingApprovals ?: 0)

    fun item(id: String): AgentItem? = items.firstOrNull { it.item.id == id }?.item

    /** True while the stream has been unbroken since the rows were last known complete. */
    private val continuous: Boolean get() = streamFrom != null && rowsSeq >= streamFrom

    // MARK: - What the Mac answered

    /** A fresh `GET /agent/sessions/{engine}`, with or without a cursor. */
    fun applying(detail: AgentSessionDetail): AgentSession {
        val at = detail.seq
        val otherTranscript = epoch != null && detail.epoch != epoch
        if (!detail.complete && (otherTranscript || !loaded)) {
            // A slice of a transcript this phone does not hold cannot be merged into it.
            // The Mac answers a foreign cursor whole, so this is a Mac older than epochs or
            // a race with a reset — either way, read it all.
            return copy(needs = Sync.Reload)
        }
        // Frames from this transcript that overtook the answer stay; anything held from
        // another transcript does not.
        val kept = if (otherTranscript) emptyList() else items
        val keptApprovals = if (otherTranscript) emptyList() else approvals
        val answered = detail.items.map { TrackedItem(it, at) }

        val merged = if (detail.complete) {
            // The whole transcript as of `at` (or its newest rows). A row the stream brought
            // since is newer than the answer and wins; a row the answer does not have and
            // that is no newer than it belongs to a transcript the Mac no longer holds.
            val byID = kept.associateBy { it.item.id }
            val named = answered.map { it.item.id }.toSet()
            answered.map { fresh -> byID[fresh.item.id]?.takeIf { it.seq > at } ?: fresh } +
                kept.filter { it.seq > at && it.item.id !in named }
        } else {
            answered.fold(kept) { list, fresh -> list.upserting(fresh) }
        }

        // The approvals in an answer are always the whole set waiting at `at`.
        val listed = detail.approvals.map { it.id }.toSet()
        val held = keptApprovals.associateBy { it.approval.id }
        val waiting = detail.approvals
            .filter { it.id !in settled }
            .map { fresh -> held[fresh.id]?.takeIf { it.seq > at } ?: TrackedApproval(fresh, at) }
        val arrivedSince = keptApprovals.filter { it.seq > at && it.approval.id !in listed }
        // Waiting before `at` and missing from it: it stopped waiting while this phone was
        // not looking, and the frame that would have said how was missed. Settled, and
        // said, so a card on screen does not just vanish. Cards from a transcript that has
        // gone altogether are dropped without a word: nobody answered them.
        val vanished = keptApprovals.filter { it.seq <= at && it.approval.id !in listed }
        val abandoned = if (otherTranscript) approvals.map { it.approval.id } else emptyList()

        val fresh = detail.session
        val summary = fresh.copy(
            state = if (!otherTranscript && stateSeq > at) this.summary?.state ?: fresh.state else fresh.state,
            turnActive = if (!otherTranscript && turnSeq > at) {
                this.summary?.turnActive ?: fresh.turnActive
            } else {
                fresh.turnActive
            },
        )

        // Rows are complete to `at` now. If the stream has been unbroken since then, the
        // frames already applied carry everything after it too.
        val rows = if (otherTranscript) at else maxOf(rowsSeq, at)
        var next = copy(
            summary = summary,
            items = merged,
            approvals = waiting + arrivedSince,
            settled = settled + vanished.map { it.approval.id } + abandoned,
            resolutions = vanished.fold(if (otherTranscript) emptyList() else resolutions) { list, gone ->
                list.adding(
                    Resolution(
                        approval = gone.approval,
                        decision = null,
                        byThisPhone = false,
                        note = notes[gone.approval.id],
                    ),
                )
            },
            notes = notes - vanished.map { it.approval.id }.toSet() - abandoned.toSet(),
            epoch = detail.epoch,
            seq = if (otherTranscript) at else maxOf(seq, at),
            rowsSeq = rows,
            stateSeq = if (otherTranscript) at else stateSeq,
            turnSeq = if (otherTranscript) at else turnSeq,
            loaded = true,
            omitted = if (detail.complete) detail.omitted else omitted,
            // `needs` is otherwise left alone: it was cleared when this fetch began, and
            // anything that asked again since asked about a later moment.
        )
        next = next.copy(rowsSeq = if (next.continuous) maxOf(next.rowsSeq, next.seq) else next.rowsSeq)

        // The stream opened after this answer was taken: the rows between the two are in
        // neither, so ask for them.
        if (streamFrom != null && next.rowsSeq < streamFrom) {
            next = next.copy(needs = next.needs.atLeast(Sync.CatchUp))
        }

        // A last check on a slice: the Mac says how many rows the transcript has. If what
        // is held cannot be that transcript — rows missing, or rows it did not have at
        // `at` — something happened that the cursor could not see, and the honest fix is to
        // read it all again.
        if (!detail.complete) {
            val expected = fresh.itemCount - next.omitted
            val total = next.items.size
            val atOrBefore = next.items.count { it.seq <= at }
            if (total < expected || atOrBefore > expected) {
                next = next.copy(needs = Sync.Reload)
            }
        }
        return next
    }

    /**
     * A summary from `GET /agent/sessions` or from one of the verbs — start, stop, new,
     * interrupt, or answering an approval. It carries no sequence, so it is taken whole:
     * it is the newest thing this phone knows at the moment it arrives, and a frame that
     * says otherwise afterwards is newer still. A different epoch than the rows were read
     * under means the rows are from something that is gone.
     */
    fun summarized(fresh: AgentSessionSummary): AgentSession {
        val next = copy(summary = fresh, stateSeq = seq, turnSeq = seq)
        return if (epoch != null && fresh.epoch != epoch) next.copy(needs = Sync.Reload) else next
    }

    // MARK: - What the stream said

    fun applying(event: AgentEvent): AgentSession {
        if (event.engine != engine) return this
        if (event.kind != AgentEvent.RESET && epoch != null && event.epoch != epoch) {
            // A frame about a transcript this phone does not hold: the Mac relaunched, or a
            // reset went by unseen. Nothing in it can be merged; read it all again.
            return copy(needs = Sync.Reload)
        }
        // The first frame since the stream (re)connected is where its unbroken run starts.
        // If the rows are known only to before it, the gap is in no frame: catch up.
        val opening = streamFrom == null
        var base = if (opening) copy(streamFrom = event.seq) else this
        if (opening && loaded && rowsSeq < event.seq) {
            base = base.copy(needs = base.needs.atLeast(Sync.CatchUp))
        }
        if (base.epoch == null) base = base.copy(epoch = event.epoch)

        val next = when (event.kind) {
            AgentEvent.RESET -> base.resetting(event)
            AgentEvent.STATE -> base.applyingState(event)
            AgentEvent.TURN -> base.applyingTurn(event)
            AgentEvent.ITEM -> event.item?.let { base.copy(items = base.items.upserting(TrackedItem(it, event.seq))) }
                ?: base
            AgentEvent.APPROVAL -> event.approval?.let { base.applyingApproval(it, event.state, event.seq) }
                ?: base
            // A kind this build has never heard of. The Mac grows them; a phone that has not
            // been rebuilt must not break on the upgrade.
            else -> base
        }
        val seq = maxOf(next.seq, event.seq)
        return next.copy(
            seq = seq,
            rowsSeq = if (next.continuous) maxOf(next.rowsSeq, seq) else next.rowsSeq,
        )
    }

    /**
     * The transcript was replaced. Every row and card held for this engine goes; what the
     * frame says is the new truth, and the Mac is asked for the rest of the summary.
     */
    private fun resetting(event: AgentEvent): AgentSession = copy(
        items = emptyList(),
        approvals = emptyList(),
        settled = settled + approvals.map { it.approval.id },
        // How the old thread's cards came down is the old thread's news.
        resolutions = emptyList(),
        notes = emptyMap(),
        epoch = event.epoch,
        summary = summary?.copy(
            epoch = event.epoch,
            threadID = event.threadID,
            state = event.state ?: summary.state,
            turnActive = event.turnActive ?: false,
            itemCount = 0,
            pendingApprovals = 0,
        ),
        // Empty, and known to be empty, as of this frame.
        rowsSeq = event.seq,
        streamFrom = event.seq,
        stateSeq = event.seq,
        turnSeq = event.seq,
        loaded = true,
        omitted = 0,
        needs = needs.atLeast(Sync.CatchUp),
    )

    private fun applyingState(event: AgentEvent): AgentSession {
        val state = event.state ?: return this
        if (event.seq < stateSeq) return this
        if (summary == null) return copy(needs = needs.atLeast(Sync.CatchUp))
        return copy(
            summary = summary.copy(
                state = state,
                // The frame also fires when the thread gets its id.
                threadID = event.threadID ?: summary.threadID,
                // A session that is not running has no turn in it.
                turnActive = if (state == AgentSessionSummary.STATE_RUNNING) summary.turnActive else false,
            ),
            stateSeq = event.seq,
            // The rest of the summary — why it failed, what it runs — comes from the Mac.
            needs = needs.atLeast(Sync.CatchUp),
        )
    }

    private fun applyingTurn(event: AgentEvent): AgentSession {
        val active = event.turnActive ?: return this
        if (event.seq < turnSeq) return this
        if (summary == null) return copy(needs = needs.atLeast(Sync.CatchUp))
        return copy(
            summary = summary.copy(turnActive = active),
            turnSeq = event.seq,
            // A turn that ended is the moment to pick up what frames do not carry: the
            // model the next turn will use, the counts.
            needs = if (active) needs else needs.atLeast(Sync.CatchUp),
        )
    }

    private fun applyingApproval(approval: AgentApproval, state: String?, at: Long): AgentSession {
        val id = approval.id
        return when (state ?: AgentEvent.PENDING) {
            AgentEvent.PENDING -> {
                if (id in settled) return this
                val held = approvals.firstOrNull { it.approval.id == id }
                when {
                    held == null -> copy(approvals = approvals + TrackedApproval(approval, at))
                    held.seq > at -> this
                    else -> copy(
                        approvals = approvals.map {
                            if (it.approval.id == id) TrackedApproval(approval, at) else it
                        },
                    )
                }
            }
            // Accepted or declined — whichever side answered it — or any other word for
            // "no longer waiting". The card comes down either way.
            else -> {
                if (id in settled) {
                    return copy(approvals = approvals.filterNot { it.approval.id == id })
                }
                val decided = state?.takeIf {
                    it == AgentEvent.ACCEPTED || it == AgentEvent.DECLINED
                }
                val mine = answeredHere[id]
                copy(
                    approvals = approvals.filterNot { it.approval.id == id },
                    settled = settled + id,
                    resolutions = resolutions.adding(
                        Resolution(
                            approval = approvals.firstOrNull { it.approval.id == id }?.approval
                                ?: approval,
                            decision = decided ?: mine,
                            byThisPhone = mine != null,
                            note = notes[id],
                        ),
                    ),
                    notes = notes - id,
                )
            }
        }
    }

    /**
     * The stream broke — it dropped and is reconnecting, or the Mac said it had to drop
     * frames for this phone. Frames after this are no longer known to follow on from the
     * ones before, so the cursor stops moving with them and the gap is fetched.
     */
    fun streamBroken(): AgentSession = copy(streamFrom = null, needs = needs.atLeast(Sync.CatchUp))

    // MARK: - What this phone did

    /** This phone's answer was applied: the card comes down, saying so. */
    fun answered(id: String, decision: String, fresh: AgentSessionSummary? = null): AgentSession {
        val card = approvals.firstOrNull { it.approval.id == id }?.approval
        val base = if (fresh != null) summarized(fresh) else this
        if (id in settled) return base.copy(answeredHere = answeredHere + (id to decision))
        return base.copy(
            approvals = approvals.filterNot { it.approval.id == id },
            settled = settled + id,
            answeredHere = answeredHere + (id to decision),
            resolutions = card?.let {
                resolutions.adding(Resolution(it, decision, byThisPhone = true, note = null))
            } ?: resolutions,
            notes = notes - id,
        )
    }

    /** 404: answered already, or gone with its engine. The card goes, quietly. */
    fun approvalGone(id: String): AgentSession = copy(
        approvals = approvals.filterNot { it.approval.id == id },
        settled = settled + id,
        notes = notes - id,
    )

    /**
     * 409: the Mac has something to say about this card — answered there first, still being
     * screened, or its engine stopped. The sentence goes on the card, and the Mac is asked
     * what is true now: the card stays only if it is still waiting.
     */
    fun approvalConflict(id: String, message: String): AgentSession =
        copy(notes = notes + (id to message), needs = needs.atLeast(Sync.CatchUp))

    fun needing(sync: Sync): AgentSession = copy(needs = needs.atLeast(sync))

    /** The fetch [needs] asked for is under way. */
    fun syncing(): AgentSession = copy(needs = Sync.None)

    fun dismissingResolutions(): AgentSession = copy(resolutions = emptyList())

    /** The cursor for a catch-up, or null when only the whole transcript will do. */
    val cursor: Cursor?
        get() {
            val epoch = epoch ?: return null
            if (!loaded || needs == Sync.Reload) return null
            return Cursor(rowsSeq, epoch)
        }

    companion object {
        /** How many resolutions are kept to explain vanished cards. */
        const val REMEMBERED_RESOLUTIONS = 5
    }
}

/** `?since=` and `?epoch=`: one cursor in two halves. */
data class Cursor(val since: Long, val epoch: String)

/** One transcript row, and the sequence this version of it is known at. */
data class TrackedItem(val item: AgentItem, val seq: Long)

/** One approval waiting, and the sequence it is known at. */
data class TrackedApproval(val approval: AgentApproval, val seq: Long)

/**
 * How a card came down.
 *
 * [decision] is `accepted` or `declined` when somebody said which, and null when the phone
 * only knows it stopped waiting — answered while the stream was down, or gone with a
 * stopped engine.
 */
data class Resolution(
    val approval: AgentApproval,
    val decision: String?,
    val byThisPhone: Boolean,
    val note: String?,
)

/** What a session needs from the Mac next. Ordered: a reload covers a catch-up. */
enum class Sync {
    None,

    /** From the cursor: merge what changed after it. */
    CatchUp,

    /** The whole transcript again: what is held cannot be trusted as a base. */
    Reload,
    ;

    fun atLeast(other: Sync): Sync = if (other.ordinal > ordinal) other else this
}

private fun List<TrackedItem>.upserting(fresh: TrackedItem): List<TrackedItem> {
    val index = indexOfFirst { it.item.id == fresh.item.id }
    if (index < 0) return this + fresh
    // Older than what is held: news the phone already has.
    if (this[index].seq > fresh.seq) return this
    return toMutableList().also { it[index] = fresh }
}

private fun List<Resolution>.adding(resolution: Resolution): List<Resolution> =
    (listOf(resolution) + filterNot { it.approval.id == resolution.approval.id })
        .take(AgentSession.REMEMBERED_RESOLUTIONS)

/**
 * Every engine on the Mac, in the order it lists them.
 *
 * Frames arrive for all of them on the one stream; this routes each to its session and
 * adds the counts the Agents badge and the tile need.
 */
data class AgentBoard(
    val engines: List<String> = emptyList(),
    val sessions: Map<String, AgentSession> = emptyMap(),
) {
    fun session(engine: String): AgentSession = sessions[engine] ?: AgentSession(engine)

    val all: List<AgentSession> get() = engines.map { session(it) }

    /** What the Agents badge shows. */
    val pendingTotal: Int get() = all.sumOf { it.pendingCount }

    fun applying(list: AgentSessionList): AgentBoard {
        val listed = list.sessions.map { it.engine }
        return copy(
            engines = listed + engines.filterNot { it in listed },
            sessions = sessions + list.sessions.associate { it.engine to session(it.engine).summarized(it) },
        )
    }

    fun applying(event: AgentEvent): AgentBoard {
        val known = event.engine in engines
        return copy(
            engines = if (known) engines else engines + event.engine,
            sessions = sessions + (event.engine to session(event.engine).applying(event)),
        )
    }

    /** The stream broke for every engine at once. */
    fun streamBroken(): AgentBoard =
        copy(sessions = engines.associateWith { session(it).streamBroken() })

    fun updating(engine: String, change: (AgentSession) -> AgentSession): AgentBoard = copy(
        engines = if (engine in engines) engines else engines + engine,
        sessions = sessions + (engine to change(session(engine))),
    )

    companion object {
        val empty = AgentBoard()
    }
}
