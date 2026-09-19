package dev.siliconoptimizer.buddy.llama

/**
 * The JNI entry points, exactly as `buddy_llama.cpp` names them.
 *
 * Bound by name — `Java_dev_siliconoptimizer_buddy_llama_LlamaNative_nativeLoad` and so on —
 * so R8 must keep this class and every `external` member unrenamed. `consumer-rules.pro`
 * says so, the app's CI checks R8's own `seeds.txt` for it, and an instrumented test on the
 * minified build calls through it. Renamed, the first answer on the phone would be an
 * `UnsatisfiedLinkError`.
 *
 * Nothing outside this module calls these directly: [LlamaSession] is the way in, and it
 * keeps every call on one thread.
 */
object LlamaNative {

    // What nativeGenerate returns.
    const val STATUS_END_OF_TURN = 0
    const val STATUS_LENGTH = 1
    const val STATUS_STOP_STRING = 2
    const val STATUS_CANCELLED = 3
    const val STATUS_PROMPT_TOO_LONG = -2

    // Where nativeGenerate writes its numbers.
    const val METRIC_PROMPT_TOKENS = 0
    const val METRIC_PROMPT_EVALUATED = 1
    const val METRIC_GENERATED = 2
    const val METRIC_PROMPT_MICROS = 3
    const val METRIC_GENERATE_MICROS = 4
    const val METRIC_CONTEXT = 5
    const val METRIC_DROPPED_MESSAGES = 6
    const val METRIC_FIRST_TOKEN_MICROS = 7
    const val METRIC_COUNT = 8

    /** Loads every CPU variant's library, keeps the best, and says which. Once per process. */
    @JvmStatic external fun nativeInit(libraryDir: String): String

    /** The backends llama.cpp registered, with the CPU variant's file and its features. */
    @JvmStatic external fun nativeBackends(): String

    @JvmStatic external fun nativeSystemInfo(): String

    /** A model and a context for it, or an `IllegalStateException` saying why not. */
    @JvmStatic external fun nativeLoad(
        path: String,
        contextLength: Int,
        batch: Int,
        threadsPrompt: Int,
        threadsGenerate: Int,
    ): Long

    /** Makes a load in progress give up at its next progress report. */
    @JvmStatic external fun nativeCancelLoad()

    /** Clears a cancel left over from the last answer. Called on the model's thread. */
    @JvmStatic external fun nativeReset(handle: Long)

    /** Stops the answer in progress — between tokens, or inside one. Any thread. */
    @JvmStatic external fun nativeCancel(handle: Long)

    /** Changes the thread counts from the next token on. Any thread. */
    @JvmStatic external fun nativeSetThreads(handle: Long, prompt: Int, generate: Int)

    @JvmStatic external fun nativeUnload(handle: Long)

    /** The prompt a conversation renders to through the model's own template, from its vocabulary alone. */
    @JvmStatic external fun nativeRenderPrompt(
        path: String,
        roles: Array<String>,
        contents: Array<ByteArray>,
        thinking: Boolean,
    ): ByteArray

    /**
     * Writes an answer into [sink], blocking until it is finished, cancelled or too long.
     *
     * [roles] and [contents] are the conversation, rendered through the model's own chat
     * template; or [rawPrompt] is completed as it is, with no template at all. Text crosses
     * as UTF-8 bytes in both directions — JNI's own strings are "modified" UTF-8, in which
     * an emoji is not what either side expects.
     */
    @JvmStatic external fun nativeGenerate(
        handle: Long,
        roles: Array<String>?,
        contents: Array<ByteArray>?,
        rawPrompt: ByteArray?,
        thinking: Boolean,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        seed: Int,
        sink: LlamaSink,
        metrics: LongArray,
    ): Int
}

/**
 * Where the answer goes, a piece at a time. Called by name from native code
 * (`onText`, `([B)Z`), so it is kept by name too.
 */
interface LlamaSink {
    /** Whole UTF-8 characters, never half of one. The return value is ignored for now. */
    fun onText(bytes: ByteArray): Boolean
}
