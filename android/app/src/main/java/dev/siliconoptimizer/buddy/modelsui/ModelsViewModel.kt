package dev.siliconoptimizer.buddy.modelsui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.transport.CatalogModel
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.InstalledModel
import dev.siliconoptimizer.buddy.transport.LoadRequest
import dev.siliconoptimizer.buddy.transport.Status
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
     * What went wrong, said once: a heading, the one line to read first, and what Retry
     * would do when retrying could help.
     */
    data class Problem(
        val title: String,
        val message: String,
        val retry: Operation? = null,
    )

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
     * Everything this list asks one Mac, so a re-pair can call all of it off at once: a
     * load on its way, an install being polled, a refresh on its way back. None of it may
     * land on the next Mac's screen.
     */
    private var mac = newScope()
    private var poller: Job? = null

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
        // An installed id carries its quantization ("model@Q4_K_M"); the status may
        // report either spelling.
        return loaded == id || loaded.startsWith("$id@") || id.startsWith("$loaded@")
    }

    private fun nameOf(id: String): String =
        installed.firstOrNull { it.id == id }?.name
            ?: catalog.firstOrNull { it.id == id }?.name
            ?: id

    // MARK: - Operations

    fun load(modelID: String, quantization: String? = null, transport: ControlTransport?) {
        if (transport == null) return
        problem = null
        mac.launch {
            job = ModelJob(modelID, "load", "Loading…")
            try {
                status = transport.load(LoadRequest(modelID, quantization))
                job = null
                refresh(transport)
            } catch (error: TransportError) {
                job = null
                problem = Problem(
                    "Couldn't load ${nameOf(modelID)}", error.message.orEmpty(),
                    retry = Operation.Load(modelID, quantization),
                )
            }
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
}
