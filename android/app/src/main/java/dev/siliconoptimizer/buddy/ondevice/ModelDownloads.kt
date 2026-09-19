package dev.siliconoptimizer.buddy.ondevice

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import android.os.storage.StorageManager
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.PhoneModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** A phone-model download as Settings and its notification show it. */
sealed interface DownloadState {
    val label: String

    data class Queued(override val label: String) : DownloadState

    /** The owner kept it to Wi-Fi, and the phone is not on Wi-Fi it can spend freely. */
    data class WaitingForWifi(override val label: String) : DownloadState
    data class OnMac(override val label: String, val stage: String?, val fraction: Double?) : DownloadState
    data class Copying(override val label: String, val received: Long, val total: Long) : DownloadState
    data class Verifying(override val label: String) : DownloadState
    data class Done(override val label: String) : DownloadState
    data class Failed(override val label: String, val message: String, val transient: Boolean) : DownloadState

    val isActive: Boolean get() = this !is Done && this !is Failed

    /** One line for the notification and the Settings row. */
    val line: String
        get() = when (this) {
            is Queued -> "Starting…"
            is WaitingForWifi -> "Waiting for Wi-Fi that isn't metered"
            is OnMac -> when (stage) {
                "checking" -> "Your Mac is checking it"
                "moving" -> "Your Mac is moving it to its model library"
                else -> "Your Mac is fetching it from Hugging Face"
            } + (fraction?.let { " · ${Math.round(it * 100)}%" } ?: "")
            is Copying -> "Copying from your Mac · ${Math.round(if (total > 0) received * 100.0 / total else 0.0)}% " +
                "of ${dev.siliconoptimizer.buddy.ui.Format.bytes(total)}"
            is Verifying -> "Checking it on this phone"
            is Done -> "Ready on this phone"
            is Failed -> message
        }

    /** 0–1 for a bar, or null for a stage that has no measure. */
    val progress: Double?
        get() = when (this) {
            is OnMac -> fraction
            is Copying -> if (total > 0) received.toDouble() / total else null
            is Done -> 1.0
            else -> null
        }
}

/** What the phone is on right now, as much of it as the rule needs. */
data class NetworkNow(
    val transports: Set<Int>,
    /** Android's own `NET_CAPABILITY_NOT_METERED`: a hotspot and a capped SSID are metered. */
    val unmetered: Boolean,
    /**
     * The networks underneath, when the one in use is a VPN and Android did not copy their
     * transports onto the tunnel.
     */
    val underneath: List<NetworkNow> = emptyList(),
) {
    val isVpn: Boolean get() = NetworkCapabilities.TRANSPORT_VPN in transports
    val isWired: Boolean
        get() = NetworkCapabilities.TRANSPORT_WIFI in transports ||
            NetworkCapabilities.TRANSPORT_ETHERNET in transports
}

/**
 * Which networks may carry a model.
 *
 * "Wi-Fi only" is what the owner asked for, and what they meant by it is "not out of my data
 * allowance" — so the test is Android's own `NOT_METERED`, not the Wi-Fi transport. A phone
 * tethered to another phone, or on a hotel network Android has marked metered, is Wi-Fi and
 * is exactly the case this refuses.
 *
 * The tailnet complicates it: reaching the Mac at all means going through Tailscale, and the
 * network in use is then a VPN. Android normally copies the underlying transports and the
 * metered state onto the tunnel, which is all this needs; where it does not, the networks
 * underneath are asked instead. Nothing here treats "is a VPN" as a reason to refuse.
 */
object DownloadNetwork {

    fun allows(now: NetworkNow, useMobileData: Boolean): Boolean {
        if (useMobileData) return true
        if (now.isWired) return now.unmetered
        // A tunnel that says nothing about what it rides on: look underneath it.
        if (now.isVpn) return now.underneath.any { it.isWired && it.unmetered }
        return false
    }

    /**
     * The job's network constraint. `NOT_VPN` is removed because Tailscale *is* a VPN: a
     * request left with the default would never be satisfied while the phone is on the
     * tailnet, which is the only way it ever reaches the Mac. `NOT_METERED` is the same
     * rule as [allows], enforced by the system while the job waits.
     */
    fun request(useMobileData: Boolean): NetworkRequest = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        .apply {
            if (!useMobileData) addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        .build()

    /** The phone's default network right now, and what it rides on. */
    fun current(context: Context): NetworkNow {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkNow(emptySet(), false)
        val active = connectivity.activeNetwork ?: return NetworkNow(emptySet(), false)
        val capabilities = connectivity.getNetworkCapabilities(active)
            ?: return NetworkNow(emptySet(), false)
        val now = read(capabilities)
        if (!now.isVpn || now.isWired) return now
        // Under the tunnel: every other connected network this app can see.
        val underneath = connectivity.allNetworks
            .filter { it != active }
            .mapNotNull { connectivity.getNetworkCapabilities(it) }
            .filterNot { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
            .map { read(it) }
        return now.copy(underneath = underneath)
    }

    fun read(capabilities: NetworkCapabilities): NetworkNow = NetworkNow(
        transports = transportsOf(capabilities),
        unmetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    fun transportsOf(capabilities: NetworkCapabilities): Set<Int> = listOf(
        NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR,
        NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
        NetworkCapabilities.TRANSPORT_BLUETOOTH,
    ).filter { capabilities.hasTransport(it) }.toSet()
}

/** Room on the phone, from `StorageManager`, which may clear other apps' cache to make it. */
class AndroidSpace(context: Context) : SpaceReserver {
    private val storage = context.getSystemService(StorageManager::class.java)

    override fun allocatableBytes(directory: File): Long = try {
        directory.mkdirs()
        storage.getAllocatableBytes(storage.getUuidForPath(directory))
    } catch (error: IOException) {
        directory.usableSpace
    }

    override fun reserve(file: File, totalBytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { storage.allocateBytes(it.fd, totalBytes) }
    }
}

/**
 * Downloads of phone models, whoever is running them.
 *
 * Android 14 and later run each one as a user-initiated data transfer job — the kind the
 * system expects a multi-gigabyte transfer the owner asked for to be — with a Wi-Fi network
 * constraint unless mobile data was agreed to. Android 10 to 13 have no such job, so a
 * `dataSync` foreground service stands in, keeping to Wi-Fi itself. Both run the same
 * [ModelDownloader] and report here.
 */
object ModelDownloads {

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states.asStateFlow()

    internal fun report(id: String, state: DownloadState) {
        _states.value = _states.value + (id to state)
    }

    fun forget(id: String) {
        _states.value = _states.value - id
    }

    const val EXTRA_MODEL = "dev.siliconoptimizer.buddy.ondevice.MODEL"
    const val EXTRA_LABEL = "dev.siliconoptimizer.buddy.ondevice.LABEL"
    const val EXTRA_MOBILE = "dev.siliconoptimizer.buddy.ondevice.MOBILE_DATA"
    const val EXTRA_BYTES = "dev.siliconoptimizer.buddy.ondevice.BYTES"

    private const val JOB_BASE = 0x5b00_0000

    fun jobID(modelID: String): Int = JOB_BASE + (modelID.hashCode() and 0xffff)

    /**
     * Starts a download the owner has agreed to — from a button, with the app on screen,
     * which is when Android allows a user-initiated job to be scheduled at all.
     */
    fun start(context: Context, model: PhoneModel, useMobileData: Boolean) {
        val remaining = model.sizeBytes - ModelStore(context).receivedBytes(model.sha256)
        report(model.id, if (!DownloadNetwork.allows(DownloadNetwork.current(context), useMobileData)) {
            DownloadState.WaitingForWifi(model.label)
        } else {
            DownloadState.Queued(model.label)
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val extras = PersistableBundle().apply {
                putString(EXTRA_MODEL, model.id)
                putString(EXTRA_LABEL, model.label)
                putBoolean(EXTRA_MOBILE, useMobileData)
                putLong(EXTRA_BYTES, remaining)
            }
            val job = JobInfo.Builder(jobID(model.id), ComponentName(context, ModelDownloadJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(DownloadNetwork.request(useMobileData))
                .setEstimatedNetworkBytes(remaining.coerceAtLeast(0), 0)
                .setExtras(extras)
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        } else {
            ModelDownloadService.start(context, model.id, model.label, useMobileData)
        }
    }

    /** Stops a download. What arrived stays, so starting again carries on from it. */
    fun cancel(context: Context, modelID: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(jobID(modelID))
        } else {
            context.startService(
                Intent(context, ModelDownloadService::class.java)
                    .setAction(ModelDownloadService.ACTION_CANCEL)
                    .putExtra(EXTRA_MODEL, modelID),
            )
        }
        forget(modelID)
        DownloadNotifier(context).cancelProgress(modelID)
    }

    /** Whether a job for this model is scheduled or running, for a Settings row after a restart. */
    fun isScheduled(context: Context, modelID: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.getSystemService(JobScheduler::class.java).getPendingJob(jobID(modelID)) != null
}

/** One download, start to finish: the part the job and the service share. */
class DownloadRunner(private val context: Context) {

    enum class Result { Done, Retry, Failed }

    suspend fun run(modelID: String, label: String, onState: (DownloadState) -> Unit): Result {
        val config = TokenStore(context).load()
        if (config == null) {
            onState(DownloadState.Failed(label, "No Mac is paired, and the phone's model only comes from your Mac.", false))
            return Result.Failed
        }
        val downloader = ModelDownloader(ControlClient(config), ModelStore(context), AndroidSpace(context))
        return try {
            val entry = downloader.download(modelID) { step ->
                onState(
                    when (step) {
                        is DownloadStep.OnMac -> DownloadState.OnMac(label, step.stage, step.fraction)
                        is DownloadStep.Copying -> DownloadState.Copying(label, step.received, step.total)
                        DownloadStep.Verifying -> DownloadState.Verifying(label)
                    },
                )
            }
            val settings = OnDeviceSettings(context)
            if (settings.preferredModelID == null || ModelStore(context).installed(settings.preferredModelID!!) == null) {
                settings.preferredModelID = entry.id
            }
            onState(DownloadState.Done(entry.label))
            Result.Done
        } catch (failure: DownloadFailure) {
            onState(DownloadState.Failed(label, failure.message ?: "The download stopped.", failure.transient))
            if (failure.transient) Result.Retry else Result.Failed
        }
    }
}
