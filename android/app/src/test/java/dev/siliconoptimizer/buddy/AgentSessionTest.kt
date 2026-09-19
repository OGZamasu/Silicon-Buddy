package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentBoard
import dev.siliconoptimizer.buddy.agents.AgentSession
import dev.siliconoptimizer.buddy.agents.Sync
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionList
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sessions reducer, over the orders a real stream produces.
 *
 * The Mac numbers everything in a session on one clock, per transcript: an epoch that
 * changes with a new thread, a restart or a relaunch. Frames arrive in order on one stream,
 * but a stream breaks, a fetch can be answered before or after the frames that overtake it,
 * the same row can arrive twice, and a transcript can be replaced underneath the phone.
 * Each test here is one of those orders, and what the phone must end up holding.
 */
class AgentSessionTest {

    private val epoch = "4B1D6C3E-2A9F-4E70-8D51-7C6B5A493827"
    private val nextEpoch = "9E2F7A10-3C4B-4D58-A6E1-0B2C3D4E5F60"

    private fun summary(
        engine: String = "codex",
        state: String = "running",
        turnActive: Boolean = false,
        pending: Int = 0,
        itemCount: Int = 0,
        model: String = "local/qwen3-coder-30b",
        epoch: String = this.epoch,
    ) = AgentSessionSummary(
        engine = engine, state = state, epoch = epoch, model = model,
        modelChoices = listOf(AgentModelChoice(model, "Qwen3-Coder 30B", "This Mac")),
        cwd = "~/Developer/lisbon", approvalMode = "screened", sandbox = "workspace-write",
        turnActive = turnActive, pendingApprovals = pending, itemCount = itemCount,
        updatedAt = "2026-09-19T10:12:44Z",
    )

    private fun item(id: String, text: String = id, kind: String = AgentItem.ASSISTANT, status: String? = null) =
        AgentItem(id = id, kind = kind, text = text, status = status, at = "2026-09-19T10:12:31Z")

    private fun approval(id: String, summary: String = "swift test --filter Lisbon") =
        AgentApproval(
            id = id, kind = "command", summary = summary,
            screening = AgentScreening("confirm", "Jev: review"), requestedAt = "2026-09-19T10:12:36Z",
        )

    private fun detail(
        items: List<AgentItem>,
        seq: Long,
        complete: Boolean,
        approvals: List<AgentApproval> = emptyList(),
        itemCount: Int = items.size,
        turnActive: Boolean = false,
        state: String = "running",
        epoch: String = this.epoch,
        omitted: Int = 0,
    ) = AgentSessionDetail(
        session = summary(itemCount = itemCount, pending = approvals.size, turnActive = turnActive, state = state, epoch = epoch),
        items = items, approvals = approvals, seq = seq, epoch = epoch, complete = complete, omitted = omitted,
    )

    private fun itemFrame(item: AgentItem, seq: Long, engine: String = "codex", epoch: String = this.epoch) =
        AgentEvent(engine = engine, kind = AgentEvent.ITEM, seq = seq, epoch = epoch, item = item)

    private fun approvalFrame(approval: AgentApproval, seq: Long, state: String = AgentEvent.PENDING) =
        AgentEvent(engine = "codex", kind = AgentEvent.APPROVAL, seq = seq, epoch = epoch, approval = approval, state = state)

    private fun turnFrame(active: Boolean, seq: Long) =
        AgentEvent(engine = "codex", kind = AgentEvent.TURN, seq = seq, epoch = epoch, turnActive = active)

    private fun stateFrame(state: String, seq: Long) =
        AgentEvent(engine = "codex", kind = AgentEvent.STATE, seq = seq, epoch = epoch, state = state)

    private fun resetFrame(seq: Long, epoch: String = nextEpoch) = AgentEvent(
        engine = "codex", kind = AgentEvent.RESET, seq = seq, epoch = epoch,
        state = "running", turnActive = false,
    )

    /** A session read whole once: rows a, b at 2. */
    private fun loaded(): AgentSession = AgentSession("codex")
        .applying(detail(listOf(item("a"), item("b")), seq = 2, complete = true))

    /**
     * The same, with the stream open since the moment it was read: the opening frame came
     * at 2, so every frame after it follows on from what is held.
     */
    private fun following(): AgentSession = loaded().applying(turnFrame(active = false, seq = 2)).syncing()

    private fun ids(session: AgentSession) = session.transcript.map { it.id }

    // MARK: - Answers: replace or merge

    @Test
    fun `a whole transcript replaces what was held`() {
        val session = loaded().applying(detail(listOf(item("c")), seq = 9, complete = true))
        assertEquals(listOf("c"), ids(session))
        assertTrue(session.loaded)
        assertEquals(9L, session.seq)
    }

    @Test
    fun `a slice merges — changed rows in place, new rows at the end`() {
        val session = loaded().applying(
            detail(listOf(item("b", "b, finished"), item("c")), seq = 10, complete = false, itemCount = 3),
        )
        assertEquals(listOf("a", "b", "c"), ids(session))
        assertEquals("b, finished", session.item("b")!!.text)
        assertEquals(Sync.None, session.needs)
    }

    /**
     * The stream overtook the answer: a frame for `b` at 12 arrived while `GET` was out,
     * and the answer — a snapshot at 10 — still has the older `b`. The newer one stays.
     */
    @Test
    fun `a slice older than what the stream brought does not overwrite it`() {
        val session = loaded()
            .applying(itemFrame(item("b", "b as of twelve"), seq = 12))
            .applying(detail(listOf(item("b", "b as of ten")), seq = 10, complete = false, itemCount = 2))
        assertEquals("b as of twelve", session.item("b")!!.text)
        assertEquals(12L, session.seq)
    }

    @Test
    fun `a complete answer keeps the rows the stream brought after it`() {
        val session = loaded()
            .applying(itemFrame(item("x", "newer than the answer"), seq = 15))
            .applying(detail(listOf(item("a", "a again")), seq = 10, complete = true))
        assertEquals(listOf("a", "x"), ids(session))
        assertEquals("a again", session.item("a")!!.text)
    }

    /** A new thread on the Mac: `complete` says the old rows are not a base any more. */
    @Test
    fun `a complete answer after a new thread drops the old thread's rows`() {
        val session = loaded()
            .applying(itemFrame(item("b", "still the old thread"), seq = 4))
            .applying(detail(emptyList(), seq = 6, complete = true))
        assertTrue(session.transcript.isEmpty())
    }

    // MARK: - Frames

    @Test
    fun `an item frame replaces its row whole, keyed by id`() {
        val session = loaded()
            .applying(itemFrame(item("c", "Run"), seq = 3))
            .applying(itemFrame(item("c", "Running the suite now."), seq = 4))
        assertEquals(listOf("a", "b", "c"), ids(session))
        assertEquals("Running the suite now.", session.item("c")!!.text)
    }

    @Test
    fun `the same frame twice is harmless`() {
        val frame = itemFrame(item("c", "Running the suite now."), seq = 4)
        val once = loaded().applying(frame)
        assertEquals(once, once.applying(frame))
    }

    @Test
    fun `an older frame for a row does not undo a newer one`() {
        val session = loaded()
            .applying(itemFrame(item("c", "finished", status = AgentItem.COMPLETED), seq = 8))
            .applying(itemFrame(item("c", "half way", status = AgentItem.RUNNING), seq = 6))
        assertEquals("finished", session.item("c")!!.text)
        assertEquals(AgentItem.COMPLETED, session.item("c")!!.status)
    }

    /**
     * The same row changed twice between two of the Mac's samples: the stream carries only
     * the second, whole. Nothing is reassembled, so nothing is missing.
     */
    @Test
    fun `a row's frames carry it whole, so a skipped version costs nothing`() {
        val session = loaded()
            .applying(itemFrame(item("c", "Run"), seq = 3))
            .applying(itemFrame(item("c", "Running the suite now, all twelve tests."), seq = 7))
        assertEquals("Running the suite now, all twelve tests.", session.item("c")!!.text)
    }

    @Test
    fun `turn frames out of order leave the newer one standing`() {
        var session = loaded().applying(turnFrame(active = true, seq = 10))
        session = session.applying(turnFrame(active = false, seq = 9))
        assertTrue("a turn frame from before cannot end the turn", session.turnActive)
        session = session.applying(turnFrame(active = false, seq = 11))
        assertFalse(session.turnActive)
    }

    @Test
    fun `a turn ending asks the Mac what else changed, a turn starting does not`() {
        val started = following().applying(turnFrame(active = true, seq = 5))
        assertEquals(Sync.None, started.needs)
        val ended = started.applying(turnFrame(active = false, seq = 6))
        assertEquals(Sync.CatchUp, ended.needs)
    }

    @Test
    fun `state frames out of order leave the newer one standing`() {
        var session = loaded().applying(stateFrame("stopped", seq = 12))
        session = session.applying(stateFrame("running", seq = 11))
        assertEquals("stopped", session.state)
        assertFalse("a stopped engine has no turn", session.turnActive)
    }

    @Test
    fun `the watermark is the highest sequence applied`() {
        val session = loaded()
            .applying(itemFrame(item("c"), seq = 5))
            .applying(itemFrame(item("d"), seq = 3))
            .applying(turnFrame(active = true, seq = 7))
            .applying(detail(listOf(item("e")), seq = 6, complete = false, itemCount = 5))
        assertEquals(7L, session.seq)
    }

    @Test
    fun `a frame for another engine is not this session's`() {
        val session = loaded()
        assertEquals(session, session.applying(itemFrame(item("z"), seq = 40, engine = "pi")))
    }

    @Test
    fun `a kind this build has never heard of is skipped`() {
        val session = loaded()
        val next = session.applying(AgentEvent(engine = "codex", kind = "weather", seq = 30, epoch = epoch))
        assertEquals(session.transcript, next.transcript)
        assertEquals(session.approvals, next.approvals)
    }

    /**
     * The fetch a turn's end asked for is out; the next turn ends before it answers. That
     * second request is about a later moment than the answer, so the answer must not
     * swallow it.
     */
    @Test
    fun `a request raised while a read is out outlives that read's answer`() {
        val session = loaded()
            .applying(turnFrame(active = false, seq = 5))
            .syncing()
            .applying(turnFrame(active = true, seq = 6))
            .applying(turnFrame(active = false, seq = 7))
            .applying(detail(listOf(item("c")), seq = 5, complete = false, itemCount = 3))
        assertEquals(Sync.CatchUp, session.needs)
    }

    // MARK: - A slice that cannot be true

    /** Rows the Mac says it has that this phone does not: something was missed. */
    @Test
    fun `a slice that leaves rows missing asks for the whole transcript`() {
        val session = loaded().applying(detail(emptyList(), seq = 10, complete = false, itemCount = 5))
        assertEquals(Sync.Reload, session.needs)
    }

    /** Rows this phone has that the Mac did not: a transcript that changed underneath. */
    @Test
    fun `a slice that leaves rows the Mac never had asks for the whole transcript`() {
        val session = loaded().applying(detail(emptyList(), seq = 10, complete = false, itemCount = 1))
        assertEquals(Sync.Reload, session.needs)
    }

    @Test
    fun `rows that arrived after the answer do not count against it`() {
        val session = following()
            .applying(itemFrame(item("x"), seq = 14))
            .applying(detail(emptyList(), seq = 10, complete = false, itemCount = 2))
        assertEquals(Sync.None, session.needs)
    }

    // MARK: - Approvals, from both sides

    @Test
    fun `a pending frame puts a card up, and an accepted frame takes it down saying so`() {
        val card = approval("A1")
        var session = loaded().applying(approvalFrame(card, seq = 5))
        assertEquals(listOf("A1"), session.pending.map { it.id })
        session = session.applying(approvalFrame(card, seq = 6, state = AgentEvent.ACCEPTED))
        assertTrue(session.pending.isEmpty())
        val said = session.resolutions.single()
        assertEquals("accepted", said.decision)
        assertFalse("answered at the Mac", said.byThisPhone)
    }

    @Test
    fun `a decline at the Mac comes down too`() {
        val card = approval("A1")
        val session = loaded()
            .applying(approvalFrame(card, seq = 5))
            .applying(approvalFrame(card, seq = 6, state = AgentEvent.DECLINED))
        assertTrue(session.pending.isEmpty())
        assertEquals("declined", session.resolutions.single().decision)
    }

    @Test
    fun `a resolution that overtakes its own pending frame keeps the card down`() {
        val card = approval("A1")
        val session = loaded()
            .applying(approvalFrame(card, seq = 12, state = AgentEvent.ACCEPTED))
            .applying(approvalFrame(card, seq = 11))
        assertTrue(session.pending.isEmpty())
    }

    @Test
    fun `a snapshot taken before a card came down cannot put it back`() {
        val card = approval("A1")
        val session = loaded()
            .applying(approvalFrame(card, seq = 5))
            .applying(approvalFrame(card, seq = 8, state = AgentEvent.ACCEPTED))
            .applying(detail(listOf(item("a"), item("b")), seq = 7, complete = false, approvals = listOf(card)))
        assertTrue(session.pending.isEmpty())
    }

    @Test
    fun `an approval missing from an answer stopped waiting while the phone was not looking`() {
        val card = approval("A1")
        val session = loaded()
            .applying(approvalFrame(card, seq = 4))
            .applying(detail(listOf(item("a"), item("b")), seq = 9, complete = false))
        assertTrue(session.pending.isEmpty())
        val said = session.resolutions.single()
        assertNull("nobody here knows which way it went", said.decision)
        // And it stays down.
        assertTrue(session.applying(approvalFrame(card, seq = 3)).pending.isEmpty())
    }

    @Test
    fun `an approval that appeared after the answer survives it`() {
        val card = approval("A2")
        val session = loaded()
            .applying(approvalFrame(card, seq = 12))
            .applying(detail(listOf(item("a"), item("b")), seq = 10, complete = false))
        assertEquals(listOf("A2"), session.pending.map { it.id })
    }

    @Test
    fun `the answer's approvals are the whole set waiting`() {
        val session = loaded().applying(
            detail(
                listOf(item("a"), item("b")), seq = 10, complete = false,
                approvals = listOf(approval("A1"), approval("A2")),
            ),
        )
        assertEquals(listOf("A1", "A2"), session.pending.map { it.id })
        assertEquals(2, session.pendingCount)
    }

    @Test
    fun `this phone's answer takes the card down, and the Mac's frame afterwards adds nothing`() {
        val card = approval("A1")
        var session = loaded().applying(approvalFrame(card, seq = 5))
        session = session.answered("A1", "accepted", summary(itemCount = 2))
        assertTrue(session.pending.isEmpty())
        assertTrue(session.resolutions.single().byThisPhone)
        session = session.applying(approvalFrame(card, seq = 6, state = AgentEvent.ACCEPTED))
        assertEquals(1, session.resolutions.size)
        assertTrue(session.pending.isEmpty())
    }

    @Test
    fun `a 404 takes the card down quietly`() {
        val session = loaded().applying(approvalFrame(approval("A1"), seq = 5)).approvalGone("A1")
        assertTrue(session.pending.isEmpty())
        assertTrue("quietly: nothing to say about it", session.resolutions.isEmpty())
        assertTrue(session.applying(approvalFrame(approval("A1"), seq = 4)).pending.isEmpty())
    }

    /**
     * A 409: answered at the Mac first. The Mac's sentence goes on the card, the phone asks
     * what is true now, and when the answer no longer lists it the card comes down still
     * carrying that sentence.
     */
    @Test
    fun `a 409 leaves the Mac's sentence on the card until the Mac says it is gone`() {
        val sentence = "That was answered at the Mac before this arrived. The agent already has its decision; nothing was sent twice."
        var session = loaded().applying(approvalFrame(approval("A1"), seq = 5)).syncing()
        session = session.approvalConflict("A1", sentence)
        assertEquals(listOf("A1"), session.pending.map { it.id })
        assertEquals(sentence, session.notes["A1"])
        assertEquals(Sync.CatchUp, session.needs)

        session = session.applying(detail(listOf(item("a"), item("b")), seq = 9, complete = false))
        assertTrue(session.pending.isEmpty())
        assertEquals(sentence, session.resolutions.single().note)
        assertTrue(session.notes.isEmpty())
    }

    @Test
    fun `a 409 for a card that is still waiting keeps it up, with the sentence`() {
        val card = approval("A1")
        val session = loaded()
            .applying(approvalFrame(card, seq = 5))
            .approvalConflict("A1", "Still screening.")
            .applying(detail(listOf(item("a"), item("b")), seq = 9, complete = false, approvals = listOf(card)))
        assertEquals(listOf("A1"), session.pending.map { it.id })
        assertEquals("Still screening.", session.notes["A1"])
    }

    @Test
    fun `before the whole transcript is read, the summary's count stands in`() {
        val board = AgentBoard.empty.applying(
            AgentSessionList(listOf(summary(pending = 2), summary(engine = "pi", state = "stopped"))),
        )
        assertEquals(2, board.session("codex").pendingCount)
        assertEquals(2, board.pendingTotal)
        // Read whole with one waiting: the list, not the older count, is the truth now.
        val read = board.updating("codex") {
            it.applying(detail(emptyList(), seq = 3, complete = true, approvals = listOf(approval("A1"))))
        }
        assertEquals(1, read.pendingTotal)
    }

    @Test
    fun `frames go to the session they name`() {
        val board = AgentBoard.empty
            .applying(AgentSessionList(listOf(summary(), summary(engine = "pi"))))
            .applying(AgentEvent(engine = "pi", kind = AgentEvent.APPROVAL, seq = 4, epoch = epoch, approval = approval("P1"), state = AgentEvent.PENDING))
            .applying(approvalFrame(approval("C1"), seq = 9))
        assertEquals(listOf("P1"), board.session("pi").pending.map { it.id })
        assertEquals(listOf("C1"), board.session("codex").pending.map { it.id })
        assertEquals(2, board.pendingTotal)
        assertEquals(listOf("codex", "pi"), board.engines)
    }

    @Test
    fun `a summary from a verb is taken whole`() {
        val session = loaded().applying(turnFrame(active = true, seq = 8))
            .summarized(summary(state = "stopped", turnActive = false))
        assertEquals("stopped", session.state)
        assertFalse(session.turnActive)
        // And a frame older than what it said cannot undo it.
        assertEquals("stopped", session.applying(stateFrame("running", seq = 7)).state)
    }

    @Test
    fun `a detail's summary does not undo a newer turn frame`() {
        val session = loaded()
            .applying(turnFrame(active = true, seq = 12))
            .applying(detail(listOf(item("a"), item("b")), seq = 10, complete = false, turnActive = false))
        assertTrue(session.turnActive)
    }

    // MARK: - Epochs: which transcript the rows belong to

    @Test
    fun `a reset drops the rows and the cards and takes the new epoch`() {
        val session = loaded()
            .applying(approvalFrame(approval("A1"), seq = 5))
            .applying(resetFrame(seq = 8))
        assertTrue(session.transcript.isEmpty())
        assertTrue(session.pending.isEmpty())
        assertTrue("nobody answered them: no line about it", session.resolutions.isEmpty())
        assertTrue(
            "how the old thread's cards came down is the old thread's news",
            loaded().applying(approvalFrame(approval("A2"), seq = 5))
                .applying(approvalFrame(approval("A2"), seq = 6, state = AgentEvent.ACCEPTED))
                .applying(resetFrame(seq = 8)).resolutions.isEmpty(),
        )
        assertEquals(nextEpoch, session.epoch)
        assertEquals(nextEpoch, session.summary!!.epoch)
        assertFalse(session.turnActive)
        assertEquals("the Mac is asked for the rest", Sync.CatchUp, session.needs)
        // And the old thread's card cannot come back.
        assertTrue(session.applying(approvalFrame(approval("A1"), seq = 4)).pending.isEmpty())
    }

    @Test
    fun `frames after a reset build the new transcript from empty`() {
        val session = loaded()
            .applying(resetFrame(seq = 8))
            .applying(itemFrame(item("n1", "a new thread", kind = AgentItem.USER), seq = 9, epoch = nextEpoch))
        assertEquals(listOf("n1"), ids(session))
        assertEquals(9L, session.cursor!!.since)
        assertEquals(nextEpoch, session.cursor!!.epoch)
    }

    /** The Mac relaunched and nobody saw a reset: the frame is about something else. */
    @Test
    fun `a frame from another epoch is not merged, and the transcript is read again`() {
        val session = loaded().applying(itemFrame(item("z", "from another launch"), seq = 3, epoch = nextEpoch))
        assertEquals(listOf("a", "b"), ids(session))
        assertEquals(Sync.Reload, session.needs)
        assertEquals("a reload has no cursor", null, session.cursor)
    }

    @Test
    fun `an answer from another epoch replaces everything held, cards included`() {
        val session = loaded()
            .applying(approvalFrame(approval("A1"), seq = 5))
            .applying(itemFrame(item("x"), seq = 9))
            .applying(detail(listOf(item("n1")), seq = 3, complete = true, epoch = nextEpoch))
        assertEquals(listOf("n1"), ids(session))
        assertTrue(session.pending.isEmpty())
        assertEquals(nextEpoch, session.epoch)
        assertEquals("the new transcript's own numbers", 3L, session.cursor!!.since)
    }

    @Test
    fun `a slice under another epoch is not merged`() {
        val session = loaded().applying(detail(listOf(item("q")), seq = 30, complete = false, epoch = nextEpoch))
        assertEquals(listOf("a", "b"), ids(session))
        assertEquals(Sync.Reload, session.needs)
    }

    @Test
    fun `a summary under another epoch says the rows are from something gone`() {
        val session = loaded().summarized(summary(epoch = nextEpoch))
        assertEquals(Sync.Reload, session.needs)
    }

    // MARK: - The cursor: how far the rows are known to be complete

    /**
     * The fetch was answered at 30; the stream opened at 41 with state, turn and approvals
     * but no rows. Rows that changed between 30 and 41 are in neither, so the cursor stays
     * at 30 and the session asks for them.
     */
    @Test
    fun `a stream that opened after the fetch leaves a gap the cursor does not skip`() {
        val session = AgentSession("codex")
            .applying(detail(listOf(item("a")), seq = 30, complete = true))
            .syncing()
            .applying(turnFrame(active = true, seq = 41))
        assertEquals(41L, session.seq)
        assertEquals("rows are known to 30, not 41", 30L, session.cursor!!.since)
        assertEquals(Sync.CatchUp, session.needs)

        // The catch-up fills the gap, and from there the frames carry the cursor.
        val caughtUp = session.syncing()
            .applying(detail(listOf(item("b")), seq = 42, complete = false, itemCount = 2))
            .applying(itemFrame(item("c"), seq = 43))
        assertEquals(43L, caughtUp.cursor!!.since)
        assertEquals(Sync.None, caughtUp.needs)
    }

    /** Fetched after the stream opened: nothing falls between the two. */
    @Test
    fun `a fetch answered after the stream opened needs nothing more`() {
        val session = AgentSession("codex")
            .applying(turnFrame(active = true, seq = 41))
            .syncing()
            .applying(detail(listOf(item("a")), seq = 44, complete = true, turnActive = true))
            .applying(itemFrame(item("b"), seq = 45))
        assertEquals(Sync.None, session.needs)
        assertEquals(45L, session.cursor!!.since)
    }

    @Test
    fun `after the stream breaks, frames no longer move the cursor`() {
        var session = following().applying(itemFrame(item("c"), seq = 3))
        assertEquals(3L, session.cursor!!.since)
        session = session.streamBroken()
        assertEquals(Sync.CatchUp, session.needs)
        // The stream is back; this frame is not known to follow on from 3.
        session = session.syncing().applying(itemFrame(item("d"), seq = 9))
        assertEquals(3L, session.cursor!!.since)
        assertEquals(Sync.CatchUp, session.needs)
        assertEquals(listOf("a", "b", "c", "d"), ids(session))
    }

    /**
     * A slow phone: the Mac dropped frames 11 to 14 for it and says so with a `resync` at
     * exactly that point. The cursor is still the last frame read before the gap, so the
     * catch-up it asks with returns just what was dropped — and the newer frames that
     * arrived after the resync do not move it past the hole.
     */
    @Test
    fun `a resync at the gap leaves the cursor just before it`() {
        var session = following()
            .applying(itemFrame(item("c", "as of ten"), seq = 10))
        assertEquals(10L, session.cursor!!.since)
        session = session.streamBroken()
            .applying(itemFrame(item("e", "after the gap"), seq = 15))
            .applying(itemFrame(item("f", "after the gap too"), seq = 16))
        assertEquals("fetch from just before the gap", 10L, session.cursor!!.since)
        assertEquals(Sync.CatchUp, session.needs)

        // What was dropped: `c` changed at 12, `d` appeared at 13.
        session = session.syncing().applying(
            detail(listOf(item("c", "as of twelve"), item("d")), seq = 16, complete = false, itemCount = 6),
        )
        assertEquals(listOf("a", "b", "c", "e", "f", "d"), ids(session))
        assertEquals("as of twelve", session.item("c")!!.text)
        assertEquals(16L, session.cursor!!.since)
        assertEquals(Sync.None, session.needs)
    }

    @Test
    fun `nothing is a cursor until the whole transcript has been read`() {
        val session = AgentSession("codex").applying(turnFrame(active = true, seq = 4))
        assertEquals(null, session.cursor)
        assertFalse(session.loaded)
    }

    // MARK: - Rows left out by the limit

    @Test
    fun `rows the Mac left out are counted, and do not make a slice look wrong`() {
        val session = AgentSession("codex")
            .applying(detail(listOf(item("r501"), item("r502")), seq = 600, complete = true, itemCount = 502, omitted = 500))
        assertEquals(500, session.omitted)
        val later = session.applying(detail(listOf(item("r503")), seq = 610, complete = false, itemCount = 503))
        assertEquals(listOf("r501", "r502", "r503"), ids(later))
        assertEquals(Sync.None, later.needs)
    }

    // MARK: - Review round: the orders the first cut got wrong

    /**
     * An old row changed after the catch-up was taken, and its frame beat the answer. The
     * slice's new row belongs after it — the old row keeps its place in the transcript.
     */
    @Test
    fun `a slice places its new rows after old rows the stream changed meanwhile`() {
        val session = following()
            .streamBroken()
            .applying(turnFrame(active = true, seq = 20))
            .syncing()
            .applying(itemFrame(item("b", "b, changed at 25"), seq = 25))
            .applying(detail(listOf(item("c")), seq = 21, complete = false, itemCount = 3))
        assertEquals(listOf("a", "b", "c"), ids(session))
        assertEquals("b, changed at 25", session.item("b")!!.text)
    }

    /** New rows the stream brought after the answer stay after the answer's new rows. */
    @Test
    fun `a slice's new rows go before rows first seen after it was taken`() {
        val session = following()
            .streamBroken()
            .applying(turnFrame(active = true, seq = 20))
            .syncing()
            .applying(itemFrame(item("e", "first seen at 25"), seq = 25))
            .applying(detail(listOf(item("c"), item("d")), seq = 21, complete = false, itemCount = 4))
        assertEquals(listOf("a", "b", "c", "d", "e"), ids(session))
    }

    /**
     * The mutation the review left standing: answering here must settle the card, or a read
     * taken before the answer — already on its way — puts it back.
     */
    @Test
    fun `a card this phone answered cannot be put back by a read taken before the answer`() {
        val card = approval("A1")
        val session = AgentSession("codex")
            .applying(detail(listOf(item("a")), seq = 5, complete = true, approvals = listOf(card)))
            .answered("A1", "accepted")
            .applying(detail(listOf(item("a")), seq = 5, complete = false, approvals = listOf(card), itemCount = 1))
        assertTrue(session.pending.isEmpty())
        assertEquals(1, session.resolutions.size)
    }

    /** Recorded before sending, so the frame that wins the race credits this phone. */
    @Test
    fun `an answer recorded before sending is this phone's when the frame arrives first`() {
        val card = approval("A1")
        val session = following()
            .applying(approvalFrame(card, seq = 5))
            .answering("A1", "accept")
            .applying(approvalFrame(card, seq = 6, state = AgentEvent.ACCEPTED))
        val said = session.resolutions.single()
        assertTrue(said.byThisPhone)
        assertEquals("accepted", said.decision)
    }

    /** An answer that never got through is not credited to this phone. */
    @Test
    fun `an answer that failed is not this phone's`() {
        val card = approval("A1")
        val session = following()
            .applying(approvalFrame(card, seq = 5))
            .answering("A1", "accept")
            .unanswering("A1")
            .applying(approvalFrame(card, seq = 6, state = AgentEvent.ACCEPTED))
        assertFalse(session.resolutions.single().byThisPhone)
    }

    /** A card this phone answered from a notification, gone by the time the app reads. */
    @Test
    fun `a card that vanished while away is credited to this phone when it answered it`() {
        val card = approval("A1")
        val session = following()
            .applying(approvalFrame(card, seq = 5))
            .remembering(mapOf("A1" to "declined"))
            .applying(detail(listOf(item("a"), item("b")), seq = 9, complete = false))
        val said = session.resolutions.single()
        assertTrue(said.byThisPhone)
        assertEquals("declined", said.decision)
    }

    /**
     * Back from the background: the stream restarts, the catch-up goes out, and the
     * stream's opening lands while it is out. An answer taken at or after the opening is
     * the whole story, so it is one read per engine, not two.
     */
    @Test
    fun `the opening that lands while its catch-up is out does not ask again`() {
        val session = following()
            .streamBroken()
            .syncing()
            .applying(turnFrame(active = false, seq = 9))
            .applying(detail(listOf(item("c")), seq = 9, complete = false, itemCount = 3))
        assertEquals(Sync.None, session.needs)
        assertEquals(9L, session.cursor!!.since)
    }

    /** But an opening newer than the answer does ask again: the gap is real. */
    @Test
    fun `an opening newer than the answer still asks for the gap`() {
        val session = following()
            .streamBroken()
            .syncing()
            .applying(turnFrame(active = true, seq = 12))
            .applying(detail(listOf(item("c")), seq = 9, complete = false, itemCount = 3))
        assertEquals(Sync.CatchUp, session.needs)
        assertEquals(9L, session.cursor!!.since)
    }

    /** A request with no frame behind it waits for a read that starts after it. */
    @Test
    fun `a verb's request is not satisfied by a read already out`() {
        val session = following()
            .streamBroken()
            .syncing()
            .needing(Sync.CatchUp)
            .applying(detail(listOf(item("c")), seq = 50, complete = false, itemCount = 3))
        assertEquals(Sync.CatchUp, session.needs)
    }

    /** P2's other half: after a relaunch the new transcript's first frame opens a new run. */
    @Test
    fun `after a relaunch the new transcript's stream starts its own run`() {
        var session = AgentSession("codex")
            .applying(detail(listOf(item("a")), seq = 400, complete = true))
            .applying(turnFrame(active = false, seq = 400))
            .syncing()
            .applying(detail(listOf(item("x")), seq = 3, complete = true, epoch = nextEpoch))
        assertEquals(Sync.None, session.needs)
        // The new stream opens at the new clock's current number, and follows on from there.
        session = session
            .applying(AgentEvent(engine = "codex", kind = AgentEvent.TURN, seq = 3, epoch = nextEpoch, turnActive = false))
            .applying(itemFrame(item("y"), seq = 4, epoch = nextEpoch))
        assertEquals(Sync.None, session.needs)
        assertEquals(listOf("x", "y"), ids(session))
        assertEquals(4L, session.cursor!!.since)
    }

    /** Every stream opens by restating each engine's state and turn; that alone is no news. */
    @Test
    fun `a stream's opening that restates what is known asks the Mac nothing`() {
        val session = following()
            .applying(stateFrame("running", seq = 2))
            .applying(turnFrame(active = false, seq = 2))
        assertEquals(Sync.None, session.needs)
        // A real change still asks.
        assertEquals(Sync.CatchUp, session.applying(stateFrame("failed", seq = 3)).needs)
    }
}
