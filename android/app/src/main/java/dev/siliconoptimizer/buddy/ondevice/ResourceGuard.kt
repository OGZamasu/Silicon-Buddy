package dev.siliconoptimizer.buddy.ondevice

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
         * Not enough free. [alternative] is a smaller installed model that does fit, when
         * there is one.
         */
        data class Refuse(
            val needed: Long,
            val available: Long,
            val alternative: InstalledPhoneModel?,
        ) : Memory
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
    ): Memory {
        val needed = model.model.recommended.minFreeMemoryBytes
        if (!lowMemory && availableBytes >= needed) return Memory.Load
        val alternative = installed
            .filter { it.id != model.id }
            .filter { it.model.recommended.minFreeMemoryBytes < needed }
            .filter { !lowMemory && availableBytes >= it.model.recommended.minFreeMemoryBytes }
            .minByOrNull { it.model.recommended.minFreeMemoryBytes }
        return Memory.Refuse(needed, availableBytes, alternative)
    }

    /** The sentence for a refusal, naming both numbers. */
    fun refusal(model: InstalledPhoneModel, refusal: Memory.Refuse): String =
        "Not enough free memory for ${model.label}: it needs ${Format.bytes(refusal.needed)} free " +
            "and this phone has ${Format.bytes(refusal.available)}." +
            (refusal.alternative?.let { " ${it.label} fits." } ?: " Close some apps and try again.")

    // MARK: - When to let go of a loaded model

    /** How long a model stays loaded once the app is off the screen. */
    const val BACKGROUND_GRACE_MS = 30_000L

    /** How long it stays loaded with nothing asked of it, on screen or off. */
    const val IDLE_MS = 5 * 60_000L

    /** `ComponentCallbacks2.TRIM_MEMORY_BACKGROUND`: the system wants memory back from the background. */
    const val TRIM_MEMORY_BACKGROUND = 40

    /** Whether a trim-memory [level] means unloading now rather than after the grace. */
    fun unloadsAtOnce(level: Int): Boolean = level >= TRIM_MEMORY_BACKGROUND
}
