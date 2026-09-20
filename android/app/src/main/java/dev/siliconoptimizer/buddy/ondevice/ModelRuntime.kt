package dev.siliconoptimizer.buddy.ondevice

import android.app.ActivityManager
import android.app.ApplicationExitInfo
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

    /**
     * When Android last killed this app because the phone ran out of memory, as a wall
     * clock reading — or null if it never did, or no longer remembers.
     *
     * The arithmetic says a model will fit; this says whether it did.
     */
    fun lastLowMemoryKillAt(): Long? = null

    /**
     * How this app's last process ended, if the system still remembers: when, and whether
     * it was an ending the app chose.
     *
     * Not only `REASON_LOW_MEMORY`: One UI reports some of its killing under other
     * reasons, and a phone that took this app while it was holding a model took it for
     * the memory whatever it wrote down. What matters is that the app did not end itself.
     */
    fun lastUnwantedExit(): Pair<Long, Boolean>? = lastLowMemoryKillAt()?.let { it to true }

    /**
     * This process's anonymous memory — what cannot be dropped and read back off a file.
     * `RssAnon` from `/proc/self/status`, and the only honest measure of what a loaded
     * model costs a phone.
     */
    fun anonymousBytes(): Long = 0
}

/**
 * The little the engine remembers between runs.
 *
 * Only this: what was being loaded and when, so a kill that follows can be attributed to
 * it, and which model has already cost the owner their other apps. Four values, in the
 * same preferences file as the choices they are about.
 */
interface MemoryLog {
    /**
      * A load is starting: which model, when, and how much the phone had free — so a kill
      * that follows can be recognised, and the reading that led to it remembered.
      */
    fun noteLoading(modelID: String, at: Long, freeBytes: Long)

    /** It is loaded and this app is still here, or it has been let go of. */
    fun noteSettled()

    /** When the load that may have been interrupted started, or 0 if none was. */
    val loadingSince: Long
    val loadingModelID: String?
    val loadingFreeBytes: Long

    /** The model whose load got this app killed, until an answer from it finishes. */
    var killedModelID: String?

    /** How much was free when that happened, so the arithmetic can be corrected by it. */
    var killedWithFreeBytes: Long

    /** The kill already taken account of, so the same one is not counted twice. */
    var noticedKillAt: Long

    /** A shorter context the owner chose for a model, or 0 for the Mac's recommendation. */
    fun chosenContext(modelID: String): Int

    fun chooseContext(modelID: String, tokens: Int)

    /**
     * What this phone measured itself: the anonymous memory a load of this model actually
     * took, at this context. 0 until it has loaded one successfully.
     */
    fun measuredResident(modelID: String, contextTokens: Int): Long

    fun noteResident(modelID: String, contextTokens: Int, bytes: Long)

    /**
     * When this app was last busy with a model — loading it, holding it, answering from
     * it — as a wall clock window. A kill inside that window is this feature's doing,
     * whichever reason the system recorded for it; outside it, it is not.
     */
    val busySince: Long
    val busyUntil: Long
    val busyModelID: String?

    fun noteBusy(modelID: String, at: Long, freeBytes: Long)

    /** Still busy, as of [at]: the window is kept open while a model is held. */
    fun noteStillBusy(at: Long)

    /**
      * Nothing is held any more — the load failed, was cancelled, or the model was let go
      * of. The window closes: an ending after this is not this feature's doing, and a
      * record left open is how an unrelated kill hours later came to be blamed on the last
      * model that happened to be named in it.
      */
    fun noteIdle()

    /** Everything in memory, for the tests. */
    class InMemory : MemoryLog {
        private var since = 0L
        private var model: String? = null
        private var free = 0L

        override fun noteLoading(modelID: String, at: Long, freeBytes: Long) {
            since = at
            model = modelID
            free = freeBytes
        }

        override fun noteSettled() {
            since = 0L
        }

        override val loadingSince: Long get() = since
        override val loadingModelID: String? get() = model
        override val loadingFreeBytes: Long get() = free
        override var killedModelID: String? = null
        override var killedWithFreeBytes: Long = 0
        override var noticedKillAt: Long = 0
        private val residents = mutableMapOf<String, Long>()
        override fun measuredResident(modelID: String, contextTokens: Int) =
            residents["$modelID@$contextTokens"] ?: 0
        override fun noteResident(modelID: String, contextTokens: Int, bytes: Long) {
            residents["$modelID@$contextTokens"] = bytes
        }

        private var busyFrom = 0L
        private var busyTo = 0L
        private var busyModel: String? = null
        override val busySince: Long get() = busyFrom
        override val busyUntil: Long get() = busyTo
        override val busyModelID: String? get() = busyModel
        override fun noteBusy(modelID: String, at: Long, freeBytes: Long) {
            busyFrom = at
            busyTo = at
            busyModel = modelID
            free = freeBytes
        }

        override fun noteStillBusy(at: Long) {
            if (busyFrom > 0) busyTo = at
        }

        override fun noteIdle() {
            busyFrom = 0
            busyTo = 0
            busyModel = null
        }

        private val contexts = mutableMapOf<String, Int>()
        override fun chosenContext(modelID: String) = contexts[modelID] ?: 0
        override fun chooseContext(modelID: String, tokens: Int) {
            contexts[modelID] = tokens
        }
    }
}

/** The real one: the same preferences file the phone's other choices live in. */
class AndroidMemoryLog(context: Context) : MemoryLog {
    private val preferences =
        context.applicationContext.getSharedPreferences(OnDeviceSettings.FILE, Context.MODE_PRIVATE)

    override fun noteLoading(modelID: String, at: Long, freeBytes: Long) {
        preferences.edit()
            .putLong(SINCE, at)
            .putString(LOADING, modelID)
            .putLong(LOADING_FREE, freeBytes)
            .apply()
    }

    override fun noteSettled() {
        preferences.edit().putLong(SINCE, 0).apply()
    }

    override val loadingSince: Long get() = preferences.getLong(SINCE, 0)
    override val loadingModelID: String? get() = preferences.getString(LOADING, null)
    override val loadingFreeBytes: Long get() = preferences.getLong(LOADING_FREE, 0)

    override var killedModelID: String?
        get() = preferences.getString(KILLED, null)
        set(value) {
            preferences.edit().apply { if (value == null) remove(KILLED) else putString(KILLED, value) }.apply()
        }

    override var killedWithFreeBytes: Long
        get() = preferences.getLong(KILLED_FREE, 0)
        set(value) = preferences.edit().putLong(KILLED_FREE, value).apply()

    override var noticedKillAt: Long
        get() = preferences.getLong(NOTICED, 0)
        set(value) = preferences.edit().putLong(NOTICED, value).apply()

    override fun measuredResident(modelID: String, contextTokens: Int): Long =
        preferences.getLong("$RESIDENT$modelID@$contextTokens", 0)

    override fun noteResident(modelID: String, contextTokens: Int, bytes: Long) {
        preferences.edit().putLong("$RESIDENT$modelID@$contextTokens", bytes).apply()
    }

    override val busySince: Long get() = preferences.getLong(BUSY_FROM, 0)
    override val busyUntil: Long get() = preferences.getLong(BUSY_TO, 0)
    override val busyModelID: String? get() = preferences.getString(BUSY_MODEL, null)

    override fun noteBusy(modelID: String, at: Long, freeBytes: Long) {
        preferences.edit()
            .putLong(BUSY_FROM, at)
            .putLong(BUSY_TO, at)
            .putString(BUSY_MODEL, modelID)
            .putLong(LOADING_FREE, freeBytes)
            .apply()
    }

    override fun noteStillBusy(at: Long) {
        if (busySince > 0) preferences.edit().putLong(BUSY_TO, at).apply()
    }

    override fun noteIdle() {
        preferences.edit().putLong(BUSY_FROM, 0).putLong(BUSY_TO, 0).remove(BUSY_MODEL).apply()
    }

    override fun chosenContext(modelID: String): Int = preferences.getInt(CONTEXT + modelID, 0)

    override fun chooseContext(modelID: String, tokens: Int) {
        preferences.edit().putInt(CONTEXT + modelID, tokens).apply()
    }

    private companion object {
        const val RESIDENT = "resident:"
        const val BUSY_FROM = "busyFrom"
        const val BUSY_TO = "busyTo"
        const val BUSY_MODEL = "busyModel"
        const val CONTEXT = "context:"
        const val SINCE = "loadingSince"
        const val LOADING = "loadingModel"
        const val LOADING_FREE = "loadingFree"
        const val KILLED = "killedModel"
        const val KILLED_FREE = "killedWithFree"
        const val NOTICED = "noticedKillAt"
    }
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
    private val packageName = context.packageName

    override val thermalStatus: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) power?.currentThermalStatus ?: 0 else 0

    override fun memory(): Pair<Long, Boolean> {
        val info = ActivityManager.MemoryInfo()
        activities?.getMemoryInfo(info) ?: return Long.MAX_VALUE to false
        return info.availMem to info.lowMemory
    }

    /**
     * Android's own record of why this app's processes ended. `REASON_LOW_MEMORY` is the
     * one that matters here: the phone took the app to make room, which is the thing the
     * memory gate exists to prevent and the only evidence that it got it wrong — an app
     * killed for memory is told nothing at the time.
     */
    override fun lastLowMemoryKillAt(): Long? = lastUnwantedExit()?.first

    /**
     * The newest exit the system still remembers, and whether the app chose it.
     *
     * A swipe out of Recents, a stop, this app calling it a day — those are chosen, and say
     * nothing about memory. Everything else that ends a process holding a gigabyte of model
     * is worth treating as the phone reclaiming it, whichever reason was recorded: One UI
     * does not always write `REASON_LOW_MEMORY` for what is plainly that.
     */
    override fun lastUnwantedExit(): Pair<Long, Boolean>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val manager = activities ?: return null
        val exits = runCatching {
            manager.getHistoricalProcessExitReasons(packageName, 0, EXITS_READ)
        }.getOrNull().orEmpty()
        val newest = exits.firstOrNull() ?: return null
        val chosen = newest.reason in setOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_USER_STOPPED,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED,
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE,
        )
        return newest.timestamp to !chosen
    }

    /** `RssAnon` from `/proc/self/status`: the memory no kernel can take back cheaply. */
    override fun anonymousBytes(): Long = runCatching {
        java.io.File("/proc/self/status").readLines()
            .firstOrNull { it.startsWith("RssAnon:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024) ?: 0
    }.getOrDefault(0)

    private companion object {
        const val EXITS_READ = 8
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
