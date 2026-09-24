package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.pairing.DeveloperConnection
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TailnetHost
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The build the owner installs, with its own BuildConfig: every way in refuses a Mac on this
 * machine — the host rule, a pairing link, the Developer form, and the client itself, before
 * it dials. Run by `scripts/ci-android.sh` as `:app:testReleaseUnitTest`; in the debug unit
 * tests, which dial a stand-in Mac here on purpose, it is skipped.
 */
class ReleaseBuildRuleTest {

    @Test
    fun `the release build refuses loopback and the emulator's host everywhere`() {
        assumeTrue("the release build's own rule", BuildConfig.BUILD_TYPE == "release")
        assertFalse(BuildConfig.LOCAL_MACS)
        for (host in listOf("127.0.0.1", "127.9.9.9", "localhost", "::1", "[::1]", "10.0.2.2")) {
            assertFalse("$host is allowed", TailnetHost.isAllowed(host))
            try {
                PairingInvite.parse("siliconbuddy://pair?host=$host&port=8788&code=418203")
                fail("a pairing link to $host was accepted")
            } catch (expected: PairingInvite.ParseError.HostNotOnTailnet) {
            }
            assertEquals(DeveloperConnection.LOCAL_ONLY, DeveloperConnection.problem(host, "8765"))
            assertNull("Enter code points $host at Developer", DeveloperConnection.codeAddressHint(host))
            // Port 1 — tcpmux, never a Mac — so that even a rule broken past the assertion
            // above could not reach anything real on the machine running the test.
            val refused = runCatching {
                runBlocking { ControlClient(ServerConfig(host, 1, token = "t")).health() }
            }.exceptionOrNull()
            assertTrue("$host was dialled: $refused", refused is TransportError.Forbidden)
        }
        assertFalse("the Developer form is offered", DeveloperConnection.offered)
        assertTrue(TailnetHost.isAllowed("100.64.0.9"))
        assertFalse(TailnetHost.EXPLANATION.contains("10.0.2.2"))
    }
}
