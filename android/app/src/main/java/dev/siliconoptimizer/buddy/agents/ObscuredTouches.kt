package dev.siliconoptimizer.buddy.agents

import android.view.MotionEvent

/**
 * Tapjacking: another app drawing over this one to steer a tap onto Accept.
 *
 * Android marks a touch that went through somebody else's window at the point touched —
 * `FLAG_WINDOW_IS_OBSCURED` — and the button refuses that press. Only that: a window that
 * merely overlaps some *other* part of this one (a picture-in-picture call, a chat head in a
 * corner) sets `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`, which says nothing about the button, and
 * refusing it would lock out anyone with a video call open.
 *
 * Each press is judged once. A refused press is forgotten as it is refused, so the next
 * answer — from a keyboard, switch access or TalkBack, none of which are touches — is not
 * held to a touch it never made.
 */
class ObscuredTouches {

    private var obscured = false

    /** A touch on a guarded button. Its first contact decides the press. */
    fun touched(action: Int, flags: Int) {
        if (action == MotionEvent.ACTION_DOWN) obscured = (flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED) != 0
    }

    /** Whether the press now being acted on came through another window. Asked once per press. */
    fun take(): Boolean {
        val was = obscured
        obscured = false
        return was
    }
}
