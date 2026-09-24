package dev.siliconoptimizer.buddy.transport

/**
 * What a reachability probe found.
 *
 * Three failures, three different fixes, and the app says which one it is instead of
 * spinning: the tailnet is off, nothing is listening at the selected port, or this device is no longer
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
            is AppNotRunning -> "Connection refused"
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
            is AppNotRunning -> TransportError.AppNotRunning.message.orEmpty()
            is Unreachable -> "Nothing answered at $host. Is Tailscale on?"
            is Failed -> reason
        }
}

/**
 * Probes a Mac in the order that separates the three failures.
 *
 * `/health` is deliberately unauthenticated on the Mac exactly so a client can tell
 * "nothing listening" from "bad token" — this is the client half of that bargain.
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
