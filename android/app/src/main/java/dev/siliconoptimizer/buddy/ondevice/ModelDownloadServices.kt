package dev.siliconoptimizer.buddy.ondevice

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.siliconoptimizer.buddy.MainActivity
import dev.siliconoptimizer.buddy.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Android 14 and later: a user-initiated data transfer job per download.
 *
 * The system holds it to the network the owner allowed — Wi-Fi unless they said otherwise —
 * stopping it when that network goes and starting it again when it comes back; each start
 * picks up from the bytes already on the phone. It shows its own notification with the
 * progress, as this kind of job must.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class ModelDownloadJobService : JobService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = params.extras.getString(ModelDownloads.EXTRA_MODEL) ?: return false
        val label = params.extras.getString(ModelDownloads.EXTRA_LABEL) ?: id
        val notifier = DownloadNotifier(this)
        // A user-initiated job must put up its notification at once.
        setNotification(
            params, notifier.progressID(id),
            notifier.progress(id, DownloadState.Queued(label)),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        running[params.jobId] = scope.launch {
            var lastDrawn = 0L
            val result = DownloadRunner(applicationContext).run(id, label) { state ->
                ModelDownloads.report(id, state)
                val now = SystemClock.elapsedRealtime()
                // Redrawn at most twice a second: a notification per 4 MB would be a storm.
                if (now - lastDrawn > 500 || !state.isActive) {
                    lastDrawn = now
                    runCatching {
                        setNotification(
                            params, notifier.progressID(id), notifier.progress(id, state),
                            JOB_END_NOTIFICATION_POLICY_REMOVE,
                        )
                        if (state is DownloadState.Copying) {
                            updateTransferredNetworkBytes(params, state.received, 0)
                        }
                    }
                }
            }
            running.remove(params.jobId)
            notifier.finished(id, ModelDownloads.states.value[id])
            jobFinished(params, result == DownloadRunner.Result.Retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        val id = params.extras.getString(ModelDownloads.EXTRA_MODEL) ?: return false
        val label = params.extras.getString(ModelDownloads.EXTRA_LABEL) ?: id
        val cancelledHere = params.stopReason == JobParameters.STOP_REASON_CANCELLED_BY_APP ||
            params.stopReason == JobParameters.STOP_REASON_USER
        if (cancelledHere) {
            ModelDownloads.forget(id)
            return false
        }
        // The network the owner allowed went away, or the system needed the job to pause:
        // it comes back by itself, from where it stopped.
        ModelDownloads.report(
            id,
            if (params.stopReason == JobParameters.STOP_REASON_CONSTRAINT_CONNECTIVITY) {
                DownloadState.WaitingForWifi(label)
            } else {
                DownloadState.Queued(label)
            },
        )
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/**
 * Android 10 to 13: a `dataSync` foreground service, keeping to Wi-Fi itself.
 *
 * There is no user-initiated job to ask for before Android 14, and a download the owner is
 * waiting for should not wait on a scheduler. It watches the default network: off Wi-Fi
 * without the owner's say-so, the transfer stops where it is and resumes when Wi-Fi returns.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(ModelDownloads.EXTRA_MODEL)
        if (intent?.action == ACTION_CANCEL) {
            id?.let { downloads.remove(it)?.cancel() }
            ModelDownloads.forget(id ?: "")
            stopIfIdle()
            return START_NOT_STICKY
        }
        if (id == null) {
            stopIfIdle()
            return START_NOT_STICKY
        }
        val label = intent.getStringExtra(ModelDownloads.EXTRA_LABEL) ?: id
        val mobile = intent.getBooleanExtra(ModelDownloads.EXTRA_MOBILE, false)
        val notifier = DownloadNotifier(this)
        val first = notifier.progress(id, DownloadState.Queued(label))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notifier.progressID(id), first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(notifier.progressID(id), first)
        }
        if (downloads.containsKey(id)) return START_NOT_STICKY
        downloads[id] = scope.launch {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val allowed = callbackFlow {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        // The change is the news; what to make of it is read whole, because
                        // a Tailscale tunnel's own capabilities may say nothing about the
                        // Wi-Fi underneath it.
                        trySend(DownloadNetwork.allows(DownloadNetwork.current(this@ModelDownloadService), mobile))
                    }

                    override fun onLost(network: Network) {
                        trySend(false)
                    }
                }
                trySend(DownloadNetwork.allows(DownloadNetwork.current(this@ModelDownloadService), mobile))
                connectivity.registerDefaultNetworkCallback(callback)
                awaitClose { connectivity.unregisterNetworkCallback(callback) }
            }.distinctUntilChanged()

            var result = DownloadRunner.Result.Retry
            var failures = 0
            while (result == DownloadRunner.Result.Retry && failures < 5) {
                if (!allowed.first()) {
                    report(notifier, id, DownloadState.WaitingForWifi(label))
                    allowed.first { it }
                }
                val transfer = launch {
                    result = DownloadRunner(applicationContext).run(id, label) { report(notifier, id, it) }
                }
                val watcher = launch {
                    allowed.first { !it }
                    transfer.cancelAndJoin()
                    result = DownloadRunner.Result.Retry
                    report(notifier, id, DownloadState.WaitingForWifi(label))
                }
                transfer.join()
                watcher.cancel()
                if (result == DownloadRunner.Result.Retry && transfer.isCancelled.not()) failures++
            }
            // Android 10 to 13 have no job to hand this back to, so giving up is the end of
            // it — and the owner is told, rather than left with a notification that simply
            // stops. What arrived is still on the phone, so "try again" carries on from it.
            if (result == DownloadRunner.Result.Retry) {
                report(
                    notifier, id,
                    DownloadState.Failed(
                        label,
                        "The download stopped after several tries. What arrived is kept — " +
                            "try again in Settings while your Mac is reachable.",
                        transient = true,
                    ),
                )
            }
            notifier.finished(id, ModelDownloads.states.value[id])
            downloads.remove(id)
            stopIfIdle()
        }
        return START_NOT_STICKY
    }

    private var lastDrawn = 0L

    private fun report(notifier: DownloadNotifier, id: String, state: DownloadState) {
        ModelDownloads.report(id, state)
        val now = SystemClock.elapsedRealtime()
        if (now - lastDrawn > 500 || !state.isActive) {
            lastDrawn = now
            notifier.showProgress(id, state)
        }
    }

    private fun stopIfIdle() {
        if (downloads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "dev.siliconoptimizer.buddy.ondevice.CANCEL_DOWNLOAD"

        fun start(context: Context, id: String, label: String, useMobileData: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ModelDownloadService::class.java)
                    .putExtra(ModelDownloads.EXTRA_MODEL, id)
                    .putExtra(ModelDownloads.EXTRA_LABEL, label)
                    .putExtra(ModelDownloads.EXTRA_MOBILE, useMobileData),
            )
        }
    }
}

/** Cancel, from the progress notification. Not exported: only this app's own intent reaches it. */
class ModelDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val id = intent.getStringExtra(ModelDownloads.EXTRA_MODEL) ?: return
        ModelDownloads.cancel(context, id)
    }

    companion object {
        const val ACTION_CANCEL = "dev.siliconoptimizer.buddy.ondevice.CANCEL"
    }
}

/** The download's notifications: the ongoing one with a bar, and the one that says how it ended. */
class DownloadNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    val isAllowed: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // Their own ranges, clear of the renders' (4101 + 16 bits) and the agents'.
    fun progressID(modelID: String): Int = PROGRESS_BASE + (modelID.hashCode() and 0xff)
    private fun doneID(modelID: String): Int = DONE_BASE + (modelID.hashCode() and 0xff)

    fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, "Getting the phone's model", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a model for this phone comes over from your Mac." },
        )
        system.createNotificationChannel(
            NotificationChannel(DONE_CHANNEL, "Phone model ready", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When the model for this phone is ready, or could not be fetched." },
        )
    }

    fun progress(modelID: String, state: DownloadState): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Getting ${state.label} for this phone")
            .setContentText(state.line)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(R.drawable.ic_launcher_foreground, "Cancel", cancelIntent(modelID))
        val fraction = state.progress
        if (fraction != null) builder.setProgress(1000, (fraction.coerceIn(0.0, 1.0) * 1000).toInt(), false)
        else builder.setProgress(0, 0, state !is DownloadState.WaitingForWifi)
        return builder.build()
    }

    fun showProgress(modelID: String, state: DownloadState) {
        if (!isAllowed) return
        runCatching { manager.notify(progressID(modelID), progress(modelID, state)) }
    }

    fun cancelProgress(modelID: String) {
        runCatching { manager.cancel(progressID(modelID)) }
    }

    /** How it ended, once. Nothing for a download that was cancelled or will carry on. */
    fun finished(modelID: String, state: DownloadState?) {
        cancelProgress(modelID)
        if (!isAllowed) return
        val (title, text) = when (state) {
            is DownloadState.Done -> "${state.label} is ready on this phone" to
                "When your Mac is out of reach, the chat can answer here instead. It will always ask first."
            is DownloadState.Failed -> if (state.transient) return else "Couldn't get ${state.label}" to state.message
            else -> return
        }
        ensureChannels()
        val notification = NotificationCompat.Builder(context, DONE_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { manager.notify(doneID(modelID), notification) }
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PHONE_MODELS),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(modelID: String): PendingIntent = PendingIntent.getBroadcast(
        context, modelID.hashCode(),
        Intent(context, ModelDownloadReceiver::class.java)
            .setAction(ModelDownloadReceiver.ACTION_CANCEL)
            .putExtra(ModelDownloads.EXTRA_MODEL, modelID),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val PROGRESS_CHANNEL = "phone-model-progress"
        const val DONE_CHANNEL = "phone-model-done"
        const val PROGRESS_BASE = 91_000
        const val DONE_BASE = 92_000
    }
}
