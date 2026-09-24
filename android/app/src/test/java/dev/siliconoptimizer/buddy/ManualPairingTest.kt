package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TailnetHost
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
    fun `formatted pairing codes are recognized before treating them as bearer tokens`() {
        listOf("418203", "418 203", " 418-203 ", "418\t203", "４１８２０３", "٤١٨٢٠٣")
            .forEach { assertTrue(it, PairingInvite.looksLikePairingCode(it)) }
        listOf("", "41820", "4182031", "abc123", "418-20x", "418203-long-control-token")
            .forEach { assertFalse(it, PairingInvite.looksLikePairingCode(it)) }
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
        val bracketed = PairingInvite.typed("[fd7a:115c:a1e0::9]:8924", "418203")
        assertEquals(PairingInvite("fd7a:115c:a1e0::9", 8924, "418203"), bracketed)
        assertEquals("fd7a:115c:a1e0::9", PairingInvite.typed("[fd7a:115c:a1e0::9]", "418203").host)
    }

    @Test
    fun `an IPv6 tailnet address is dialled in brackets`() {
        val config = ServerConfig("fd7a:115c:a1e0::9", 8788, "t")
        assertEquals("http://[fd7a:115c:a1e0::9]:8788/health", config.url("/health").toString())
        assertEquals("[fd7a:115c:a1e0::9]:8788", config.displayAddress)
        // Brackets already there are not doubled, and IPv4 gets none.
        assertEquals("[fd7a:115c:a1e0::9]", TailnetHost.forUrl("[fd7a:115c:a1e0::9]"))
        assertEquals("http://100.64.0.9:8788/health", ServerConfig("100.64.0.9", 8788, "t").url("/health").toString())
    }

    @Test
    fun `an IPv6 invite reaches the Mac`() = runBlocking {
        // ::1 is the one IPv6 address a test can listen on. The request has to be a URL
        // before it can go anywhere, which unbracketed it was not.
        val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("::1"))
        val answered = kotlin.concurrent.thread(isDaemon = true) {
            server.accept().use { socket ->
                // Read the request whole — headers, then the body they announce — so
                // closing the socket afterwards cannot reset it under the client.
                val reader = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toInt()
                    }
                }
                val request = CharArray(length)
                var read = 0
                while (read < length) {
                    val count = reader.read(request, read, length - read)
                    if (count < 0) break
                    read += count
                }
                val body = """{"deviceID":"D6","token":"v6","macName":"Six","port":8788}"""
                socket.getOutputStream().write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                )
            }
        }
        try {
            val config = PairingInvite("::1", server.localPort, "418203").exchange("Pixel", "android")
            assertEquals("v6", config.token)
            assertEquals("::1", config.host)
        } finally {
            answered.join(5_000)
            server.close()
        }
    }

    @Test
    fun `a pasted link is held for confirmation, never spent as typed`() {
        // Someone else chose a link's host, so the form's own Pair button never spends
        // one: `pasted` hands it on for the confirmation a tapped link gets, and `typed`
        // refuses it outright.
        val link = "siliconbuddy://pair?host=100.64.0.9&port=8788&code=418203"
        assertTrue(PairingInvite.isLink(" $link"))
        assertFalse(PairingInvite.isLink("100.64.0.9"))
        assertEquals(PairingInvite("100.64.0.9", 8788, "418203"), PairingInvite.pasted(" $link"))
        assertNull("an address is not a link", PairingInvite.pasted("100.64.0.9"))
        assertThrows(PairingInvite.ParseError.LinkNotTyped::class.java) {
            PairingInvite.typed(link, "418203", "8788")
        }
    }

    @Test
    fun `a pasted link off the tailnet is refused before anything is offered`() {
        assertThrows(PairingInvite.ParseError.HostNotOnTailnet::class.java) {
            PairingInvite.pasted("siliconbuddy://pair?host=evil.example.com&port=8788&code=418203")
        }
        assertThrows(PairingInvite.ParseError::class.java) {
            PairingInvite.pasted("siliconbuddy://pair?host=100.64.0.9&port=8788")
        }
    }

    @Test
    fun `digits from any script go into the fields as ASCII`() {
        assertEquals("418 203", PairingInvite.asciiDigits("٤١٨ ٢٠٣", keepSpaces = true))
        assertEquals("418203", PairingInvite.asciiDigits("４１８２０３"))
        assertEquals("8788", PairingInvite.asciiDigits("8 7-8a8"))
        assertEquals("418203", PairingInvite.typed("100.64.0.9", PairingInvite.asciiDigits("۴۱۸۲۰۳")).code)
    }

    @Test
    fun `a typed address off the tailnet is refused as a scanned one is`() {
        listOf("evil.example.com", "192.168.1.10", "100.64.0.1.evil.example.com").forEach { host ->
            val error = assertThrows(PairingInvite.ParseError.HostNotOnTailnet::class.java) {
                PairingInvite.typed(host, "418203")
            }
            assertEquals(host, error.host)
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
