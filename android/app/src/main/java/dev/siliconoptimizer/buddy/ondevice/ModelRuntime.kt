package dev.siliconoptimizer.buddy.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.siliconoptimizer.buddy.chat.ChatMessage
import dev.siliconoptimizer.buddy.llama.LlamaEvent
import dev.siliconoptimizer.buddy.llama.LlamaRequest
import dev.siliconoptimizer.buddy.llama.LlamaRuntime
import dev.siliconoptimizer.buddy.llama.LlamaSession
import kotlinx.coroutines.flow.Flow

/**
 * What the engine needs of llama.cpp, and of the phone it runs on.
 *
 * Both are interfaces so the engine's own rules — refusing for memory, re-checking a file
 * that changed, stopping when the phone is too hot, freeing a model whose answer was
 * abandoned — can be tested where they are *applied*, and not only as tables. The real
 * ones are [NativeModelRuntime] and [AndroidDeviceState].
 */
interface ModelRuntime {
    suspend fun availability(): LlamaRuntime.Availability

    /**
     * Loads a model. If the caller is cancelled while this is running, the implementation
     * frees whatever it made rather than leaking it, and throws `CancellationException`.
     */
    suspend fun open(path: String, settings: LlamaSession.Settings): ModelSession

    /** Asks a load in progress to give up at its next progress report. */
    fun cancelLoading()
}

/** One loaded model. */
interface ModelSession {
    val isClosed: Boolean
    fun generate(request: LlamaRequest): Flow<LlamaEvent>
    fun cancel()
    fun setThreads(prompt: Int, generate: Int)
    suspend fun close()
}

/** What the engine reads about the phone right now. */
interface DeviceState {
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int

    /** Available bytes, and whether Android considers itself low on memory. */
    fun memory(): Pair<Long, Boolean>
}

/** llama.cpp itself. */
class NativeModelRuntime(private val libraryDir: String) : ModelRuntime {
    override suspend fun availability() = LlamaRuntime.availability(libraryDir)

    override suspend fun open(path: String, settings: LlamaSession.Settings): ModelSession =
        NativeModelSession(LlamaSession.open(path, settings))

    override fun cancelLoading() = LlamaSession.cancelLoading()
}

private class NativeModelSession(private val session: LlamaSession) : ModelSession {
    override val isClosed: Boolean get() = session.isClosed
    override fun generate(request: LlamaRequest) = session.generate(request)
    override fun cancel() = session.cancel()
    override fun setThreads(prompt: Int, generate: Int) = session.setThreads(prompt, generate)
    override suspend fun close() = session.close()
}

/** The phone, through Android. */
class AndroidDeviceState(context: Context) : DeviceState {
    private val power = context.getSystemService(PowerManager::class.java)
    private val activities = context.getSystemService(ActivityManager::class.java)

    override val thermalStatus: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: 0 else 0

    override fun memory(): Pair<Long, Boolean> {
        val info = ActivityManager.MemoryInfo()
        activities?.getMemoryInfo(info) ?: return Long.MAX_VALUE to false
        return info.availMem to info.lowMemory
    }
}

/**
 * How much of a conversation the phone's model is given.
 *
 * The context is 4,096 tokens and the prompt is read at about 120 tokens a second on the
 * owner's phone, so a long conversation would cost tens of seconds before the first word —
 * and the oldest turns are the least useful. About 1,500 tokens of the newest ones go, and
 * the reply says plainly that the rest did not.
 */
object PhoneHistory {

    const val BUDGET_TOKENS = 1_500

    /** Four characters to a token is close enough for English, and never under-counts badly. */
    fun tokensIn(text: String): Int = (text.length + 3) / 4

    data class Capped(val messages: List<ChatMessage>, val dropped: Int) {
        val wasTrimmed: Boolean get() = dropped > 0
    }

    /**
     * The newest messages that fit in [budget], oldest dropped first — always keeping the
     * last message, however long it is, because that is the question being asked.
     */
    fun cap(messages: List<ChatMessage>, budget: Int = BUDGET_TOKENS): Capped {
        val said = messages.filter { it.content.isNotBlank() }
        if (said.isEmpty()) return Capped(said, 0)
        var total = 0
        var first = said.size - 1
        for (index in said.indices.reversed()) {
            val cost = tokensIn(said[index].content)
            if (index < said.size - 1 && total + cost > budget) break
            total += cost
            first = index
        }
        return Capped(said.subList(first, said.size), first)
    }
}
