package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.ServerEvent
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the watch asks of Android: notifications up, notifications down, and a count. */
interface WatchSink {
    /** An approval started waiting: its notification goes up. */
    fun post(notice: ApprovalNotice)

    /** It stopped waiting, or the watch ended: its notification comes down. */
    fun cancel(notificationID: Int)

    /** How many approvals wait now, for the watcher's own notification; [changed] since last time. */
    fun waiting(count: Int, changed: Boolean)
}

/**
 * The background watcher's loop, apart from the service that hosts it.
 *
 * Reads the watched sessions whole, follows `/events`, rings once per approval that starts
 * waiting and takes it down when it stops, and ends — saying why — when the turn is over,
 * when nothing has come from the Mac for [WatchPolicy.GRACE_MS] however often the stream
 * has been dialled again, or when the Mac refuses this phone. Android's part goes through
 * [WatchSink]; everything that decides is here, where a test can drive it.
 */
class AgentWatch(
    private val transport: ControlTransport,
    engines: Set<String>,
    private val sink: WatchSink,
    private val sdk: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var watched: Set<String> = engines
        private set

    var board: AgentBoard = AgentBoard.empty
        private set

    /** Approval ids this watch has put a notification up for. Never put up twice. */
    private val posted = mutableSetOf<String>()

    /** Of those, the ones still showing, and their notifications. */
    private val showing = mutableMapOf<String, Int>()

    private var lastCount = -1
    private var closed = false

    /** Approvals waiting in the watched sessions. */
    val waiting: Int get() = watched.sumOf { board.session(it).pending.size }

    /** A later start, naming more engines. */
    fun watch(engines: Set<String>) {
        watched = watched + engines
    }

    /** Watches until there is nothing left to watch; answers why it stopped. */
    suspend fun run(): WatchEnding {
        val policy = WatchPolicy(clock())
        return try {
            // Read whole first. The app may have been put away a second after an approval
            // arrived, and that one has had no notification yet.
            watched.forEach { sync(it) }
            if (!anyTurn()) {
                WatchEnding.TurnEnded
            } else {
                coroutineScope {
                    // Between reconnects the stream says nothing at all, so the grace is
                    // also checked on a clock rather than only when a drop is reported.
                    val watchdog = launch {
                        while (true) {
                            delay(WatchPolicy.CHECK_EVERY_MS)
                            if (policy.givesUp(clock())) throw LostTouch()
                        }
                    }
                    try {
                        follow(policy)
                    } finally {
                        watchdog.cancel()
                    }
                }
            }
        } catch (lost: LostTouch) {
            WatchEnding.LostTouch
        } catch (error: TransportError) {
            WatchPolicy.ending(error)
        }
    }

    /**
     * The watch is over: every approval notification it put up comes down — nothing would
     * take a stale one down once it stops listening — and nothing goes up after.
     */
    fun close() {
        closed = true
        showing.values.forEach { sink.cancel(it) }
        showing.clear()
    }

    /** Follows the stream until the turn ends; throws when it gives up. */
    private suspend fun follow(policy: WatchPolicy): WatchEnding = try {
        transport.events().collect { event ->
            val now = clock()
            when (event) {
                is ServerEvent.Disconnected -> {
                    // A drop is not an ending: the client is already dialling again, with a
                    // growing delay. Only the grace running out is.
                    policy.dropped()
                    board = board.streamBroken()
                    if (policy.givesUp(now)) throw LostTouch()
                }
                is ServerEvent.Resync -> {
                    // Frames were dropped for this phone: fetch what they said.
                    policy.live(now)
                    board = board.streamBroken()
                    watched.forEach { sync(it) }
                    if (!anyTurn()) throw Finished()
                }
                else -> {
                    val wasDown = policy.down
                    policy.live(now)
                    if (wasDown) {
                        // Whatever happened while it was down was missed — the turn may
                        // have ended in it.
                        watched.forEach { sync(it) }
                        if (!anyTurn()) throw Finished()
                    }
                    if (event is ServerEvent.Agent && event.event.engine in watched) {
                        val engine = event.event.engine
                        board = board.updating(engine) { it.remembering(AgentAnswers.of(engine)) }
                            .applying(event.event)
                        if (board.session(engine).needs != Sync.None) sync(engine)
                        refresh()
                        if (!anyTurn()) throw Finished()
                    }
                }
            }
        }
        // The stream only ends when the route is gone, which the client throws for.
        WatchEnding.LostTouch
    } catch (done: Finished) {
        WatchEnding.TurnEnded
    }

    private suspend fun sync(engine: String) {
        val cursor = board.session(engine).cursor
        board = board.updating(engine) { it.syncing() }
        try {
            val detail = transport.agentSession(engine, cursor?.since, cursor?.epoch)
            board = board.updating(engine) { it.remembering(AgentAnswers.of(engine)).applying(detail) }
        } catch (error: TransportError) {
            // A revoked or narrowed pairing ends the watch; retrying cannot mend it.
            if (error is TransportError.Unauthorized || error is TransportError.Forbidden) throw error
            // Anything else is not worth stopping for while the stream is up; the next
            // frame or the next reconnect asks again.
            board = board.updating(engine) { it.needing(Sync.CatchUp) }
        }
        refresh()
    }

    private fun anyTurn(): Boolean = watched.any { board.session(it).turnActive }

    /**
     * One notification per approval waiting in a watched session: up once when it starts
     * waiting, down when it stops. An id that has been put up is never put up again — the
     * person may have swiped it away, or answered it from the shade a moment before the
     * frame saying so arrived.
     */
    private fun refresh() {
        if (closed) return
        val notices = AgentNotifications.notices(board, watched, sdk)
        val waitingNow = notices.map { it.approvalID }.toSet()
        for (notice in notices) {
            if (notice.approvalID in posted) continue
            posted += notice.approvalID
            showing[notice.approvalID] = notice.notificationID
            sink.post(notice)
        }
        for ((id, notificationID) in showing.toMap()) {
            if (id !in waitingNow) {
                showing.remove(id)
                sink.cancel(notificationID)
            }
        }
        val count = waiting
        sink.waiting(count, changed = count != lastCount)
        lastCount = count
    }

    private class LostTouch : Exception()
    private class Finished : Exception()
}
