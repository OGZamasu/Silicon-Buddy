package dev.siliconoptimizer.buddy.agents

import dev.siliconoptimizer.buddy.transport.TransportError

/** Why the background watcher let go. Only some of these are worth a notification. */
enum class WatchEnding {
    /** The turn ended: there is nothing left to ask about. */
    TurnEnded,

    /** Stop watching was tapped, or the notification swiped away. */
    Dismissed,

    /** The app came back to the front; the Agents tab has it now. */
    AppReturned,

    /** The Mac has not been heard from for longer than the grace. */
    LostTouch,

    /** 401: the Mac no longer knows this phone. Retrying cannot help. */
    Unpaired,

    /** 403: this phone may no longer follow agents on that Mac. */
    NotAllowed,
}

/**
 * When the background watcher stops believing in its Mac.
 *
 * A stream that drops is not a Mac that has gone: a Mac relaunching, a phone moving from
 * Wi-Fi to cellular, a tailnet that re-routes all drop it for a few seconds, and the client
 * is already dialling again with its growing delay. So one failed reconnect is not the end;
 * [GRACE_MS] without a single live frame is. Pure, so the rule is a test rather than a hope.
 */
class WatchPolicy(start: Long, private val graceMs: Long = GRACE_MS) {

    /** When the stream last delivered anything — a frame, a heartbeat. */
    var lastLive: Long = start
        private set

    /** True from a drop until the stream delivers again. */
    var down: Boolean = false
        private set

    fun live(now: Long) {
        lastLive = now
        down = false
    }

    fun dropped() {
        down = true
    }

    /** Down, and for longer than the grace since anything arrived. */
    fun givesUp(now: Long): Boolean = down && now - lastLive >= graceMs

    companion object {
        /** About a minute: enough for a Mac to relaunch or a phone to change networks. */
        const val GRACE_MS = 60_000L

        /** How often a watcher that is down checks the grace, between reconnects. */
        const val CHECK_EVERY_MS = 5_000L

        /** How an error ends the watch: retrying cannot mend a revoked or narrowed pairing. */
        fun ending(error: TransportError): WatchEnding = when (error) {
            is TransportError.Unauthorized -> WatchEnding.Unpaired
            is TransportError.Forbidden -> WatchEnding.NotAllowed
            else -> WatchEnding.LostTouch
        }

        /** What the last notification says, or nothing for an ending that needs no word. */
        fun sentence(ending: WatchEnding): String? = when (ending) {
            WatchEnding.LostTouch -> "Your Mac stopped answering. Open Silicon Buddy to check on it."
            WatchEnding.Unpaired ->
                "This phone is no longer paired with your Mac. Open Silicon Buddy to pair it again."
            WatchEnding.NotAllowed ->
                "This phone may no longer follow agents on your Mac. Pair it again with full control."
            WatchEnding.TurnEnded, WatchEnding.Dismissed, WatchEnding.AppReturned -> null
        }
    }
}
