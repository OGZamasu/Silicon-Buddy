package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.chat.SendLimits
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.transport.DeviceScope
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TailnetHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A scanned QR, or a link any app can open, names the machine this device is about to
 * hand a bearer token to. These are the tests that keep that from being anyone's choice
 * but the owner's.
 */
class PairingSecurityTest {

    // MARK: - Which hosts exist at all

    @Test
    fun `the tailnet and the loopbacks are allowed`() {
        listOf(
            "127.0.0.1", "127.1.2.3", "localhost", "::1",
            "10.0.2.2", // the emulator's name for its host
            "100.64.0.1", "100.100.100.100", "100.127.255.254",
            "fd7a:115c:a1e0::1", "fd7a:115c:a1e0:ab12:4843:cd96:625a:1",
            "[fd7a:115c:a1e0::1]",
        ).forEach { assertTrue("$it should be reachable", TailnetHost.isAllowed(it)) }
    }

    @Test
    fun `everything else is refused`() {
        listOf(
            "evil.example.com", "example.com", "192.168.1.10", "10.0.0.5", "172.16.0.1",
            "8.8.8.8", "100.63.255.255", "100.128.0.1", "0.0.0.0", "255.255.255.255",
            "fd7a:115c:a1e1::1", "2001:4860:4860::8888", "",
            // The oldest trick there is: a name that starts with an address.
            "100.64.0.1.evil.example.com",
            "100.64.0.1@evil.example.com",
        ).forEach { assertFalse("$it must not be dialled", TailnetHost.isAllowed(it)) }
    }

    @Test
    fun `an address is parsed, not scanned for digits`() {
        assertFalse("No octal-looking octets", TailnetHost.isAllowed("100.064.0.1"))
        assertFalse(TailnetHost.isAllowed("100.64.0"))
        assertFalse(TailnetHost.isAllowed("100.64.0.1.2"))
        assertFalse(TailnetHost.isAllowed("100.64.0.256"))
        assertFalse("A zone is an interface", TailnetHost.isAllowed("fd7a:115c:a1e0::1%wlan0"))
    }

    // MARK: - What a QR may say

    @Test
    fun `a pairing link to another host is refused before anything is dialled`() {
        listOf("evil.example.com", "192.168.1.10", "8.8.8.8").forEach { host ->
            val error = assertThrows(PairingInvite.ParseError.HostNotOnTailnet::class.java) {
                PairingInvite.parse("siliconbuddy://pair?host=$host&port=8788&code=123456")
            }
            assertEquals(host, error.host)
        }
    }

    @Test
    fun `a tailnet pairing link parses`() {
        assertEquals(
            "100.100.100.100",
            PairingInvite.parse("siliconbuddy://pair?host=100.100.100.100&port=8788&code=418203").host,
        )
    }

    @Test
    fun `the refusal explains itself`() {
        val message = PairingInvite.ParseError.HostNotOnTailnet("evil.example.com").message.orEmpty()
        assertTrue(message.contains("tailnet"))
        assertTrue(message.contains("evil.example.com"))
    }

    // MARK: - The token, when the keystore is gone

    @Test
    fun `an insecure device never writes the token down`() {
        val config = ServerConfig(
            host = "100.64.0.1", port = 8788, token = "a-bearer-token",
            macName = "Mac Studio", deviceID = "D1", scope = DeviceScope.Chat,
        )
        val written = TokenStore.writableFields(config, secure = false)
        assertNull("The token must not reach disk", written["token"])
        assertFalse(written.values.any { it == "a-bearer-token" })
        // The rest is still remembered, so the form comes back filled in.
        assertEquals("100.64.0.1", written["host"])
        assertEquals(8788, written["port"])
        assertEquals("chat", written["scope"])
    }

    @Test
    fun `a device with a keystore keeps the token`() {
        val config = ServerConfig(host = "100.64.0.1", port = 8788, token = "a-bearer-token")
        assertEquals("a-bearer-token", TokenStore.writableFields(config, secure = true)["token"])
    }

    // MARK: - Scope

    @Test
    fun `a chat scope device is not offered control`() {
        val config = ServerConfig(
            host = "100.64.0.1", port = 8788, token = "t", scope = DeviceScope.Chat,
        )
        assertFalse(config.canControl)
        assertTrue(DeviceScope.Chat.explanation.contains("chat only"))
    }

    @Test
    fun `a full scope device is offered control`() {
        assertTrue(
            ServerConfig(host = "100.64.0.1", port = 8788, token = "t").canControl,
        )
    }

    @Test
    fun `an unknown scope from a newer Mac is not mistaken for control`() {
        // Forward compatibility runs the other way for scopes: an unrecognised name is
        // read as full only because that is what an *older* Mac meant by silence.
        assertEquals(DeviceScope.Full, DeviceScope.from(null))
        assertEquals(DeviceScope.Chat, DeviceScope.from("chat"))
        assertEquals(DeviceScope.Chat, DeviceScope.from("CHAT"))
    }

    // MARK: - What a device may send

    @Test
    fun `too many pictures is refused before sending`() {
        val attachments = List(9) { "data:image/jpeg;base64,AAAA" }
        assertNotNull(SendLimits.problem(attachments, "look"))
    }

    @Test
    fun `a picture over the Mac's limit is refused before sending`() {
        val big = "data:image/jpeg;base64," + "A".repeat(SendLimits.MAX_IMAGE_BYTES * 2)
        assertNotNull(SendLimits.problem(listOf(big), ""))
    }

    @Test
    fun `a body over four megabytes is refused before sending`() {
        val attachments = List(3) {
            "data:image/jpeg;base64," + "A".repeat(1_800_000)
        }
        val problem = SendLimits.problem(attachments, "")
        assertNotNull(problem)
        assertTrue(problem!!.contains("4 MB"))
    }

    @Test
    fun `eight small pictures are fine`() {
        val attachments = List(8) { "data:image/jpeg;base64," + "A".repeat(100_000) }
        assertNull(SendLimits.problem(attachments, "look"))
    }
}
