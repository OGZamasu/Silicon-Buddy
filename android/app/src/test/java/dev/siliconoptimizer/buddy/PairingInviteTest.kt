package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.pairing.PairingInvite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingInviteTest {

    @Test
    fun `parses the Mac's QR code`() {
        val invite = PairingInvite.parse("siliconbuddy://pair?host=100.118.191.4&port=8788&code=418203")
        assertEquals("100.118.191.4", invite.host)
        assertEquals(8788, invite.port)
        assertEquals("418203", invite.code)
    }

    @Test
    fun `accepts the path spelling`() {
        val invite = PairingInvite.parse("siliconbuddy:///pair?host=mac.local&port=80&code=000000")
        assertEquals("mac.local", invite.host)
        assertEquals(80, invite.port)
    }

    @Test
    fun `is case insensitive about the scheme`() {
        assertEquals(1, PairingInvite.parse("SiliconBuddy://Pair?host=h&port=1&code=123456").port)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("h", PairingInvite.parse("  siliconbuddy://pair?host=h&port=9&code=123456\n").host)
    }

    @Test
    fun `rejects another scheme`() {
        val error = assertThrows(PairingInvite.ParseError.WrongScheme::class.java) {
            PairingInvite.parse("https://example.com/pair?host=h&port=1&code=123456")
        }
        assertEquals("https", error.scheme)
    }

    @Test
    fun `rejects another action`() {
        val error = assertThrows(PairingInvite.ParseError.WrongAction::class.java) {
            PairingInvite.parse("siliconbuddy://open?host=h&port=1&code=123456")
        }
        assertEquals("open", error.action)
    }

    @Test
    fun `rejects a missing host`() {
        val error = assertThrows(PairingInvite.ParseError.Missing::class.java) {
            PairingInvite.parse("siliconbuddy://pair?port=1&code=123456")
        }
        assertEquals("host", error.field)
    }

    @Test
    fun `rejects a missing port`() {
        val error = assertThrows(PairingInvite.ParseError.Missing::class.java) {
            PairingInvite.parse("siliconbuddy://pair?host=h&code=123456")
        }
        assertEquals("port", error.field)
    }

    @Test
    fun `rejects a port out of range`() {
        assertThrows(PairingInvite.ParseError.BadPort::class.java) {
            PairingInvite.parse("siliconbuddy://pair?host=h&port=99999&code=123456")
        }
    }

    @Test
    fun `rejects a port that is not a number`() {
        assertThrows(PairingInvite.ParseError.BadPort::class.java) {
            PairingInvite.parse("siliconbuddy://pair?host=h&port=eight&code=123456")
        }
    }

    @Test
    fun `rejects a code of the wrong length`() {
        assertThrows(PairingInvite.ParseError.BadCode::class.java) {
            PairingInvite.parse("siliconbuddy://pair?host=h&port=1&code=12345")
        }
    }

    @Test
    fun `rejects a non-numeric code`() {
        assertThrows(PairingInvite.ParseError.BadCode::class.java) {
            PairingInvite.parse("siliconbuddy://pair?host=h&port=1&code=abc123")
        }
    }

    @Test
    fun `rejects empty text`() {
        assertThrows(PairingInvite.ParseError.NotAUri::class.java) { PairingInvite.parse("   ") }
    }

    @Test
    fun `rejects an arbitrary QR code`() {
        assertThrows(PairingInvite.ParseError::class.java) {
            PairingInvite.parse("WIFI:S:Home;T:WPA;P:hunter2;;")
        }
    }

    @Test
    fun `round trips through its own URI`() {
        val original = PairingInvite("100.64.0.1", 8788, "007007")
        assertEquals(original, PairingInvite.parse(original.toUriString()))
    }
}
