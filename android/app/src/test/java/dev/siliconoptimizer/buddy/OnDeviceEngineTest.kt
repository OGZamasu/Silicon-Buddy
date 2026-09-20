package dev.siliconoptimizer.buddy

import android.content.ContextWrapper
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.llama.LlamaEvent
import dev.siliconoptimizer.buddy.llama.LlamaMetrics
import dev.siliconoptimizer.buddy.llama.LlamaRequest
import dev.siliconoptimizer.buddy.llama.LlamaRuntime
import dev.siliconoptimizer.buddy.llama.LlamaSession
import dev.siliconoptimizer.buddy.llama.LlamaStop
import dev.siliconoptimizer.buddy.ondevice.DeviceState
import dev.siliconoptimizer.buddy.ondevice.InstalledPhoneModel
import dev.siliconoptimizer.buddy.ondevice.ModelRuntime
import dev.siliconoptimizer.buddy.ondevice.ModelSession
import dev.siliconoptimizer.buddy.ondevice.MemoryLog
import dev.siliconoptimizer.buddy.ondevice.ModelStore
import dev.siliconoptimizer.buddy.ondevice.OnDeviceChat
import dev.siliconoptimizer.buddy.ondevice.OnDeviceEngine
import dev.siliconoptimizer.buddy.ondevice.OnDeviceNotices
import dev.siliconoptimizer.buddy.ondevice.Preflight
import dev.siliconoptimizer.buddy.ondevice.ResourceGuard
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.PhoneModelMeasured
import dev.siliconoptimizer.buddy.transport.PhoneModelOnMac
import dev.siliconoptimizer.buddy.transport.PhoneModelRecommended
import dev.siliconoptimizer.buddy.transport.PhoneModelSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine's own rules, applied rather than tabulated.
 *
 * [ResourceGuard] says what the rules *are* and is tested as a table; this is about the
 * engine doing what the table says at the moment it matters: refusing to load a model the
 * phone has no room for, noticing a file that changed since it was checked, stopping an
 * answer when the phone gets too hot, and — the expensive one — never leaving a gigabyte
 * of model loaded for an answer nobody is waiting for.
 *
 * llama.cpp and Android are both stood in for, which is the point: neither has an opinion
 * here, and on a real phone neither can be made to behave on cue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnDeviceEngineTest {

    @get:Rule val folder = TemporaryFolder()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    // MARK: - Stand-ins

    /** An answer that writes one word and then waits to be told how it ends. */
    class FakeSession : ModelSession {
        override var isClosed = false
            private set
        val threads = CopyOnWriteArrayList<ResourceGuard.Threads>()
        val started = AtomicInteger(0)
        private var ending = CompletableDeferred<LlamaStop>()

        override fun generate(request: LlamaRequest): Flow<LlamaEvent> = flow {
            started.incrementAndGet()
            ending = CompletableDeferred()
            emit(LlamaEvent.Text("Hello"))
            val stop = ending.await()
            emit(LlamaEvent.Finished(LlamaMetrics(3, 3, 2, 10.0, 10.0, 5.0, 4096, 0), stop))
        }

        /** As llama.cpp's abort callback does: the answer ends, marked cancelled. */
        override fun cancel() {
            ending.complete(LlamaStop.Cancelled)
        }

        fun finish() {
            ending.complete(LlamaStop.EndOfTurn)
        }

        override fun setThreads(prompt: Int, generate: Int) {
            threads += ResourceGuard.Threads(prompt, generate)
        }

        override suspend fun close() {
            isClosed = true
            ending.complete(LlamaStop.Cancelled)
        }
    }

    /** llama.cpp, with a load that can be held open. */
    class FakeRuntime : ModelRuntime {
        val sessions = CopyOnWriteArrayList<FakeSession>()
        val settings = CopyOnWriteArrayList<LlamaSession.Settings>()
        val loadsCancelled = AtomicInteger(0)
        val freedMidLoad = AtomicInteger(0)
        var holdLoad: CompletableDeferred<Unit>? = null
        val loadStarted = CompletableDeferred<Unit>()

        /** Thrown by the next load, as llama.cpp does when a file will not load. */
        var failWith: IllegalStateException? = null

        /** Run as the load finishes, for a phone whose memory changes with it. */
        var onOpen: (() -> Unit)? = null

        override suspend fun availability() = LlamaRuntime.Availability.Ready("cpu")

        override suspend fun open(path: String, settings: LlamaSession.Settings): ModelSession {
            this.settings += settings
            loadStarted.complete(Unit)
            try {
                holdLoad?.await()
            } catch (gone: kotlinx.coroutines.CancellationException) {
                // What LlamaSession.open does: whatever llama.cpp made by then is freed
                // before the cancellation goes any further.
                freedMidLoad.incrementAndGet()
                throw gone
            }
            failWith?.let { throw it }
            onOpen?.invoke()
            return FakeSession().also { sessions += it }
        }

        override fun cancelLoading() {
            loadsCancelled.incrementAndGet()
        }
    }

    /** The phone: as hot, as full, and as murderous as the test says. */
    class FakeDevice(var thermal: Int = 0, var available: Long = Long.MAX_VALUE, var low: Boolean = false) :
        DeviceState {
        override val thermalStatus: Int get() = thermal
        override fun memory(): Pair<Long, Boolean> = available to low

        /** When this app's last process ended, and whether it chose to. */
        var killedAt: Long? = null
        var endingWasChosen = false
        override fun lastUnwantedExit(): Pair<Long, Boolean>? = killedAt?.let { it to !endingWasChosen }

        /** What `/proc/self/status` would say: set by the fake load. */
        var anonymous = 0L
        override fun anonymousBytes(): Long = anonymous
    }

    private lateinit var store: ModelStore
    private lateinit var runtime: FakeRuntime
    private lateinit var device: FakeDevice
    private lateinit var log: MemoryLog.InMemory
    private var now = 1_000L

    private fun engine(): OnDeviceEngine {
        store = ModelStore(folder.newFolder())
        runtime = FakeRuntime()
        device = FakeDevice()
        log = MemoryLog.InMemory()
        return OnDeviceEngine(store, runtime, device, log, clock = { now })
    }

    /**
     * The owner's own entry, measured, so the bands have something to be derived from: the
     * peak beyond the weights is a tenth of the whole gate, which puts the floor an
     * unmistakable distance below it.
     */
    private fun measured(entry: InstalledPhoneModel, peakBeyondWeights: Long): InstalledPhoneModel =
        entry.copy(
            model = entry.model.copy(
                measured = PhoneModelMeasured(
                    device = "Galaxy S24 Ultra", runtime = "llama.cpp b11053, CPU",
                    conditions = "phone hot and charging", tokensPerSecond = 17.4,
                    threadSweep = emptyList(), promptTokensPerSecond = 122.9,
                    secondsToFirstWord300 = 2.5, firstWordEstimated = true,
                    sustainedTokensPerSecond = null, sustainedMeasured = false,
                    peakMemoryBytes = entry.model.sizeBytes + peakBeyondWeights,
                    peakMemoryContextTokens = 640,
                ),
            ),
        )

    /** A model on the phone: a file of [bytes] bytes, and the record that it was verified. */
    private fun install(id: String, label: String, minFree: Long, bytes: Int = 64): InstalledPhoneModel {
        val sha = "%064x".format(java.math.BigInteger(1, id.toByteArray()))
        val file = File(store.directory.also { it.mkdirs() }, "$sha.gguf")
        file.writeBytes(ByteArray(bytes) { it.toByte() })
        return InstalledPhoneModel(
            model = PhoneModel(
                id = id, label = label, isDefault = minFree < 4_000_000_000,
                sizeBytes = bytes.toLong(), sha256 = ModelStore.sha256(file),
                licence = "Apache-2.0", source = PhoneModelSource("r", "c", "f.gguf"),
                onMac = PhoneModelOnMac("ready"),
                recommended = PhoneModelRecommended(6, 4, 4096, minFree, false),
                slowerOnPhone = minFree >= 4_000_000_000,
            ),
            fileName = file.name, verifiedBytes = file.length(), verifiedModifiedAt = file.lastModified(),
            installedAt = 0,
        )
    }

    /** Writes the entries into the store's own index, as a finished download would. */
    private fun record(vararg entries: InstalledPhoneModel) {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; explicitNulls = false }
        File(store.directory, "installed.json")
            .writeText(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(InstalledPhoneModel.serializer()), entries.toList()))
    }

    // MARK: - Memory

    @Test
    fun `a model the phone has no room for is refused, and the smaller one is named`() = runBlocking {
        val engine = engine()
        val gemma = install("gemma-4-e2b-q4_0", "Gemma 4 E2B", 4_700_000_000)
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(gemma, qwen)

        device.available = 3_400_000_000
        val refused = engine.preflight(gemma)
        assertTrue(refused is Preflight.Refused)
        val refusal = refused as Preflight.Refused
        assertTrue(refusal.message.contains("Not enough free memory for Gemma 4 E2B"))
        assertEquals("the one that does fit", qwen.id, refusal.alternative?.id)
        assertTrue("nothing was loaded", runtime.sessions.isEmpty())

        assertEquals("and the smaller one loads", Preflight.Ready, engine.preflight(qwen))
    }

    @Test
    fun `a phone Android says is low on memory refuses even with the number on its side`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)
        device.available = 8_000_000_000
        device.low = true
        assertTrue(engine.preflight(qwen) is Preflight.Refused)
        assertTrue(runtime.sessions.isEmpty())
    }

    @Test
    fun `a phone with room for the working memory but not the rest answers, and says what it costs`() = runBlocking {
        val engine = engine()
        // 800 MB beyond the weights, so the floor is 1.00 GB and the Mac's gate 3.10 GB.
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)

        device.available = 3_100_000_000
        assertEquals("room for all of it", Preflight.Ready, engine.preflight(qwen))

        device.available = 2_554_596L * 1024
        val warned = engine.preflight(qwen)
        assertTrue("it runs, and says what it costs", warned is Preflight.Warned)
        val message = (warned as Preflight.Warned).message
        assertTrue(message.contains("3.10 GB"))
        assertTrue(message.contains("2.62 GB"))
        assertTrue(message.contains("Other apps may close"))

        device.available = 999_999_999
        val refused = engine.preflight(qwen)
        assertTrue(refused is Preflight.Refused)
        assertTrue(
            "and below the working memory it is refused, honestly",
            (refused as Preflight.Refused).message.contains("There is no smaller model on this phone"),
        )
        assertTrue("nothing was loaded on the way", runtime.sessions.isEmpty())
    }

    @Test
    fun `a load that got the app killed is remembered - less context, a higher floor, and it says so`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 2_554_596L * 1024

        // A load, and then Android takes the app for its memory while it is running.
        log.noteBusy(qwen.id, now, device.available)
        now += 1_000
        device.killedAt = now

        // The same reading is not tried again: it is the one that killed the app.
        val refused = engine.preflight(qwen)
        assertTrue("that reading is not tried again", refused is Preflight.Refused)
        assertEquals("the kill is noticed once", now, log.noticedKillAt)
        assertEquals(qwen.id, log.killedModelID)

        // With meaningfully more room it runs again — asking for less, and saying so.
        device.available = 3_000_000_000
        val warned = engine.preflight(qwen)
        assertTrue("more room is allowed again", warned is Preflight.Warned)
        assertTrue("and it says what it will do about it", (warned as Preflight.Warned).message.contains("ran out of memory the last time"))
        assertEquals("the next load remembers less of the conversation", 2048, engine.contextFor(qwen))

        // It answers at that reading, the phone survives, and the kill is behind it.
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it starts writing") { events.any { it is ChatStreamEvent.Token } }
        assertEquals("loaded with the shorter context", 2048, runtime.settings.last().contextLength)
        runtime.sessions.last().finish()
        answering.join()
        assertNull("the phone survived it, so that is over", log.killedModelID)
        assertEquals(4096, engine.contextFor(qwen))
    }

    @Test
    fun `a kill in the middle of an answer counts, which is when it is likeliest`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 2_900_000_000

        // Loaded, answering — the moment the context and the compute buffers are all in
        // use, and the moment a phone short of memory takes the app.
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it is writing") { events.any { it is ChatStreamEvent.Token } }
        now += 5_000
        runtime.sessions.last().finish()
        answering.join()

        // …and the process ends there. The next run reads the system's record of it.
        device.killedAt = now + 1_000
        val engineAfterRestart = OnDeviceEngine(store, FakeRuntime(), device, log, clock = { now })
        val refused = engineAfterRestart.preflight(qwen)

        assertEquals("the kill is laid at this model's door", qwen.id, log.killedModelID)
        assertTrue("and that reading is not tried again", refused is Preflight.Refused)
        assertEquals("with a shorter context when it is", 2048, engineAfterRestart.contextFor(qwen))
    }

    @Test
    fun `an ending the app chose is not a kill`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 2_900_000_000
        log.noteBusy(qwen.id, now, device.available)
        device.killedAt = now + 1_000
        device.endingWasChosen = true

        assertTrue(engine.preflight(qwen) is Preflight.Warned)
        assertNull("swiped out of Recents is not the phone reclaiming memory", log.killedModelID)
    }

    @Test
    fun `a load that failed leaves nothing for a later kill to be blamed on`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 2_900_000_000
        runtime.failWith = IllegalStateException("llama.cpp could not load that model file")

        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        engine.generate(qwen, dev.siliconoptimizer.buddy.llama.LlamaRequest(rawPrompt = "hi"))
            .collect { events += it }
        assertTrue("the load failed", events.any { it is ChatStreamEvent.Failed })

        assertNull("the record was closed with the load that failed", log.busyModelID)

        // A kill a second later — well inside the window a live load would have — is not
        // this model's doing, because there is no longer a record saying it was busy.
        device.killedAt = now + 1_000
        runtime.failWith = null
        assertTrue(engine.preflight(qwen) is Preflight.Warned)
        assertNull("nothing was still open for it to be blamed on", log.killedModelID)
        assertEquals(4096, engine.contextFor(qwen))
    }

    @Test
    fun `what a load costs this phone is measured and used instead of the estimate`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 3_100_000_000
        // The fake phone's own anonymous memory grows by 2.0 GB across the load: more than
        // the Mac's measurement implies, which is the case that matters.
        device.anonymous = 500_000_000
        runtime.onOpen = { device.anonymous = 2_500_000_000 }

        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it is writing") { events.any { it is ChatStreamEvent.Token } }
        runtime.sessions.last().finish()
        answering.join()

        assertEquals("recorded against this model and this context", 2_000_000_000L, engine.measuredAt(qwen, 4096))
        assertEquals("and it is the floor now", (2_000_000_000 * 1.25).toLong(), engine.floorFor(qwen))
        engine.unload("test")
        device.available = 2_400_000_000
        assertTrue(
            "so a phone the estimate would have let through is refused",
            engine.preflight(qwen) is Preflight.Refused,
        )
    }

    @Test
    fun `a kill that was not about a model of ours is not held against it`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 2_554_596L * 1024
        // Killed while nothing was loading — the phone was short of memory for its own
        // reasons, and this app happened to be the biggest thing running.
        device.killedAt = now + 5_000

        assertTrue(engine.preflight(qwen) is Preflight.Warned)
        assertNull(log.killedModelID)
        assertEquals(4096, engine.contextFor(qwen))
    }

    @Test
    fun `a shorter context the owner chose is remembered, and the model let go of so it takes`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 3_100_000_000

        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it is writing") { events.any { it is ChatStreamEvent.Token } }
        assertEquals(4096, runtime.settings.last().contextLength)
        runtime.sessions.last().finish()
        answering.join()

        engine.chooseContext(qwen, 2048)
        assertEquals(2048, engine.contextFor(qwen))
        await("the loaded model was let go of, so the choice takes") { engine.state.value == OnDeviceEngine.State.Unloaded }

        val again = CopyOnWriteArrayList<ChatStreamEvent>()
        val second = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Again?")), 128)
                .collect { again += it }
        }
        await("it is writing again") { again.any { it is ChatStreamEvent.Token } }
        assertEquals("with what the owner chose", 2048, runtime.settings.last().contextLength)
        runtime.sessions.last().finish()
        second.join()
    }

    @Test
    fun `making room lets go of the model this app is holding, and says what came back`() = runBlocking {
        val engine = engine()
        val qwen = measured(install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000), 800_000_000)
        record(qwen)
        device.available = 3_100_000_000

        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it is writing, so there is something to finish") { events.any { it is ChatStreamEvent.Token } }
        runtime.sessions.last().finish()
        answering.join()

        // The phone counts the model's memory back as this app lets go of it.
        val session = runtime.sessions.last()
        device.available = 1_800_000_000
        val recovered = engine.makeRoom().also { device.available = 3_100_000_000 }

        assertTrue("it was holding one", recovered.hadModel)
        assertTrue("and let go of it", session.isClosed)
        assertEquals(OnDeviceEngine.State.Unloaded, engine.state.value)
        assertEquals("nothing left loaded to ask about", null, engine.loadedModel)
    }

    // MARK: - The file

    @Test
    fun `a model file that changed since it was checked is hashed again, and deleted when it differs`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)
        // Something else wrote to it: same name, different bytes.
        val file = store.file(qwen)
        file.writeBytes(ByteArray(64) { 9 })
        file.setLastModified(qwen.verifiedModifiedAt + 10_000)

        val refused = engine.preflight(qwen)
        assertTrue(refused is Preflight.Refused)
        assertEquals(OnDeviceNotices.changedOnDisk("Qwen3.5 2B"), (refused as Preflight.Refused).message)
        assertTrue("it was deleted rather than run", store.installed().isEmpty())
        assertTrue("and nothing was loaded from it", runtime.sessions.isEmpty())
    }

    @Test
    fun `a file that changed but still hashes the same is kept`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)
        // Touched — a restore, a copy — but the same bytes.
        store.file(qwen).setLastModified(qwen.verifiedModifiedAt + 10_000)
        assertEquals(Preflight.Ready, engine.preflight(qwen))
        assertEquals(1, store.installed().size)
    }

    // MARK: - Heat

    @Test
    fun `a phone that gets critically hot stops the answer it is writing`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)

        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it starts writing") { events.any { it is ChatStreamEvent.Token } }

        device.thermal = ResourceGuard.THERMAL_CRITICAL
        engine.onHeat(ResourceGuard.THERMAL_CRITICAL)
        answering.join()

        val failure = events.filterIsInstance<ChatStreamEvent.Failed>().single()
        assertEquals(OnDeviceNotices.TOO_HOT, failure.message)
        assertTrue("what it wrote before stopping is kept", events.any { it is ChatStreamEvent.Token })

        // And it will not start another one while the phone is still that hot.
        val refused = engine.preflight(qwen)
        assertEquals(OnDeviceNotices.TOO_HOT_TO_START, (refused as Preflight.Refused).message)
    }

    @Test
    fun `a warm phone keeps answering with fewer threads`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128)
                .collect { events += it }
        }
        await("it starts writing") { events.any { it is ChatStreamEvent.Token } }

        engine.onHeat(ResourceGuard.THERMAL_SEVERE)
        val session = runtime.sessions.single()
        await("the threads are thinned") { session.threads.size > 1 }
        assertEquals(ResourceGuard.Threads(3, 2), session.threads.last())
        assertTrue("and it is still going", events.none { it is ChatStreamEvent.Failed })

        session.finish()
        answering.join()
        assertTrue(events.any { it is ChatStreamEvent.Finished })
    }

    // MARK: - A load nobody is waiting for

    @Test
    fun `an answer abandoned while the model is still loading leaves nothing loaded`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)
        runtime.holdLoad = CompletableDeferred()

        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128).collect { }
        }
        runtime.loadStarted.await()
        await("the engine says it is loading") { engine.state.value is OnDeviceEngine.State.Loading }

        // The owner closes the conversation, or leaves the app: the answer is cancelled
        // while a gigabyte of model is still being read in.
        answering.cancel()
        answering.join()

        await("the engine is back to nothing loaded") { engine.state.value == OnDeviceEngine.State.Unloaded }
        assertEquals("what the load had made was freed", 1, runtime.freedMidLoad.get())
        assertTrue("and no session was kept", runtime.sessions.isEmpty())
        assertNull(engine.loadedModel)
        assertNull("and nothing is left saying this model was busy", log.busyModelID)

        // So a kill a second later — the phone short of memory for its own reasons — is
        // not laid at this model's door.
        device.killedAt = now + 1_000
        engine.preflight(qwen)
        assertNull(log.killedModelID)

        // And the next question still works.
        runtime.holdLoad = null
        val events = CopyOnWriteArrayList<ChatStreamEvent>()
        val again = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Still there?")), 128)
                .collect { events += it }
        }
        await("it answers") { events.any { it is ChatStreamEvent.Token } }
        runtime.sessions.last().finish()
        again.join()
        assertTrue(events.any { it is ChatStreamEvent.Finished })
    }

    @Test
    fun `Stop during a load reaches llama_cpp, and says nothing when nothing is loading`() = runBlocking {
        val engine = engine()
        val qwen = install("qwen3.5-2b-q4_0", "Qwen3.5 2B", 3_100_000_000)
        record(qwen)

        engine.cancelLoading()
        assertEquals("nothing is loading: nothing to stop", 0, runtime.loadsCancelled.get())

        runtime.holdLoad = CompletableDeferred()
        val answering = launch(Dispatchers.Default) {
            engine.answer(qwen, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = "Hello?")), 128).collect { }
        }
        runtime.loadStarted.await()
        await("the engine says it is loading") { engine.state.value is OnDeviceEngine.State.Loading }
        engine.cancelLoading()
        assertEquals("llama.cpp was told to give up", 1, runtime.loadsCancelled.get())
        answering.cancel()
        answering.join()
    }

    // MARK: - Where any of this is kept

    @Test
    fun `models and the conversations they answered are kept out of backups`() {
        val noBackup = folder.newFolder("no_backup")
        val files = folder.newFolder("files")
        val context = object : ContextWrapper(null) {
            override fun getNoBackupFilesDir(): File = noBackup
            override fun getFilesDir(): File = files
        }
        val models = ModelStore(context).directory
        val conversations = OnDeviceChat.conversationFolder(context)
        assertEquals(noBackup, models.parentFile)
        assertEquals(noBackup, conversations.parentFile)
        assertFalse("a gigabyte of weights is not something to back up", models.path.startsWith(files.path))
        assertFalse(
            "and a conversation the phone answered is on this phone, not in anybody's cloud",
            conversations.path.startsWith(files.path),
        )
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) org.junit.Assert.fail("never: $what")
            Thread.sleep(5)
        }
    }
}
