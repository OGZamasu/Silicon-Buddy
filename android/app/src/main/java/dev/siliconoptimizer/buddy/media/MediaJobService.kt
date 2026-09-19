package dev.siliconoptimizer.buddy.media

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ImageRequest
import dev.siliconoptimizer.buddy.transport.MeshRequest
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.VideoGenerateRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The work the Mac is doing for this phone, and what came back.
 *
 * A process-wide object rather than state on a screen, because the point of the
 * foreground service is that the answer survives the app being put away: the person
 * starts a render, switches to something else, and the result has to be somewhere when
 * they come back — or in a notification if they do not.
 */
object MediaJobCenter {

    @Serializable
    sealed interface Work {
        val kind: String
        val summary: String

        @Serializable
        data class Image(val request: ImageRequest, override val summary: String) : Work {
            override val kind: String get() = "image"
        }

        @Serializable
        data class Video(val request: VideoGenerateRequest, override val summary: String) : Work {
            override val kind: String get() = "video"
        }

        @Serializable
        data class Mesh(val request: MeshRequest, override val summary: String) : Work {
            override val kind: String get() = "mesh"
        }
    }

    /** What a finished piece of work left behind — always a path on the Mac. */
    data class Outcome(
        val kind: String,
        val headline: String,
        val path: String? = null,
        val detail: String? = null,
        val warning: String? = null,
        val failed: Boolean = false,
        val at: Long = System.currentTimeMillis(),
    )

    private val _running = MutableStateFlow<List<Work>>(emptyList())
    val running: StateFlow<List<Work>> = _running.asStateFlow()

    private val _outcomes = MutableStateFlow<List<Outcome>>(emptyList())
    val outcomes: StateFlow<List<Outcome>> = _outcomes.asStateFlow()

    /**
     * The newest fraction the event stream reported for a kind, so the ongoing
     * notification can show a bar instead of a barber's pole. Fed by the app's one
     * `/events` subscription while it is alive; absent when it is not, which is the
     * honest state rather than a guess.
     */
    private val _fractions = MutableStateFlow<Map<String, Double>>(emptyMap())
    val fractions: StateFlow<Map<String, Double>> = _fractions.asStateFlow()

    fun note(kind: String, fraction: Double?) {
        _fractions.value = _fractions.value.toMutableMap().apply {
            if (fraction == null) remove(kind) else put(kind, fraction)
        }
    }

    internal fun began(work: Work) {
        _running.value = _running.value + work
    }

    internal fun ended(work: Work, outcome: Outcome) {
        _running.value = _running.value.filterNot { it === work }
        _outcomes.value = (listOf(outcome) + _outcomes.value).take(20)
        note(work.kind, null)
    }

    fun forget() {
        _outcomes.value = emptyList()
        _fractions.value = emptyMap()
    }
}

/**
 * A render, kept alive while the app is not.
 *
 * `POST /image/generate`, `/video/generate` and `/mesh/generate` answer when the work is
 * done, which is minutes later. Run from a screen, that request dies the moment Android
 * decides the app is in the background — and the Mac finishes the render anyway, so the
 * phone has thrown away an answer it paid for. So the request runs here, behind an
 * ongoing notification, and the result lands in [MediaJobCenter] whether anybody is
 * watching or not.
 */
class MediaJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier by lazy { MediaNotifier(this) }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    private var outstanding = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payload = intent?.getStringExtra(EXTRA_WORK)
        val work = payload?.let { runCatching { json.decodeFromString<MediaJobCenter.Work>(it) }.getOrNull() }
        if (work == null) {
            if (outstanding == 0) stopSelf()
            return START_NOT_STICKY
        }

        outstanding++
        startInForeground(work)
        MediaJobCenter.began(work)

        scope.launch {
            val outcome = runWork(work)
            MediaJobCenter.ended(work, outcome)
            notifier.post(
                JobNotice(
                    id = "${work.kind}-${System.currentTimeMillis()}",
                    title = outcome.headline,
                    body = listOfNotNull(outcome.path, outcome.detail, outcome.warning)
                        .joinToString(" · ")
                        .ifBlank { "Finished on the Mac." },
                    isFailure = outcome.failed,
                ),
            )
            outstanding--
            if (outstanding <= 0) {
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runWork(work: MediaJobCenter.Work): MediaJobCenter.Outcome {
        val config = TokenStore(applicationContext).load()
            ?: return MediaJobCenter.Outcome(
                kind = work.kind,
                headline = "No Mac is paired",
                failed = true,
            )
        val client = ControlClient(config)
        return try {
            when (work) {
                is MediaJobCenter.Work.Image -> client.generateImage(work.request).let {
                    MediaJobCenter.Outcome(
                        kind = work.kind,
                        headline = "Image ready",
                        path = it.path,
                        detail = "${it.model} · ${String.format(Locale.US, "%.1f", it.elapsedSeconds)}s",
                        warning = it.warning,
                    )
                }
                is MediaJobCenter.Work.Video -> client.generateVideo(work.request).let {
                    MediaJobCenter.Outcome(
                        kind = work.kind,
                        headline = "Clip ready",
                        path = it.file,
                        detail = "${it.model} on ${it.node} · ${String.format(Locale.US, "%.0f", it.elapsedSeconds)}s",
                    )
                }
                is MediaJobCenter.Work.Mesh -> client.generateMesh(work.request).let {
                    MediaJobCenter.Outcome(
                        kind = work.kind,
                        headline = "Mesh ready",
                        path = it.glbPath ?: it.objPath,
                        detail = "${it.model} · ${String.format(Locale.US, "%.0f", it.elapsedSeconds)}s",
                        warning = it.warning,
                    )
                }
            }
        } catch (error: TransportError) {
            MediaJobCenter.Outcome(
                kind = work.kind,
                headline = "That ${JobNotifications.noun(work.kind)} failed",
                detail = error.message,
                failed = true,
            )
        }
    }

    private fun startInForeground(work: MediaJobCenter.Work) {
        notifier.ensureChannels()
        val notification = notifier.ongoing(
            JobNotifications.ongoingText(work.kind, work.summary),
            MediaJobCenter.fractions.value[work.kind],
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                JobNotifications.PROGRESS_NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(JobNotifications.PROGRESS_NOTIFICATION, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_WORK = "dev.siliconoptimizer.buddy.media.WORK"

        private val json = Json {
            ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true
        }

        /**
         * Starts the render. Foreground from the first moment, because a service that
         * waits for its own coroutine to call `startForeground` is a crash on Android
         * 12 and later.
         */
        fun start(context: Context, work: MediaJobCenter.Work) {
            val intent = Intent(context, MediaJobService::class.java)
                .putExtra(EXTRA_WORK, json.encodeToString(work))
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
