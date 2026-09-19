package dev.siliconoptimizer.buddy.transport

import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a call to the Mac did not answer.
 *
 * The distinctions here are the ones a person can act on. "Unreachable" means turn
 * Tailscale on; "the app isn't running" means open Silicon Optimizer; "unauthorized"
 * means pair again. Collapsing those into one "network error" would make the app
 * useless exactly when something is wrong.
 */
sealed class TransportError(message: String) : Exception(message) {
    /** Nothing at that address answered: wrong host, tailnet down, Mac asleep. */
    data class Unreachable(val host: String) :
        TransportError("Can't reach $host. Check that Tailscale is on and the Mac is awake.")

    /** The address answered but nothing is listening on the port. */
    data object AppNotRunning :
        TransportError("The Mac answered, but Silicon Optimizer isn't running on it.")

    data object TimedOut : TransportError("The Mac took too long to answer.")

    /** 401: the token is wrong, or the Mac revoked this device. */
    data object Unauthorized :
        TransportError("This device isn't paired any more. Pair it again from the Mac.")

    /**
     * 403: the token is good but this device may not do that — a chat-scope device
     * asking to load a model, or a wrong pairing code. The Mac's own words matter here,
     * because "not allowed" and "no longer paired" call for different actions.
     */
    data class Forbidden(val detail: String) : TransportError(detail)

    /** 409: the Mac is already answering in that conversation. */
    data class Conflict(val detail: String) : TransportError(detail)

    /** 413: more than a device may send — 4 MiB a body, about 1.5 MB an image. */
    data class TooLarge(val detail: String) : TransportError(detail)

    /**
     * 411: the Mac will not read a chunked body. This one is ours to fix, not the
     * owner's: every request this app sends sets a Content-Length.
     */
    data class ChunkedNotAccepted(val detail: String) : TransportError(detail)

    /**
     * 404 on a route that exists, about a thing that does not — a conversation deleted
     * on the Mac, say. Not the same as "this Mac is too old".
     */
    data class NotFound(val detail: String) : TransportError(detail)

    /** 404 on a route this app knows about: that Mac has not shipped it yet. */
    data class RouteUnavailable(val path: String) :
        TransportError("This Mac doesn't have $path yet.")

    /** 400 with the Mac's own explanation; most often "no model is loaded". */
    data class BadRequest(val detail: String) : TransportError(detail)

    /** 429: the Mac is already doing as much of this as it will do at once. */
    data class Busy(val detail: String) : TransportError(detail)

    data class Server(val status: Int, val detail: String) :
        TransportError(if (detail.isBlank()) "The Mac returned an error ($status)." else detail)

    data class Decoding(val detail: String) :
        TransportError("The Mac's answer didn't match what this app expects: $detail")

    data object NotConfigured : TransportError("No Mac is paired yet.")

    data object Cancelled : TransportError("Cancelled.")

    val isMissingRoute: Boolean get() = this is RouteUnavailable

    /**
     * True when the Mac refused because of what this device may do, rather than
     * because it does not know this device.
     */
    val isForbidden: Boolean get() = this is Forbidden

    /**
     * A 404 about a thing rather than a route: the same status, a different meaning,
     * and only the caller knows which it asked for.
     */
    fun asNotFound(): TransportError = when (this) {
        is RouteUnavailable -> NotFound("The Mac has nothing at $path any more.")
        else -> this
    }

    /**
     * What may be written to a log: the shape of the failure and nothing else.
     *
     * `message` is written for the person looking at the screen, and several of these
     * carry the Mac's tailnet address in it — `Unreachable` by construction, `Forbidden`
     * when Android refuses cleartext — while others carry whatever text the Mac put in
     * an error body. logcat is a different audience: it survives the moment, it goes
     * into bug reports, and an address on the owner's tailnet is not a thing to leave
     * lying around in one. So nothing here interpolates a host, a path, a conversation
     * id or any server-supplied text; a status code is as specific as it gets.
     *
     * Kept exhaustive deliberately — a new case has to choose its own tag rather than
     * fall into a branch that might print something.
     */
    val logSummary: String
        get() = when (this) {
            is Unreachable -> "unreachable"
            is AppNotRunning -> "app not running"
            is TimedOut -> "timed out"
            is Unauthorized -> "unauthorized"
            is Forbidden -> "forbidden"
            is Conflict -> "conflict"
            is TooLarge -> "too large"
            is ChunkedNotAccepted -> "chunked not accepted"
            is NotFound -> "not found"
            is RouteUnavailable -> "route unavailable"
            is BadRequest -> "bad request"
            is Busy -> "busy"
            is Server -> "server error $status"
            is Decoding -> "decoding failed"
            is NotConfigured -> "not configured"
            is Cancelled -> "cancelled"
        }

    /** What to offer the person, when there is something to offer. */
    val recovery: String?
        get() = when (this) {
            is Unreachable -> "Open Tailscale, then pull to refresh."
            is AppNotRunning -> "Open Silicon Optimizer on the Mac."
            is Unauthorized -> "Settings, Silicon Buddy, Pair a device."
            is Forbidden -> "Pair again from the Mac with full control."
            is Conflict -> "Wait for the answer, or start another conversation."
            is TooLarge -> "Send fewer or smaller pictures."
            is NotFound -> "It may have been deleted on the Mac."
            is BadRequest -> "Load a model from the Models tab."
            else -> null
        }

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        /**
         * Maps what the socket layer says.
         *
         * [ConnectException] is the interesting one: the connection was actively
         * refused, which means the host is there and the port is not — the Mac is awake
         * and the app is closed.
         */
        fun from(error: IOException, host: String): TransportError = when (error) {
            is SocketTimeoutException -> TimedOut
            is ConnectException -> AppNotRunning
            is UnknownHostException, is NoRouteToHostException -> Unreachable(host)
            else -> {
                val text = error.message.orEmpty()
                when {
                    // The platform refusing plain HTTP is not the Mac being away, and
                    // the fix is different: use the address it was paired with.
                    text.contains("Cleartext", true) -> Forbidden(
                        "Android refused a plain HTTP connection to " + host + ". " +
                            TailnetHost.EXPLANATION,
                    )
                    text.contains("ECONNREFUSED", true) ||
                        text.contains("refused", true) -> AppNotRunning
                    text.contains("timed out", true) -> TimedOut
                    else -> Unreachable(host)
                }
            }
        }

        /** Maps an HTTP status plus the Mac's error body. Null when the call succeeded. */
        fun from(status: Int, body: String, path: String): TransportError? {
            if (status in 200..299) return null
            val detail = runCatching {
                lenient.decodeFromString(ErrorResponse.serializer(), body).error
            }.getOrElse { body.trim() }
            return when (status) {
                401 -> Unauthorized
                403 -> Forbidden(detail.ifBlank { "The Mac wouldn't allow that." })
                404 -> RouteUnavailable(path)
                409 -> Conflict(detail.ifBlank { "That conversation is still being answered." })
                411 -> ChunkedNotAccepted(detail.ifBlank { "The Mac wouldn't read that request." })
                413 -> TooLarge(detail.ifBlank { "That was too large to send." })
                400 -> BadRequest(detail.ifBlank { "The Mac rejected the request." })
                429 -> Busy(detail.ifBlank { "The Mac is busy." })
                else -> Server(status, detail)
            }
        }
    }
}
