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
import dev.siliconoptimizer.buddy.transport.RenderBudget
import dev.siliconoptimizer.buddy.transport.TransportError
import dev.siliconoptimizer.buddy.transport.VideoGenerateRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
        /** This render's own id, so its notification is its own. */
        val id: String
        val kind: String
        val summary: String

        @Serializable
        data class Image(
            val request: ImageRequest,
            override val summary: String,
            override val id: String = UUID.randomUUID().toString(),
        ) : Work {
            override val kind: String get() = "image"
        }

        @Serializable
        data class Video(
            val request: VideoGenerateRequest,
            override val summary: String,
            override val id: String = UUID.randomUUID().toString(),
        ) : Work {
            override val kind: String get() = "video"
        }

        @Serializable
        data class Mesh(
            val request: MeshRequest,
            override val summary: String,
            override val id: String = UUID.randomUUID().toString(),
        ) : Work {
            override val kind: String get() = "mesh"
        }
    }

    /** What a finished piece of work left behind — always a path on the Mac. */
    data class Outcome(
        val kind: String,
        val headline: String,
        val path: String? = null,
        /** `GET /media/{id}`: what makes "save to Photos" possible at all. */
        val mediaID: String? = null,
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

    /**
     * Kinds this phone has just finished waiting on, and until when.
     *
     * The Mac's `job` event for a render often lands *after* the request it belongs to
     * has already answered — the stand-in publishes it a moment before returning, and a
     * real Mac has no reason to be tidier. Checking "is one running right now" therefore
     * misses by a second and the render is announced twice. The claim outlives the
     * request by long enough for the stream to catch up.
     */
    private val claims = mutableMapOf<String, Long>()
    private const val CLAIM_GRACE_MS = 60_000L

    /**
     * Whether this phone is waiting on a render of this kind, or just was.
     *
     * The Mac reports an image or a mesh on `/events` too, under the id `image` or
     * `mesh`. Without this a render started here would be announced twice: once by the
     * stream, once by the service that held the request.
     */
    @Synchronized
    fun isRendering(kind: String): Boolean {
        if (_running.value.any { it.kind == kind }) return true
        val until = claims[kind] ?: return false
        if (System.currentTimeMillis() > until) {
            claims.remove(kind)
            return false
        }
        return true
    }

    internal fun began(work: Work) {
        _running.value = _running.value + work
    }

    @Synchronized
    internal fun ended(work: Work, outcome: Outcome) {
        _running.value = _running.value.filterNot { it.id == work.id }
        _outcomes.value = (listOf(outcome) + _outcomes.value).take(20)
        claims[work.kind] = System.currentTimeMillis() + CLAIM_GRACE_MS
        note(work.kind, null)
    }

    @Synchronized
    fun forget() {
        _outcomes.value = emptyList()
        _fractions.value = emptyMap()
        claims.clear()
    }
}

/**
 * A render, kept alive while the app is not.
 *
 * `POST /image/generate`, `/video/generate` and `/mesh/generate` answer when the work is
 * done, which is minutes later. Run from a screen, that request dies the moment Android
 * decides the app is in the background — and the Mac finishes the render anyway, so the
 * phone has thrown away an answer it paid for. So the request runs here, behind an
 * ongoing notification with a way out of it, and the result lands in [MediaJobCenter]
 * whether anybody is watching or not.
 */
class MediaJobService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifier by lazy { MediaNotifier(this) }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    /** Counted from the main thread and from every render's own coroutine. */
    private val outstanding = AtomicInteger(0)
    private val running = ConcurrentHashMap<String, Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The bar in the ongoing notification is the same fraction the queue screen
        // draws, which arrives on the app's one event stream.
        scope.launch {
            MediaJobCenter.fractions.collect { fractions ->
                MediaJobCenter.running.value
                    .filter { running.containsKey(it.id) }
                    .forEach { work ->
                        notifier.showOngoing(
                            notificationID(work), work, fractions[work.kind], this@MediaJobService,
                        )
                    }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // Stops the waiting, not the render: the Mac has the request and will
            // finish it. Cancelling disconnects the socket, which is the only thing
            // that gets a blocked read to return.
            intent.getStringExtra(EXTRA_WORK_ID)?.let { running[it]?.cancel() }
            return START_NOT_STICKY
        }

        val payload = intent?.getStringExtra(EXTRA_WORK)
        val work = payload?.let {
            runCatching { json.decodeFromString<MediaJobCenter.Work>(it) }.getOrNull()
        }
        if (work == null) {
            if (outstanding.get() == 0) stopSelf()
            return START_NOT_STICKY
        }

        outstanding.incrementAndGet()
        startInForeground(work)
        MediaJobCenter.began(work)

        val job = scope.launch {
            val outcome = runWork(work)
            MediaJobCenter.ended(work, outcome)
            notifier.cancel(notificationID(work))
            notifier.post(
                JobNotice(
                    // This render's own id: it rings once, and a second render of the
                    // same kind does not replace its notification.
                    id = work.id,
                    title = outcome.headline,
                    body = listOfNotNull(outcome.detail, outcome.warning)
                        .joinToString(" · ")
                        .ifBlank { work.summary.ifBlank { "Finished on the Mac." } },
                    isFailure = outcome.failed,
                ),
            )
            running.remove(work.id)
            if (outstanding.decrementAndGet() <= 0) {
                stopForegroundCompat()
                stopSelf()
            }
        }
        running[work.id] = job
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
            // The Mac gives an accepted job twelve hours. A phone holding a socket for
            // that long is a phone with a stuck notification on it, so this waits the
            // rest of the Mac's own budget and then says where to look instead.
            withTimeoutOrNull(RenderBudget.TOTAL_SECONDS * 1000L) {
                when (work) {
                    is MediaJobCenter.Work.Image -> client.generateImage(work.request).let {
                        MediaJobCenter.Outcome(
                            kind = work.kind,
                            headline = "Your image is ready",
                            path = it.path,
                            mediaID = it.mediaID,
                            detail = "${it.model} · ${seconds(it.elapsedSeconds)}",
                            warning = it.warning,
                        )
                    }
                    is MediaJobCenter.Work.Video -> client.generateVideo(work.request).let {
                        MediaJobCenter.Outcome(
                            kind = work.kind,
                            headline = "Your clip is ready",
                            path = it.file,
                            mediaID = it.mediaID,
                            detail = "${it.model} on ${it.node} · ${seconds(it.elapsedSeconds)}",
                        )
                    }
                    is MediaJobCenter.Work.Mesh -> client.generateMesh(work.request).let {
                        MediaJobCenter.Outcome(
                            kind = work.kind,
                            headline = "Your mesh is ready",
                            path = it.glbPath ?: it.objPath,
                            mediaID = it.mediaID ?: it.objMediaID,
                            detail = "${it.model} · ${seconds(it.elapsedSeconds)}",
                            warning = it.warning,
                        )
                    }
                }
            } ?: MediaJobCenter.Outcome(
                kind = work.kind,
                headline = "Still rendering on the Mac",
                detail = "This phone stopped waiting after " +
                    "${RenderBudget.TOTAL_SECONDS / 60} minutes. The Mac kept going.",
                failed = true,
            )
        } catch (cancelled: CancellationException) {
            MediaJobCenter.Outcome(
                kind = work.kind,
                headline = "Stopped waiting",
                detail = "The Mac still has the request and may finish it.",
                failed = true,
            )
        } catch (error: TransportError) {
            MediaJobCenter.Outcome(
                kind = work.kind,
                headline = "That ${JobNotifications.noun(work.kind)} failed",
                detail = error.message,
                failed = true,
            )
        }
    }

    private fun seconds(value: Double): String = String.format(Locale.US, "%.0fs", value)

    /** One notification per render, so two of them do not replace each other. */
    private fun notificationID(work: MediaJobCenter.Work): Int =
        JobNotifications.PROGRESS_NOTIFICATION + (work.id.hashCode() and 0xffff)

    private fun startInForeground(work: MediaJobCenter.Work) {
        notifier.ensureChannels()
        val notification = notifier.ongoing(
            work, MediaJobCenter.fractions.value[work.kind], this,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationID(work),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationID(work), notification)
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
        const val EXTRA_WORK_ID = "dev.siliconoptimizer.buddy.media.WORK_ID"
        const val ACTION_CANCEL = "dev.siliconoptimizer.buddy.media.STOP_WAITING"

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
