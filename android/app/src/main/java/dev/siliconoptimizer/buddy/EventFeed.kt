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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
     * Every `job` event, terminal ones included.
     *
     * The map above is "what is running now", which is what the dashboard wants; the
     * queue and the notifications want the moment a render finished or failed, and that
     * is exactly the event the map drops. One stream for the app, two shapes of it.
     */
    private val _jobEvents = MutableSharedFlow<JobProgress>(
        replay = 0, extraBufferCapacity = 64,
    )
    val jobEvents: SharedFlow<JobProgress> = _jobEvents.asSharedFlow()

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
     * When the next attempt is due, while the stream is down. Null when it is up.
     *
     * A moment rather than a duration: a stored "4000ms" read a second later is a second
     * wrong, and this is what a countdown is drawn from. The client reconnects by itself,
     * so nothing here has to act on it — but a screen that says "Streaming from /events"
     * during an outage is telling the owner the one thing they would check it for,
     * wrongly.
     */
    var retryAt by mutableStateOf<Long?>(null)
        private set

    /** Whatever the drop was, as a fixed tag. Never the error's message, which names the Mac. */
    var lastDropSummary by mutableStateOf<String?>(null)
        private set

    /** Seconds until the next attempt, rounded up, or null while the stream is up. */
    fun secondsUntilRetry(now: Long = System.currentTimeMillis()): Long? =
        retryAt?.let { maxOf(0L, (it - now + 999) / 1000) }

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
                        retryAt = System.currentTimeMillis() + event.retryInMillis
                        lastDropSummary = event.summary
                        // The stream is down, so there is no "last event" any longer.
                        // Leaving the old timestamp makes a dead stream read as one that
                        // was alive a moment ago.
                        lastEvent = null
                        return@collect
                    }
                    isLive = true
                    reconnectAttempt = 0
                    retryAt = null
                    lastDropSummary = null
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
                            // The Mac's own words for a render that is over. "completed"
                            // is the video queue's; the others are what the image and
                            // mesh lanes say.
                            val done = event.progress.status.lowercase() in
                                setOf("finished", "completed", "failed", "cancelled")
                            if (done) jobs.remove(event.progress.id)
                            else jobs[event.progress.id] = event.progress
                            _jobEvents.tryEmit(event.progress)
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
        retryAt = null
        lastDropSummary = null
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
