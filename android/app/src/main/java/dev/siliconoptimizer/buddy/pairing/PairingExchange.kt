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
 */
class PairingExchange(private val scope: CoroutineScope) {

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

    var state by mutableStateOf<State>(State.Idle)
        private set

    val isWorking: Boolean get() = state is State.Working

    /**
     * Spends [invite] with [exchange] and hands what the Mac answered to [store]. False, with
     * nothing dialled, while another code is still being spent: one pairing at a time.
     */
    fun start(
        invite: PairingInvite,
        exchange: suspend (PairingInvite) -> ServerConfig,
        store: (ServerConfig) -> Unit,
    ): Boolean {
        if (state is State.Working) return false
        state = State.Working(invite)
        scope.launch {
            // The scope is AppState's, and it ends when the activity finishes — Back out of
            // the app while the Mac thinks. A request cut off there is as likely to have been
            // answered as one cut off by a closing sheet, so it is not cut off.
            withContext(NonCancellable) {
                state = try {
                    store(exchange(invite))
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
