package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.modelsui.ModelsViewModel
import dev.siliconoptimizer.buddy.modelsui.ModelsViewModel.LoadOutcome
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadConflict
import dev.siliconoptimizer.buddy.transport.LoadFailure
import dev.siliconoptimizer.buddy.transport.LoadInterruption
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A load outlives its request (#13). `POST /load` answers after 25 seconds whether or not
 * the model is in, so the answer can be "still loading" — and the screen has to stay with
 * the load, through the Mac's pushed status or by asking, until it has an ending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelLoadFollowTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val id = "test-model@Q4_K_M"
    private val onDisk = InstalledModel(id, "Test Model", "Q4_K_M", 100, false, false)
    private val loading = Status("Loading Test Model…")
    private val weights = Status("Loading weights… 42%")
    private val loaded = Status("Ready", loadedModelID = id, loadedModelName = "Test Model", contextLength = 4096)
    private val killed = "llama-server was killed (signal 9) after 8 seconds, which usually means " +
        "the system reclaimed its memory."
    private val log = "load_tensors: loading model tensors\nloaded multimodal model, 'mmproj-Q8_0.gguf'"

    private fun failed(
        reason: String,
        detail: String? = log,
        replaced: Boolean = false,
        state: String = killed,
        modelID: String? = null,
    ) =
        Status(
            state,
            failure = LoadFailure(
                reason = reason, detail = detail, runtime = "llama.cpp", signal = 9,
                wasReplaced = replaced, at = "2026-09-19T11:04:38Z", modelID = modelID,
            ),
        )

    /** What a Mac with `interruptedLoads` (OGZamasu/silicon-optimizer#97) says of a load it stopped. */
    private fun stopped(reason: String, replacedBy: String? = null, state: String = "Loading weights… 42%") =
        Status(
            state,
            interruptedLoads = listOf(
                LoadInterruption(modelID = id, reason = reason, replacedBy = replacedBy, at = "2026-09-19T11:04:38Z"),
            ),
        )

    /**
     * A Mac whose `POST /load` answers with [answer] — once [gate] opens, when there is one —
     * and whose `GET /status` walks through [readings], repeating the last.
     */
    private inner class SlowMac(
        val answer: Status,
        vararg readings: Status,
    ) : HangingTransport() {
        private val queue = ArrayDeque(readings.toList())
        var gate: CompletableDeferred<Unit>? = null
        var statusReads = 0
        var loads = 0

        override suspend fun load(request: LoadRequest): Status {
            loads++
            gate?.await()
            return answer
        }

        override suspend fun status(): Status {
            statusReads++
            return if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() ?: answer
        }

        override suspend fun installed(): List<InstalledModel> = listOf(onDisk)
        override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> =
            emptyList()
    }

    @Test
    fun `a quick load is over when the answer says so`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loaded)
        try {
            model.load(id, transport = mac)
            runCurrent()
            assertNull(model.job)
            assertNull(model.problem)
            assertTrue(model.isLoaded(id))
            assertEquals("only the refresh afterwards reads the status", 1, mac.statusReads)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("nothing is being followed", 1, mac.statusReads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a delayed success is followed by asking, when there is no event feed`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading, weights, weights, loaded)
        try {
            model.load(id, transport = mac)
            runCurrent()
            assertEquals("the answer is not the end of it", "load", model.job?.kind)
            assertEquals("Loading Test Model…", model.job?.message)

            advanceTimeBy(ModelsViewModel.POLL_WITHOUT_EVENTS_MS)
            runCurrent()
            assertEquals("the row says what the Mac says", "Loading weights… 42%", model.job?.message)
            assertFalse(model.isLoaded(id))

            advanceTimeBy(2 * ModelsViewModel.POLL_WITHOUT_EVENTS_MS)
            runCurrent()
            assertNull(model.job)
            assertNull(model.problem)
            assertTrue(model.isLoaded(id))
            val reads = mac.statusReads

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("a finished load is not asked about again", reads, mac.statusReads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a delayed success arrives on the event feed without asking`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading, loaded)
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            advanceTimeBy(3_000)
            model.statusChanged(weights)
            runCurrent()
            assertEquals("Loading weights… 42%", model.job?.message)
            advanceTimeBy(3_000)
            model.statusChanged(loaded)
            runCurrent()

            assertNull(model.job)
            assertTrue(model.isLoaded(id))
            assertEquals("the frames were enough: only the refresh afterwards asked", 1, mac.statusReads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a missed frame is made up for by a slow poll`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading, loaded)
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            advanceTimeBy(ModelsViewModel.POLL_WITH_EVENTS_MS - 1)
            runCurrent()
            assertEquals("with a live feed, nothing is asked before the slow poll", 0, mac.statusReads)
            assertNotNull(model.job)

            advanceTimeBy(2)
            runCurrent()
            assertNull(model.job)
            assertTrue(model.isLoaded(id))
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a delayed failure says the sentence, with the log behind it`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        // The Mac's own /status agrees with the frame, as it would.
        val mac = SlowMac(loading, failed("killed"))
        model.eventsLive = true
        try {
            model.refresh(mac)
            runCurrent()
            model.load(id, transport = mac)
            runCurrent()
            model.statusChanged(failed("killed"))
            runCurrent()

            assertNull(model.job)
            val problem = model.problem
            assertNotNull(problem)
            problem!!
            assertEquals("Couldn't load Test Model", problem.title)
            assertEquals("the one line comes first", killed, problem.message)
            assertEquals("the log is there, for the disclosure", log, problem.detail)
            assertTrue(problem.isFault)
            assertEquals(ModelsViewModel.Operation.Load(id, null), problem.retry)
            assertNull("the list does not say it a second time", model.standingFailure)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load that another load replaced is not a fault`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading)
        model.eventsLive = true
        try {
            model.refresh(mac)
            runCurrent()
            model.load(id, transport = mac)
            runCurrent()
            val replaced = failed(
                "replaced", detail = null, replaced = true,
                state = "llama-server was replaced by another load (Qwen3-Coder 30B).",
            )
            model.statusChanged(replaced)
            runCurrent()
            assertNull(model.job)
            assertEquals("Test Model wasn't loaded", model.problem?.title)
            assertEquals(replaced.state, model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertNull("asking again would undo somebody's choice", model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `another model resident instead is a replacement too`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val other = Status("Ready", loadedModelID = "qwen3-coder-30b", loadedModelName = "Qwen3-Coder 30B A3B")
        val mac = SlowMac(loading, loading, other)
        try {
            model.load(id, transport = mac)
            runCurrent()
            advanceTimeBy(2 * ModelsViewModel.POLL_WITHOUT_EVENTS_MS)
            runCurrent()
            assertNull(model.job)
            assertEquals("The Mac loaded Qwen3-Coder 30B A3B instead.", model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertTrue(model.isLoaded("qwen3-coder-30b"))
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load called off on the Mac ends as cancelled`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading)
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            val cancelled = failed(
                "cancelled", detail = null,
                state = "llama-server was stopped by an unload before it finished loading.",
            )
            model.statusChanged(cancelled)
            runCurrent()
            assertNull(model.job)
            assertEquals(cancelled.state, model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertEquals(LoadOutcome.Cancelled, LoadOutcome.of(cancelled, id))
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a reason this app has never heard of is a failure, shown by its sentence`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading)
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            val novel = failed("outOfCheese", state = "The runtime ran out of cheese.")
            assertEquals(LoadFailure.Reason.Exited, novel.failure!!.kind)
            model.statusChanged(novel)
            runCurrent()
            assertNull(model.job)
            assertEquals("The runtime ran out of cheese.", model.problem?.message)
            assertTrue(model.problem!!.isFault)
            assertEquals(log, model.problem?.detail)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a chat-scope reading has no log, and nothing pretends it has`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val withheld = failed("killed", detail = null)
        val mac = SlowMac(withheld, withheld)
        try {
            model.refresh(mac)
            runCurrent()
            val standing = model.standingFailure
            assertNotNull("a chat device still sees that the last load failed", standing)
            assertNull("and not the runtime's log, which it was not sent", standing!!.detail)
            assertEquals("llama.cpp · signal 9", standing.facts)
            assertEquals(killed, model.status?.state)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a pushed frame older than the answer is not the ending`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading).apply { gate = CompletableDeferred() }
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            // The Mac's previous failure, arriving while the request is out.
            model.statusChanged(failed("killed"))
            runCurrent()
            mac.gate!!.complete(Unit)
            runCurrent()
            assertNull("a failure from before this load is not this load's", model.problem)
            assertEquals("load", model.job?.kind)
            assertEquals("the answer is newer than the frame", loading, model.status)

            model.statusChanged(loaded)
            runCurrent()
            assertNull(model.job)
            assertNull(model.problem)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load that ended while the answer was out is read at once, not after a wait`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        // The load finished just after the Mac stopped waiting: its answer says "loading",
        // the frame saying "loaded" arrived first, and the Mac will not push it again.
        val mac = SlowMac(loading, loaded).apply { gate = CompletableDeferred() }
        model.eventsLive = true
        try {
            model.load(id, transport = mac)
            runCurrent()
            model.statusChanged(loaded)
            runCurrent()
            mac.gate!!.complete(Unit)
            runCurrent()
            assertNull("not ten seconds of 'Loading…' for a model that is in", model.job)
            assertTrue(model.isLoaded(id))
            assertNull(model.problem)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `following is bounded, and says so when the Mac never did`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val mac = SlowMac(loading)
        try {
            model.refresh(mac)
            runCurrent()
            model.load(id, transport = mac)
            runCurrent()
            advanceTimeBy(ModelsViewModel.FOLLOW_LIMIT_MS - 1)
            runCurrent()
            assertNotNull("still within the Mac's own limit", model.job)

            advanceTimeBy(2)
            runCurrent()
            assertNull(model.job)
            assertEquals("Still loading Test Model", model.problem?.title)
            assertFalse(model.problem!!.isFault)
            assertEquals(ModelsViewModel.Operation.Refresh, model.problem?.retry)
            val reads = mac.statusReads
            assertTrue("it asked about every two seconds", reads in 300..340)

            advanceTimeBy(10 * 60_000)
            runCurrent()
            assertEquals("and then stopped", reads, mac.statusReads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a reset while waiting stops following, and the old answer lands nowhere`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val old = SlowMac(loaded).apply { gate = CompletableDeferred() }
        model.load(id, transport = old)
        runCurrent()
        assertEquals("load", model.job?.kind)

        model.reset()
        old.gate!!.complete(Unit)
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(model.job)
        assertNull("the old Mac's status is not the new pairing's", model.status)
        assertNull(model.problem)
        assertTrue(model.installed.isEmpty())
    }

    @Test
    fun `a re-pair stops asking the old Mac about its slow load`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val old = SlowMac(loading)
        model.load(id, transport = old)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        val asked = old.statusReads
        assertTrue(asked > 0)

        model.reset()
        val next = SlowMac(Status("Not loaded"))
        model.refresh(next)
        runCurrent()
        // A frame the old feed let slip after the reset: the new pairing is not following anything.
        model.statusChanged(failed("killed"))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("the old Mac is not asked again", asked, old.statusReads)
        assertNull(model.job)
        assertNull("nothing was being followed, so nothing failed", model.problem)
        model.reset()
    }

    @Test
    fun `a re-pair while a follow-up poll hangs never revives the old load`() = runTest(dispatcher) {
        val model = ModelsViewModel()
        val old = object : HangingTransport() {
            override suspend fun load(request: LoadRequest) = loading
            // status() hangs, as a Mac that has gone away does.
        }
        model.load(id, transport = old)
        runCurrent()
        advanceTimeBy(ModelsViewModel.POLL_WITHOUT_EVENTS_MS + 1)
        runCurrent()
        assertEquals("load", model.job?.kind)

        model.reset()
        advanceTimeBy(ModelsViewModel.FOLLOW_LIMIT_MS)
        runCurrent()
        assertNull(model.job)
        assertNull("nothing was left to say it was still loading", model.problem)
        assertNull(model.status)
    }

    // MARK: - A Mac that says which loads it stopped, and whose failure it is (#97)

    private fun followUntil(pushed: Status, answer: Status = loading): ModelsViewModel {
        val model = ModelsViewModel()
        model.eventsLive = true
        val mac = SlowMac(answer)
        model.refresh(mac)
        dispatcher.scheduler.runCurrent()
        model.load(id, transport = mac)
        dispatcher.scheduler.runCurrent()
        model.statusChanged(pushed)
        dispatcher.scheduler.runCurrent()
        return model
    }

    @Test
    fun `a load another load replaced ends at once, says by what, and offers no retry`() = runTest(dispatcher) {
        // The replacing load's line, and no failure at all: only `interruptedLoads` says it.
        val model = followUntil(stopped("replaced", replacedBy = "qwen3-coder-30b"))
        try {
            assertNull("following ends at the Mac's word", model.job)
            assertEquals("Test Model wasn't loaded", model.problem?.title)
            assertEquals("Replaced by qwen3-coder-30b before it finished loading.", model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertNull("asking again would undo somebody's choice", model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a load an unload stopped says so, before the failure line has even settled`() = runTest(dispatcher) {
        val model = followUntil(stopped("cancelled", state = "Not loaded"))
        try {
            assertNull(model.job)
            assertEquals("Stopped by an unload before it finished loading.", model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertNull(model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `another model's failure is not this load's fault`() = runTest(dispatcher) {
        // The Mac went on to load another model, and that one failed.
        val theirs = failed("killed", modelID = "fails-to-load@Q4_K_M")
        assertEquals(LoadOutcome.Replaced, LoadOutcome.of(theirs, id))
        val model = followUntil(theirs)
        try {
            assertNull(model.job)
            assertEquals("Test Model wasn't loaded", model.problem?.title)
            assertFalse("not this load's log, nor its fault", model.problem!!.isFault)
            assertNull(model.problem?.detail)
            assertNull(model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `this model's own failure is still a failure, with its log and a retry`() = runTest(dispatcher) {
        val ours = failed("killed", modelID = id)
        assertEquals(LoadOutcome.Failed, LoadOutcome.of(ours, id))
        assertEquals("the catalogue spelling too", LoadOutcome.Failed, LoadOutcome.of(ours, "test-model"))
        val model = followUntil(ours)
        try {
            assertEquals("Couldn't load Test Model", model.problem?.title)
            assertTrue(model.problem!!.isFault)
            assertEquals(log, model.problem?.detail)
            assertNotNull(model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `an older Mac without either field is read as before`() {
        // No `modelID`: the failure is taken as this load's, as it always was.
        assertEquals(LoadOutcome.Failed, LoadOutcome.of(failed("killed"), id))
        assertEquals(LoadOutcome.Cancelled, LoadOutcome.of(failed("cancelled"), id))
        assertEquals(LoadOutcome.Replaced, LoadOutcome.of(failed("killed", replaced = true), id))
        assertEquals(LoadOutcome.Pending, LoadOutcome.of(loading, id))
        // Another model's stopped load says nothing about this one.
        val someoneElses = Status(
            "Loading Test Model…",
            interruptedLoads = listOf(LoadInterruption("qwen3-coder-30b", "replaced", id, "2026-09-19T11:04:38Z")),
        )
        assertEquals(LoadOutcome.Pending, LoadOutcome.of(someoneElses, id))
    }

    @Test
    fun `the same model asked for again is followed to its end, not called replaced`() = runTest(dispatcher) {
        // A newer load of the same model: the answer is the live status, nothing is listed.
        val model = ModelsViewModel()
        val mac = SlowMac(weights, weights, loaded)
        try {
            model.load(id, transport = mac)
            runCurrent()
            assertEquals("load", model.job?.kind)
            advanceTimeBy(2 * ModelsViewModel.POLL_WITHOUT_EVENTS_MS + 1)
            runCurrent()
            assertNull(model.job)
            assertNull(model.problem)
            assertTrue(model.isLoaded(id))
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a 409 for a load that was stopped is the Mac's sentence, and not a fault`() = runTest(dispatcher) {
        val sentence = "Test Model was not loaded: another load (Qwen3-Coder 30B A3B) replaced it before it finished."
        val model = ModelsViewModel()
        val mac = object : HangingTransport() {
            var reads = 0
            override suspend fun load(request: LoadRequest): Status = throw TransportError.Conflict(sentence)
            override suspend fun status(): Status {
                reads++
                return stopped("replaced", replacedBy = "qwen3-coder-30b")
            }
            override suspend fun installed(): List<InstalledModel> = listOf(onDisk)
            override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> = emptyList()
        }
        try {
            model.refresh(mac)
            runCurrent()
            mac.reads = 0
            model.load(id, transport = mac)
            runCurrent()
            assertNull(model.job)
            assertEquals("Test Model wasn't loaded", model.problem?.title)
            assertEquals(sentence, model.problem?.message)
            assertFalse(model.problem!!.isFault)
            assertNull(model.problem?.retry)
            assertEquals("the status is read once, for the list", 1, mac.reads)
        } finally {
            model.reset()
        }
    }

    @Test
    fun `a 409 because another load is running is still something to retry`() = runTest(dispatcher) {
        val busy = "This Mac is already loading bonsai-2-27b (started 12s ago), and this route runs one load " +
            "at a time. Nothing was changed."
        val model = ModelsViewModel()
        val mac = object : HangingTransport() {
            override suspend fun load(request: LoadRequest): Status = throw TransportError.Conflict(busy)
            override suspend fun status(): Status = Status("Loading Bonsai 2 27B…")
            override suspend fun installed(): List<InstalledModel> = listOf(onDisk)
            override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> = emptyList()
        }
        try {
            model.refresh(mac)
            runCurrent()
            model.load(id, transport = mac)
            runCurrent()
            assertEquals("Couldn't load Test Model", model.problem?.title)
            assertEquals(busy, model.problem?.message)
            assertNotNull(model.problem?.retry)
        } finally {
            model.reset()
        }
    }

    /**
     * The round-3 nit. A load of this model was stopped earlier, so the Mac still lists that;
     * then this one is refused with the older 409, because another load is running. That
     * refusal comes before the Mac's load begins — which is what clears the entry — so the
     * entry was still there, and the refusal read as this load being stopped: "wasn't
     * loaded", with no Retry, though nothing had been tried.
     */
    @Test
    fun `a 409 because another load is running stays retryable with an earlier interruption still listed`() =
        runTest(dispatcher) {
            val busy = "This Mac is already loading bonsai-2-27b (started 12s ago), and this route runs one load " +
                "at a time. Nothing was changed. Follow it with GET /status, or POST /unload to stop it and then " +
                "load again."
            val model = ModelsViewModel()
            val mac = object : HangingTransport() {
                override suspend fun load(request: LoadRequest): Status = throw TransportError.Conflict(busy)
                override suspend fun status(): Status = stopped("cancelled", state = "Loading Bonsai 2 27B…")
                override suspend fun installed(): List<InstalledModel> = listOf(onDisk)
                override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> =
                    emptyList()
            }
            try {
                model.refresh(mac)
                runCurrent()
                model.load(id, transport = mac)
                runCurrent()
                assertEquals("Couldn't load Test Model", model.problem?.title)
                assertEquals(busy, model.problem?.message)
                assertNotNull(model.problem?.retry)
            } finally {
                model.reset()
            }
        }

    /** Words this app does not know: the list decides, and only an entry it had not seen. */
    @Test
    fun `a 409 in other words counts an interruption only when it is new`() = runTest(dispatcher) {
        val words = "Test Model could not be loaded just now."
        val known = stopped("cancelled")
        val fresh = Status(
            "Not loaded",
            interruptedLoads = listOf(LoadInterruption(id, "replaced", "qwen3-coder-30b", "2026-09-19T11:09:02Z")),
        )
        for ((after, stoppedHere) in listOf(known to false, fresh to true)) {
            val model = ModelsViewModel()
            var reading = known
            val mac = object : HangingTransport() {
                override suspend fun load(request: LoadRequest): Status {
                    reading = after
                    throw TransportError.Conflict(words)
                }
                override suspend fun status(): Status = reading
                override suspend fun installed(): List<InstalledModel> = listOf(onDisk)
                override suspend fun catalog(category: String?, onlyRunnable: Boolean): List<CatalogModel> =
                    emptyList()
            }
            try {
                model.refresh(mac)
                runCurrent()
                model.load(id, transport = mac)
                runCurrent()
                if (stoppedHere) {
                    assertEquals("Test Model wasn't loaded", model.problem?.title)
                    assertNull(model.problem?.retry)
                } else {
                    assertEquals("the entry from before is not this load's", "Couldn't load Test Model", model.problem?.title)
                    assertNotNull(model.problem?.retry)
                }
            } finally {
                model.reset()
            }
        }
    }

    /**
     * The same rule while a load is followed: an entry the phone already knew of before it
     * asked belongs to an earlier load. A current Mac clears it when the load starts; one
     * that was still listed is not this load's ending.
     */
    @Test
    fun `an interruption listed before the load was asked for does not end it`() = runTest(dispatcher) {
        val leftover = Status(
            "Loading Test Model…",
            interruptedLoads = listOf(LoadInterruption(id, "replaced", "qwen3-coder-30b", "2026-09-19T11:04:38Z")),
        )
        val model = ModelsViewModel()
        val mac = SlowMac(leftover, leftover, leftover, loaded)
        try {
            model.refresh(mac)
            runCurrent()
            model.load(id, transport = mac)
            runCurrent()
            assertEquals("still being followed", "load", model.job?.kind)
            assertNull(model.problem)

            advanceTimeBy(ModelsViewModel.POLL_WITHOUT_EVENTS_MS * 3)
            runCurrent()
            assertNull(model.job)
            assertNull(model.problem)
            assertTrue(model.isLoaded(id))
        } finally {
            model.reset()
        }
    }

    @Test
    fun `the two 409s are told apart by the Mac's own sentences`() {
        // Word for word from the Mac: LoadDispatcher's refusal, and InterruptedLoad.sentence.
        val busy = "This Mac is already loading qwen3-coder-30b@Q4_K_M (started 3s ago), and this route runs " +
            "one load at a time. Nothing was changed. Follow it with GET /status, or POST /unload to stop it " +
            "and then load again."
        val unloaded = "Test Model was not loaded: an unload stopped it before it finished loading."
        val replaced = "Test Model was not loaded: another load (Qwen3-Coder 30B A3B) replaced it before it finished."
        val reloaded = "Test Model is being loaded again, by a newer load with its own settings."
        assertTrue(LoadConflict.isAlreadyLoading(busy))
        assertFalse(LoadConflict.wasStopped(busy))
        for (sentence in listOf(unloaded, replaced)) {
            assertTrue(sentence, LoadConflict.wasStopped(sentence))
            assertFalse(sentence, LoadConflict.isAlreadyLoading(sentence))
        }
        // Never a 409: the Mac answers a reload 200, with the status to follow.
        assertFalse(LoadConflict.wasStopped(reloaded))
    }

    @Test
    fun `an outcome is read from one status`() {
        assertEquals(LoadOutcome.Pending, LoadOutcome.of(loading, id))
        assertEquals(LoadOutcome.Loaded, LoadOutcome.of(loaded, id))
        assertEquals("the catalogue spelling of the id is the same model",
            LoadOutcome.Loaded, LoadOutcome.of(loaded, "test-model"))
        assertEquals(LoadOutcome.Failed, LoadOutcome.of(failed("killed"), id))
        assertEquals(LoadOutcome.Failed, LoadOutcome.of(failed("timedOut"), id))
        assertEquals(LoadOutcome.Replaced, LoadOutcome.of(failed("replaced"), id))
        assertEquals("wasReplaced wins over the reason", LoadOutcome.Replaced,
            LoadOutcome.of(failed("killed", replaced = true), id))
        assertEquals(LoadOutcome.Failed, LoadOutcome.of(failed("somethingNew"), id))
    }
}
