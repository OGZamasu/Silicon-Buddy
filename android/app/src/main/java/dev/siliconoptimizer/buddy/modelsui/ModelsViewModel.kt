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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    var error by mutableStateOf<String?>(null)
        private set
    var search by mutableStateOf("")
    var section by mutableStateOf(Section.Installed)

    private var poller: Job? = null

    fun refresh(transport: ControlTransport?) {
        if (transport == null) return
        viewModelScope.launch {
            isLoading = true
            val installedTask = async { runCatching { transport.installed() }.getOrNull() }
            val catalogTask = async { runCatching { transport.catalog() }.getOrNull() }
            val statusTask = async { runCatching { transport.status() }.getOrNull() }
            val newInstalled = installedTask.await()
            val newCatalog = catalogTask.await()
            val newStatus = statusTask.await()
            error = if (newInstalled == null && newCatalog == null) {
                "Couldn't read the model list."
            } else {
                null
            }
            newInstalled?.let { installed = it }
            newCatalog?.let { catalog = it }
            newStatus?.let { status = it }
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

    // MARK: - Operations

    fun load(modelID: String, quantization: String? = null, transport: ControlTransport?) {
        if (transport == null) return
        viewModelScope.launch {
            job = ModelJob(modelID, "load", "Loading…")
            try {
                status = transport.load(LoadRequest(modelID, quantization))
                refresh(transport)
            } catch (error: TransportError) {
                this@ModelsViewModel.error = error.message
            } finally {
                job = null
            }
        }
    }

    fun unload(transport: ControlTransport?) {
        if (transport == null) return
        viewModelScope.launch {
            job = ModelJob(status?.loadedModelID.orEmpty(), "unload", "Unloading…")
            try {
                transport.unload()
                refresh(transport)
            } catch (error: TransportError) {
                this@ModelsViewModel.error = error.message
            } finally {
                job = null
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
        poller?.cancel()
        poller = viewModelScope.launch {
            job = ModelJob(model.id, "install", "Asking the Mac…")
            try {
                val message = transport.install(LoadRequest(model.id, quant))
                job = ModelJob(model.id, "install", message)
            } catch (error: TransportError) {
                this@ModelsViewModel.error = error.message
                job = null
                return@launch
            }

            var settled = 0
            var lastSize = -1L
            for (attempt in 0 until 600) {
                delay(2000)
                val list = runCatching { transport.installed() }.getOrNull() ?: emptyList()
                val state = runCatching { transport.status() }.getOrNull()
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

    fun clearError() {
        error = null
    }

    /** Throws away the last Mac's lists, so a re-pair never shows another machine's disk. */
    fun reset() {
        poller?.cancel()
        poller = null
        job = null
        installed = emptyList()
        catalog = emptyList()
        status = null
        error = null
        search = ""
    }
}
