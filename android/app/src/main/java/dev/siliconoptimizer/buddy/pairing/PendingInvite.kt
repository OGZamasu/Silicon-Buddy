package dev.siliconoptimizer.buddy.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle

/**
 * The invite "Pair with this Mac?" is asking about, kept in the activity's saved state as
 * well as in memory.
 *
 * A link is read once: an activity recreated after its process died is handed the intent it
 * was opened with, and does not read it again, or a spent code would come back. So an invite
 * that lived only in the view model died with the process — Android kills a backgrounded app
 * when it wants the memory — and the person came back to no question at all. Saved, the
 * question comes back.
 *
 * Only an invite nobody has answered is saved. Declining it, or starting to spend it, takes
 * it out, so a code that was refused or spent is never offered again, after a restart or
 * otherwise. What comes back is parsed again, by the same strict rules as a scanned code.
 */
class PendingInvite(private val saved: SavedStateHandle) {

    var invite by mutableStateOf(restore())
        private set

    /**
     * Invites this process has had an answer to: declined, or handed to the Mac — whatever
     * the Mac then said. The same link can come back — tapped twice, or redelivered by the
     * app that sent it — while its code is being spent or just after it was refused. It is
     * asked about again on screen, since somebody opened it, but it is not saved again: a
     * refused code must not come back after a restart.
     */
    private val answered = mutableSetOf<PairingInvite>()

    /** Asks about [invite], or stops asking when it is null, in memory and in saved state. */
    fun offer(invite: PairingInvite?) {
        this.invite = invite
        if (invite == null || invite in answered) {
            saved.remove<String>(KEY)
        } else {
            saved[KEY] = invite.toUriString()
        }
    }

    /** "Not now": the question is answered, and goes. */
    fun decline() {
        invite?.let { answered += it }
        offer(null)
    }

    /**
     * [spent] is being spent. Still asked about on screen while the Mac answers — and after
     * a refusal, so it can be tried again — but no longer saved: whatever the Mac did with the
     * code, offering it again after a restart would be offering a code that may be spent.
     */
    fun spending(spent: PairingInvite) {
        answered += spent
        if (invite == spent) saved.remove<String>(KEY)
    }

    private fun restore(): PairingInvite? =
        saved.get<String>(KEY)?.let { runCatching { PairingInvite.parse(it) }.getOrNull() }

    companion object {
        const val KEY = "dev.siliconoptimizer.buddy.PENDING_INVITE"
    }
}
