package dev.siliconoptimizer.buddy.machines

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.ui.Format
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URI
import java.util.Locale

/**
 * The machines page: this Mac, and everything it can hand work to.
 *
 * Assembled from five routes that each know a piece — `/status`, `/profile`,
 * `/metrics`, `/v1/node` and `/swarm` — into rows that say the same kinds of thing
 * about every machine, so the node and the Mac can be read side by side.
 *
 * What is missing is deliberate rather than forgotten. `GET /swarm` gives a peer's
 * name, address, whether it answered and which lanes it advertises; it does not give
 * that peer's loaded GGUF, its adapters or its GPU. The Mac holds more than it
 * publishes, and a phone cannot reach a node directly, so those lines are absent here
 * rather than invented. See `docs/PLAN.md`.
 */

/** One lane a machine offers: a model or a kind of work, and whether it is ready. */
data class Lane(val id: String, val kind: String, val ready: Boolean, val detail: String? = null)

/** One machine, however it was learned about. */
data class Machine(
    val name: String,
    val isThisMac: Boolean,
    val reachable: Boolean,
    /** The chip, or the platform — what this machine is. */
    val headline: String,
    /** What it is doing, or why it is not answering. */
    val detail: String? = null,
    val address: String? = null,
    val stats: List<Pair<String, String>> = emptyList(),
    val lanes: List<Lane> = emptyList(),
    /** What this app cannot see about this machine, and why. */
    val blindSpot: String? = null,
) {
    val lanesByKind: Map<String, List<Lane>> get() = lanes.groupBy { it.kind }
}

object Machines {

    /**
     * What `GET /swarm` is: the Mac's last poll, not the node's own answer.
     *
     * Everything past name, address and reachability is optional there, and the adapter
     * riding on a loaded GGUF is not in it at all. `GET /swarm/peers/{name}/status`
     * asks the node now, which is what the "Ask it now" button on a peer's card does.
     */
    const val PEER_BLIND_SPOT =
        "This is what the Mac's last poll saw. Ask the node itself for its loaded " +
            "GGUF, its adapter and its GPU."

    fun from(
        status: Status?,
        profile: Profile?,
        metrics: Metrics?,
        node: NodeAdvertisement?,
        swarm: SwarmView?,
    ): List<Machine> = listOfNotNull(thisMac(status, profile, metrics, node)) +
        (swarm?.peers?.map(::peer) ?: emptyList())

    private fun thisMac(
        status: Status?,
        profile: Profile?,
        metrics: Metrics?,
        node: NodeAdvertisement?,
    ): Machine? {
        if (status == null && profile == null && metrics == null && node == null) return null
        val chip = profile?.chip ?: node?.profile?.chip ?: "This Mac"
        val cores = listOfNotNull(
            profile?.gpuCores?.let { "$it GPU cores" } ?: node?.profile?.gpuCores?.let { "$it GPU cores" },
            profile?.let { "${it.performanceCores}P + ${it.efficiencyCores}E" },
        ).joinToString(" · ")
        return Machine(
            name = node?.name ?: "This Mac",
            isThisMac = true,
            reachable = true,
            headline = listOfNotNull(chip, cores.takeIf { it.isNotEmpty() }).joinToString(" · "),
            detail = status?.let {
                listOfNotNull(
                    it.loadedModelName ?: it.state,
                    it.contextLength?.let { context -> "${context / 1024}K context" },
                    it.lastGenerationTokensPerSecond?.takeIf { rate -> rate > 0 }
                        ?.let { rate -> Format.rate(rate) },
                ).joinToString(" · ")
            },
            stats = buildList {
                metrics?.let {
                    add("Memory" to "${Format.bytes(it.memoryUsedBytes)} of ${Format.bytes(it.memoryTotalBytes)}")
                    add("Pressure" to it.memoryPressure.replaceFirstChar { c -> c.uppercase() })
                    add("GPU" to Format.percent(it.gpuUtilization))
                    add("CPU" to Format.percent(it.cpuUtilization))
                    if (it.swapUsedBytes > 0) add("Swap" to Format.bytes(it.swapUsedBytes))
                }
                profile?.let { add("A model may have" to Format.bytes(it.modelBudgetBytes)) }
                node?.metrics?.let {
                    add("Queue" to "${it.queueDepth} waiting")
                    add("Headroom" to Format.gigabytes(it.headroomGB))
                }
            },
            lanes = node?.capabilities?.map {
                Lane(it.id, it.kind, it.ready, it.detail.takeIf { d -> d.isNotBlank() })
            } ?: emptyList(),
        )
    }

    /** One peer as it answered just now, rather than as the Mac last remembered it. */
    fun asked(status: dev.siliconoptimizer.buddy.transport.PeerNodeStatus): Machine = Machine(
        name = status.name,
        isThisMac = false,
        reachable = status.reachable,
        headline = if (!status.reachable) {
            "Not answering"
        } else {
            listOfNotNull(status.hardware, status.platform).joinToString(" · ")
                .ifEmpty { "Answering" }
        },
        detail = status.error ?: status.gguf?.let { gguf ->
            if (gguf.running) {
                listOfNotNull(
                    gguf.model,
                    gguf.adapter?.let { "with $it" },
                    gguf.contextLength?.let { "${it / 1024}K context" },
                    gguf.engine,
                ).joinToString(" · ")
            } else {
                "No GGUF running"
            }
        },
        address = host(status.baseURL),
        stats = buildList {
            status.usedMemoryGB?.let { used ->
                status.totalMemoryGB?.let { total ->
                    add("Memory" to "${Format.gigabytes(used)} of ${Format.gigabytes(total)}")
                } ?: add("Memory" to Format.gigabytes(used))
            }
            status.headroomGB?.let { add("Headroom" to Format.gigabytes(it)) }
            status.gpuUtilization?.let { add("GPU" to Format.percent(it)) }
            status.queueDepth?.let { add("Queue" to "$it waiting") }
            status.gguf?.uptimeSeconds?.let {
                add("Serving for" to Format.secondsAgo(it).removeSuffix(" ago"))
            }
            status.gguf?.installedModels?.takeIf { it.isNotEmpty() }?.let {
                add("On its disk" to "${it.size} models")
            }
        },
        lanes = status.capabilities.map { Lane(it.id, it.kind, it.ready) },
        blindSpot = null,
    )

    private fun peer(peer: dev.siliconoptimizer.buddy.transport.SwarmPeer): Machine = Machine(
        name = peer.name,
        isThisMac = false,
        reachable = peer.reachable,
        // A machine that did not answer is not described by what it is made of.
        headline = if (!peer.reachable) {
            "Not answering"
        } else {
            listOfNotNull(peer.hardware, peer.platform).joinToString(" · ")
                .ifEmpty { "Answering" }
        },
        detail = peer.error ?: peer.loadedModel?.let { model ->
            listOfNotNull(
                model,
                peer.modelContextLength?.let { "${it / 1024}K context" },
                peer.modelEngine,
            ).joinToString(" · ")
        } ?: peer.capabilities
            .filter { it.ready }
            .map { kindName(it.kind) }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(", "),
        address = host(peer.baseURL),
        stats = buildList {
            peer.usedMemoryGB?.let { used ->
                peer.totalMemoryGB?.let { total ->
                    add("Memory" to "${Format.gigabytes(used)} of ${Format.gigabytes(total)}")
                } ?: add("Memory" to Format.gigabytes(used))
            }
            peer.headroomGB?.let { add("Headroom" to Format.gigabytes(it)) }
            peer.gpuUtilization?.let { add("GPU" to Format.percent(it)) }
            peer.gpuConsumer?.let { add("GPU held by" to it) }
            peer.queueDepth?.let { add("Queue" to "$it waiting") }
        },
        lanes = peer.capabilities.map { Lane(it.id, it.kind, it.ready) },
        blindSpot = PEER_BLIND_SPOT,
    )

    /** The Mac's word for a lane, as a person would read it. */
    fun kindName(kind: String): String = when (kind.lowercase()) {
        "llm" -> "Language"
        "image" -> "Image"
        "video" -> "Video"
        "mesh" -> "3D"
        "audio" -> "Audio"
        else -> kind.replaceFirstChar { it.uppercase(Locale.US) }
    }

    /** The host, not the whole URL: a port and a scheme say nothing on a phone. */
    fun host(baseURL: String): String =
        runCatching { URI(baseURL).host ?: baseURL }.getOrDefault(baseURL)
}

class MachinesViewModel : ViewModel() {

    var machines by mutableStateOf<List<Machine>>(emptyList())
        private set
    var polledSecondsAgo by mutableStateOf<Double?>(null)
        private set
    var exposure by mutableStateOf<dev.siliconoptimizer.buddy.transport.TailnetExposure?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun refresh(transport: ControlTransport?) {
        if (transport == null) {
            error = "No Mac is paired yet."
            return
        }
        viewModelScope.launch {
            isLoading = true
            load(transport)
            isLoading = false
        }
    }

    /**
     * One pass, as a plain suspending call so a test can drive it without a main
     * looper. Each reading is fetched separately: a Mac that has no `/v1/node` should
     * cost the node card, not the page.
     */
    suspend fun load(transport: ControlTransport) = coroutineScope {
        val status = async { runCatching { transport.status() }.getOrNull() }
        val profile = async { runCatching { transport.profile() }.getOrNull() }
        val metrics = async { runCatching { transport.metrics() }.getOrNull() }
        val node = async { runCatching { transport.node() }.getOrNull() }
        val swarm = async { runCatching { transport.swarm() }.getOrNull() }

        val loaded = status.await()
        val machine = profile.await()
        val now = metrics.await()
        val advertisement = node.await()
        val peers = swarm.await()
        machines = Machines.from(loaded, machine, now, advertisement, peers)
        polledSecondsAgo = peers?.polledSecondsAgo
        exposure = peers?.exposure
        error = if (machines.isEmpty()) "Couldn't reach the Mac." else null
    }

    /**
     * Asks one node directly.
     *
     * `GET /swarm` is a memory of a poll; this is the node answering, and the only
     * place its adapter appears. Full control only — the Mac sends its own credential
     * for that node to ask.
     */
    fun ask(name: String, transport: ControlTransport?) {
        if (transport == null) return
        viewModelScope.launch {
            asking = name
            try {
                val status = transport.peerStatus(name)
                machines = machines.map { if (it.name == name) Machines.asked(status) else it }
                askedAt = askedAt + (name to System.currentTimeMillis())
            } catch (failure: dev.siliconoptimizer.buddy.transport.TransportError) {
                error = failure.message
            } finally {
                asking = null
            }
        }
    }

    var asking by mutableStateOf<String?>(null)
        private set
    var askedAt by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    fun reset() {
        machines = emptyList()
        asking = null
        askedAt = emptyMap()
        polledSecondsAgo = null
        exposure = null
        error = null
    }
}
