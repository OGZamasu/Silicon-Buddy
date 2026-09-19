package dev.siliconoptimizer.buddy

import dev.siliconoptimizer.buddy.machines.Machines
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Machines page, built from the Mac's own export.
 *
 * Driven by the contract fixtures rather than by invented objects, because the point of
 * the page is that it says true things about real machines — and because the fields it
 * *cannot* fill in are as much a part of the milestone as the ones it can.
 */
class MachinesTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private inline fun <reified T> response(name: String): T {
        val text = javaClass.classLoader?.getResourceAsStream("$name.json")?.bufferedReader()
            ?.readText() ?: error("Missing fixture $name.json — run contract/refresh.sh")
        val fields = Json.parseToJsonElement(text) as JsonObject
        return json.decodeFromString(fields.getValue("response").toString())
    }

    private val status: Status get() = response("GET__status")
    private val profile: Profile get() = response("GET__profile")
    private val metrics: Metrics get() = response("GET__metrics")
    private val node: NodeAdvertisement get() = response("GET__v1_node")
    private val swarm: SwarmView get() = response("GET__swarm")

    @Test
    fun `this Mac comes first, named the way it advertises itself`() {
        val machines = Machines.from(status, profile, metrics, node, swarm)
        val mac = machines.first()
        assertTrue(mac.isThisMac)
        assertEquals("Mac Studio", mac.name)
        assertTrue("The chip is what a person recognises", mac.headline.contains("Apple M3 Max"))
        assertTrue(mac.headline.contains("40 GPU cores"))
        assertTrue("What is loaded is the first thing worth knowing", mac.detail!!.contains("Qwen3-Coder 30B A3B"))
    }

    @Test
    fun `the Mac's card carries memory, pressure and the GPU`() {
        val mac = Machines.from(status, profile, metrics, node, swarm).first()
        val stats = mac.stats.toMap()
        assertNotNull(stats["Memory"])
        assertEquals("Normal", stats["Pressure"])
        assertEquals("12%", stats["GPU"])
        assertEquals("24%", stats["CPU"])
        assertNotNull("The node advertisement knows the queue", stats["Queue"])
        assertNotNull(stats["Headroom"])
        assertNull("No swap is not a line worth printing", stats["Swap"])
    }

    @Test
    fun `the Mac's own lanes come from what it advertises to peers`() {
        val mac = Machines.from(status, profile, metrics, node, swarm).first()
        assertEquals(listOf("llm-qwen3-coder-30b"), mac.lanes.map { it.id })
        assertEquals("Loaded at 16K context", mac.lanes.first().detail)
        assertTrue(mac.lanes.first().ready)
    }

    @Test
    fun `each peer is a machine with the lanes the swarm advertises`() {
        val machines = Machines.from(status, profile, metrics, node, swarm)
        val peer = machines.last()
        assertFalse(peer.isThisMac)
        assertEquals("silicon-node", peer.name)
        assertTrue(peer.reachable)
        assertEquals("NVIDIA GeForce RTX 3090 Ti · windows-cuda", peer.headline)
        assertEquals("silicon-node", peer.address)
        assertEquals(
            listOf("image-to-mesh", "text-to-video", "text-to-image"),
            peer.lanes.map { it.id },
        )
        assertEquals(listOf("mesh", "video", "image"), peer.lanesByKind.keys.toList())
        assertFalse("a lane the node is not serving says so", peer.lanes.last().ready)
    }

    /** What the poll carries now, which is most of what the page used to be missing. */
    @Test
    fun `a peer's card shows the memory and the GPU the poll saw`() {
        val peer = Machines.from(status, profile, metrics, node, swarm).last()
        val stats = peer.stats.toMap()
        assertEquals("9.4 GB of 24.0 GB", stats["Memory"])
        assertEquals("14.6 GB", stats["Headroom"])
        assertEquals("38%", stats["GPU"])
        assertEquals("job:text-to-video", stats["GPU held by"])
        assertEquals("1 waiting", stats["Queue"])
        assertTrue("and the GGUF it is serving", peer.detail!!.contains("qwen3.8-27b"))
    }

    /**
     * The poll is a memory; the adapter is only ever in the node's own answer. Asking
     * replaces the remembered card with the one the node just gave.
     */
    @Test
    fun `asking a node directly is the only way to see its adapter`() {
        val remembered = Machines.from(status, profile, metrics, node, swarm).last()
        assertFalse(remembered.detail!!.contains("lora"))
        assertNotNull(remembered.blindSpot)

        val asked = Machines.asked(response("GET__swarm_peers__name__status"))
        assertEquals("silicon-node", asked.name)
        assertTrue(asked.detail!!.contains("bonsai-27b-v3.lora.gguf"))
        assertTrue(asked.detail!!.contains("64K context"))
        assertEquals("NVIDIA GeForce RTX 3090 Ti · windows-cuda", asked.headline)
        assertEquals("2 models", asked.stats.toMap()["On its disk"])
        assertNull("nothing is missing from an answer it just gave", asked.blindSpot)
    }

    /**
     * The honest half. `GET /swarm` gives a name, an address, reachability and lanes —
     * and nothing about the GGUF that peer has loaded, its adapters or its GPU. The
     * page says so rather than leaving a gap that reads like a bug.
     */
    @Test
    fun `a peer's card admits what the Mac does not publish about it`() {
        val peer = Machines.from(status, profile, metrics, node, swarm).last()
        assertNotNull(peer.blindSpot)
        assertTrue(peer.blindSpot!!.contains("loaded GGUF"))
        assertNull("This Mac hides nothing from itself", Machines.from(status, profile, metrics, node, null).first().blindSpot)
    }

    @Test
    fun `an unreachable peer says why, when the Mac knows why`() {
        val broken = swarm.copy(
            peers = swarm.peers.map {
                it.copy(reachable = false, error = "Connection refused.")
            },
        )
        val peer = Machines.from(status, profile, metrics, node, broken).last()
        assertFalse(peer.reachable)
        assertEquals("Not answering", peer.headline)
        assertEquals("Connection refused.", peer.detail)
    }

    @Test
    fun `a Mac that answers nothing at all is no machines rather than an empty card`() {
        assertTrue(Machines.from(null, null, null, null, null).isEmpty())
        // One reading is enough to draw the Mac: a missing /v1/node costs its name, not
        // the card.
        val partial = Machines.from(status, null, null, null, null)
        assertEquals(1, partial.size)
        assertEquals("This Mac", partial.first().name)
    }

    @Test
    fun `a peer's address is the host, not a URL with a port in it`() {
        assertEquals("100.64.0.7", Machines.host("http://100.64.0.7:8790"))
        assertEquals("not a url", Machines.host("not a url"))
    }
}
