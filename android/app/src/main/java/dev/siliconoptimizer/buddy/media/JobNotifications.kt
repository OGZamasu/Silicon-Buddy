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
     * would ring.
     */
    fun transition(previous: MediaJob?, next: MediaJob): JobNotice? {
        if (!next.state.isTerminal) return null
        if (previous != null && previous.state == next.state) return null
        val noun = noun(next.kind)
        return when (next.state) {
            JobState.Done -> JobNotice(
                id = next.id,
                title = "Your $noun is ready",
                body = listOfNotNull(
                    next.title.takeIf { it.isNotBlank() },
                    next.file ?: next.outputDirectory,
                ).joinToString(" — ").ifBlank { "Rendered on the Mac." },
                isFailure = false,
            )
            JobState.Failed -> JobNotice(
                id = next.id,
                title = "That $noun failed",
                body = next.error ?: next.title.ifBlank { "The Mac stopped rendering it." },
                isFailure = true,
            )
            JobState.Stopped -> JobNotice(
                id = next.id,
                title = "That $noun stopped",
                body = next.error
                    ?: "The Mac is no longer following it. The node may still finish it.",
                isFailure = true,
            )
            else -> null
        }
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

    fun notices(previous: QueueState, next: QueueState): List<JobNotice> {
        // The first reading of a Mac is its history. A queue keeps finished clips for a
        // long time, and opening the app should not fire a notification for every one of
        // them — those endings happened, and were said at the time or not at all.
        if (!primed) {
            primed = true
            next.jobs.filter { it.state.isTerminal }.forEach { announced[it.id] = it.state }
            return emptyList()
        }
        val notices = mutableListOf<JobNotice>()
        for (job in next.jobs) {
            if (!job.state.isTerminal) {
                // Queued again, or rendering again: the next ending is news.
                announced.remove(job.id)
                continue
            }
            if (announced[job.id] == job.state) continue
            val notice = JobNotifications.transition(previous.job(job.id), job)
            announced[job.id] = job.state
            if (notice != null) notices.add(notice)
        }
        // A clip the Mac has forgotten cannot be announced again either way.
        announced.keys.retainAll(next.jobs.map { it.id }.toSet())
        return notices
    }

    fun forget() {
        announced.clear()
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

    /** The ongoing one the foreground service holds up while a request is open. */
    fun ongoing(text: String, fraction: Double?): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, JobNotifications.PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Silicon Buddy")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(queueIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (fraction != null) {
            builder.setProgress(100, (fraction.coerceIn(0.0, 1.0) * 100).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
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
            .build()
        runCatching { manager.notify(notice.id.hashCode(), notification) }
    }

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
