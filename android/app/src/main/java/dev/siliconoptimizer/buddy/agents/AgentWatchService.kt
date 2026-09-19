package dev.siliconoptimizer.buddy.agents

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
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * An agent turn, kept in view while the app is not.
 *
 * Started when the app leaves the foreground with a turn running in a session this phone
 * has opened. It opens `/events` for itself — the app's own stream belongs to a screen that
 * Android may take away — reads the watched sessions whole so an approval asked for a moment
 * ago still rings, and then posts one notification per approval that starts waiting, and
 * takes each down when it stops: answered here, at the Mac, or gone with its engine.
 *
 * It lets go for exactly four reasons: the turn ended; the stream went silent past the
 * 45-second heartbeat grace and the next attempt could not bring it back; the person
 * dismissed it; or the app came back to the front, where the Agents tab takes over.
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
    private var board = AgentBoard.empty
    private var watch: Job? = null

    /** Approval ids this service has put a notification up for. Never put up twice. */
    private val posted = mutableSetOf<String>()

    /** Of those, the ones still showing. */
    private val showing = mutableMapOf<String, Int>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finish(lostTouch = false)
                return START_NOT_STICKY
            }
            ACTION_WATCH -> {
                val engines = intent.getStringArrayExtra(EXTRA_ENGINES)?.toSet().orEmpty()
                watched = watched + engines
                // Foreground from the first moment: a service that waits for its own
                // coroutine to call this is a crash on Android 12 and later.
                startInForeground()
                if (watched.isEmpty()) {
                    finish(lostTouch = false)
                } else if (watch == null) {
                    watch = scope.launch { run() }
                }
            }
            else -> if (watch == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val notification = notifier.watching(watched, waiting(), stopIntent(this))
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
            finish(lostTouch = false)
            return
        }
        val client = ControlClient(config)
        try {
            // Read whole first. The app may have been put away a second after an approval
            // arrived, and that one has had no notification yet.
            watched.forEach { sync(client, it) }
            if (!anyTurn()) {
                finish(lostTouch = false)
                return
            }
            var down = false
            client.events().collect { event ->
                when (event) {
                    is ServerEvent.Disconnected -> {
                        // The first drop may be a blip — a 45-second silence, a tailnet
                        // hiccup — and the client is already dialling again. A second one
                        // with nothing live in between is a Mac that cannot be reached.
                        if (down) throw LostTouch()
                        down = true
                        board = board.streamBroken()
                    }
                    is ServerEvent.Resync -> {
                        // Frames were dropped for this phone: fetch what they said.
                        board = board.streamBroken()
                        watched.forEach { sync(client, it) }
                    }
                    else -> {
                        if (down) {
                            down = false
                            // Whatever happened while it was down was missed.
                            watched.forEach { sync(client, it) }
                        }
                        if (event is ServerEvent.Agent && event.event.engine in watched) {
                            board = board.applying(event.event)
                            val engine = event.event.engine
                            if (board.session(engine).needs != Sync.None) sync(client, engine)
                            refreshNotifications()
                            if (!anyTurn()) {
                                finish(lostTouch = false)
                                throw Finished()
                            }
                        }
                    }
                }
            }
        } catch (lost: LostTouch) {
            finish(lostTouch = true)
        } catch (done: Finished) {
            // Said and done.
        } catch (error: TransportError) {
            // Unauthorized, or a Mac without `/events`: nothing here will get better.
            finish(lostTouch = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun sync(client: ControlTransport, engine: String) {
        val cursor = board.session(engine).cursor
        board = board.updating(engine) { it.syncing() }
        try {
            val detail = client.agentSession(engine, cursor?.since, cursor?.epoch)
            board = board.updating(engine) { it.applying(detail) }
        } catch (error: TransportError) {
            if (error is TransportError.Unauthorized || error is TransportError.Forbidden) throw error
            // A read that failed while the stream is up is not worth stopping for; the
            // next frame or the next reconnect asks again.
        }
        refreshNotifications()
    }

    private fun anyTurn(): Boolean = watched.any { board.session(it).turnActive }

    private fun waiting(): Int = watched.sumOf { board.session(it).pending.size }

    /**
     * One notification per approval waiting in a watched session: up once when it starts
     * waiting, down when it stops. An id that has been put up is never put up again — the
     * person may have swiped it away, or answered it from the shade a moment before the
     * frame saying so arrived.
     */
    private fun refreshNotifications() {
        val notices = AgentNotifications.notices(board, watched, Build.VERSION.SDK_INT)
        val waitingNow = notices.map { it.approvalID }.toSet()
        for (notice in notices) {
            if (notice.approvalID in posted) continue
            posted += notice.approvalID
            showing[notice.approvalID] = notice.notificationID
            notifier.post(notice)
        }
        for ((id, notificationID) in showing.toMap()) {
            if (id !in waitingNow) {
                showing.remove(id)
                notifier.cancel(notificationID)
            }
        }
        notifier.showWatching(watched, waiting(), stopIntent(this))
        val count = waiting()
        if (count != lastCount) {
            lastCount = count
            SnapshotStore(this).notePendingApprovals(count)
            BuddyTileService.refresh(this)
        }
    }

    private var lastCount = -1

    private fun finish(lostTouch: Boolean) {
        watch?.cancel()
        watch = null
        // Stale buttons are worse than none: nothing here would take them down once it
        // stops listening.
        showing.values.forEach { notifier.cancel(it) }
        showing.clear()
        if (lostTouch && watched.isNotEmpty()) notifier.lostTouch(watched)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        requested = false
        showing.values.forEach { notifier.cancel(it) }
        showing.clear()
        scope.cancel()
        super.onDestroy()
    }

    private class LostTouch : Exception()
    private class Finished : Exception()

    companion object {
        const val ACTION_WATCH = "dev.siliconoptimizer.buddy.agents.WATCH"
        const val ACTION_STOP = "dev.siliconoptimizer.buddy.agents.STOP_WATCHING"
        const val EXTRA_ENGINES = "dev.siliconoptimizer.buddy.agents.ENGINES"

        /**
         * Starts watching [engines]. Called as the app leaves the foreground — one of the
         * moments Android still lets an app start a foreground service — and a refusal is
         * swallowed: the turn carries on on the Mac either way, and the app shows it on
         * return.
         */
        fun start(context: Context, engines: Collection<String>) {
            if (engines.isEmpty()) return
            val intent = Intent(context, AgentWatchService::class.java)
                .setAction(ACTION_WATCH)
                .putExtra(EXTRA_ENGINES, engines.toTypedArray())
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

        /** Whether a watch was asked for and not yet called off, in this process. */
        @Volatile
        private var requested = false

        fun stopIntent(context: Context): PendingIntent = PendingIntent.getService(
            context, 0,
            Intent(context, AgentWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/**
 * Accept or Decline, pressed on an approval notification.
 *
 * Only ever reached on Android 12 and later, and only after the system has asked for the
 * owner's fingerprint or PIN — the action is built with `setAuthenticationRequired(true)`.
 * Not exported: nothing outside this app can send it. The answer goes to the Mac once and
 * the notification says what came of it, in the Mac's own words when there are any.
 */
class ApprovalActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_ANSWER) return
        val engine = intent.getStringExtra(EXTRA_ENGINE) ?: return
        val id = intent.getStringExtra(EXTRA_APPROVAL) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION)
            ?.takeIf { it == AgentApprovalDecision.ACCEPT || it == AgentApprovalDecision.DECLINE }
            ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val notifier = AgentNotifier(app)
            try {
                val config = TokenStore(app).load()
                if (config == null) {
                    notifier.settle(engine, id, "No Mac is paired any more.")
                    return@launch
                }
                val result = withTimeout(ANSWER_TIMEOUT_MS) {
                    ControlClient(config).answerAgentApproval(engine, id, decision)
                }
                notifier.settle(
                    engine, id,
                    if (result.decision == "accepted") "Accepted on this phone." else "Declined on this phone.",
                )
            } catch (gone: TransportError.NotFound) {
                notifier.cancel(AgentNotifications.notificationID(engine, id))
            } catch (conflict: TransportError.Conflict) {
                notifier.settle(engine, id, conflict.message ?: AgentNotices.ANSWERED_ON_THE_MAC)
            } catch (error: TransportError) {
                notifier.settle(
                    engine, id,
                    "Not sent — ${error.message} Open Silicon Buddy to answer it there.",
                )
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                notifier.settle(
                    engine, id,
                    "Your Mac took too long to answer. Open Silicon Buddy to check.",
                )
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

        /** Inside the ten seconds a receiver holding `goAsync` is given. */
        const val ANSWER_TIMEOUT_MS = 9_000L

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
