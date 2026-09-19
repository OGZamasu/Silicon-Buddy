package dev.siliconoptimizer.buddy.agents

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
import dev.siliconoptimizer.buddy.transport.AgentApproval
import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision

/**
 * One approval, as a notification: what it says and what its buttons do.
 *
 * Decided here, apart from Android's notification machinery, because this is the part that
 * can be wrong in a way that matters. A notification is drawn on a locked screen in a room
 * with other people in it, and its buttons run commands on somebody's Mac — so what it may
 * say and who may press it are rules with tests, not properties set in passing.
 */
data class ApprovalNotice(
    val engine: String,
    val approvalID: String,
    /** Its own notification: two approvals never replace each other. */
    val notificationID: Int,
    /** "Codex wants to run a command". */
    val title: String,
    /** The command line, the paths or the tool — one line, cut short, secrets masked. */
    val text: String,
    /** The same with a little more room, and why the engine is asking. */
    val detail: String,
    val actions: List<NoticeAction>,
)

/**
 * A button on an approval notification.
 *
 * [authenticationRequired] is Android 12's `setAuthenticationRequired`: pressed on a locked
 * phone, the system asks for the owner's fingerprint or PIN before the intent is sent. On an
 * older Android there is no such thing, and a button that ran a command from a locked shade
 * would run it for whoever is holding the phone — so there [opensApp] is true instead and
 * the button opens the session, where the decision is made with the phone unlocked.
 */
data class NoticeAction(
    val label: String,
    /** `accept` or `decline`. */
    val decision: String,
    val authenticationRequired: Boolean,
    val opensApp: Boolean,
)

object AgentNotifications {

    /** Approvals: heads-up, because an agent is blocked until somebody answers. */
    const val APPROVAL_CHANNEL = "agent-approvals"

    /** The foreground service's own quiet notification while it watches. */
    const val WATCH_CHANNEL = "agent-watching"
    const val WATCH_NOTIFICATION = 4201
    const val LOST_TOUCH_NOTIFICATION = 4202

    /** What a locked screen shows instead of the command: that something waits, not what. */
    const val PUBLIC_TITLE = "Silicon Buddy"
    const val PUBLIC_TEXT = "An agent on your Mac is waiting for you"

    /** One line in the shade, and a few in the expanded notification. */
    const val LINE_LIMIT = 140
    const val DETAIL_LIMIT = 320
    const val DETAIL_LINES = 3

    /** Android 12 is where a notification action can demand the device be unlocked. */
    const val AUTHENTICATED_ACTIONS_SDK = 31

    fun notice(engine: String, approval: AgentApproval, sdk: Int): ApprovalNotice {
        val detail = buildList {
            add(excerpt(approval.summary, DETAIL_LIMIT, DETAIL_LINES))
            approval.reason?.takeIf { it.isNotBlank() }?.let { add(line(it, LINE_LIMIT)) }
            // The guardrail's own sentence — "Jev: review: destructive" — as the Mac's card
            // shows it.
            add(line(approval.screening.summary, LINE_LIMIT))
        }.joinToString("\n")
        val authenticated = sdk >= AUTHENTICATED_ACTIONS_SDK
        return ApprovalNotice(
            engine = engine,
            approvalID = approval.id,
            notificationID = notificationID(engine, approval.id),
            title = AgentNotices.headline(engine, approval),
            text = line(approval.summary, LINE_LIMIT),
            detail = detail,
            actions = listOf(
                NoticeAction(
                    "Decline", AgentApprovalDecision.DECLINE,
                    authenticationRequired = authenticated, opensApp = !authenticated,
                ),
                NoticeAction(
                    "Accept", AgentApprovalDecision.ACCEPT,
                    authenticationRequired = authenticated, opensApp = !authenticated,
                ),
            ),
        )
    }

    /**
     * One notice per approval waiting in a session this phone is watching — never two for
     * the same id, and none for a session nobody here opened.
     */
    fun notices(board: AgentBoard, watched: Set<String>, sdk: Int): List<ApprovalNotice> =
        watched.sorted()
            .flatMap { engine -> board.session(engine).pending.map { engine to it } }
            .distinctBy { (_, approval) -> approval.id }
            .map { (engine, approval) -> notice(engine, approval, sdk) }

    /** Stable per approval, and clear of the render notifications' numbers. */
    fun notificationID(engine: String, approvalID: String): Int =
        0x5A000000 or ("$engine:$approvalID".hashCode() and 0x00FFFFFF)

    /**
     * Where "Accepted on this phone" goes after a button in the shade was pressed. Its own
     * number rather than the approval's: the watcher takes the approval's notification
     * down the moment the Mac confirms the answer, and that must not take the confirmation
     * with it.
     */
    fun settledID(engine: String, approvalID: String): Int =
        0x5B000000 or ("$engine:$approvalID".hashCode() and 0x00FFFFFF)

    /** What the ongoing notification says while the service watches. */
    fun watchingTitle(engines: Collection<String>): String =
        "Watching " + engines.sorted().joinToString(" and ") { AgentNotices.engine(it) } +
            " on your Mac"

    fun watchingText(waiting: Int): String = when (waiting) {
        0 -> "A turn is running. Anything it asks you about rings here."
        1 -> "1 approval is waiting for you."
        else -> "$waiting approvals are waiting for you."
    }

    // MARK: - What a notification may say

    /**
     * A single line: the first line of [text], secrets masked, cut on a word if it is long.
     * A command can be a heredoc and a tool call can carry a whole file; neither belongs
     * on a lock screen.
     */
    fun line(text: String, limit: Int): String {
        val first = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val more = text.trim().lines().count { it.isNotBlank() } > 1
        return cut(redact(first), limit, more)
    }

    /** A few lines at most, for the expanded notification. Never the whole of anything. */
    fun excerpt(text: String, limit: Int, lines: Int): String {
        val all = text.trim().lines().filter { it.isNotBlank() }
        val kept = all.take(lines).joinToString("\n") { it.trim() }
        return cut(redact(kept), limit, all.size > lines)
    }

    private fun cut(text: String, limit: Int, more: Boolean): String {
        if (text.length <= limit) return if (more) "$text …" else text
        val head = text.take(limit)
        val space = head.lastIndexOf(' ')
        return (if (space > limit / 2) head.take(space) else head).trimEnd() + "…"
    }

    private val bearer = Regex("(?i)\\b(bearer)\\s+[A-Za-z0-9._~+/=-]+")
    private val assignment = Regex(
        "(?i)\\b(token|secret|password|passwd|pwd|api[_-]?key|access[_-]?key|auth)" +
            "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)",
    )
    private val opaque = Regex("[A-Za-z0-9_-]{32,}")

    /**
     * Masks what looks like a credential: a bearer token, `password=…` and its cousins,
     * and any long unbroken run of letters and digits — an API key, a hash, a token by any
     * other name. Heuristic by nature, and on purpose tilted towards masking too much: a
     * notification that says `•••` where a commit hash was costs nothing.
     */
    fun redact(text: String): String = text
        .replace(bearer) { "${it.groupValues[1]} •••" }
        .replace(assignment) { "${it.groupValues[1]}${it.groupValues[2]}•••" }
        .replace(opaque, "•••")
}

/**
 * The Android half: channels, permission, and the notifications themselves.
 *
 * Separate from [AgentNotifications] so the rules can be tested without a device, and so
 * the one place that touches `NotificationManager` also knows that posting without the
 * permission on Android 13 and later is a silent no-op rather than an error.
 */
class AgentNotifier(private val context: Context) {

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
                AgentNotifications.APPROVAL_CHANNEL,
                "Agent approvals",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "When Codex or Pi on your Mac is waiting for you to allow " +
                    "something. Accepting or declining asks you to unlock the phone first."
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
        system.createNotificationChannel(
            NotificationChannel(
                AgentNotifications.WATCH_CHANNEL,
                "Watching agent sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while Silicon Buddy keeps an agent turn in view with " +
                    "the app closed."
            },
        )
    }

    /** The notification one approval gets. */
    fun build(notice: ApprovalNotice): Notification {
        ensureChannels()
        val builder = NotificationCompat.Builder(context, AgentNotifications.APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.detail))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setContentIntent(openSession(context, notice.engine, notice.notificationID))
            // Whoever is holding a locked phone sees that something is waiting, not what.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.APPROVAL_CHANNEL))
        actions(context, notice).forEach { builder.addAction(it) }
        return builder.build()
    }

    fun post(notice: ApprovalNotice) {
        if (!isAllowed) return
        runCatching { manager.notify(notice.notificationID, build(notice)) }
    }

    /**
     * After an answer from the notification: the approval's own notification goes, and a
     * quiet one says what came of it — in the Mac's words when it had any — with no
     * buttons on it.
     */
    fun settle(engine: String, approvalID: String, text: String) {
        cancel(AgentNotifications.notificationID(engine, approvalID))
        if (!isAllowed) return
        val notificationID = AgentNotifications.settledID(engine, approvalID)
        ensureChannels()
        val notification = NotificationCompat.Builder(context, AgentNotifications.APPROVAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(AgentNotices.engine(engine))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setAutoCancel(true)
            .setTimeoutAfter(SETTLED_TIMEOUT_MS)
            .setContentIntent(openSession(context, engine, notificationID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.APPROVAL_CHANNEL))
            .build()
        runCatching { manager.notify(notificationID, notification) }
    }

    fun cancel(notificationID: Int) {
        runCatching { manager.cancel(notificationID) }
    }

    /** The foreground service's notification, with the two ways out of it. */
    fun watching(engines: Collection<String>, waiting: Int, stop: PendingIntent): Notification {
        ensureChannels()
        return NotificationCompat.Builder(context, AgentNotifications.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(AgentNotifications.watchingTitle(engines))
            .setContentText(AgentNotifications.watchingText(waiting))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openSession(context, engines.firstOrNull(), AgentNotifications.WATCH_NOTIFICATION))
            // Android 14 lets a person swipe a foreground service's notification away;
            // doing that means "stop", so it does.
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_launcher_foreground, "Stop watching", stop)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.WATCH_CHANNEL))
            .build()
    }

    fun showWatching(engines: Collection<String>, waiting: Int, stop: PendingIntent) {
        if (!isAllowed) return
        runCatching {
            manager.notify(AgentNotifications.WATCH_NOTIFICATION, watching(engines, waiting, stop))
        }
    }

    /** Said once, when the service lets go because the Mac stopped answering. */
    fun lostTouch(engines: Collection<String>) {
        if (!isAllowed) return
        ensureChannels()
        val names = engines.sorted().joinToString(" and ") { AgentNotices.engine(it) }
        val notification = NotificationCompat.Builder(context, AgentNotifications.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Stopped watching $names")
            .setContentText("Your Mac stopped answering. Open Silicon Buddy to check on it.")
            .setAutoCancel(true)
            .setContentIntent(openSession(context, engines.firstOrNull(), AgentNotifications.LOST_TOUCH_NOTIFICATION))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.WATCH_CHANNEL))
            .build()
        runCatching { manager.notify(AgentNotifications.LOST_TOUCH_NOTIFICATION, notification) }
    }

    private fun publicVersion(channel: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(AgentNotifications.PUBLIC_TITLE)
            .setContentText(AgentNotifications.PUBLIC_TEXT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    companion object {
        /** How long "Accepted" stays in the shade after an answer from a notification. */
        const val SETTLED_TIMEOUT_MS = 8_000L

        /**
         * The notification's two buttons.
         *
         * On Android 12 and later each one is a broadcast to [ApprovalActionReceiver] and
         * demands the device be unlocked first. Before that, each one opens the session
         * instead — the decision is then made in the app, on an unlocked phone.
         *
         * Public so a test can build them on the JVM, where every `PendingIntent` is null
         * and what is being checked is the flag, not the intent.
         */
        fun actions(context: Context?, notice: ApprovalNotice): List<NotificationCompat.Action> =
            notice.actions.mapIndexed { index, action ->
                val intent = context?.let {
                    if (action.opensApp) {
                        openSession(it, notice.engine, notice.notificationID * 4 + index + 1)
                    } else {
                        ApprovalActionReceiver.intent(it, notice, action.decision)
                    }
                }
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground, action.label, intent,
                )
                    .setAuthenticationRequired(action.authenticationRequired)
                    .setShowsUserInterface(action.opensApp)
                    .build()
            }

        /** Opens the Agents tab on [engine]'s session. A request for a screen, nothing more. */
        fun openSession(context: Context, engine: String?, requestCode: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_AGENTS)
                .apply { engine?.let { putExtra(MainActivity.EXTRA_ENGINE, it) } }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
