package dev.siliconoptimizer.buddy

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.siliconoptimizer.buddy.pairing.PairingInvite
import dev.siliconoptimizer.buddy.pairing.TokenStore
import dev.siliconoptimizer.buddy.transport.ConnectivityProbe
import dev.siliconoptimizer.buddy.transport.ControlClient
import dev.siliconoptimizer.buddy.transport.ControlTransport
import dev.siliconoptimizer.buddy.transport.Reachability
import dev.siliconoptimizer.buddy.transport.ServerConfig
import dev.siliconoptimizer.buddy.transport.Status
import kotlinx.coroutines.launch

/**
 * The one piece of state every screen needs: which Mac we are talking to, whether it is
 * answering, and which of the newer routes it turned out to have.
 */
class AppState(application: Application) : AndroidViewModel(application) {

    private val tokens = TokenStore(application)

    var config by mutableStateOf<ServerConfig?>(tokens.load())
        private set
    var reachability by mutableStateOf<Reachability>(Reachability.Unknown)
        private set
    var status by mutableStateOf<Status?>(null)
        private set

    /** Which M0 routes this Mac turned out to have. Discovered by using them. */
    var supportsStreaming by mutableStateOf(true)
        internal set
    var supportsRemoteConversations by mutableStateOf(false)
        internal set

    val isPaired: Boolean get() = config != null

    /** The client for the paired Mac, or null when there is none. */
    val transport: ControlTransport?
        get() = config?.let { ControlClient(it) }

    val macDisplayName: String
        get() = config?.macName ?: config?.host ?: "No Mac"

    val tokenIsEncrypted: Boolean get() = tokens.isEncrypted

    // MARK: - Pairing

    /**
     * Trades a scanned invite for a per-device token. Throws
     * `TransportError.RouteUnavailable` on a Mac that has not shipped `/buddy/pair`, so
     * the caller can offer the advanced form instead.
     */
    suspend fun pair(invite: PairingInvite) {
        val probe = ControlClient(ServerConfig(invite.host, invite.port, token = ""))
        val paired = probe.pair(invite.code, deviceName, platform)
        connect(
            ServerConfig(
                host = invite.host,
                port = paired.port,
                token = paired.token,
                macName = paired.macName,
                deviceID = paired.deviceID,
            ),
        )
        refreshReachability()
    }

    /** The advanced form: host, port and the token from the Mac's control.json. */
    fun connect(newConfig: ServerConfig) {
        tokens.save(newConfig)
        config = newConfig
        reachability = Reachability.Unknown
    }

    fun forget() {
        tokens.forget()
        config = null
        status = null
        reachability = Reachability.Unknown
    }

    fun noteMacName(name: String) {
        val current = config ?: return
        if (current.macName == name) return
        tokens.noteMacName(name)
        config = current.copy(macName = name)
    }

    // MARK: - Connection state

    fun refreshReachability() {
        val client = transport ?: run {
            reachability = Reachability.Unknown
            return
        }
        viewModelScope.launch {
            reachability = Reachability.Checking
            val result = ConnectivityProbe(client).check()
            reachability = result
            if (result.isReady) {
                status = runCatching { client.status() }.getOrNull()
            }
        }
    }

    companion object {
        /** What this device calls itself when it asks the Mac to pair. */
        val deviceName: String
            get() = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android device" }

        const val platform = "android"
    }
}
