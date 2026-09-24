package dev.siliconoptimizer.buddy.transport

/**
 * What a reachability probe found.
 *
 * Three failures, three different fixes, and the app says which one it is instead of
 * spinning: the tailnet is off, the Mac app is closed, or this device is no longer
 * paired.
 */
sealed interface Reachability {
    data object Unknown : Reachability
    data object Checking : Reachability

    /**
     * The Mac answered and took the token. [version] is the Mac app's, as [Health.appVersionLabel]
     * reads it, and null when the Mac did not say.
     */
    data class Ready(val version: String?, val loadedModel: String?) : Reachability

    /** `/health` answered but `/status` came back 401. */
    data object Unauthorized : Reachability

    /** Something is at that address, nothing is on that port. */
    data object AppNotRunning : Reachability

    /** Nothing at that address at all. */
    data class Unreachable(val host: String) : Reachability

    data class Failed(val reason: String) : Reachability

    val isReady: Boolean get() = this is Ready

    val headline: String
        get() = when (this) {
            is Unknown -> "Not connected"
            is Checking -> "Checking…"
            is Ready -> "Connected"
            is Unauthorized -> "Not paired"
            is AppNotRunning -> "App not running"
            is Unreachable -> "Unreachable"
            is Failed -> "Error"
        }

    val detail: String
        get() = when (this) {
            is Unknown -> "No Mac paired yet."
            is Checking -> "Talking to the Mac…"
            is Ready -> {
                val app = version?.let { "Silicon Optimizer $it" } ?: "Silicon Optimizer"
                loadedModel?.let { "$app — $it" } ?: app
            }
            is Unauthorized -> "The Mac refused this device's token. Pair again."
            is AppNotRunning -> "The Mac is awake but Silicon Optimizer is closed."
            is Unreachable -> "Nothing answered at $host. Is Tailscale on?"
            is Failed -> reason
        }
}

/**
 * Probes a Mac in the order that separates the three failures.
 *
 * `/health` is deliberately unauthenticated on the Mac exactly so a client can tell
 * "app not running" from "bad token" — this is the client half of that bargain.
 */
class ConnectivityProbe(private val transport: ControlTransport) {

    suspend fun check(): Reachability {
        val version = try {
            transport.health().appVersionLabel
        } catch (error: TransportError) {
            return when (error) {
                is TransportError.AppNotRunning -> Reachability.AppNotRunning
                is TransportError.Unreachable -> Reachability.Unreachable(error.host)
                is TransportError.TimedOut -> Reachability.Unreachable("the Mac")
                is TransportError.Cancelled -> Reachability.Unknown
                // A Mac old enough to lack /health still proves it is listening.
                is TransportError.RouteUnavailable -> return authorizedCheck(null)
                else -> Reachability.Failed(error.message ?: "Unknown error")
            }
        } catch (error: Exception) {
            return Reachability.Failed(error.message ?: "Unknown error")
        }
        return authorizedCheck(version)
    }

    private suspend fun authorizedCheck(version: String?): Reachability = try {
        val status = transport.status()
        Reachability.Ready(version, status.loadedModelName ?: status.state)
    } catch (error: TransportError) {
        when (error) {
            is TransportError.Unauthorized, is TransportError.NotConfigured ->
                Reachability.Unauthorized
            is TransportError.AppNotRunning -> Reachability.AppNotRunning
            is TransportError.Unreachable -> Reachability.Unreachable(error.host)
            is TransportError.Cancelled -> Reachability.Unknown
            else -> Reachability.Failed(error.message ?: "Unknown error")
        }
    } catch (error: Exception) {
        Reachability.Failed(error.message ?: "Unknown error")
    }
}

/**
 * One check of the paired Mac: whether it answers, then — when it does — what it is running.
 *
 * A check takes seconds, and the phone can be re-paired or told to forget its Mac while one
 * is out. What comes back is about the Mac that was asked, so it is handed on only while
 * that Mac is still the one paired ([stillPaired], asked before each answer is written).
 * Written after the pairing changed, the old Mac's model went into the snapshot the widget
 * and the tile read, and "Connected" onto a phone that had just forgotten its Mac.
 */
object PairedMacCheck {
    suspend fun run(
        transport: ControlTransport,
        stillPaired: () -> Boolean,
        reachability: (Reachability) -> Unit,
        status: (Status?) -> Unit,
    ) {
        val result = ConnectivityProbe(transport).check()
        if (!stillPaired()) return
        reachability(result)
        if (!result.isReady) return
        val answered = runCatching { transport.status() }.getOrNull()
        if (!stillPaired()) return
        status(answered)
    }
}
