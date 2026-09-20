package dev.siliconoptimizer.buddy.ondevice

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.os.Process
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.llama.LlamaEvent
import dev.siliconoptimizer.buddy.llama.LlamaMessage
import dev.siliconoptimizer.buddy.llama.LlamaRequest
import dev.siliconoptimizer.buddy.llama.LlamaRuntime
import dev.siliconoptimizer.buddy.llama.LlamaSampling
import dev.siliconoptimizer.buddy.llama.LlamaSession
import dev.siliconoptimizer.buddy.llama.LlamaStop
import dev.siliconoptimizer.buddy.transport.ChatMetrics
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Whether the phone may answer right now, checked before a word is written. */
sealed interface Preflight {
    data object Ready : Preflight

    /**
     * It will run, and the owner should know what it will cost first: a phone with room
     * for the model's working memory but not for the Mac's whole figure may close other
     * apps for it, and will be slower while the weights are read back off the file.
     */
    data class Warned(val message: String) : Preflight

    /** Why not, in a sentence — and, for memory, a smaller model that would fit. */
    data class Refused(val message: String, val alternative: InstalledPhoneModel? = null) : Preflight
}

/**
 * The phone's own model: loaded when asked for, let go when it is not wanted.
 *
 * One per process, because there is one model's worth of memory. It owns the rules the
 * owner set for it:
 *
 * - **Memory**: refuse to load below the Mac's `minFreeMemoryBytes` for the model, or while
 *   Android is in its low-memory state, and name a smaller installed model that fits.
 * - **Heat**: MODERATE thins the threads, SEVERE thins them further and keeps answering, and
 *   CRITICAL or worse stops the answer — "Stopped — phone too hot" — and starts no new one.
 * - **The screen**: an answer is only written while the app is in front. Leaving cancels it;
 *   the model unloads after 30 s in the background, at once when Android asks for memory back
 *   from a background app, and after five idle minutes anywhere.
 */
class OnDeviceEngine internal constructor(
    val store: ModelStore,
    private val runtime: ModelRuntime,
    private val device: DeviceState,
    private val log: MemoryLog = MemoryLog.InMemory(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface State {
        data object Unloaded : State
        data class Loading(val model: InstalledPhoneModel) : State
        data class Loaded(val model: InstalledPhoneModel, val threads: ResourceGuard.Threads) : State
        data class Answering(val model: InstalledPhoneModel, val threads: ResourceGuard.Threads) : State
    }

    /** Why an answer stopped early, for the sentence under it. */
    enum class StopReason { User, Background, Heat, Memory }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Why the last model was let go: `idle`, `background`, `trim-memory`, `deleted`… */
    @Volatile var lastUnloadReason: String? = null
        private set

    /** llama.cpp's account of its backends, once the library is loaded. */
    @Volatile var backends: String? = null
        private set

    private val mutex = Mutex()
    @Volatile private var session: ModelSession? = null
    @Volatile private var sessionModel: InstalledPhoneModel? = null
    @Volatile private var stopReason: StopReason? = null
    @Volatile private var inForeground = true
    private var unloadTimer: Job? = null

    /** Android, when there is one: memory warnings and the thermal listener come from here. */
    private var context: Context? = null
    private var listeningToHeat = false
    private val heatListener = PowerManager.OnThermalStatusChangedListener { status -> onHeat(status) }

    /** Starts listening to Android for memory pressure. The tests' engine has no Context. */
    private fun watch(context: Context) {
        this.context = context
        context.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // UI_HIDDEN arrives every time the app leaves the screen, which the grace
                // below handles; BACKGROUND and worse mean Android wants memory back now.
                if (ResourceGuard.unloadsAtOnce(level) && session != null) {
                    android.util.Log.i(LOG, "trim-memory level $level: unloading the phone's model")
                }
                if (ResourceGuard.unloadsAtOnce(level)) {
                    stopReason = StopReason.Memory
                    session?.cancel()
                    scope.launch { unload("trim-memory") }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                stopReason = StopReason.Memory
                session?.cancel()
                scope.launch { unload("low-memory") }
            }
        })
    }

    // MARK: - What this phone can do

    /** Loads the library once and says whether this phone can run a model at all. */
    suspend fun availability(): LlamaRuntime.Availability =
        runtime.availability().also {
            if (it is LlamaRuntime.Availability.Ready) backends = it.backends
        }

    val thermalStatus: Int get() = device.thermalStatus

    /** Free memory as Android counts it, and whether it considers itself low. */
    fun memory(): Pair<Long, Boolean> = device.memory()

    val loadedModel: InstalledPhoneModel? get() = sessionModel.takeIf { session?.isClosed == false }

    /**
     * Everything that has to be true before the phone answers with [model]: the library,
     * the temperature, the memory, and a file that is still the one that was verified.
     */
    suspend fun preflight(model: InstalledPhoneModel): Preflight {
        if (availability() is LlamaRuntime.Availability.Unavailable) {
            return Preflight.Refused(LlamaRuntime.NOT_AVAILABLE)
        }
        if (ResourceGuard.heat(thermalStatus, model.model.recommended) is ResourceGuard.Heat.Stop) {
            return Preflight.Refused(OnDeviceNotices.TOO_HOT_TO_START)
        }
        if (loadedModel?.id == model.id) return Preflight.Ready
        // Another model's memory comes back before this one's is counted.
        if (loadedModel != null) unload("switching")
        val (available, low) = memory()
        val installed = store.installed()
        noticeAnyKill()
        val decision = ResourceGuard.memory(
            model, available, low, installed, killedWithFreeBytes(model),
            measuredResident = measuredResident(model),
        )
        if (decision is ResourceGuard.Memory.Refuse) {
            return Preflight.Refused(ResourceGuard.refusal(model, decision), decision.alternative)
        }
        if (!store.isIntact(model)) {
            val stillGood = withContext(Dispatchers.IO) { store.reverify(model) }
            if (!stillGood) return Preflight.Refused(OnDeviceNotices.changedOnDisk(model.label))
        }
        if (decision is ResourceGuard.Memory.Tight) {
            return Preflight.Warned(
                ResourceGuard.warning(model, decision) +
                    if (log.killedModelID == model.id) " " + OnDeviceNotices.SHORTER_AFTER_A_KILL else "",
            )
        }
        return Preflight.Ready
    }

    // MARK: - What the phone has already done about it

    /**
     * Whether the phone ended this app while it was holding a model.
     *
     * Over the whole window, not only the load: the peak is in the middle of an answer,
     * when the context and the compute buffers are all in use, and the first version of
     * this looked only at a load in flight — so it missed the likeliest kill of all and
     * held a stale "loading" record against a model hours later.
     *
     * Reason-agnostic, deliberately. One UI reports some of its reclaiming under reasons
     * other than `REASON_LOW_MEMORY`; what is not in doubt is that this app did not choose
     * to end while it was holding a gigabyte of weights.
     */
    private fun noticeAnyKill() {
        val (endedAt, unwanted) = device.lastUnwantedExit() ?: return
        if (endedAt <= log.noticedKillAt) return
        log.noticedKillAt = endedAt
        if (!unwanted) return
        val model = log.busyModelID ?: return
        val from = log.busySince
        if (from == 0L || endedAt < from) return
        // The window is kept open while a model is held, and its end is the last time this
        // app was seen alive; an ending after that is the phone's doing, not a coincidence.
        if (endedAt > log.busyUntil + KILL_WINDOW_MS) return
        log.killedModelID = model
        // What the phone had free when it decided it needed this app's memory more than
        // this app did. Whatever the arithmetic says, that much is not enough.
        log.killedWithFreeBytes = log.loadingFreeBytes
        android.util.Log.i(LOG, "the phone ended this app while it held a model; it will ask for less")
    }

    /**
     * After a kill, the arithmetic is not the last word: what the phone actually did is.
     * The floor rises to what was free at the attempt that killed it, so the band that
     * said "tight but fine" no longer covers the reading that proved otherwise.
     */
    private fun killedWithFreeBytes(model: InstalledPhoneModel): Long =
        if (log.killedModelID == model.id) log.killedWithFreeBytes else 0

    /** What this phone measured a load of [model] costing it at exactly [contextTokens]. */
    fun measuredAt(model: InstalledPhoneModel, contextTokens: Int): Long =
        log.measuredResident(model.id, contextTokens)

    /** What this phone measured a load of [model] costing it, at the context it will use. */
    internal fun measuredResident(model: InstalledPhoneModel): Long? =
        log.measuredResident(model.id, contextFor(model)).takeIf { it > 0 }

    /** The floor this phone will apply to [model] — for the Settings row and the sheet. */
    fun floorFor(model: InstalledPhoneModel): Long? =
        ResourceGuard.residentFloor(model.model, measuredResident(model))

    /**
     * The context to load with: the Mac's, the shorter one the owner chose for this model,
     * and never more than half of the Mac's after a kill.
     */
    internal fun contextFor(model: InstalledPhoneModel): Int {
        val recommended = model.model.recommended.contextLength
        val chosen = log.chosenContext(model.id).takeIf { it in 1 until recommended } ?: recommended
        if (log.killedModelID != model.id) return chosen
        return maxOf(ResourceGuard.SMALLEST_CONTEXT, minOf(chosen, recommended / 2))
    }

    /** What the owner chose for [model], or the Mac's recommendation. */
    fun chosenContext(model: InstalledPhoneModel): Int = contextFor(model)

    /** Remember a shorter context for this model, and let the loaded one go so it takes. */
    fun chooseContext(model: InstalledPhoneModel, tokens: Int) {
        log.chooseContext(model.id, tokens)
        if (sessionModel?.id == model.id) scope.launch { unload("context-changed") }
    }

    /** What letting go of everything this app holds recovered, and whether it held a model. */
    data class Recovered(val before: Long, val after: Long, val hadModel: Boolean) {
        val freed: Long get() = after - before
    }

    /**
     * Gives back everything this app is holding: the model, and its own caches.
     *
     * It is the only half of "make room" this app can do — Android does not let one app
     * close another — so it is done first, before the owner is asked to close anything.
     */
    suspend fun makeRoom(): Recovered {
        val before = device.memory().first
        val hadModel = session != null
        if (hadModel) unload("make-room")
        withContext(Dispatchers.IO) {
            context?.let { app ->
                runCatching { app.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
                runCatching { app.codeCacheDir.listFiles()?.forEach { it.deleteRecursively() } }
            }
        }
        // The kernel does not count freed pages back the instant they are freed, and a
        // number that has not moved yet reads as a button that did nothing.
        delay(SETTLE_MS)
        return Recovered(before, device.memory().first, hadModel)
    }

    // MARK: - Answering

    /**
     * The answer to [history], as the chat already reads a Mac's: tokens, then `Finished`
     * with the phone's own numbers — or `Failed` with the reason it stopped.
     */
    fun answer(model: InstalledPhoneModel, history: List<ChatMessage>, maxTokens: Int): Flow<ChatStreamEvent> {
        // What was said, including an answer that was stopped part-way: that is still what
        // the owner read. An empty reply — one that failed before a word — is left out.
        val messages = history
            .filter { it.role in setOf(ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT, ChatMessage.ROLE_SYSTEM) }
            .filter { it.content.isNotBlank() }
            .map { LlamaMessage(it.role, it.content) }
        return generate(
            model,
            LlamaRequest(
                messages = messages,
                thinking = model.model.recommended.thinking,
                maxTokens = maxTokens,
                sampling = LlamaSampling.chat(),
            ),
        )
    }

    /** Any request, with the same guards. The chat uses [answer]; tests complete raw text. */
    fun generate(model: InstalledPhoneModel, request: LlamaRequest): Flow<ChatStreamEvent> = flow {
        val loaded = try {
            load(model)
        } catch (failure: OnDeviceFailure) {
            emit(ChatStreamEvent.Failed(failure.message ?: "The model on this phone would not load."))
            return@flow
        }
        if (!inForeground) {
            emit(ChatStreamEvent.Failed(OnDeviceNotices.LEFT_APP))
            return@flow
        }
        val plan = when (val heat = ResourceGuard.heat(thermalStatus, model.model.recommended)) {
            ResourceGuard.Heat.Stop -> {
                emit(ChatStreamEvent.Failed(OnDeviceNotices.TOO_HOT_TO_START))
                return@flow
            }
            is ResourceGuard.Heat.Run -> heat.threads
        }
        loaded.setThreads(plan.prompt, plan.generate)
        // Still holding it, and about to hold the most of it: this is where the context
        // and the compute buffers are all in use, and where a phone short of memory takes
        // the app. The window stays open until it is let go of.
        log.noteStillBusy(clock())
        stopReason = null
        cancelUnloadTimer()
        _state.value = State.Answering(model, plan)
        val thinking = ThinkingSplitter()
        try {
            loaded.generate(request).collect { event ->
                when (event) {
                    is LlamaEvent.Text -> thinking.feed(event.text).forEach { emit(it) }
                    is LlamaEvent.Failed -> emit(ChatStreamEvent.Failed(event.message))
                    is LlamaEvent.Finished -> {
                        thinking.finish().forEach { emit(it) }
                        when (event.stop) {
                            LlamaStop.Cancelled -> emit(ChatStreamEvent.Failed(sentence(stopReason)))
                            LlamaStop.PromptTooLong -> emit(
                                ChatStreamEvent.Failed(
                                    "That is more than ${model.label} can read at once on this phone. " +
                                        "Start a new conversation with a shorter question.",
                                ),
                            )
                            else -> {
                                // It answered, and the phone is still here: whatever the
                                // last kill was about, this model is not it any more.
                                if (log.killedModelID == model.id) {
                                    log.killedModelID = null
                                    log.killedWithFreeBytes = 0
                                }
                                emit(
                                ChatStreamEvent.Finished(
                                    ChatMetrics(
                                        promptTokens = event.metrics.promptTokens,
                                        generatedTokens = event.metrics.generatedTokens,
                                        tokensPerSecond = event.metrics.tokensPerSecond,
                                        timeToFirstToken = event.metrics.firstTokenMillis / 1000.0,
                                    ),
                                ),
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            if (session === loaded && !loaded.isClosed) {
                _state.value = State.Loaded(model, plan)
                scheduleUnload(if (inForeground) ResourceGuard.IDLE_MS else ResourceGuard.BACKGROUND_GRACE_MS, if (inForeground) "idle" else "background")
            }
        }
    }

    /** Stops the answer in progress. The chat's own Stop goes through the flow instead. */
    fun cancel(reason: StopReason = StopReason.User) {
        stopReason = reason
        session?.cancel()
    }

    /**
     * Stops a load in progress. Stop during the minute a model takes to arrive in memory has
     * to reach llama.cpp itself; cancelling the flow alone would leave it loading.
     */
    fun cancelLoading() {
        if (_state.value is State.Loading) runtime.cancelLoading()
    }

    private fun sentence(reason: StopReason?): String = when (reason) {
        StopReason.Heat -> OnDeviceNotices.TOO_HOT
        StopReason.Background -> OnDeviceNotices.LEFT_APP
        StopReason.Memory -> OnDeviceNotices.UNLOADED_FOR_MEMORY
        StopReason.User, null -> OnDeviceNotices.STOPPED
    }

    // MARK: - Loading and letting go

    private suspend fun load(model: InstalledPhoneModel): ModelSession = mutex.withLock {
        session?.let { current ->
            if (!current.isClosed && sessionModel?.id == model.id) return current
            current.close()
        }
        session = null
        sessionModel = null
        if (availability() is LlamaRuntime.Availability.Unavailable) throw OnDeviceFailure(LlamaRuntime.NOT_AVAILABLE)
        val plan = when (val heat = ResourceGuard.heat(thermalStatus, model.model.recommended)) {
            is ResourceGuard.Heat.Run -> heat.threads
            ResourceGuard.Heat.Stop -> throw OnDeviceFailure(OnDeviceNotices.TOO_HOT_TO_START)
        }
        // Nothing is read into memory before the file is the one that was verified: the
        // preflight checks this too, but a load can also be reached from a widget or a
        // retry, and a swapped file is the one thing llama.cpp will happily read.
        if (!store.isIntact(model) && !withContext(Dispatchers.IO) { store.reverify(model) }) {
            throw OnDeviceFailure(OnDeviceNotices.changedOnDisk(model.label))
        }
        _state.value = State.Loading(model)
        // Written down before the largest allocation this app ever makes, so that if the
        // phone takes the app for it — then, or at any point while it is held — the next
        // run can tell that is what happened.
        val context = contextFor(model)
        log.noteBusy(model.id, clock(), device.memory().first)
        val anonymousBefore = device.anonymousBytes()
        try {
            val opened = runtime.open(
                store.file(model).absolutePath,
                LlamaSession.Settings(
                    contextLength = context,
                    threadsPrompt = plan.prompt,
                    threadsGenerate = plan.generate,
                ),
            )
            // What it actually cost this phone, rather than what another phone's
            // measurement implies: anonymous memory is the part no kernel can take back
            // cheaply, and KleidiAI's repacked weights are in it.
            val anonymous = device.anonymousBytes() - anonymousBefore
            if (anonymous > 0) log.noteResident(model.id, context, anonymous)
            log.noteStillBusy(clock())
            session = opened
            sessionModel = model
            _state.value = State.Loaded(model, plan)
            listenToHeat(true)
            scheduleUnload(if (inForeground) ResourceGuard.IDLE_MS else ResourceGuard.BACKGROUND_GRACE_MS, if (inForeground) "idle" else "background")
            opened
        } catch (cancelled: CancellationException) {
            // The answer this was for is gone. LlamaSession.open frees what it made; all
            // that is left here is to stop calling this loaded — and to close the window,
            // so a kill hours later is not laid at this model's door.
            log.noteIdle()
            _state.value = State.Unloaded
            session = null
            sessionModel = null
            throw cancelled
        } catch (failure: IllegalStateException) {
            log.noteIdle()
            _state.value = State.Unloaded
            throw OnDeviceFailure(
                if (failure.message == "cancelled") OnDeviceNotices.LEFT_APP
                else "${model.label} would not load on this phone: ${failure.message}",
            )
        }
    }

    /** Frees the model now. Any answer in progress stops first. */
    suspend fun unload(reason: String) {
        log.noteIdle()
        session?.cancel()
        mutex.withLock {
            val current = session ?: return
            current.close()
            session = null
            sessionModel = null
            _state.value = State.Unloaded
            lastUnloadReason = reason
            listenToHeat(false)
            cancelUnloadTimer()
            // Why, as a fixed word — never the model's name or anything it was asked.
            android.util.Log.i(LOG, "phone model unloaded: $reason")
        }
    }

    private fun scheduleUnload(afterMillis: Long, reason: String) {
        synchronized(this) {
            unloadTimer?.cancel()
            unloadTimer = scope.launch {
                delay(afterMillis)
                unload(reason)
            }
        }
    }

    private fun cancelUnloadTimer() {
        synchronized(this) {
            unloadTimer?.cancel()
            unloadTimer = null
        }
    }

    // MARK: - The app on and off the screen

    /** The app left the screen: stop writing, and let go of the model after a grace. */
    fun appLeftForeground() {
        inForeground = false
        if (_state.value is State.Answering) cancel(StopReason.Background)
        if (_state.value is State.Loading) runtime.cancelLoading()
        if (session != null) scheduleUnload(ResourceGuard.BACKGROUND_GRACE_MS, "background")
    }

    /** Back on screen: the model stays, on the idle clock again. */
    fun appCameToForeground() {
        inForeground = true
        if (session != null && _state.value !is State.Answering) scheduleUnload(ResourceGuard.IDLE_MS, "idle")
    }

    // MARK: - Heat

    private fun listenToHeat(listen: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val context = context ?: return
        val manager = context.getSystemService(PowerManager::class.java) ?: return
        synchronized(this) {
            if (listen && !listeningToHeat) {
                runCatching { manager.addThermalStatusListener(context.mainExecutor, heatListener) }
                listeningToHeat = true
            } else if (!listen && listeningToHeat) {
                runCatching { manager.removeThermalStatusListener(heatListener) }
                listeningToHeat = false
            }
        }
    }

    internal fun onHeat(status: Int) {
        val model = sessionModel ?: return
        when (val heat = ResourceGuard.heat(status, model.model.recommended)) {
            ResourceGuard.Heat.Stop -> if (_state.value is State.Answering) cancel(StopReason.Heat)
            is ResourceGuard.Heat.Run -> {
                session?.setThreads(heat.threads.prompt, heat.threads.generate)
                when (val current = _state.value) {
                    is State.Answering -> _state.value = current.copy(threads = heat.threads)
                    is State.Loaded -> _state.value = current.copy(threads = heat.threads)
                    else -> Unit
                }
            }
        }
    }

    companion object {
        private const val LOG = "SiliconBuddy"

        /** How long to let the kernel count freed memory back before reading it again. */
        private const val SETTLE_MS = 400L

        /**
         * How long after this app was last seen holding a model an ending still counts as
         * the phone taking it. A minute: the app writes the window's end as it works, and
         * a process that dies a minute after the last sign of life died doing that.
         */
        private const val KILL_WINDOW_MS = 60_000L

        @Volatile private var instance: OnDeviceEngine? = null

        fun get(context: Context): OnDeviceEngine = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                OnDeviceEngine(
                    store = ModelStore(app),
                    runtime = NativeModelRuntime(app.applicationInfo.nativeLibraryDir),
                    device = AndroidDeviceState(app),
                    log = AndroidMemoryLog(app),
                ).also {
                    it.watch(app)
                    instance = it
                }
            }
        }

        /** The engine if something already made it; never makes one. */
        fun existing(): OnDeviceEngine? = instance

        /** Whether this phone could run a model at all, without loading anything. */
        val couldRun: Boolean
            get() = LlamaRuntime.current !is LlamaRuntime.Availability.Unavailable &&
                "arm64-v8a" in Build.SUPPORTED_64_BIT_ABIS && Process.is64Bit()
    }
}

class OnDeviceFailure(message: String) : Exception(message)

/**
 * Routes a leading `<think>…</think>` block to the folded "thinking" row.
 *
 * Both models answer with thinking off, where the template already closes the block before
 * the answer begins, so normally this passes everything through as answer. It exists for a
 * model or a template that thinks anyway: a phone should show an answer, not a monologue.
 */
class ThinkingSplitter {
    private var buffer = StringBuilder()
    private var state = State.Undecided

    private enum class State { Undecided, Thinking, Answering }

    fun feed(text: String): List<ChatStreamEvent> {
        val out = mutableListOf<ChatStreamEvent>()
        when (state) {
            State.Answering -> if (text.isNotEmpty()) out += ChatStreamEvent.Token(text)
            State.Undecided -> {
                buffer.append(text)
                val trimmed = buffer.trimStart()
                when {
                    trimmed.startsWith(OPEN) -> {
                        state = State.Thinking
                        val rest = trimmed.removePrefix(OPEN).toString()
                        buffer = StringBuilder()
                        out += feed(rest)
                    }
                    OPEN.startsWith(trimmed) -> Unit // could still be the tag
                    else -> {
                        state = State.Answering
                        out += ChatStreamEvent.Token(buffer.toString())
                        buffer = StringBuilder()
                    }
                }
            }
            State.Thinking -> {
                buffer.append(text)
                val end = buffer.indexOf(CLOSE)
                if (end >= 0) {
                    val thought = buffer.substring(0, end)
                    val rest = buffer.substring(end + CLOSE.length).trimStart()
                    if (thought.isNotEmpty()) out += ChatStreamEvent.Reasoning(thought)
                    buffer = StringBuilder()
                    state = State.Answering
                    if (rest.isNotEmpty()) out += ChatStreamEvent.Token(rest)
                } else {
                    // Everything but what could be the start of the closing tag.
                    val keep = (1 until CLOSE.length).lastOrNull { buffer.endsWith(CLOSE.substring(0, it)) } ?: 0
                    val ready = buffer.substring(0, buffer.length - keep)
                    if (ready.isNotEmpty()) out += ChatStreamEvent.Reasoning(ready)
                    buffer = StringBuilder(buffer.substring(buffer.length - keep))
                }
            }
        }
        return out
    }

    fun finish(): List<ChatStreamEvent> {
        val rest = buffer.toString()
        buffer = StringBuilder()
        return when {
            rest.isEmpty() -> emptyList()
            state == State.Thinking -> listOf(ChatStreamEvent.Reasoning(rest))
            else -> listOf(ChatStreamEvent.Token(rest))
        }
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}
