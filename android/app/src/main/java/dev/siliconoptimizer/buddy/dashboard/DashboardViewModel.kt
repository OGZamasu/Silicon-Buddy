package dev.siliconoptimizer.buddy.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.AppState
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.Metrics
import dev.siliconoptimizer.buddy.transport.NodeAdvertisement
import dev.siliconoptimizer.buddy.transport.Profile
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.SwarmView
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Everything the first screen shows, fetched together.
 *
 * Each reading is kept separately so one slow or missing endpoint — `/v1/node` on an
 * older Mac, say — leaves the rest of the screen intact instead of blanking it.
 */
class DashboardViewModel : ViewModel() {

    var status by mutableStateOf<Status?>(null)
        private set

    /**
     * When the Mac last answered anything here, as a clock reading.
     *
     * The dashboard asks the Mac every few seconds whether anyone is watching the chat or
     * not, so it is usually the first to know that a Mac which was out of reach is back —
     * earlier than the reachability probe, and without a stream. The chat reads this to take
     * its "answer on this phone" offer down.
     */
    var macAnsweredAt by mutableStateOf(0L)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set
    var metrics by mutableStateOf<Metrics?>(null)
        private set
    var swarm by mutableStateOf<SwarmView?>(null)
        private set
    var node by mutableStateOf<NodeAdvertisement?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var ticker: Job? = null

    /**
     * The Mac refused this phone's token (401): it no longer knows this phone. Nothing is
     * polled again — no retry mends that — until the phone is paired anew, which resets it.
     */
    var unpaired by mutableStateOf(false)
        private set

    /** Stops every reading this screen makes, for good, and says why. */
    fun markUnpaired() {
        unpaired = true
        stopLiveUpdates()
        isLoading = false
        error = UNPAIRED
    }

    /** Set when this Mac has no `/events`, in which case `/status` has to be asked for. */
    var pollsStatus by mutableStateOf(true)

    /** Throws away the last Mac's readings, so a re-pair never shows another machine. */
    fun reset() {
        stopLiveUpdates()
        unpaired = false
        status = null
        profile = null
        metrics = null
        swarm = null
        node = null
        error = null
    }

    /** Takes the status the event stream pushed, so the card is current without asking. */
    fun apply(streamed: Status) {
        status = streamed
    }

    /** One pass over every dashboard endpoint, in parallel. */
    fun refresh(transport: ControlTransport?, app: AppState? = null) {
        if (transport == null) {
            error = "No Mac is paired yet."
            return
        }
        if (unpaired) return
        viewModelScope.launch {
            isLoading = true
            val statusTask = async { runCatching { transport.status() } }
            val profileTask = async { runCatching { transport.profile() } }
            val metricsTask = async { runCatching { transport.metrics() } }
            val swarmTask = async { runCatching { transport.swarm() } }
            val nodeTask = async { runCatching { transport.node() } }
            val all = listOf(statusTask, profileTask, metricsTask, swarmTask, nodeTask).map { it.await() }
            if (all.any { it.exceptionOrNull() is TransportError.Unauthorized }) {
                markUnpaired()
                return@launch
            }

            val newStatus = statusTask.await().getOrNull()
            val newProfile = profileTask.await().getOrNull()
            val newMetrics = metricsTask.await().getOrNull()
            val newSwarm = swarmTask.await().getOrNull()
            val newNode = nodeTask.await().getOrNull()

            error = if (newStatus == null && newProfile == null && newMetrics == null) {
                "Couldn't reach the Mac."
            } else {
                macAnsweredAt = System.currentTimeMillis()
                null
            }
            newStatus?.let { status = it }
            newProfile?.let { profile = it }
            newMetrics?.let { metrics = it }
            newSwarm?.let { swarm = it }
            newNode?.let {
                node = it
                app?.noteMacName(it.name)
            }
            isLoading = false
        }
    }

    /**
     * Live metrics while the dashboard is on screen. Polling, because `GET /events`
     * arrives with M0; when it does this becomes the fallback rather than the plan.
     */
    fun startLiveUpdates(transport: ControlTransport?, seconds: Long = 4) {
        ticker?.cancel()
        pausedTicker = null
        tickerTransport = transport
        if (transport == null || unpaired) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(seconds * 1000)
                val read = runCatching { transport.metrics() }
                if (read.exceptionOrNull() is TransportError.Unauthorized) return@launch markUnpaired()
                read.getOrNull()?.let {
                    metrics = it
                    macAnsweredAt = System.currentTimeMillis()
                }
                // The status card comes from the event stream when the Mac has one.
                if (pollsStatus) {
                    val asked = runCatching { transport.status() }
                    if (asked.exceptionOrNull() is TransportError.Unauthorized) return@launch markUnpaired()
                    asked.getOrNull()?.let { status = it }
                }
            }
        }
    }

    fun stopLiveUpdates() {
        ticker?.cancel()
        ticker = null
        pausedTicker = null
    }

    /** The transport a paused ticker was polling, until it is resumed. */
    private var pausedTicker: ControlTransport? = null
    private var tickerTransport: ControlTransport? = null

    /**
     * The app left the screen: nobody is looking at the metrics, and polling them every few
     * seconds from the background keeps a radio awake for nothing.
     */
    fun pauseLiveUpdates() {
        if (ticker == null) return
        pausedTicker = tickerTransport
        ticker?.cancel()
        ticker = null
    }

    /** Back on screen: polling resumes if [pauseLiveUpdates] stopped it. */
    fun resumeLiveUpdates() {
        val transport = pausedTicker ?: return
        pausedTicker = null
        startLiveUpdates(transport)
    }

    override fun onCleared() {
        stopLiveUpdates()
        super.onCleared()
    }

    companion object {
        const val UNPAIRED = "This phone is no longer paired with the Mac. Pair it again to see it here."
    }

    val loadedModelTitle: String
        get() = status?.loadedModelName ?: status?.activity ?: status?.state ?: "Unknown"

    val loadedModelDetail: String
        get() {
            val current = status ?: return "No reading yet."
            val parts = buildList {
                add(current.state)
                current.contextLength?.let { add("${it / 1024}K context") }
                if (current.expertStreaming) add("expert streaming")
                current.activity?.takeIf { current.loadedModelID != null }?.let { add(it) }
            }
            return parts.joinToString(" · ")
        }
}
