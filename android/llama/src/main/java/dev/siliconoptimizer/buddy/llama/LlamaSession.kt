package dev.siliconoptimizer.buddy.llama

import android.os.Build
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one thread every llama.cpp call runs on.
 *
 * A context is not safe to touch from two threads, and loading, answering and unloading
 * have to happen in order anyway: a model freed while an answer is still being written is
 * a crash. One thread makes that ordering the executor's job rather than every caller's.
 * llama.cpp starts its own worker threads for the arithmetic; this one only drives them.
 */
object LlamaThread {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama").apply { isDaemon = true }
    }
    val dispatcher = executor.asCoroutineDispatcher()
    internal val scope = CoroutineScope(SupervisorJob() + dispatcher)
}

/** Whether this phone can run the model at all, and which CPU backend llama.cpp picked. */
object LlamaRuntime {

    sealed interface Availability {
        /** [backends] is llama.cpp's own account: the CPU variant's library and its features. */
        data class Ready(val backends: String) : Availability
        data class Unavailable(val reason: String) : Availability
    }

    const val NOT_AVAILABLE =
        "The phone's own model is not available on this phone: it needs a 64-bit ARM processor."

    @Volatile private var known: Availability? = null

    /**
     * Loads the library and llama.cpp's backends, once. [libraryDir] is the app's
     * `nativeLibraryDir`, where the per-generation CPU libraries were unpacked at install.
     */
    suspend fun availability(libraryDir: String): Availability {
        known?.let { return it }
        return withContext(LlamaThread.dispatcher) {
            known ?: probe(libraryDir).also { known = it }
        }
    }

    /** What is known already, without loading anything. */
    val current: Availability? get() = known

    private fun probe(libraryDir: String): Availability {
        // A 32-bit phone — or a 32-bit process on a 64-bit one — has no arm64 library to
        // load, and the fast paths llama.cpp relies on are AArch64 only.
        if ("arm64-v8a" !in Build.SUPPORTED_64_BIT_ABIS || !Process.is64Bit()) {
            return Availability.Unavailable(NOT_AVAILABLE)
        }
        return try {
            System.loadLibrary("buddy_llama")
            Availability.Ready(LlamaNative.nativeInit(libraryDir))
        } catch (missing: UnsatisfiedLinkError) {
            // An APK built for another ABI (an x86_64 emulator, say) has no library at all.
            Availability.Unavailable(NOT_AVAILABLE)
        }
    }
}

/** One message of a conversation, as the chat template sees it. */
data class LlamaMessage(val role: String, val content: String)

/** How the next token is chosen. Temperature 0 is greedy: the same answer every time. */
data class LlamaSampling(
    val temperature: Float,
    val topK: Int = 0,
    val topP: Float = 1f,
    val minP: Float = 0f,
    val seed: Int = 0,
) {
    companion object {
        val Greedy = LlamaSampling(temperature = 0f)

        /** Qwen's own advice for answering without thinking; sensible for Gemma too. */
        fun chat(seed: Int = (System.nanoTime() and 0x7fffffff).toInt()) =
            LlamaSampling(temperature = 0.7f, topK = 20, topP = 0.8f, minP = 0f, seed = seed)
    }
}

/**
 * What to answer. A conversation goes through the model's own chat template; [rawPrompt]
 * is completed as it stands, which is what a model with no template — or a test — needs.
 */
data class LlamaRequest(
    val messages: List<LlamaMessage> = emptyList(),
    val rawPrompt: String? = null,
    val thinking: Boolean = false,
    val maxTokens: Int = 1024,
    val sampling: LlamaSampling = LlamaSampling.chat(),
)

enum class LlamaStop { EndOfTurn, Length, StopString, Cancelled, PromptTooLong }

/** llama.cpp's numbers for one answer. */
data class LlamaMetrics(
    val promptTokens: Int,
    /** How many of [promptTokens] had to be read; the rest were still in memory. */
    val promptEvaluated: Int,
    val generatedTokens: Int,
    val promptMillis: Double,
    val generateMillis: Double,
    val firstTokenMillis: Double,
    val contextLength: Int,
    /** Old turns left out because the conversation outgrew the context. */
    val droppedMessages: Int,
) {
    val tokensPerSecond: Double
        get() = if (generateMillis > 0) generatedTokens / (generateMillis / 1000.0) else 0.0

    companion object {
        fun from(raw: LongArray) = LlamaMetrics(
            promptTokens = raw[LlamaNative.METRIC_PROMPT_TOKENS].toInt(),
            promptEvaluated = raw[LlamaNative.METRIC_PROMPT_EVALUATED].toInt(),
            generatedTokens = raw[LlamaNative.METRIC_GENERATED].toInt(),
            promptMillis = raw[LlamaNative.METRIC_PROMPT_MICROS] / 1000.0,
            generateMillis = raw[LlamaNative.METRIC_GENERATE_MICROS] / 1000.0,
            firstTokenMillis = raw[LlamaNative.METRIC_FIRST_TOKEN_MICROS] / 1000.0,
            contextLength = raw[LlamaNative.METRIC_CONTEXT].toInt(),
            droppedMessages = raw[LlamaNative.METRIC_DROPPED_MESSAGES].toInt(),
        )
    }
}

sealed interface LlamaEvent {
    data class Text(val text: String) : LlamaEvent
    data class Finished(val metrics: LlamaMetrics, val stop: LlamaStop) : LlamaEvent
    data class Failed(val message: String) : LlamaEvent
}

/**
 * A loaded model. Everything that touches it runs on [LlamaThread], in order.
 *
 * [cancel] and [setThreads] may be called from any thread; both are an atomic store on the
 * native side, taken under [lock] so that neither can reach a session [close] has freed.
 */
class LlamaSession private constructor(
    private var handle: Long,
    val contextLength: Int,
) {
    private val lock = Any()
    @Volatile private var closed = false

    /** One answer's Stop. A new answer gets a new one, so a late Stop cannot cut it short. */
    private class Answer {
        val stopped = AtomicBoolean(false)
    }

    @Volatile private var answer: Answer? = null

    val isClosed: Boolean get() = closed

    data class Settings(
        val contextLength: Int = 4096,
        val batch: Int = 512,
        val threadsPrompt: Int = 4,
        val threadsGenerate: Int = 4,
    )

    /**
     * The three native calls a load involves, behind an interface.
     *
     * The rule that matters here — a model whose caller went away is freed, not left
     * sitting in a gigabyte of memory nothing can reach — is a rule about coroutines, and
     * this is what lets it be tested on a JVM, where there is no llama.cpp to load.
     */
    internal interface Loads {
        fun load(path: String, settings: Settings): Long
        fun cancelLoad()
        fun unload(handle: Long)
    }

    companion object {

        private object Native : Loads {
            override fun load(path: String, settings: Settings): Long = LlamaNative.nativeLoad(
                path, settings.contextLength, settings.batch,
                settings.threadsPrompt, settings.threadsGenerate,
            )

            override fun cancelLoad() = LlamaNative.nativeCancelLoad()
            override fun unload(handle: Long) = LlamaNative.nativeUnload(handle)
        }

        /** llama.cpp itself, except in this module's own tests. */
        internal var loads: Loads = Native

        /**
         * Loads [path]. Throws `IllegalStateException` with llama.cpp's reason.
         *
         * The load runs on [LlamaThread] in a coroutine of its own, so a caller that is
         * cancelled half-way — the owner left the app, or closed the chat — cannot walk away
         * from a model that is still arriving. The load is told to stop, and whatever it
         * managed to make is freed before the cancellation is passed on.
         */
        suspend fun open(path: String, settings: Settings): LlamaSession {
            val loading = LlamaThread.scope.async {
                LlamaSession(loads.load(path, settings), settings.contextLength)
            }
            try {
                return loading.await()
            } catch (cancelled: CancellationException) {
                cancelLoading()
                withContext(NonCancellable) {
                    runCatching { loading.await() }.getOrNull()?.close()
                }
                throw cancelled
            }
        }

        /** Asks a load in progress to stop at its next progress report. */
        fun cancelLoading() = loads.cancelLoad()

        /**
         * What [messages] render to through the model's own chat template — the exact text an
         * answer starts from — reading only the vocabulary, not the weights.
         */
        suspend fun renderPrompt(path: String, messages: List<LlamaMessage>, thinking: Boolean): String =
            withContext(LlamaThread.dispatcher) {
                String(
                    LlamaNative.nativeRenderPrompt(
                        path,
                        messages.map { it.role }.toTypedArray(),
                        messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        thinking,
                    ),
                    Charsets.UTF_8,
                )
            }
    }

    /**
     * The answer, a piece at a time, then [LlamaEvent.Finished] — or [LlamaEvent.Failed].
     *
     * Collecting it starts the work; stopping collecting stops it, through the same
     * cancel the app's own Stop uses. The pieces are buffered without limit so the model's
     * thread never waits on the screen.
     */
    fun generate(request: LlamaRequest): Flow<LlamaEvent> = callbackFlow {
        val mine = Answer()
        answer = mine
        val worker = LlamaThread.scope.launch {
            if (closed) {
                trySend(LlamaEvent.Failed("The model was unloaded."))
                channel.close()
                return@launch
            }
            LlamaNative.nativeReset(handle)
            // A Stop that arrived while this waited its turn still counts.
            if (mine.stopped.get()) LlamaNative.nativeCancel(handle)
            val metrics = LongArray(LlamaNative.METRIC_COUNT)
            val sink = object : LlamaSink {
                override fun onText(bytes: ByteArray): Boolean {
                    trySend(LlamaEvent.Text(String(bytes, Charsets.UTF_8)))
                    return true
                }
            }
            try {
                val status = LlamaNative.nativeGenerate(
                    handle,
                    request.messages.takeIf { request.rawPrompt == null }
                        ?.map { it.role }?.toTypedArray(),
                    request.messages.takeIf { request.rawPrompt == null }
                        ?.map { it.content.toByteArray(Charsets.UTF_8) }?.toTypedArray(),
                    request.rawPrompt?.toByteArray(Charsets.UTF_8),
                    request.thinking,
                    request.maxTokens,
                    request.sampling.temperature,
                    request.sampling.topK,
                    request.sampling.topP,
                    request.sampling.minP,
                    request.sampling.seed,
                    sink,
                    metrics,
                )
                trySend(LlamaEvent.Finished(LlamaMetrics.from(metrics), stopOf(status)))
            } catch (failure: IllegalStateException) {
                trySend(LlamaEvent.Failed(failure.message ?: "llama.cpp failed."))
            }
            channel.close()
        }
        awaitClose {
            if (!worker.isCompleted) stop(mine)
            if (answer === mine) answer = null
        }
    }.buffer(Channel.UNLIMITED)

    /** Stops the answer in progress, if there is one. An answer that has ended is left alone. */
    fun cancel() {
        stop(answer ?: return)
    }

    private fun stop(which: Answer) {
        which.stopped.set(true)
        synchronized(lock) {
            if (!closed && answer === which) LlamaNative.nativeCancel(handle)
        }
    }

    /** Fewer threads when the phone is warm; applied from the next token. */
    fun setThreads(prompt: Int, generate: Int) {
        synchronized(lock) {
            if (!closed) LlamaNative.nativeSetThreads(handle, prompt, generate)
        }
    }

    /** Frees the model and its context, after any answer in progress has stopped. */
    suspend fun close() = withContext(NonCancellable) {
        cancel()
        withContext(LlamaThread.dispatcher) {
            synchronized(lock) {
                if (closed) return@withContext
                closed = true
                val freeing = handle
                handle = 0
                loads.unload(freeing)
            }
        }
    }

    private fun stopOf(status: Int): LlamaStop = when (status) {
        LlamaNative.STATUS_END_OF_TURN -> LlamaStop.EndOfTurn
        LlamaNative.STATUS_LENGTH -> LlamaStop.Length
        LlamaNative.STATUS_STOP_STRING -> LlamaStop.StopString
        LlamaNative.STATUS_CANCELLED -> LlamaStop.Cancelled
        LlamaNative.STATUS_PROMPT_TOO_LONG -> LlamaStop.PromptTooLong
        else -> LlamaStop.Cancelled
    }
}
