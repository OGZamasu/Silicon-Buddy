package dev.siliconoptimizer.buddy.modelsui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadFailure
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The model list: what is on the Mac's disk, what the catalog offers, and the cloud. */
class ModelsViewModel : ViewModel() {

    enum class Section(val label: String) {
        Installed("Installed"), Catalog("Catalog"), Cloud("Cloud")
    }

    /** What a long-running model operation is doing, so the row can say so. */
    data class ModelJob(
        val modelID: String,
        val kind: String,
        val message: String,
        /** 0–1 when the Mac tells us enough to know; null while it is indeterminate. */
        val fraction: Double? = null,
    )

    /** Something this screen asked the Mac for, kept so a failure can offer to ask again. */
    sealed interface Operation {
        data object Refresh : Operation
        data class Load(val modelID: String, val quantization: String?) : Operation
        data object Unload : Operation
        data class Install(val model: CatalogModel, val quantization: String?) : Operation
    }

    /**
     * What went wrong, said once: a heading, the one line to read first, the Mac's log
     * behind a tap when there is one, and what Retry would do when retrying could help.
     */
    data class Problem(
        val title: String,
        val message: String,
        val detail: String? = null,
        val retry: Operation? = null,
        /** False for an ending nobody got wrong — another load taking the Mac, say. */
        val isFault: Boolean = true,
        /** The Mac's own account this was built from, when it was. */
        val failure: LoadFailure? = null,
    )

    /** How a load this screen started has ended, read from one status. */
    enum class LoadOutcome {
        Pending, Loaded, Failed, Replaced, Cancelled;

        companion object {
            fun of(status: Status, modelID: String): LoadOutcome {
                val loaded = status.loadedModelID
                val failure = status.failure
                return when {
                    loaded != null && sameModel(loaded, modelID) -> Loaded
                    failure != null -> when (failure.kind) {
                        LoadFailure.Reason.Replaced -> Replaced
                        LoadFailure.Reason.Cancelled -> Cancelled
                        else -> Failed
                    }
                    // Nothing is resident while a load runs, so another model resident
                    // now is one somebody asked for instead.
                    loaded != null -> Replaced
                    else -> Pending
                }
            }

            /** An installed id carries its quantization ("model@Q4_K_M"); a status may use either. */
            fun sameModel(a: String, b: String): Boolean =
                a == b || a.startsWith("$b@") || b.startsWith("$a@")
        }
    }

    var installed by mutableStateOf<List<InstalledModel>>(emptyList())
        private set
    var catalog by mutableStateOf<List<CatalogModel>>(emptyList())
        private set
    var status by mutableStateOf<Status?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var job by mutableStateOf<ModelJob?>(null)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set

    /** The line a person reads first, when something went wrong. */
    val error: String? get() = problem?.message

    /**
     * True when the last read of the Mac's disk failed. An empty list then says nothing
     * about the disk, and must not be drawn as an empty library.
     */
    var installedFailed by mutableStateOf(false)
        private set

    /** The same for the catalog. */
    var catalogFailed by mutableStateOf(false)
        private set

    var search by mutableStateOf("")
    var section by mutableStateOf(Section.Installed)

    /**
     * Set from the event feed. While it is live a load is followed by the Mac's own status
     * frames, with a slow poll behind them in case one is missed; without it, by polling.
     */
    var eventsLive = false

    /**
     * Everything this list asks one Mac, so a re-pair can call all of it off at once: a
     * load being followed, an install being polled, a refresh on its way back. None of it
     * may land on the next Mac's screen.
     */
    private var mac = newScope()
    private var poller: Job? = null
    private var loader: Job? = null

    /** Status frames pushed while a load is being followed; only the newest matters. */
    private val frames = Channel<Status>(Channel.CONFLATED)

    private fun newScope() =
        CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))

    /**
     * Reads the three lists. A list that fails to arrive keeps what was there — a refresh
     * that fails must not empty the screen — and says so, with a retry.
     *
     * Only a refresh's own complaint is replaced or cleared here: this also runs after
     * every operation, and must not wipe the reason that operation failed.
     */
    fun refresh(transport: ControlTransport?) {
        if (transport == null) return
        mac.launch {
            isLoading = true
            val installedTask = async { attempt { transport.installed() } }
            val catalogTask = async { attempt { transport.catalog() } }
            val statusTask = async { attempt { transport.status() } }
            val newInstalled = installedTask.await()
            val newCatalog = catalogTask.await()
            val newStatus = statusTask.await()
            newInstalled.getOrNull()?.let { installed = it }
            newCatalog.getOrNull()?.let { catalog = it }
            newStatus.getOrNull()?.let { status = it }
            installedFailed = newInstalled.isFailure
            catalogFailed = newCatalog.isFailure

            val cause = newInstalled.exceptionOrNull() ?: newCatalog.exceptionOrNull()
            val ours = problem?.retry == Operation.Refresh
            if (cause != null) {
                if (problem == null || ours) {
                    problem = Problem(
                        title = when {
                            newInstalled.isFailure && newCatalog.isFailure -> "Couldn't read the model list"
                            newInstalled.isFailure -> "Couldn't read the models on the Mac"
                            else -> "Couldn't read the catalog"
                        },
                        message = cause.message ?: "The Mac didn't answer.",
                        retry = Operation.Refresh,
                    )
                }
            } else if (ours) {
                problem = null
            }
            isLoading = false
        }
    }

    /** A status frame from the event feed: the list shows it, and a load being followed hears it. */
    fun statusChanged(pushed: Status) {
        status = pushed
        frames.trySend(pushed)
    }

    // MARK: - Filtering

    val filteredInstalled: List<InstalledModel>
        get() = if (search.isBlank()) installed else installed.filter { entry ->
            val needle = search.lowercase()
            entry.name.lowercase().contains(needle) ||
                entry.id.lowercase().contains(needle) ||
                entry.quantization.lowercase().contains(needle)
        }

    val filteredCatalog: List<CatalogModel>
        get() = catalog.filterNot { it.isCloud }
            .filter { search.isBlank() || it.matches(search) }
            .sortedWith(
                compareByDescending<CatalogModel> { it.featured == true }
                    .thenByDescending { it.rating },
            )

    val filteredCloud: List<CatalogModel>
        get() = catalog.filter { it.isCloud }
            .filter { search.isBlank() || it.matches(search) }

    fun isLoaded(id: String): Boolean {
        val loaded = status?.loadedModelID ?: return false
        return LoadOutcome.sameModel(loaded, id)
    }

    /**
     * The Mac's last failed load, when nothing on screen is already saying so. A device
     * paired for chat is sent it without the log, and is shown it that way.
     */
    val standingFailure: LoadFailure?
        get() = status?.failure?.takeIf { job == null && problem?.failure != it }

    private fun nameOf(id: String): String =
        installed.firstOrNull { it.id == id }?.name
            ?: catalog.firstOrNull { it.id == id }?.name
            ?: id

    // MARK: - Operations

    /**
     * Loads a model and stays with it until it has an ending.
     *
     * `POST /load` answers after 25 seconds whether or not the load is done: a slow one
     * comes back as the live, still-loading status and carries on on the Mac. So the
     * answer is only the first reading. The load is followed through the status frames the
     * event feed pushes, polled for when there is no feed or a frame seems to be missing,
     * until the model is resident, the Mac says how it failed, another load took its
     * place — or the Mac's own ten-minute limit has passed with nothing said.
     */
    fun load(modelID: String, quantization: String? = null, transport: ControlTransport?) {
        if (transport == null) return
        problem = null
        loader?.cancel()
        loader = mac.launch {
            val name = nameOf(modelID)
            val retry = Operation.Load(modelID, quantization)
            job = ModelJob(modelID, "load", "Loading…")
            val answer = try {
                transport.load(LoadRequest(modelID, quantization))
            } catch (error: TransportError) {
                job = null
                val failed = Problem("Couldn't load $name", error.message.orEmpty(), retry = retry)
                problem = failed
                // A load the Mac tried and lost inside its patience is a 400 whose sentence
                // ends with the status line, and /status has the log beside that line. Said
                // once, as the line itself: the heading already says the load failed, and
                // the failure it names is then not said again in the list.
                attempt { transport.status() }.getOrNull()?.let { after ->
                    status = after
                    val failure = after.failure
                    if (failure != null && describes(error.message, after.state) && problem == failed) {
                        problem = failed.copy(message = after.state, detail = failure.detail, failure = failure)
                    }
                }
                return@launch
            }
            // Whatever the feed pushed while the request was out is older than its answer —
            // but may have been the load ending just after the Mac stopped waiting. The Mac
            // pushes a status only when it changes, so nothing would say it again: when a
            // frame was dropped, the Mac is asked straight away rather than after a wait.
            var dropped = false
            while (frames.tryReceive().isSuccess) dropped = true
            status = answer

            var latest = answer
            val outcome = withTimeoutOrNull(FOLLOW_LIMIT_MS) {
                var outcome = LoadOutcome.of(latest, modelID)
                var askNow = dropped
                while (outcome == LoadOutcome.Pending) {
                    job = ModelJob(modelID, "load", latest.state.ifBlank { "Loading…" })
                    val every = if (eventsLive) POLL_WITH_EVENTS_MS else POLL_WITHOUT_EVENTS_MS
                    val pushed = if (askNow) null else withTimeoutOrNull(every) { frames.receive() }
                    askNow = false
                    val next = pushed
                        ?: attempt { transport.status() }.getOrNull()?.also { status = it }
                        ?: continue
                    latest = next
                    outcome = LoadOutcome.of(next, modelID)
                }
                outcome
            }
            job = null
            val failure = latest.failure
            problem = when (outcome) {
                LoadOutcome.Loaded -> null
                LoadOutcome.Failed -> Problem(
                    "Couldn't load $name", latest.state, failure?.detail, retry, failure = failure,
                )
                LoadOutcome.Replaced -> Problem(
                    "$name wasn't loaded",
                    if (failure != null) latest.state
                    else "The Mac loaded ${latest.loadedModelName ?: latest.loadedModelID} instead.",
                    failure?.detail, isFault = false, failure = failure,
                )
                LoadOutcome.Cancelled -> Problem(
                    "$name wasn't loaded", latest.state, failure?.detail,
                    isFault = false, failure = failure,
                )
                LoadOutcome.Pending, null -> Problem(
                    "Still loading $name",
                    "The Mac hasn't said how this load ended. Refresh to ask it again.",
                    retry = Operation.Refresh, isFault = false,
                )
            }
            // Not after running out of time: the status has just been read, and a refresh
            // that succeeded would clear the one thing there is to say.
            if (outcome != null) refresh(transport)
        }
    }

    fun unload(transport: ControlTransport?) {
        if (transport == null) return
        problem = null
        val loaded = status?.loadedModelID.orEmpty()
        mac.launch {
            job = ModelJob(loaded, "unload", "Unloading…")
            try {
                transport.unload()
                job = null
                refresh(transport)
            } catch (error: TransportError) {
                job = null
                val name = status?.loadedModelName
                    ?: loaded.takeIf { it.isNotEmpty() }?.let(::nameOf)
                    ?: "the model"
                problem = Problem("Couldn't unload $name", error.message.orEmpty(), retry = Operation.Unload)
            }
        }
    }

    /**
     * Starts a download and follows it by polling.
     *
     * `POST /install` answers as soon as the download starts, so progress has to be
     * inferred: `/installed` shows the file growing. When `GET /events` lands in M0
     * this becomes the fallback.
     */
    fun install(model: CatalogModel, quantization: String? = null, transport: ControlTransport?) {
        if (transport == null) return
        val quant = quantization ?: model.recommendation?.quantization
        val expected = model.recommendation?.downloadBytes
        problem = null
        poller?.cancel()
        poller = mac.launch {
            job = ModelJob(model.id, "install", "Asking the Mac…")
            try {
                val message = transport.install(LoadRequest(model.id, quant))
                job = ModelJob(model.id, "install", message)
            } catch (error: TransportError) {
                job = null
                problem = Problem(
                    "Couldn't install ${model.name}", error.message.orEmpty(),
                    retry = Operation.Install(model, quantization),
                )
                return@launch
            }

            var settled = 0
            var lastSize = -1L
            for (round in 0 until 600) {
                delay(2000)
                val list = attempt { transport.installed() }.getOrNull() ?: emptyList()
                val state = attempt { transport.status() }.getOrNull()
                val entry = list.firstOrNull { it.id == model.id || it.id.startsWith("${model.id}@") }
                val size = entry?.sizeOnDiskBytes ?: 0
                installed = list
                state?.let { status = it }
                job = ModelJob(
                    model.id,
                    "install",
                    if (size > 0) {
                        "${Format.bytes(size)} of ${Format.bytes(expected)} downloaded"
                    } else {
                        state?.state ?: "Downloading…"
                    },
                    expected?.takeIf { it > 0 }?.let { minOf(1.0, size.toDouble() / it) },
                )
                if (entry != null && size == lastSize) {
                    settled++
                    if (settled >= 2) break // Two identical readings: it stopped growing.
                } else {
                    settled = 0
                }
                lastSize = size
            }
            job = null
            refresh(transport)
        }
    }

    /** Asks again for whatever failed. A retry is a new attempt, so the old words go first. */
    fun retry(transport: ControlTransport?) {
        val operation = problem?.retry ?: return
        problem = null
        when (operation) {
            Operation.Refresh -> refresh(transport)
            is Operation.Load -> load(operation.modelID, operation.quantization, transport)
            Operation.Unload -> unload(transport)
            is Operation.Install -> install(operation.model, operation.quantization, transport)
        }
    }

    fun clearError() {
        problem = null
    }

    /**
     * Throws away the last Mac's lists, and stops everything still asking it, so a re-pair
     * never shows another machine's disk — or its answer, arriving late.
     */
    fun reset() {
        mac.cancel()
        mac = newScope()
        poller = null
        loader = null
        while (frames.tryReceive().isSuccess) Unit
        job = null
        installed = emptyList()
        catalog = emptyList()
        status = null
        problem = null
        installedFailed = false
        catalogFailed = false
        isLoading = false
        search = ""
    }

    /** A call to the Mac, with the failures a person can be told about caught. */
    private suspend fun <T> attempt(call: suspend () -> T): Result<T> = try {
        Result.success(call())
    } catch (error: TransportError) {
        Result.failure(error)
    }

    companion object {
        /**
         * Whether an error the Mac answered a load with is about the failure its status now
         * shows. A load that fails before the Mac stops waiting is answered 400 "The model
         * failed to load: <the status line>", so the line is matched at the end.
         */
        fun describes(error: String?, state: String): Boolean =
            error != null && state.isNotBlank() && (error == state || error.endsWith(": $state"))

        /** Without an event feed, how often a load is asked about. */
        const val POLL_WITHOUT_EVENTS_MS = 2_000L

        /** With one, how long to wait for a frame before asking anyway. */
        const val POLL_WITH_EVENTS_MS = 10_000L

        /**
         * How long a load is followed. The Mac gives a runtime ten minutes to answer and
         * then says it timed out; a little over that, and the Mac has had its say.
         */
        const val FOLLOW_LIMIT_MS = 11 * 60_000L
    }
}
