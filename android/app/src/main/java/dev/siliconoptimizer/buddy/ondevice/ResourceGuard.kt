package dev.siliconoptimizer.buddy.ondevice

import dev.siliconoptimizer.buddy.transport.PhoneModel
import dev.siliconoptimizer.buddy.transport.PhoneModelRecommended
import dev.siliconoptimizer.buddy.ui.Format

/**
 * What the phone's memory and temperature allow, decided without Android so the rules can
 * be tested as rules.
 *
 * The owner's calls (2026-09-19): warmth does not matter much — a phone answering because
 * the Mac is away should keep answering — so heat only thins the threads until it is
 * critical. Memory is the Mac's measurement: below a model's `minFreeMemoryBytes` it is not
 * loaded, and a smaller one that fits is offered instead.
 */
object ResourceGuard {

    /** `PowerManager.THERMAL_STATUS_*`, in order. */
    const val THERMAL_NONE = 0
    const val THERMAL_LIGHT = 1
    const val THERMAL_MODERATE = 2
    const val THERMAL_SEVERE = 3
    const val THERMAL_CRITICAL = 4
    const val THERMAL_EMERGENCY = 5
    const val THERMAL_SHUTDOWN = 6

    /** Threads for reading the prompt, and for writing the answer. */
    data class Threads(val prompt: Int, val generate: Int)

    sealed interface Heat {
        /** Answer, with these threads. [reduced] when the phone is warm enough to thin them. */
        data class Run(val threads: Threads, val reduced: Boolean) : Heat

        /** Critical or worse: stop the answer, and start no new one. */
        data object Stop : Heat
    }

    /**
     * MODERATE drops a quarter of the threads and SEVERE half — never below two — and
     * CRITICAL and beyond stop. Below MODERATE the Mac's recommendation stands.
     */
    fun heat(thermalStatus: Int, recommended: PhoneModelRecommended): Heat {
        val base = Threads(recommended.threadsPrompt.coerceAtLeast(1), recommended.threadsGenerate.coerceAtLeast(1))
        return when {
            thermalStatus >= THERMAL_CRITICAL -> Heat.Stop
            thermalStatus >= THERMAL_SEVERE -> Heat.Run(
                Threads(thin(base.prompt, 2), thin(base.generate, 2)), reduced = true,
            )
            thermalStatus >= THERMAL_MODERATE -> Heat.Run(
                Threads(thin(base.prompt, 4, 3), thin(base.generate, 4, 3)), reduced = true,
            )
            else -> Heat.Run(base, reduced = false)
        }
    }

    /** [count] × [numerator] ÷ [denominator], at least two — or [count] if that is fewer. */
    private fun thin(count: Int, denominator: Int, numerator: Int = 1): Int =
        minOf(count, maxOf(2, count * numerator / denominator))

    sealed interface Memory {
        data object Load : Memory

        /**
         * Room for the part that cannot be paged out, but not for the Mac's whole figure.
         * It will run: the weights come back off the file as the kernel needs them, other
         * apps may be closed for it, and it will be slower. The owner is told before it
         * starts — never after.
         */
        data class Tight(
            val needed: Long,
            val floor: Long,
            val available: Long,
        ) : Memory

        /**
         * Not enough free for the part that must stay resident. [alternative] is a smaller
         * installed model that does fit, when there is one — and on a phone whose Mac
         * offers nothing smaller, there is not.
         */
        data class Refuse(
            val needed: Long,
            val floor: Long,
            val available: Long,
            val alternative: InstalledPhoneModel?,
        ) : Memory
    }

    /** The quarter of headroom the Mac puts on its own figure, applied to this one. */
    const val HEADROOM = 1.25

    /**
     * How much more than the reading that already got this app killed to insist on before
     * trying again. A tenth: memory readings move by tens of megabytes between one look and
     * the next, so "one byte more than last time" would be no protection at all.
     */
    const val KILL_MARGIN = 0.10

    /**
     * What must be free whatever the kernel does with the rest — the memory that cannot be
     * dropped and read back off the file.
     *
     * The first version of this said "the weights are memory-mapped, so they are not a
     * reason to refuse". That is false for the models this feature exists for. KleidiAI
     * claims Q4_0 and Q8_0 matmul weights and **repacks them into anonymous memory**
     * (`ggml-cpu/kleidiai/kleidiai.cpp`), and llama.cpp only maps a tensor whose buffer
     * type is host-mappable (`llama-model-loader.cpp`), so a repacked model exists twice:
     * a file copy the kernel may drop, and an anonymous copy it may not. Measured on the
     * emulator with SmolLM2 Q8_0, a 145 MB file: `RssAnon` +203 MB, `RssFile` +145 MB.
     *
     * So the part that must stay is the repacked weights plus the context and the compute
     * buffers, and what the Mac measured is the sum of both copies:
     *
     *     peak = anonymous + however much of the file was resident at the time
     *     anonymous = peak − file resident  ≥  peak − sizeBytes
     *     anonymous = repacked + context + compute  ≥  sizeBytes
     *
     * — so `max(sizeBytes, peak − sizeBytes)` is the most that can be claimed from the two
     * fields without guessing, and it reproduces the measurement above exactly (145 MB
     * against 203 MB: the larger is 203). The Mac's quarter of headroom goes on top.
     *
     * It is a lower bound derived from a measurement taken on another phone, so it is only
     * the starting point: [measuredResident] is what this phone itself recorded the last
     * time it loaded this model, and that wins whenever it exists.
     *
     * Null when the Mac's entry carries no measurement and this phone has none of its own:
     * nothing to work it out from, and `minFreeMemoryBytes` is then the only gate there is.
     */
    fun residentFloor(model: PhoneModel, measuredResident: Long? = null): Long? {
        measuredResident?.takeIf { it > 0 }?.let { return (it * HEADROOM).toLong() }
        val peak = model.measured?.peakMemoryBytes ?: return null
        val anonymous = maxOf(model.sizeBytes, peak - model.sizeBytes)
        if (anonymous <= 0) return null
        // A floor above the Mac's own figure would be a worse gate than the one it
        // replaces; that only happens if the numbers disagree, and then the Mac's wins.
        return (anonymous * HEADROOM).toLong().coerceAtMost(model.recommended.minFreeMemoryBytes)
    }

    /**
     * What the Mac's gate would be at [contextTokens] — the advisory "runs best" figure,
     * and nothing that decides anything.
     *
     * A shorter context asks for less, but how much less is not something these two fields
     * can say: Qwen3.5-2B is partly recurrent, and a recurrent state does not grow with the
     * context at all, while the Mac's peak was taken with 640 tokens read. Scaling the
     * floor by it would have lowered Qwen's floor by 400 MB for a real saving of tens —
     * which is how a phone gets killed in the middle of an answer. So this moves and the
     * floor does not.
     */
    fun neededAt(model: PhoneModel, contextTokens: Int): Long {
        val needed = model.recommended.minFreeMemoryBytes
        val recommended = model.recommended.contextLength
        if (contextTokens >= recommended || recommended <= 0) return needed
        val peak = model.measured?.peakMemoryBytes ?: return needed
        val beyondWeights = peak - model.sizeBytes
        if (beyondWeights <= 0) return needed
        val scale = (1 - CONTEXT_SHARE) + CONTEXT_SHARE * (contextTokens.toDouble() / recommended)
        return minOf(needed, model.sizeBytes + (beyondWeights * scale * HEADROOM).toLong())
    }

    /**
     * Whether [model] may be loaded with [availableBytes] free. A system already in its
     * low-memory state refuses too, whatever the number says: the next thing it does is
     * kill something, and a freshly loaded model is the largest thing to kill.
     */
    fun memory(
        model: InstalledPhoneModel,
        availableBytes: Long,
        lowMemory: Boolean,
        installed: List<InstalledPhoneModel>,
        /**
          * What was free when a load of this model already got this app killed, or 0 if it
          * never has. What the phone did outranks the arithmetic: that much was not enough,
          * and neither is a little more — [KILL_MARGIN] over it is the new floor.
          */
        killedWithFreeBytes: Long = 0,
        /** What this phone itself recorded the last time it loaded this model, if it has. */
        measuredResident: Long? = null,
    ): Memory {
        val needed = model.model.recommended.minFreeMemoryBytes
        val afterAKill =
            if (killedWithFreeBytes > 0) killedWithFreeBytes + (killedWithFreeBytes * KILL_MARGIN).toLong() else 0
        val floor = maxOf(residentFloor(model.model, measuredResident) ?: needed, afterAKill).coerceAtMost(needed)
        if (!lowMemory && availableBytes >= needed) return Memory.Load
        // Android's own low-memory state is a refusal whatever the numbers say: the next
        // thing it does is kill something, and a model just loaded is the biggest thing
        // there is to kill.
        if (!lowMemory && availableBytes >= floor) return Memory.Tight(needed, floor, availableBytes)
        val alternative = installed
            .filter { it.id != model.id }
            .filter { it.model.recommended.minFreeMemoryBytes < needed }
            .filter { !lowMemory && availableBytes >= (residentFloor(it.model) ?: it.model.recommended.minFreeMemoryBytes) }
            .minByOrNull { it.model.recommended.minFreeMemoryBytes }
        return Memory.Refuse(needed, floor, availableBytes, alternative)
    }

    /** The sentence for a refusal, naming both numbers and what they are about. */
    fun refusal(model: InstalledPhoneModel, refusal: Memory.Refuse): String =
        "Not enough free memory for ${model.label}: it needs ${Format.bytes(refusal.floor)} free for " +
            "its working memory and this phone has ${Format.bytes(refusal.available)}." +
            (
                refusal.alternative?.let { " ${it.label} fits." }
                    ?: " There is no smaller model on this phone. Close some apps and try again."
                )

    /**
     * The sentence before an answer that will run, but on a phone with little to spare.
     * Both numbers, and what they are: an owner who is told "low on memory" and nothing
     * else cannot tell whether to close something or to stop asking.
     */
    fun warning(model: InstalledPhoneModel, tight: Memory.Tight): String =
        "Your phone is low on memory: ${model.label} runs best with " +
            "${Format.bytes(tight.needed)} free and this phone has ${Format.bytes(tight.available)}. " +
            "Other apps may close, and answers may be slower."

    // MARK: - When to let go of a loaded model

    /** The smallest context worth loading, when a kill has said the recommended one is too big. */
    const val SMALLEST_CONTEXT = 1024

    /** The shorter context offered when memory is tight: half of the Mac's 4,096. */
    const val SMALL_CONTEXT = 2048

    /**
     * How much of what a loaded model holds beyond its weights moves with the context.
     *
     * An estimate, and used for one thing only: the advisory "runs best" figure in
     * [neededAt]. It must never reach a floor — a recurrent model's state does not grow
     * with the context, so a floor scaled by this would be below what the phone actually
     * needs, and the phone would find out in the middle of an answer.
     */
    const val CONTEXT_SHARE = 0.5

    /** How long a model stays loaded once the app is off the screen. */
    const val BACKGROUND_GRACE_MS = 30_000L

    /** How long it stays loaded with nothing asked of it, on screen or off. */
    const val IDLE_MS = 5 * 60_000L

    /** `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND`: the system wants memory back from the background. */
    const val TRIM_MEMORY_BACKGROUND = 40

    /** Whether a trim-memory [level] means unloading now rather than after the grace. */
    fun unloadsAtOnce(level: Int): Boolean = level >= TRIM_MEMORY_BACKGROUND
}
