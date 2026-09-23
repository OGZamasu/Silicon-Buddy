package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.transport.ConnectivityProbe
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.Health
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ServerConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Which Silicon Optimizer the phone says it is talking to (OGZamasu/silicon-optimizer#81).
 *
 * A Mac used to answer `/health` with a literal "0.1.0", so a phone connected to 0.5.0
 * build 157 said 0.1.0. A current Mac says `appVersion` and `appBuild`; an older one still
 * answers, and the phone has to show something true for both.
 */
class AppVersionTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private lateinit var server: LoopbackServer

    @Before
    fun setUp() {
        server = LoopbackServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun health(text: String) = json.decodeFromString<Health>(text)

    // MARK: - What each Mac says

    @Test
    fun `a current Mac's version and build`() {
        assertEquals("0.5.0 (157)", health(NEW_MAC).appVersionLabel)
    }

    @Test
    fun `an older Mac's version field`() {
        assertEquals("0.1.0", health(OLD_MAC).appVersionLabel)
    }

    @Test
    fun `the app version wins over whatever version says`() {
        // A Mac is free to mean something else by `version` — a protocol, say. Once it
        // names its app version, that is the one shown.
        val health = health("""{"status":"ok","version":"2","appVersion":"0.6.0","appBuild":"170"}""")
        assertEquals("0.6.0 (170)", health.appVersionLabel)
    }

    @Test
    fun `a build is shown only beside a named app version`() {
        assertEquals("0.1.0", health("""{"status":"ok","version":"0.1.0","appBuild":"157"}""").appVersionLabel)
        assertEquals("0.5.0", health("""{"status":"ok","appVersion":"0.5.0","appBuild":" "}""").appVersionLabel)
        assertEquals("dev", health("""{"status":"ok","appVersion":"dev","appBuild":"dev"}""").appVersionLabel)
    }

    @Test
    fun `a Mac that names no version gets none`() {
        assertNull(health("""{"status":"ok"}""").appVersionLabel)
        assertNull(health("""{"status":"ok","version":"","appVersion":" "}""").appVersionLabel)
    }

    // MARK: - What the phone shows

    private fun probe(): Reachability = runBlocking {
        server.reply("/status", 200, """{"state":"Loaded","loadedModelName":"Qwen3 4B"}""")
        ConnectivityProbe(ControlClient(ServerConfig("127.0.0.1", server.port, "device-token"))).check()
    }

    @Test
    fun `connected to a current Mac, the phone shows its version and build`() {
        server.reply("/health", 200, NEW_MAC)
        val ready = probe()
        assertEquals(Reachability.Ready("0.5.0 (157)", "Qwen3 4B"), ready)
        assertEquals("Silicon Optimizer 0.5.0 (157) — Qwen3 4B", ready.detail)
    }

    @Test
    fun `connected to an older Mac, the phone shows what it said`() {
        server.reply("/health", 200, OLD_MAC)
        assertEquals("Silicon Optimizer 0.1.0 — Qwen3 4B", probe().detail)
    }

    @Test
    fun `a Mac without health is not given a version called unknown`() {
        // No /health reply: the loopback server answers 404, a route this Mac lacks.
        val ready = probe()
        assertEquals(Reachability.Ready(null, "Qwen3 4B"), ready)
        assertEquals("Silicon Optimizer — Qwen3 4B", ready.detail)
    }

    private companion object {
        /** `/health` from a Mac with #81: the running bundle's version and build. */
        const val NEW_MAC = """{"status":"ok","version":"0.5.0","appVersion":"0.5.0","appBuild":"157"}"""

        /** `/health` from a Mac before it: one field, whatever the app really was. */
        const val OLD_MAC = """{"status":"ok","version":"0.1.0"}"""
    }
}
