package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.TransportError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransportErrorTest {

    private fun body(message: String) = """{"error":"$message"}"""

    // MARK: - Socket failures

    @Test
    fun `connection refused means the app is not running`() {
        // The Mac answered the SYN with a RST: the machine is up, the port is closed.
        assertEquals(
            TransportError.AppNotRunning,
            TransportError.from(ConnectException("ECONNREFUSED"), "mac"),
        )
    }

    @Test
    fun `a timeout is its own thing`() {
        assertEquals(
            TransportError.TimedOut,
            TransportError.from(SocketTimeoutException("timed out"), "mac"),
        )
    }

    @Test
    fun `an unknown host is unreachable`() {
        assertEquals(
            TransportError.Unreachable("mac.tailnet"),
            TransportError.from(UnknownHostException("mac.tailnet"), "mac.tailnet"),
        )
    }

    @Test
    fun `a plain IO failure that mentions refusal is still the app being closed`() {
        assertEquals(
            TransportError.AppNotRunning,
            TransportError.from(IOException("failed to connect: ECONNREFUSED"), "mac"),
        )
    }

    @Test
    fun `any other IO failure lands somewhere useful`() {
        assertEquals(
            TransportError.Unreachable("mac"),
            TransportError.from(IOException("something odd"), "mac"),
        )
    }

    // MARK: - HTTP statuses

    @Test
    fun `success is not an error`() {
        assertNull(TransportError.from(200, "", "/status"))
        assertNull(TransportError.from(204, "", "/unload"))
    }

    @Test
    fun `unauthorized`() {
        assertEquals(
            TransportError.Unauthorized,
            TransportError.from(401, body("Invalid or missing control token."), "/status"),
        )
    }

    /**
     * 403 and 401 mean different things and ask for different things: one is "pair
     * again", the other is "this device was paired for chat only".
     */
    @Test
    fun `forbidden is not unauthorized`() {
        val refusal = TransportError.from(
            403, body("This device is paired for chat only."), "/load",
        )
        assertEquals(TransportError.Forbidden("This device is paired for chat only."), refusal)
        assertTrue(refusal!!.isForbidden)
        assertNotEquals(TransportError.Unauthorized.recovery, refusal.recovery)
    }

    @Test
    fun `a conversation conflict is its own case`() {
        assertEquals(
            TransportError.Conflict("That conversation is still being answered."),
            TransportError.from(
                409, body("That conversation is still being answered."), "/conversations/1/messages",
            ),
        )
    }

    @Test
    fun `too large is its own case`() {
        assertTrue(
            TransportError.from(413, body("Too big."), "/chat") is TransportError.TooLarge,
        )
    }

    @Test
    fun `a cleartext refusal is not the Mac being away`() {
        val error = TransportError.from(
            java.io.IOException("Cleartext HTTP traffic to 100.64.0.1 not permitted"),
            "100.64.0.1",
        )
        assertTrue(error is TransportError.Forbidden)
    }

    @Test
    fun `not found names the route so the caller can fall back`() {
        val error = TransportError.from(404, "", "/chat/stream")
        assertEquals(TransportError.RouteUnavailable("/chat/stream"), error)
        assertTrue(error!!.isMissingRoute)
    }

    @Test
    fun `bad request keeps the Mac's words`() {
        val error = TransportError.from(400, body("No model is loaded."), "/chat")
        assertEquals(TransportError.BadRequest("No model is loaded."), error)
        assertEquals("No model is loaded.", error?.message)
    }

    @Test
    fun `too many requests is busy`() {
        assertEquals(
            TransportError.Busy("Too many synchronous video requests."),
            TransportError.from(429, body("Too many synchronous video requests."), "/video/generate"),
        )
    }

    @Test
    fun `an unknown status carries its number`() {
        // 502: nothing the Mac documents. (503 was the example here until the phone's models
        // made it one — the Mac's library drive gone — with a case of its own.)
        assertEquals(
            TransportError.Server(502, "gone fishing"),
            TransportError.from(502, body("gone fishing"), "/status"),
        )
    }

    @Test
    fun `a non-JSON error body is still readable`() {
        assertEquals(
            TransportError.Server(500, "something broke"),
            TransportError.from(500, "something broke", "/status"),
        )
    }

    @Test
    fun `an empty bad request still says something`() {
        assertEquals(
            TransportError.BadRequest("The Mac rejected the request."),
            TransportError.from(400, "", "/load"),
        )
    }

    @Test
    fun `a server error with no message still reads as an error`() {
        assertEquals(
            "The Mac returned an error (500).",
            TransportError.Server(500, "").message,
        )
    }

    // MARK: - What the person is told

    @Test
    fun `every error has something to say`() {
        val errors = listOf(
            TransportError.Unreachable("mac"),
            TransportError.AppNotRunning,
            TransportError.TimedOut,
            TransportError.Unauthorized,
            TransportError.Forbidden("x"),
            TransportError.Conflict("y"),
            TransportError.TooLarge("z"),
            TransportError.RouteUnavailable("/events"),
            TransportError.BadRequest("x"),
            TransportError.Busy("y"),
            TransportError.Server(500, ""),
            TransportError.Decoding("z"),
            TransportError.NotConfigured,
            TransportError.Cancelled,
        )
        errors.forEach { assertTrue("$it says nothing", !it.message.isNullOrBlank()) }
    }

    @Test
    fun `the three failures that matter have separate advice`() {
        assertNotNull(TransportError.Unreachable("mac").recovery)
        assertNotNull(TransportError.AppNotRunning.recovery)
        assertNotNull(TransportError.Unauthorized.recovery)
        assertNotEquals(
            TransportError.Unreachable("mac").recovery,
            TransportError.AppNotRunning.recovery,
        )
    }
}
