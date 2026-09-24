package dev.siliconoptimizer.buddy.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.siliconoptimizer.buddy.MainActivity
import dev.siliconoptimizer.buddy.R

/**
 * What a job's change of state is worth telling somebody about.
 *
 * A render takes minutes, which is long enough that nobody watches it. So the phone
 * says when one finishes and when one fails, and says nothing at all while it is
 * working — that is what the ongoing notification beside it is for. The decision of
 * which transition deserves a notification is here, separately from Android's
 * notification machinery, because it is the part that can be wrong.
 */
data class JobNotice(
    val id: String,
    val title: String,
    val body: String,
    val isFailure: Boolean,
    /** Where the notification should land; the queue is the only place a job exists. */
    val opensQueue: Boolean = true,
)

object JobNotifications {

    /** One channel for the ongoing render, one for the "it's done" that outlives it. */
    const val PROGRESS_CHANNEL = "media-progress"
    const val FINISHED_CHANNEL = "media-finished"

    const val PROGRESS_NOTIFICATION = 4101

    /** The kind, as a person says it. */
    fun noun(kind: String): String = when (kind.lowercase()) {
        "video" -> "clip"
        "image" -> "image"
        "mesh" -> "mesh"
        else -> kind.lowercase().ifBlank { "job" }
    }

    /**
     * The notification a job's move from [previous] to [next] deserves, or null.
     *
     * Only the crossing into a finished state is worth an alert. A job first seen
     * already finished counts — the phone may have been asleep while the Mac worked —
     * but a repeat of the same terminal state does not, or every poll of the queue
     * would ring. (Whether a job first seen finished is worth asking about at all is the
     * announcer's call: for an image or a mesh it is not; see [JobAnnouncer.notices].)
     */
    fun transition(previous: MediaJob?, next: MediaJob): JobNotice? {
        if (!next.state.isTerminal) return null
        if (previous != null && previous.state == next.state) return null
        val noun = noun(next.kind)
        return when (next.state) {
            JobState.Done -> JobNotice(
                id = next.id,
                title = "Your $noun is ready",
                // What it was, not where it is. A notification is drawn on a locked
                // screen and read by whoever is holding the phone, and the path is a
                // fact about the owner's disk that nobody standing nearby needs.
                body = listOfNotNull(
                    next.title.takeIf { it.isNotBlank() },
                    next.scene?.let { scene ->
                        next.variation?.let { "scene $scene · take $it" } ?: "scene $scene"
                    },
                    "on the Mac",
                ).joinToString(" — "),
                isFailure = false,
            )
            JobState.Failed -> JobNotice(
                id = next.id,
                title = "That $noun failed",
                body = withoutPaths(
                    next.error ?: next.title.ifBlank { "The Mac stopped rendering it." },
                ),
                isFailure = true,
            )
            // A cancel the node confirmed is what somebody asked for, and it is final:
            // "may still finish it" would be untrue of it.
            JobState.Stopped -> if (next.cancel == CancelState.Confirmed) {
                JobNotice(
                    id = next.id,
                    title = "That $noun was cancelled",
                    body = "Its node stopped the render. Nothing will be published for it.",
                    isFailure = false,
                )
            } else {
                JobNotice(
                    id = next.id,
                    title = "That $noun stopped",
                    body = withoutPaths(
                        next.error
                            ?: "The Mac is no longer following it. The node may still finish it.",
                    ),
                    isFailure = true,
                )
            }
            else -> null
        }
    }

    /**
     * The Mac's sentences sometimes name a file, and a notification is read by whoever
     * is holding the phone. The sentence survives; the path does not.
     */
    fun withoutPaths(text: String): String =
        text.split(" ").joinToString(" ") { word ->
            if (word.startsWith("/") && word.count { it == '/' } > 1) "a file on the Mac" else word
        }

    /** The line the ongoing notification shows while a request is open. */
    fun ongoingText(kind: String, detail: String?): String =
        listOfNotNull("Rendering a ${noun(kind)} on the Mac", detail).joinToString(" — ")
}

/**
 * Says each piece of news once, however it arrived.
 *
 * Two sources report the same render — the `job` events and the queue poll — and which
 * of them notices a clip has finished is a race. Comparing one against the other would
 * mean a notification that depends on the timing of a poll: sometimes two, sometimes
 * none. So the queue as a whole is compared against the queue as it was, and an id that
 * has already been announced in a state is not announced again until it leaves that
 * state — which a retry does.
 */
class JobAnnouncer {

    private val announced = mutableMapOf<String, JobState>()
    private var primed = false

    /**
     * Renders seen running while the service held this phone's request of their kind: the
     * service's to announce, whenever their ending reaches the stream. The kind-wide claim
     * it makes lapses a minute after the request answers, and the app can come back to the
     * screen long after that — to hear the ending again, in the Mac's opening frames.
     *
     * Each with how many requests of its kind the service had given up on when it was taken
     * (see [notices]'s `gaveUp`): one the service gave up on since has no answer coming from
     * it, and its render's ending is news again.
     */
    private val claimed = mutableMapOf<String, Int>()

    /** True once a queue has been read; nothing is announced before that. */
    val isPrimed: Boolean get() = primed

    /**
     * The Mac's queue as it was when this phone arrived.
     *
     * Everything already finished in it is history: it happened while the app was
     * somewhere else, and was said at the time or not at all. Priming has to come from
     * a real `GET /video/queue` rather than from whatever happened to be applied first
     * — a `job` event can beat the first poll, and priming on that would make every
     * clip in the Mac's memory "new" a moment later, which is a notification each.
     */
    fun prime(queue: QueueState) {
        announced.clear()
        queue.jobs.filter { it.state.isTerminal }.forEach { announced[it.id] = it.state }
        primed = true
    }

    /**
     * What is worth saying about the move from [previous] to [next].
     *
     * [handledElsewhere] is for work this phone started itself through the foreground
     * service: the Mac reports it on `/events` as well, and the render should ring
     * once, from whichever of the two owns it.
     *
     * [gaveUp] counts, by kind, the requests the service ended without the Mac's answer —
     * its wait ran out, the owner stopped waiting, the connection dropped. The service's
     * last word on those was not how the render ended, so a render claimed before one of
     * them is not left to the service any more: the Mac's ending is announced here.
     */
    fun notices(
        previous: QueueState,
        next: QueueState,
        handledElsewhere: (MediaJob) -> Boolean = { false },
        gaveUp: (kind: String) -> Int = { 0 },
    ): List<JobNotice> {
        if (!primed) return emptyList()
        val notices = mutableListOf<JobNotice>()
        for (job in next.jobs) {
            if (!job.state.isTerminal) {
                // Queued again, or rendering again: the next ending is news.
                announced.remove(job.id)
                if (handledElsewhere(job) && job.id !in claimed) claimed[job.id] = gaveUp(job.kind)
                continue
            }
            if (announced[job.id] == job.state) continue
            announced[job.id] = job.state
            // Given up on since it was claimed: the service will not say how it ended.
            claimed[job.id]?.let { since -> if (gaveUp(job.kind) > since) claimed.remove(job.id) }
            if (job.id in claimed || handledElsewhere(job)) continue
            val before = previous.job(job.id)
            // An image or a mesh is a `job` event and nothing else, and the Mac tells every
            // phone that opens `/events` how its last few ended. One whose first word here is
            // its ending finished while this phone was not looking — before a cold start, or
            // while the app was away — and was said then or not at all. A clip is different:
            // the video queue this phone was primed from is the history, and a clip first
            // seen finished after that is one that finished between two readings.
            if (before == null && !job.isQueued) continue
            JobNotifications.transition(before, job)?.let { notices.add(it) }
        }
        // A clip the Mac has forgotten cannot be announced again either way.
        val present = next.jobs.map { it.id }.toSet()
        announced.keys.retainAll(present)
        claimed.keys.retainAll(present)
        return notices
    }

    fun forget() {
        announced.clear()
        claimed.clear()
        primed = false
    }
}

/**
 * The Android half: channels, permission, and posting.
 *
 * Separate from the rules above so the rules can be tested without a device, and so the
 * one place that touches `NotificationManager` also knows that posting without
 * permission on Android 13 and later is a silent no-op rather than an error.
 */
class MediaNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    val isAllowed: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(
                JobNotifications.PROGRESS_CHANNEL,
                "Renders in progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shown while the Mac is rendering something for you." },
        )
        system.createNotificationChannel(
            NotificationChannel(
                JobNotifications.FINISHED_CHANNEL,
                "Finished renders",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "When a clip, an image or a mesh is done — or fails." },
        )
    }

    /**
     * The ongoing one the foreground service holds up while a request is open, with
     * the only thing a person can do about a render they are tired of waiting for.
     */
    fun ongoing(work: MediaJobCenter.Work, fraction: Double?, context: Context): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(this.context, JobNotifications.PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Silicon Buddy")
            .setContentText(JobNotifications.ongoingText(work.kind, work.summary))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(queueIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Stop waiting",
                stopWaitingIntent(context, work.id),
            )
        if (fraction != null) {
            builder.setProgress(100, (fraction.coerceIn(0.0, 1.0) * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** Redraws a render's ongoing notification as its fraction moves. */
    fun showOngoing(id: Int, work: MediaJobCenter.Work, fraction: Double?, context: Context) {
        if (!isAllowed) return
        runCatching { manager.notify(id, ongoing(work, fraction, context)) }
    }

    fun cancel(id: Int) {
        runCatching { manager.cancel(id) }
    }

    /**
     * "Stop waiting" lets go of the request. It is not a cancel: the Mac has the work
     * and will finish it, and this app will not claim otherwise.
     */
    fun stopWaitingIntent(context: Context, workID: String): PendingIntent {
        val intent = Intent(context, MediaJobService::class.java)
            .setAction(MediaJobService.ACTION_CANCEL)
            .putExtra(MediaJobService.EXTRA_WORK_ID, workID)
        return PendingIntent.getService(
            context, workID.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun post(notice: JobNotice) {
        if (!isAllowed) return
        ensureChannels()
        val notification = NotificationCompat.Builder(context, JobNotifications.FINISHED_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notice.title)
            .setContentText(notice.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.body))
            .setAutoCancel(true)
            .setContentIntent(queueIntent())
            .setCategory(if (notice.isFailure) NotificationCompat.CATEGORY_ERROR else null)
            // What is on somebody's Mac is their business, and a locked screen is
            // shown to whoever is in the room.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic(notice))
            .build()
        runCatching { manager.notify(notice.id.hashCode(), notification) }
    }

    /** What a locked phone shows instead: that there is something, not what. */
    private fun generic(notice: JobNotice): Notification =
        NotificationCompat.Builder(context, JobNotifications.FINISHED_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Silicon Buddy")
            .setContentText(if (notice.isFailure) "A render needs a look" else "A render finished")
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    /** Tapping any of these opens the queue, which is where a job can be acted on. */
    private fun queueIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_QUEUE)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
