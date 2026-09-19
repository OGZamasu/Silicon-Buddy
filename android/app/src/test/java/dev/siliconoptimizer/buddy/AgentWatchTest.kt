package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.agents.AgentAnswers
import dev.siliconoptimizer.buddy.agents.AgentNotifications
import dev.siliconoptimizer.buddy.agents.AgentWatch
import dev.siliconoptimizer.buddy.agents.ApprovalNotice
import dev.siliconoptimizer.buddy.agents.ApprovalReplies
import dev.siliconoptimizer.buddy.agents.ShadeAnswer
import dev.siliconoptimizer.buddy.agents.WatchEnding
import dev.siliconoptimizer.buddy.agents.WatchPolicy
import dev.siliconoptimizer.buddy.agents.WatchSink
import dev.siliconoptimizer.buddy.agents.answerFromShade
import dev.siliconoptimizer.buddy.agents.knownEngine
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentApprovalResult
import dev.siliconoptimizer.buddy.transport.AgentEvent
import dev.siliconoptimizer.buddy.transport.AgentItem
import dev.siliconoptimizer.buddy.transport.AgentModelChoice
import dev.siliconoptimizer.buddy.transport.AgentScreening
import dev.siliconoptimizer.buddy.transport.AgentSessionDetail
import dev.siliconoptimizer.buddy.transport.AgentSessionSummary
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The background watcher, apart from Android: when it stops believing in the Mac, how it
 * follows a turn from the first read to the end, and what a button in the shade sends and
 * says came of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentWatchTest {

    @Before
    fun forgetAnswers() = AgentAnswers.forget()

    @After
    fun forgetAnswersAgain() = AgentAnswers.forget()

    private val t0 = 1_000_000L

    // MARK: - When the watcher lets go

    /** One failed reconnect used to end the watch; a relaunch or a network change is seconds. */
    @Test
    fun `a drop is not the end`() {
        val policy = WatchPolicy(t0)
        policy.live(t0)
        policy.dropped()
        assertFalse(policy.givesUp(t0 + 1_100))
        assertFalse(policy.givesUp(t0 + 45_000))
        assertFalse(policy.givesUp(t0 + WatchPolicy.GRACE_MS - 1))
        assertTrue(policy.givesUp(t0 + WatchPolicy.GRACE_MS))
    }

    @Test
    fun `anything live starts the grace again`() {
        val policy = WatchPolicy(t0)
        policy.dropped()
        policy.live(t0 + 50_000)
        assertFalse(policy.down)
        policy.dropped()
        assertFalse(policy.givesUp(t0 + 100_000))
        assertTrue(policy.givesUp(t0 + 50_000 + WatchPolicy.GRACE_MS))
    }

    @Test
    fun `a stream that is up is never given up on`() {
        val policy = WatchPolicy(t0)
        assertFalse(policy.givesUp(t0 + 10 * 60_000))
    }

    /** A Mac that never answered at all is given the same minute from when watching began. */
    @Test
    fun `the grace counts from the start when nothing ever arrived`() {
        val policy = WatchPolicy(t0)
        policy.dropped()
        assertFalse(policy.givesUp(t0 + 30_000))
        assertTrue(policy.givesUp(t0 + 60_000))
    }

    @Test
    fun `a revoked or narrowed pairing ends the watch for what it is`() {
        assertEquals(WatchEnding.Unpaired, WatchPolicy.ending(TransportError.Unauthorized))
        assertEquals(WatchEnding.NotAllowed, WatchPolicy.ending(TransportError.Forbidden("chat only")))
        assertEquals(WatchEnding.LostTouch, WatchPolicy.ending(TransportError.Unreachable("100.64.0.9")))
        assertEquals(WatchEnding.LostTouch, WatchPolicy.ending(TransportError.TimedOut))
    }

    @Test
    fun `only the endings worth knowing about are said`() {
        assertTrue(WatchPolicy.sentence(WatchEnding.Unpaired)!!.contains("no longer paired"))
        assertTrue(WatchPolicy.sentence(WatchEnding.LostTouch)!!.contains("stopped answering"))
        assertTrue(WatchPolicy.sentence(WatchEnding.NotAllowed)!!.contains("full control"))
        assertNull(WatchPolicy.sentence(WatchEnding.TurnEnded))
        assertNull(WatchPolicy.sentence(WatchEnding.Dismissed))
        assertNull(WatchPolicy.sentence(WatchEnding.AppReturned))
    }

    // MARK: - What a button in the shade came to

    @Test
    fun `an answer that landed says whose it was`() {
        assertEquals(ApprovalReplies.ACCEPTED, ApprovalReplies.forDecision("accepted"))
        assertEquals(ApprovalReplies.DECLINED, ApprovalReplies.forDecision("declined"))
    }

    /** Never the error's own words: `Unreachable` carries the Mac's address by construction. */
    @Test
    fun `a failure is one fixed sentence, never the error's message`() {
        val unreachable = TransportError.Unreachable("100.64.0.9")
        assertTrue(unreachable.message!!.contains("100.64.0.9"))
        val said = ApprovalReplies.forError(unreachable)!!
        assertEquals(ApprovalReplies.UNREACHABLE, said)
        assertFalse(said.contains("100."))

        assertEquals(ApprovalReplies.UNPAIRED, ApprovalReplies.forError(TransportError.Unauthorized))
        assertEquals(ApprovalReplies.NOT_ALLOWED, ApprovalReplies.forError(TransportError.Forbidden("anything the Mac wrote")))
        assertEquals(ApprovalReplies.TOO_SLOW, ApprovalReplies.forError(TransportError.TimedOut))
        assertEquals(ApprovalReplies.UNREACHABLE, ApprovalReplies.forError(TransportError.AppNotRunning))
        assertEquals(ApprovalReplies.UNREACHABLE, ApprovalReplies.forError(TransportError.Server(500, "stack trace from 100.64.0.9")))
        assertNull("a 404 is a card that is simply gone", ApprovalReplies.forError(TransportError.NotFound("gone")))
    }

    /** A 409 on this route is several things; the Mac's sentence is read, never repeated. */
    @Test
    fun `a conflict is classified from the Mac's words, not echoed`() {
        assertEquals(
            ApprovalReplies.ANSWERED_ON_THE_MAC,
            ApprovalReplies.forError(TransportError.Conflict("That was answered at the Mac before this arrived. The agent already has its decision; nothing was sent twice.")),
        )
        assertEquals(
            ApprovalReplies.STILL_SCREENING,
            ApprovalReplies.forError(TransportError.Conflict("Jev is still screening that call. It can be answered once the verdict is in.")),
        )
        assertEquals(
            ApprovalReplies.ENGINE_STOPPED,
            ApprovalReplies.forError(TransportError.Conflict("Pi is not running. Start it with POST /agent/sessions/pi/start and try again.")),
        )
        val odd = ApprovalReplies.forError(TransportError.Conflict("<b>see http://100.64.0.9:8788/debug</b>"))!!
        assertEquals(ApprovalReplies.NOT_TAKEN, odd)
        assertFalse(odd.contains("100."))
    }

    @Test
    fun `every sentence the shade can show is free of addresses and paths`() {
        val all = listOf(
            ApprovalReplies.ACCEPTED, ApprovalReplies.DECLINED, ApprovalReplies.LOCKED,
            ApprovalReplies.NO_MAC, ApprovalReplies.UNPAIRED, ApprovalReplies.NOT_ALLOWED,
            ApprovalReplies.ANSWERED_ON_THE_MAC, ApprovalReplies.STILL_SCREENING,
            ApprovalReplies.ENGINE_STOPPED, ApprovalReplies.NOT_TAKEN, ApprovalReplies.UNREACHABLE,
            ApprovalReplies.TOO_SLOW,
        ) + WatchEnding.entries.mapNotNull { WatchPolicy.sentence(it) }
        for (sentence in all) {
            assertFalse(sentence, sentence.contains("100."))
            assertFalse(sentence, sentence.contains("/agent"))
            assertFalse(sentence, sentence.contains("http"))
        }
        assertTrue(ApprovalReplies.LOCKED.contains("Unlock"))
    }

    /** An intent names an engine; one this build does not know is refused, not trusted. */
    @Test
    fun `only the engines this build knows are accepted from an intent`() {
        assertEquals("codex", knownEngine("codex"))
        assertEquals("pi", knownEngine("pi"))
        assertNull(knownEngine("../status"))
        assertNull(knownEngine("claude"))
        assertNull(knownEngine(null))
    }

    // MARK: - The watch, from the first read to the end

    private val epoch = "E1"

    private fun approval(id: String) = AgentApproval(
        id = id, kind = "command", summary = "swift test --filter Lisbon",
        screening = AgentScreening("confirm", "Jev: runs the tests"), requestedAt = "2026-09-19T10:12:36Z",
    )

    private fun summary(engine: String, turnActive: Boolean, pending: Int) = AgentSessionSummary(
        engine = engine, state = "running", epoch = epoch, model = "m",
        modelChoices = listOf(AgentModelChoice("m", "M", "This Mac")), cwd = "~/x",
        approvalMode = "screened", sandbox = "workspace-write", turnActive = turnActive,
        pendingApprovals = pending, itemCount = 1, updatedAt = "2026-09-19T10:12:44Z",
    )

    /** A Mac whose sessions move as the test says, answering reads from where they are now. */
    private inner class Mac : HangingTransport() {
        val frames = Channel<ServerEvent>(Channel.UNLIMITED)
        var opened = 0
        val reads = mutableListOf<String>()
        var seq = 5L
        var turnActive = true
        var approvals = listOf(approval("A1"))
        var readError: TransportError? = null

        override fun events(): Flow<ServerEvent> = flow {
            opened++
            for (event in frames) emit(event)
        }

        override suspend fun agentSession(engine: String, since: Long?, epoch: String?): AgentSessionDetail {
            reads += "$engine@$since"
            readError?.let { throw it }
            return AgentSessionDetail(
                session = summary(engine, turnActive, approvals.size),
                items = if (since == null) {
                    listOf(AgentItem(id = "u1", kind = "user", text = "run the tests", at = "2026-09-19T10:12:31Z"))
                } else {
                    emptyList()
                },
                approvals = approvals, seq = seq, epoch = this@AgentWatchTest.epoch, complete = since == null,
            )
        }

        /**
         * The opening frame a stream starts with: the turn as it stands, at the Mac's
         * current number — it restates, it does not move anything on.
         */
        fun opening() = ServerEvent.Agent(
            AgentEvent(engine = "codex", kind = AgentEvent.TURN, seq = seq, epoch = this@AgentWatchTest.epoch, turnActive = turnActive),
        )

        /** Something happening on the Mac: the next number. */
        fun frame(kind: String, turnActive: Boolean? = null, approval: AgentApproval? = null, state: String? = null, engine: String = "codex"): ServerEvent {
            seq += 1
            return ServerEvent.Agent(
                AgentEvent(
                    engine = engine, kind = kind, seq = seq, epoch = this@AgentWatchTest.epoch,
                    approval = approval, turnActive = turnActive, state = state,
                ),
            )
        }

        fun endTurn(): ServerEvent {
            turnActive = false
            return frame(AgentEvent.TURN, turnActive = false)
        }
    }

    /** The shade, as far as the watch can see it. */
    private class Shade : WatchSink {
        val posted = mutableListOf<ApprovalNotice>()
        val cancelled = mutableListOf<Int>()
        val counts = mutableListOf<Int>()

        override fun post(notice: ApprovalNotice) {
            posted += notice
        }

        override fun cancel(notificationID: Int) {
            cancelled += notificationID
        }

        override fun waiting(count: Int, changed: Boolean) {
            if (changed) counts += count
        }
    }

    private fun TestScope.watching(mac: Mac, shade: Shade, vararg engines: String = arrayOf("codex")): Pair<AgentWatch, Deferred<WatchEnding>> {
        val watch = AgentWatch(mac, engines.toSet(), shade, sdk = 34, clock = { testScheduler.currentTime })
        val ending = (this as CoroutineScope).async { watch.run() }
        runCurrent()
        return watch to ending
    }

    private fun nid(id: String) = AgentNotifications.notificationID("codex", id)

    @Test
    fun `a turn that is not running is not watched`() = runTest {
        val mac = Mac().apply { turnActive = false; approvals = emptyList() }
        val (_, ending) = watching(mac, Shade())
        assertEquals(WatchEnding.TurnEnded, ending.await())
        assertEquals("the stream is never opened", 0, mac.opened)
        assertEquals(listOf("codex@null"), mac.reads)
    }

    @Test
    fun `an approval waiting when the app left rings once and comes down when the Mac answers it`() = runTest {
        val mac = Mac()
        val shade = Shade()
        val (_, ending) = watching(mac, shade)
        assertEquals(listOf("A1"), shade.posted.map { it.approvalID })
        assertEquals(1, mac.opened)

        mac.frames.send(mac.opening())
        mac.approvals = emptyList()
        mac.frames.send(mac.frame(AgentEvent.APPROVAL, approval = approval("A1"), state = AgentEvent.ACCEPTED))
        runCurrent()
        assertEquals(listOf(nid("A1")), shade.cancelled)
        assertFalse(ending.isCompleted)

        mac.frames.send(mac.endTurn())
        assertEquals(WatchEnding.TurnEnded, ending.await())
        assertEquals("rung once", 1, shade.posted.size)
        assertEquals(listOf(1, 0), shade.counts)
    }

    @Test
    fun `an approval asked while watching rings, and never twice`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val shade = Shade()
        val (_, ending) = watching(mac, shade)
        mac.frames.send(mac.opening())
        mac.approvals = listOf(approval("A2"))
        mac.frames.send(mac.frame(AgentEvent.APPROVAL, approval = approval("A2"), state = AgentEvent.PENDING))
        runCurrent()
        assertEquals(listOf("A2"), shade.posted.map { it.approvalID })
        // A resync reads it again, still waiting: it is not rung a second time.
        mac.frames.send(ServerEvent.Resync(3))
        runCurrent()
        assertEquals(listOf("codex@null", "codex@6"), mac.reads)
        assertEquals(1, shade.posted.size)
        mac.frames.send(mac.endTurn())
        assertEquals(WatchEnding.TurnEnded, ending.await())
    }

    @Test
    fun `a drop is ridden out, and what was missed is read on the way back`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val shade = Shade()
        val (_, ending) = watching(mac, shade)
        mac.frames.send(mac.opening())
        mac.frames.send(ServerEvent.Disconnected(attempt = 1, retryInMillis = 1_000, summary = "unreachable"))
        runCurrent()
        // Twenty seconds without the Mac — a relaunch — and it asked something meanwhile.
        advanceTimeBy(20_000)
        mac.seq = 9
        mac.approvals = listOf(approval("A3"))
        assertFalse(ending.isCompleted)
        mac.frames.send(ServerEvent.Beat("2026-09-19T10:13:05Z"))
        runCurrent()
        assertEquals("one read, from where the rows were known", listOf("codex@null", "codex@5"), mac.reads)
        assertEquals(listOf("A3"), shade.posted.map { it.approvalID })
        mac.frames.send(mac.endTurn())
        assertEquals(WatchEnding.TurnEnded, ending.await())
    }

    @Test
    fun `a turn that ended while the stream was down ends the watch on the way back`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val (_, ending) = watching(mac, Shade())
        mac.frames.send(mac.opening())
        mac.frames.send(ServerEvent.Disconnected(attempt = 1, retryInMillis = 1_000, summary = "unreachable"))
        runCurrent()
        advanceTimeBy(5_000)
        mac.turnActive = false
        mac.frames.send(ServerEvent.Beat(null))
        assertEquals(WatchEnding.TurnEnded, ending.await())
    }

    @Test
    fun `a minute without the Mac ends the watch, and not a moment before`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val (_, ending) = watching(mac, Shade())
        mac.frames.send(mac.opening())
        mac.frames.send(ServerEvent.Disconnected(attempt = 1, retryInMillis = 1_000, summary = "unreachable"))
        runCurrent()
        advanceTimeBy(WatchPolicy.GRACE_MS - 1_000)
        runCurrent()
        assertFalse(ending.isCompleted)
        assertEquals(WatchEnding.LostTouch, ending.await())
        assertTrue(testScheduler.currentTime <= WatchPolicy.GRACE_MS + WatchPolicy.CHECK_EVERY_MS)
    }

    @Test
    fun `a revoked pairing ends the watch at once`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val (_, ending) = watching(mac, Shade())
        mac.frames.send(mac.opening())
        mac.frames.send(ServerEvent.Disconnected(attempt = 1, retryInMillis = 1_000, summary = "unreachable"))
        runCurrent()
        mac.readError = TransportError.Unauthorized
        mac.frames.send(ServerEvent.Beat(null))
        runCurrent()
        assertTrue(ending.isCompleted)
        assertEquals(WatchEnding.Unpaired, ending.await())
    }

    @Test
    fun `a narrowed pairing ends it before the stream is opened`() = runTest {
        val mac = Mac().apply { readError = TransportError.Forbidden("This device is paired for chat only.") }
        val (_, ending) = watching(mac, Shade())
        assertEquals(WatchEnding.NotAllowed, ending.await())
        assertEquals(0, mac.opened)
    }

    @Test
    fun `a read that fails for any other reason is asked again, not given up on`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val shade = Shade()
        val (_, ending) = watching(mac, shade)
        mac.frames.send(mac.opening())
        mac.readError = TransportError.TimedOut
        mac.frames.send(ServerEvent.Resync(1))
        runCurrent()
        assertFalse(ending.isCompleted)
        // The next frame finds the read still owed and asks again, and this time it lands.
        mac.readError = null
        mac.approvals = listOf(approval("A4"))
        mac.frames.send(mac.frame(AgentEvent.STATE, state = "running"))
        runCurrent()
        assertEquals(listOf("codex@null", "codex@5", "codex@5"), mac.reads)
        assertEquals(listOf("A4"), shade.posted.map { it.approvalID })
        mac.frames.send(mac.endTurn())
        assertEquals(WatchEnding.TurnEnded, ending.await())
    }

    @Test
    fun `an engine nobody here opened rings nothing`() = runTest {
        val mac = Mac().apply { approvals = emptyList() }
        val shade = Shade()
        val (_, ending) = watching(mac, shade)
        mac.frames.send(mac.opening())
        mac.frames.send(mac.frame(AgentEvent.APPROVAL, approval = approval("P1"), state = AgentEvent.PENDING, engine = "pi"))
        mac.frames.send(mac.frame(AgentEvent.TURN, turnActive = false, engine = "pi"))
        runCurrent()
        assertTrue(shade.posted.isEmpty())
        assertTrue("nothing of pi's is read", mac.reads.none { it.startsWith("pi") })
        assertFalse("pi's turn is not codex's", ending.isCompleted)
        mac.frames.send(mac.endTurn())
        assertEquals(WatchEnding.TurnEnded, ending.await())
    }

    @Test
    fun `an answer from the shade comes down as this phone's`() = runTest {
        val mac = Mac()
        val (watch, ending) = watching(mac, Shade())
        mac.frames.send(mac.opening())
        AgentAnswers.note("codex", "A1", "accept")
        mac.approvals = emptyList()
        mac.frames.send(mac.frame(AgentEvent.APPROVAL, approval = approval("A1"), state = AgentEvent.ACCEPTED))
        runCurrent()
        assertTrue(watch.board.session("codex").resolutions.single().byThisPhone)
        mac.frames.send(mac.endTurn())
        ending.await()
    }

    @Test
    fun `a closed watch takes its notifications down and puts none up after`() = runTest {
        val mac = Mac()
        val shade = Shade()
        val (watch, ending) = watching(mac, shade)
        mac.frames.send(mac.opening())
        runCurrent()
        watch.close()
        assertEquals(listOf(nid("A1")), shade.cancelled)
        mac.approvals = listOf(approval("A1"), approval("A5"))
        mac.frames.send(mac.frame(AgentEvent.APPROVAL, approval = approval("A5"), state = AgentEvent.PENDING))
        runCurrent()
        assertEquals(listOf("A1"), shade.posted.map { it.approvalID })
        ending.cancel()
    }

    // MARK: - A button in the shade

    private inner class Answering : HangingTransport() {
        val answers = mutableListOf<String>()
        var creditedDuring: Map<String, String>? = null
        var error: TransportError? = null
        var hangs = false

        override suspend fun answerAgentApproval(engine: String, id: String, decision: String): AgentApprovalResult {
            answers += "$engine/$id/$decision"
            creditedDuring = AgentAnswers.of(engine)
            if (hangs) awaitCancellation()
            error?.let { throw it }
            return AgentApprovalResult(id, AgentAnswers.decided(decision), summary(engine, turnActive = true, pending = 0))
        }
    }

    private val accept = ShadeAnswer("codex", "A1", "accept")

    @Test
    fun `a locked phone sends nothing and keeps the approval for later`() = runTest {
        val mac = Answering()
        val reply = answerFromShade(accept, locked = true, transport = mac)
        assertEquals(ApprovalReplies.LOCKED, reply.text)
        assertTrue(reply.keepApproval)
        assertTrue(mac.answers.isEmpty())
        assertTrue(AgentAnswers.of("codex").isEmpty())
    }

    @Test
    fun `no Mac to send to says so`() = runTest {
        val reply = answerFromShade(accept, locked = false, transport = null)
        assertEquals(ApprovalReplies.NO_MAC, reply.text)
        assertFalse(reply.keepApproval)
        assertTrue(AgentAnswers.of("codex").isEmpty())
    }

    @Test
    fun `an answer that landed is this phone's, from before it was sent`() = runTest {
        val mac = Answering()
        assertEquals(ApprovalReplies.ACCEPTED, answerFromShade(accept, locked = false, transport = mac).text)
        assertEquals(listOf("codex/A1/accept"), mac.answers)
        assertEquals("recorded before the request went", mapOf("A1" to "accepted"), mac.creditedDuring)
        assertEquals(mapOf("A1" to "accepted"), AgentAnswers.of("codex"))
        assertEquals(
            ApprovalReplies.DECLINED,
            answerFromShade(ShadeAnswer("codex", "A2", "decline"), locked = false, transport = mac).text,
        )
    }

    @Test
    fun `an answer that did not land is not this phone's, and says so in a fixed sentence`() = runTest {
        val mac = Answering()
        val cases = mapOf(
            TransportError.Conflict("That was answered at the Mac before this arrived.") to ApprovalReplies.ANSWERED_ON_THE_MAC,
            TransportError.Conflict("Jev is still screening that call.") to ApprovalReplies.STILL_SCREENING,
            TransportError.Unreachable("100.64.0.9") to ApprovalReplies.UNREACHABLE,
            TransportError.Unauthorized to ApprovalReplies.UNPAIRED,
            TransportError.Forbidden("chat only") to ApprovalReplies.NOT_ALLOWED,
        )
        for ((error, sentence) in cases) {
            mac.error = error
            val reply = answerFromShade(accept, locked = false, transport = mac)
            assertEquals(error.toString(), sentence, reply.text)
            assertTrue(error.toString(), AgentAnswers.of("codex").isEmpty())
        }
    }

    @Test
    fun `a card that is gone comes down without a word`() = runTest {
        val mac = Answering().apply { error = TransportError.NotFound("No approval A1 is waiting.") }
        val reply = answerFromShade(accept, locked = false, transport = mac)
        assertNull(reply.text)
        assertFalse(reply.keepApproval)
    }

    @Test
    fun `a Mac that does not answer in time is given up on inside the receiver's ten seconds`() = runTest {
        val mac = Answering().apply { hangs = true }
        val reply = answerFromShade(accept, locked = false, transport = mac)
        assertEquals(ApprovalReplies.TOO_SLOW, reply.text)
        assertEquals(ShadeAnswer.ANSWER_TIMEOUT_MS, testScheduler.currentTime)
        assertTrue(ShadeAnswer.ANSWER_TIMEOUT_MS < 10_000)
        assertTrue(AgentAnswers.of("codex").isEmpty())
    }

    @Test
    fun `only what this build put on a button is taken from an intent`() {
        assertEquals(accept, ShadeAnswer.from("codex", "A1", "accept"))
        assertEquals(ShadeAnswer("pi", "A1", "decline"), ShadeAnswer.from("pi", "A1", "decline"))
        assertNull(ShadeAnswer.from("claude", "A1", "accept"))
        assertNull(ShadeAnswer.from("codex", " ", "accept"))
        assertNull(ShadeAnswer.from("codex", "x".repeat(ShadeAnswer.MAX_ID + 1), "accept"))
        assertNull(ShadeAnswer.from("codex", "A1", "approve"))
        assertNull(ShadeAnswer.from("codex", null, "accept"))
    }
}
