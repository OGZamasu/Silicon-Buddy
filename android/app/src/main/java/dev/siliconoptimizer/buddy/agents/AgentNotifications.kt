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
import dev.siliconoptimizer.buddy.transport.TransportError

/**
 * One approval, as a notification: what it says and what its buttons do.
 *
 * Decided here, apart from Android's notification machinery, because this is the part that
 * can be wrong in a way that matters. A notification is drawn on a lock screen, and Android
 * shows a private notification's full content there unless the owner has chosen to hide
 * sensitive content — which is not the default. So an approval notification never carries
 * the command, the paths, the tool's arguments, the engine's reason or any output: only
 * which engine, what kind of thing it wants to do, and what the Mac's guardrail made of it.
 *
 * And because the command is not on it, nothing on it can approve the command: allowing
 * something nobody has read — whatever the guardrail said, "would block" and "not
 * screened" included — is not an answer. Its buttons are Decline, which is safe to give
 * blind, and Review, which opens that card in the app after unlocking; Accept is only ever
 * pressed there, under the command it runs.
 */
data class ApprovalNotice(
    val engine: String,
    val approvalID: String,
    /** Its own notification: two approvals never replace each other. */
    val notificationID: Int,
    /** "Codex wants to run a command". */
    val title: String,
    /** The guardrail's verdict, in words: "Screened: asks you". */
    val text: String,
    /** The same, and where the detail is. */
    val detail: String,
    val actions: List<NoticeAction>,
)

/**
 * A button on an approval notification.
 *
 * [authenticationRequired] is Android 12's `setAuthenticationRequired`: pressed on a locked
 * phone, the system asks for the owner's fingerprint or PIN before the intent is sent — and
 * the receiver checks again, because a notification listener can send it without the system
 * UI. On an older Android there is no such thing, and a button that answered from a locked
 * shade would answer for whoever is holding the phone — so there the one button is Review,
 * and the decision is made in the app, on an unlocked phone.
 */
data class NoticeAction(
    val label: String,
    /** `decline`, or null for the button that opens the card in the app. Never `accept`. */
    val decision: String?,
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

    /** What a locked screen shows instead, per kind of notification: that, not what. */
    const val PUBLIC_TITLE = "Silicon Buddy"
    const val PUBLIC_APPROVAL = "An agent on your Mac is waiting for you"
    const val PUBLIC_ANSWERED = "An agent's question was answered"
    const val PUBLIC_WATCHING = "Following an agent on your Mac"
    const val PUBLIC_ENDED = "Stopped following an agent on your Mac"

    /** The longest the in-app resolution line gets. */
    const val LINE_LIMIT = 140

    /** Android 12 is where a notification action can demand the device be unlocked. */
    const val AUTHENTICATED_ACTIONS_SDK = 31

    const val DECLINE = "Decline"
    const val REVIEW = "Review"

    /** Under the verdict: where the command is, and where accepting it happens. */
    const val REVIEW_HINT = "Review it to see exactly what it wants; you accept it in the app."

    /** High bytes of the notification numbers, so the kinds never collide. */
    private const val APPROVAL_IDS = 0x5A
    private const val SETTLED_IDS = 0x5B

    fun notice(engine: String, approval: AgentApproval, sdk: Int): ApprovalNotice {
        val verdict = AgentNotices.verdict(approval.screening.verdict)
        val authenticated = sdk >= AUTHENTICATED_ACTIONS_SDK
        val review = NoticeAction(REVIEW, null, authenticationRequired = authenticated, opensApp = true)
        return ApprovalNotice(
            engine = engine,
            approvalID = approval.id,
            notificationID = notificationID(engine, approval.id),
            title = AgentNotices.headline(engine, approval),
            text = verdict,
            detail = "$verdict. $REVIEW_HINT",
            actions = if (authenticated) {
                listOf(
                    NoticeAction(DECLINE, AgentApprovalDecision.DECLINE, authenticationRequired = true, opensApp = false),
                    review,
                )
            } else {
                // Android 10 and 11 cannot make a button wait for the owner's unlock, so
                // nothing is answered from their shade: the one button opens the card.
                listOf(review)
            },
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
        (APPROVAL_IDS shl 24) or ("$engine:$approvalID".hashCode() and 0x00FFFFFF)

    /**
     * Where "Declined on this phone" goes after a button in the shade was pressed. Its own
     * number rather than the approval's: the watcher takes the approval's notification
     * down the moment the Mac confirms the answer, and that must not take the confirmation
     * with it.
     */
    fun settledID(engine: String, approvalID: String): Int =
        (SETTLED_IDS shl 24) or ("$engine:$approvalID".hashCode() and 0x00FFFFFF)

    /** Whether a posted notification is an approval's own (not its confirmation). */
    fun isApproval(notificationID: Int): Boolean = (notificationID ushr 24) == APPROVAL_IDS

    /** What the ongoing notification says while the service watches. */
    fun watchingTitle(engines: Collection<String>): String =
        "Watching " + engines.sorted().joinToString(" and ") { AgentNotices.engine(it) } +
            " on your Mac"

    fun watchingText(waiting: Int): String = when (waiting) {
        0 -> "A turn is running. Anything it asks you about rings here."
        1 -> "1 approval is waiting for you."
        else -> "$waiting approvals are waiting for you."
    }

    // MARK: - Text shown outside the transcript

    /**
     * A single line: the first line of [text], secrets masked, cut on a word if it is long.
     * For the in-app line that says how a card came down — never for a notification.
     */
    fun line(text: String, limit: Int): String {
        val masked = redact(text.trim())
        val first = masked.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val more = masked.lines().count { it.isNotBlank() } > 1
        return cut(first, limit, more)
    }

    private fun cut(text: String, limit: Int, more: Boolean): String {
        if (text.length <= limit) return if (more) "$text …" else text
        val head = text.take(limit)
        val space = head.lastIndexOf(' ')
        return (if (space > limit / 2) head.take(space) else head).trimEnd() + "…"
    }

    private const val MASK = "•••"

    /** A private key block, to its end or to the end of what was given. */
    private val privateKey = Regex(
        "(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE KEY-----|\\z)",
    )

    /** `scheme://user:secret@host` and `scheme://token@host`. */
    private val userinfo = Regex("(?i)\\b([a-z][a-z0-9+.-]*://)([^\\s/@:]+)(?::([^\\s/@]+))?@")

    /** `Authorization: Bearer …`, `Basic …`. */
    private val scheme = Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+")

    /**
     * `password=…`, `DB_PASSWORD=…`, `OPENAI_API_KEY: …`, `X-Api-Key: …`, `SECRET_KEY=…`,
     * `STRIPE_KEY=…`, and a bare `key=…` — which is also `?key=…` in a URL, where the rest of
     * the URL goes with it: a secret can hold an `&`. The name may carry any prefix — `\b`
     * alone never matches after `_`, which is how `GITHUB_TOKEN` slipped by — and anything
     * ending `_KEY` or `-key` counts; `monkey` does not.
     */
    private val assignment = Regex(
        "(?i)(?<![A-Za-z0-9_])([A-Za-z0-9_]*(?:token|secret|password|passwd|pwd|pass|" +
            "api[_-]?key|access[_-]?key|private[_-]?key|auth|credentials?|[_-]key)|key)" +
            "(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)",
    )

    /** `--password X`, `--api-key=X`, `--token X`. */
    private val flag = Regex(
        "(?i)(--[a-z0-9-]*(?:password|passwd|pass|token|secret|api-?key|auth)[a-z0-9-]*)" +
            "(=|\\s+)(\"[^\"]*\"|'[^']*'|\\S+)",
    )

    /** `curl -u user:secret`. */
    private val userFlag = Regex("(?<![\\w-])(-u|--user)(\\s*)([^\\s:]+):(\\S+)")

    /** `sshpass -p secret`. */
    private val sshpass = Regex("(?i)\\b(sshpass(?:\\s+-[a-oq-z]\\S*)*\\s+-p)(\\s*)(\\S+)")

    /** `mysql -psecret`: the value attached, which is how the mysql family takes one. */
    private val attachedP = Regex("(?<![\\w-])-p([^\\s-]\\S*)")

    /** A long unbroken run of letters and digits: a key, a hash, a token by any other name. */
    private val opaque = Regex("[A-Za-z0-9_-]{32,}")

    /** Base64 with its own punctuation: `+`, `/` and `=` padding, and a digit somewhere. */
    private val base64 = Regex(
        "(?<![A-Za-z0-9+/=_-])(?=[A-Za-z0-9+/]*[0-9])(?=[A-Za-z0-9+/]*[A-Za-z])" +
            "(?:[A-Za-z0-9+/]{16,}={1,2}|(?=[A-Za-z0-9/]*\\+)[A-Za-z0-9+/]{24,}={0,2})(?![A-Za-z0-9+/=_-])",
    )

    /**
     * Masks what looks like a credential. Heuristic by nature, and on purpose tilted towards
     * masking too much: a line that says `•••` where a commit hash was costs nothing.
     */
    fun redact(text: String): String = text
        .replace(privateKey, "[private key]")
        .replace(userinfo) { match ->
            val password = match.groups[3]
            if (password != null) "${match.groupValues[1]}${match.groupValues[2]}:$MASK@"
            else "${match.groupValues[1]}$MASK@"
        }
        .replace(scheme) { "${it.groupValues[1]} $MASK" }
        .replace(flag) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        .replace(sshpass) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        .replace(userFlag) { "${it.groupValues[1]}${it.groupValues[2]}${it.groupValues[3]}:$MASK" }
        .replace(attachedP) { "-p$MASK" }
        .replace(assignment) { "${it.groupValues[1]}${it.groupValues[2]}$MASK" }
        .replace(base64, MASK)
        .replace(opaque, MASK)
}

/**
 * What a button in the shade came to, in one fixed sentence per outcome.
 *
 * Never the error's own message: `Unreachable` puts the Mac's tailnet address in its message
 * by construction, and a 409's body is whatever the Mac wrote. A notification is read on a
 * lock screen and outlives the moment, so it says which of a handful of things happened and
 * nothing a server supplied.
 */
object ApprovalReplies {
    const val ACCEPTED = "Accepted on this phone."
    const val DECLINED = "Declined on this phone."
    const val LOCKED = "Unlock your phone to answer. Nothing was sent."

    /** An Accept from a notification an earlier build posted: accepting happens in the app. */
    const val ACCEPT_IN_APP = "Open Silicon Buddy to accept it, where you can see exactly what it wants. Nothing was sent."
    const val NO_MAC = "No Mac is paired with this phone any more."
    const val UNPAIRED = "This phone is no longer paired with your Mac. Open Silicon Buddy to pair it again."
    const val NOT_ALLOWED = "This phone may not answer agents on your Mac. Pair it again with full control."
    const val ANSWERED_ON_THE_MAC = "Answered on the Mac first. Nothing was sent twice."
    const val STILL_SCREENING = "The Mac's guardrail is still looking at it. Try again in a moment."
    const val ENGINE_STOPPED = "The agent has stopped on the Mac, so there is nothing to answer."
    const val NOT_TAKEN = "Your Mac didn't take that answer. Open Silicon Buddy to see why."
    const val UNREACHABLE = "Your Mac didn't answer. Open Silicon Buddy to answer it there."
    const val TOO_SLOW = "Your Mac took too long to answer. Open Silicon Buddy to check."

    fun forDecision(decision: String): String =
        if (AgentAnswers.decided(decision) == "accepted") ACCEPTED else DECLINED

    /** Null when there is nothing to say: a 404 means the card is simply gone. */
    fun forError(error: TransportError): String? = when (error) {
        is TransportError.NotFound, is TransportError.RouteUnavailable -> null
        is TransportError.Unauthorized -> UNPAIRED
        is TransportError.Forbidden -> NOT_ALLOWED
        is TransportError.Conflict -> forConflict(error.detail)
        is TransportError.TimedOut -> TOO_SLOW
        is TransportError.NotConfigured -> NO_MAC
        else -> UNREACHABLE
    }

    /**
     * A 409 is several things on this route; the Mac's sentence says which. Read, never
     * repeated.
     */
    fun forConflict(detail: String): String {
        val text = detail.lowercase()
        return when {
            "answered at the mac" in text -> ANSWERED_ON_THE_MAC
            "screening" in text -> STILL_SCREENING
            "not running" in text -> ENGINE_STOPPED
            else -> NOT_TAKEN
        }
    }
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
                    "something. Review opens it in the app, where it can be accepted; " +
                    "declining from here asks you to unlock the phone first."
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
            .setContentIntent(openSession(context, notice.engine, notice.notificationID, notice.approvalID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.APPROVAL_CHANNEL, AgentNotifications.PUBLIC_APPROVAL))
        actions(context, notice).forEach { builder.addAction(it) }
        return builder.build()
    }

    fun post(notice: ApprovalNotice) {
        if (!isAllowed) return
        runCatching { manager.notify(notice.notificationID, build(notice)) }
    }

    /**
     * After an answer from the notification: the approval's own notification goes, and a
     * quiet one says what came of it — one of [ApprovalReplies]' sentences — with no
     * buttons on it.
     */
    fun settle(engine: String, approvalID: String, text: String, keepApproval: Boolean = false) {
        if (!keepApproval) cancel(AgentNotifications.notificationID(engine, approvalID))
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
            .setContentIntent(openSession(context, engine, notificationID, approvalID))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.APPROVAL_CHANNEL, AgentNotifications.PUBLIC_ANSWERED))
            .build()
        runCatching { manager.notify(notificationID, notification) }
    }

    fun cancel(notificationID: Int) {
        runCatching { manager.cancel(notificationID) }
    }

    /**
     * Takes down every approval notification this app has up — the ones a watcher left
     * behind when its process died, whose buttons nothing is behind any more.
     */
    fun cancelApprovals() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            system.activeNotifications
                .filter { AgentNotifications.isApproval(it.id) }
                .forEach { manager.cancel(it.id) }
        }
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
            .setPublicVersion(publicVersion(AgentNotifications.WATCH_CHANNEL, AgentNotifications.PUBLIC_WATCHING))
            .build()
    }

    fun showWatching(engines: Collection<String>, waiting: Int, stop: PendingIntent) {
        if (!isAllowed) return
        runCatching {
            manager.notify(AgentNotifications.WATCH_NOTIFICATION, watching(engines, waiting, stop))
        }
    }

    /** Said once, when the service lets go for a reason worth knowing about. */
    fun ended(engines: Collection<String>, ending: WatchEnding) {
        val text = WatchPolicy.sentence(ending) ?: return
        if (!isAllowed) return
        ensureChannels()
        val names = engines.sorted().joinToString(" and ") { AgentNotices.engine(it) }
        val notification = NotificationCompat.Builder(context, AgentNotifications.WATCH_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Stopped watching $names")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openSession(context, engines.firstOrNull(), AgentNotifications.LOST_TOUCH_NOTIFICATION))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(AgentNotifications.WATCH_CHANNEL, AgentNotifications.PUBLIC_ENDED))
            .build()
        runCatching { manager.notify(AgentNotifications.LOST_TOUCH_NOTIFICATION, notification) }
    }

    private fun publicVersion(channel: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(AgentNotifications.PUBLIC_TITLE)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    companion object {
        /** How long "Declined" stays in the shade after an answer from a notification. */
        const val SETTLED_TIMEOUT_MS = 8_000L

        /**
         * The notification's buttons.
         *
         * On Android 12 and later Decline is a broadcast to [ApprovalActionReceiver] and
         * Review opens the card in the app, and both demand the device be unlocked first.
         * Before that, the one button is Review — the decision is then made in the app, on
         * an unlocked phone.
         *
         * Public so a test can build them on the JVM, where every `PendingIntent` is null
         * and what is being checked is the flag, not the intent.
         */
        fun actions(context: Context?, notice: ApprovalNotice): List<NotificationCompat.Action> =
            notice.actions.mapIndexed { index, action ->
                val intent = context?.let {
                    val decision = action.decision
                    if (action.opensApp || decision == null) {
                        openSession(it, notice.engine, notice.notificationID * 4 + index + 1, notice.approvalID)
                    } else {
                        ApprovalActionReceiver.intent(it, notice, decision)
                    }
                }
                NotificationCompat.Action.Builder(
                    R.drawable.ic_launcher_foreground, action.label, intent,
                )
                    .setAuthenticationRequired(action.authenticationRequired)
                    .setShowsUserInterface(action.opensApp)
                    .build()
            }

        /**
         * Opens the Agents tab on [engine]'s session, with [approvalID]'s card the one in
         * front when it is still waiting. A request for a screen, nothing more.
         */
        fun openSession(context: Context, engine: String?, requestCode: Int, approvalID: String? = null): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_AGENTS)
                .apply { knownEngine(engine)?.let { putExtra(MainActivity.EXTRA_ENGINE, it) } }
                .apply { approvalID?.let { putExtra(MainActivity.EXTRA_APPROVAL, it) } }
            return PendingIntent.getActivity(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
