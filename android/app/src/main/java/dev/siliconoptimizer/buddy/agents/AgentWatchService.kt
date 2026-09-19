package dev.siliconoptimizer.buddy.agents

import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.reach.SnapshotStore
import dev.siliconoptimizer.buddy.tile.BuddyTileService
import dev.siliconoptimizer.buddy.transport.AgentApprovalDecision
import dev.siliconoptimizer.buddy.transport.ControlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * An agent turn, kept in view while the app is not.
 *
 * Started when the app leaves the foreground with a turn running in a session this phone
 * has opened. It opens `/events` for itself — the app closes its own stream when it leaves
 * the screen — reads the watched sessions whole so an approval asked a moment ago still
 * rings, and then posts one notification per approval that starts waiting, and takes each
 * down when it stops: answered here, at the Mac, or gone with its engine.
 *
 * It lets go when the turn ends; when nothing has arrived from the Mac for
 * [WatchPolicy.GRACE_MS] however many times the stream has been dialled again — a relaunch
 * or a change of network is a few seconds, not a reason to stop; when the Mac says this
 * phone is no longer paired, which no retry can mend; when the person dismisses it; or when
 * the app comes back to the front, where the Agents tab takes over.
 *
 * `foregroundServiceType="remoteMessaging"` on Android 14 and later: this is the phone
 * carrying on a conversation that lives on another device — the agent's requests arrive
 * here, the person's answers go back — which is the use that type exists for. It is not
 * `dataSync`, which is for moving files and has a daily budget on Android 15, and it needs
 * no runtime permission beyond the manifest's.
 */
class AgentWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifier by lazy { AgentNotifier(this) }

    private var watched: Set<String> = emptySet()
    private var watch: Job? = null
    private var finished = false

    /** The loop itself, while one runs. */
    private var loop: AgentWatch? = null

    /** The newest start this instance was handed, and its start id. */
    private var handled = 0L
    private var lastStartId = 0

    /** Where the loop's notifications go. */
    private val sink = object : WatchSink {
        override fun post(notice: ApprovalNotice) = notifier.post(notice)

        override fun cancel(notificationID: Int) = notifier.cancel(notificationID)

        override fun waiting(count: Int, changed: Boolean) {
            notifier.showWatching(watched, count, dismissIntent(this@AgentWatchService))
            if (changed) {
                SnapshotStore(this@AgentWatchService).notePendingApprovals(count)
                BuddyTileService.refresh(this@AgentWatchService)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Approval notifications a watcher left behind when its process died have nothing
        // behind their buttons any more; this one will post what is waiting now.
        notifier.cancelApprovals()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> finish(WatchEnding.AppReturned)
            ACTION_WATCH -> {
                handled = intent.getLongExtra(EXTRA_GENERATION, 0L)
                if (finished) reopen()
                val engines = intent.getStringArrayExtra(EXTRA_ENGINES)
                    ?.mapNotNull { knownEngine(it) }?.toSet().orEmpty()
                watched = watched + engines
                loop?.watch(engines)
                // Foreground from the first moment: a service that waits for its own
                // coroutine to call this is a crash on Android 12 and later.
                startInForeground()
                if (watched.isEmpty()) {
                    finish(WatchEnding.Dismissed)
                } else if (watch == null) {
                    watch = scope.launch { run() }
                }
            }
            ACTION_DISMISS -> finish(WatchEnding.Dismissed)
            else -> if (watch == null) stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun reopen() {
        finished = false
        loop = null
    }

    private fun startInForeground() {
        val notification = notifier.watching(watched, loop?.waiting ?: 0, dismissIntent(this))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AgentNotifications.WATCH_NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            )
        } else {
            startForeground(AgentNotifications.WATCH_NOTIFICATION, notification)
        }
    }

    private suspend fun run() {
        val config = TokenStore(applicationContext).load()
        if (config == null || !config.canControl) {
            finish(WatchEnding.Dismissed)
            return
        }
        val current = AgentWatch(ControlClient(config), watched, sink, Build.VERSION.SDK_INT)
        loop = current
        finish(current.run())
    }

    private fun finish(ending: WatchEnding) {
        if (finished) {
            stopSelfResult(lastStartId)
            return
        }
        finished = true
        val job = watch
        watch = null
        // Stale buttons are worse than none: nothing here would take them down once it
        // stops listening.
        loop?.close()
        loop = null
        if (watched.isNotEmpty()) notifier.ended(watched, ending)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        // A start that arrived after this one keeps the service: `stopSelfResult` only stops
        // it when the last start it was handed is the one being ended.
        if (stopSelfResult(lastStartId) && handled == generation) requested = false
        job?.cancel()
    }

    override fun onDestroy() {
        loop?.close()
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_WATCH = "dev.siliconoptimizer.buddy.agents.WATCH"
        const val ACTION_STOP = "dev.siliconoptimizer.buddy.agents.STOP_WATCHING"
        const val ACTION_DISMISS = "dev.siliconoptimizer.buddy.agents.DISMISS_WATCHING"
        const val EXTRA_ENGINES = "dev.siliconoptimizer.buddy.agents.ENGINES"
        const val EXTRA_GENERATION = "dev.siliconoptimizer.buddy.agents.GENERATION"

        /** Whether a watch was asked for and not yet called off, in this process. */
        @Volatile
        private var requested = false

        /**
         * Bumped with every start. An instance that ends clears [requested] only when the
         * start it handled is still the newest — otherwise its end would erase a start made
         * after it, and the app, back in front, would never tell that one to stop.
         */
        @Volatile
        private var generation = 0L

        /**
         * Starts watching [engines]. Called as the app leaves the foreground — one of the
         * moments Android still lets an app start a foreground service — and a refusal is
         * swallowed: the turn carries on on the Mac either way, and the app shows it on
         * return.
         */
        fun start(context: Context, engines: Collection<String>) {
            val known = engines.mapNotNull { knownEngine(it) }
            if (known.isEmpty()) return
            generation += 1
            val intent = Intent(context, AgentWatchService::class.java)
                .setAction(ACTION_WATCH)
                .putExtra(EXTRA_ENGINES, known.toTypedArray())
                .putExtra(EXTRA_GENERATION, generation)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onSuccess { requested = true }
                .onFailure {
                    android.util.Log.w(ControlClient.LOG, "agent watch not started: ${it.javaClass.simpleName}")
                }
        }

        /**
         * The app is back in front; the Agents tab takes over.
         *
         * Asked of the service as a command rather than with `stopService`: a quick trip
         * to the home screen and back can land here before the service has run its first
         * command, and a service started with `startForegroundService` that is brought down
         * before it has called `startForeground` takes the app down with it. Commands
         * arrive in order, so this one always follows the start it undoes.
         */
        fun stop(context: Context) {
            if (!requested) return
            requested = false
            runCatching {
                context.startService(Intent(context, AgentWatchService::class.java).setAction(ACTION_STOP))
            }
        }

        /** The notification's own way out: its button, and swiping it away. */
        fun dismissIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 0,
            Intent(context, AgentWatchService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Accept or Decline, pressed on an approval notification.
 *
 * Only ever reached on Android 12 and later, from actions built with
 * `setAuthenticationRequired(true)`. That flag is enforced by the system's own UI, not by the
 * intent: a notification listener or a watch bridge can send the same intent on a locked
 * phone. So the lock is checked here as well, and a locked phone answers nothing. Not
 * exported: nothing outside this app can address it. The answer goes to the Mac once and
 * the notification says what came of it, in one of [ApprovalReplies]' fixed sentences.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val answer = ShadeAnswer.from(
            intent.getStringExtra(EXTRA_ENGINE),
            intent.getStringExtra(EXTRA_APPROVAL),
            intent.getStringExtra(EXTRA_DECISION),
        ) ?: return
        val app = context.applicationContext
        val notifier = AgentNotifier(app)
        val locked = app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val transport = if (locked) null else TokenStore(app).load()?.let { ControlClient(it) }
                val reply = answerFromShade(answer, locked, transport)
                val text = reply.text
                if (text == null) {
                    notifier.cancel(AgentNotifications.notificationID(answer.engine, answer.id))
                } else {
                    // Locked, the approval's own notification stays, buttons and all, for
                    // when the phone is unlocked; this only says why nothing happened.
                    notifier.settle(answer.engine, answer.id, text, keepApproval = reply.keepApproval)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.siliconoptimizer.buddy.agents.ANSWER"
        const val EXTRA_ENGINE = "dev.siliconoptimizer.buddy.agents.ENGINE"
        const val EXTRA_APPROVAL = "dev.siliconoptimizer.buddy.agents.APPROVAL"
        const val EXTRA_DECISION = "dev.siliconoptimizer.buddy.agents.DECISION"

        fun intent(context: Context, notice: ApprovalNotice, decision: String): PendingIntent {
            val intent = Intent(context, ApprovalActionReceiver::class.java)
                .setAction(ACTION_ANSWER)
                .putExtra(EXTRA_ENGINE, notice.engine)
                .putExtra(EXTRA_APPROVAL, notice.approvalID)
                .putExtra(EXTRA_DECISION, decision)
            // One request code per approval and decision, so Accept never carries
            // Decline's extras.
            val code = notice.notificationID * 2 + if (decision == AgentApprovalDecision.ACCEPT) 1 else 0
            return PendingIntent.getBroadcast(
                context, code, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
