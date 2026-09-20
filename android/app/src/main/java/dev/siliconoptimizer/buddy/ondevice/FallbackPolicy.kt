package dev.siliconoptimizer.buddy.ondevice

import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.TransportError

/**
 * What is known about whether the Mac can answer right now.
 *
 * Four of these are the Mac being out of reach — the only times the phone offers to answer
 * itself. A Mac that answered and said no (no model loaded, a conversation already being
 * answered, a refusal) is still the Mac answering: offering the phone there would be
 * second-guessing it.
 */
enum class MacState {
    /** It answered, or nothing has said otherwise yet. */
    Answering,

    /** Nothing at that address: the tailnet is down, the Mac is asleep or elsewhere. */
    Unreachable,

    /** The address answered, but Silicon Optimizer is not running on it. */
    AppNotRunning,

    /** It did not answer in time. */
    TimedOut,

    /** No Mac is paired, or the Mac no longer knows this phone. */
    Unpaired,

    /** It answered with a refusal of some other kind. */
    Refused,
    ;

    val isOutOfReach: Boolean get() = this in setOf(Unreachable, AppNotRunning, TimedOut, Unpaired)

    companion object {
        /** From a request that failed. Null for one that was merely cancelled. */
        fun of(error: Throwable): MacState? = when (error) {
            is TransportError.Unreachable -> Unreachable
            is TransportError.AppNotRunning -> AppNotRunning
            is TransportError.TimedOut -> TimedOut
            is TransportError.Unauthorized, is TransportError.NotConfigured -> Unpaired
            is TransportError.Cancelled -> null
            is TransportError -> Refused
            else -> null
        }

        /** From the reachability probe the dashboard already runs. */
        fun of(reachability: Reachability, paired: Boolean): MacState = when {
            !paired -> Unpaired
            reachability is Reachability.Unreachable -> Unreachable
            reachability is Reachability.AppNotRunning -> AppNotRunning
            reachability is Reachability.Unauthorized -> Unpaired
            reachability is Reachability.Failed -> Refused
            else -> Answering
        }
    }
}

/** Whether to offer "Answer on this phone", and with which model — or why not. */
sealed interface FallbackDecision {
    data class Offer(val model: InstalledPhoneModel) : FallbackDecision

    sealed interface NoOffer : FallbackDecision

    /** The Mac can answer, so it does. */
    data object MacAnswering : NoOffer

    /** The Mac is out of reach but there is no model on this phone. */
    data object NoModel : NoOffer

    /** This phone cannot run one at all (not 64-bit ARM). */
    data object NotAvailable : NoOffer

    /** Already answering on the phone: there is nothing to offer. */
    data object AlreadyOnPhone : NoOffer
}

/**
 * When the phone offers to answer by itself.
 *
 * Only ever offered, never silent: the phone never answers in the Mac's place without a
 * tap, and every answer it writes is labelled. This is the whole rule, kept apart from
 * any screen so that its truth table is a test rather than a hope.
 */
object FallbackPolicy {

    fun decide(
        mac: MacState,
        installed: List<InstalledPhoneModel>,
        runtimeAvailable: Boolean,
        inPhoneConversation: Boolean = false,
        preferredID: String? = null,
    ): FallbackDecision = when {
        inPhoneConversation -> FallbackDecision.AlreadyOnPhone
        !mac.isOutOfReach -> FallbackDecision.MacAnswering
        !runtimeAvailable -> FallbackDecision.NotAvailable
        else -> choose(installed, preferredID)?.let { FallbackDecision.Offer(it) }
            ?: FallbackDecision.NoModel
    }

    /**
     * Which installed model to offer: the one the owner picked, else the Mac's default,
     * else the smallest — the one most likely to fit.
     */
    fun choose(installed: List<InstalledPhoneModel>, preferredID: String? = null): InstalledPhoneModel? =
        installed.firstOrNull { it.id == preferredID }
            ?: installed.firstOrNull { it.model.isDefault }
            ?: installed.minByOrNull { it.model.sizeBytes }
}
