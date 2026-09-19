package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.TransportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/**
 * Nothing the client logs may name the Mac.
 *
 * `TransportError.message` is written for the person looking at the screen, and
 * `Unreachable` puts the tailnet address straight into it — that is the whole point of
 * the message. logcat is a different audience: it outlives the moment and it ends up in
 * bug reports. The first version of the reconnect logging wrote `error.message`, which
 * meant every flap of the tunnel printed the owner's 100.x address.
 */
class LogRedactionTest {

    private val host = "100.64.0.9"

    /** Every case, so a new one cannot quietly inherit a branch that prints something. */
    private val everyError: List<TransportError> = listOf(
        TransportError.Unreachable(host),
        TransportError.AppNotRunning,
        TransportError.TimedOut,
        TransportError.Unauthorized,
        TransportError.Forbidden("Android refused a plain HTTP connection to $host."),
        TransportError.Conflict("Still answering $host."),
        TransportError.TooLarge("Too big for $host."),
        TransportError.ChunkedNotAccepted("$host won't read that."),
        TransportError.NotFound("Gone from $host."),
        TransportError.RouteUnavailable("/conversations/9f3c-secret-id"),
        TransportError.BadRequest("$host has no model loaded."),
        TransportError.Busy("$host is busy."),
        TransportError.Server(503, "$host fell over."),
        TransportError.Decoding("bad field from $host"),
        TransportError.NotConfigured,
        TransportError.Cancelled,
    )

    @Test
    fun `no log summary contains the host, whatever the message says`() {
        for (error in everyError) {
            assertFalse(
                "${error::class.simpleName} leaked the host: ${error.logSummary}",
                error.logSummary.contains(host),
            )
            assertFalse(
                "${error::class.simpleName} leaked an octet: ${error.logSummary}",
                error.logSummary.contains("100."),
            )
        }
    }

    /**
     * The narrower rule, and the one that actually protects the owner: a summary is a
     * fixed tag, never the server's or the formatter's free text. Anything echoing a
     * detail would pass a host check by accident today and fail it tomorrow.
     */
    @Test
    fun `a log summary never echoes free text from the error`() {
        for (error in everyError) {
            assertFalse(
                "${error::class.simpleName} echoed its message",
                error.logSummary == error.message,
            )
            assertFalse(
                "${error::class.simpleName} leaked a conversation id",
                error.logSummary.contains("secret-id"),
            )
            assertFalse(
                "${error::class.simpleName} leaked a path",
                error.logSummary.contains("/"),
            )
        }
    }

    /** The tags have to stay stable, because they are what a reader greps for. */
    @Test
    fun `the tags say which failure it was`() {
        assertEquals("unreachable", TransportError.Unreachable(host).logSummary)
        assertEquals("app not running", TransportError.AppNotRunning.logSummary)
        assertEquals("timed out", TransportError.TimedOut.logSummary)
        assertEquals("unauthorized", TransportError.Unauthorized.logSummary)
        // A status is as specific as it gets: it is ours, not the Mac's text.
        assertEquals("server error 503", TransportError.Server(503, "anything").logSummary)
    }

    /**
     * The messages must keep naming the host — this is not an argument for stripping it
     * everywhere. The screen is where it belongs, because "can't reach 100.64.0.9"
     * is how somebody works out that they paired with the wrong machine.
     */
    @Test
    fun `the message a person reads still names the Mac`() {
        assertTrue(TransportError.Unreachable(host).message!!.contains(host))
        val cleartext = TransportError.from(IOException("Cleartext HTTP not permitted"), host)
        assertTrue(cleartext.message!!.contains(host))
        // …and that is exactly the one whose summary must not.
        assertEquals("forbidden", cleartext.logSummary)
    }

    /** The socket mapping feeds the summary, so it is worth pinning the common paths. */
    @Test
    fun `socket failures map to host-free tags`() {
        assertEquals(
            "unreachable",
            TransportError.from(UnknownHostException(host), host).logSummary,
        )
        assertEquals(
            "app not running",
            TransportError.from(ConnectException("ECONNREFUSED $host"), host).logSummary,
        )
    }
}
