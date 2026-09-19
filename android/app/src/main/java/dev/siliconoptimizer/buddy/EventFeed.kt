package dev.siliconoptimizer.buddy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.DownloadProgress
import dev.siliconoptimizer.buddy.transport.JobProgress
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What the Mac is doing, pushed rather than asked for.
 *
 * One stream for the whole app: the Mac answers 429 to a device that opens several, and
 * the dashboard and the models list want the same facts anyway. When `GET /events` is
 * not there — a Mac from before M0 — this says so, and the screens that care go back to
 * polling. That is the only reason polling still exists.
 */
class EventFeed : ViewModel() {

    var status by mutableStateOf<Status?>(null)
        private set
    val downloads = mutableStateMapOf<String, DownloadProgress>()
    val jobs = mutableStateMapOf<String, JobProgress>()

    /**
     * The last answer check the Mac published, by conversation. Kept rather than
     * consumed: the chat screen may not be on screen when it arrives.
     */
    val verdicts = mutableStateMapOf<String, dev.siliconoptimizer.buddy.transport.Verdict>()
    var lastEvent by mutableStateOf<Long?>(null)
        private set

    /** True once the stream is open and delivering. */
    var isLive by mutableStateOf(false)
        private set

    /** True when this Mac has no `/events` and the screens must poll instead. */
    var mustPoll by mutableStateOf(false)
        private set

    /**
     * How long until the next attempt, while the stream is down. Null when it is up.
     *
     * The client reconnects by itself, so nothing here has to act on this — but a screen
     * that says "Streaming from /events" during an outage is telling the owner the one
     * thing they would check it for, wrongly.
     */
    var retryInMillis by mutableStateOf<Long?>(null)
        private set

    /** The backoff step the client has reached; 0 while connected. */
    var reconnectAttempt by mutableStateOf(0)
        private set

    private var job: Job? = null

    fun start(transport: ControlTransport?) {
        stop()
        if (transport == null) {
            mustPoll = true
            return
        }
        mustPoll = false
        job = viewModelScope.launch {
            try {
                transport.events().collect { event ->
                    if (event is ServerEvent.Disconnected) {
                        isLive = false
                        reconnectAttempt = event.attempt
                        retryInMillis = event.retryInMillis
                        return@collect
                    }
                    isLive = true
                    reconnectAttempt = 0
                    retryInMillis = null
                    lastEvent = System.currentTimeMillis()
                    when (event) {
                        is ServerEvent.StatusChanged -> status = event.status
                        is ServerEvent.Download -> {
                            if ((event.progress.progress ?: 0.0) >= 1.0) {
                                downloads.remove(event.progress.id)
                            } else {
                                downloads[event.progress.id] = event.progress
                            }
                        }
                        is ServerEvent.Job -> {
                            val done = event.progress.status.lowercase() in
                                setOf("finished", "failed", "cancelled")
                            if (done) jobs.remove(event.progress.id)
                            else jobs[event.progress.id] = event.progress
                        }
                        is ServerEvent.Checked -> {
                            // Keyed by conversation, because that is the only key the
                            // transcript shares with the Mac today: `StoredMessage`
                            // carries no id, so a per-message match is not possible
                            // until the Mac exports one.
                            verdicts[event.verdict.conversationID.orEmpty()] = event.verdict
                        }
                        is ServerEvent.Beat -> Unit
                        // Handled above, before the stream is called live.
                        is ServerEvent.Disconnected -> Unit
                    }
                }
                isLive = false
            } catch (error: TransportError) {
                // Pre-M0 Mac, or a token that stopped working. Either way the screens
                // have to ask rather than wait.
                isLive = false
                mustPoll = true
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        isLive = false
        retryInMillis = null
        reconnectAttempt = 0
    }

    fun clear() {
        stop()
        status = null
        downloads.clear()
        jobs.clear()
        lastEvent = null
        mustPoll = false
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /** The download for a model, whichever spelling of its id the list is holding. */
    fun download(modelID: String): DownloadProgress? {
        downloads[modelID]?.let { return it }
        val base = modelID.substringBefore('@')
        return downloads.entries.firstOrNull {
            it.key == base || it.key.startsWith("$base@")
        }?.value
    }
}
