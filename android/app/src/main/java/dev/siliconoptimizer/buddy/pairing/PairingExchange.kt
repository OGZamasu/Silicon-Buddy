package dev.siliconoptimizer.buddy.pairing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.TransportError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pairing code being spent, owned by `AppState` rather than by the sheet or the dialog
 * that asked for it.
 *
 * `POST /buddy/pair` spends the code and makes this phone a device on the Mac. The sheet
 * used to run it in its own coroutine scope, so closing the sheet mid-request cancelled it —
 * and a request cut off after it was sent is one the Mac may already have answered: the code
 * spent, a device record made, and its token dropped by a phone that then says it isn't
 * paired. Here the exchange runs to its end whatever becomes of the screen that started it,
 * or of the activity, and what it brings back is stored. A failure that no screen is left to
 * show stays in [state] until one has shown it.
 *
 * The app keeps one for the whole process (`AppState`'s companion), not one per view model:
 * Back out of the app mid-exchange and open it again, and the new view model is the one
 * that has to see the code still being spent, and take its answer — see [attach].
 */
class PairingExchange(private val scope: CoroutineScope) {

    /** Takes what the Mac answered for an invite: stores it, and shows it. */
    fun interface Lander {
        fun land(invite: PairingInvite, config: ServerConfig)
    }

    sealed interface State {
        data object Idle : State
        data class Working(val invite: PairingInvite) : State

        /** [message] is for the person; [macTooOld] is a Mac without `/buddy/pair`. */
        data class Failed(
            val invite: PairingInvite,
            val message: String,
            val macTooOld: Boolean,
        ) : State
    }

    /** What a dialog asking about one invite shows of the exchange. */
    sealed interface Phase {
        /** Nothing is being spent: this invite may go. */
        data object Ready : Phase

        /** This invite is being spent. */
        data object Spending : Phase

        /** Another code is being spent, and this one waits for it. Declining is still fine. */
        data class Waiting(val other: PairingInvite) : Phase

        /** This invite was refused, and may be tried again. */
        data class Failed(val message: String, val macTooOld: Boolean) : Phase

        /** Another code was refused, and nothing has said so yet. This one may go. */
        data class OtherFailed(val other: PairingInvite, val message: String) : Phase
    }

    var state by mutableStateOf<State>(State.Idle)
        private set

    /** The screen state an answer lands on: whichever is current when it lands. */
    private var lander: Lander? = null

    /**
     * [lander] is the one on screen now, and takes whatever lands from here on — an answer
     * to a code an earlier one started included. An app backed out of while the Mac thought,
     * and opened again, has a new view model that read the token store before the answer
     * was in it; it said "not paired" until the next restart, though the token was stored.
     */
    fun attach(lander: Lander) {
        this.lander = lander
    }

    /** [lander] has gone. Its answers land on the next one to attach, or on the one that asked. */
    fun detach(lander: Lander) {
        if (this.lander === lander) this.lander = null
    }

    val isWorking: Boolean get() = state is State.Working

    fun phaseOf(invite: PairingInvite): Phase = when (val now = state) {
        State.Idle -> Phase.Ready
        is State.Working -> if (now.invite == invite) Phase.Spending else Phase.Waiting(now.invite)
        is State.Failed ->
            if (now.invite == invite) Phase.Failed(now.message, now.macTooOld)
            else Phase.OtherFailed(now.invite, now.message)
    }

    /**
     * Spends [invite] with [exchange] and hands what the Mac answered to the attached lander —
     * to [store], the one that asked, when none is attached, which still writes the token
     * where the next one to start will read it. False, with nothing dialled, while another
     * code is still being spent: one pairing at a time.
     */
    fun start(
        invite: PairingInvite,
        exchange: suspend (PairingInvite) -> ServerConfig,
        store: Lander,
    ): Boolean {
        if (state is State.Working) return false
        state = State.Working(invite)
        scope.launch {
            // The scope is AppState's, and it ends when the activity finishes — Back out of
            // the app while the Mac thinks. A request cut off there is as likely to have been
            // answered as one cut off by a closing sheet, so it is not cut off.
            withContext(NonCancellable) {
                state = try {
                    val answer = exchange(invite)
                    (lander ?: store).land(invite, answer)
                    State.Idle
                } catch (error: TransportError) {
                    State.Failed(
                        invite,
                        if (error.isMissingRoute) MAC_TOO_OLD_FOR_CODES
                        else error.message ?: "Pairing didn't finish.",
                        macTooOld = error.isMissingRoute,
                    )
                }
            }
        }
        return true
    }

    /** A failure has been shown to the person, so it need not be shown again. */
    fun acknowledge() {
        if (state is State.Failed) state = State.Idle
    }
}
