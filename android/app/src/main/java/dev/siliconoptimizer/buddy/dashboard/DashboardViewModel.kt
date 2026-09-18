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

    /** One pass over every dashboard endpoint, in parallel. */
    fun refresh(transport: ControlTransport?, app: AppState? = null) {
        if (transport == null) {
            error = "No Mac is paired yet."
            return
        }
        viewModelScope.launch {
            isLoading = true
            val statusTask = async { runCatching { transport.status() }.getOrNull() }
            val profileTask = async { runCatching { transport.profile() }.getOrNull() }
            val metricsTask = async { runCatching { transport.metrics() }.getOrNull() }
            val swarmTask = async { runCatching { transport.swarm() }.getOrNull() }
            val nodeTask = async { runCatching { transport.node() }.getOrNull() }

            val newStatus = statusTask.await()
            val newProfile = profileTask.await()
            val newMetrics = metricsTask.await()
            val newSwarm = swarmTask.await()
            val newNode = nodeTask.await()

            error = if (newStatus == null && newProfile == null && newMetrics == null) {
                "Couldn't reach the Mac."
            } else {
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
        if (transport == null) return
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(seconds * 1000)
                runCatching { transport.metrics() }.getOrNull()?.let { metrics = it }
                runCatching { transport.status() }.getOrNull()?.let { status = it }
            }
        }
    }

    fun stopLiveUpdates() {
        ticker?.cancel()
        ticker = null
    }

    override fun onCleared() {
        stopLiveUpdates()
        super.onCleared()
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
