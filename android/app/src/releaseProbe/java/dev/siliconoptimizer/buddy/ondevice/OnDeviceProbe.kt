package dev.siliconoptimizer.buddy.ondevice

import android.content.Context
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.llama.LlamaRequest
import dev.siliconoptimizer.buddy.llama.LlamaSampling
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.transport.ChatStreamEvent
import dev.siliconoptimizer.buddy.transport.ControlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The phone's model as an instrumented test reaches it: by name, from inside the minified
 * release build.
 *
 * The tests are about what R8 left behind — the JNI entry points, the engine, the real
 * downloader against a stand-in Mac — so they cannot use app classes directly (R8 renames
 * them). This one is kept by name (proguard-rules.pro) and speaks only in strings and
 * numbers, which the Java test side can read without anything from this app's classpath.
 * Nothing here is reachable from outside the process: it is not a component, and it does
 * nothing the Settings screen cannot.
 */
object OnDeviceProbe {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<JSONObject>()
    private var chat: Job? = null
    @Volatile private var chatStarted = 0L

    /** Downloads [modelID] from the paired Mac through the app's own downloader. "" or why not. */
    @JvmStatic
    fun download(context: Context, modelID: String): String = runBlocking(Dispatchers.IO) {
        val config = TokenStore(context).load() ?: return@runBlocking "not paired"
        try {
            ModelDownloader(ControlClient(config), ModelStore(context), AndroidSpace(context))
                .download(modelID)
            ""
        } catch (failure: DownloadFailure) {
            failure.message ?: "failed"
        }
    }

    /**
     * Leaves [bytes] of a part-finished download on the phone, as a cancelled one would:
     * the reserved file and the record of how much of it arrived. The Settings screen reads
     * it from disk, which is what makes it survive a restart.
     */
    @JvmStatic
    fun partial(context: Context, sha256: String, bytes: Long): String {
        val store = ModelStore(context)
        val part = store.partFile(sha256)
        part.parentFile?.mkdirs()
        java.io.RandomAccessFile(part, "rw").use { it.setLength(bytes) }
        store.noteReceived(sha256, bytes)
        return part.name
    }

    /** The verified models, as `id,id`. */
    @JvmStatic
    fun installed(context: Context): String = ModelStore(context).installed().joinToString(",") { it.id }

    /** The phone's own verification, again, from the bytes on disk: the file's SHA-256. */
    @JvmStatic
    fun sha256(context: Context, modelID: String): String {
        val store = ModelStore(context)
        val entry = store.installed(modelID) ?: return ""
        return ModelStore.sha256(store.file(entry))
    }

    /** llama.cpp's account of its backends: the CPU variant it chose and that variant's features. */
    @JvmStatic
    fun backends(context: Context): String = runBlocking {
        when (val availability = OnDeviceEngine.get(context).availability()) {
            is dev.siliconoptimizer.buddy.llama.LlamaRuntime.Availability.Ready -> availability.backends
            is dev.siliconoptimizer.buddy.llama.LlamaRuntime.Availability.Unavailable -> "unavailable: ${availability.reason}"
        }
    }

    /**
     * Completes [prompt] as raw text — no chat template — and returns
     * `{"text", "generated", "stop", "failure"}`. [greedy] makes it deterministic.
     */
    @JvmStatic
    fun complete(context: Context, modelID: String, prompt: String, maxTokens: Int, greedy: Boolean): String = runBlocking {
        val engine = OnDeviceEngine.get(context)
        val model = ModelStore(context).installed(modelID) ?: return@runBlocking JSONObject().put("failure", "not installed").toString()
        val text = StringBuilder()
        val result = JSONObject()
        engine.generate(
            model,
            LlamaRequest(
                rawPrompt = prompt,
                maxTokens = maxTokens,
                sampling = if (greedy) LlamaSampling.Greedy else LlamaSampling.chat(seed = 42),
            ),
        ).collect { event ->
            when (event) {
                is ChatStreamEvent.Token -> text.append(event.text)
                is ChatStreamEvent.Reasoning -> text.append(event.text)
                is ChatStreamEvent.Finished -> result.put("generated", event.metrics.generatedTokens)
                    .put("promptTokens", event.metrics.promptTokens)
                    .put("tokensPerSecond", event.metrics.tokensPerSecond)
                is ChatStreamEvent.Failed -> result.put("failure", event.message)
            }
        }
        result.put("text", text.toString()).toString()
    }

    /**
     * Starts answering [question] through the chat's own path — the model's template, its
     * recommended settings, the guards — in the background. [events] says what came back.
     */
    @JvmStatic
    fun startChat(context: Context, modelID: String, question: String, maxTokens: Int): String {
        val engine = OnDeviceEngine.get(context)
        val model = ModelStore(context).installed(modelID) ?: return "not installed"
        chat?.cancel()
        events.clear()
        chatStarted = System.nanoTime()
        chat = scope.launch {
            engine.answer(model, listOf(ChatMessage(role = ChatMessage.ROLE_USER, content = question)), maxTokens)
                .collect { event ->
                    val at = (System.nanoTime() - chatStarted) / 1_000_000
                    events += when (event) {
                        is ChatStreamEvent.Token -> JSONObject().put("type", "token").put("text", event.text).put("at", at)
                        is ChatStreamEvent.Reasoning -> JSONObject().put("type", "reasoning").put("at", at)
                        is ChatStreamEvent.Finished -> JSONObject().put("type", "finished").put("at", at)
                            .put("promptTokens", event.metrics.promptTokens)
                            .put("generated", event.metrics.generatedTokens)
                            .put("tokensPerSecond", event.metrics.tokensPerSecond)
                            .put("firstToken", event.metrics.timeToFirstToken ?: 0.0)
                        is ChatStreamEvent.Failed -> JSONObject().put("type", "failed").put("message", event.message).put("at", at)
                    }
                }
            events += JSONObject().put("type", "closed").put("at", (System.nanoTime() - chatStarted) / 1_000_000)
        }
        return ""
    }

    /**
     * The prompt [question] renders to through [modelID]'s own chat template, with thinking
     * as the Mac recommends — or as [thinking] says, when it is "on" or "off".
     */
    @JvmStatic
    fun renderPrompt(context: Context, modelID: String, question: String, thinking: String): String = runBlocking {
        val model = ModelStore(context).installed(modelID) ?: return@runBlocking "not installed"
        OnDeviceEngine.get(context).availability()
        dev.siliconoptimizer.buddy.llama.LlamaSession.renderPrompt(
            ModelStore(context).file(model).absolutePath,
            listOf(dev.siliconoptimizer.buddy.llama.LlamaMessage(ChatMessage.ROLE_USER, question)),
            when (thinking) {
                "on" -> true
                "off" -> false
                else -> model.model.recommended.thinking
            },
        )
    }

    /** Everything [startChat]'s answer has said so far, with milliseconds since it started. */
    @JvmStatic
    fun events(): String = JSONArray(events.toList()).toString()

    /** Stops the answer the way the chat's Stop does. Returns the moment, on [events]'s clock. */
    @JvmStatic
    fun cancel(context: Context): Long {
        val at = (System.nanoTime() - chatStarted) / 1_000_000
        OnDeviceEngine.get(context).cancel()
        return at
    }

    /** `Unloaded`, `Loading:<id>`, `Loaded:<id>` or `Answering:<id>`. */
    @JvmStatic
    fun state(context: Context): String = when (val state = OnDeviceEngine.get(context).state.value) {
        OnDeviceEngine.State.Unloaded -> "Unloaded"
        is OnDeviceEngine.State.Loading -> "Loading:${state.model.id}"
        is OnDeviceEngine.State.Loaded -> "Loaded:${state.model.id}"
        is OnDeviceEngine.State.Answering -> "Answering:${state.model.id}"
    }

    @JvmStatic
    fun lastUnloadReason(context: Context): String = OnDeviceEngine.get(context).lastUnloadReason ?: ""

    @JvmStatic
    fun unload(context: Context) = runBlocking { OnDeviceEngine.get(context).unload("test") }

    /** Deletes a model from the phone, as Settings does. */
    @JvmStatic
    fun delete(context: Context, modelID: String) = runBlocking {
        val engine = OnDeviceEngine.get(context)
        if (engine.loadedModel?.id == modelID) engine.unload("deleted")
        val store = ModelStore(context)
        store.deleteEverything(modelID, store.installed(modelID)?.model?.sha256)
    }

    /** This process's resident memory, from the kernel. */
    @JvmStatic
    fun residentBytes(): Long = File("/proc/self/status").readLines()
        .firstOrNull { it.startsWith("VmRSS:") }
        ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024) ?: -1
}
