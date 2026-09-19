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
     * Every `agent` frame, in the order the Mac sent them.
     *
     * A flow rather than state for the same reason as [jobEvents]: the sessions reducer
     * wants each frame, not the latest one. The buffer is generous because streamed prose
     * arrives ten frames a second per engine — and a frame that still does not fit is
     * not lost quietly: [agentFramesDropped] moves, and the sessions catch up from the
     * Mac rather than trusting a transcript with a hole in it.
     */
    private val _agentEvents = MutableSharedFlow<AgentFeed>(replay = 0, extraBufferCapacity = 512)
    val agentEvents: SharedFlow<AgentFeed> = _agentEvents.asSharedFlow()

    /** Bumped whenever an `agent` frame could not be handed on. */
    var agentFramesDropped by mutableStateOf(0)
        private set

    /**
     * Bumped on every `resync`: the Mac dropped frames for this phone. The agent sessions
     * hear it in order with their frames; this is for the rest — the render queue — which
     * has to be read again too.
     */
    var resyncs by mutableStateOf(0)
        private set

    /** A break this feed still owes the sessions, because the last one did not fit. */
    private var owesBreak = false

    /**
     * Hands an agent frame on, in order. A frame that does not fit is not dropped silently:
     * the next thing the sessions hear is that the stream broke, so they fetch the gap
     * rather than trust a transcript with a hole in it.
     */
    private fun emitAgent(item: AgentFeed) {
        if (owesBreak) {
            if (!_agentEvents.tryEmit(AgentFeed.Broken)) {
                agentFramesDropped++
                return
            }
            owesBreak = false
        }
        if (!_agentEvents.tryEmit(item)) {
            owesBreak = true
            agentFramesDropped++
        }
    }

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

    /** The Mac refused this phone's token on the stream (401). Cleared by a new [start]. */
    var unauthorized by mutableStateOf(false)
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

    /** The stream this feed was told to hold, while the app itself has let go of it. */
    private var paused: ControlTransport? = null

    /**
     * The app is leaving the screen. A stream in the background keeps a radio awake to tell
     * nobody anything — the watcher, when there is one, holds its own — so it is closed, and
     * [resume] opens it again. Only this feed's own pause is resumed: a feed started fresh
     * afterwards (a new Mac, a new screen) is not opened twice.
     */
    fun pause() {
        val running = job ?: return
        running.cancel()
        job = null
        paused = current
        isLive = false
        retryAt = null
    }

    /** Back on screen: the stream opens again if [pause] closed it. True when it did. */
    fun resume(): Boolean {
        val transport = paused ?: return false
        paused = null
        start(transport)
        return true
    }

    /** What [start] was last given. */
    private var current: ControlTransport? = null

    fun start(transport: ControlTransport?) {
        stop()
        paused = null
        unauthorized = false
        current = transport
        // A new connection does not follow on from the old one: whatever the sessions heard
        // before this, the frames after it are a new run.
        emitAgent(AgentFeed.Broken)
        if (transport == null) {
            mustPoll = true
            return
        }
        mustPoll = false
        job = viewModelScope.launch {
            try {
                transport.events().collect { event ->
                    if (event is ServerEvent.Disconnected) {
                        // The sessions hear about the break in order with their frames:
                        // whatever arrives after it is not known to follow on.
                        emitAgent(AgentFeed.Broken)
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
                            // One vocabulary for "over", shared with the queue screen:
                            // two lists of the Mac's status words would drift apart,
                            // and a render would be finished in one place and running
                            // in the other.
                            val done = dev.siliconoptimizer.buddy.media.JobState
                                .of(event.progress.status).isTerminal
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
                        is ServerEvent.Agent -> emitAgent(AgentFeed.Frame(event.event))
                        is ServerEvent.Resync -> {
                            emitAgent(AgentFeed.Broken)
                            resyncs++
                        }
                        is ServerEvent.Beat -> Unit
                        // Handled above, before the stream is called live.
                        is ServerEvent.Disconnected -> Unit
                    }
                }
                isLive = false
            } catch (error: TransportError) {
                isLive = false
                if (error is TransportError.Unauthorized) {
                    // The Mac no longer knows this phone. Asking instead of listening would
                    // only be refused the same way, every few seconds: nothing polls.
                    unauthorized = true
                    mustPoll = false
                } else {
                    // A Mac without `/events`: the screens have to ask rather than wait.
                    mustPoll = true
                }
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

/**
 * What the agent sessions are told, in the order the stream said it: a frame, or that the
 * run of frames broke — a dropped connection, or frames the Mac had to drop for this phone.
 */
sealed interface AgentFeed {
    data class Frame(val event: dev.siliconoptimizer.buddy.transport.AgentEvent) : AgentFeed

    data object Broken : AgentFeed
}
