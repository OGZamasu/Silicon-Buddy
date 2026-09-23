package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pairing without a camera (#17). A phone on the tailnet gets in only by spending the code
 * the Mac shows: the control token in the Mac's control.json is refused anywhere but the
 * Mac's own loopback. So what a person types has to end where a scan does, at
 * `POST /buddy/pair`, and must never need that token.
 */
class ManualPairingTest {

    private lateinit var server: LoopbackServer

    @Before
    fun setUp() {
        server = LoopbackServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // MARK: - What a person types

    @Test
    fun `the code and address the Mac shows are enough`() {
        val invite = PairingInvite.typed("100.64.0.9", "418203")
        assertEquals(PairingInvite("100.64.0.9", PairingInvite.DEFAULT_PORT, "418203"), invite)
        assertEquals(8788, PairingInvite.DEFAULT_PORT)
    }

    @Test
    fun `the code may be typed the way the Mac spaces it`() {
        assertEquals("418203", PairingInvite.typed("100.64.0.9", "418 203").code)
        assertEquals("418203", PairingInvite.typed("100.64.0.9", " 418-203 ").code)
    }

    @Test
    fun `a port can be given, in its field or after the address`() {
        assertEquals(8924, PairingInvite.typed("10.0.2.2", "418203", "8924").port)
        assertEquals(8924, PairingInvite.typed("100.64.0.9:8924", "418203", "8788").port)
        assertEquals("100.64.0.9", PairingInvite.typed("100.64.0.9:8924", "418203").host)
        assertEquals(PairingInvite.DEFAULT_PORT, PairingInvite.typed("100.64.0.9", "418203", " ").port)
    }

    @Test
    fun `an IPv6 tailnet address is never split at a colon`() {
        val invite = PairingInvite.typed("fd7a:115c:a1e0::9", "418203")
        assertEquals("fd7a:115c:a1e0::9", invite.host)
        assertEquals(PairingInvite.DEFAULT_PORT, invite.port)
    }

    @Test
    fun `a pasted pairing link is read as the link`() {
        val link = "siliconbuddy://pair?host=100.64.0.9&port=8788&code=418203"
        assertTrue(PairingInvite.isLink(" $link"))
        assertFalse(PairingInvite.isLink("100.64.0.9"))
        assertEquals(PairingInvite.parse(link), PairingInvite.typed(link, "", ""))
    }

    @Test
    fun `a typed address off the tailnet is refused as a scanned one is`() {
        listOf("evil.example.com", "192.168.1.10", "100.64.0.1.evil.example.com").forEach { host ->
            val error = assertThrows(PairingInvite.ParseError.HostNotOnTailnet::class.java) {
                PairingInvite.typed(host, "418203")
            }
            assertEquals(host, error.host)
        }
        assertThrows(PairingInvite.ParseError.HostNotOnTailnet::class.java) {
            PairingInvite.typed("siliconbuddy://pair?host=evil.example.com&port=8788&code=418203", "")
        }
    }

    @Test
    fun `a code that is not six digits is refused`() {
        listOf("41820", "4182031", "abc123", "--", "418 20x").forEach { code ->
            assertThrows(code, PairingInvite.ParseError.BadCode::class.java) {
                PairingInvite.typed("100.64.0.9", code)
            }
        }
    }

    @Test
    fun `a bad port or a missing address says which`() {
        assertThrows(PairingInvite.ParseError.BadPort::class.java) {
            PairingInvite.typed("100.64.0.9", "418203", "99999")
        }
        assertThrows(PairingInvite.ParseError.BadPort::class.java) {
            PairingInvite.typed("100.64.0.9:http", "418203")
        }
        val missing = assertThrows(PairingInvite.ParseError.NoAddress::class.java) {
            PairingInvite.typed("  ", "418203")
        }
        assertTrue(missing.message.orEmpty().contains("address"))
    }

    // MARK: - Where it goes

    @Test
    fun `a typed code is spent at buddy pair with no token`() = runBlocking {
        server.reply(
            "/buddy/pair", 200,
            """{"deviceID":"D1","token":"device-token","macName":"Demo Mac","port":8788,"scope":"chat"}""",
        )
        val invite = PairingInvite.typed("127.0.0.1", "418 203", server.port.toString())

        val config = invite.exchange("Pixel", "android")

        val request = server.request("/buddy/pair")
        assertNotNull("the code never reached the Mac", request)
        assertEquals("POST", request!!.method)
        assertNull("the code is the credential; nothing else goes with it",
            request.headers["authorization"])
        assertTrue(request.body, request.body.contains("\"code\":\"418203\""))
        assertTrue(request.body, request.body.contains("\"platform\":\"android\""))
        // What the phone keeps is the Mac's answer: its own token, the port to keep dialling.
        assertEquals("127.0.0.1", config.host)
        assertEquals(8788, config.port)
        assertEquals("device-token", config.token)
        assertEquals("Demo Mac", config.macName)
        assertEquals("D1", config.deviceID)
        assertEquals(DeviceScope.Chat, config.scope)
    }

    @Test
    fun `a wrong typed code is the Mac's refusal, not a missing route`() = runBlocking {
        server.reply("/buddy/pair", 403, """{"error":"That pairing code is not the one on screen."}""")
        val invite = PairingInvite.typed("127.0.0.1", "000000", server.port.toString())
        val error = runCatching { invite.exchange("Pixel", "android") }.exceptionOrNull()
        assertTrue("$error", error is TransportError.Forbidden)
        assertEquals("That pairing code is not the one on screen.", error!!.message)
    }

    @Test
    fun `a Mac too old for codes is told apart`() = runBlocking {
        server.reply("/buddy/pair", 401, """{"error":"Invalid or missing control token."}""")
        val invite = PairingInvite.typed("127.0.0.1", "418203", server.port.toString())
        val error = runCatching { invite.exchange("Pixel", "android") }.exceptionOrNull()
        assertEquals(TransportError.RouteUnavailable("/buddy/pair"), error)
    }

    @Test
    fun `nothing is dialled for an invite off the tailnet`() = runBlocking {
        var dialled = false
        val error = runCatching {
            PairingInvite("192.168.1.10", 8788, "418203").exchange("Pixel", "android") {
                dialled = true
                error("must not be built")
            }
        }.exceptionOrNull()
        assertTrue("$error", error is TransportError.Forbidden)
        assertFalse(dialled)
    }
}
