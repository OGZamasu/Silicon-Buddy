package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentAnswers
import dev.siliconoptimizer.buddy.agents.AgentsViewModel
import dev.siliconoptimizer.buddy.agents.Sync
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentApprovalResult
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentMessageAccepted
import dev.siliconoptimizer.buddy.transport.AgentMessageRequest
import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionList
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Agents tab's asking: when it reads, what it does with a refusal, and whose answer an
 * answer is. The reducer under it has its own tests; these are about the calls.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val epoch = "E1"

    private fun summary(turnActive: Boolean = true, pending: Int = 1) = AgentSessionSummary(
        engine = "codex", state = "running", epoch = epoch, model = "m",
        modelChoices = listOf(AgentModelChoice("m", "M", "This Mac")), cwd = "~/x",
        approvalMode = "screened", sandbox = "workspace-write", turnActive = turnActive,
        pendingApprovals = pending, itemCount = 1, updatedAt = "2026-09-19T10:12:44Z",
    )

    private val card = AgentApproval(
        id = "A1", kind = "command", summary = "swift test",
        screening = AgentScreening("confirm", "Jev: review"), requestedAt = "2026-09-19T10:12:36Z",
    )

    private fun detail(seq: Long, approvals: List<AgentApproval> = listOf(card), complete: Boolean = true) =
        AgentSessionDetail(
            session = summary(pending = approvals.size),
            items = listOf(AgentItem(id = "a", kind = "user", text = "hi", at = "2026-09-19T10:12:31Z")),
            approvals = approvals, seq = seq, epoch = epoch, complete = complete,
        )

    /** A Mac that answers what the test tells it to, and counts what it was asked. */
    private inner class Mac : HangingTransport() {
        val reads = mutableListOf<Long?>()
        val answers = mutableListOf<String>()
        var sends = 0
        var next: () -> AgentSessionDetail = { detail(5) }
        var readError: TransportError? = null
        var answerError: TransportError? = null
        var sendError: TransportError? = null
        var gate: CompletableDeferred<Unit>? = null
        var duringAnswer: () -> Unit = {}

        override suspend fun agentSessions() = AgentSessionList(listOf(summary()))

        override suspend fun agentSession(engine: String, since: Long?, epoch: String?): AgentSessionDetail {
            reads += since
            gate?.await()
            readError?.let { throw it }
            return next()
        }

        override suspend fun answerAgentApproval(engine: String, id: String, decision: String): AgentApprovalResult {
            answers += decision
            duringAnswer()
            answerError?.let { throw it }
            return AgentApprovalResult(id, if (decision == "accept") "accepted" else "declined", summary(pending = 0))
        }

        override suspend fun sendAgentMessage(engine: String, request: AgentMessageRequest): AgentMessageAccepted {
            sends++
            sendError?.let { throw it }
            return AgentMessageAccepted("row")
        }
    }

    private lateinit var model: AgentsViewModel
    private lateinit var mac: Mac

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AgentAnswers.forget()
        model = AgentsViewModel()
        mac = Mac()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        AgentAnswers.forget()
    }

    private fun frame(kind: String, seq: Long, state: String? = null, turnActive: Boolean? = null, approval: AgentApproval? = null) =
        AgentEvent(engine = "codex", kind = kind, seq = seq, epoch = epoch, state = state, turnActive = turnActive, approval = approval)

    /** Refresh, read whole, and the stream open at the answer's number. */
    private fun opened() {
        model.refresh(mac)
        model.apply(frame(AgentEvent.TURN, 5, turnActive = true))
    }

    @Test
    fun `refreshing reads each session whole`() = runTest(dispatcher) {
        opened()
        assertEquals(listOf<Long?>(null), mac.reads)
        assertEquals(listOf("A1"), model.board.session("codex").pending.map { it.id })
        assertEquals(1, model.board.pendingTotal)
    }

    /** 401: retrying cannot mend it, so nothing is asked again and the tab offers to pair. */
    @Test
    fun `a revoked phone stops reading and is told to pair again`() = runTest(dispatcher) {
        opened()
        mac.readError = TransportError.Unauthorized
        model.streamBroken()
        assertTrue(model.unpaired)
        assertEquals(AgentsViewModel.UNPAIRED, model.unavailable)
        val readsSoFar = mac.reads.size
        // Frames keep coming; none of them asks the Mac for anything any more.
        model.apply(frame(AgentEvent.TURN, 9, turnActive = false))
        model.streamBroken()
        assertEquals(readsSoFar, mac.reads.size)
        // And the card's buttons do nothing.
        model.answer("codex", "A1", accept = true)
        assertTrue(mac.answers.isEmpty())
    }

    @Test
    fun `a 401 answering a card is the same end`() = runTest(dispatcher) {
        opened()
        mac.answerError = TransportError.Unauthorized
        model.answer("codex", "A1", accept = true)
        assertTrue(model.unpaired)
        assertTrue("no credit for an answer that did not land", AgentAnswers.of("codex").isEmpty())
    }

    @Test
    fun `a 403 says why in the Mac's words and is not an unpairing`() = runTest(dispatcher) {
        opened()
        mac.readError = TransportError.Forbidden("This device is paired for chat only.")
        model.streamBroken()
        assertFalse(model.unpaired)
        assertEquals("This device is paired for chat only.", model.unavailable)
    }

    /** The frame that says the card was answered can beat the reply; it is still this phone's. */
    @Test
    fun `this phone's answer is credited to it when the frame arrives first`() = runTest(dispatcher) {
        opened()
        mac.duringAnswer = { model.apply(frame(AgentEvent.APPROVAL, 6, state = AgentEvent.ACCEPTED, approval = card)) }
        model.answer("codex", "A1", accept = true)
        val said = model.board.session("codex").resolutions.single()
        assertTrue(said.byThisPhone)
        assertEquals("accepted", said.decision)
        assertTrue(model.board.session("codex").pending.isEmpty())
    }

    /**
     * The answer landed — the Mac's frame says so — and the reply to it was lost on the way
     * back. It was still this phone's, which only the record made before sending can tell.
     */
    @Test
    fun `an answer whose reply was lost is still this phone's`() = runTest(dispatcher) {
        opened()
        mac.duringAnswer = { model.apply(frame(AgentEvent.APPROVAL, 6, state = AgentEvent.ACCEPTED, approval = card)) }
        mac.answerError = TransportError.Unreachable("100.64.0.9")
        model.answer("codex", "A1", accept = true)
        assertTrue(model.board.session("codex").resolutions.single().byThisPhone)
        assertFalse(
            "the problem line is not shown for a card that came down",
            model.board.session("codex").pending.any { it.id == "A1" },
        )
    }

    @Test
    fun `a 409 leaves the Mac's sentence on the card and reads again`() = runTest(dispatcher) {
        opened()
        mac.answerError = TransportError.Conflict("That was answered at the Mac before this arrived.")
        mac.next = { detail(7, approvals = emptyList(), complete = false) }
        model.answer("codex", "A1", accept = false)
        assertEquals(listOf<Long?>(null, 5L), mac.reads)
        val said = model.board.session("codex").resolutions.single()
        assertFalse(said.byThisPhone)
        assertEquals("That was answered at the Mac before this arrived.", said.note)
    }

    @Test
    fun `a 404 takes the card down quietly`() = runTest(dispatcher) {
        opened()
        mac.answerError = TransportError.NotFound("gone")
        model.answer("codex", "A1", accept = true)
        assertTrue(model.board.session("codex").pending.isEmpty())
        assertTrue(model.board.session("codex").resolutions.isEmpty())
        assertNull(model.cardProblems["A1"])
    }

    /** One read per engine when the stream comes back, even with its opening landing mid-read. */
    @Test
    fun `a return is one catch-up, not two`() = runTest(dispatcher) {
        opened()
        mac.gate = CompletableDeferred()
        mac.next = { detail(9, complete = false) }
        model.streamBroken()
        assertEquals(2, mac.reads.size)
        model.apply(frame(AgentEvent.TURN, 9, turnActive = true))
        mac.gate!!.complete(Unit)
        assertEquals("the read already out answered the opening", 2, mac.reads.size)
        assertEquals(Sync.None, model.board.session("codex").needs)
    }

    @Test
    fun `codex's send mid-turn is refused with the way out`() = runTest(dispatcher) {
        opened()
        mac.sendError = TransportError.Conflict("Codex is still working on the last message. Wait for the turn to end, or stop it.")
        model.drafts["codex"] = "and another thing"
        model.send("codex")
        assertTrue(model.sendRefused["codex"]!!.contains("stop it"))
        assertEquals("the draft is kept", "and another thing", model.drafts["codex"])
    }

    @Test
    fun `a tap through another app's window sends nothing and says why`() = runTest(dispatcher) {
        opened()
        model.refuseObscured("A1")
        assertEquals(AgentsViewModel.OBSCURED, model.cardProblems["A1"])
        assertTrue(mac.answers.isEmpty())
    }
}
